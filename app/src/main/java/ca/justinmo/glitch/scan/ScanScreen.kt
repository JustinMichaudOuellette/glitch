package ca.justinmo.glitch.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.PreviewImage
import ca.justinmo.glitch.R
import ca.justinmo.glitch.ZoomState
import ca.justinmo.glitch.decodeSampled
import ca.justinmo.glitch.imageResolution
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.JpegRestartInjector
import ca.justinmo.glitch.jpeg.JpegStructure
import ca.justinmo.glitch.ui.MediaPreview
import ca.justinmo.glitch.ui.PreviewWithControls
import ca.justinmo.glitch.ui.STACKED_CONTROLS_HEIGHT
import ca.justinmo.glitch.ui.ScanActionRow
import ca.justinmo.glitch.ui.TitleBar
import ca.justinmo.glitch.ui.previewFrame
import ca.justinmo.glitch.ui.theme.GlitchTheme
import androidx.compose.ui.tooling.preview.Preview
import ca.justinmo.glitch.ui.PreviewUtil
import ca.justinmo.glitch.ui.createShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** Preview decode size, matching the other screens'. */
private const val PREVIEW_CAP_PX = 2000

/**
 * How many bits a press of Flip may try before giving up for that press.
 *
 * Each candidate is decoded and compared with the picture on screen, because a
 * flip that leaves the pixels identical or stops the file decoding is a press
 * spent for nothing. The walk is deterministic and the cursor moves past the
 * candidates that were tried, so the next press carries on from there.
 */
private const val MAX_FLIP_ATTEMPTS = 24

/**
 * The scan, as a screen of its own - the third of the file's three regions to get
 * one, after the two tables.
 *
 * It works the way the old all-in-one editor's scan mode did, deliberately: the same
 * area slider aimed at a row of the picture, the same undo / flip / reset rhythm, and
 * the same window slider and button that add restart markers to bound how far one bad
 * bit travels. Next then decides whether what was done here goes on to the
 * final preview. What it does not copy is that editor's framing: this screen has a
 * pane of its own, so the preview draws the whole 9:16 frame rather than cropping it,
 * and the sliders ride over the bottom edge of the photo instead of taking a slab out
 * of the layout above it.
 *
 * The title is also this screen's one piece of furniture: tapping it opens the Huffman
 * tables. They are the edit the flow does not walk through - a screen of their own, opened
 * from here, backed out of to here, and confirmed onward to the final preview - and this is
 * the step to open them from, because what each code means is worth judging with the damage
 * already in the picture in front of you and the codes that carry it counted for you. The
 * info button beside the title opens [ScanInfoScreen], which explains the rest: what the
 * scan data is, what the row means, and what restart markers do for a glitch.
 *
 * The one difference is the part that makes a screen of it: the editor flipped a
 * *random* bit in the band and rejected the ones that change nothing, which is how
 * you explore; here a press of flip walks the row in order but still rejects the
 * bits that leave the picture identical or stop the file decoding, so a press takes
 * the next one that actually changes something. The same row and the same presses
 * still write the same file, each press can be undone on its own, and the walk
 * reports the bit it landed on rather than the ones it passed.
 */
