package ca.justinmo.glitch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import ca.justinmo.glitch.dht.DhtState
import ca.justinmo.glitch.dqt.DqtState
import ca.justinmo.glitch.preview.PreviewState
import ca.justinmo.glitch.resize.ResizeState
import ca.justinmo.glitch.scan.ScanState
import ca.justinmo.glitch.source.SourceFlow

/**
 * The one view model behind the whole flow: where it is, what it is working on, and
 * the state of every screen in it.
 *
 * The screens used to keep themselves in `remember`, which is exactly as long as they
 * are composed - so walking back a step threw the step you came from away, and coming
 * forward again met a screen with its edits, its sliders and its open panel all back
 * at their defaults. That is wrong twice over: back is how you change your mind about
 * the *next* step, not a way to lose the one you are on, and the state of a step is a
 * property of the file being worked on rather than of the frame being drawn.
 *
 * Everything a step needs to be itself again therefore lives here, one holder per
 * screen, and the screens read and write those holders instead of their own copies.
 * The holders are cleared only when their *input* changes - a new file from an earlier
 * step means the later steps have nothing to say about it - so navigating back and
 * forth keeps what each step decided, and changing the resize target and carrying on
 * still starts the table and scan steps clean.
 *
 * One view model rather than one per screen because the flow is one thing: the screens
 * are steps of a single edit, they hand the same bytes along, and they share a zoom.
 */
internal class GlitchViewModel : ViewModel() {

  /** Where the flow is. Held here so it survives a configuration change too. */
  enum class Step { Loading, Source, Resize, Dqt, Dht, Scan, Preview }

  var step by mutableStateOf(Step.Source)

  // The file the flow is working on. [sourceBytes] is what was picked and never
  // changes; [workingBytes] is what each step has made of it so far.
  var sourceBytes by mutableStateOf<ByteArray?>(null)
  var sourceResolution by mutableStateOf<Pair<Int, Int>?>(null)
  var sourceName by mutableStateOf("image")
  var workingBytes by mutableStateOf<ByteArray?>(null)

  // Which picker produced the image, and the request to reopen it when the user walks
  // back to the first screen for a different photo.
  var lastSourceFlow by mutableStateOf<SourceFlow?>(null)
  var resumePicker by mutableStateOf<SourceFlow?>(null)

  /** The pinch-zoom, shared: it follows the picture from one step to the next. */
  val zoom = ZoomState()

  /**
   * The last decoded preview, shared for the same reason the zoom is: it follows the picture
   * from one step to the next, so a screen arriving has something to draw on its first frame
   * instead of a blank and a re-decode of a file the last screen had already decoded.
   * [PreviewImage] says what makes a frame safe to hand on to a different step.
   */
  val previewImage = PreviewImage()

  val resize = ResizeState()
  val dqt = DqtState()
  val dht = DhtState()
  val scan = ScanState()
  val preview = PreviewState()

  /**
   * Where the flow begins on this launch: the picker, or the loading step while a photo
   * another app shared into us is read and converted. A no-op once the flow is under
   * way, so that a configuration change cannot drag it back to the first step.
   */
  fun start(hasSharedImage: Boolean) {
    if (step != Step.Source) return
    step = if (hasSharedImage) Step.Loading else Step.Source
  }

