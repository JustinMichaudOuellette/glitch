package ca.justinmo.glitch.jpeg

/**
 * Injects JPEG restart markers into a baseline scan so that glitch damage can be
 * *bounded*, not just aimed.
 *
 * ## Why the UI wants this
 *
 * A corrupt byte desyncs the Huffman decoder at the MCU it lands in, and with no
 * restart markers there is nothing to resynchronise at - so the damage runs to
 * the bottom of the image. [ScanGeometry] can therefore only choose where the
 * damage *starts*. Measured on `b.jpg`, every one of 15 corrupt bytes reached
 * row 1.00 of the image.
 *
 * A restart marker (RST0-RST7) tells the decoder to resynchronise, so damage is
 * confined to the remainder of that restart interval. Measured after injecting a
 * marker every 7812 MCUs (36 MCU rows) into `b.jpg`, six corrupt bytes produced
 * bands of 0.066-0.124, 0.194-0.249, 0.443-0.498, 0.578-0.623, 0.823-0.872 and
 * 0.941-0.997 - each inside its predicted window, instead of all reaching 1.00.
 *
 * ## Why it is a transcode and not two inserted bytes
 *
 * Markers cannot simply be spliced into the bitstream:
 *
 *  - A restart byte-aligns the entropy stream, so padding bits must be added.
 *  - The DC differential predictor resets to zero at every restart, so the DC of
 *    the first block in each interval must be re-coded as a difference from 0
 *    rather than from its predecessor. Conflating the decoder's predictor with
 *    the encoder's silently re-emits the original DC difference and shifts every
 *    DC value - which is exactly the bug the prototype hit before it was fixed.
 *
 * So the scan is Huffman-decoded and re-encoded. The transcode is lossless: the
 * absolute DC values and all AC coefficients are carried over unchanged, only
 * the differencing at interval starts and the bit alignment change. Verified
 * pixel-for-pixel, and with no restarts requested the entropy stream comes back
 * byte-for-byte identical to the original, which is asserted in JpegParserTest.
 *
 * ## Supported inputs
 *
 * Baseline (SOF0) files with a single interleaved sequential scan. Progressive
 * files are refused with a reason rather than mangled - their AC scans use EOB
 * runs and successive approximation, which this transcoder does not model.
 */
object JpegRestartInjector {

  /** Why injection is not possible for [structure], or null when it is. */
  fun unsupportedReason(structure: JpegStructure): String? {
    val frame = structure.frame ?: return "the frame header could not be read"
    if (frame.progressive) return "progressive JPEGs are not supported yet"
    if (structure.huffmanTables.isEmpty()) return "the file has no Huffman tables"
    if (structure.scans.size != 1) return "the file has ${structure.scans.size} scans"
    val scan = structure.scans.first()
    if (scan.components.size < 2) return "the scan is not interleaved"
    if (scan.isRefinement || scan.ss != 0 || scan.se != 63) {
      return "the scan is not a full sequential scan"
    }
    if (loadTables(structure) == null) return "the Huffman tables are incomplete"
    if (frame.mcusPerRow <= 0 || frame.mcuRows <= 0) return "the frame geometry is unusable"
    return null
  }

  /** True when [structure] already carries restart markers this app can use. */
  fun isInjectable(structure: JpegStructure): Boolean = unsupportedReason(structure) == null

  /**
   * MCU interval that makes each restart window span [windowFraction] of the
   * image height. Always at least one whole MCU row, so windows line up with
   * image rows, and never more MCUs than the frame has.
   */
  fun intervalForWindowFraction(frame: JpegFrame, windowFraction: Float): Int {
    val rowsPerWindow = (windowFraction * frame.mcuRows).toInt().coerceAtLeast(1)
    val interval = rowsPerWindow * frame.mcusPerRow
    val total = frame.mcusPerRow * frame.mcuRows
    return interval.coerceIn(frame.mcusPerRow, maxOf(frame.mcusPerRow, total))
  }

