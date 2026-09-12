package ca.justinmo.glitch.dqt

import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.NumberPicker
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ca.justinmo.glitch.ui.MediaPreview
import ca.justinmo.glitch.ui.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.jpeg.ByteSpan
import ca.justinmo.glitch.jpeg.JpegNative
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.JpegStructure
import ca.justinmo.glitch.ui.theme.GlitchTheme
import ca.justinmo.glitch.PreviewImage
import ca.justinmo.glitch.R
import ca.justinmo.glitch.ZoomState
import ca.justinmo.glitch.imageResolution
import ca.justinmo.glitch.decodeSampled
import ca.justinmo.glitch.ui.PreviewWithControls
import ca.justinmo.glitch.ui.ScanActionRow
import ca.justinmo.glitch.ui.STACKED_CONTROLS_HEIGHT
import ca.justinmo.glitch.ui.TitleBar
import ca.justinmo.glitch.ui.previewFrame
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-edge cap for the preview on this screen. */
private const val DQT_PREVIEW_CAP_PX = 1400

/**
 * Panel heights as a fraction of the window. The panel opens at its tallest -
 * half the window - and can be dragged down to [DQT_PANEL_SHORT_FRACTION], or
 * past [DQT_PANEL_DISMISS_FRACTION] to close.
 *
 * [DQT_PANEL_DRAG_MIN_FRACTION] is deliberately below [DQT_PANEL_DISMISS_FRACTION],
 * otherwise the clamp would stop the drag before it could ever dismiss.
 */
private const val DQT_PANEL_MAX_FRACTION = 0.5f
private const val DQT_PANEL_SHORT_FRACTION = 0.3f
private const val DQT_PANEL_DRAG_MIN_FRACTION = 0.08f
private const val DQT_PANEL_DISMISS_FRACTION = 0.2f

/**
 * Fling speed (px/s, downwards positive) that overrides the position when the
 * finger lifts: faster than this one way closes the panel, the other way opens
 * it fully. Below it the nearest stop wins.
 */
private const val DQT_PANEL_FLING_VELOCITY = 900f

/** The stops a released drag snaps to: the short one, and the half-window top. */
private val DQT_PANEL_STOPS = listOf(
  DQT_PANEL_SHORT_FRACTION,
  DQT_PANEL_MAX_FRACTION,
)

/**
 * Height the panel assumes when its parent gives it no bound at all - which is
 * what a preview (and any unbounded container) reports, and where multiplying an
 * infinite height by a fraction would blow up the layout.
 */
private val DQT_PANEL_FALLBACK_HEIGHT = 640.dp

/**
 * Which set of quantizers a tile opens: an effect family ([QuantAxis]) plus
 * whether it lives in the chroma table. `DC + chroma` is saturation and
 * `DC + luma` is brightness; "color" in the labels always means chroma.
 */
internal data class DqtFamily(val axis: QuantAxis, val chroma: Boolean, val tint: Boolean = false)

/**
 * The tiles on this screen in display order: the two that move the whole picture
 * first - they are by far the loudest - then each texture in luma and again in
 * chroma.
 */
internal val DQT_FAMILIES: List<DqtFamily> = listOf(
  DqtFamily(QuantAxis.DC, chroma = false, tint = true),
  DqtFamily(QuantAxis.DC, chroma = true, tint = true),
  DqtFamily(QuantAxis.HORIZONTAL, chroma = false,),
  DqtFamily(QuantAxis.HORIZONTAL, chroma = true,),
  DqtFamily(QuantAxis.VERTICAL, chroma = false,),
  DqtFamily(QuantAxis.VERTICAL, chroma = true,),
  DqtFamily(QuantAxis.CHECKERED, chroma = false,),
  DqtFamily(QuantAxis.CHECKERED, chroma = true,),
)

/** Quantizers of [family] in [tables], coarsest frequency first. */
internal fun entriesFor(tables: List<QuantTable>, family: DqtFamily): List<QuantEntry> =
  tables.filter { it.isChroma == family.chroma }
    .flatMap { it.entriesFor(family.axis) }
    .sortedWith(compareBy({ maxOf(it.u, it.v) }, { it.u }, { it.v }))

/**
 * [entriesFor] including quantizers the picture never uses.
 *
 * Resetting a pattern has to clear every byte it owns, not just the rows on
 * screen: an entry can be dead (its coefficient is zero everywhere) and still
 * have been edited before the decoder's census landed.
 */
private fun allEntriesFor(tables: List<QuantTable>, family: DqtFamily): List<QuantEntry> =
  tables.filter { it.isChroma == family.chroma }
    .flatMap { it.allEntriesFor(family.axis) }
    .sortedWith(compareBy({ maxOf(it.u, it.v) }, { it.u }, { it.v }))

/** Title of a family tile, e.g. "Checkered color". */
private fun familyTitleRes(family: DqtFamily): Int = when (family.axis) {
  QuantAxis.DC ->
    if (family.chroma) R.string.dqt_family_saturation else R.string.dqt_family_brightness
  QuantAxis.HORIZONTAL ->
    if (family.chroma) R.string.dqt_family_horizontal_color else R.string.dqt_family_horizontal
  QuantAxis.VERTICAL ->
    if (family.chroma) R.string.dqt_family_vertical_color else R.string.dqt_family_vertical
  QuantAxis.CHECKERED ->
    if (family.chroma) R.string.dqt_family_checkered_color else R.string.dqt_family_checkered
}

/** One line on what the family does to the picture. */
private fun familyBlurbRes(family: DqtFamily): Int = when (family.axis) {
  QuantAxis.DC -> if (family.chroma) {
    R.string.dqt_blurb_saturation
  } else {
    R.string.dqt_blurb_brightness
  }
  QuantAxis.HORIZONTAL ->
    if (family.chroma) R.string.dqt_blurb_horizontal_color else R.string.dqt_blurb_horizontal
  QuantAxis.VERTICAL ->
    if (family.chroma) R.string.dqt_blurb_vertical_color else R.string.dqt_blurb_vertical
  QuantAxis.CHECKERED ->
    if (family.chroma) R.string.dqt_blurb_checkered_color else R.string.dqt_blurb_checkered
}

/** Qualitative name of a scale, 1 (coarsest) to 7 (finest). */
private fun scaleWordRes(scale: Int): Int = when (scale) {
  1 -> R.string.dqt_scale_coarsest
  2 -> R.string.dqt_scale_coarse
  3 -> R.string.dqt_scale_medium
  4 -> R.string.dqt_scale_fine
  else -> R.string.dqt_scale_subpixel
}

/** Size text such as "4.0 px" or "0.8-1.0 px" for one section. */
private fun sizeText(entries: List<QuantEntry>): String {
  val widths = entries.map { it.bandPx }
  val low = widths.min()
  val high = widths.max()
  return if (high - low < 0.05f) {
    String.format(Locale.getDefault(), "%.1f px", low)
  } else {
    String.format(Locale.getDefault(), "%.1f-%.1f px", low, high)
  }
}

