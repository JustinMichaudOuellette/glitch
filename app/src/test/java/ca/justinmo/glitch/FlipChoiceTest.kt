package ca.justinmo.glitch

import ca.justinmo.glitch.dht.FlipChoice
import ca.justinmo.glitch.dht.SweepOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DHT screen's flip is meant to be aimed rather than lucky: it should land on
 * the bits the sweep found worth having most of the time, and on the ones that
 * wrecked a picture only rarely - while still being able to land there at all.
 *
 * Those are properties of [FlipChoice] alone - the weights and the walk through them
 * - so they are checked here rather than by pressing Flip on a phone.
 */
class FlipChoiceTest {

  /** One symbol's eight bits, categorised the way the strip categorises them. */
  private fun bits(vararg outcomes: SweepOutcome): List<Pair<Int, SweepOutcome>> =
    outcomes.mapIndexed { bit, outcome -> bit to outcome }

  @Test
  fun aBitTheSweepCallsFatalIsNeverChosen() {
    val onlyFatal = bits(SweepOutcome.FATAL, SweepOutcome.FATAL)
    for (step in 0 until 50) {
      assertNull("a fatal bit is not offered", FlipChoice.choose(onlyFatal, step))
    }
    // And not when it is sitting beside usable bits either.
    val mixed = bits(SweepOutcome.FATAL, SweepOutcome.ONE, SweepOutcome.FATAL)
    for (step in 0 until 50) {
      assertEquals("only the usable bit is ever taken", 1, FlipChoice.choose(mixed, step))
    }
  }

  @Test
  fun theGoodCategoriesComeUpMoreOftenThanTheWreckedOnes() {
    val outcomes = listOf(
      SweepOutcome.NOTHING, SweepOutcome.ONE, SweepOutcome.TWO, SweepOutcome.THREE,
      SweepOutcome.FOUR, SweepOutcome.WRECK_ONE, SweepOutcome.WRECK_TWO, SweepOutcome.WRECK_ALL,
    )
    val candidates = bits(*outcomes.toTypedArray())
    val taken = (0 until 8000).mapNotNull { FlipChoice.choose(candidates, it) }
    assertEquals("every press lands on a bit", 8000, taken.size)

    val chosen = outcomes.indices.map { bit -> taken.count { it == bit } }
    val good = chosen.take(5).sum()
    val wrecked = chosen.drop(5).sum()
    assertTrue("the good categories dominate (good $good, wrecked $wrecked)", good > wrecked * 4)
    assertTrue("and the wrecked ones are still reachable", wrecked > 0)
    assertTrue("nothing is skipped entirely", chosen.min() > 0)

    // The loudest good category beats the quietest one.
    assertTrue("FOUR comes up more than ONE", chosen[4] > chosen[1])
    assertTrue("and NOTHING comes up least of the good ones", chosen[0] < chosen[1])
  }

  @Test
  fun theSamePressAlwaysTakesTheSameBit() {
    val candidates = bits(
      SweepOutcome.ONE, SweepOutcome.WRECK_ALL, SweepOutcome.NOTHING,
      SweepOutcome.FOUR, SweepOutcome.TWO, SweepOutcome.WRECK_ONE,
      SweepOutcome.THREE, SweepOutcome.ONE,
    )
    val first = (0 until 64).map { FlipChoice.choose(candidates, it) }
    val again = (0 until 64).map { FlipChoice.choose(candidates, it) }
    assertEquals("the choice is not random", first, again)
    assertTrue("and it is not stuck on one bit", first.toSet().size > 1)
  }
}
