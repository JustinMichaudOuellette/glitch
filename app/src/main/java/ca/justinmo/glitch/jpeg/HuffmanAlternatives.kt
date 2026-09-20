package ca.justinmo.glitch.jpeg

/**
 * What a code can be made to mean. These are the edits worth offering: a symbol
 * is a packed pair, and only a handful of the 256 values it could hold say
 * something a user can picture.
 *
 * The labels live in string resources; this enum is the vocabulary both the
 * screen and its tests use.
 */
enum class HuffmanBehaviour {
  /** Skip 0 with a size of 0: the block stops here. */
  END_OF_BLOCK,

  /** Skip 15 with a size of 0: sixteen zero coefficients in a row. */
  ZERO_RUN,

  /** The same skip, one more bit of value: the coefficient lands bigger. */
  VALUE_WIDER,

  /** The same skip, one less bit of value: it lands smaller. */
  VALUE_NARROWER,

  /** One further along the block, same value width. */
  SKIP_FURTHER,

  /** One earlier in the block, same value width. */
  SKIP_EARLIER,

  /** Skip nothing, one-bit value: the smallest detail a block can carry. */
  TINY_DETAIL,

  /** A level one bit wider. */
  LEVEL_WIDER,

  /** A level one bit narrower. */
  LEVEL_NARROWER,

  /** A level of zero: the block average, and nothing about it. */
  LEVEL_FLAT,
}

/** One way to rewrite a symbol: the byte to write, and what that makes it mean. */
class HuffmanAlternative(val value: Int, val behaviour: HuffmanBehaviour) {
  override fun toString(): String = "$behaviour=$value"
}

/**
 * The alternatives offered for a code, in the order the screen shows them.
 *
 * A detail symbol is `run << 4 | size`, so the useful rewrites are structural
 * (end the block, skip sixteen) or a one-step move along either nibble. A level
 * symbol is a category, so it only steps up, down, or to zero.
 *
 * The current value is never offered, alternatives that land on the same byte
 * are collapsed, and the list is capped: a row of six named choices is a
 * decision, a row of two hundred and fifty-six is a slider.
 */
object HuffmanAlternatives {

  /** Most choices a row offers. */
  const val MAX_CHOICES = 6

  /** The choices for a code as it stands in the file. */
  fun forSymbol(symbol: HuffmanSymbol): List<HuffmanAlternative> =
    forValue(symbol.tableClass, symbol.value)

  /**
   * The choices for a code that currently means [value] in a [tableClass] table.
   *
   * This takes the **current** value, not the one the file shipped with, so
   * repeated taps walk: choosing "value +1 bit" and then "value +1 bit" again
   * lands two steps away, and any byte is reachable by tapping. Deriving the
   * choices from the original value instead would quietly stop the second tap
   * from doing anything, which is how the screen behaved before this took the
   * value as an argument.
   */
  fun forValue(tableClass: Int, value: Int): List<HuffmanAlternative> {
    val candidates = if (tableClass == 0) {
      levelAlternatives(value and 0xFF)
    } else {
      detailAlternatives(value and 0xFF)
    }
    val seen = HashSet<Int>()
    val out = ArrayList<HuffmanAlternative>(MAX_CHOICES)
    for (candidate in candidates) {
      if (candidate.value == (value and 0xFF)) continue
      if (!seen.add(candidate.value)) continue
      out.add(candidate)
      if (out.size == MAX_CHOICES) break
    }
    return out
  }

  private fun detailAlternatives(value: Int): List<HuffmanAlternative> {
    val run = (value shr 4) and 0x0F
    val size = value and 0x0F
    val packed = { r: Int, s: Int -> ((r and 0x0F) shl 4) or (s and 0x0F) }
    return buildList {
      add(HuffmanAlternative(0x00, HuffmanBehaviour.END_OF_BLOCK))
      add(HuffmanAlternative(0xF0, HuffmanBehaviour.ZERO_RUN))
      if (size < 15) add(HuffmanAlternative(packed(run, size + 1), HuffmanBehaviour.VALUE_WIDER))
      if (size > 0) add(HuffmanAlternative(packed(run, size - 1), HuffmanBehaviour.VALUE_NARROWER))
      if (run < 15) add(HuffmanAlternative(packed(run + 1, size), HuffmanBehaviour.SKIP_FURTHER))
      if (run > 0) add(HuffmanAlternative(packed(run - 1, size), HuffmanBehaviour.SKIP_EARLIER))
      add(HuffmanAlternative(0x01, HuffmanBehaviour.TINY_DETAIL))
    }
  }

  private fun levelAlternatives(category: Int): List<HuffmanAlternative> {
    return buildList {
      if (category < HuffmanSymbol.DC_MAX_CATEGORY) {
        add(HuffmanAlternative(category + 1, HuffmanBehaviour.LEVEL_WIDER))
      }
      if (category > 0) {
        add(HuffmanAlternative(category - 1, HuffmanBehaviour.LEVEL_NARROWER))
      }
      add(HuffmanAlternative(0, HuffmanBehaviour.LEVEL_FLAT))
    }
  }
}