/**
 * Zoom and pan for a screen whose preview has controls drawn over it.
 *
 * The other screens put `transformable` on the image, which works because
 * nothing sits on top of it. Here the pattern row is drawn *over* the photo and
 * the bits panel slides over both, and a gesture that lands on either of them
 * never reaches the image - so this sits on the screen root instead, sees every
 * gesture first (a child cannot hide events from its parent) and decides:
 *
 * * **two or more fingers** zoom and pan, wherever they land - including on the
 *   pattern row and on the open panel;
 * * **one finger** pans only when the gesture starts on the photo and outside
 *   the panel, so the panel's list still scrolls and its buttons still tap. A
 *   tap is never consumed, because panning only starts past the touch slop.
 *
 * Shared with the DHT screen, which lays its tiles and its panel out the same
 * way and wants the same rules.
 */
internal fun Modifier.previewTransformGestures(
  previewBoundsProvider: () -> Rect?,
  panelBoundsProvider: () -> Rect?,
  onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
  val slop = viewConfiguration.touchSlop
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val preview = previewBoundsProvider()
    val panel = panelBoundsProvider()
    val panAllowed = preview != null && preview.contains(down.position) &&
      (panel == null || !panel.contains(down.position))

    var lastSpan = 0f
    var lastCentroid = Offset.Zero
    var pansBy = 0f
    var panning = false
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val pressed = event.changes.filter { it.pressed }
      if (pressed.isEmpty()) break

      if (pressed.size >= 2) {
        val centroid = pressed.fold(Offset.Zero) { sum, change -> sum + change.position } /
          pressed.size.toFloat()
        val span = pressed.fold(0f) { sum, change ->
          sum + (change.position - centroid).getDistance()
        } / pressed.size
        if (lastSpan > 0f && span > 0f) {
          onTransform((span / lastSpan).coerceIn(0.5f, 2f), centroid - lastCentroid)
        }
        lastSpan = span
        lastCentroid = centroid
        panning = false
        pressed.forEach { it.consume() }
      } else {
        lastSpan = 0f
        val change = pressed.first()
        if (!panAllowed) continue
        val delta = change.position - change.previousPosition
        pansBy += delta.getDistance()
        if (!panning && pansBy > slop) panning = true
        if (panning && delta != Offset.Zero) {
          onTransform(1f, delta)
          change.consume()
        }
      }
    }
  }
}

/**
 * Screen between [ca.justinmo.glitch.resize.ResizeScreen] and [ca.justinmo.glitch.scan.ScanScreen]: adjust the quantization tables
 * before glitching.
 *
 * It is the one table screen that is a stop on the flow's path rather than a side trip
 * off it: the table sets how the picture is stored, and the scan step that follows
 * flips bits in that stored form, so this has to be decided before the scan is reached.
 * The Huffman screen, the deeper edit, is opened from the scan screen's title instead -
 * backing out of it lands back there and confirming it goes on to the preview - and it is
 * not a step at all, and certainly not the step after this one.
 *
 * The tables decide how coarsely each frequency of the picture is stored, so
 * editing them damages one chosen pattern instead of random bytes: the eight
 * tiles pick the pattern, the panel behind a tile edits the bytes of the
 * quantizers that draw it one quantizer per row, and the action bar under the
 * photo carries the edits on, flips a bit by itself, or unwinds the last edit one
 * changed value per press - the undo stack holds *what each edit changed* rather
 * than a copy of the table after it, so an edit that moved twenty quantizers walks
 * back over twenty presses. Whatever is set goes into the bytes handed to
 * [ca.justinmo.glitch.scan.ScanScreen].
 *
 * The panel is deliberately **not** a `ModalBottomSheet`: that renders in its own
 * window behind a scrim and swallows every touch aimed at this screen, which
 * would stop the preview from being pinch-zoomed while a pattern is open. This
 * panel is a plain surface in the same window, so the preview above it stays
 * live and pinching keeps working - on the preview, and (through
 * [pinchOutsidePreview]) anywhere else on the screen too.
 *
 * Nothing the user decides here is remembered by the screen itself: the values,
 * the undo history, the flip count, the open pattern and the explanation are read
 * from and written to [DqtState], which outlives it, and the pinch-zoom is the
 * [ca.justinmo.glitch.ZoomState] the flow shares between its steps. Walking back to
 * this step therefore meets the table as it was left, not a clean one.
 */
