package ca.justinmo.glitch.dht

import ca.justinmo.glitch.jpeg.BitReader
import ca.justinmo.glitch.jpeg.HuffmanCodes
import ca.justinmo.glitch.jpeg.JpegStructure

/**
 * Why a file's code usage could not be counted. The screens map these to
 * user-facing text; nothing in this package holds display strings.
 */
enum class CensusBlock {
  /** No usable SOF header. */
  NO_FRAME,

  /** Progressive, or more than one scan. */
  NOT_SINGLE_SCAN,

  /** The single scan is not a full sequential interleaved scan. */
  NOT_INTERLEAVED,

  /** The scan already carries restart markers, so the walk would need to resync. */
  HAS_RESTARTS,

  /** No Huffman tables, or the tables do not cover the components. */
  NO_TABLES,

  /** The entropy stream desynchronised partway through. */
  UNDECODABLE,
}

/**
 * How often each Huffman code actually occurs in one file's scan data.
 *
 * This is the honest answer to "will editing this symbol do anything?". A
 * symbol can only change the picture if its **code** appears in the bitstream,
 * and the answer is a property of the image, not of the table: on the four
 * sample photos of `DHT.md` 1031 of AC chroma's 1296 symbol bits change
 * nothing, mostly because their code is never emitted (section 6).
 *
 * Counts are held **per symbol index**, so they stay valid while the user edits
 * symbol *values*: rewriting what a code means does not change how often the
 * encoder emitted it. They become stale the moment the code lengths change -
 * which this app never offers, because every bit of a code-length count makes
 * the file undecodable.
 */
class HuffmanCensus internal constructor(
  private val uses: Map<Int, IntArray>,
  /** Codes decoded across the whole scan. */
  val symbolsRead: Int,
  /** 8x8 blocks walked. */
  val blocks: Int,
  /** Bytes of the scan the walk consumed. */
  val bytesRead: Int,
) {
  /** Times the code owned by [index] of [table] was decoded. */
  fun uses(table: HuffmanTable, index: Int): Int =
    uses[tableKey(table.tableClass, table.tableId)]?.getOrNull(index) ?: 0

  /** Times the code owned by [symbol] was decoded. */
  fun uses(symbol: HuffmanSymbol): Int = uses(symbol.tableClass, symbol.tableId, symbol.index)

  private fun uses(tableClass: Int, tableId: Int, index: Int): Int =
    uses[tableKey(tableClass, tableId)]?.getOrNull(index) ?: 0

  /** How many of [table]'s symbols occur at least once. */
  fun usedSymbolCount(table: HuffmanTable): Int {
    val counts = uses[tableKey(table.tableClass, table.tableId)] ?: return 0
    var n = 0
    for (value in counts) if (value > 0) n++
    return n
  }

  override fun toString(): String =
    "HuffmanCensus($symbolsRead codes over $blocks blocks, ${uses.size} table(s))"
}

/** Key for one table: its class in the high byte, its id in the low one. */
private fun tableKey(tableClass: Int, tableId: Int): Int = (tableClass shl 8) or tableId

/**
 * Walks a file's entropy stream and counts which Huffman codes it uses.
 *
 * The walk mirrors [ca.justinmo.glitch.jpeg.JpegRestartInjector]'s transcode loop - MCU by MCU, block by
 * block, DC then AC - but only reads: every decoded symbol is tallied and every
 * value is consumed so the reader stays aligned. It is O(1) in memory and one
 * pass over the scan, so a phone photo costs a few hundred milliseconds.
 */
object HuffmanCensusReader {

  /** Why [structure] cannot be counted, or null when it can. */
  fun blockFor(structure: JpegStructure): CensusBlock? {
    val frame = structure.frame ?: return CensusBlock.NO_FRAME
    if (frame.progressive || structure.scans.size != 1) return CensusBlock.NOT_SINGLE_SCAN
    val scan = structure.scans.firstOrNull() ?: return CensusBlock.NOT_SINGLE_SCAN
    if (scan.components.size < 2 || scan.isRefinement || scan.ss != 0 || scan.se != 63) {
      return CensusBlock.NOT_INTERLEAVED
    }
    if (structure.restartInterval > 0) return CensusBlock.HAS_RESTARTS
    if (structure.huffmanTables.isEmpty()) return CensusBlock.NO_TABLES
    if (frame.mcusPerRow <= 0 || frame.mcuRows <= 0) return CensusBlock.NO_FRAME
    return null
  }

