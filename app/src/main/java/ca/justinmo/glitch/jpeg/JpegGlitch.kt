package ca.justinmo.glitch.jpeg

import kotlin.random.Random

/**
 * Pure-Kotlin port of the JPEG region parsing + bit-flip logic from
 * `jpg_glitch.py` (no Android dependencies, so it can be unit-tested on a JVM).
 *
 * The JPEG is walked segment by segment and split into corruptable byte
 * regions, mirroring the Python tool:
 *
 *  - DQT  quantization tables (payload bytes only)
 *  - DHT  Huffman tables (payload bytes only)
 *  - SCAN scan / entropy data (default, the classic glitch region)
 *  - RST  restart markers (FF D0-FF D7 inside scan data)
 *
 * Ranges never include marker/length bytes for DQT/DHT and never include the
 * EOI marker. Byte offsets returned by the parser refer to the *original*
 * source byte array; since bit flips never change the file length, the
 * regions stay valid while the working buffer is mutated.
 *
 * ## Why flips land on "fatal" bytes
 *
 * The Python-parity spans above are deliberately kept byte-for-byte identical
 * (they are asserted against `jpg_glitch.py` in JpegParserTest), but not every
 * byte inside them is worth flipping. Exhaustive single-bit sweeps over the
 * sample files (every bit of every DQT and DHT byte, full decode, measuring the
 * changed-pixel fraction) found three classes of byte:
 *
 *  - **Fatal** - every one of the 8 bits makes the file undecodable:
 *    * DQT table spec byte (the byte before each 64/128-entry table). 16/16
 *      bits undecodable on both tables of `b.jpg`.
 *    * DHT table spec byte plus the 16 code-length counts that follow it
 *      (BITS[1..16]). 16/16 bits undecodable for all four DHT tables of
 *      `b.jpg` - 68 of 416 DHT payload bytes.
 *  - **Dead** - no bit does anything visible: most of DHT (60.6% of its bits
 *    measured as no-ops) and the high-frequency tail of DQT, where the image
 *    has no surviving coefficients for that quantizer to act on.
 *  - **Useful** - a graded or heavy change.
 *
 * [JpegStructure.fatal] lists the fatal bytes so flips can avoid them; landing
 * on one is a guaranteed failed attempt and a guaranteed "it completely
 * glitches" outcome.
 *
 * ## Why a byte offset predicts *where* damage happens
 *
 * JPEG scan data is one long variable-length-coded bitstream, and there are no
 * restart markers in either sample file (DRI = 0). A corrupt byte therefore
 * desyncs the Huffman decoder at the MCU that byte belongs to and the damage
 * runs to the end of the scan. Measured on `b.jpg` (baseline, 3472x4624), the
 * first visibly damaged row tracks the byte offset almost exactly:
 *
 *     aim (fraction of scan bytes)   first damaged row (fraction of height)
 *     0.05                            0.06
 *     0.15                            0.16
 *     0.35                            0.35
 *     0.55                            0.56
 *     0.85                            0.86
 *
 * mean absolute error 0.030, median 0.026, max 0.050 of image height. That is
 * the app's area control: [ScanGeometry] converts between a byte offset and the
 * image row band the resulting damage starts at, in both directions.
 *
 * The *end* of the band cannot be controlled by a bit flip - with no restart
 * markers the damage always runs to the bottom of the image - so the area
 * control picks where the damage *starts*.
 */

/** Half-open byte span [start, endExclusive), like the Python ranges. */
class ByteSpan(val start: Int, val endExclusive: Int) {
  val size: Int get() = endExclusive - start

  fun contains(offset: Int): Boolean = offset >= start && offset < endExclusive

  override fun toString(): String = "[$start, $endExclusive)"
}

object ByteSpans {
  /** Total byte count of a span list. */
  fun size(spans: List<ByteSpan>): Int = spans.sumOf { it.size }