@Composable
internal fun DqtScreen(
  bytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
  // The zoom and the edits are handed in rather than remembered in here, because
  // both belong to the edit rather than to the frame: the flow keeps one zoom across
  // its steps, and this step's table has to still be the table it was when the user
  // walks back to it. The defaults keep the screen usable on its own - a preview, or
  // a caller with nothing to hand in - by doing exactly what the removed `remember`
  // did: a zoom started where [initialScale] / [initialOffset] say, and no edits.
  zoom: ZoomState = remember(initialScale, initialOffset) { ZoomState(initialScale, initialOffset) },
  /**
   * The flow's shared decoded preview: this screen's first frame draws the picture the step
   * before it was showing rather than a blank over a re-decode of the same file.
   */
  previewImage: PreviewImage = remember { PreviewImage() },
  state: DqtState = remember { DqtState() },
) {
  val structure = remember(bytes) { JpegParser.parse(bytes) }

  // Which quantizers can move a pixel is a property of the entropy-coded data,
  // not of the DQT payload: the decoder multiplies each coefficient by its
  // quantizer, so a coefficient that is zero in every block stays zero whatever
  // the slider says. The decoder already has that answer, so ask it once per
  // file, off the main thread. Until it lands every entry counts as live; null
  // (library missing, undecodable stream) keeps that behaviour for good.
  var liveMasks by remember(bytes) { mutableStateOf<LongArray?>(null) }
  var censusDone by remember(bytes) { mutableStateOf(false) }
  LaunchedEffect(bytes) {
    liveMasks = withContext(Dispatchers.Default) { JpegNative.liveCoefficientMasks(bytes) }
    censusDone = true
  }

  // Two parses on purpose. [allTables] describes the bytes the writer needs and
  // never changes while the screen is up, so applying an edit does not depend on
  // the census (a dead entry is still one or two bytes wide). [tables] is the
  // same payload with each entry marked live or dead, and is what the panel and
  // the flip draw from.
  val allTables = remember(bytes, structure) { QuantTables.parse(bytes, structure) }
  val tables = remember(bytes, structure, liveMasks) {
    QuantTables.parse(bytes, structure, liveMasks)
  }

  // A panel opened before the census landed can turn out to have no live rows at
  // all (all of that pattern's coefficients are zero in this picture). Close it
  // rather than leave an empty sheet on screen.
  LaunchedEffect(tables, state.openFamily) {
    val open = state.openFamily
    if (open != null && entriesFor(tables, open).isEmpty()) state.openFamily = null
  }

  // What the photo measures, straight out of the header. Taken with the EXIF rotation applied
  // because that is how the decoder below will hand the picture back, so the frame the pane
  // lays out for before the decode lands is the frame the bitmap will fill.
  val shape = remember(bytes) { imageResolution(bytes) }

  // The edits (quantizer offset -> the value the user set, so everything else in the
  // file stays exactly as it was), the undo history, the quantizer the last edit
  // landed on, the flip count, the open pattern and the explanation all live in
  // [state] now instead of in `remember` here. They are what the user decided about
  // these tables, and a decision has to outlive the screen that made it: back is how
  // the flow changes its mind about the *next* step, not a way to lose this one, so
  // coming forward again has to meet the same edits, the same undo history and the
  // same open panel rather than a clean table. [DqtState] carries the explanation of
  // each field and the tie-breakers they encode.
  //
  // Only what describes the frame being drawn stays local below: the parsed structure
  // and tables, the decoded preview, the last family the panel showed, the measured
  // bounds and the panel's own animation. All of those are rebuilt from [bytes] and
  // the layout the moment the screen comes back, so remembering them would only keep
  // a stale frame alive.
  var preview by remember(bytes) {
    mutableStateOf(previewImage.showable(bytes, shape))
  }

  // The panel animates out after the family is cleared, so it keeps the last
  // one it showed; its bounds are dropped with it, so a closed panel cannot go
  // on blocking pans over the photo.
  var shownFamily by remember { mutableStateOf<DqtFamily?>(null) }
  var previewBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var panelBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var screenBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  LaunchedEffect(state.openFamily) {
    if (state.openFamily == null) panelBoundsInRoot = null
  }

  val applied = remember(bytes, allTables, state.edits) {
    if (state.edits.isEmpty()) bytes else bytes.copyOf().also {
      QuantTables.applyValues(it, allTables, state.edits)
    }
  }

  LaunchedEffect(applied, shape) {
    if (previewImage.covers(applied, DQT_PREVIEW_CAP_PX)) {
      previewImage.showable(applied, shape)?.let { preview = it }
      return@LaunchedEffect
    }
    // The picture already on screen - the last step's, at this step's edits one decode
    // behind - stays there until this lands: a frame of the right shape a shade out of date
    // reads as the edit being made, where a blank reads as the app having lost the photo.
    val decoded = withContext(Dispatchers.Default) {
      decodeSampled(
        applied,
        DQT_PREVIEW_CAP_PX
      )
    }
    if (decoded != null) {
      previewImage.put(applied, DQT_PREVIEW_CAP_PX, decoded)
      preview = decoded
    }
  }

  // Back closes what is on top first: the explanation, then the open pattern,
  // then the screen.
  BackHandler(enabled = state.openFamily != null) { state.openFamily = null }
  BackHandler(enabled = state.showInfo) { state.showInfo = false }

  val transform: (Float, Offset) -> Unit = { zoomChange, panChange ->
    zoom.scale = (zoom.scale * zoomChange).coerceIn(0.5f, 5f)
    zoom.offset += panChange
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onGloballyPositioned { screenBoundsInRoot = it.boundsInRoot() }
      // While the explanation is up, its scroller owns every gesture.
      .then(
        if (state.showInfo) {
          Modifier
        } else {
          Modifier.previewTransformGestures(
            // Read through the state holders, so the bounds are current whenever
            // a gesture arrives (this lambda is captured once, the values are not).
            previewBoundsProvider = {
              val screen = screenBoundsInRoot
              val preview = previewBoundsInRoot
              if (screen == null || preview == null) null
              else preview.translate(-screen.topLeft)
            },
            panelBoundsProvider = {
              val screen = screenBoundsInRoot
              val panel = panelBoundsInRoot
              if (screen == null || panel == null) null
              else panel.translate(-screen.topLeft)
            },
            onTransform = transform,
          )
        },
      ),
  ) {
    DqtScreenContent(
      preview = preview?.asImageBitmap(),
      expectedSize = shape,
      tables = tables,
      edits = state.edits,
      zoomScale = zoom.scale,
      zoomOffset = zoom.offset,
      onPreviewBounds = { previewBoundsInRoot = it },
      onBack = onBack,
      onInfo = { state.showInfo = true },
      // Set the shown family with the open one, so the panel is created on the
      // tapped family rather than reusing the page it was left on.
      onOpenFamily = { family ->
        shownFamily = family
        state.openFamily = family
      },
      onNext = { onContinue(applied, zoom.scale, zoom.offset) },
      onResetAll = {
        // Clearing the table goes on the stack like any other edit, and it is
        // recorded *before* the map is replaced: what undo has to put back is what
        // the map held a moment ago, which is the whole of it. A table with nothing
        // set has nothing to come back to, so it leaves no change at all.
        val next = emptyMap<Int, Int>()
        if (state.edits.isNotEmpty()) {
          state.history.record(before = state.edits, after = next)
          state.lastEdited = null
        }
        state.edits = next
      },
      onUndo = {
        // The last change comes off the stack and is reversed in the edits map: the
        // value that change found there, or - when it found no edit at all - the
        // offset taken back out. That second half is what keeps "set back to the
        // value the file already carries" distinct from "never touched", which is
        // the distinction the map itself makes and the badges are drawn from.
        //
        // One press reverses one change, and a change is one quantizer: the wheel's
        // guard below leaves a single change for a whole drag, but a reset that
        // cleared twenty quantizers left twenty.
        state.edits = state.history.undo(state.edits)
        state.lastEdited = null
      },
      canUndo = !state.history.isEmpty,
      // No flip until the census has landed: before it, every quantizer still
      // counts as live and the draw could land on one this picture never reads.
      canFlip = censusDone && tables.any { it.liveEntries.isNotEmpty() },
      onFlip = {
        // The quantizer is drawn from the live entries of every table, so the
        // bytes it moves are ones the decoder actually reads. The bit is drawn
        // from the high bits only: bit 0 can leave the effective value at 1 (0
        // and 1 both decode as 1), and the low bits can move the quantizer too
        // little for the IDCT change to survive rounding - even though the
        // coefficient is non-zero. See [flipQuantizerBit].
        val quantizers = tables.flatMap { it.liveEntries }
        if (quantizers.isNotEmpty()) {
          // Seeded from the clock, so the draw differs between runs of the app and
          // between presses. The press number rides along with it because a seed of
          // milliseconds alone would repeat for two presses inside the same one -
          // and it is counted in the holder with the rest of the step, so the
          // presses made before a walk back are still counted after it.
          val random = kotlin.random.Random(System.currentTimeMillis() + state.flipStep)
          val quantizer = quantizers[random.nextInt(quantizers.size)]
          val target = quantizer.offset
          val current = state.edits[target] ?: quantizer.value
          val flipped = flipQuantizerBit(current, quantizer.bitCount, random)
          // Landing back on the value the file already has drops the edit, so the
          // counts and the tile badges stay honest.
          val next = if (flipped == quantizer.value) {
            state.edits - target
          } else {
            state.edits + (target to flipped)
          }
          // A flip moves one quantizer, so the step it leaves is that one value and
          // the value it displaced - two ints, where the old stack copied the whole
          // edits map to undo a single bit. Recorded before the map is replaced,
          // because the value the change found is the one still in it.
          state.history.record(before = state.edits, after = next)
          state.edits = next
          state.lastEdited = target
          state.flipStep += 1
        }
      },
    )

    AnimatedVisibility(
      visible = state.openFamily != null,
      enter = slideInVertically { it },
      exit = slideOutVertically { it },
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      shownFamily?.let { family ->
        DqtBitsPanel(
          family = family,
          tables = tables,
          edits = state.edits,
          onValueChange = { entry, value ->
            // Setting a quantizer back to the value the file already has drops
            // the edit, so the counts and badges stay honest.
            val next = if (value == entry.value) {
              state.edits - entry.offset
            } else {
              state.edits + (entry.offset to value)
            }
            // The wheel fires for every step of a drag, so only the first step on a
            // new quantizer leaves a change to come back to: the rest of the drag
            // only moves that one quantizer further, and undo wants where the drag
            // started rather than every tick along the way. The guard is on the
            // *quantizer* and not on the value, which is the meaning it had when it
            // guarded a snapshot - and it is also why the change carries the value
            // the first tick set, with the ticks after it leaving the stack alone.
            if (state.lastEdited != entry.offset) {
              state.history.record(before = state.edits, after = next)
              state.lastEdited = entry.offset
            }
            state.edits = next
          },
          onResetEntry = { entry ->
            // Clearing a quantizer is an edit like any other, so undo can bring it
            // back - but only when there was something set to clear, or the change
            // would say a value went from nothing to nothing.
            val next = state.edits - entry.offset
            if (state.edits.containsKey(entry.offset)) {
              state.history.record(before = state.edits, after = next)
              state.lastEdited = null
            }
            state.edits = next
          },
          onResetFamily = { target ->
            val offsets = allEntriesFor(tables, target).mapTo(HashSet()) { it.offset }
            val next = state.edits.filterKeys { it !in offsets }
            // One press clears a whole pattern, and what it leaves on the stack is
            // one change per quantizer that press cleared - each holding the value
            // that quantizer had, so the pattern can be walked back. The record is
            // taken against the map as it stands now rather than against the one it
            // becomes, because this is where every value a change has to put back is
            // still there to be read. With nothing set in this pattern there is
            // nothing to come back to, so it leaves no change at all.
            if (state.edits.keys.any { it in offsets }) {
              state.history.record(before = state.edits, after = next)
              state.lastEdited = null
            }
            state.edits = next
          },
          onFamilyChange = { state.openFamily = it },
          onClose = { state.openFamily = null },
          onBounds = { panelBoundsInRoot = it },
        )
      }
    }

    // Drawn last, so the explanation covers the panel and the pattern row.
    if (state.showInfo) {
      DqtInfoScreen(onClose = { state.showInfo = false })
    }
  }
}

