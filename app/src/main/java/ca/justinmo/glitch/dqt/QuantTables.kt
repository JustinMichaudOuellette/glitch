package ca.justinmo.glitch.dqt

import ca.justinmo.glitch.jpeg.JpegStructure
import kotlin.collections.iterator
import kotlin.random.Random

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
  /**
   * Whether this quantizer can change the picture: its coefficient is non-zero
   * somewhere in the file (see [QuantTables.parse]). A dead entry is kept in
   * [QuantTable.entries] - the writer still needs its byte layout - but the
   * screen does not offer it.
   */
  val live: Boolean = true,
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
  /** Entries of [axis] whose quantizer can affect the picture. */
  fun entriesFor(axis: QuantAxis): List<QuantEntry> =
    entries.filter { it.axis == axis && it.live }

  /** All entries of [axis], including ones the picture never uses. */
  fun allEntriesFor(axis: QuantAxis): List<QuantEntry> = entries.filter { it.axis == axis }

  /** Every quantizer of this table that can affect the picture. */
  val liveEntries: List<QuantEntry> get() = entries.filter { it.live }

  override fun toString(): String =
    "QuantTable(id=$id, ${if (isChroma) "chroma" else "luma"}, ${entries.size} entries)"
}

/**
 * Lowest bit the DQT screen's random flip may touch.
 *
 * Flipping bit 0 can leave the *effective* quantizer unchanged - 0 and 1 both
 * decode as 1 - and the low bits can change the value by so little that the
 * IDCT change rounds away even on a coefficient this picture has. Bit 4 moves
 * the decoded quantizer by at least 15 (16 when it goes through zero), which is
 * at least a couple of levels in the affected block.
 */
internal const val QUANT_FLIP_MIN_BIT = 4

/**
 * Flip one randomly chosen high bit of [current], staying inside [bitCount]
 * bits. The bit is random among the ones that can still move a pixel; see
 * [QUANT_FLIP_MIN_BIT].
 */
internal fun flipQuantizerBit(current: Int, bitCount: Int, random: Random): Int {
  val first = QUANT_FLIP_MIN_BIT.coerceAtMost(bitCount - 1).coerceAtLeast(0)
  val choices = (bitCount - first).coerceAtLeast(1)
  return current xor (1 shl (first + random.nextInt(choices)))
}

/**
 * Reads the quantization tables the picture actually uses out of a parsed JPEG.
 *
 * [ca.justinmo.glitch.jpeg.JpegStructure.dqt] holds one span per DQT segment, payload only, so a segment
 * may carry several tables (8-bit or 16-bit). Nothing here flips anything: the
 * caller edits [QuantEntry.offset] bytes and writes the array back.
 *
 * A DQT segment may define tables no component ever selects - leftovers from an
 * editor, or a chroma table in a grayscale file - and those quantizers have no
 * effect on the decoded picture. They are dropped here rather than handed to the
 * DQT screen, which would otherwise offer sliders whose edits change bytes the
 * decoder never reads. A table redefined between scans keeps every definition a
 * scan actually decoded with; one defined twice before the first scan keeps only
 * the last, which is the one the decoder ends up using.
 *
 * [liveMasks] carries the second half of the same question, straight from the
 * decoder: per table id, a bitmask of the coefficients that are non-zero in at
 * least one block. A coefficient that is zero everywhere decodes to zero whatever
 * its quantizer says, so its row is marked dead and the screen does not draw it.
 * Null means "not measured" - every entry is taken as live.
 */
object QuantTables {

  /** Table id the luma component uses, so the rest can be called chroma. */
  private fun lumaTableId(structure: JpegStructure): Int =
    structure.frame?.components?.firstOrNull()?.quantTable ?: 0

  fun parse(
    data: ByteArray,
    structure: JpegStructure,
    liveMasks: LongArray? = null,
  ): List<QuantTable> {
    val lumaId = lumaTableId(structure)
    val parsed = ArrayList<QuantTable>(2)
    for (span in structure.dqt) {
      var p = span.start
      while (p < span.endExclusive) {
        val spec = data[p].toInt() and 0xFF
        val precision = (spec shr 4) and 0x0F
        val id = spec and 0x0F
        val byteCount = when (precision) {
          0 -> 1
          1 -> 2
          else -> return usedTables(parsed, structure) // reserved/invalid precision: stop here
        }
        if (p + 1 + 64 * byteCount > span.endExclusive) {
          return usedTables(parsed, structure) // truncated
        }
        val tableMask = liveMasks?.getOrNull(id)
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
              // A mask the decoder did not report for this id is left permissive:
              // hiding a row is only safe when its coefficient is known dead.
              live = tableMask == null || ((tableMask ushr coefficient) and 1L) != 0L,
            ),
          )
        }
        parsed.add(
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
    return usedTables(parsed, structure)
  }

  /**
   * Narrows [parsed] to the definitions the decoder applies to this frame.
   *
   * Without a frame header there is nothing to resolve the selectors against, so
   * everything parsed is kept (which is also what previews and partially parsed
   * files need). Otherwise a table id nothing selects is dropped, and a repeated
   * id keeps only the definitions a scan was decoded with: for each scan, the
   * last definition before its SOS marker.
   */
  private fun usedTables(parsed: List<QuantTable>, structure: JpegStructure): List<QuantTable> {
    val frame = structure.frame ?: return parsed
    val usedIds = frame.components.mapTo(HashSet()) { it.quantTable }
    val referenced = parsed.filter { it.id in usedIds }
    if (referenced.isEmpty()) return emptyList()
    // No scan headers to place the definitions between (a truncated walk): the
    // last definition of each referenced id is the table the decoder is left with.
    if (structure.scans.isEmpty()) return lastDefinitionPerId(referenced)

    val tableIdByComponent = frame.components.associate { it.id to it.quantTable }
    val live = HashSet<Int>()
    for (scan in structure.scans) {
      for (component in scan.components) {
        val tableId = tableIdByComponent[component.id] ?: continue
        val inEffect = referenced.lastOrNull {
          it.id == tableId && it.specOffset < scan.markerOffset
        } ?: continue
        live.add(inEffect.specOffset)
      }
    }
    // A component no scan reaches keeps its last definition rather than losing its
    // rows: better a slider that may do nothing than a knob the file actually has
    // but the screen cannot show.
    val scannedIds = structure.scans.asSequence()
      .flatMap { it.components.asSequence() }
      .mapNotNull { tableIdByComponent[it.id] }
      .toSet()
    for (id in usedIds - scannedIds) {
      referenced.lastOrNull { it.id == id }?.let { live.add(it.specOffset) }
    }
    return referenced.filter { it.specOffset in live }
  }

  /** The last definition of each table id, in the order those definitions appear. */
  private fun lastDefinitionPerId(tables: List<QuantTable>): List<QuantTable> =
    tables.reversed().distinctBy { it.id }.reversed()

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
