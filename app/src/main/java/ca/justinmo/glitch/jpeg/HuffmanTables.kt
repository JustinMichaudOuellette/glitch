package ca.justinmo.glitch.jpeg

/**
 * One symbol of a Huffman table: the byte a screen edits, and the code it owns.
 *
 * A DHT table is `1 information byte + 16 code-length counts + N symbols`, and
 * the symbols are stored **in code order**, which is what makes [index] the
 * useful coordinate rather than the byte value: symbol #0 owns the shortest
 * code, short codes are the ones JPEG hands to the most frequent (run, size)
 * pairs, and a change to an early symbol therefore lands on thousands of blocks
 * while a change to #150 lands on none at all. See `DHT.md` section 5.
 *
 * What the value means depends on the table:
 *
 *  - **AC table** (`tableClass == 1`): `run << 4 | size` - how many zero
 *    coefficients to skip, and how many bits the non-zero one needs. Editing
 *    either nibble is an ordinary value change; the file still decodes.
 *  - **DC table** (`tableClass == 0`): a bit *count* - how many raw bits follow
 *    the code. Values 0-15 keep the file decodable; 16-255 desync the decoder
 *    and the file stops opening, which is why [maxValue] is 15 there.
 */
class HuffmanSymbol(
  /** 0 = DC table, 1 = AC table (the Tc field of the table's spec byte). */
  val tableClass: Int,
  /** Table id 0-3 (the Th field). */
  val tableId: Int,
  /** Position in the symbol list: the code's rank, shortest code first. */
  val index: Int,
  /** Offset of the symbol byte, which is what an edit writes. */
  val offset: Int,
  /** Bits in this symbol's code, from the table's counts (1-16). */
  val codeLength: Int,
  private val data: ByteArray,
) {
  /** The symbol byte as it stands in [data], 0-255. */
  val value: Int get() = data[offset].toInt() and 0xFF

  /** True for a DC table, where the symbol is a category rather than a run/size pair. */
  val isDc: Boolean get() = tableClass == 0

  /** AC only: consecutive zero coefficients to skip (the high nibble). */
  val run: Int get() = value shr 4

  /** AC only: bits the non-zero coefficient needs (the low nibble). */
  val size: Int get() = value and 0x0F

  /** Largest value this symbol can take and still decode (see the class comment). */
  val maxValue: Int get() = if (isDc) DC_MAX_CATEGORY else 255

  /** An AC symbol that ends the block instead of coding a coefficient. */
  val isEndOfBlock: Boolean get() = !isDc && size == 0 && run != 15

  /** An AC symbol that skips sixteen zero coefficients (run 15, size 0). */
  val isZeroRun: Boolean get() = !isDc && size == 0 && run == 15

  override fun toString(): String = buildString {
    append("HuffmanSymbol(")
    append(if (isDc) "DC" else "AC")
    append(" table $tableId #$index, ")
    if (isDc) append("category $value") else append("run $run size $size")
    append(", ${codeLength}-bit code at $offset)")
  }

  companion object {
    /**
     * Highest DC category the decoder accepts. A DC symbol is a count of raw
     * bits to read, and 16 or more is not something an 8-bit sample can carry -
     * measured as fatal on every panel in `DHT.md` section 3.
     */
    const val DC_MAX_CATEGORY = 15

    /**
     * Highest DC category that means anything for 8-bit samples (a coefficient
     * difference needs at most 11 bits). Values 12-15 decode but are already
     * outside what any encoder emits.
     */
    const val DC_USEFUL_CATEGORY = 11
  }
}

/**
 * One parsed Huffman table: its spec byte, its code-length counts, and every
 * symbol it defines.
 *
 * [isChroma] is resolved against the scan header (the table id the luma
 * component codes with), so a grayscale file's single table is reported as luma
 * and any other table id as chroma - the same rule [QuantTables] uses.
 */