/**
 * Stateless body of [DqtScreen]: the preview with the pattern row over its
 * bottom edge and the action row centred in the space under it, laid out
 * like the resize screen - both drawn inside the pane the shared [MediaPreview]
 * lays out, and over the media instead when the pane has no room left below the
 * photo. Decoding, byte editing and the zoom all happen above it - in [DqtScreen],
 * which keeps the edits in [DqtState] and reads the shared
 * [ca.justinmo.glitch.ZoomState] - so this can be rendered - and previewed - on its
 * own from the values it is handed.
 */
@Composable
internal fun DqtScreenContent(
  preview: ImageBitmap?,
  /**
   * What the photo measures, for the frames before [preview] has arrived: the pane lays itself
   * out for the picture that is coming rather than for one the size of the whole pane, which is
   * what stops the pattern row and the buttons from moving the moment the decode lands.
   */
  expectedSize: Pair<Int, Int>? = null,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  zoomScale: Float,
  zoomOffset: Offset,
  onPreviewBounds: (Rect) -> Unit,
  onBack: () -> Unit,
  onInfo: () -> Unit,
  onOpenFamily: (DqtFamily) -> Unit,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  onUndo: () -> Unit,
  onFlip: () -> Unit,
  canUndo: Boolean,
  canFlip: Boolean,
  modifier: Modifier = Modifier,
) {
  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      // The shared pane draws the photo - fitted, or covering a pane too short for
      // the 9:16 frame this app works in - and hands the furniture over it the
      // measurements it laid out with. That is what keeps the pattern row on the
      // photo's real bottom edge and the buttons centred in the room underneath:
      // the pane and the rows now read the same arithmetic instead of each working
      // it out for themselves and drifting apart on the phones where they disagree.
      //
      // `gesture` is left empty on purpose. This screen needs its pinch to work
      // anywhere - over the pattern row and over the open panel as well as over the
      // picture - so the gesture sits on the screen root ([previewTransformGestures])
      // and a second one on the photo would only compete with it.
      //
      // `filterQuality` is left out too, because its default is already what this
      // screen drew with: FilterQuality.None, the mode that keeps a glitch's block
      // edges exact. The decode here is capped near the size the photo is shown at,
      // so the cheap mode is also the accurate one.
      MediaPreview(
        bitmap = preview,
        contentDescription = stringResource(R.string.dqt_preview_desc),
        modifier = previewModifier.onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) },
        zoomScale = zoomScale,
        zoomOffset = zoomOffset,
        expectedSize = expectedSize,
      ) { frame ->
        Box(modifier = Modifier.fillMaxWidth()) {
          TitleBar(
            title = stringResource(R.string.dqt_title),
            onBack = onBack,
            onInfo = onInfo,
          )
        }

        // A photo with a screen's worth of room under it keeps that room for the controls:
        // the pattern row and the action row are stacked in the bottom portion and the
        // picture is centred above them, rather than the row riding a bottom edge that on a
        // landscape photo is halfway up the screen.
        if (frame.stacksControls) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .height(STACKED_CONTROLS_HEIGHT)
              .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.SpaceBetween,
          ) {
            DqtFamilyRow(
              tables = tables,
              edits = edits,
              onOpenFamily = onOpenFamily,
            )
            DqtActionsRow(
              edits = edits,
              onNext = onNext,
              onResetAll = onResetAll,
              onUndo = onUndo,
              onFlip = onFlip,
              canUndo = canUndo,
              canFlip = canFlip,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        } else if (frame.hasRoomBelow) {
          // Pattern row over the bottom edge of the photo.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(frame.photoHeight),
            contentAlignment = Alignment.BottomCenter,
          ) {
            DqtFamilyRow(
              tables = tables,
              edits = edits,
              onOpenFamily = onOpenFamily,
            )
          }

          // The action row centred in the space below the photo.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(frame.roomBelow)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
          ) {
            DqtActionsRow(
              edits = edits,
              onNext = onNext,
              onResetAll = onResetAll,
              onUndo = onUndo,
              onFlip = onFlip,
              canUndo = canUndo,
              canFlip = canFlip,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The pane's own rule, asked again from outside the pane. This slot never
        // sees the frame the pane laid out with, so it works that frame out from the
        // same shared arithmetic - and then asks `hasRoomBelow`, which is precisely
        // the question the pane answered before drawing the rows into its own
        // layout. Its negation is therefore exactly "the pane drew no rows": a
        // window too short, or a photo that fills the pane. Written as that negation
        // rather than as the shorter-looking comparison it replaces, so the two
        // sides of the screen cannot drift apart again.
        val frame = previewFrame(preview, maxWidth, maxHeight, expectedSize)
        if (!frame.hasRoomBelow) {
          DqtFamilyRow(
            tables = tables,
            edits = edits,
            onOpenFamily = onOpenFamily,
            modifier = Modifier.padding(top = 16.dp),
          )
        }
      }
    },
    controls = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Same rule as the overlay above, and the same reason: the action row only
        // belongs down here when the pane kept its rows to itself.
        val frame = previewFrame(preview, maxWidth, maxHeight, expectedSize)
        if (!frame.hasRoomBelow) {
          DqtActionsRow(
            edits = edits,
            onNext = onNext,
            onResetAll = onResetAll,
            onUndo = onUndo,
            onFlip = onFlip,
            canUndo = canUndo,
            canFlip = canFlip,
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp, bottom = 24.dp),
          )
        }
      }
    },
  )
}