@Composable
internal fun ScanScreen(
  bytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  /**
   * The picture to show before the file's own decode arrives - nothing on a device,
   * where the decode is immediate, but the sample asset in a preview, where the
   * decoder is an .so that a preview on the host cannot load.
   */
  examplePreview: androidx.compose.ui.graphics.ImageBitmap? = null,
  /**
   * The pinch-zoom, shared with the rest of the flow. Defaulted to a zoom of its own,
   * built from [initialScale] and [initialOffset] the way this screen used to build one
   * - so it still stands up where there is no flow behind it, in a preview above all.
   * The flow passes the shared one instead, and that is what keeps the framing from
   * jumping back to the fitted size every time a step is reached again.
   */
  zoom: ZoomState = remember(initialScale, initialOffset) { ZoomState(initialScale, initialOffset) },
  /**
   * The flow's shared decoded preview: the first frame here draws the picture the step before
   * was showing rather than a blank over a re-decode of a file that was decoded a moment ago.
   */
  previewImage: PreviewImage = remember { PreviewImage() },
  /**
   * What this step has made of the file, kept in the flow's view model rather than in
   * this screen's own `remember`: see the note on the working bytes below.
   */
  state: ScanState = remember { ScanState() },
  /**
   * Opens the Huffman screen, and hands it the file this screen is *showing* rather than the
   * one it was handed: what the tables are edited on has to be the picture the user is
   * looking at, flips and all, or the side trip would quietly work on a different file and
   * come back with those flips gone. The flow moves the step and decides what the trip
   * leaves behind - see `GlitchViewModel.closeDht` - so this screen only asks for it.
   */
  onOpenHuffman: (bytes: ByteArray) -> Unit,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // The working bytes - what a flip, an undo or an injection has made of the file that
  // arrived - are *derived* here rather than stored: `applyTo` lays the changes on the
  // stack over the bytes this screen was handed, so the view model holds the edits and not
  // the file. `remember` lasts exactly as long as this screen is composed, which is the
  // wrong lifetime for a step of an edit - walking back to this step has to show the flips
  // it made rather than the bytes it was handed, and stepping forward again must not meet a
  // screen that has quietly unflipped everything - so the stack outlives the frame while
  // the file it describes does not.
  //
  // The `remember` is load-bearing rather than tidy: `applyTo` copies the whole file, so
  // without it a photo would be copied on every recomposition. It is keyed on the bytes
  // arriving and on what the stack holds, and there is nothing else left for the derived
  // file to fall out of step with - an empty stack hands the arriving bytes straight back,
  // which is what lets a step that has edited nothing follow its input, and the base is
  // keyed on as well as the count because a rebase can replace the starting file without
  // changing how many changes there are. The array's identity is stable between rebuilds
  // too, so the parse and the decode below re-run when the file really changed and not once
  // a frame.
  val edited = remember(bytes, state.history.changes.size, state.history.base) {
    state.history.applyTo(bytes)
  }

  var injecting by remember { mutableStateOf(false) }
  var flipping by remember { mutableStateOf(false) }
  var readout by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }

  val structure = remember(edited) { JpegParser.parse(edited) }
  val geometry = structure.geometry.firstOrNull()

  // The area control is discrete on the file's block rows, so a fraction kept from
  // an earlier visit (or the 0.5 default) is put on the nearest section rather than
  // leaving the thumb between ticks.
  LaunchedEffect(geometry) {
    val frame = geometry ?: return@LaunchedEffect
    val row = frame.unitRowForFraction(state.areaFraction)
    val snapped = if (frame.unitRows > 1) row.toFloat() / (frame.unitRows - 1) else 0f
    if (state.areaFraction != snapped) state.areaFraction = snapped
  }

  // What the photo measures, from the header, with the EXIF rotation the decoder will apply -
  // so the pane can lay itself out for the picture that is coming instead of for one the size
  // of the whole pane, and the controls below land in the right place from the first frame.
  val shape = remember(edited) { imageResolution(edited) }

  var preview by remember {
    mutableStateOf(previewImage.showable(edited, shape) ?: examplePreview?.asAndroidBitmap())
  }
  // Flip stays inert for a moment after the screen appears. Tapping "next" on the
  // previous screen and arriving here can otherwise deliver the same touch to
  // whichever control now sits under the finger, and Flip is a button that changes
  // the file - so the one control on this screen that must never fire by accident
  // waits until the tap that brought the user here is over.
  var armed by remember(bytes) { mutableStateOf(false) }
  LaunchedEffect(bytes) {
    armed = false
    delay(350.milliseconds)
    armed = true
  }
  LaunchedEffect(edited, shape) {
    if (previewImage.covers(edited, PREVIEW_CAP_PX)) {
      previewImage.showable(edited, shape)?.let { preview = it }
      return@LaunchedEffect
    }
    // Only a successful decode replaces the picture: on a device it always is one,
    // and where it is not - a preview, or a file too damaged to read - keeping what
    // is already on screen beats blanking it. What is already on screen is normally the
    // last step's frame of the same picture, one flip out of date at most.
    val decoded = withContext(Dispatchers.Default) { decodeSampled(edited, PREVIEW_CAP_PX) }
    if (decoded != null) {
      previewImage.put(edited, PREVIEW_CAP_PX, decoded)
      preview = decoded
    }
  }

  fun flip() {
    if (flipping || injecting) return
    if (ScanSweep.flipAt(structure, state.areaFraction, state.walkStep) == null) {
      error = context.getString(R.string.scan_unavailable)
      return
    }
    // The row is walked in the same deterministic order as before, but a candidate
    // only counts if it decodes and changes the picture: a bit that leaves the
    // pixels identical or kills the stream is skipped and the walk moves on. The
    // cursor is advanced past everything that was tried, so the same presses in the
    // same row still write the same file.
    val base = edited
    val basePreview = preview
    val startStep = state.walkStep
    val area = state.areaFraction
    flipping = true
    error = null
    scope.launch {
      var decoded: Bitmap? = null
      val found = withContext(Dispatchers.Default) {
        ScanSweep.findEffectiveFlip(
          structure = structure,
          areaFraction = area,
          startStep = startStep,
          maxAttempts = MAX_FLIP_ATTEMPTS,
          base = base,
        ) { _, candidate ->
          val bitmap = decodeSampled(candidate, PREVIEW_CAP_PX) ?: return@findEffectiveFlip false
          if (basePreview != null && basePreview.sameAs(bitmap)) {
            bitmap.recycle()
            false
          } else {
            decoded = bitmap
            true
          }
        }
      }
      if (found == null) {
        // Nothing in this stretch of the row survives and changes. Keep the cursor
        // moving so the next press tries further along rather than the same bits.
        state.walkStep = startStep + MAX_FLIP_ATTEMPTS
        readout = null
        error = context.getString(R.string.scan_no_effect)
        flipping = false
        return@launch
      }
      // The flip goes on the stack as the one byte it changed, and the flips that came before
      // stay where they are: the working file is built from the whole stack on the next frame.
      // Recording the difference is what makes a press outlive the screen - walking back to
      // this step rebuilds the file and lands on the row this press walked to rather than the
      // one the file arrived with - at the cost of a byte per press instead of a copy.
      state.history.record(before = base, after = found.bytes)
      state.walkStep = found.nextStep
      // Put the frame we already decoded on screen; the effect below decodes the
      // derived file again, but this keeps the picture from lagging behind the press.
      decoded?.let { preview = it }
      error = null
      val row = geometry?.let { it.unitRowForFraction(area) + 1 }
      // A row is only worked out when there is a geometry to work it out from, so a null row
      // here is also the case where the file's structure could not be read: the readout drops
      // the row rather than reporting one it made up.
      readout = if (row != null) {
        context.getString(
          R.string.scan_flip_readout,
          "0x${found.flip.offset.toString(16)}",
          found.flip.bit,
          row,
        )
      } else {
        context.getString(
          R.string.scan_flip_readout_plain,
          "0x${found.flip.offset.toString(16)}",
          found.flip.bit,
        )
      }
      flipping = false
    }
  }

  fun undo() {
    // The walk is in flight; its commit is about to land on this stack.
    if (flipping) return
    if (state.history.isEmpty) return
    // Undo drops the last change and stops there: the working file is derived from what is
    // left of the stack, so it follows on the next frame without anything being written
    // back - there is no copy in the holder to put back. The walk cursor is deliberately
    // left where it is: the bit just taken back should not be the bit the next press lands
    // on, so Flip continues past it instead of repeating it.
    state.undo()
    error = null
  }

  fun reset() {
    // The walk is in flight; its commit is about to land on this stack.
    if (flipping) return
    // Back to *nothing edited*, which is what an empty stack is: the working file is built
    // from the stack, so clearing it puts the screen back on the bytes that arrived - the
    // file this step follows again until it is handed another or edited once more. Any base
    // a rebase left goes with the changes, and has to: it is the starting point of edits
    // this step no longer holds. [ScanState.clear] also puts the walk cursor back to the
    // start, which is what makes the next press begin the row again.
    state.clear()
    readout = null
    error = null
  }

  fun injectRestarts() {
    if (injecting || flipping) return
    injecting = true
    error = null
    val interval = structure.frame?.let {
      JpegRestartInjector.intervalForWindowFraction(it, state.windowFraction)
    } ?: 0
    scope.launch {
      val result = withContext(Dispatchers.Default) {
        JpegRestartInjector.inject(edited, structure, interval)
      }
      injecting = false
      if (result == null) {
        error = context.getString(
          R.string.edit_window_failed,
          JpegRestartInjector.unsupportedReason(structure) ?: "",
        )
        return@launch
      }
      // Restart markers are an edit to the file like a flip is, so they land on the stack
      // too - but not in `walkStep`, which is where the next press starts along the row.
      //
      // They are only recorded as a list of changed bytes while that is the cheaper way to
      // keep them, and it usually is not. Injection transcodes the scan, so it rewrites a
      // large part of the file, and one change costs a small object - a dozen bytes or more
      // - to describe one byte of it: on a rewrite of more than about a quarter of the file,
      // describing the difference costs several times what keeping the injected file costs,
      // which is the very thing this stack exists to avoid. Past that line the injection is
      // not described at all - the stack is rebased on the injected file and starts again
      // from there, because a wholesale rewrite is cheaper to forget than to describe. The
      // edits behind it are given up with it, which is why undo and reset go quiet until the
      // next change; the injected file is not given up, because it becomes the file the
      // changes are laid on top of, so the screen goes on showing it rather than falling
      // back to the bytes this step was handed. Under the line it is an ordinary change and
      // comes back off the stack like any other.
      if (changedByteCount(edited, result.bytes) > edited.size / 4) {
        state.history.rebase(result.bytes)
      } else {
        state.history.record(before = edited, after = result.bytes)
      }
      readout = context.getString(
        R.string.edit_window_done,
        result.restartCount,
        (result.windowFraction * 100).roundToInt(),
      )
    }
  }

  BackHandler { onBack() }
  // The explanation is the only thing that can be "on top" here, so back closes it first and
  // the screen itself second. It is registered *after* the handler above, which is what makes
  // it win while it is enabled: the framework walks the handlers most-recently-added first.
  BackHandler(enabled = state.showInfo) { state.showInfo = false }

  // Flip is live when the arrival guard is over, there is a picture to compare
  // candidates against, and no other edit (a flip walk or a restart injection) is
  // already in flight - both change the same stack.
  val flipReady = armed && preview != null && !flipping && !injecting

  PreviewWithControls(
    preview = { modifier ->
      MediaPreview(
        bitmap = preview?.asImageBitmap(),
        contentDescription = stringResource(R.string.scan_preview_desc),
        modifier = modifier,
        zoomScale = zoom.scale,
        zoomOffset = zoom.offset,
        // The shared zoom, written in place: the gesture state the skeleton hands back
        // names its own first argument after the change it reports, so it is named for
        // that here - `zoom` is the holder this writes into, not the delta.
        //
        // While the explanation is up there is no gesture at all, as on the other screens
        // that draw one: it covers the picture, and a pinch that reached through it would
        // zoom a photo the user cannot see.
        gesture = if (state.showInfo) {
          Modifier
        } else {
          Modifier.transformable(
            rememberTransformableState { zoomChange, pan, _ ->
              zoom.scale = (zoom.scale * zoomChange).coerceIn(0.5f, 6f)
              zoom.offset += pan
            },
          )
        },
        // Medium, as on the final preview: the decode is capped
        // at 2000 px against a 1080 px window, so the GPU minifies it by about half,
        // and sampling a minified bitmap without mipmaps turns fine texture into a
        // moire. The four other previews are either drawn at the size they were
        // decoded at or are the resize screen's own case, which says so itself.
        filterQuality = FilterQuality.Medium,
        // What the photo will measure, for the frames before the decode lands: the panel
        // under it is placed off the frame, so a frame computed from the header puts it
        // where the photo is going to be rather than where a pane-filling stand-in would be.
        expectedSize = shape,
        // A preview that has not decoded yet keeps the pane's size rather than
        // collapsing the layout around it.
        placeholder = { Spacer(Modifier.fillMaxSize()) },
      ) { frame ->
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(
            title = stringResource(R.string.scan_title),
            onBack = onBack,
            onInfo = { state.showInfo = true },
            // The title is the way into the Huffman tables - the same title the other
            // screens have, with a triangle after it to say it does something. What it
            // opens is the one edit that is not a step of the flow: it works on the file
            // this screen has built, so it is handed `edited` rather than the bytes the
            // step arrived with.
            onTitleClick = { onOpenHuffman(edited) },
            titleClickLabel = stringResource(R.string.scan_huffman),
          )
        }

        // On a tall window the sliders sit over the bottom edge of the photo and the
        // buttons take the room under it - the arrangement the two table screens and
        // the final preview use. It matters more here than anywhere: this screen's panel
        // is three times the height of a button row, and while it had a slot of its own
        // in the layout it took that height from the preview, which then could not
        // draw the photo at its full width without cropping it.
        // A photo with room to spare under it keeps that room for the controls, and the
        // panel and the action row are stacked in it rather than laid across the picture -
        // the arrangement a landscape photo lands in, where the picture's own bottom edge is
        // halfway up the screen and a panel riding it would be drawn over the picture with
        // the band below left empty.
        if (frame.stacksControls) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .height(STACKED_CONTROLS_HEIGHT)
              .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.SpaceBetween,
          ) {
            ScanControls(
              structure = structure,
              areaFraction = state.areaFraction,
              onAreaChange = { state.areaFraction = it },
              windowFraction = state.windowFraction,
              onWindowChange = { state.windowFraction = it },
              injecting = injecting,
              onInject = { injectRestarts() },
              readout = readout,
              error = error,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
            ScanActions(
              undoable = !state.history.isEmpty,
              flipEnabled = flipReady,
              onReset = { reset() },
              onUndo = { undo() },
              onFlip = { flip() },
              onNext = { onContinue(edited, zoom.scale, zoom.offset) },
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        } else if (frame.hasRoomBelow) {
          Box(
            modifier = Modifier.fillMaxWidth().height(frame.photoHeight),
            contentAlignment = Alignment.BottomCenter,
          ) {
            // The two sliders write the holder, so the row a flip is aimed at and the
            // restart window are still where they were left when the step is come back
            // to. The panel is one composable in one place; it is only the call sites
            // that are two, because the window decides which of them is drawn.
            ScanControls(
              structure = structure,
              areaFraction = state.areaFraction,
              onAreaChange = { state.areaFraction = it },
              windowFraction = state.windowFraction,
              onWindowChange = { state.windowFraction = it },
              injecting = injecting,
              onInject = { injectRestarts() },
              readout = readout,
              error = error,
              modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(frame.roomBelow)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
          ) {
            // The flags come straight off the holder in this scope: whether the stack holds
            // anything to take back, for reset and undo, and [flipReady] for flip alone -
            // no edit of the stack is in flight. The arrival guard has no business with the
            // other two - see [ScanActions].
            ScanActions(
              undoable = !state.history.isEmpty,
              flipEnabled = flipReady,
              // Next hands on what the flips made of the file, with the shared zoom, so the
              // next screen opens on the framing this one was being looked at with. Leaving
              // the bytes alone is reset followed by next: the flips are the whole of what
              // this screen does to them.
              onReset = { reset() },
              onUndo = { undo() },
              onFlip = { flip() },
              onNext = { onContinue(edited, zoom.scale, zoom.offset) },
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        }
      }
    },
    // On a short window, or one the photo already covers, the panel and the buttons go
    // over the media instead - the skeleton's other arrangement, and the same test the
    // pane makes for itself.
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pane = previewFrame(preview?.asImageBitmap(), maxWidth, maxHeight, shape)
        if (!pane.hasRoomBelow) {
          ScanControls(
            structure = structure,
            areaFraction = state.areaFraction,
            onAreaChange = { state.areaFraction = it },
            windowFraction = state.windowFraction,
            onWindowChange = { state.windowFraction = it },
            injecting = injecting,
            onInject = { injectRestarts() },
            readout = readout,
            error = error,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp),
          )
        }
      }
    },
    controls = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pane = previewFrame(preview?.asImageBitmap(), maxWidth, maxHeight, shape)
        if (!pane.hasRoomBelow) {
          // The same three flags as the pane's copy above, read the same way: from the
          // holder, in this scope, on this frame. The two sites exist because the window
          // decides which of them is drawn, not because the two rows differ.
          ScanActions(
            undoable = !state.history.isEmpty,
            flipEnabled = flipReady,
            onReset = { reset() },
            onUndo = { undo() },
            onFlip = { flip() },
            onNext = { onContinue(edited, zoom.scale, zoom.offset) },
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp, bottom = 24.dp),
          )
        }
      }
    },
  )

  // Drawn after the screen itself, so it covers it. The app composes a screen inside the
  // flow's own Box, and this fills that Box rather than the preview pane - which is what
  // keeps the sliders and the action row from staying live underneath the explanation.
  if (state.showInfo) {
    ScanInfoScreen(onClose = { state.showInfo = false })
  }
}