  /** Result of a successful injection. */
  class Result(
    val bytes: ByteArray,
    val intervalMcus: Int,
    val restartCount: Int,
    /** Fraction of image height each restart window spans. */
    val windowFraction: Float,
  )

  /**
   * Rewrite [data] with a DRI marker and an RST marker every [intervalMcus] MCUs.
   * Returns null when [unsupportedReason] is non-null or the stream cannot be
   * walked to completion (in which case [data] is left untouched).
   */
  fun inject(data: ByteArray, structure: JpegStructure, intervalMcus: Int): Result? {
    if (intervalMcus <= 0) return null
    if (unsupportedReason(structure) != null) return null
    val frame = structure.frame ?: return null
    val scan = structure.scans.first()
    val tables = loadTables(structure) ?: return null

    val byId = frame.components.associateBy { it.id }
    val comps = scan.components.map { sc ->
      val sampling = byId[sc.id] ?: return null
      ScanPlan(
        id = sc.id,
        h = sampling.h.coerceAtLeast(1),
        v = sampling.v.coerceAtLeast(1),
        dc = tables.dc[sc.dcTable] ?: return null,
        ac = tables.ac[sc.acTable] ?: return null,
      )
    }

    val totalMcus = frame.mcusPerRow * frame.mcuRows
    if (totalMcus <= 0) return null

    val reader = BitReader(data, scan.start, scan.endExclusive) ?: return null
    val writer = BitWriter()
    val absPred = HashMap<Int, Int>(comps.size)
    val encPred = HashMap<Int, Int>(comps.size)
    for (c in comps) {
      absPred[c.id] = 0
      encPred[c.id] = 0
    }
    var restartCount = 0
    var rstNum = 0

    for (mcu in 0 until totalMcus) {
      if (mcu > 0 && mcu % intervalMcus == 0) {
        writer.emitMarker(0xD0 + rstNum)
        rstNum = (rstNum + 1) and 7
        restartCount++
        for (c in comps) encPred[c.id] = 0
      }
      for (c in comps) {
        val blocks = c.h * c.v
        for (block in 0 until blocks) {
          if (!transcodeBlock(reader, writer, c, absPred, encPred)) return null
        }
      }
    }

    val body = writer.finish()

    // Rebuild: prefix, DRI, SOS marker + header, new entropy data, EOI, trailer.
    // b.jpg carries ~12.7 KB after its EOI (a second embedded image), so the
    // trailer must be carried over or that data is silently destroyed.
    val out = java.io.ByteArrayOutputStream(data.size + 16 + restartCount * 2)
    out.write(data, 0, scan.markerOffset)
    out.write(0xFF)
    out.write(0xDD) // DRI
    out.write(0x00)
    out.write(0x04)
    out.write((intervalMcus shr 8) and 0xFF)
    out.write(intervalMcus and 0xFF)
    out.write(data, scan.markerOffset, scan.start - scan.markerOffset) // SOS + header
    out.write(body)
    // Preserve whatever terminated the scan - normally the EOI marker - and any
    // trailer after it. b.jpg carries ~12.7 KB after its EOI (a second embedded
    // image), so writing our own EOI here would both duplicate the marker and
    // drop that data.
    if (scan.endExclusive + 1 < data.size) {
      out.write(data, scan.endExclusive, data.size - scan.endExclusive)
    } else {
      out.write(0xFF)
      out.write(0xD9) // source had no terminator: supply an EOI
    }

    val windowFraction = (intervalMcus / frame.mcusPerRow).toFloat() / frame.mcuRows
    return Result(out.toByteArray(), intervalMcus, restartCount, windowFraction)
  }

  /** Per-scan component plan: sampling factors and the tables it codes with. */
  private class ScanPlan(
    val id: Int,
    val h: Int,
    val v: Int,
    val dc: HuffmanCodes,
    val ac: HuffmanCodes,
  )