/** The eight pattern buttons, one per effect family. */
@Composable
private fun DqtFamilyRow(
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onOpenFamily: (DqtFamily) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DQT_FAMILIES.forEach { family ->
      DqtFamilyIconButton(
        family = family,
        tables = tables,
        edits = edits,
        onClick = { onOpenFamily(family) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * Reset / undo / flip / next, on the shared action bar.
 *
 * All four are here. The wheel over a tile *sets* a quantizer, but a quantizer can
 * also be flipped with nothing to lean on - one bit of the value it already has -
 * so this step has a flip to offer, and undo is what makes aiming that flip safe
 * to try. Reset stays put and greys out until there is something to clear: a row
 * whose buttons come and go is harder to aim at than one that always has the same
 * four in the same places.
 */
@Composable
private fun DqtActionsRow(
  edits: Map<Int, Int>,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  onUndo: () -> Unit,
  onFlip: () -> Unit,
  canUndo: Boolean,
  canFlip: Boolean,
  modifier: Modifier = Modifier,
) {
  // Every flag is worked out here, on the recomposition that drew the row, and none of
  // them is remembered anywhere above it: `edits` is `state.edits` and `canUndo` is
  // `!state.history.isEmpty`, both read in the screen body a frame up and handed
  // down, while `canFlip` asks the *file* whether it carries a quantizer to flip -
  // which is the one of the three that is about the photo rather than about what the
  // user has done to it. That is the whole of what this row has to get right: the way a
  // bar like this goes wrong is a flag taken from a copy - a `remember`ed map, a val
  // hoisted above the recomposing scope, a count snapshotted before the holder moved -
  // which pins the button to the frame it was drawn on, leaving reset grey after the
  // first edit and undo grey the moment it is needed.
  ScanActionRow(
    modifier = modifier,
    onNext = onNext,
    nextEnabled = true,
    onReset = onResetAll,
    resetEnabled = edits.isNotEmpty(),
    onUndo = onUndo,
    undoEnabled = canUndo,
    onFlip = onFlip,
    flipEnabled = canFlip,
  )
}

@Composable
internal fun familyIcon(family: DqtFamily): ImageVector = when (family.axis) {
  QuantAxis.DC -> if (family.chroma) Icons.Filled.Contrast else Icons.Filled.LightMode
  QuantAxis.HORIZONTAL -> if (family.chroma) ImageVector.vectorResource(R.drawable.horizontal_color) else ImageVector.vectorResource(
    R.drawable.horizontal)
  QuantAxis.VERTICAL -> if (family.chroma) ImageVector.vectorResource(R.drawable.vertical_color) else ImageVector.vectorResource(
    R.drawable.vertical)
  QuantAxis.CHECKERED -> if (family.chroma) ImageVector.vectorResource(R.drawable.checkered_color) else ImageVector.vectorResource(
    R.drawable.checkered)
}

/** One pattern icon button: icon representing the family area/type. */
@Composable
private fun DqtFamilyIconButton(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val entries = entriesFor(tables, family)
  val changed = entries.count { edits.containsKey(it.offset) }
  val enabled = entries.isNotEmpty()

  Surface(
    modifier = modifier
      .padding(4.dp)
      .aspectRatio(1f)
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = when {
      !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
      changed > 0 -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      changed > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
      else -> MaterialTheme.colorScheme.onSurface
    },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = familyIcon(family),
        contentDescription = stringResource(familyTitleRes(family)),
        modifier = Modifier.size(24.dp),
        tint = if (family.tint) LocalContentColor.current else Color.Unspecified,
      )
    }
  }
}

/**
 * The pattern panel: every quantizer of the current family, one per row, under
 * section labels that say how fine the row's pattern is. Each row is a label, a
 * slider and a reset, and swiping left or right pages through the other
 * families without leaving the panel.
 *
 * The panel is a plain surface over the bottom of the screen rather than a modal
 * sheet, so the preview above stays interactive - pinch it to inspect the effect
 * while the panel is open. It can be dragged by its handle: up to its tall stop,
 * down to its short one, and down past that to close.
 */
@Composable
private fun DqtBitsPanel(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onValueChange: (QuantEntry, Int) -> Unit,
  onResetEntry: (QuantEntry) -> Unit,
  onResetFamily: (DqtFamily) -> Unit,
  onFamilyChange: (DqtFamily) -> Unit,
  onClose: () -> Unit,
  onBounds: (Rect) -> Unit,
  initialFraction: Float = DQT_PANEL_MAX_FRACTION,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    // A preview - or any parent that leaves the height unbounded - reports an
    // infinite max height, and scaling that by the panel fraction throws. Fall
    // back to a sane window instead.
    val windowHeight = if (maxHeight.value.isFinite()) maxHeight else DQT_PANEL_FALLBACK_HEIGHT
    val windowHeightPx = with(LocalDensity.current) { windowHeight.toPx() }.coerceAtLeast(1f)

    // Where the panel sits while it is being dragged, and where it snaps to when
    // the finger lifts. Following the finger exactly (snap) and animating the
    // rest keeps the drag glued to the touch without a springy lag.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(initialFraction) }
    var targetFraction by remember { mutableFloatStateOf(initialFraction) }
    val fraction by animateFloatAsState(
      targetValue = if (dragging) dragFraction else targetFraction,
      animationSpec = if (dragging) snap() else tween(durationMillis = 200),
    )

    val pagerState = rememberPagerState(
      initialPage = DQT_FAMILIES.indexOf(family).coerceAtLeast(0),
    ) { DQT_FAMILIES.size }
    // Land on the family that was tapped. `initialPage` only applies the first
    // time this pager is created, and the panel stays composed while it slides
    // out (and keeps the last family it showed), so without this an already
    // built pager would open on whatever page it was left on.
    LaunchedEffect(family) {
      val index = DQT_FAMILIES.indexOf(family)
      if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    // Report the settled page, so the screen's idea of the open family keeps up
    // with the swipes (and closing reopens where the user left off).
    LaunchedEffect(pagerState.currentPage) {
      onFamilyChange(DQT_FAMILIES[pagerState.currentPage])
    }

    // One drag for every grab target: the handle, the page header, and (through
    // the nested-scroll connection below) the quantizer list itself.
    val dragState = rememberDraggableState { delta ->
      dragFraction = (dragFraction - delta / windowHeightPx)
        .coerceIn(DQT_PANEL_DRAG_MIN_FRACTION, DQT_PANEL_MAX_FRACTION)
    }
    /**
     * Where the panel goes when the finger lifts: a flick decides it, otherwise
     * the nearest stop. [velocityY] is in px/s, positive downwards.
     */
    fun settleDrag(velocityY: Float = 0f) {
      when {
        velocityY > DQT_PANEL_FLING_VELOCITY -> {
          // Flicked down: leave, from whatever height it was at. Freeze at that
          // height first, so it slides away at that size instead of springing
          // back up on the way out.
          targetFraction = dragFraction
          onClose()
        }
        velocityY < -DQT_PANEL_FLING_VELOCITY -> {
          // Flicked up: open as far as it goes.
          targetFraction = DQT_PANEL_MAX_FRACTION
        }
        dragFraction < DQT_PANEL_DISMISS_FRACTION -> {
          targetFraction = dragFraction
          onClose()
        }
        else -> {
          // Neither flick nor past the dismissal height: snap to the nearest stop.
          targetFraction = DQT_PANEL_STOPS.minBy { stop -> kotlin.math.abs(stop - dragFraction) }
        }
      }
    }
    val dragModifier = Modifier.draggable(
      orientation = Orientation.Vertical,
      state = dragState,
      onDragStarted = {
        dragFraction = fraction
        dragging = true
      },
      onDragStopped = { velocity ->
        dragging = false
        settleDrag(velocity)
      },
    )
    // A scrollable child always beats a parent's own drag detector, so a drag
    // inside the list cannot be taken by [dragModifier]. This instead takes the
    // scroll the list could not use: while the list has room it consumes
    // everything, and once it is at its end the leftover arrives here and moves
    // the panel - which is what makes the sheet draggable from anywhere on it.
    val listDrag = object : NestedScrollConnection {
      override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        // Only a finger moves the panel. Scroll left over from a *fling* is the
        // list arriving at its end with momentum to spare - a flick from the
        // bottom of the list back up to the top must not carry on into pulling
        // the sheet down, which would collapse and then dismiss it.
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y == 0f) return Offset.Zero
        dragging = true
        dragFraction = (dragFraction - available.y / windowHeightPx)
          .coerceIn(DQT_PANEL_DRAG_MIN_FRACTION, DQT_PANEL_MAX_FRACTION)
        return Offset(0f, available.y)
      }

      /**
       * Called when the finger lifts, before the list's fling runs - which is
       * why settling happens here and not in [onPostFling]: that one only fires
       * once the fling animation has finished, so a flick that left the list
       * still travelling would hold the panel at its dragged height for the
       * length of that animation before it finally closed.
       */
      override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        settleDrag(available.y)
        // The panel took the gesture; there is nothing left for the list to do.
        return available
      }

      override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // Safety net for a gesture that reached the fling phase without passing
        // through [onPreFling].
        if (dragging) {
          dragging = false
          settleDrag(available.y)
        }
        return Velocity.Zero
      }
    }

    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(windowHeight * fraction)
        .nestedScroll(listDrag)
        .onGloballyPositioned { onBounds(it.boundsInRoot()) },
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      tonalElevation = 3.dp,
      shadowElevation = 12.dp,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // Grab handle. The panel can be dragged from the header and from inside
        // the list as well, but this is the one target that always works, even
        // with the list scrolled.
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            .padding(vertical = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .width(36.dp)
              .height(4.dp)
              .clip(RoundedCornerShape(2.dp))
              .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
          )
        }

        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
          DqtFamilyPage(
            family = DQT_FAMILIES[page],
            tables = tables,
            edits = edits,
            onValueChange = onValueChange,
            onResetEntry = onResetEntry,
            onResetFamily = onResetFamily,
            onClose = onClose,
            modifier = dragModifier,
          )
        }
      }
    }
  }
}