  /**
   * [spans] minus [holes], as a sorted list of non-overlapping spans.
   * [holes] must be sorted and non-overlapping (see [JpegStructure.fatal]).
   */
  fun subtract(spans: List<ByteSpan>, holes: List<ByteSpan>): List<ByteSpan> {
    if (holes.isEmpty()) return spans
    val out = ArrayList<ByteSpan>(spans.size)
    for (span in spans) {
      var cursor = span.start
      for (hole in holes) {
        if (hole.endExclusive <= cursor) continue
        if (hole.start >= span.endExclusive) break
        if (hole.start > cursor) out.add(ByteSpan(cursor, hole.start))
        cursor = maxOf(cursor, hole.endExclusive)
        if (cursor >= span.endExclusive) break
      }
      if (cursor < span.endExclusive) out.add(ByteSpan(cursor, span.endExclusive))
    }
    return out
  }

  /** True when [offset] lies in any of [spans]. */
  fun contains(spans: List<ByteSpan>, offset: Int): Boolean = spans.any { it.contains(offset) }
}

/**
 * The four corruptable regions, mirroring jpg_glitch.py's number keys.
 * Display labels live in string resources (see regionCodeRes / regionDescriptionRes).
 */
enum class GlitchRegion { DQT, DHT, SCAN, RST }

/** One component of the frame header: its id, sampling factors and quant table. */
class ComponentSampling(val id: Int, val h: Int, val v: Int, val quantTable: Int) {
  override fun toString(): String = "id=$id ${h}x$v q$quantTable"
}

/**
 * Frame geometry from the SOF marker. Needed to turn a byte offset inside scan
 * data into the image row band that corruption of that byte affects.
 */
class JpegFrame(
  val width: Int,
  val height: Int,
  val precision: Int,
  val progressive: Boolean,
  val components: List<ComponentSampling>,
) {
  val maxH: Int = components.maxOfOrNull { it.h }?.coerceAtLeast(1) ?: 1
  val maxV: Int = components.maxOfOrNull { it.v }?.coerceAtLeast(1) ?: 1

  /** MCU size in pixels (8x8 blocks times the largest sampling factors). */
  val mcuWidth: Int = 8 * maxH
  val mcuHeight: Int = 8 * maxV

  /** Interleaved MCU grid of the whole image. */
  val mcusPerRow: Int = ceilDiv(width, mcuWidth)
  val mcuRows: Int = ceilDiv(height, mcuHeight)

  override fun toString(): String =
    "$width" + "x$height ${if (progressive) "progressive" else "baseline"} comps=$components"
}

/**
 * One scan: the SOS header plus the entropy-coded byte range that follows it.
 *
 * [ss]/[se] are the spectral selection (first/last coefficient) and [ah]/[al]
 * the successive-approximation bit positions. In a progressive file these make
 * each scan a semantically distinct knob:
 *
 *   - `ss == 0 && se == 0`  -> the DC scan. In `a.jpg` (10 scans) corrupting
 *     this one changed 47.7% of all pixels on average (max 94.2%) because every
 *     later refinement scan builds on it. It is the file's "nuke".
 *   - `ss > 0`              -> an AC band. Corrupting `a.jpg` scan 1 (AC 1-5)
 *     changed 25.9% on average - blocky structural smearing.
 *   - `ah > 0`              -> a refinement scan. `a.jpg` scan 6 refines DC and
 *     measured 0.0% changed: a dead knob the old single-"Scan"-chip UI could
 *     not distinguish from the nuke.
 */
class ScanInfo(
  val index: Int,
  val markerOffset: Int,
  val start: Int,
  val endExclusive: Int,
  val componentIds: List<Int>,
  val ss: Int,
  val se: Int,
  val ah: Int,
  val al: Int,
) {
  val byteCount: Int get() = endExclusive - start
  val componentCount: Int get() = componentIds.size

  /** A DC-only scan (spectral selection 0..0). */
  val isDcOnly: Boolean get() = ss == 0 && se == 0

  /** A successive-approximation refinement pass. */
  val isRefinement: Boolean get() = ah > 0

  /** True for the single full-band, non-refining scan of a baseline file. */
  val isBaselineFull: Boolean get() = !isRefinement && ss == 0 && se == 63 && componentCount > 1

  override fun toString(): String =
    "#$index bytes[$start,$endExclusive) comps=$componentIds Ss=$ss Se=$se Ah=$ah Al=$al"
}

