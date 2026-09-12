package ca.justinmo.glitch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which decoded frame a screen may draw before its own decode has landed.
 *
 * This is the rule that decides whether the shared preview hands a step the picture the last
 * step was showing, so getting it wrong is not a wrong-looking frame - it is the *right*
 * picture from a file that is no longer underneath, laid out as if it were. The holder itself
 * needs a real decoded bitmap and so cannot be built here; what is checked is the shape test it
 * decides by, which is the part with a decision in it.
 */
class PreviewImageTest {

  @Test
  fun aStepWhoseEditKeepsTheShapeMayShowTheLastStepsFrame() {
    // The scan screen's flips and the DQT screen's quantizers both produce a new file with the
    // same proportions, which is the case the reuse exists for: the picture on screen is the
    // one about to be shown, one edit behind.
    assertTrue(sameShape(2000, 1500, 4000 to 3000))
    assertTrue(sameShape(1400, 1400, 800 to 800))
  }

  @Test
  fun aCropIsNeverShownAsThePhotoItWasCutFrom() {
    // The resize screen's preview is its export: 9:16 out of a 4:3 photo. Laying the sliders
    // out for one shape and drawing the other is exactly what the shape test is for.
    assertFalse(sameShape(2000, 1500, 1080 to 1920))
    assertFalse(sameShape(1080, 1920, 4000 to 3000))
  }

  @Test
  fun aDifferentShapeOfTheSameOrientationIsStillADifferentShape() {
    // 3:4 and 9:16 are both portrait, and neither stands in for the other: the frame the
    // furniture is placed against differs by a third of the width.
    assertFalse(sameShape(3000, 4000, 1080 to 1920))
  }

  @Test
  fun nothingIsReusedWhenTheCallerDoesNotKnowTheShape() {
    assertFalse(sameShape(2000, 1500, null))
  }

  @Test
  fun aDegenerateShapeIsNotAShape() {
    assertFalse(sameShape(2000, 1500, 0 to 0))
    assertFalse(sameShape(0, 0, 2000 to 1500))
  }
}
