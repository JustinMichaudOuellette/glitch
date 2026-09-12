package ca.justinmo.glitch.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.justinmo.glitch.OutputFormat

/**
 * What the last screen has decided: the format the file will be handed over as, whether the
 * explanation is up, and whether this picture has been saved.
 *
 * Small, because the screen does nothing else - but the format is the whole of its
 * choice, and losing it on the way back from a step looked at again would mean the
 * picture quietly saving as something other than what was chosen.
 */
internal class PreviewState {
  /** The selected output format. Raw JPEG until the user says otherwise. */
  var format by mutableStateOf(OutputFormat.RawJpeg)

  /** Whether the explanation is up. */
  var showInfo by mutableStateOf(false)

  /**
   * Whether this picture has been written into the gallery from this screen.
   *
   * It lives here rather than in the screen's own `remember` because it is what the restart
   * button asks before it throws the session away, and the answer has to survive the screen:
   * a save, a walk back to the scan to change something and a walk forward again must not add
   * up to "you have not saved this" - the picture is in the gallery either way.
   *
   * Only a save sets it. Sharing hands the file to an app the user picks, which decides what
   * it keeps, so it says nothing about whether there is a copy to come back to.
   */
  var saved by mutableStateOf(false)
    private set

  /** The file went into the gallery. */
  fun markSaved() {
    saved = true
  }

  /** A different picture is in the flow: nothing has been saved of *this* one yet. */
  fun clearSaved() {
    saved = false
  }
}
