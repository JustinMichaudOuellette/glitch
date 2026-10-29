package ca.justinmo.glitch.resize

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import ca.justinmo.glitch.STORIES_ASPECT

/**
 * The frame the picture is trimmed to before it is resized.
 *
 * [aspect] is width / height, or null for "leave the frame alone": the export, the live
 * preview and the size arithmetic all take that one number, so the three choices on the
 * screen stay one value everywhere below it.
 */
internal enum class CropMode(val aspect: Float?) {
  /** The whole picture, at its own shape. */
  None(null),

  /** A centred square. */
  Square(1f),

  /** The 9:16 frame the app works in, and what stories take. */
  Stories(STORIES_ASPECT),
}

/**
 * What the resize screen has chosen, kept here so that walking back to it - which is
 * what changing your mind about the size means - shows the size and the crop you left,
 * rather than the defaults.
 *
 * [targetSize] is null until something picks one, and the screen reads
 * `state.targetSize ?: <its own default>`: the default lives with the screen, where
 * the numbers it chooses between are, and this holder only has to remember a choice.
 * [crop] works the same way, and for a stronger reason: what its default should be
 * depends on the photo, which this holder never sees.
 */
internal class ResizeState {
  /** Longest edge the file is exported at, or null while the default stands. */
  var targetSize by mutableStateOf<Int?>(null)

  /**
   * The crop the user picked, or null while the screen's own default for this photo
   * stands.
   *
   * Null rather than a particular mode, because which frame a picture should *start*
   * in is a question about the picture: a 9:16 trim is a trim on a portrait frame and
   * a different photograph on a landscape one. Only the screen has the resolution in
   * hand, so the default belongs there - this holder's job is to remember the user's
   * own answer once there is one, and null is how it says there is not.
   */
  var crop by mutableStateOf<CropMode?>(null)

  /**
   * How far the crop editor is pulled in, relative to the biggest window of the chosen
   * shape that fits: 1 is that whole window, [ca.justinmo.glitch.MAX_CROP_ZOOM] the
   * tightest crop the editor offers.
   *
   * A number rather than a rectangle because it is what a pinch changes, and because it
   * means the same thing on every pane: the window is worked out from it and [cropPan]
   * where it is drawn and where it is exported, so neither has to remember the other.
   */
  var cropZoom by mutableFloatStateOf(1f)

  /**
   * How far the picture has been dragged under the window, as a proportion of its own
   * drawn width and height. Positive moves the picture right and down.
   */
  var cropPan by mutableStateOf(Offset.Zero)

  /** Whether the explanation is up. */
  var showInfo by mutableStateOf(false)

  /** Centre the window again, at the biggest size the frame allows. */
  fun resetCropView() {
    cropZoom = 1f
    cropPan = Offset.Zero
  }

  /**
   * A different picture has been picked, so what was chosen about the last one does not
   * describe it: the frame goes back to the photo-dependent default and the window to the
   * middle. [targetSize] stays - it is how this user exports rather than what this file is.
   */
  fun resetCropForNewPicture() {
    crop = null
    resetCropView()
  }

  /** The picture underneath changed: back to the screen's own defaults. */
  fun clear() {
    targetSize = null
    crop = null
    resetCropView()
  }
}