/**
 * Maps a byte offset inside one scan's entropy data to the image row band that
 * corruption of that byte destroys, and back again.
 *
 * Entropy data is stored MCU by MCU in raster order, so the number of bytes per
 * MCU row is `scan.byteCount / unitRows` and the mapping is linear:
 *
 *     damage starts at image row ~= (offset - scan.start) / scan.byteCount * height
 *
 * For an interleaved scan (`componentCount > 1`) a "unit" is an MCU of
 * [JpegFrame.mcuWidth] x [JpegFrame.mcuHeight] pixels. For a single-component
 * scan a unit is one 8x8 block of that component's plane, which covers
 * `8 * maxV / v` image rows - this is why progressive AC/chroma scans can still
 * be aimed even though they do not cover the image in MCU order.
 */
class ScanGeometry(
  val scan: ScanInfo,
  val unitsPerRow: Int,
  val unitRows: Int,
  /** Image rows each unit row covers (MCU height, or the component's block height). */
  val rowsPerUnitRow: Int,
  val imageHeight: Int,
) {
  val totalUnits: Int = unitsPerRow * unitRows

  /**
   * Unit index a byte at [offset] belongs to.
   *
   * Rounds to the nearest unit rather than truncating, so that
   * `unitForOffset(offsetForUnit(u)) == u` as closely as integer maths allows.
   * Truncating both ways made a band's first byte map to the previous unit row
   * whenever the byte density is low (a.jpg's AC scans hold far more blocks than
   * bytes), which shifted the aimed row by one.
   */
  fun unitForOffset(offset: Int): Int {
    if (scan.byteCount <= 0 || totalUnits <= 0) return 0
    val clamped = offset.coerceIn(scan.start, scan.endExclusive - 1)
    val numerator = (clamped - scan.start).toLong() * totalUnits + scan.byteCount / 2
    return (numerator / scan.byteCount).toInt().coerceIn(0, totalUnits - 1)
  }

  /** First byte offset belonging to unit [unit]. */
  fun offsetForUnit(unit: Int): Int {
    if (totalUnits <= 0) return scan.start
    val clamped = unit.coerceIn(0, totalUnits - 1)
    val offset = scan.start + (clamped.toLong() * scan.byteCount / totalUnits).toInt()
    return offset.coerceIn(scan.start, scan.endExclusive)
  }

  /**
   * Image-row fraction at which damage from a flip at [offset] begins.
   * This is the number the UI shows for a chosen flip position.
   *
   * [unitForOffset] yields a *unit* index and [unitRowFraction] expects a unit
   * *row*, so the index is rounded to the nearest row. Flooring here dropped a
   * whole row whenever the round trip landed a unit or two short, which happens
   * routinely in scans that hold far more blocks than bytes (a.jpg's AC scans).
   * With fewer bytes than units the byte -> unit map is many-to-one, so a row is
   * the finest resolution this mapping can honestly claim.
   */
  fun damageStartFraction(offset: Int): Float {
    val perRow = unitsPerRow.coerceAtLeast(1)
    val row = (unitForOffset(offset) + perRow / 2) / perRow
    return unitRowFraction(row)
  }

  /** Image-row fraction where unit row [unitRow] starts. */
  fun unitRowFraction(unitRow: Int): Float {
    if (imageHeight <= 0) return 0f
    val rows = unitRow.toLong() * rowsPerUnitRow
    return (rows.toDouble() / imageHeight).toFloat().coerceIn(0f, 1f)
  }

  /**
   * The byte span covering the unit row that contains image-row fraction
   * [rowFraction]. A flip anywhere in this span starts its damage at
   * approximately [rowFraction], giving the caller a whole MCU row of candidate
   * bytes to find one that actually takes effect.
   */
  fun byteSpanForRowFraction(rowFraction: Float): ByteSpan {
    if (totalUnits <= 0) return ByteSpan(scan.start, scan.endExclusive)
    val targetRow = (rowFraction.coerceIn(0f, 1f) * imageHeight).toInt()
    val unitRow = (targetRow / rowsPerUnitRow.coerceAtLeast(1)).coerceIn(0, unitRows - 1)
    val firstUnit = unitRow * unitsPerRow
    val start = offsetForUnit(firstUnit)
    val end = offsetForUnit(firstUnit + unitsPerRow)
    return ByteSpan(start, maxOf(end, start + 1).coerceAtMost(scan.endExclusive))
  }

  override fun toString(): String =
    "geometry(scan #${scan.index} ${unitsPerRow}x$unitRows units, " +
      "${rowsPerUnitRow}px/unit-row, ${totalUnits} units)"
}

