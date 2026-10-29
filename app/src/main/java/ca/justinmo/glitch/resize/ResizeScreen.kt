package ca.justinmo.glitch.resize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.NormalizedCrop
import ca.justinmo.glitch.PreviewImage
import ca.justinmo.glitch.R
import ca.justinmo.glitch.RESIZE_MAX_DIM
import ca.justinmo.glitch.clampedCropPan
import ca.justinmo.glitch.cropPixelSize
import ca.justinmo.glitch.cropWindow
import ca.justinmo.glitch.decodeSampled
import ca.justinmo.glitch.previewAtTarget
import ca.justinmo.glitch.resizeJpeg
import ca.justinmo.glitch.ui.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.ui.PreviewWithControls
import ca.justinmo.glitch.ui.STACKED_CONTROLS_HEIGHT
import ca.justinmo.glitch.ui.TitleBar
import ca.justinmo.glitch.ui.createShadow
import ca.justinmo.glitch.ui.theme.GlitchTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Smallest output size offered on the long edge (px). */
private const val MIN_RESIZE_PX = 128

/**
 * Memory cap for the decoded preview sample (long edge, px). Big enough to
 * faithfully preview every target up to the cap, small enough to keep the
 * single decode + per-change scaling fast.
 */
private const val MASTER_PREVIEW_CAP_PX = 2400

/**
 * Default / common export size (px). Sizes above it stay available up to the
 * original image's own width, so the untouched size can be picked.
 */
private const val DEFAULT_RESIZE_PX = 1080

/**
 * Coarse snapping step (px) for the size seek bar. Multiples of 8; the grid
 * is anchored at [DEFAULT_RESIZE_PX] so 1080 is always one of the stops.
 */
private const val RESIZE_STEP_PX = 64

/**
 * The discrete output sizes offered by the seek bar: the smallest size,
 * [anchor] plus/minus whole [step]s within the range, and the largest size.
 * Sorted ascending, no duplicates.
 */
private fun allowedResizeSizes(min: Int, max: Int, anchor: Int, step: Int): List<Int> {
  val sizes = sortedSetOf<Int>()
  sizes.add(min)
  var up = anchor
  while (up <= max) {
    sizes.add(up)
    up += step
  }
  var down = anchor - step
  while (down >= min) {
    sizes.add(down)
    down -= step
  }
  sizes.add(max)
  return sizes.toList()
}

/** The frame the chosen crop has: the mode's own, or the picture's for No crop. */
private fun cropAspectFor(crop: CropMode, resolution: Pair<Int, Int>?): Float {
  crop.aspect?.let { return it }
  val (width, height) = resolution ?: return 1f
  if (width <= 0 || height <= 0) return 1f
  return width.toFloat() / height.toFloat()
}

/**
 * True when the chosen size and crop leave the file exactly as it is: the full width, and
 * a window that still holds the whole picture. The button then reads "Keep <w> x <h>" and
 * the original bytes are handed on instead of being re-encoded at the same size.
 *
 * The window is what decides it, not the mode: No crop that has been pinched in is a crop
 * like any other, and a 9:16 window on a picture that is already 9:16 is not one at all.
 */
private fun keepsSource(
  resolution: Pair<Int, Int>?,
  targetSize: Int,
  crop: NormalizedCrop,
): Boolean {
  val (width, _) = resolution ?: return false
  if (targetSize < width) return false
  return crop.isWholePicture
}

/**
 * Which crop the screen starts the user off with, for a picture of this size.
 *
 * The answer follows what the trim would cost, because the crop is offered before it is
 * understood and a default that removes most of the photograph the moment it is opened is
 * not a default. On a landscape picture a 9:16 crop keeps a vertical slice and throws away
 * more than half of a 4:3 frame, so a landscape picture arrives whole and No crop is
 * selected. On a portrait picture 9:16 is a trim - the sides of a 3:4 photo, the top and
 * bottom of one already taller than 9:16 - and it is the frame stories take, so that is
 * the default there. A square picture starts on 1:1 for the same reason: it is the frame
 * that keeps it whole, where 9:16 would cut the sides off a picture that is already square.
 *
 * Nothing here is a decision: the buttons write the user's own answer into the holder, and
 * this only stands in for it until they make one - which is also why it can be worked out
 * again for the next photo.
 */
