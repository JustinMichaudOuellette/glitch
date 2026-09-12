package ca.justinmo.glitch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * The undo stacks of the editing screens, kept as what *changed* rather than as a copy
 * of the file after every step.
 *
 * The screens used to undo by keeping a whole snapshot per edit: the two table screens
 * kept a copy of the edits map, and the scan screen kept a copy of the entire file,
 * several megabytes for a photo and one more for every press of Flip. None of that was
 * dangerous while the stack lived in the screen's own `remember`, because leaving the
 * step threw it away - but the stacks now live in the view model, which lives as long
 * as the session, and an unbounded pile of full-size copies is a way to run out of
 * memory on a phone.
 *
 * So both stacks record the *difference* instead. A flip is one byte that changed, an
 * injection is a few thousand of them - or, where it rewrote too much of the file for
 * even that to be worth keeping, the rewritten file itself as a new starting point (see
 * [ByteHistory.rebase]) - and a quantizer is one value: a handful of bytes per step where
 * there used to be a file. That also means the stacks can afford to be deep: the limits
 * here are generous because a step costs almost nothing.
 *
 * Both are held as Compose state (`mutableStateListOf`), because the action bar reads
 * them every frame to decide whether reset and undo are live - but the two lists do not
 * hold the same kind of thing, and which is which is worth knowing before reading either
 * of them. [ByteHistory.changes] holds the *changes* themselves, because one call to its
 * `record` is one press of Flip or one injection and is already the unit undo works in.
 * [ValueHistory.changes] holds *steps*, each of them the list of [ValueChange]s that one
 * call to its `record` made, because one press there can move a whole quantization
 * pattern. So one stack is a list of changes and the other is a list of steps; each class
 * below says which it is and why, and anything reading them has to know.
 */

/** One byte that an edit changed, and what it was before. */
internal class ByteChange(val offset: Int, val before: Int, val after: Int)

/**
 * The scan step's undo stack: the bytes each edit changed, oldest first.
 *
 * Undo works by dropping the last change and rebuilding the file from the ones that are
 * left, applied to the bytes the step was handed - or to [base], once [rebase] has given
 * the stack a starting point of its own. That is why nothing here is relative to a
 * previous version of the file: every change is a byte offset and the byte that was there,
 * so the whole stack is a description of the edit and not a history of copies, and it
 * stays true as long as the file it starts from does.
 *
 * [limit] is a guard against a runaway session rather than a memory budget - a step is
 * a few dozen bytes, so a thousand of them is nothing.
 */
internal class ByteHistory(private val limit: Int = 1000) {
  val changes: SnapshotStateList<ByteChange> = mutableStateListOf()

  /**
   * The file the changes are laid on top of, or null while they are laid on the bytes the
   * step was handed.
   *
   * Only [rebase] sets it, and it exists for the one edit that cannot be described as a
   * list of bytes: a rewrite of the file large enough that the description would cost more
   * memory than the file it describes. There the file itself is kept instead, as the point
   * the stack starts from again. It is Compose state like [changes], because the screens
   * read it while working out the file the changes describe.
   */
  var base by mutableStateOf<ByteArray?>(null)
    private set

  /**
   * True when there is nothing to take back. A [rebase] empties the stack as well - the
   * file it keeps is a starting point rather than a change - so a wholesale rewrite leaves
   * the step with nothing for undo to drop and nothing for reset to clear until the next
   * edit lands on top of it.
   */
  val isEmpty: Boolean get() = changes.isEmpty()

  fun clear() {
    changes.clear()
    base = null
  }

  /**
   * Record every byte where [after] differs from [before], and drop the oldest changes
   * if the stack has run past [limit] - the oldest are the ones a user is least likely
   * to want back, and keeping them is what a limit is for.
   *
   * The offsets recorded are offsets into the file these two were built from, which is
   * [base] when there is one and the step's incoming bytes otherwise.
   */
  fun record(before: ByteArray, after: ByteArray) {
    for (i in 0 until minOf(before.size, after.size)) {
      if (before[i] != after[i]) {
        changes.add(ByteChange(i, before[i].toInt() and 0xFF, after[i].toInt() and 0xFF))
      }
    }
    while (changes.size > limit) changes.removeAt(0)
  }