/**
 * How many bytes [after] differs from [before] at the same offset: the size of the list
 * that recording the two would build, which is the number the restart injection is weighed
 * by.
 *
 * It compares offsets rather than content, and deliberately so. Injection transcodes the
 * scan and writes restart markers into it, so the injected file comes out a little longer
 * and every byte after an inserted marker sits at a new offset - and those are precisely
 * the bytes a change list would have to name one at a time, so counting them as changed is
 * counting the list that recording would really produce. Offsets past the shorter of the
 * two are not counted at all, there being nothing there to compare them against.
 */
private fun changedByteCount(before: ByteArray, after: ByteArray): Int {
  var changed = 0
  for (i in 0 until minOf(before.size, after.size)) {
    if (before[i] != after[i]) changed++
  }
  return changed
}

/**
 * The two sliders and their readouts: the row of the picture a flip is aimed at, and
 * how far one bad bit is allowed to travel before a restart marker stops it.
 *
 * This is the scan panel the old editor had, and it is a composable of its own rather
 * than a slot of the skeleton because it is drawn in two different places depending on
 * the window - over the bottom edge of the photo on a tall one, over the media itself
 * when the window is short and the controls have no floor of their own. [structure]
 * comes in whole rather than as the half-dozen strings derived from it, so that the
 * panel reads the file the same way the screen does.
 */