  private class CodeTables(val dc: Map<Int, HuffmanCodes>, val ac: Map<Int, HuffmanCodes>)

  private fun loadTables(structure: JpegStructure): CodeTables? {
    val dc = HashMap<Int, HuffmanCodes>()
    val ac = HashMap<Int, HuffmanCodes>()
    for (spec in structure.huffmanTables) {
      val table = HuffmanCodes(spec.counts, spec.symbols) ?: return null
      if (spec.tableClass == 0) dc[spec.tableId] = table else ac[spec.tableId] = table
    }
    return if (dc.isEmpty() || ac.isEmpty()) null else CodeTables(dc, ac)
  }

  /**
   * Decode one 8x8 block and immediately re-encode it.
   *
   * Streaming per block keeps this O(1) in memory - nothing is buffered per
   * block. The DC needs two predictors: [absPred] accumulates the true absolute
   * DC while reading, and [encPred] mirrors what the decoder will hold (reset to
   * 0 at each restart) and is what the re-encoded difference must be measured
   * against.
   */
  private fun transcodeBlock(
    reader: BitReader,
    writer: BitWriter,
    c: ScanPlan,
    absPred: HashMap<Int, Int>,
    encPred: HashMap<Int, Int>,
  ): Boolean {
    // --- DC ---
    val size = reader.decodeHuffman(c.dc)
    if (size < 0) return false
    val diff = if (size == 0) 0 else reader.receiveExtend(size)
    val dcAbs = (absPred[c.id] ?: 0) + diff
    absPred[c.id] = dcAbs
    val emit = dcAbs - (encPred[c.id] ?: 0)
    val emitSize = category(emit)
    if (!writer.writeHuffman(c.dc, emitSize)) return false
    if (emitSize > 0) writer.writeBits(magnitudeBits(emit, emitSize), emitSize)
    encPred[c.id] = dcAbs

    // --- AC ---
    var k = 1
    while (k < 64) {
      val rs = reader.decodeHuffman(c.ac)
      if (rs < 0) return false
      if (!writer.writeHuffman(c.ac, rs)) return false
      val run = rs shr 4
      val acSize = rs and 0x0F
      if (acSize == 0) {
        if (run == 15) {
          k += 16 // ZRL: sixteen zero coefficients
          continue
        }
        break // EOB
      }
      val value = reader.receiveExtend(acSize)
      writer.writeBits(magnitudeBits(value, acSize), acSize)
      k += run + 1
    }
    return true
  }

  /** Number of bits needed for |value|; 0 for 0. */
  internal fun category(value: Int): Int {
    var a = if (value < 0) -value else value
    var n = 0
    while (a != 0) {
      a = a shr 1
      n++
    }
    return n
  }

  /** The raw bits that represent [value] in a field of [size] bits. */
  internal fun magnitudeBits(value: Int, size: Int): Int =
    if (value < 0) value + (1 shl size) - 1 else value
}

/**
 * Canonical Huffman code table built from a DHT segment, in the form libjpeg
 * uses: per-length minimum code, maximum code and a start index into the symbol
 * list. Decoding is one comparison per code length; encoding uses a flat
 * symbol -> (length, code) table built alongside.
 */
internal class HuffmanCodes(counts: IntArray, val symbols: IntArray) {
  private val minCode = IntArray(17)
  private val maxCode = IntArray(17) { -1 }
  private val valPtr = IntArray(17)
  private val encLength = IntArray(256)
  private val encCode = IntArray(256)

  init {
    var code = 0
    var k = 0
    for (length in 1..16) {
      val count = counts.getOrElse(length - 1) { 0 }
      if (count > 0) {
        valPtr[length] = k
        minCode[length] = code
        for (i in 0 until count) {
          val symbol = symbols.getOrElse(k) { 0 }
          encLength[symbol] = length
          encCode[symbol] = code
          code++
          k++
        }
        maxCode[length] = code - 1
      }
      code = code shl 1
    }
  }

