package ca.justinmo.glitch

import ca.justinmo.glitch.scan.ScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan screen's walk cursor: undo must take the change back without taking
 * the cursor with it, or the next press would repeat the bit just undone.
 */
class ScanStateTest {

  @Test
  fun undoLeavesTheWalkCursorWhereItWas() {
    val state = ScanState()
    state.walkStep = 7
    state.history.record(byteArrayOf(0, 0, 0), byteArrayOf(0, 1, 0))
    assertTrue("there is a change to take back", !state.history.isEmpty)

    state.undo()

    assertTrue("the change is gone", state.history.isEmpty)
    assertEquals("the next press continues past the undone bit", 7, state.walkStep)
  }

  @Test
  fun resetReturnsTheWalkCursorToTheStart() {
    val state = ScanState()
    state.walkStep = 12
    state.history.record(byteArrayOf(0), byteArrayOf(1))

    state.clear()

    assertTrue("the changes are gone", state.history.isEmpty)
    assertEquals("reset starts the row again", 0, state.walkStep)
  }
}