@Composable
private fun ScanControls(
  structure: JpegStructure,
  areaFraction: Float,
  onAreaChange: (Float) -> Unit,
  windowFraction: Float,
  onWindowChange: (Float) -> Unit,
  injecting: Boolean,
  onInject: () -> Unit,
  readout: String?,
  error: String?,
  modifier: Modifier = Modifier,
) {
  val geometry = structure.geometry.firstOrNull()
  val restartsActive = structure.restartInterval > 0
  val injectBlockedReason = JpegRestartInjector.unsupportedReason(structure)

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = if (geometry != null) {
        stringResource(
          R.string.edit_area_row,
          (areaFraction * 100).roundToInt(),
        )
      } else {
        stringResource(R.string.edit_area_no_geometry)
      },
      style = MaterialTheme.typography.bodySmall.copy(
        color = Color.White,
        shadow = createShadow()
      )
    )

    // The area control is discrete: its positions are the file's block rows, so
    // moving it moves the damage start one section at a time instead of sliding
    // through fractions of the image. The thumb is snapped here rather than with
    // Material's `steps`, because a section count in the dozens would turn the
    // track into a dotted line.
    val sections = geometry?.unitRows?.coerceAtLeast(1) ?: 0
    Slider(
      value = areaFraction,
      onValueChange = { fraction ->
        onAreaChange(
          if (sections > 1) {
            val row = (fraction.coerceIn(0f, 1f) * (sections - 1)).roundToInt()
              .coerceIn(0, sections - 1)
            row.toFloat() / (sections - 1)
          } else {
            fraction
          },
        )
      },
      valueRange = 0f..1f,
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
      ),
    )

    Spacer(Modifier.height(12.dp))

    if (restartsActive) {
      Text(
        text = stringResource(R.string.edit_window_active, structure.restartInterval),
        style = MaterialTheme.typography.bodySmall.copy(
          color = Color.White,
          shadow = createShadow()
        ),
        color = MaterialTheme.colorScheme.primary,
      )
    } else if (injectBlockedReason != null) {
      Text(
        text = stringResource(R.string.edit_window_unsupported, injectBlockedReason),
        style = MaterialTheme.typography.bodySmall.copy(
          color = Color.White,
          shadow = createShadow()
        )
      )
    } else {
      Text(
        text = stringResource(R.string.edit_window_section, (windowFraction * 100).roundToInt()),
        style = MaterialTheme.typography.labelMedium.copy(
          color = Color.White,
          shadow = createShadow()
        )
      )
      Slider(
        value = windowFraction,
        onValueChange = onWindowChange,
        valueRange = 0.02f..0.5f,
        colors = SliderDefaults.colors(
          thumbColor = MaterialTheme.colorScheme.tertiary,
          activeTrackColor = MaterialTheme.colorScheme.tertiary,
        ),
      )
      Button(
        onClick = onInject,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        enabled = !injecting,
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.tertiary,
          contentColor = MaterialTheme.colorScheme.onTertiary,
        ),
      ) {
        Text(
          text = if (injecting) stringResource(R.string.edit_window_working)
          else stringResource(R.string.edit_window_button),
        )
      }
    }

    val skipped = structure.fatalByteCount(GlitchRegion.SCAN)
    if (skipped > 0) {
      Spacer(Modifier.height(4.dp))
      Text(
        text = stringResource(R.string.edit_fatal_note, skipped),
        style = MaterialTheme.typography.bodySmall.copy(
          color = Color.White,
          shadow = createShadow()
        )
      )
    }

    readout?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
  }
}

