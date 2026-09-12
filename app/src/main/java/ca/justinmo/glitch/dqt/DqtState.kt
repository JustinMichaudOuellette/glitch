package ca.justinmo.glitch.dqt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.justinmo.glitch.ValueHistory

/**
 * What the quantization screen has decided, kept here so that walking back to it shows
 * the same edits rather than a clean table.
 *
 * Only the edits and the aim survive. The decoded preview, the measured bounds of the
 * panel and the animation's own state are left to the screen: they describe the frame
 * being drawn, not the edit being made, and they are rebuilt the moment the screen
 * comes back.
 *
 * What does survive survives for the *session* rather than for a visit: the flow holds
 * one of these, so the undo stack below is the one thing in here with a size worth
 * thinking about - which is why it is a stack of differences and not a pile of copies.
 */
internal class DqtState {
  /** Quantizer offset -> the value the user set, exactly as the screen holds it. */
  var edits by mutableStateOf<Map<Int, Int>>(emptyMap())

  /**
   * What each edit changed, oldest first: what undo unwinds.
   *
   * The *difference* per step and not a copy of [edits] after it, because this stack
   * now lives as long as the session instead of as long as the screen. A snapshot per
   * step is a copy of the whole edits map, and that map is one entry for every
   * quantizer the user has set - a single family is 64 of them, so one step taken
   * after resetting a pattern copies all 64, and each of the 32 steps the old stack
   * kept held its own copy of the table. A step here is only the values that actually
   * moved: one value for a wheel tick, one for a flip, at most the family a reset
   * clears. That is a few bytes, so the depth costs nothing and undo can be as deep
   * as a session is long - see [ValueHistory], which does the arithmetic and knows
   * that "no edit here" is a state to restore just as much as a value is.
   */
  val history = ValueHistory()

  /** The quantizer the last edit landed on, so a drag leaves one step. */
  var lastEdited by mutableStateOf<Int?>(null)

  /** Flip presses so far: the salt in the flip's seed. */
  var flipStep by mutableIntStateOf(0)

  /** Which pattern's panel is open, if any. */
  var openFamily by mutableStateOf<DqtFamily?>(null)

  /** Whether the explanation is up. */
  var showInfo by mutableStateOf(false)

  /** The file underneath changed: nothing here is about it any more. */
  fun clear() {
    edits = emptyMap()
    history.clear()
    lastEdited = null
    flipStep = 0
    openFamily = null
  }
}
