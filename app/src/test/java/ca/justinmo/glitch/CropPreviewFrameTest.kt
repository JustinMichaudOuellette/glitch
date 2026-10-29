package ca.justinmo.glitch

import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.resize.cropPreviewFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop editor's pane, checked because it is the one preview in the app that must *not*
 * cover its pane: the parts of the photograph the crop leaves out are drawn dimmed, and
 * drawing the picture to fill the window would cut them off the screen instead - the user
 * would be choosing a crop without being able to see what it is choosing between.
 */
class CropPreviewFrameTest {

  private val paneWidth = 411.dp
  private val paneHeight = 716.dp

  @Test
  fun noPhotoIsClippedAndNoPhotoIsCovered() {
    val sizes = listOf(
      1080 to 1920, // stories, which fills the width of another screen
      4032 to 3024, // landscape
      3024 to 4032, // portrait
      1000 to 1000, // square
      1000 to 500, // a wide panorama
    )

    for (size in sizes) {
      val frame = cropPreviewFrame(size, paneWidth, paneHeight)
      val context = "a ${size.first}x${size.second} photo"
      assertFalse("$context is not covered", frame.coversPane)
      assertTrue("$context fits across", frame.photoWidth <= paneWidth)
      assertTrue("$context fits down", frame.photoHeight <= paneHeight)
      assertTrue("$context starts inside the pane", frame.left.value >= 0f)
      assertTrue("$context starts inside the pane", frame.top.value >= 0f)
    }
  }
}