private fun defaultCropFor(resolution: Pair<Int, Int>?): CropMode {
  val (width, height) = resolution ?: return CropMode.None
  return when {
    width > height -> CropMode.None
    width < height -> CropMode.Stories
    else -> CropMode.Square
  }
}

/**
 * Screen 2: crop the JPEG and shrink it (faster glitching), or keep it untouched.
 */
@Composable
internal fun ResizeScreen(
  bytes: ByteArray,
  resolution: Pair<Int, Int>,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
  /**
   * Defaulted so the screen still stands on its own - the previews below render it, and a
   * caller with no flow behind it gets a screen that works for as long as it is composed,
   * which is what `remember` gave it before.
   *
   * The flow's shared decoded preview. This screen decodes a *master* of the file rather than
   * the file at the size it draws it, so being handed the last step's decode is what keeps a
   * step back into this screen from going blank while that master is made again.
   */
  previewImage: PreviewImage = remember { PreviewImage() },
  state: ResizeState = remember { ResizeState() },
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var preview by remember(bytes) { mutableStateOf(previewImage.showable(bytes, resolution)) }
  var currentResolution by remember(bytes) { mutableStateOf(resolution) }
  var working by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // The size, the crop and the explanation are not this screen's own any more. They
  // live in [state] because walking back to this step is how you change your mind
  // about the size: the screen that came before it was left for that reason, and
  // arriving here again has to meet the size and the crop you left rather than a
  // step reset to its defaults. Only a *choice* is remembered out there, though -
  // [ResizeState.targetSize] stays null until the seek bar is touched,
  // [ResizeState.crop] until one of the crop buttons is, and the window in
  // [ResizeState.cropZoom] / [ResizeState.cropPan] likewise - so the defaults they
  // stand in for belong here, where the sizes being chosen between are and where the
  // photo's own shape is known.
  val targetSize = state.targetSize ?: DEFAULT_RESIZE_PX
  val crop = state.crop ?: defaultCropFor(resolution)
  val cropAspect = cropAspectFor(crop, resolution)
  val sourceAspect = resolution.first.toFloat() / resolution.second.toFloat()
  val cropRect = cropWindow(sourceAspect, cropAspect, state.cropZoom, state.cropPan)

  LaunchedEffect(bytes) {
    // One cached sample large enough for the largest selectable target (the
    // original size), capped for memory.
    val longEdge = max(resolution.first, resolution.second)
    val masterDim = max(RESIZE_MAX_DIM, longEdge / 2)
      .coerceAtMost(MASTER_PREVIEW_CAP_PX)
    if (previewImage.covers(bytes, masterDim)) {
      previewImage.showable(bytes, resolution)?.let { preview = it }
      return@LaunchedEffect
    }
    val decoded = withContext(Dispatchers.Default) { decodeSampled(bytes, maxDim = masterDim) }
    // A decode that fails keeps what is on screen: the spinner is for a picture that has not
    // arrived, and a blank is not a better answer for one that has.
    if (decoded != null) {
      previewImage.put(bytes, masterDim, decoded)
      preview = decoded
    }
  }

  fun resize(maxDim: Int, window: NormalizedCrop) {
    if (working) return
    working = true
    error = null
    scope.launch {
      val result = runCatching {
        withContext(Dispatchers.Default) { resizeJpeg(bytes, maxDim, crop = window) }
      }
      val resized = result.getOrNull()
      if (resized == null) {
        working = false
        error = context.getString(R.string.resize_error, result.exceptionOrNull()?.message)
        return@launch
      }
      // The next step's first frame: the same window the export kept, cut out of the
      // master and scaled by the resampler the export uses. What this screen shows is the
      // whole photograph with the frame drawn over it, which is the wrong shape to hand
      // on; this is the export previewed rather than the editor's view of the source.
      // A preview that cannot be made is not an export failure - the next step decodes.
      val exportPreview = runCatching {
        withContext(Dispatchers.Default) {
          preview?.let { previewAtTarget(it, maxDim, crop = window) }
        }
      }.getOrNull()
      // The cap it is filed under is its own long edge, which is what the cap means: this
      // frame carries that much detail, so a step wanting less needs no decode of its own.
      exportPreview?.let { previewImage.put(resized, maxOf(it.width, it.height), it) }
      working = false
      onContinue(resized, 1f, Offset.Zero)
    }
  }

  // Back closes the explanation before it leaves the screen. The explanation is
  // one of the things the holder owns, so closing it on the way out is also what
  // keeps it closed when the step is walked back into.
  BackHandler(enabled = state.showInfo) { state.showInfo = false }

  Box(modifier = Modifier.fillMaxSize()) {
    ResizeScreenContent(
      // The whole photograph, not the crop: this preview is the editor, and the window
      // drawn over it is what the export will keep.
      preview = preview?.asImageBitmap(),
      resolution = currentResolution,
      targetSize = targetSize,
      crop = crop,
      cropZoom = state.cropZoom,
      cropPan = state.cropPan,
      working = working,
      onCropTransform = { zoom, pan ->
        state.cropZoom = zoom
        state.cropPan = clampedCropPan(pan, sourceAspect, cropAspect, zoom)
      },
      onTargetSizeChange = { state.targetSize = it },
      onCropChange = { chosen ->
        // Only a real change starts the window over: tapping the frame that is already
        // selected must not throw away the crop that was just aimed.
        if (state.crop != chosen) {
          state.crop = chosen
          state.resetCropView()
        }
      },
      onBack = onBack,
      onInfo = { state.showInfo = true },
      // Skip hands the original on, so the crop just aimed does not describe the file
      // that leaves: the framing after this step starts fitted rather than at whatever
      // the editor was left holding.
      onSkip = { onContinue(bytes, 1f, Offset.Zero) },
      onResize = {
        if (keepsSource(resolution, targetSize, cropRect)) {
          // The button promised to keep the file: pass the original bytes on
          // rather than re-encoding an identical frame.
          onContinue(bytes, 1f, Offset.Zero)
        } else {
          resize(targetSize, cropRect)
        }
      },
    )

    // Drawn last, over the screen, so the explanation costs the choices underneath
    // it nothing: the size and the crop it is covering up are the same in the
    // holder when it closes again.
    if (state.showInfo) {
      ResizeInfoScreen(onClose = { state.showInfo = false })
    }
  }
}