  /**
   * Start the stack again from [file]: forget the changes recorded so far, and make [file]
   * the file the ones recorded after this are laid on top of.
   *
   * This is the escape hatch of a change list rather than the common case. Every change
   * costs a small object - a dozen bytes or more - to describe one byte of the file, so a
   * rewrite of half the file would cost several times what simply keeping the file costs.
   * Where that is the trade, the file wins: the edits behind it are given up, and [applyTo]
   * builds on [file] from then on, which is what keeps the rewrite in the file the step
   * hands on rather than dropping back to the bytes it was given.
   */
  fun rebase(file: ByteArray) {
    changes.clear()
    base = file
  }

  /**
   * Hold [file] as the starting point of the changes already recorded, keeping every one of
   * them: the same [base] a [rebase] sets, without the giving up.
   *
   * This is for the moment a step hands its own file on. Its changes are offsets into the
   * file it was *handed*, and once the flow carries the file it made, that step is entered
   * again with the new one - so without this the flips would be laid over a file that already
   * has them. That is not a wrong picture, which is exactly what makes it worth a note: the
   * offsets land on the same bytes and write the values they already hold, so nothing looks
   * broken, and what quietly stops working is undo - the button stays live and takes a change
   * off the stack that the file in front of the user no longer depends on.
   *
   * It costs one file for as long as the trail does, which is the price of the trail meaning
   * anything once the step has been left. Nothing is pinned twice: a first [base] only, so the
   * file an injection pinned stays the one the changes are relative to.
   */
  fun pinBase(file: ByteArray) {
    if (base == null) base = file
  }

  /**
   * The file the changes describe, built on top of [source] - or on top of [base] when a
   * rebase has replaced the starting point, in which case [source] is not read at all.
   * Applying the whole stack rather than a single change is what keeps it honest: a change
   * is a byte offset into the file the stack starts from, so an edit cannot drift as later
   * edits land on top of it.
   *
   * With nothing to apply it is the starting file itself that comes back, [base] included,
   * rather than a copy of it. That is safe because nothing here - and nothing in the
   * screens, where a flip and the restart injector each build a new array - ever writes
   * into a file it was handed.
   */
  fun applyTo(source: ByteArray): ByteArray {
    val start = base ?: source
    if (changes.isEmpty()) return start
    val out = start.copyOf()
    for (change in changes) {
      if (change.offset in out.indices) out[change.offset] = change.after.toByte()
    }
    return out
  }
}

/** One quantizer or symbol value an edit changed, and what it was before. */
internal class ValueChange(val offset: Int, val before: Int?, val after: Int?)

/**
 * The table screens' undo stack: the *steps* each edit made, oldest first, each step
 * being the values that one press moved.
 *
 * A step is one call to [record] - one press of reset or flip, one tick of a wheel, one
 * guarded drag - and it holds one [ValueChange] for every value that call moved. A step
 * is therefore not a change, and the difference is the whole reason this stack is shaped
 * the way it is: undo is a press of a button, and what one press of undo has to take back
 * is what one press of the table did. Resetting a quantization pattern sets up to
 * sixty-four quantizers back to the values the file carries, all of it in one press, so
 * one press of undo has to put sixty-four quantizers back. Recorded as changes on one
 * flat list, as this stack was for a while, each press of undo could only take back one
 * of them and the pattern came back a quantizer at a time - sixty-four presses to undo
 * one, which is not what the button says it does. So the step is the unit here, and the
 * changes are only how a step is spelled out.
 *
 * A null value means "not edited here" - the difference between a quantizer set back to
 * the value the file already carries and one that was never touched at all, which is
 * the distinction the edits map itself makes and the one undo has to restore.
 *
 * [limit] bounds the number of *steps*, not the number of changes, and that is the only
 * reading of a limit that means anything to the user of an undo button: a press is one
 * step however many quantizers went with it, so a thousand here is a thousand presses of
 * undo, whatever the tables did in between.
 *
 * One thing the stack is not is a faithful forward replay of an edit. A guarded drag
 * records its step from the value at the *first* tick of the drag rather than from where
 * the drag ended - the guard in the screen keeps one step per dragged quantizer, so the
 * ticks after the first leave the stack alone - which means the [ValueChange.after] side
 * of such a step is a value the user moved straight past. Undo is unaffected, because it
 * reads only the [ValueChange.before] side and lands back where the drag began; the note
 * is here because a step that stops describing an edit at its first tick is exactly what
 * a reader would otherwise take for a bug in this class.
 */
