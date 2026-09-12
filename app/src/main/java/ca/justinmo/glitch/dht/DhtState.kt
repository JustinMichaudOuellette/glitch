package ca.justinmo.glitch.dht

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.justinmo.glitch.ValueHistory

/**
 * What the Huffman screen has decided, kept here so that walking back to it shows the
 * same edits rather than a clean set of tables.
 *
 * The census, the decoded preview and the "this edit no longer decodes" flag are left
 * to the screen: they are read off the file and the edits, so they are built again the
 * moment it comes back, and a stale copy of them would be worse than none.
 */
internal class DhtState {
  /**
   * Symbol offset -> the value the user set, exactly as the screen holds it. Only
   * touched bytes are in here, so everything else in the file stays as it was.
   */
  var edits by mutableStateOf<Map<Int, Int>>(emptyMap())

  /**
   * The undo trail: the *difference* each edit made, gathered into one step per edit -
   * the symbols that step moved, each with the value it had before and the value that
   * replaced it - oldest first, and one step per press of undo. What undo unwinds.
   *
   * This used to be one whole copy of [edits] per step, and that is what it must not be
   * now that the holder lives in the view model: the stack is as long as the *session*
   * rather than as long as the frame that made it, so a copy per step is the entire map
   * again and again, kept for as long as the user stays in the flow. Recording the
   * difference costs a few bytes a step instead, whatever the size of the map, which is
   * also what lets the stack be deep enough to be worth having.
   *
   * Recording the difference rather than the state either side of it is also what makes
   * undo able to restore "this symbol was not edited at all" - the distinction the map
   * itself makes between a symbol set back to the value the file carries and one that
   * was never touched. The entries are [ca.justinmo.glitch.ValueChange]s, so the whole
   * mechanism is documented on [ValueHistory].
   *
   * One step per edit rather than one per slider tick, because a drag reports
   * continuously and undoing one tick at a time would be no undo at all: [lastEdited]
   * is what makes a drag leave one step.
   */
  val history = ValueHistory()

  /** The symbol the last edit landed on, so a drag leaves one entry. */
  var lastEdited by mutableStateOf<Int?>(null)

  /**
   * Flip presses so far: the salt in the flip's seed, kept alongside the clock so that
   * two presses inside the same millisecond still draw apart.
   */
  var flipStep by mutableIntStateOf(0)

  /** Which table's sheet is open, if any. */
  var openFamily by mutableStateOf<DhtFamily?>(null)

  /**
   * Whether the sheet lists only the codes this photo emits. Kept because it is a
   * choice about how to *look* at the table rather than a property of the file, and
   * one the user made deliberately.
   */
  var onlyUsed by mutableStateOf(true)

  /** Whether the explanation is up. */
  var showInfo by mutableStateOf(false)

  /**
   * The file underneath changed: the edits and the undo trail behind them are about it
   * no longer, and neither is an open sheet. [onlyUsed] and [showInfo] are deliberately
   * left alone - they are choices about how to look at this screen rather than claims
   * about the file, and the user made them on purpose.
   */
  fun clear() {
    edits = emptyMap()
    history.clear()
    lastEdited = null
    flipStep = 0
    openFamily = null
  }
}