/**
 * Stateless body of [ResizeScreen]: the crop editor over the decoded JPEG, with the size and
 * crop controls beneath it. Decoding, cropping and encoding live in [ResizeScreen], so this
 * part can be rendered - and previewed - on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResizeScreenContent(
  preview: ImageBitmap?,
  resolution: Pair<Int, Int>?,
  targetSize: Int,
  crop: CropMode,
  cropZoom: Float,
  cropPan: Offset,
  working: Boolean,
  onCropTransform: (zoom: Float, pan: Offset) -> Unit,
  onTargetSizeChange: (Int) -> Unit,
  onCropChange: (CropMode) -> Unit,
  onBack: () -> Unit,
  onInfo: () -> Unit,
  onSkip: () -> Unit,
  onResize: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // The window, the size it exports at and whether the file survives untouched are all
  // asked here once and handed to whichever arrangement is drawn, so the picture and the
  // button cannot disagree about what is about to happen.
  val cropAspect = cropAspectFor(crop, resolution)
  val sourceAspect = resolution?.let { it.first.toFloat() / it.second.toFloat() } ?: 1f
  val cropRect = cropWindow(sourceAspect, cropAspect, cropZoom, cropPan)
  val resultingDim = calculateResultingDim(resolution, targetSize, cropRect)
  val keeping = keepsSource(resolution, targetSize, cropRect)

  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      // The pane is the crop editor: the photograph with the export's window drawn over
      // it, and everything the window leaves out dimmed. It is laid out in the trailing
      // lambda because the editor hands back a BoxWithConstraintsScope - this screen's
      // furniture is measured against the frame the pane settled on rather than against the
      // bitmap or a 9:16 frame of its own imagining, so it needs the pane's own constraints.
      CropPreview(
        bitmap = preview,
        sourceSize = resolution,
        cropAspect = cropAspect,
        zoom = cropZoom,
        pan = cropPan,
        onTransform = onCropTransform,
        enabled = !working,
        description = stringResource(R.string.resize_source_desc),
        modifier = previewModifier,
        // Shown while there is no bitmap yet: the same spinner, centred in the pane, that
        // this screen drew for itself before.
        placeholder = {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        },
      ) { frame ->
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(
            title = stringResource(R.string.resize_title),
            onBack = onBack,
            onInfo = onInfo,
          )
        }

        // A frame that leaves the bottom of the screen room for them gets the controls
        // there: the window is centred in what is left above that room (the frame moved it
        // down to [PreviewFrame.top]) and the slider, the crop buttons and the two action
        // buttons are stacked in the bottom portion. This is the arrangement a landscape
        // photo lands in, and the one it needed: before it, the panel rode the picture's own
        // bottom edge - which on a landscape photo is halfway up the screen - so the
        // controls were drawn across the middle of the picture with an empty band under it.
        if (frame.stacksControls) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .height(STACKED_CONTROLS_HEIGHT)
              .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            ResizeOverlayContent(
              resolution = resolution,
              targetSize = targetSize,
              crop = crop,
              enabled = !working,
              onTargetSizeChange = onTargetSizeChange,
              onCropChange = onCropChange,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
            ResizeControls(
              working = working,
              onSkip = onSkip,
              onResize = onResize,
              resultingDim = resultingDim,
              keeping = keeping,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        } else if (frame.hasRoomBelow) {
          // Otherwise the photo owns the window and the controls ride it, off the frame the
          // pane measured rather than anything worked out here. The frame is the one answer
          // to where the photo's edges are, how much room is under it, and whether the
          // buttons can go below it at all - and the crop window is drawn inside it.
          Box(
            modifier = Modifier.fillMaxWidth().height(frame.photoHeight),
            contentAlignment = Alignment.BottomCenter
          ) {
             ResizeOverlayContent(
               resolution = resolution,
               targetSize = targetSize,
               crop = crop,
               enabled = !working,
               onTargetSizeChange = onTargetSizeChange,
               onCropChange = onCropChange,
               modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)
             )
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(frame.roomBelow)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
          ) {
            ResizeControls(
              working = working,
              onSkip = onSkip,
              onResize = onResize,
              resultingDim = resultingDim,
              keeping = keeping,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The pane's own frame asked again outside it, off the same numbers: this slot
        // sits below the pane and never sees what the pane settled on.
        val pane = cropPreviewFrame(resolution, maxWidth, maxHeight)

        // Short window, or a photo that fills the pane: keep the sliders at the bottom edge.
        if (!pane.hasRoomBelow) {
          ResizeOverlayContent(
            resolution = resolution,
            targetSize = targetSize,
            crop = crop,
            enabled = !working,
            onTargetSizeChange = onTargetSizeChange,
            onCropChange = onCropChange,
            modifier = Modifier
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp)
          )
        }
      }
    },
    controls = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pane = cropPreviewFrame(resolution, maxWidth, maxHeight)

        // Short window, or a photo that fills the pane: keep the buttons in their own row.
        if (!pane.hasRoomBelow) {
          ResizeControls(
            working = working,
            onSkip = onSkip,
            onResize = onResize,
            resultingDim = resultingDim,
            keeping = keeping,
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp, bottom = 24.dp)
          )
        }
      }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResizeOverlayContent(
  resolution: Pair<Int, Int>?,
  targetSize: Int,
  crop: CropMode,
  enabled: Boolean,
  onTargetSizeChange: (Int) -> Unit,
  onCropChange: (CropMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    val res = resolution
    // The seek bar spans every size from the smallest up to the original width,
    // so the untouched size is reachable; the stops stay 64px apart on a grid
    // anchored at DEFAULT_RESIZE_PX and the exact width is always one of them.
    val maxTargetPx = (res?.first ?: DEFAULT_RESIZE_PX).coerceAtLeast(MIN_RESIZE_PX)
    val allowedSizes = remember(maxTargetPx) {
      allowedResizeSizes(MIN_RESIZE_PX, maxTargetPx, DEFAULT_RESIZE_PX, RESIZE_STEP_PX)
    }

    Text(
      text = stringResource(R.string.resize_preset_label, targetSize),
      modifier = Modifier.fillMaxWidth(),
      style = MaterialTheme.typography.titleMedium.copy(shadow = createShadow()),
      textAlign = TextAlign.Center,
    )
    Slider(
      value = targetSize.toFloat(),
      enabled = enabled,
      onValueChange = { v ->
        onTargetSizeChange(allowedSizes.minByOrNull { abs(it - v.roundToInt()) } ?: targetSize)
      },
      valueRange = MIN_RESIZE_PX.toFloat()..maxTargetPx.toFloat(),
      modifier = Modifier.fillMaxWidth()
        .systemGestureExclusion { layoutCoords ->
          // Exclude the entire bounding box of the slider plus extra padding if needed
          Rect(0f, 0f, layoutCoords.size.width.toFloat(), layoutCoords.size.height.toFloat())
        },
      thumb = {
        Box(
          modifier = Modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(size = 4.dp))
            .size(width = 8.dp, height = 48.dp)
            .clip(RoundedCornerShape(size = 4.dp))
            .background(MaterialTheme.colorScheme.primary)
        )
      },
      track = { sliderState ->
        val trackColors = SliderDefaults.colors()
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
        ) {
          SliderDefaults.Track(
            sliderState = sliderState,
            colors = trackColors
          )

          // Dot marking the size most exports use. Material measures the track as
          // (slider width - thumb width) and places it at thumb width / 2, so the
          // track spans the thumb's travel exactly and the value maps straight
          // onto this Box: x = fraction * width. Drawn in the slider's own tick
          // color for whichever half of the track it lands in.
          if (maxTargetPx > DEFAULT_RESIZE_PX) {
            val dotFraction = (DEFAULT_RESIZE_PX - MIN_RESIZE_PX).toFloat() /
              (maxTargetPx - MIN_RESIZE_PX).toFloat()
            val dotColor = if (targetSize >= DEFAULT_RESIZE_PX) {
              trackColors.activeTickColor
            } else {
              trackColors.inactiveTickColor
            }
            Canvas(modifier = Modifier.matchParentSize()) {
              drawCircle(
                color = dotColor,
                radius = 2.dp.toPx(),
                center = Offset(size.width * dotFraction, size.height / 2f),
              )
            }
          }
        }
      }
    )

    // Always offered, because there is always a frame to decide between: even a picture
    // that is already 9:16 can still be squared off. The row it replaces hid itself on a
    // 9:16 photo, back when the only states were "crop" and "no crop" and one of them did
    // nothing there.
    if (res != null) {
      Spacer(Modifier.height(16.dp))
      CropModeRow(current = crop, enabled = enabled, onCropChange = onCropChange)
    }
  }
}

/**
 * The three frames a picture can be cropped to, as one row of buttons.
 *
 * No crop keeps the photo's own shape, 1:1 trims it to a centred square and 9:16 to the
 * frame stories take. The frame in effect is filled with the primary colour and reported
 * as selected to a screen reader, so which frame the Resize button is about to export can
 * be read without pressing anything.
 *
 * A row rather than the switch it replaced: a switch has two positions, and there are
 * three answers now.
 */
