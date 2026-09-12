package ca.justinmo.glitch

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.ui.previewFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame every screen's photo is drawn in, checked here because it is the one answer
 * four screens lay their furniture out from and nobody can see it is wrong: the picture
 * looks plausible either way, and what goes missing is the buttons - which is exactly what
 * happened with a landscape photo, blown up to the height of the window with two thirds of
 * its width cut off and the action row drawn over it because the pane had no room left.
 *
 * The pane is a Pixel 7a's: 411 x 772 dp, which is taller than a 9:16 frame of its own
 * width (730.7 dp), so a stories-shaped photo fits inside it with room under it for the
 * buttons - and [SHORT_PANE] is the same phone with a keyboard or a shorter window, where
 * it does not.
 */
class PreviewFrameTest {

  @Test
  fun aLandscapePhotoIsShownWholeCentredWithRoomBelowIt() {
    // 4:3, the commonest landscape there is.
    val frame = previewFrame(4000, 3000, PANE_WIDTH, PANE_HEIGHT)

    assertFalse("a landscape photo is never covered", frame.coversPane)
    assertEquals("full width", 411f, frame.photoWidth.value, 0.5f)
    // 411 * 3000 / 4000: the whole photograph, at the width of the pane.
    assertEquals("its own shape", 308.25f, frame.photoHeight.value, 0.5f)
    assertEquals("centred: nothing is trimmed off either side", 0f, frame.left.value, 0.01f)
    assertEquals("the rest of the pane", 463.75f, frame.roomBelow.value, 0.5f)
    assertTrue("so the controls get a row of their own below it", frame.hasRoomBelow)
  }

  @Test
  fun aShortPhotoIsCentredAboveTheControlsRatherThanStuckToTheTop() {
    val frame = previewFrame(4000, 3000, PANE_WIDTH, PANE_HEIGHT)

    assertTrue("there is room for the controls under it", frame.stacksControls)
    // The photo is centred in what the controls leave: (772 - 260 - 308.25) / 2.
    assertEquals("moved down out of the top corner", 101.87f, frame.top.value, 0.5f)
    // And its bottom edge is still comfortably above the band the controls take.
    val photoBottom = frame.top.value + frame.photoHeight.value
    assertEquals("the controls start below it", 512f, PANE_HEIGHT.value - 260f, 0.01f)
    assertTrue("the picture ends above them", photoBottom < PANE_HEIGHT.value - 260f)
  }

  @Test
  fun aStoriesPhotoKeepsTheTopAndTheControlsGoOverIt() {
    val frame = previewFrame(1080, 1920, PANE_WIDTH, PANE_HEIGHT)

    assertFalse("it leaves nothing like a screen's worth of room", frame.stacksControls)
    assertEquals("so it is not moved", 0f, frame.top.value, 0.01f)
    assertTrue("and the controls ride it, as they always have", frame.hasRoomBelow)
  }

  @Test
  fun aPortraitPhotoThatLeavesLessThanTheControlsNeedKeepsTheTopToo() {
    // 3:4 leaves 224 dp under it in this pane: less than the 260 the controls take, so
    // stacking them would push them over the picture's own bottom edge anyway.
    val frame = previewFrame(3000, 4000, PANE_WIDTH, PANE_HEIGHT)

    assertEquals("3:4 at full width", 548f, frame.photoHeight.value, 0.5f)
    assertFalse("not enough room to stack", frame.stacksControls)
    assertEquals("so the picture keeps the top", 0f, frame.top.value, 0.01f)
  }

  @Test
  fun aStoriesPhotoFillsTheWidthAndLeavesTheRestForTheButtons() {
    val frame = previewFrame(1080, 1920, PANE_WIDTH, PANE_HEIGHT)

    assertFalse("9:16 fits a pane taller than 9:16", frame.coversPane)
    assertEquals("full width", 411f, frame.photoWidth.value, 0.5f)
    assertEquals("9:16 of that width", 730.67f, frame.photoHeight.value, 0.5f)
    assertEquals("centred", 0f, frame.left.value, 0.01f)
    assertTrue("the buttons keep the band underneath", frame.hasRoomBelow)
  }

