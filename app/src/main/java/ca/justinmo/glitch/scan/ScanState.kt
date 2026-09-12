package ca.justinmo.glitch.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.justinmo.glitch.ByteHistory

/**
 * What the scan screen has done to the file, kept here so that walking back to it
 * shows the same flips rather than the bytes it was handed.
 *
 * There is no working file in here, and that is the point of the stack: the file is
 * *derived* on the frame that draws it, as the bytes arriving into the screen plus the
 * changes on [history] (`history.applyTo`). What the view model keeps for this step is
 * therefore the description of the edit - a few bytes per flip - rather than a copy of the
 * file per flip, which is what made a long session on one picture worth hundreds of
 * megabytes. [ByteHistory] is where the description and its one exception live; the screen
 * is where the file is built from it.
 */
internal class ScanState {
  /**
   * One entry per flip and per injection, oldest first: what undo walks back through, and
   * what the working file is built from. The stack is the state here rather than a list on
   * a `var`, so dropping a step of it is dropping a handful of bytes and nothing has to be
   * copied back into the holder to see the result.
   */
  val history = ByteHistory()

  /**
   * Where the next press of Flip starts in the walk along the row.
   *
   * This is a cursor, not a count of the flips in [history]: undoing a flip leaves
   * it where it is, so the next press carries on to the next bit instead of
   * repeating the one just taken back. Only [clear] - and the screen's reset -
   * returns it to the start.
   */
  var walkStep by mutableIntStateOf(0)

  /** Which row of the picture the flip is aimed at, as a fraction of its height. */
  var areaFraction by mutableFloatStateOf(0.5f)

  /** How far one bad bit is allowed to travel before a restart marker, as a fraction. */
  var windowFraction by mutableFloatStateOf(0.15f)

  /**
   * Whether the explanation is up.
   *
   * Here with the rest of what this step decided rather than in the screen's own `remember`,
   * for the same reason the sliders are: leaving the step and coming back finds it as it was
   * left. [clear] deliberately leaves it alone - which way the user was looking at this
   * screen is not a claim about the file - and both ways this step is cleared happen while
   * the screen is not the one on top anyway.
   */
  var showInfo by mutableStateOf(false)

  /**
   * Drop the last change without moving [walkStep].
   *
   * The walk cursor stays where the press that made the change left it, so the
   * next press carries on to the next bit rather than repeating the one just taken
   * back. Reset is the only thing that returns the cursor to the start.
   */
  fun undo() {
    history.changes.removeLastOrNull()
  }

  /**
   * The file underneath changed: nothing here is about it any more.
   *
   * The changes go with it, and so does any base a rebase left behind: a change is a byte
   * offset into the bytes this step was handed, so once those are not the bytes any more
   * the whole stack is describing a file that no longer exists. The screen then goes back
   * to following whatever file arrives, which is the new one.
   */
  fun clear() {
    history.clear()
    walkStep = 0
  }
}