class HuffmanTable(
  /** 0 = DC, 1 = AC. */
  val tableClass: Int,
  /** Table id 0-3. */
  val tableId: Int,
  /** Offset of the information byte (Tc/Th), which is never safe to edit. */
  val specOffset: Int,
  /** The 16 code-length counts, BITS[1..16]. Editing any of them breaks the file. */
  val counts: IntArray,
  val entries: List<HuffmanSymbol>,
  val isChroma: Boolean,
) {
  val isAc: Boolean get() = tableClass == 1
  val symbolCount: Int get() = entries.size

  /** Total payload bytes this table occupies, spec byte and counts included. */
  val byteCount: Int get() = 17 + entries.size

  /** Highest code length in the table. */
  val maxCodeLength: Int get() = counts.indexOfLast { it > 0 } + 1

  /** Symbols grouped by code length, shortest (loudest) first. */
  fun entriesByLength(): List<Pair<Int, List<HuffmanSymbol>>> =
    entries.groupBy { it.codeLength }.toSortedMap().map { (length, group) -> length to group }

  override fun toString(): String =
    "HuffmanTable(${if (isAc) "AC" else "DC"} ${if (isChroma) "chroma" else "luma"} " +
      "id=$tableId, $symbolCount symbols, spec at $specOffset)"
}

/**
 * Reads the Huffman tables out of a parsed JPEG, and writes edited symbols back.
 *
 * [JpegStructure.dht] holds one span per DHT segment, payload only, and a
 * segment may carry several tables. Only the symbol bytes are exposed: the
 * information byte and the 16 counts sit in [JpegStructure.fatal] because every
 * bit of them makes the file undecodable (`DHT.md` section 3), so nothing here
 * ever offers them for editing - the walk records [HuffmanTable.specOffset] so a
 * screen can say where they are without being able to change them.
 */
object HuffmanTables {

  /**
   * The table ids the *luma* component codes with, as (DC id, AC id). Taken
   * from the first scan's first component, falling back to table 0.
   */
  private fun lumaTableIds(structure: JpegStructure): Pair<Int, Int> {
    val luma = structure.scans.firstOrNull()?.components?.firstOrNull()
    return (luma?.dcTable ?: 0) to (luma?.acTable ?: 0)
  }

  /** Tables in the file, in segment order. */
  fun parse(data: ByteArray, structure: JpegStructure): List<HuffmanTable> {
    val (lumaDc, lumaAc) = lumaTableIds(structure)
    val tables = ArrayList<HuffmanTable>(4)
    for (span in structure.dht) {
      var p = span.start
      while (p + 17 <= span.endExclusive) {
        val spec = data[p].toInt() and 0xFF
        val tableClass = (spec shr 4) and 0x0F
        val tableId = spec and 0x0F
        val counts = IntArray(16)
        var symbols = 0
        for (i in 1..16) {
          counts[i - 1] = data[p + i].toInt() and 0xFF
          symbols += counts[i - 1]
        }
        // A class outside 0/1, or a symbol count that runs past the segment, is
        // not a table this app can offer: stop here rather than guess at offsets.
        if (tableClass > 1 || tableId > 3 || p + 17 + symbols > span.endExclusive) break
        // Code length of each symbol, in list order.
        val lengths = IntArray(symbols)
        var index = 0
        for (length in 1..16) {
          repeat(counts[length - 1]) { if (index < symbols) lengths[index++] = length }
        }
        val entries = ArrayList<HuffmanSymbol>(symbols)
        for (i in 0 until symbols) {
          entries.add(
            HuffmanSymbol(
              tableClass = tableClass,
              tableId = tableId,
              index = i,
              offset = p + 17 + i,
              codeLength = lengths[i],
              data = data,
            ),
          )
        }
        tables.add(
          HuffmanTable(
            tableClass = tableClass,
            tableId = tableId,
            specOffset = p,
            counts = counts,
            entries = entries,
            isChroma = tableId != if (tableClass == 0) lumaDc else lumaAc,
          ),
        )
        p += 17 + symbols
      }
    }
    return tables
  }

  /**
   * Write edited symbol values into [data] in place: [values] maps a symbol byte
   * offset to the value it should have. Values are masked to a byte, so a
   * caller cannot lengthen the file or scribble outside the symbol it is editing.
   */
  fun applyValues(data: ByteArray, values: Map<Int, Int>) {
    for ((offset, value) in values) {
      if (offset < 0 || offset >= data.size) continue
      data[offset] = (value and 0xFF).toByte()
    }
  }

  /** The table a symbol belongs to, or null when [offset] is not a symbol byte. */
  fun tableOf(tables: List<HuffmanTable>, offset: Int): HuffmanTable? =
    tables.firstOrNull { table -> table.entries.any { it.offset == offset } }
}