/** Parsed structure of one JPEG. */
class JpegStructure(
  val dqt: List<ByteSpan>,
  val dht: List<ByteSpan>,
  val scan: List<ByteSpan>,
  val rst: List<ByteSpan>,
  val scans: List<ScanInfo>,
  val geometry: List<ScanGeometry>,
  val frame: JpegFrame?,
  /** Bytes that every single-bit flip makes undecodable (see the file header). */
  val fatal: List<ByteSpan>,
  /** APPn/COM segments - metadata only, never a glitch target. */
  val metadata: List<ByteSpan>,
) {
  fun spansOf(region: GlitchRegion): List<ByteSpan> = when (region) {
    GlitchRegion.DQT -> dqt
    GlitchRegion.DHT -> dht
    GlitchRegion.SCAN -> scan
    GlitchRegion.RST -> rst
  }

  fun byteCount(region: GlitchRegion): Int = ByteSpans.size(spansOf(region))

  /**
   * Bytes of [region] that are safe to flip: the parsed spans minus [fatal].
   * Use this instead of [spansOf] for anything that mutates the file.
   */
  fun flippableSpans(region: GlitchRegion): List<ByteSpan> =
    ByteSpans.subtract(spansOf(region), fatal)

  fun flippableByteCount(region: GlitchRegion): Int = ByteSpans.size(flippableSpans(region))

  /** Bytes excluded from [region] because flipping them can never decode. */
  fun fatalByteCount(region: GlitchRegion): Int =
    byteCount(region) - flippableByteCount(region)

  fun geometryFor(scanIndex: Int): ScanGeometry? =
    geometry.firstOrNull { it.scan.index == scanIndex }

  fun isFatal(offset: Int): Boolean = ByteSpans.contains(fatal, offset)

  val isProgressive: Boolean get() = frame?.progressive ?: (scans.size > 1)
}

object JpegParser {
  // JPEG marker codes.
  private const val EOI = 0xD9 // end of image
  private const val SOS = 0xDA // start of scan (entropy-coded data follows)
  private const val DQT = 0xDB // define quantization table(s)
  private const val DHT = 0xC4 // define Huffman table(s)
  private const val SOF0 = 0xC0
  private const val SOF1 = 0xC1
  private const val SOF2 = 0xC2