/**
 * The row of four actions, drawn under the photo on a tall window and with the panel otherwise.
 *
 * The three flags are read off the holder on the frame that draws the row. Reset and undo are
 * the same condition here and deliberately so: reset clears the flips *and* the trail behind
 * them in one press - the stack back to empty, base and all, and `state.walkStep` back to zero -
 * so "there is a flip to take back" and "there is a flip to clear" are one question, and
 * [undoable] is asked once and answered for both rather than written out twice and left to
 * drift apart.
 *
 * Flip is the one flag with a second condition, and it is the only one the screen's arrival
 * guard is allowed to touch: [flipEnabled] is [flipReady] - `armed`, a preview to compare
 * candidates against, and no flip walk or restart injection already in flight. `armed`
 * withholds flip for the first moment after the screen appears, so the tap that brought the
 * user here cannot land on the one button that rewrites the file. Reset and undo are never
 * gated on it: they only ever take work back, so waiting on the guard would make the two safe
 * buttons on the row the slowest to come alive - and undo late is worse than undo early,
 * because the mistake it exists to make recoverable is the one a mis-aimed tap just made.
 * Neither is next gated on it, since with nothing flipped yet it hands on exactly the bytes
 * that arrived.
 *
 * Nothing here is remembered, either: a `remember`ed copy of the history would freeze the row on
 * its first frame, which is how a bar like this ends up with a reset and an undo that never come
 * alive.
 */