internal class ValueHistory(private val limit: Int = 1000) {
  /**
   * The steps, oldest first. Each entry is the list of [ValueChange]s that one call to
   * [record] made - not the changes themselves, which is what [ByteHistory.changes]
   * holds; this is the one place the two stacks differ in what their list holds.
   *
   * Compose state for the same reason as [ByteHistory.changes]: the action bar reads
   * whether there is anything here, through [isEmpty], every frame it is drawn.
   */
  val changes: SnapshotStateList<List<ValueChange>> = mutableStateListOf()

  val isEmpty: Boolean get() = changes.isEmpty()

  fun clear() {
    changes.clear()
  }

  /**
   * Record what this one edit changed: every value that moved between [before] and
   * [after], gathered into a single step and pushed as one entry, oldest first. The
   * oldest steps past [limit] are dropped, because the oldest are the ones a user is
   * least likely to want back and keeping them is what a limit is for.
   *
   * A call that moved nothing leaves no step at all rather than an empty one. That is
   * the difference between a press that did something and a press that did not: an empty
   * step would still be a step, so the next press of undo would spend itself taking back
   * nothing. The screens mostly guard against that on their own - a reset with nothing
   * set in it, and a wheel that lands back on the value the file already carries, both
   * leave the two maps equal - but the guarantee belongs here, because it is this stack
   * that decides what a press of undo is worth.
   */
  fun record(before: Map<Int, Int>, after: Map<Int, Int>) {
    val offsets = before.keys + after.keys
    val step = mutableListOf<ValueChange>()
    for (offset in offsets) {
      val was = before[offset]
      val now = after[offset]
      if (was != now) step.add(ValueChange(offset, was, now))
    }
    if (step.isEmpty()) return
    changes.add(step)
    while (changes.size > limit) changes.removeAt(0)
  }

  /**
   * Take the last step back off the stack and reverse the whole of it in [edits]: every
   * value that step moved is put back to the value the step found there, or taken out of
   * the map altogether when it found no edit at all. A step of sixty-four changes comes
   * back in one press, which is what one press of the table cost.
   *
   * The changes are walked last first, so the last thing the step did is the first thing
   * undone. A step holds at most one change per offset - the offsets it walks are the
   * union of the two maps' keys, and there is one entry per key - so for the steps this
   * stack actually records the order cannot matter; it is written in reverse because undo
   * has to be the inverse of the step whatever shape the step has, and because that is
   * the direction the word means.
   *
   * What comes back is [edits] with those values put back and nothing else touched, which
   * is what lets a press that moved one value sit on top of a press that moved sixty-four
   * without either knowing about the other.
   */
  fun undo(edits: Map<Int, Int>): Map<Int, Int> {
    val step = changes.removeLastOrNull() ?: return edits
    var restored = edits
    for (change in step.asReversed()) {
      restored = if (change.before == null) {
        restored - change.offset
      } else {
        restored + (change.offset to change.before)
      }
    }
    return restored
  }
}