  /**
   * Walk the JPEG structure and return byte ranges per corruptable region.
   * Mirrors `parse_jpeg` in jpg_glitch.py - the DQT/DHT/SCAN/RST spans are
   * byte-for-byte identical to the Python reference (asserted in tests) - and
   * additionally collects the frame geometry, per-scan headers, the list of
   * never-flip bytes and the metadata segments.
   */
  fun parse(data: ByteArray): JpegStructure {
    val dqt = mutableListOf<ByteSpan>()
    val dht = mutableListOf<ByteSpan>()
    val scan = mutableListOf<ByteSpan>()
    val rst = mutableListOf<ByteSpan>()
    val scans = mutableListOf<ScanInfo>()
    val fatal = mutableListOf<ByteSpan>()
    val metadata = mutableListOf<ByteSpan>()
    var frame: JpegFrame? = null
    val n = data.size
    var pos = 2 // just past SOI (FF D8)

    while (pos + 1 < n) {
      if ((data[pos].toInt() and 0xFF) != 0xFF) break // lost sync - stop cleanly
      val code = data[pos + 1].toInt() and 0xFF
      if (code == 0xFF) { // fill bytes before a marker code
        pos += 1
        continue
      }
      if (code == EOI) break
      // standalone markers with no length field (TEM/RST/SOI/EOI)
      if (code == 0x01 || code in 0xD0..0xD8) {
        pos += 2
        continue
      }
      if (pos + 3 >= n) break // not enough bytes for a segment length
      val segLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
      if (segLen < 2 || pos + 2 + segLen > n) break // bogus length - refuse to walk further
      val segEnd = pos + 2 + segLen
      val payloadStart = pos + 4

      when (code) {
        SOS -> {
          val header = readScanHeader(data, payloadStart, segEnd)
          val terminator = consumeScan(data, segEnd, scan, rst)
          scans.add(
            ScanInfo(
              index = scans.size,
              markerOffset = pos,
              start = segEnd,
              endExclusive = terminator,
              componentIds = header?.first ?: emptyList(),
              ss = header?.second?.get(0) ?: 0,
              se = header?.second?.get(1) ?: 63,
              ah = header?.second?.get(2) ?: 0,
              al = header?.second?.get(3) ?: 0,
            ),
          )
          pos = terminator
          continue
        }
        DQT -> {
          dqt.add(ByteSpan(payloadStart, segEnd)) // payload only
          collectQuantTables(data, payloadStart, segEnd, fatal)
        }
        DHT -> {
          dht.add(ByteSpan(payloadStart, segEnd)) // payload only
          collectHuffmanTables(data, payloadStart, segEnd, fatal)
        }
        SOF0, SOF1, SOF2 -> {
          frame = readFrameHeader(data, payloadStart, segEnd, code == SOF2)
        }
        else -> if (isMetadata(code)) {
          metadata.add(ByteSpan(pos, segEnd))
        }
      }
      pos = segEnd
    }

    val sortedFatal = fatal.sortedBy { it.start }
    val builtFrame = frame
    val geometry = scans.mapNotNull { info ->
      builtFrame?.let { buildGeometry(info, it) }
    }
    return JpegStructure(
      dqt = dqt,
      dht = dht,
      scan = scan,
      rst = rst,
      scans = scans,
      geometry = geometry,
      frame = builtFrame,
      fatal = sortedFatal,
      metadata = metadata,
    )
  }

  /** APPn (0xE0-0xEF) and COM (0xFE) segments carry no image data. */
  private fun isMetadata(code: Int): Boolean = code in 0xE0..0xEF || code == 0xFE

  /**
   * Read the SOS header: component ids and the spectral selection /
   * successive-approximation fields. Returns (componentIds, [Ss, Se, Ah, Al])
   * or null when the segment is too short to hold a valid header.
   */
  private fun readScanHeader(
    data: ByteArray,
    payloadStart: Int,
    payloadEnd: Int,
  ): Pair<List<Int>, IntArray>? {
    if (payloadStart >= data.size) return null
    val ns = data[payloadStart].toInt() and 0xFF
    if (ns <= 0) return null
    // 1 (Ns) + 2*Ns (component selectors) + 3 (Ss, Se, AhAl)
    val tailStart = payloadStart + 1 + 2 * ns
    if (tailStart + 2 >= payloadEnd || tailStart + 2 >= data.size) return null
    val ids = ArrayList<Int>(ns)
    for (i in 0 until ns) {
      val at = payloadStart + 1 + 2 * i
      if (at >= payloadEnd) return null
      ids.add(data[at].toInt() and 0xFF)
    }
    val ss = data[tailStart].toInt() and 0xFF
    val se = data[tailStart + 1].toInt() and 0xFF
    val ahAl = data[tailStart + 2].toInt() and 0xFF
    return ids to intArrayOf(ss, se, ahAl shr 4, ahAl and 0x0F)
  }

