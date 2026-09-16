package ca.justinmo.glitch.jpeg

/**
 * One quantizer of a quantization table: the byte(s) that hold it and the
 * coefficient it scales.
 *
 * JPEG stores each table as an information byte (`Pq` = precision, `Tq` = table
 * id) followed by 64 quantizers in zigzag order, one byte each at 8-bit
 * precision and two bytes at 16-bit precision. [offset] is the quantizer's
 * first byte in the file, which is what a screen edits.
 */
class QuantEntry(
  val tableId: Int,
  val precision: Int,
  /** 0-63 in zigzag order. */
  val coefficient: Int,
  val offset: Int,
  val byteCount: Int,
  private val data: ByteArray,
) {
  /** u: horizontal half-cycles per 8x8 block. */
  val u: Int get() = QUANT_U[coefficient]

  /** v: vertical half-cycles per 8x8 block. */
  val v: Int get() = QUANT_V[coefficient]

  /** The quantizer as it was when this table was parsed (1 byte, or 2 big-endian). */
  val value: Int
    get() {
      var out = 0
      for (i in 0 until byteCount) out = (out shl 8) or (data[offset + i].toInt() and 0xFF)
      return out
    }

  /** Bits this quantizer has: 8 for `Pq = 0`, 16 for `Pq = 1`. */
  val bitCount: Int get() = byteCount * 8

  /** Which family of effects this coefficient belongs to. */
  val axis: QuantAxis get() = QuantAxis.of(u, v)

  /**
   * Width of one band or cell in pixels. Inside a single 8x8 block the basis
   * runs `2u` bands across, so each band is `4 / u` px wide; the pattern
   * repeats every `8 / u` px.
   */
  val bandPx: Float get() = 4f / maxOf(u, v, 1)

  override fun toString(): String =
    "QuantEntry(table $tableId, coeff $coefficient ($u,$v), q=$value at $offset)"
}

/** The four shapes a quantization table can draw, matching the flip screen. */
enum class QuantAxis {
  /** Coefficient 0: the block average, i.e. brightness or saturation. */
  DC,

  /** `v = 0`: bands running vertically. */
  VERTICAL,

  /** `u = 0`: bands running horizontally. */
  HORIZONTAL,

  /** Both frequencies: a checkerboard when `u == v`, a plaid otherwise. */
  CHECKERED,
  ;

  companion object {
    fun of(u: Int, v: Int): QuantAxis = when {
      u == 0 && v == 0 -> DC
      v == 0 -> VERTICAL
      u == 0 -> HORIZONTAL
      else -> CHECKERED
    }
  }
}

/**
 * One parsed quantization table. [isChroma] is resolved against the frame so a
 * grayscale file's single table is reported as luma, and a table id other than
 * the luma one as chroma.
 */
class QuantTable(
  val id: Int,
  val precision: Int,
  /** Offset of the table's information byte, which is never safe to flip. */
  val specOffset: Int,
  val entries: List<QuantEntry>,
  val isChroma: Boolean,
) {
  fun entriesFor(axis: QuantAxis): List<QuantEntry> = entries.filter { it.axis == axis }

  override fun toString(): String =
    "QuantTable(id=$id, ${if (isChroma) "chroma" else "luma"}, ${entries.size} entries)"
}

/**
 * Reads the quantization tables out of a parsed JPEG.
 *
 * [JpegStructure.dqt] holds one span per DQT segment, payload only, so a segment
 * may carry several tables (8-bit or 16-bit). Nothing here flips anything: the
 * caller edits [QuantEntry.offset] bytes and writes the array back.
 */
object QuantTables {

  /** Table id the luma component uses, so the rest can be called chroma. */
  private fun lumaTableId(structure: JpegStructure): Int =
    structure.frame?.components?.firstOrNull()?.quantTable ?: 0

  fun parse(data: ByteArray, structure: JpegStructure): List<QuantTable> {
    val lumaId = lumaTableId(structure)
    val tables = ArrayList<QuantTable>(2)
    for (span in structure.dqt) {
      var p = span.start
      while (p < span.endExclusive) {
        val spec = data[p].toInt() and 0xFF
        val precision = (spec shr 4) and 0x0F
        val id = spec and 0x0F
        val byteCount = when (precision) {
          0 -> 1
          1 -> 2
          else -> return tables // reserved/invalid precision: stop here
        }
        if (p + 1 + 64 * byteCount > span.endExclusive) return tables // truncated
        val entries = ArrayList<QuantEntry>(64)
        for (coefficient in 0 until 64) {
          val offset = p + 1 + coefficient * byteCount
          entries.add(
            QuantEntry(
              tableId = id,
              precision = precision,
              coefficient = coefficient,
              offset = offset,
              byteCount = byteCount,
              data = data,
            ),
          )
        }
        tables.add(
          QuantTable(
            id = id,
            precision = precision,
            specOffset = p,
            entries = entries,
            isChroma = id != lumaId,
          ),
        )
        p += 1 + 64 * byteCount
      }
    }
    return tables
  }

  /**
   * Apply edited quantizer values to [data] in place: [values] maps a quantizer
   * offset to the value it should have. Values are written big-endian, matching
   * the 16-bit table layout.
   */
  fun applyValues(data: ByteArray, tables: List<QuantTable>, values: Map<Int, Int>) {
    if (values.isEmpty()) return
    val byteCountByOffset = HashMap<Int, Int>(values.size)
    for (table in tables) {
      for (entry in table.entries) byteCountByOffset[entry.offset] = entry.byteCount
    }
    for ((offset, value) in values) {
      val byteCount = byteCountByOffset[offset] ?: 1
      for (i in 0 until byteCount) {
        val shift = 8 * (byteCount - 1 - i)
        data[offset + i] = ((value shr shift) and 0xFF).toByte()
      }
    }
  }
}

/**
 * Zigzag order: coefficient index -> row-major index in the 8x8 block, where the
 * row-major index is `v * 8 + u`. Mirrors the ordering `jpg_glitch.py` walks, so
 * bit N of a DQT payload means the same thing in the app as in the Python tool.
 */
private val ZIGZAG_ROW_MAJOR = intArrayOf(
  0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
  12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
  35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
  58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
)

/** Horizontal half-cycles per block of each zigzag coefficient. */
internal val QUANT_U = IntArray(64) { ZIGZAG_ROW_MAJOR[it] % 8 }

/** Vertical half-cycles per block of each zigzag coefficient. */
internal val QUANT_V = IntArray(64) { ZIGZAG_ROW_MAJOR[it] / 8 }