/**
 * Everything of one family: its title, what it does, and its quantizer rows.
 *
 * [modifier] carries the panel drag, so the title block can be used to
 * move the sheet - the close button rides along in the title row, where it sits
 * next to the thing it closes.
 */
@Composable
private fun DqtFamilyPage(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onValueChange: (QuantEntry, Int) -> Unit,
  onResetEntry: (QuantEntry) -> Unit,
  onResetFamily: (DqtFamily) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier,
) {
  val entries = remember(tables, family) { entriesFor(tables, family) }
  // Sections are grouped by scale - the dominant frequency of the pattern -
  // coarsest first. DC has no scale at all, so it gets a single labelled group.
  val sections: List<Pair<Int?, List<QuantEntry>>> = remember(entries, family) {
    if (family.axis == QuantAxis.DC) {
      listOf(null to entries)
    } else {
      entries.groupBy { maxOf(it.u, it.v) }
        .toSortedMap()
        .map { (scale, group) -> scale to group }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(modifier)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(familyTitleRes(family)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(
              R.string.dqt_sheet_counts,
              entries.size,
              stringResource(
                if (family.chroma) R.string.dqt_table_chroma else R.string.dqt_table_luma
              ),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = stringResource(familyBlurbRes(family)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Top)) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.common_close),
          )
        }
      }
      Text(
        // The DC families have no pattern to hint at; they get told how the
        // wheel works instead.
        text = stringResource(
          if (family.axis == QuantAxis.DC) R.string.dqt_sheet_hint_dc else R.string.dqt_sheet_hint
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(
        onClick = { onResetFamily(family) },
        // Nothing to undo until one of this pattern's quantizers has been moved.
        enabled = entries.any { edits.containsKey(it.offset) },
      ) {
        Icon(
          imageVector = Icons.Filled.Refresh,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = stringResource(R.string.dqt_sheet_reset))
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      sections.forEach { (scale, group) ->
        item(key = "header-$scale") {
          Text(
            text = if (scale == null) {
              stringResource(R.string.dqt_section_dc)
              } else {
                stringResource(
                  if (family.axis == QuantAxis.CHECKERED) {
                    R.string.dqt_section_cells
                  } else {
                    R.string.dqt_section_bands
                  },
                  stringResource(scaleWordRes(scale)),
                  sizeText(group),
                )
              },
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
            )
          }
          items(group, key = { it.offset }) { entry ->
            QuantizerRow(
              entry = entry,
              value = edits[entry.offset] ?: entry.value,
              edited = edits.containsKey(entry.offset),
              onValueChange = { value -> onValueChange(entry, value) },
              onReset = { onResetEntry(entry) },
            )
          }
        }
        item { Spacer(Modifier.height(24.dp)) }
      }
    }
}

/**
 * One quantizer in a single row: a control over every value the byte can hold
 * (0-255 for the usual 8-bit table) and the value it is set to.
 *
 * Most families get a slider, plus a reset button once the quantizer differs from
 * the file: a whole pattern of coefficients is adjusted by feel, and dragging
 * along the row moves a lot of them quickly. The DC families - brightness and
 * saturation - get a [NumberPicker] instead, because there the table holds one
 * number that *is* the effect, so exact single steps beat a coarse drag. Those
 * rows have no reset button: one number is the whole family, so the panel's
 * "Reset this pattern" is already exactly that undo.
 *
 * Dragging right (or stepping up) makes the quantizer coarser - that frequency is
 * stored more roughly and rings harder - and a value of 0 deletes it outright,
 * which is why the value turns red there.
 */
