package ca.justinmo.glitch.jpeg

/**
 * How one bit flip of the DHT payload turned out in the `b_x4` sweep.
 *
 * The folders the sweep was sorted into, as an enum: the number is how many of
 * the four sample photos the flip visibly changed, and a wreck is a change that
 * destroyed the picture rather than altering it.
 */
enum class SweepOutcome(
  /** The character [DhtSweepData.ENCODED] uses for this outcome. */
  val code: Char,
  /** How many of the four sample photos changed. */
  val photosChanged: Int,
  /** True when the sample batch filed it as a wreck. */
  val isWreck: Boolean,
) {
  /** No sample at all: the flip stops the file decoding. */
  FATAL('-', 0, false),

  /** Decodes to a pixel-identical picture. */
  NOTHING('.', 0, false),

  /** All four photos wrecked. */
  WRECK_ALL('B', 4, true),

  /** Three of four, wrecked. */
  WRECK_THREE('Z', 3, true),

  /** Two of four, wrecked. */
  WRECK_TWO('Y', 2, true),

  /** One of four, wrecked. */
  WRECK_ONE('X', 1, true),

  /** One of the four changed, and it looked like a change rather than a wreck. */
  ONE('1', 1, false),

  /** Two of the four. */
  TWO('2', 2, false),

  /** Three of the four. */
  THREE('3', 3, false),

  /** All four, and still not a wreck. */
  FOUR('4', 4, false),
  ;

  /**
   * A change that showed on at least one sample photo without wrecking it.
   * These are the 211 edits of the sweep that were worth keeping.
   */
  val isUsable: Boolean get() = this != FATAL && this != NOTHING && !isWreck

  companion object {
    private val byCode = entries.associateBy { it.code }

    fun of(code: Char): SweepOutcome? = byCode[code]
  }
}

/**
 * One classified edit: flipping [bit] of the symbol at [offset] took it from
 * [from] to [to], and the sweep filed the result as [outcome].
 *
 * This is the unit the screen offers. A bit flip *is* a value change, so a
 * catalogue of measured bit flips is a catalogue of value changes with known
 * results - which is exactly what a user wants and what a raw 0-255 slider
 * cannot tell them.
 */
class SweepEdit(
  val tableClass: Int,
  val tableId: Int,
  /** True when the table is a chroma one, for the label. */
  val chroma: Boolean,
  val symbolIndex: Int,
  val codeLength: Int,
  /** Offset of the symbol byte, which is what an edit writes. */
  val offset: Int,
  /** The symbol's value in the file as loaded. */
  val from: Int,
  /** What it becomes. */
  val to: Int,
  /** Which bit of the byte differs. */
  val bit: Int,
  val outcome: SweepOutcome,
) {
  val isDc: Boolean get() = tableClass == 0

  override fun toString(): String =
    "SweepEdit(${if (isDc) "DC" else "AC"}${if (chroma) " chroma" else " luma"} " +
      "#$symbolIndex $from -> $to, $outcome)"
}

/**
 * The sweep's classification, as something a screen can ask questions of.
 *
 * Every answer here describes **the four sample photos** the sweep ran on, not
 * the picture the user has open. That is worth saying out loud in the UI: the
 * best evidence available for "will this wreck it" is a measurement on four
 * different images, and content dependence is real - the edit the sweep filed
 * as a wreck on two of those photos can be the one someone likes on their own.
 */
object DhtSweep {

  /** One character per payload bit, 3328 of them. */
  val ENCODED: String get() = DhtSweepData.ENCODED

  /** The payload the classification's bit indices refer to. */
  val BASELINE: ByteArray by lazy {
    val hex = DhtSweepData.BASELINE_HEX
    ByteArray(hex.length / 2) { i ->
      hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  /** Bits classified by the sweep. */
  val BIT_COUNT: Int get() = ENCODED.length

  /** What flipping [bitIndex] of the payload did. */
  fun outcomeOf(bitIndex: Int): SweepOutcome? {
    if (bitIndex !in ENCODED.indices) return null
    return SweepOutcome.of(ENCODED[bitIndex])
  }

  /**
   * True when [payload] is the payload the sweep measured.
   *
   * Every bit index in the classification means one specific byte of one
   * specific table, so a file that lays its Huffman tables out differently -
   * another encoder's optimised tables, a different segment order, a grayscale
   * file with two tables - cannot be described by the sweep at all. Rather than
   * guess at a mapping, the screen says so and offers the exact-value editor.
   */
  fun appliesTo(payload: ByteArray): Boolean = payload.contentEquals(BASELINE)

  /**
   * Every classified edit of [symbol], which sits at payload byte [payloadByte].
   * Eight of them at most: one per bit, each a different value and outcome.
   */
  fun editsFor(symbol: HuffmanSymbol, payloadByte: Int): List<SweepEdit> {
    val from = symbol.value
    return (0..7).mapNotNull { bit ->
      val outcome = outcomeOf(payloadByte * 8 + bit) ?: return@mapNotNull null
      SweepEdit(
        tableClass = symbol.tableClass,
        tableId = symbol.tableId,
        chroma = false,
        symbolIndex = symbol.index,
        codeLength = symbol.codeLength,
        offset = symbol.offset,
        from = from,
        to = from xor (1 shl bit),
        bit = bit,
        outcome = outcome,
      )
    }
  }

  /**
   * Every classified edit of every symbol in [tables], in table order.
   *
   * Only meaningful when [appliesTo] says the file matches the sweep; callers
   * check that first, which is why the chroma flag is filled in here where the
   * table is known.
   */
  fun editsFor(tables: List<HuffmanTable>): List<SweepEdit> {
    val out = ArrayList<SweepEdit>()
    for (table in tables) {
      for (symbol in table.entries) {
        val payloadByte = table.payloadIndex + 17 + symbol.index
        for (edit in editsFor(symbol, payloadByte)) {
          out.add(
            SweepEdit(
              tableClass = edit.tableClass,
              tableId = edit.tableId,
              chroma = table.isChroma,
              symbolIndex = edit.symbolIndex,
              codeLength = edit.codeLength,
              offset = edit.offset,
              from = edit.from,
              to = edit.to,
              bit = edit.bit,
              outcome = edit.outcome,
            ),
          )
        }
      }
    }
    return out
  }

  /** How many of the 3328 bits the sweep filed each way. */
  fun tally(): Map<SweepOutcome, Int> {
    val counts = HashMap<SweepOutcome, Int>()
    for (code in ENCODED) {
      val outcome = SweepOutcome.of(code) ?: continue
      counts[outcome] = (counts[outcome] ?: 0) + 1
    }
    return counts
  }
}
