package ca.justinmo.glitch.jpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the named behaviours a code can be swapped to.
 *
 * This is what replaced the raw 0-255 slider: a row offers the handful of
 * meanings worth having, never the value it already holds, never the same byte
 * twice, and never anything that would stop the file opening.
 */
class HuffmanAlternativesTest {

  private fun ac(run: Int, size: Int, index: Int = 5): HuffmanSymbol {
    val data = ByteArray(18)
    data[17] = ((run shl 4) or size).toByte()
    return HuffmanSymbol(1, 0, index, 17, 6, data)
  }

  private fun dc(category: Int, index: Int = 3): HuffmanSymbol {
    val data = ByteArray(18)
    data[17] = category.toByte()
    return HuffmanSymbol(0, 0, index, 17, 4, data)
  }

  private fun values(symbol: HuffmanSymbol) =
    HuffmanAlternatives.forSymbol(symbol).map { it.value }

  private fun behaviourOf(symbol: HuffmanSymbol, behaviour: HuffmanBehaviour): Int? =
    HuffmanAlternatives.forSymbol(symbol).firstOrNull { it.behaviour == behaviour }?.value

  @Test
  fun aDetailCodeOffersTheStructuralChangesAndOneStepEachWay() {
    val symbol = ac(run = 3, size = 4)
    val offered = HuffmanAlternatives.forSymbol(symbol).map { it.behaviour }

    assertTrue("end the block", HuffmanBehaviour.END_OF_BLOCK in offered)
    assertTrue("sixteen zeros", HuffmanBehaviour.ZERO_RUN in offered)
    assertTrue("value wider", HuffmanBehaviour.VALUE_WIDER in offered)
    assertTrue("value narrower", HuffmanBehaviour.VALUE_NARROWER in offered)
    assertTrue("skip further", HuffmanBehaviour.SKIP_FURTHER in offered)
    assertTrue("skip earlier", HuffmanBehaviour.SKIP_EARLIER in offered)

    assertEquals("ends the block means byte 0", 0x00, behaviourOf(symbol, HuffmanBehaviour.END_OF_BLOCK))
    assertEquals("sixteen zeros means byte 0xF0", 0xF0, behaviourOf(symbol, HuffmanBehaviour.ZERO_RUN))
    // run 3, size 4 = 0x34; one step on either nibble.
    assertEquals("value +1 bit", 0x35, behaviourOf(symbol, HuffmanBehaviour.VALUE_WIDER))
    assertEquals("value -1 bit", 0x33, behaviourOf(symbol, HuffmanBehaviour.VALUE_NARROWER))
    assertEquals("skip +1", 0x44, behaviourOf(symbol, HuffmanBehaviour.SKIP_FURTHER))
    assertEquals("skip -1", 0x24, behaviourOf(symbol, HuffmanBehaviour.SKIP_EARLIER))
  }

  @Test
  fun theCurrentValueIsNeverOffered() {
    for (run in 0..15) {
      for (size in 0..15) {
        val symbol = ac(run, size)
        val offered = values(symbol)
        assertFalse(
          "run $run size $size must not offer its own value ${run shl 4 or size}",
          (run shl 4 or size) in offered,
        )
      }
    }
    for (category in 0..15) {
      assertFalse("category $category", category in values(dc(category)))
    }
  }

  @Test
  fun noAlternativeIsOfferedTwice() {
    for (run in 0..15) {
      for (size in 0..15) {
        val offered = values(ac(run, size))
        assertEquals("run $run size $size", offered.size, offered.toSet().size)
      }
    }
    for (category in 0..15) {
      val offered = values(dc(category))
      assertEquals("category $category", offered.size, offered.toSet().size)
    }
  }

  @Test
  fun theEdgesOfTheNibblesAreRespected() {
    // A code that already ends the block: no narrower value, and byte 0x00 is
    // its own value so it is not offered either.
    val eob = ac(run = 0, size = 0)
    assertFalse("no narrower value", HuffmanBehaviour.VALUE_NARROWER in
      HuffmanAlternatives.forSymbol(eob).map { it.behaviour })
    assertFalse("no earlier skip", HuffmanBehaviour.SKIP_EARLIER in
      HuffmanAlternatives.forSymbol(eob).map { it.behaviour })

    val widest = ac(run = 15, size = 15)
    assertFalse("no wider value at size 15", HuffmanBehaviour.VALUE_WIDER in
      HuffmanAlternatives.forSymbol(widest).map { it.behaviour })
    assertFalse("no further skip at run 15", HuffmanBehaviour.SKIP_FURTHER in
      HuffmanAlternatives.forSymbol(widest).map { it.behaviour })

    // Every offered value is a legal byte for the table.
    for (run in 0..15) {
      for (size in 0..15) {
        val symbol = ac(run, size)
        for (value in values(symbol)) {
          assertTrue("run $run size $size offers $value", value in 0..255)
        }
      }
    }
  }