  /**
   * A step has handed its file on. Only a *different* file invalidates the steps after
   * it: pressing next on a step that was not touched hands along the very same bytes -
   * the edits are what a step adds, and a step that added none adds none of them - and
   * dropping what a later step already did to them would be losing work for nothing. So
   * walking forward again over a step that was not touched keeps the step ahead of it
   * exactly as it was left, which is what back and forward are for.
   *
   * When the bytes really did change, the later steps are cleared, and they have to be: a
   * later step's edits are *offsets* into the file it was handed - a flip is a byte offset
   * and the byte that was there, a quantizer is an index into a table the file carries - so
   * once the file underneath them is a different file, the whole stack is a description of
   * something that no longer exists. Keeping it would not just show stale edits: the scan's
   * stack is applied to whatever file arrives, so its offsets would land on bytes it never
   * touched and hand on a file that never had this step's edit in it.
   */
  fun advance(from: Step, bytes: ByteArray) {
    val previous = workingBytes
    val changed = previous?.let { !it.contentEquals(bytes) } ?: true
    workingBytes = bytes
    if (!changed) return
    when (from) {
      Step.Resize -> {
        dqt.clear()
        dht.clear()
        scan.clear()
      }
      Step.Dqt -> {
        dht.clear()
        scan.clear()
      }
      // The scan step handing its own file on: its changes are byte offsets into the file it
      // was *handed*, and from here on this step is entered again with the file it made
      // instead - so the stack pins the file it came from as its starting point and keeps
      // every change. Without that, walking back to the scan would lay the flips over a file
      // that already has them: the picture would look right and undo would be a button that
      // does nothing, which is worse than not offering it.
      Step.Scan -> if (previous != null && !scan.history.isEmpty) scan.history.pinBase(previous)
      // Nothing after the scan step holds a copy of the file, and the preview's format
      // is a choice about the file that leaves rather than about this picture. The Huffman
      // side trip is not on this path at all: it is opened from the scan and hands its file
      // back through [closeDht], which is the one place that can tell a trip which
      // changed something from a trip which did not.
      else -> Unit
    }
  }

  /**
   * The file the Huffman side trip is editing, or null while it is not open.
   *
   * The trip is opened from the scan screen and works on the file that screen is *showing* -
   * the bytes it was handed with its flips already laid over them - which is not
   * [workingBytes]: that still holds the pre-flip file until the scan step hands something
   * on. Keeping the two apart is what makes looking at the tables cost nothing, because
   * [closeDht] can compare what the trip was given with what it brought back.
   */
  var dhtInput by mutableStateOf<ByteArray?>(null)

  /** Open the Huffman side trip on [bytes], the file the scan screen is showing. */
  fun openDht(bytes: ByteArray) {
    dhtInput = bytes
    step = Step.Dht
  }

  /**
   * The step that handed the final preview its file, which is where back goes: the scan
   * screen on the flow's own path, and the Huffman tables when the preview was reached by
   * confirming them - the trip is not a step, so there is no fixed answer, and a preview that
   * always went back to the scan would make confirming the tables a one-way door.
   *
   * It is set on every way into the preview, so it always describes the last one.
   */
  var previewFrom by mutableStateOf(Step.Scan)
    private set

  /**
   * A step has handed its file on to the preview, which is the one place that has to be told
   * where it came from - the whole of this function is that bookkeeping and the advance.
   */
  fun toPreview(from: Step, bytes: ByteArray) {
    advance(from, bytes)
    previewFrom = from
    step = Step.Preview
  }

  /**
   * Back from the final preview, to whatever handed it the file.
   *
   * The Huffman case reopens the trip rather than restoring the one that ended, and on the
   * file as it now stands: that trip handed its edits over and is over, and its edits are in
   * these bytes - so the tables come back showing what they were set to, over the picture they
   * were set on, and nothing has to be kept alive to make that true.
   */
  fun backFromPreview() {
    val file = workingBytes
    if (previewFrom == Step.Dht && file != null) {
      openDht(file)
    } else {
      step = previewFrom
    }
  }

