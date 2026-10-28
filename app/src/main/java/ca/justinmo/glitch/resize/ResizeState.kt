package ca.justinmo.glitch.resize

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

  /** Whether the explanation is up. */
  var showInfo by mutableStateOf(false)

  /** The picture underneath changed: back to the screen's own defaults. */
  fun clear() {
    targetSize = null
    crop = null
  }
}
