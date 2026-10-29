package ca.justinmo.glitch

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop window's arithmetic, checked here rather than on the screen because it is what
 * the export and the editor have to agree on: the rectangle the preview shows as kept is
 * handed to the native pipeline as proportions, and a sign error in the drag direction or a
 * window that walks off the picture would show up as a crop of the wrong part of the photo
 * only after the file was written.
 */
class CropGeometryTest {

  private val landscape = 4f / 3f
  private val portrait = 3f / 4f
  private val stories = 9f / 16f

  @Test
  fun noCropStartsAsTheWholePicture() {
    val window = cropWindow(landscape, landscape, zoom = 1f, pan = Offset.Zero)

    assertEquals("left edge", 0f, window.left, 1e-5f)
    assertEquals("top edge", 0f, window.top, 1e-5f)
    assertEquals("full width", 1f, window.width, 1e-5f)
    assertEquals("full height", 1f, window.height, 1e-5f)
    assertTrue("nothing would be cut off", window.isWholePicture)
  }

  @Test
  fun storiesAtRestTrimsTheSidesOfAPortrait() {
    val window = cropWindow(portrait, stories, zoom = 1f, pan = Offset.Zero)

    // A 3:4 picture is wider than 9:16, so the window keeps the full height and takes the
    // middle three quarters of the width: (1 - 0.75) / 2 on each side.
    assertEquals("centred", 0.125f, window.left, 1e-5f)
    assertEquals("full height", 1f, window.height, 1e-5f)
    assertEquals("9:16 of the height", 0.75f, window.width, 1e-5f)
    assertFalse("it would cut the sides off", window.isWholePicture)
  }

  @Test
  fun aSquareAtRestTrimsTheTopAndBottomOfATallPicture() {
    val window = cropWindow(portrait, 1f, zoom = 1f, pan = Offset.Zero)

    assertEquals("full width", 1f, window.width, 1e-5f)
    assertEquals("as tall as it is wide", 0.75f, window.height, 1e-5f)
    assertEquals("centred vertically", 0.125f, window.top, 1e-5f)
  }

  @Test
  fun zoomingInShrinksTheWindowAboutItsCentre() {
    val window = cropWindow(landscape, 1f, zoom = 2f, pan = Offset.Zero)

    // A square window at zoom 1 is 0.75 x 1; twice the zoom is half that, still centred.
    assertEquals("half as wide", 0.375f, window.width, 1e-5f)
    assertEquals("half as tall", 0.5f, window.height, 1e-5f)
    assertEquals("still centred", 0.3125f, window.left, 1e-5f)
    assertEquals("still centred", 0.25f, window.top, 1e-5f)
  }

  @Test
  fun aDragMovesTheWindowAndStopsAtTheEdge() {
    // Positive pan drags the picture right and down, so the window it exposes is further
    // left and up.
    val toLeftEdge = cropWindow(landscape, 1f, zoom = 2f, pan = Offset(1f, 1f))
    assertEquals("no blank on the left", 0f, toLeftEdge.left, 1e-5f)
    assertEquals("no blank on top", 0f, toLeftEdge.top, 1e-5f)

    val toRightEdge = cropWindow(landscape, 1f, zoom = 2f, pan = Offset(-1f, -1f))
    assertEquals("no blank on the right", 1f, toRightEdge.right, 1e-5f)
    assertEquals("no blank on the bottom", 1f, toRightEdge.bottom, 1e-5f)
  }

  @Test
  fun theDragIsPulledBackWhenTheZoomDrops() {
    val zoomedIn = clampedCropPan(Offset(0.9f, 0.9f), landscape, 1f, zoom = 5f)
    // A square window of a 4:3 picture at 5x is 0.15 wide and 0.2 tall, so the drag may
    // only be (1 - 0.15) / 2 and (1 - 0.2) / 2.
    assertEquals(0.425f, zoomedIn.x, 1e-5f)
    assertEquals(0.4f, zoomedIn.y, 1e-5f)

    // At zoom 1 the window is 0.75 x 1, so it cannot move vertically at all.
    val zoomedOut = clampedCropPan(Offset(0.9f, 0.9f), landscape, 1f, zoom = 1f)
    assertEquals(0.125f, zoomedOut.x, 1e-5f)
    assertEquals(0f, zoomedOut.y, 1e-5f)
  }

  @Test
  fun theWindowAlwaysKeepsTheChosenAspectAndStaysInsideThePicture() {
    val sourceAspects = listOf(0.5f, 0.75f, 1f, 4f / 3f, 2f)
    val cropAspects = listOf(stories, 1f, 4f / 3f, portrait)
    val zooms = listOf(1f, 1.5f, 3f, MAX_CROP_ZOOM)
    val pans = listOf(
      Offset.Zero,
      Offset(0.1f, -0.2f),
      Offset(-1f, 1f),
      Offset(0.7f, 0.3f),
    )

    for (sourceAspect in sourceAspects) {
      for (cropAspect in cropAspects) {
        for (zoom in zooms) {
          for (pan in pans) {
            val window = cropWindow(sourceAspect, cropAspect, zoom, pan)
            val context = "source $sourceAspect, crop $cropAspect, zoom $zoom, pan $pan"
            assertTrue("$context: left", window.left >= -1e-5f)
            assertTrue("$context: top", window.top >= -1e-5f)
            assertTrue("$context: right", window.right <= 1f + 1e-5f)
            assertTrue("$context: bottom", window.bottom <= 1f + 1e-5f)
            // The window's aspect, measured in picture pixels rather than in proportions.
            val actual = (window.width * 1000f) / (window.height * (1000f / sourceAspect))
            assertEquals("$context: aspect", cropAspect, actual, 1e-3f)
          }
        }
      }
    }
  }

  @Test
  fun theCropSizeIsTheWindowsOwnPixels() {
    // Three quarters of a 4032 px square, full height: a 3024 x 4032 stories strip.
    val window = NormalizedCrop(left = 0.125f, top = 0f, width = 0.75f, height = 1f)
    assertEquals(3024 to 4032, cropPixelSize(4032 to 4032, window))

    // Rounding down to nothing still has to describe at least one pixel.
    val square = NormalizedCrop(left = 0.125f, top = 0f, width = 0.75f, height = 0.75f)
    assertEquals(1 to 1, cropPixelSize(1 to 1, square))
  }
}