  /**
   * The Huffman side trip is over, and the flow goes on to [then] - the scan screen it was
   * opened from when the trip is backed out of, and the final preview when it is confirmed.
   *
   * [handedOn] is the file the trip is handing on, or null where it is handing nothing on at
   * all - the back arrow on its bar, the triangle on its title, and OS back - which leaves the
   * file as it was: the tables were looked at, and nothing was applied.
   *
   * A trip that changed nothing leaves the scan's stack alone, and that is the point of
   * comparing against [dhtInput] rather than [workingBytes]: the file the trip was handed
   * already has the scan's flips in it, so measuring it against the older pre-flip bytes would
   * call every trip a change and throw the scan's undo stack away for opening a screen.
   *
   * A trip that did change something has moved the file out from under that stack - and can
   * move it further than its own edit, since rewriting what a code means changes the length of
   * the table it lives in, which shifts every offset after it - so the stack goes, exactly as
   * it does when an earlier step hands on a different file. Nothing about the picture is lost
   * by that: the flips are in the bytes the trip was given, and the bytes handed on are that
   * file with the tables retuned.
   *
   * Confirming the trip commits the file whether or not the tables were touched, and that
   * asymmetry with the way back is deliberate: [workingBytes] is what the preview draws, and
   * it still holds the *pre-flip* file - the scan step has not handed anything on yet - so an
   * unedited trip that only set the step would hand the preview a picture with the user's
   * flips missing from it. Backing out hands nothing on and so commits nothing, which is what
   * keeps the scan screen's own trail intact behind it.
   */
  fun closeDht(handedOn: ByteArray? = null, then: Step = Step.Scan) {
    val input = dhtInput
    val previous = workingBytes
    dhtInput = null
    step = then
    // Confirming the trip is one of the two ways into the preview, so the preview has to
    // know that back goes to the tables rather than to the scan behind them.
    if (then == Step.Preview) previewFrom = Step.Dht
    if (handedOn == null) return
    workingBytes = handedOn
    if (input != null && input.contentEquals(handedOn)) {
      // The tables were not touched, so the scan screen's trail still describes this file
      // and is kept - but the file it is relative to is [previous] and is about to stop
      // being the flow's, so the stack pins it, exactly as it does when the scan hands its
      // file on itself. Undo then goes on working on what the user can see.
      if (previous != null && !scan.history.isEmpty) scan.history.pinBase(previous)
      return
    }
    scan.clear()
  }

  /**
   * Start again with another photo, which is what the preview's restart button means: the flow
   * goes back to the first screen, and the picker the user used last is asked for on arrival -
   * so they are looking at the gallery or the camera rather than at a welcome screen they have
   * to press the same button on again.
   *
   * The photo goes and everything the steps made of it goes with it: that is the thing being
   * restarted. The output format stays, as it does when any new photo enters the flow - it is
   * how this user works rather than what this file is.
   */
  fun restart() {
    sourceBytes = null
    sourceResolution = null
    workingBytes = null
    dhtInput = null
    // Clears the steps' holders, where the last picture left them, the preview's own "this one
    // has been saved" - nothing has been saved of the photo about to be picked - and the
    // framing: a picture nobody has looked at yet should be met fitted, not at whatever pinch
    // and pan the last one was left at.
    freshFromResize()
    resumePicker = lastSourceFlow
    step = Step.Source
  }

  /**
   * A different photo has been picked: everything the steps after the picker decided is
   * about the old one and goes.
   *
   * The output format stays, because it is how this user works rather than what this file is.
   * The zoom does not: it is the framing of the picture that just went, so a new photo is met
   * fitted instead of inheriting a pinch from the one before it.
   */
  fun freshFromResize() {
    dqt.clear()
    dht.clear()
    scan.clear()
    resetZoom()
    // The crop is a decision about the picture that is going: its frame, its window and
    // the default they stand in for all depend on the photo. The export width stays,
    // because that is how this user works rather than what this file is.
    resize.resetCropForNewPicture()
    // Another photo's picture is not this one's, and there is no reason to hold the megabytes
    // while the user is still in the picker.
    previewImage.clear()
    // A new photo has not been through the preview at all, let alone through the tables on
    // the way to it: whatever the last picture was confirmed from is not this one's history.
    previewFrom = Step.Scan
    // Whatever was saved was the *last* picture, so the preview has to ask again before its
    // restart button throws this one away.
    preview.clearSaved()
  }

  /**
   * Start the picture fitted again.
   *
   * The pinch is the framing of the file on screen, so a step that changes the file - or
   * hands on a crop, which changes its frame - has to leave the zoom where a new picture
   * wants it. Otherwise the next step meets a cropped file still blown up to wherever the
   * last one was being examined.
   */
  fun resetZoom() {
    zoom.scale = 1f
    zoom.offset = Offset.Zero
  }
}

/**
 * The pinch-zoom of the picture on screen, in one place for the whole flow.
 *
 * It lives here rather than in each screen because it is deliberately continuous: the
 * resize screen hands the framing it left to the table screens, and a step that
 * started at 1x would jump the picture back to its fitted size the moment it was
 * reached. Holding it once also means walking back to a step shows the picture the way
 * it was being looked at, rather than the way it arrived.
 */
internal class ZoomState(
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
) {
  var scale by mutableFloatStateOf(initialScale)
  var offset by mutableStateOf(initialOffset)
}
