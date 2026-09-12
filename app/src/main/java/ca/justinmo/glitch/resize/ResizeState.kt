package ca.justinmo.glitch.resize

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the resize screen has chosen, kept here so that walking back to it - which is
 * what changing your mind about the size means - shows the size and the crop you left,
 * rather than the defaults.
 *
 * [targetSize] is null until something picks one, and the screen reads
 * `state.targetSize ?: <its own default>`: the default lives with the screen, where
 * the numbers it chooses between are, and this holder only has to remember a choice.
 * [cropForStories] works the same way, and for a stronger reason: what its default
 * should be depends on the photo, which this holder never sees.
 */
internal class ResizeState {
  /** Longest edge the file is exported at, or null while the default stands. */
  var targetSize by mutableStateOf<Int?>(null)

  /**
   * Whether the picture is cropped to the 9:16 frame the app works in, or null while the
   * screen's own default for this photo stands.
   *
   * Null rather than `true`, because whether the crop should *start* on is a question about
   * the picture: it is a trim on a portrait frame and a different photograph on a landscape
   * one. Only the screen has the resolution in hand, so the default belongs there - this
   * holder's job is to remember the user's own answer once there is one, and null is how it
   * says there is not.
   */
  var cropForStories by mutableStateOf<Boolean?>(null)

  /** Whether the explanation is up. */
  var showInfo by mutableStateOf(false)

  /** The picture underneath changed: back to the screen's own defaults. */
  fun clear() {
    targetSize = null
    cropForStories = null
  }
}