@Composable
private fun CropModeRow(
  current: CropMode,
  enabled: Boolean = true,
  onCropChange: (CropMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    CropMode.entries.forEach { mode ->
      val chosen = mode == current
      Button(
        onClick = { onCropChange(mode) },
        enabled = enabled,
        modifier = Modifier
          .weight(1f)
          .height(44.dp)
          .semantics { selected = chosen },
        colors = ButtonDefaults.buttonColors(
          containerColor = if (chosen) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
          },
          contentColor = if (chosen) {
            MaterialTheme.colorScheme.onPrimary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        ),
        elevation = ButtonDefaults.buttonElevation(
          defaultElevation = if (chosen) 4.dp else 2.dp,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp),
      ) {
        Text(
          text = stringResource(mode.labelRes),
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
        )
      }
    }
  }
}

/** The label on each crop button. */
private val CropMode.labelRes: Int
  get() = when (this) {
    CropMode.None -> R.string.resize_crop_none
    CropMode.Square -> R.string.resize_crop_square
    CropMode.Stories -> R.string.resize_crop_stories
  }

@Composable
private fun ResizeControls(
  working: Boolean,
  onSkip: () -> Unit,
  onResize: () -> Unit,
  resultingDim: Pair<Int, Int>?,
  keeping: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(
      onClick = onSkip,
      modifier = Modifier.height(56.dp),
      enabled = !working,
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
      ),
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
      Text(text = stringResource(R.string.resize_skip))
    }

    Button(
      onClick = onResize,
      modifier = Modifier.weight(1f).height(56.dp),
      enabled = !working,
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
      Text(
        text = when {
          working -> stringResource(R.string.resize_progress)
          // Full width with nothing to crop: the button keeps the file as it is,
          // so it says so - the numbers are the original size.
          keeping && resultingDim != null ->
            stringResource(R.string.resize_button_keep, resultingDim.first, resultingDim.second)
          resultingDim != null ->
            stringResource(R.string.resize_button_to, resultingDim.first, resultingDim.second)
          else -> stringResource(R.string.resize_button)
        },
      )
    }
  }
}