@Composable
private fun QuantizerRow(
  entry: QuantEntry,
  value: Int,
  edited: Boolean,
  onValueChange: (Int) -> Unit,
  onReset: () -> Unit,
) {
  val maxValue = (1 shl entry.bitCount) - 1
  val isDc = entry.axis == QuantAxis.DC
  // Zero and "changed" mean the same in every row: a quantizer at zero drops that
  // frequency entirely, a changed one is flagged with the accent. Both controls
  // use the same three colors, so a slider row and a picker row read alike.
  val valueColor = when {
    value == 0 -> MaterialTheme.colorScheme.error
    edited -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  // The picker's field shows only the digits, so name the quantizer in words for
  // screen readers and for the preview, where the wheel is not drawn at all.
  val pickerLabel = if (LocalInspectionMode.current) {
    "block average, value $value"
  } else {
    stringResource(R.string.dqt_row_picker_label, value)
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 2.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    // The picker is the only thing in its row, so it sits in the middle rather
    // than hugging the edge the reset button used to hold down.
    horizontalArrangement = if (isDc) Arrangement.Center else Arrangement.spacedBy(8.dp),
  ) {
    if (isDc) {
      NumberPickerCell(
        value = value,
        maxValue = maxValue,
        textColor = valueColor,
        onValueChange = onValueChange,
        modifier = Modifier.semantics {
          // The wheel only announces "5"; say which quantizer that is.
          contentDescription = pickerLabel
        },
      )
    } else {
      Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maxValue)) },
        valueRange = 0f..maxValue.toFloat(),
        modifier = Modifier.weight(1f).padding(start = 16.dp).systemGestureExclusion(),
        colors = SliderDefaults.colors(
          thumbColor = MaterialTheme.colorScheme.primary,
          activeTrackColor = MaterialTheme.colorScheme.primary,
        ),
      )

      Text(
        text = value.toString(),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        // Wide enough for a 16-bit table's five digits.
        modifier = Modifier.width(32.dp),
        maxLines = 1,
        color = valueColor,
      )

      if (edited) {
        IconButton(onClick = onReset, modifier = Modifier.size(48.dp)) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            // Use LocalInspectionMode to provide a fallback string during Compose Previews
            // to bypass any potential out-of-sync layoutlib/Preview cache NoSuchFieldError issues.
            contentDescription = if (LocalInspectionMode.current) {
              "reset to ${entry.value}"
            } else {
              stringResource(R.string.dqt_row_reset_to, entry.value)
            },
          )
        }
      } else {
        // Same footprint as the button, so the sliders stay lined up.
        Spacer(Modifier.size(48.dp))
      }
    }
  }
}

/** A wheel needs a fixed box; [NumberPicker] has no intrinsic size in Compose. */
private val NUMBER_PICKER_WIDTH = 96.dp
private val NUMBER_PICKER_HEIGHT = 112.dp

/**
 * The platform number picker, wrapped for Compose.
 *
 * Compose ships no equivalent, so this is an [AndroidView] around
 * [android.widget.NumberPicker]: a wheel of the values the byte can hold with
 * +1/-1 steppers beside it. A drag on the wheel turns the wheel rather than
 * scrolling the list under it, and the number field is blocked from focus, so no
 * soft keyboard rises over the panel.
 *
 * It is built with [ca.justinmo.glitch.R.style.Theme_Glitch_NumberPicker] rather than the app's own
 * theme: the widget colors itself from the Android theme it is inflated with, and
 * the app's is a light one (Compose paints the black surface itself), which would
 * leave it drawing black numbers and arrows onto black. Only the selected number
 * is recolored from Compose, for the changed and zeroed states.
 *
 * Draws nothing in a Compose preview: `AndroidView` needs a real Android context,
 * so the previews show a stand-in of the wheel instead.
 *
 * Shared with the DHT screen, whose DC symbol rows are the same kind of control:
 * one number that *is* the effect.
 */
@Composable
internal fun NumberPickerCell(
  value: Int,
  maxValue: Int,
  textColor: Color,
  onValueChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  // The view is built once and lives on across recompositions, so the callback it
  // was wired to would go stale without this.
  val currentOnValueChange by rememberUpdatedState(onValueChange)
  val argb = textColor.toArgb()
  // Bound outside the builder: inside it the parameter names would be shadowed by
  // NumberPicker's own `value` and `maxValue` properties, and the assignments
  // below would quietly read back the widget's fields instead.
  val top = maxValue
  val initial = value.coerceIn(0, top)
  val context = LocalContext.current
  val pickerContext = remember(context) {
    ContextThemeWrapper(context, R.style.Theme_Glitch_NumberPicker)
  }

  if (LocalInspectionMode.current) {
    // AndroidView renders nothing in a preview and the picker needs a real
    // context, so previews get a stand-in laid out like the widget: the selected
    // value in the middle, the values either side of it above and below in the
    // color the widget's own wheel uses, and the two steppers at the right edge.
    // Same box, so nothing else in the panel shifts when the real wheel appears.
    val wheelColor = colorResource(R.color.number_picker_text)
    Box(
      modifier = Modifier.width(NUMBER_PICKER_WIDTH).height(NUMBER_PICKER_HEIGHT),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Above the selected value come the larger ones, as on the wheel. Blank at
        // the ends, where the wheel stops instead of wrapping.
        Text(
          text = if (value < top) (value + 1).toString() else "",
          style = MaterialTheme.typography.bodyLarge,
          color = wheelColor,
          maxLines = 1,
        )
        Text(
          text = value.toString(),
          style = MaterialTheme.typography.bodyLarge,
          color = textColor,
          maxLines = 1,
        )
        Text(
          text = if (value > 0) (value - 1).toString() else "",
          style = MaterialTheme.typography.bodyLarge,
          color = wheelColor,
          maxLines = 1,
        )
      }
      Column(
        modifier = Modifier.align(Alignment.CenterEnd),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Filled.ArrowDropUp,
          contentDescription = null,
          tint = textColor,
          modifier = Modifier.size(20.dp),
        )
        Icon(
          imageVector = Icons.Filled.ArrowDropDown,
          contentDescription = null,
          tint = textColor,
          modifier = Modifier.size(20.dp),
        )
      }
    }
    return
  }

  AndroidView(
    factory = {
      NumberPicker(pickerContext).apply {
        // maxValue has to be in place before value, or the setter clamps it.
        // Both are named like the parameters of this composable, so they are
        // addressed through the receiver.
        minValue = 0
        this.maxValue = top
        this.value = initial
        // No wrapping: a value that jumps from 255 back to 0 would be a
        // catastrophic edit made by accident.
        wrapSelectorWheel = false
        setFormatter { it.toString() }
        // Keep the inner text field out of the way: no caret, no soft keyboard
        // rising over the panel. The wheel and buttons still work.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        tintPicker(this, argb)
        setOnValueChangedListener { _, _, newValue -> currentOnValueChange(newValue) }
      }
    },
    update = { picker ->
      // The wheel takes its color from the theme it was built with; the selected
      // number follows the row's state instead.
      tintPicker(picker, argb)
      if (picker.maxValue != top) picker.maxValue = top
      if (picker.value != value) picker.value = value.coerceIn(0, top)
    },
    onRelease = { picker -> picker.setOnValueChangedListener(null) },
    modifier = modifier.width(NUMBER_PICKER_WIDTH).height(NUMBER_PICKER_HEIGHT),
  )
}

/**
 * Recolors the number field and the steppers of [NumberPicker].
 *
 * The wheel itself cannot be reached from here - the color of the values around
 * the selected one is set once from the theme and has no setter - which is why the
 * widget is built with a theme that already draws them light.
 */