  /**
   * Count the codes of [data], or null when the scan cannot be walked to the end.
   * [tables] are the parsed tables of the same bytes, which supply the
   * symbol-value -> symbol-index mapping the counts are keyed by.
   */
  fun read(
    data: ByteArray,
    structure: JpegStructure,
    tables: List<HuffmanTable>,
  ): HuffmanCensus? {
    if (blockFor(structure) != null) return null
    val frame = structure.frame ?: return null
    val scan = structure.scans.firstOrNull() ?: return null
    if (scan.start !in 0..scan.endExclusive || scan.endExclusive > data.size) return null

    val codes = HashMap<Int, HuffmanCodes>(structure.huffmanTables.size)
    for (spec in structure.huffmanTables) {
      codes[tableKey(spec.tableClass, spec.tableId)] = HuffmanCodes(spec.counts, spec.symbols)
    }
    val indexOf = HashMap<Int, IntArray>(tables.size)
    for (table in tables) {
      val map = IntArray(256) { -1 }
      for (entry in table.entries) {
        // One code per symbol value in every table an encoder writes; if a file
        // ever repeated a value, the first code that owns it wins the tally.
        if (map[entry.value] < 0) map[entry.value] = entry.index
      }
      indexOf[tableKey(table.tableClass, table.tableId)] = map
    }

    val sampling = frame.components.associateBy { it.id }
    val plans = ArrayList<Plan>(scan.components.size)
    for (component in scan.components) {
      val factor = sampling[component.id] ?: return null
      val dc = codes[tableKey(0, component.dcTable)] ?: return null
      val ac = codes[tableKey(1, component.acTable)] ?: return null
      plans.add(
        Plan(
          h = factor.h.coerceAtLeast(1),
          v = factor.v.coerceAtLeast(1),
          dc = dc,
          ac = ac,
          dcKey = tableKey(0, component.dcTable),
          acKey = tableKey(1, component.acTable),
        ),
      )
    }

    val tally = HashMap<Int, IntArray>(structure.huffmanTables.size)
    for (table in tables) {
      tally[tableKey(table.tableClass, table.tableId)] = IntArray(table.symbolCount)
    }

    var read = 0
    var blocks = 0
    val reader = BitReader(data, scan.start, scan.endExclusive)
    val totalMcus = frame.mcusPerRow * frame.mcuRows
    for (mcu in 0 until totalMcus) {
      for (plan in plans) {
        repeat(plan.h * plan.v) {
          val dcSymbol = reader.decodeHuffman(plan.dc)
          if (dcSymbol < 0) return null
          if (!count(tally, indexOf, plan.dcKey, dcSymbol)) return null
          read++
          blocks++
          if (dcSymbol > 0) reader.receiveExtend(dcSymbol)

          var k = 1
          while (k < 64) {
            val rs = reader.decodeHuffman(plan.ac)
            if (rs < 0) return null
            if (!count(tally, indexOf, plan.acKey, rs)) return null
            read++
            val run = rs shr 4
            val size = rs and 0x0F
            if (size == 0) {
              if (run == 15) {
                k += 16 // ZRL: sixteen zero coefficients
                continue
              }
              break // end of block
            }
            reader.receiveExtend(size)
            k += run + 1
          }
        }
      }
    }
    // A walk that read past the end of the scan lost the stream: the counts it
    // collected are the garbage after the desync, so report nothing instead.
    if (reader.exhausted) return null
    return HuffmanCensus(tally, read, blocks, reader.bytesRead)
  }

  /** Tally one decoded symbol; false when the table has no such symbol. */
  private fun count(
    tally: Map<Int, IntArray>,
    indexOf: Map<Int, IntArray>,
    key: Int,
    symbol: Int,
  ): Boolean {
    val counts = tally[key] ?: return false
    val map = indexOf[key] ?: return false
    val index = if (symbol in 0..255) map[symbol] else -1
    if (index !in counts.indices) return false
    counts[index]++
    return true
  }

  /** One scan component's decode plan: its block grid and its two tables. */
  private class Plan(
    val h: Int,
    val v: Int,
    val dc: HuffmanCodes,
    val ac: HuffmanCodes,
    val dcKey: Int,
    val acKey: Int,
  )
}
