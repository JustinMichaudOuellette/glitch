package ca.justinmo.glitch.dht

import kotlin.random.Random

/**
 * Which bit of a symbol a press of Flip should take, given what the `b_x4` sweep
 * categorised each of them as.
 *
 * Flipping at random is how the editor explores, and on a Huffman table it is a bad
 * way to explore: the eight bits of one symbol are not equivalent. Some change a
 * level by a step, some rewrite a run, some stop the file decoding altogether - and
 * `DHT.md` measured which is which, on four photos, and the strip on the screen
 * draws that measurement under every row. This is the same measurement used as a
 * prior rather than as a label: an edit is *chosen* from the bits that did something
 * good, and only occasionally from the ones that wrecked a picture.
 *
 * The wrecked bits are rare, not impossible. Two of them are the strongest edits the
 * sweep found, the classification's own worked example - luma detail symbol #10,
 * 65 to 97 - is filed as wrecking two of the four photos, and a taste that never
 * reached it would be a worse tool. Rare means "you will not land there by
 * accident", which is exactly what a weighted choice buys.
 */
internal object FlipChoice {

  /**
   * How often a bit categorised like this should be chosen, relative to the others.
   *
   * The scale is arbitrary; only the ratios are read. What matters is the order:
   * a bit that changed a photo and left it a photo outranks one that changed
   * nothing, and both outrank one that wrecked a sample - while a wreck still
   * outranks the bits the sweep called fatal, which are not offered at all because
   * a file with one flipped does not decode.
   */
  fun weight(outcome: SweepOutcome): Int = when (outcome) {
    // Never: the sweep has no picture for these, and neither would the screen.
    SweepOutcome.FATAL -> 0
    // Harmless, and pointless: the file is byte-different and the picture is not.
    SweepOutcome.NOTHING -> 1
    // The good ones: the picture changed and survived. The more samples it changed,
    // the louder the edit, so the more often it is worth landing on.
    SweepOutcome.ONE -> 4
    SweepOutcome.TWO -> 6
    SweepOutcome.THREE -> 8
    SweepOutcome.FOUR -> 10
    // Wrecked a picture: reachable on purpose, unlikely by accident.
    SweepOutcome.WRECK_ONE -> 2
    SweepOutcome.WRECK_TWO -> 2
    SweepOutcome.WRECK_THREE -> 1
    SweepOutcome.WRECK_ALL -> 1
  }

  /**
   * The bit to flip on press number [step], or null when the sweep has nothing
   * usable to say about any of [candidates].
   *
   * [candidates] is one symbol's bits, as `(bit, outcome)` in bit order. The choice
   * is weighted by [weight] and made without a random source: the same press on the
   * same row always takes the same bit, so a flip can be undone, repeated and
   * compared - which is the property the scan screen's flip has too, and the reason
   * neither of them is a dice roll.
   */
  /**
   * The bit to flip, drawn from [candidates] with a chance proportional to its
   * [weight], or null when the sweep has nothing usable to say about any of them.
   *
   * [candidates] is one symbol's bits, as `(bit, outcome)` in bit order. The draw is
   * a weighted one: a bit's chance is its weight over the pool's total, so a
   * category weighted ten comes up ten times as often as one weighted one, and a bit
   * the sweep called fatal - weight zero - is never in the pool at all. That last
   * part is what makes this more than a lottery on a real table: the sweep found the
   * DC tables around 93% fatal or wrecking, so most of a symbol's eight bits are
   * simply not in the draw, and a press is spent on one of the few that do something.
   *
   * [random] is passed in rather than taken from the ambient source, so a caller can
   * hand in its own - and a test can seed one and check the odds.
   */
  fun choose(candidates: List<Pair<Int, SweepOutcome>>, random: Random): Int? {
    val pool = candidates.filter { weight(it.second) > 0 }
    if (pool.isEmpty()) return null
    val total = pool.sumOf { weight(it.second) }
    var ticket = random.nextInt(total)
    for ((bit, outcome) in pool) {
      ticket -= weight(outcome)
      if (ticket < 0) return bit
    }
    return pool.last().first
  }

  /**
   * The same draw from a fixed sequence instead of a random source: press number
   * [step] takes the next ticket along, so the same press on the same row always
   * takes the same bit and the proportions still come out as the weights.
   *
   * Kept because it is what [FlipChoiceTest]'s determinism checks exercise; nothing
   * in the app calls it any more. Before building anything on it, decide whether a
   * flip should be reproducible at all - the screen's does not have to be, and is
   * not.
   */
  fun choose(candidates: List<Pair<Int, SweepOutcome>>, step: Int): Int? {
    val pool = candidates.filter { weight(it.second) > 0 }
    if (pool.isEmpty()) return null
    val total = pool.sumOf { weight(it.second) }
    val ticket = ((step.toLong() * 2654435761L) % total).toInt()
    var cursor = 0
    for ((bit, outcome) in pool) {
      cursor += weight(outcome)
      if (ticket < cursor) return bit
    }
    return pool.last().first
  }
}