  /** Bits used to encode [symbol], or 0 when the table has no such symbol. */
  fun lengthOf(symbol: Int): Int =
    if (symbol in 0..255) encLength[symbol] else 0

  /** Code for [symbol]; only meaningful when [lengthOf] is non-zero. */
  fun codeOf(symbol: Int): Int = if (symbol in 0..255) encCode[symbol] else 0

  /** Decode one symbol, or -1 when no code matches (corrupt table). */
  fun decode(readBit: () -> Int): Int {
    var code = 0
    for (length in 1..16) {
      code = (code shl 1) or readBit()
      if (maxCode[length] >= 0 && code <= maxCode[length]) {
        val index = valPtr[length] + code - minCode[length]
        return if (index in symbols.indices) symbols[index] else -1
      }
    }
    return -1
  }
}

/**
 * Bit reader over one scan's entropy data. Undoes 0xFF00 byte stuffing and
 * refuses to read past the end of the scan, so a desync is detected as a failure
 * rather than silently reading into the next marker.
 */
internal class BitReader(private val data: ByteArray, start: Int, private val end: Int) {
  private var pos = start
  private var acc = 0
  private var bitsLeft = 0

  init {
    require(start in 0..end && end <= data.size) { "bad scan range [$start,$end)" }
  }

  private fun nextByte(): Int {
    if (pos >= end) return -1
    val b = data[pos].toInt() and 0xFF
    pos++
    if (b == 0xFF) {
      if (pos < end && (data[pos].toInt() and 0xFF) == 0x00) {
        pos++ // stuffed 0xFF: a literal data byte
      } else {
        return -1 // a real marker, not entropy data
      }
    }
    return b
  }

  private fun bit(): Int {
    if (bitsLeft == 0) {
      val b = nextByte()
      if (b < 0) return 0 // pad with zeros past the end, as decoders do
      acc = b
      bitsLeft = 8
    }
    bitsLeft--
    return (acc shr bitsLeft) and 1
  }

  fun decodeHuffman(table: HuffmanCodes): Int = table.decode { bit() }

  /** Read [size] bits and sign-extend them (JPEG receive/extend). */
  fun receiveExtend(size: Int): Int {
    if (size == 0) return 0
    var v = 0
    for (i in 0 until size) v = (v shl 1) or bit()
    if (v < (1 shl (size - 1))) v -= (1 shl size) - 1
    return v
  }
}

/**
 * Bit writer for entropy data. Bytes equal to 0xFF are stuffed with a following
 * 0x00; [emitMarker] byte-aligns first (padding with 1-bits, as the spec
 * requires) and then writes the marker bytes without stuffing.
 */
internal class BitWriter {
  private val out = java.io.ByteArrayOutputStream(1 shl 16)
  private var acc = 0
  private var bitsUsed = 0

  fun writeBit(b: Int) {
    acc = (acc shl 1) or (b and 1)
    bitsUsed++
    if (bitsUsed == 8) {
      emit(acc)
      acc = 0
      bitsUsed = 0
    }
  }

  fun writeBits(value: Int, count: Int) {
    for (i in count - 1 downTo 0) writeBit((value shr i) and 1)
  }

  private fun emit(byte: Int) {
    out.write(byte)
    if (byte == 0xFF) out.write(0x00) // byte stuffing
  }

  /** Pad with 1-bits to a byte boundary. */
  fun byteAlign() {
    while (bitsUsed != 0) writeBit(1)
  }

  fun emitMarker(code: Int) {
    byteAlign()
    out.write(0xFF)
    out.write(code)
  }

  /** Encode [symbol] using [table]; false when the table lacks that symbol. */
  fun writeHuffman(table: HuffmanCodes, symbol: Int): Boolean {
    val length = table.lengthOf(symbol)
    if (length == 0) return false
    writeBits(table.codeOf(symbol), length)
    return true
  }

  fun finish(): ByteArray {
    byteAlign()
    return out.toByteArray()
  }
}