private fun tintPicker(picker: NumberPicker, textColor: Int) {
  fun walk(view: View) {
    when (view) {
      is EditText -> {
        view.setTextColor(textColor)
        // The field paints its own selection box; the wheel draws the numbers.
        view.background = null
        view.isFocusable = false
        view.isFocusableInTouchMode = false
      }

      is ImageView -> view.imageTintList = ColorStateList.valueOf(textColor)
      is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i))
      else -> Unit
    }
  }
  walk(picker)
}

// --- Previews ---------------------------------------------------------------

/**
 * A stand-in quantization payload for the previews: two 8-bit tables with the
 * q92 values the batch in DQT.md was made with, so the tiles and sections show
 * realistic counts without needing a JPEG fixture.
 */
private fun previewTables(): List<QuantTable> {
  val luma = intArrayOf(
    3, 2, 2, 2, 2, 2, 3, 2, 2, 2, 3, 3, 3, 3, 4, 6, 4, 4, 4, 4, 4, 8, 6, 6, 5,
    6, 9, 8, 10, 10, 9, 8, 9, 9, 10, 12, 15, 12, 10, 11, 14, 11, 9, 9, 13, 17,
    13, 14, 15, 16, 16, 17, 16, 10, 12, 18, 19, 18, 16, 19, 15, 16, 16, 16,
  )
  val chromaHead = intArrayOf(3, 3, 3, 4, 3, 4, 8, 4, 4, 8, 16, 11, 9, 11)
  val chroma = IntArray(64) { if (it < chromaHead.size) chromaHead[it] else 16 }
  val data = ByteArray(130)
  data[0] = 0x00
  for (i in 0 until 64) data[1 + i] = luma[i].toByte()
  data[65] = 0x01
  for (i in 0 until 64) data[66 + i] = chroma[i].toByte()
  val structure = JpegStructure(
    dqt = listOf(ByteSpan(0, 65), ByteSpan(65, 130)),
    dht = emptyList(),
    scan = emptyList(),
    rst = emptyList(),
    scans = emptyList(),
    geometry = emptyList(),
    frame = null,
    fatal = emptyList(),
    metadata = emptyList(),
  )
  return QuantTables.parse(data, structure)
}

@Composable
private fun DqtScreenPreviewShell(edits: Map<Int, Int>) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      DqtScreenContent(
        // The stand-in photo other screens preview with: without it the layout
        // falls back to the no-room-below arrangement, which is not what a real
        // photo looks like.
        preview = previewImageBitmap(),
        tables = previewTables(),
        edits = edits,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onPreviewBounds = {},
        onBack = {},
        onInfo = {},
        onOpenFamily = {},
        onNext = {},
        onResetAll = {},
        onUndo = {},
        onFlip = {},
        canUndo = false,
        canFlip = false,
      )
    }
  }
}

@Preview(name = "DQT", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtScreenPreview() {
  DqtScreenPreviewShell(edits = emptyMap())
}

@Preview(name = "DQT - edited", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtScreenEditedPreview() {
  // Two edited quantizers, so the tile badges and the next label show.
  DqtScreenPreviewShell(edits = mapOf(9 to 3, 41 to 130))
}

@Preview(name = "DQT - small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun DqtScreenSmallPreview() {
  DqtScreenPreviewShell(edits = emptyMap())
}

/**
 * The bits panel where it really sits: over the bottom of the photo, on a family
 * with every scale. [fraction] stands in for the drag position, so the stops can
 * be checked without a finger.
 */
@Composable
private fun DqtBitsPanelPreviewShell(
  family: DqtFamily,
  edits: Map<Int, Int>,
  fraction: Float = DQT_PANEL_MAX_FRACTION,
) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize()) {
        Image(
          bitmap = previewImageBitmap(),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        // Bottom-aligned, the way AnimatedVisibility places it on the screen.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
          DqtBitsPanel(
            family = family,
            tables = previewTables(),
            edits = edits,
            onValueChange = { _, _ -> },
            onResetEntry = {},
            onResetFamily = {},
            onFamilyChange = {},
            onClose = {},
            onBounds = {},
            initialFraction = fraction,
          )
        }
      }
    }
  }
}

@Preview(name = "DQT panel - checkered", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelCheckeredPreview() {
  // Offsets are into the stand-in payload: luma coefficient 4 (offset 5) set to
  // 130, coefficient 12 (offset 13) zeroed, so both a changed row and the
  // deleted-frequency color show.
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.CHECKERED, chroma = false,),
    edits = mapOf(5 to 130, 13 to 0),
  )
}

@Preview(name = "DQT panel - nothing changed", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelUnchangedPreview() {
  // Chroma DC (table 1, coefficient 0) - the single-row case, and nothing edited
  // yet, so "Reset this pattern" shows disabled.
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.DC, chroma = true,),
    edits = emptyMap(),
  )
}

@Preview(name = "DQT panel - short stop", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelShortPreview() {
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.VERTICAL, chroma = false,),
    // Luma coefficient 5 (2,0) - in the vertical family, so the row shows edited.
    edits = mapOf(6 to 34),
    fraction = DQT_PANEL_SHORT_FRACTION,
  )
}

@Preview(name = "DQT panel - max height", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelMaxPreview() {
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.HORIZONTAL, chroma = true,),
    // Chroma coefficient 2 (0,1) - the first horizontal row of the chroma table.
    edits = mapOf(68 to 96),
    fraction = DQT_PANEL_MAX_FRACTION,
  )
}

@Preview(name = "DQT panel - small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun DqtBitsPanelSmallPreview() {
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.HORIZONTAL, chroma = false,),
    edits = mapOf(3 to 19),
  )
}

@Preview(name = "DQT panel - picker changed", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelPickerPreview() {
  // Luma DC (table 0, coefficient 0, offset 1) raised from 3 to 8: the picker
  // should show its changed color and "Reset this pattern" should light up.
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.DC, chroma = false,),
    edits = mapOf(1 to 8),
  )
}

/**
 * The quantizer rows on their own, where the two controls can be compared at a
 * size that shows their colors: the picker at rest, changed and zeroed - the
 * three states every row shares - each with the faded neighbors the wheel draws
 * around the selected value; then slider rows, which are the only ones that carry
 * a reset button.
 */
@Preview(name = "DQT quantizer rows", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtQuantizerRowsPreview() {
  val luma = previewTables().first()
  val dc = luma.entries.first()
  val cells = luma.entriesFor(QuantAxis.CHECKERED).filter { maxOf(it.u, it.v) == 2 }
  val band = cells.first()
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        QuantizerRowLabel(stringResource(R.string.dqt_section_dc))
        QuantizerRow(dc, value = dc.value, edited = false, onValueChange = {}, onReset = {})
        QuantizerRow(dc, value = dc.value + 2, edited = true, onValueChange = {}, onReset = {})
        QuantizerRow(dc, value = 0, edited = true, onValueChange = {}, onReset = {})

        QuantizerRowLabel(
          stringResource(
            R.string.dqt_section_cells,
            stringResource(scaleWordRes(2)),
            sizeText(cells),
          )
        )
        QuantizerRow(band, value = band.value, edited = false, onValueChange = {}, onReset = {})
        QuantizerRow(band, value = band.value + 40, edited = true, onValueChange = {}, onReset = {})
      }
    }
  }
}

/** The section heading the panel puts above a group of rows. */
@Composable
private fun QuantizerRowLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
  )
}