  /** Parse the SOF payload into a [JpegFrame]. */
  private fun readFrameHeader(
    data: ByteArray,
    payloadStart: Int,
    payloadEnd: Int,
    progressive: Boolean,
  ): JpegFrame? {
    if (payloadStart + 6 > payloadEnd) return null
    val precision = data[payloadStart].toInt() and 0xFF
    val height = ((data[payloadStart + 1].toInt() and 0xFF) shl 8) or
      (data[payloadStart + 2].toInt() and 0xFF)
    val width = ((data[payloadStart + 3].toInt() and 0xFF) shl 8) or
      (data[payloadStart + 4].toInt() and 0xFF)
    val count = data[payloadStart + 5].toInt() and 0xFF
    if (count <= 0 || payloadStart + 6 + 3 * count > payloadEnd) return null
    val components = ArrayList<ComponentSampling>(count)
    for (i in 0 until count) {
      val at = payloadStart + 6 + 3 * i
      val id = data[at].toInt() and 0xFF
      val sampling = data[at + 1].toInt() and 0xFF
      val quantTable = data[at + 2].toInt() and 0xFF
      components.add(
        ComponentSampling(
          id = id,
          h = (sampling shr 4) and 0x0F,
          v = sampling and 0x0F,
          quantTable = quantTable,
        ),
      )
    }
    return JpegFrame(width, height, precision, progressive, components)
  }

  /**
   * Walk a DQT payload, recording each table's spec byte as fatal and its
   * 64/128 value bytes as safe. Handles segments holding several tables and
   * both 8-bit (Pq=0) and 16-bit (Pq=1) precision.
   */
  private fun collectQuantTables(
    data: ByteArray,
    payloadStart: Int,
    payloadEnd: Int,
    fatal: MutableList<ByteSpan>,
  ) {
    var p = payloadStart
    while (p < payloadEnd) {
      val spec = data[p].toInt() and 0xFF
      val precision = (spec shr 4) and 0x0F
      val values = when (precision) {
        0 -> 64
        1 -> 128
        else -> return // reserved/invalid - stop, leave the rest unclassified
      }
      if (p + 1 + values > payloadEnd) return // truncated table
      fatal.add(ByteSpan(p, p + 1))
      p += 1 + values
    }
  }

  /**
   * Walk a DHT payload, recording each table's spec byte plus its 16
   * code-length counts as fatal, and its symbol bytes as safe.
   *
   * The counts are fatal because a corrupted BITS[] array makes the code
   * lengths inconsistent with the symbols they prefix; measured 16/16
   * undecodable bits for all four tables of `b.jpg`.
   */
  private fun collectHuffmanTables(
    data: ByteArray,
    payloadStart: Int,
    payloadEnd: Int,
    fatal: MutableList<ByteSpan>,
  ) {
    var p = payloadStart
    while (p + 17 <= payloadEnd) {
      var symbols = 0
      for (i in 1..16) symbols += data[p + i].toInt() and 0xFF
      if (p + 17 + symbols > payloadEnd) return // truncated table
      fatal.add(ByteSpan(p, p + 17))
      p += 17 + symbols
    }
  }

  /**
   * Classify entropy-coded data of one scan starting at offset [p0].
   * Handles 0xFF-stuffing (FF 00 = a data byte 0xFF) and standalone restart
   * markers (FF D0-FF D7). Returns the offset of the marker that terminates
   * the scan (or data.size at end of file). Mirrors `_consume_scan`.
   */
  private fun consumeScan(
    data: ByteArray,
    p0: Int,
    scan: MutableList<ByteSpan>,
    rst: MutableList<ByteSpan>,
  ): Int {
    val n = data.size
    var p = p0
    var runStart = p0
    while (p < n) {
      val b = data[p].toInt() and 0xFF
      if (b != 0xFF) {
        p += 1
        continue
      }
      val nb = if (p + 1 < n) data[p + 1].toInt() and 0xFF else null
      if (nb == 0) { // stuffed 0xFF data byte - part of the scan
        p += 2
        continue
      }
      if (nb == null) { // lone 0xFF at end of file: treat as scan byte
        p += 1
        continue
      }
      if (nb in 0xD0..0xD7) { // restart marker: its own region
        if (p > runStart) scan.add(ByteSpan(runStart, p))
        rst.add(ByteSpan(p, p + 2))
        p += 2
        runStart = p
        continue
      }
      // any other marker (EOI, next SOS, ...) ends this scan
      if (p > runStart) scan.add(ByteSpan(runStart, p))
      return p
    }
    if (p > runStart) scan.add(ByteSpan(runStart, p))
    return n
  }