private fun calculateResultingDim(
  res: Pair<Int, Int>?,
  targetSize: Int,
  crop: NormalizedCrop,
): Pair<Int, Int>? {
  if (res == null) return null
  // The window's own pixel size, then the width cap: the height follows the window's
  // aspect rather than one worked out again, so the button's numbers are the export's.
  val (cropWidth, cropHeight) = cropPixelSize(res, crop)
  val finalWidth = min(cropWidth, targetSize)
  val finalHeight = (finalWidth.toFloat() * cropHeight / cropWidth).roundToInt()
    .coerceAtLeast(1)
  return finalWidth to finalHeight
}

// --- Previews ---------------------------------------------------------------

@Composable
private fun ResizeScreenPreviewShell(
  working: Boolean = false,
  targetSize: Int = 1080,
  crop: CropMode = CropMode.Stories,
  cropZoom: Float = 1f,
  cropPan: Offset = Offset.Zero,
) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      ResizeScreenContent(
        preview = previewImageBitmap(),
        resolution = 4032 to 3024,
        targetSize = targetSize,
        crop = crop,
        cropZoom = cropZoom,
        cropPan = cropPan,
        working = working,
        onCropTransform = { _, _ -> },
        onTargetSizeChange = {},
        onCropChange = {},
        onBack = {},
        onInfo = {},
        onSkip = {},
        onResize = {},
      )
    }
  }
}

@Preview(name = "Resize", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun ResizeScreenPreview() {
  ResizeScreenPreviewShell(working = false)
}

@Preview(name = "Resize · working", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun ResizeScreenWorkingPreview() {
  ResizeScreenPreviewShell(working = true)
}

@Preview(name = "Resize · small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun ResizeScreenSmallScreenPreview() {
  ResizeScreenPreviewShell(working = false)
}

/** The square crop on a landscape photo: a centred 1:1 export. */
@Preview(name = "Resize · 1:1", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun ResizeScreenSquarePreview() {
  ResizeScreenPreviewShell(crop = CropMode.Square)
}

/**
 * Top of the seek bar with No crop selected: nothing would change, so the button
 * reads "Keep 4032 × 3024" and hands the original bytes on.
 */
@Preview(name = "Resize · keep original", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun ResizeScreenKeepPreview() {
  ResizeScreenPreviewShell(targetSize = 4032, crop = CropMode.None)
}