  @Test
  fun aPhotoThatCannotFitThePaneIsStillCovered() {
    val frame = previewFrame(1080, 1920, PANE_WIDTH, SHORT_PANE)

    assertTrue("9:16 is taller than a short window", frame.coversPane)
    assertEquals("full width, and the overflow is trimmed", 411f, frame.photoWidth.value, 0.5f)
    assertEquals("taller than the pane it covers", 730.67f, frame.photoHeight.value, 0.5f)
    assertFalse("no room under a covered pane", frame.hasRoomBelow)
  }

  @Test
  fun aPhotoTooTallForItsPaneIsLetterboxedRatherThanCut() {
    // A very tall picture (1:3) in a window wide enough for it: covering it would cut
    // content off the top and bottom for no reason, so it is fitted, and "fitted" here
    // means bars at the sides - which is the case where the photo is not full width.
    val frame = previewFrame(1000, 3000, 800.dp, 1500.dp)

    assertFalse("the pane is tall enough for a 9:16 frame", frame.coversPane)
    assertEquals("fitted by height", 500f, frame.photoWidth.value, 0.5f)
    assertEquals("the whole pane's height", 1500f, frame.photoHeight.value, 0.5f)
    assertEquals("and centred between the bars", 150f, frame.left.value, 0.5f)
    assertFalse("nothing left over under it", frame.hasRoomBelow)
  }

  @Test
  fun anUnmeasuredPhotoStandsInForTheWholePane() {
    // What the layout is settled on before the first decode lands: the pane's own shape,
    // and no photo to measure, so the pane is covered rather than left half empty.
    val frame = previewFrame(null, PANE_WIDTH, SHORT_PANE)

    assertEquals("the whole pane", 411f, frame.photoWidth.value, 0.5f)
    assertEquals("the whole pane", SHORT_PANE.value, frame.photoHeight.value, 0.5f)
    assertEquals("centred", 0f, frame.left.value, 0.01f)
    assertFalse("and no room under it", frame.hasRoomBelow)
  }

  @Test
  fun theHeaderLaysTheScreenOutBeforeTheDecodeLands() {
    // The whole point of taking the photo's size out of the file's header: a screen that has
    // not decoded anything yet can still put its furniture where the picture is going to be,
    // instead of where a stand-in the size of the pane would be and then move it.
    val beforeDecode = previewFrame(null, PANE_WIDTH, PANE_HEIGHT, expectedSize = 4000 to 3000)
    val afterDecode = previewFrame(4000, 3000, PANE_WIDTH, PANE_HEIGHT)

    assertEquals("full width either way", afterDecode.photoWidth, beforeDecode.photoWidth)
    assertEquals("the same height", afterDecode.photoHeight, beforeDecode.photoHeight)
    assertEquals("the same place", afterDecode.top, beforeDecode.top)
    assertEquals("the same room for the buttons", afterDecode.roomBelow, beforeDecode.roomBelow)
    assertEquals("and the same verdict on them", afterDecode.stacksControls, beforeDecode.stacksControls)
  }

  @Test
  fun theHeaderAlsoSavesAPortraitScreenFromThePaneFillingStandIn() {
    // 9:16 on a phone is the case where the stand-in gets it almost right, which is why this
    // went unnoticed: the furniture only moves by the band the buttons live in.
    val beforeDecode = previewFrame(null, PANE_WIDTH, PANE_HEIGHT, expectedSize = 1080 to 1920)

    assertTrue("room under the photo from the first frame", beforeDecode.hasRoomBelow)
    assertEquals("9:16 of the width", 730.67f, beforeDecode.photoHeight.value, 0.5f)
  }

  /** The pane a phone leaves once the button row has its own height: a Pixel 7a's window. */
  private val PANE_WIDTH: Dp = 411.dp
  private val PANE_HEIGHT: Dp = 772.dp

  /** The same window too short to hold a 9:16 frame of that width. */
  private val SHORT_PANE: Dp = 700.dp
}