  /**
   * Build the offset <-> image-row mapping for one scan.
   *
   * Interleaved scans (more than one component) are stored as a raster of
   * [JpegFrame.mcuWidth] x [JpegFrame.mcuHeight] MCUs covering the whole image.
   * Single-component scans (progressive AC and chroma scans) are stored as a
   * raster of 8x8 blocks of that component's own plane, which is subsampled by
   * its sampling factors - so one block row covers `8 * maxV / v` image rows.
   */
  private fun buildGeometry(info: ScanInfo, frame: JpegFrame): ScanGeometry? {
    if (info.byteCount <= 0 || frame.width <= 0 || frame.height <= 0) return null
    if (info.componentCount > 1) {
      return ScanGeometry(
        scan = info,
        unitsPerRow = frame.mcusPerRow,
        unitRows = frame.mcuRows,
        rowsPerUnitRow = frame.mcuHeight,
        imageHeight = frame.height,
      )
    }
    val component = frame.components.firstOrNull { it.id == info.componentIds.firstOrNull() }
      ?: return ScanGeometry(
        scan = info,
        unitsPerRow = frame.mcusPerRow,
        unitRows = frame.mcuRows,
        rowsPerUnitRow = frame.mcuHeight,
        imageHeight = frame.height,
      )
    val h = component.h.coerceAtLeast(1)
    val v = component.v.coerceAtLeast(1)
    val planeWidth = ceilDiv(frame.width * h, frame.maxH)
    val planeHeight = ceilDiv(frame.height * v, frame.maxV)
    return ScanGeometry(
      scan = info,
      unitsPerRow = ceilDiv(planeWidth, 8),
      unitRows = ceilDiv(planeHeight, 8),
      rowsPerUnitRow = 8 * frame.maxV / v,
      imageHeight = frame.height,
    )
  }
}

internal fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) 0 else (a + b - 1) / b

object JpegGlitch {
  /** Attempts per flip press (roll back undecodable / no-op flips), as in the Python tool. */
  const val MAX_FLIP_TRIES = 50

  /**
   * Flip one random bit inside the union of [spans]. Mutates [data].
   * Returns (byteOffset, bitIndex) or null if the region has no bytes.
   *
   * Mirrors `random_bit_in_ranges` in jpg_glitch.py: uniform over all bytes in
   * the region, then a uniformly random bit. Prefer [flipRandomSafeBit] when
   * the parsed [JpegStructure] is available, so flips avoid fatal bytes.
   */
  fun flipRandomBit(data: ByteArray, spans: List<ByteSpan>, rng: Random = Random.Default): Pair<Int, Int>? {
    val total = spans.sumOf { it.size }
    if (total <= 0) return null
    var k = rng.nextInt(total) // uniform over all region bytes
    for (span in spans) {
      if (k < span.size) {
        val pos = span.start + k
        val bit = rng.nextInt(8)
        data[pos] = (data[pos].toInt() xor (1 shl bit)).toByte()
        return pos to bit
      }
      k -= span.size
    }
    throw IllegalStateException("unreachable")
  }

  /**
   * Flip one random bit inside [spans], never landing on a byte in [fatal].
   *
   * Fatal bytes (DQT table spec bytes, DHT spec bytes and code-length counts)
   * make the file undecodable for every one of their 8 bits, so sampling them
   * only wastes attempts - the old behaviour threw away ~16% of DHT attempts
   * unconditionally.
   */
  fun flipRandomSafeBit(
    data: ByteArray,
    spans: List<ByteSpan>,
    fatal: List<ByteSpan>,
    rng: Random = Random.Default,
  ): Pair<Int, Int>? = flipRandomBit(data, ByteSpans.subtract(spans, fatal), rng)
}