@Composable
private fun ScanActions(
  undoable: Boolean,
  flipEnabled: Boolean,
  onReset: () -> Unit,
  onUndo: () -> Unit,
  onFlip: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ScanActionRow(
    modifier = modifier,
    onReset = onReset,
    onUndo = onUndo,
    onFlip = onFlip,
    onNext = onNext,
    // Reset and undo wait for something to take back, but next does not: with nothing
    // flipped yet it takes on exactly the file that arrived, so there is no state in
    // which pressing it is wrong - and a row whose last button is dead until you have
    // touched something reads as a screen that has not finished loading.
    resetEnabled = undoable,
    undoEnabled = undoable,
    flipEnabled = flipEnabled,
    nextEnabled = true,
  )
}

// --- Previews ---------------------------------------------------------------

/**
 * The screen as it really behaves: the bundled file is parsed for real, so the area
 * slider points at an actual row, the readout names that row out of the file's own
 * height, the restart window and its button are in the state that file puts them
 * in, and the flip button is waiting for the guard that keeps the tap which brought
 * the user here from flipping anything.
 *
 * Only the picture is absent, and that is not a shortcut: the decoder is an .so and
 * a preview runs on the host, so it cannot be drawn here. The screen handles that
 * as it handles the first moment after it opens, which is the other thing this
 * preview shows.
 */

@Preview(name = "Scan data", device = "spec:width=411dp,height=891dp")
@Composable
private fun ScanScreenPreview() {
  GlitchTheme {
    // Surface, not showBackground: this is what paints the app's own background in
    // GlitchApp, so without it a preview sits on the tool's canvas instead.
    Surface(modifier = Modifier.fillMaxSize()) {
      ScanScreen(
        bytes = scanPreviewBytes(),
        examplePreview = PreviewUtil.previewImageBitmap(),
        onOpenHuffman = {},
        onBack = {},
        onContinue = { _, _, _ -> },
      )
    }
  }
}

/** And the state a file with nothing editable in its scan leaves it in. */
@Preview(name = "Scan data - nothing to edit", device = "spec:width=411dp,height=891dp")
@Composable
private fun ScanScreenNoScanPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      ScanScreen(
        bytes = "not a jpeg".toByteArray(),
        examplePreview = PreviewUtil.previewImageBitmap(),
        onOpenHuffman = {},
        onBack = {},
        onContinue = { _, _, _ -> },
      )
    }
  }
}