  @Test
  fun aLevelCodeOnlyStepsUpDownOrFlat() {
    val middle = dc(category = 5)
    val offered = HuffmanAlternatives.forSymbol(middle).map { it.behaviour }
    assertEquals(
      "three choices in the middle",
      listOf(HuffmanBehaviour.LEVEL_WIDER, HuffmanBehaviour.LEVEL_NARROWER, HuffmanBehaviour.LEVEL_FLAT),
      offered,
    )

    // At zero there is nothing narrower, and "flat" is the value it already
    // holds, so exactly one choice survives.
    val flat = dc(category = 0)
    assertEquals(
      listOf(HuffmanBehaviour.LEVEL_WIDER),
      HuffmanAlternatives.forSymbol(flat).map { it.behaviour },
    )

    // Nothing may ask for a category the decoder rejects (16 and up).
    val top = dc(category = HuffmanSymbol.DC_MAX_CATEGORY)
    assertFalse(
      "no wider at the ceiling",
      HuffmanBehaviour.LEVEL_WIDER in HuffmanAlternatives.forSymbol(top).map { it.behaviour },
    )
    for (category in 0..15) {
      for (value in values(dc(category))) {
        assertTrue("category $category offers $value", value in 0..HuffmanSymbol.DC_MAX_CATEGORY)
      }
    }
  }

  @Test
  fun aRowIsADecisionNotASlider() {
    for (run in 0..15) {
      for (size in 0..15) {
        val offered = values(ac(run, size))
        assertTrue(
          "run $run size $size offers ${offered.size} choices",
          offered.size <= HuffmanAlternatives.MAX_CHOICES,
        )
        assertTrue("at least two ways out of any code", offered.size >= 2)
      }
    }
  }

  // --- Chaining -------------------------------------------------------------

  private fun step(value: Int, behaviour: HuffmanBehaviour, tableClass: Int = 1): Int {
    val choice = HuffmanAlternatives.forValue(tableClass, value)
      .firstOrNull { it.behaviour == behaviour }
    assertTrue("$behaviour is offered from $value", choice != null)
    return choice!!.value
  }

  /** Taps [behaviour] until it is no longer offered, and reports where it stopped. */
  private fun walkToEnd(value: Int, behaviour: HuffmanBehaviour, tableClass: Int = 1): Int {
    var current = value
    repeat(64) {
      val next = HuffmanAlternatives.forValue(tableClass, current)
        .firstOrNull { it.behaviour == behaviour } ?: return current
      current = next.value
    }
    return current
  }

  @Test
  fun repeatedTapsCarryOnFromWhereTheLastOneLanded() {
    // The screen derives the choices from the value in force, so a second tap
    // continues the walk rather than re-offering the first step. This is the
    // behaviour the screen shipped without, and it is what makes a value a few
    // steps away reachable without the raw slider.
    var value = 0x41 // run 4, size 1 - a six-bit code of the standard table
    value = step(value, HuffmanBehaviour.SKIP_FURTHER)
    assertEquals("run 5, size 1", 0x51, value)
    value = step(value, HuffmanBehaviour.SKIP_FURTHER)
    assertEquals("run 6, size 1", 0x61, value)
    assertEquals("which is the value 97", 97, value)

    // And the walk is visible to the user as it goes: the choice just taken is
    // no longer on offer, because it is now the current value.
    assertFalse("97 is not offered from 97", 97 in values(ac(6, 1)))
    assertTrue(
      "but the next step is",
      HuffmanBehaviour.SKIP_FURTHER in
        HuffmanAlternatives.forValue(1, 97).map { it.behaviour },
    )
  }

  @Test
  fun anyValueIsReachableByTapping() {
    // Walking down the value nibble from 0x6F gets to 0x61, and up the skip
    // nibble from 0x01 gets to 0x61 as well: the named choices span the byte.
    var value = 0x6F
    repeat(14) { value = step(value, HuffmanBehaviour.VALUE_NARROWER) }
    assertEquals("all the way down to run 6, size 1", 0x61, value)

    value = 0x01
    repeat(6) { value = step(value, HuffmanBehaviour.SKIP_FURTHER) }
    assertEquals("and up from run 0", 0x61, value)

    // A step that would leave the nibble is simply not offered any more, which
    // is why the walk ends instead of wrapping round.
    assertEquals("run stops at 15", 0xFF, walkToEnd(0x0F, HuffmanBehaviour.SKIP_FURTHER))
    assertEquals("size stops at 15", 0x0F, walkToEnd(0x01, HuffmanBehaviour.VALUE_WIDER))
    // Byte 0x00 *is* end of block, so the last step down is offered under that
    // name rather than twice, and the narrower walk stops one short of it.
    assertEquals("narrowing stops at size 1", 0x01, walkToEnd(0x0F, HuffmanBehaviour.VALUE_NARROWER))
    assertEquals(
      "and end of block is the same byte",
      0x00,
      step(0x01, HuffmanBehaviour.END_OF_BLOCK),
    )
  }

  @Test
  fun aLevelWalkStopsAtTheCeilingTheDecoderAccepts() {
    assertEquals(
      "never past what the decoder accepts",
      HuffmanSymbol.DC_MAX_CATEGORY,
      walkToEnd(0, HuffmanBehaviour.LEVEL_WIDER, tableClass = 0),
    )
    assertEquals(
      "and back to zero",
      0,
      walkToEnd(HuffmanSymbol.DC_MAX_CATEGORY, HuffmanBehaviour.LEVEL_NARROWER, tableClass = 0),
    )
  }

  @Test
  fun forSymbolFollowsTheValueInTheFile() {
    // The convenience overload has to agree with the value-based one, since the
    // screen edits by value and only the tests and previews still hold symbols.
    for (run in 0..15) {
      for (size in 0..15) {
        val symbol = ac(run, size)
        assertEquals(
          "run $run size $size",
          HuffmanAlternatives.forValue(1, symbol.value).map { it.value },
          HuffmanAlternatives.forSymbol(symbol).map { it.value },
        )
      }
    }
  }
}
