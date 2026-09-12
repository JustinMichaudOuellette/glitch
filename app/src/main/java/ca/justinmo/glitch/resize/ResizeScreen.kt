package ca.justinmo.glitch.resize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.R
import ca.justinmo.glitch.RESIZE_MAX_DIM
import ca.justinmo.glitch.STORIES_ASPECT
import ca.justinmo.glitch.PreviewImage
import ca.justinmo.glitch.ZoomState
import ca.justinmo.glitch.decodeSampled
import ca.justinmo.glitch.previewAtTarget
import ca.justinmo.glitch.resizeJpeg
import ca.justinmo.glitch.ui.MediaPreview
import ca.justinmo.glitch.ui.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.ui.PreviewWithControls
import ca.justinmo.glitch.ui.STACKED_CONTROLS_HEIGHT
import ca.justinmo.glitch.ui.TitleBar
import ca.justinmo.glitch.ui.createShadow
import ca.justinmo.glitch.ui.previewFrame
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

/** True when [width] x [height] is already a 9:16 frame (within tolerance). */
private fun isStoriesAspect(width: Int, height: Int): Boolean =
  abs(width.toDouble() / height - STORIES_ASPECT.toDouble()) <= 0.01

/**
 * True when the chosen size and crop leave the file exactly as it is: the full
 * width, and no crop left to apply. The button then reads "Keep <w> x <h>" and
 * the original bytes are handed on instead of being re-encoded at the same size.
 */
private fun keepsSource(
  resolution: Pair<Int, Int>?,
  targetSize: Int,
  cropForStories: Boolean,
): Boolean {
  val (width, height) = resolution ?: return false
  if (targetSize < width) return false
  return !cropForStories || isStoriesAspect(width, height)
}

/**
 * Whether the 9:16 crop should start on for a picture of this size.
 *
 * On for anything that is not wider than it is tall, off for a landscape picture, and the
 * difference is not a matter of taste: a crop to 9:16 takes a *trim* off a portrait frame -
 * the sides of a 3:4 photo, the top and bottom of one already taller than 9:16 - while on a
 * landscape photo it keeps a vertical slice and throws the rest away, less than half of a
 * 4:3 picture. A default that removes most of the photograph the moment it is opened is not
 * a default, so a landscape picture arrives whole and the switch is how the crop is asked
 * for.
 *
 * Nothing here is a decision: the switch writes the user's own answer into the holder, and
 * this only stands in for it until they make one - which is also why it can be worked out
 * again for the next photo.
 */
private fun defaultCropFor(resolution: Pair<Int, Int>?): Boolean {
  val (width, height) = resolution ?: return true
  return width <= height
}

/**
 * Screen 2: shrink the JPEG first (faster glitching) or keep it untouched.
 */
@Composable
internal fun ResizeScreen(
  bytes: ByteArray,
  resolution: Pair<Int, Int>,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
  // Both defaulted so the screen still stands on its own - the previews below
  // render it, and a caller that has no flow behind it gets a screen that works
  // for as long as it is composed, which is what `remember` gave it before.
  zoom: ZoomState = remember { ZoomState() },
  /**
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
  // [ResizeState.targetSize] stays null until the seek bar is touched and
  // [ResizeState.cropForStories] until the switch is - so the defaults they stand in
  // for belong here, where the sizes being chosen between are and where the photo's
  // own shape is known.
  val targetSize = state.targetSize ?: DEFAULT_RESIZE_PX
  val cropForStories = state.cropForStories ?: defaultCropFor(resolution)

  // Pinch-to-zoom. The gesture itself is recognised by the content (it owns the
  // modifier that needs the layout), so this is only the zoom/pan it reports back -
  // and the zoom is [zoom] rather than a local, because it is deliberately
  // continuous: the framing left here is the framing the table steps are reached
  // at, and the framing this step is found in when it is walked back to.

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

  val displayedPreview = remember(preview, targetSize, cropForStories) {
    preview?.let { raw ->
      previewAtTarget(raw, targetSize, if (cropForStories) STORIES_ASPECT else null)
    }
  }

  fun resize(maxDim: Int, cropAspect: Float?) {
    if (working) return
    working = true
    error = null
    scope.launch {
      val result = runCatching {
        withContext(Dispatchers.Default) { resizeJpeg(bytes, maxDim, cropAspect) }
      }
      working = false
      result.onSuccess { resized ->
        // The picture this screen has been showing *is* the file being handed on: the resample
        // it was derived with is the one the export uses. So it goes into the shared preview
        // under the new bytes, and the next step draws it on its first frame.
        //
        // Without this, what the next step found was the *master* decode of the file that came
        // in - which is a different shape from the file going out whenever there is a crop, and
        // a frame of the wrong shape is one the next step must not draw, so it drew nothing
        // until its own decode landed. That is the one transition that still went blank, and it
        // is the commonest one there is: crop on, press resize, land on the tables.
        //
        // The cap it is filed under is its own long edge, which is what the cap means: this
        // frame carries that much detail, so a step wanting less needs no decode of its own.
        displayedPreview?.let { previewImage.put(resized, maxOf(it.width, it.height), it) }
        onContinue(resized, zoom.scale, zoom.offset)
      }.onFailure { error = context.getString(R.string.resize_error, it.message) }
    }
  }

  // Decoding, the zoom gesture and the encode stay here; the whole visible
  // layout is drawn by the stateless ResizeScreenContent below, which is what
  // the @Preview functions render.
  // Back closes the explanation before it leaves the screen. The explanation is
  // one of the things the holder owns, so closing it on the way out is also what
  // keeps it closed when the step is walked back into.
  BackHandler(enabled = state.showInfo) { state.showInfo = false }

  Box(modifier = Modifier.fillMaxSize()) {
    ResizeScreenContent(
      preview = displayedPreview?.asImageBitmap(),
      // What the preview is going to measure, which is the file the resize button promises -
      // not the file that came in. The crop is a different shape, and the sliders and buttons
      // are placed off the frame, so laying the screen out for the photo that came in would
      // put them under a picture that is not the one on screen.
      expectedSize = calculateResultingDim(resolution, targetSize, cropForStories),
      resolution = currentResolution,
      targetSize = targetSize,
      cropForStories = cropForStories,
      working = working,
      zoomScale = zoom.scale,
      zoomOffset = zoom.offset,
      onTransform = { zoomChange, panChange ->
        zoom.scale = (zoom.scale * zoomChange).coerceIn(0.5f, 5f)
        zoom.offset += panChange
      },
      onTargetSizeChange = { state.targetSize = it },
      onCropChange = { state.cropForStories = it },
      onBack = onBack,
      onInfo = { state.showInfo = true },
      onSkip = { onContinue(bytes, zoom.scale, zoom.offset) },
      onResize = {
        if (keepsSource(resolution, targetSize, cropForStories)) {
          // The button promised to keep the file: pass the original bytes on
          // rather than re-encoding an identical frame.
          onContinue(bytes, zoom.scale, zoom.offset)
        } else {
          resize(targetSize, if (cropForStories) STORIES_ASPECT else null)
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
 * Stateless body of [ResizeScreen]: the zoomable preview of the decoded JPEG
 * with the size / crop controls beneath it. Decoding, cropping and encoding
 * live in [ResizeScreen], so this part can be rendered - and previewed - on
 * its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResizeScreenContent(
  preview: ImageBitmap?,
  /**
   * What the preview is going to measure - the crop and scale this screen is promising, not
   * the file that came in - for the frames before [preview] has been derived. The furniture is
   * placed off the frame, so this is what keeps it still while the master decode and the
   * resample happen.
   */
  expectedSize: Pair<Int, Int>? = null,
  resolution: Pair<Int, Int>?,
  targetSize: Int,
  cropForStories: Boolean,
  working: Boolean,
  zoomScale: Float,
  zoomOffset: Offset,
  onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
  onTargetSizeChange: (Int) -> Unit,
  onCropChange: (Boolean) -> Unit,
  onBack: () -> Unit,
  onInfo: () -> Unit,
  onSkip: () -> Unit,
  onResize: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state = rememberTransformableState { zoomChange, offsetChange, _ ->
    onTransform(zoomChange, offsetChange)
  }

  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      // The pane is the shared MediaPreview: it owns the one way this app draws a
      // photo, this screen's zoom on it, and the spinner until the decode lands.
      // Anything below is furniture this screen puts *over* that photo, and it is
      // laid out in the trailing lambda because MediaPreview hands back a
      // BoxWithConstraintsScope - this screen's furniture is measured against the
      // frame the pane settled on rather than against the bitmap or a 9:16 frame of
      // its own imagining, so it needs the pane's own constraints.
      MediaPreview(
        bitmap = preview,
        contentDescription = stringResource(R.string.resize_source_desc),
        modifier = previewModifier,
        zoomScale = zoomScale,
        zoomOffset = zoomOffset,
        // The pinch has always been taken by the photo itself here, never by the
        // whole screen, so the title bar and the sliders keep their own drags.
        gesture = Modifier.transformable(state = state),
        // The one screen whose preview is filtered as it is drawn. Everywhere
        // else the decode is capped to roughly the size it is shown at, so
        // nearest-neighbour sampling keeps the block edges of a glitch exact.
        // Here the preview is by definition a *different* size from the one on
        // screen - it is the export's size - and measured at 1272 px it is
        // drawn at 0.85x and at 568 px at 1.9x, so the GPU is always
        // resampling it. Medium, not Low: it adds mipmapping, which is what
        // stops a minified bitmap from turning its own fine texture into a
        // moire. Filtering without mipmaps (Low) did not; a regular grid with
        // a 0.94 autocorrelation was still measured on screen at large
        // targets, while the same pixels in the file show no such structure at
        // all (residual 1.2 levels, best correlation 0.24).
        filterQuality = FilterQuality.Medium,
        expectedSize = expectedSize,
        // Shown while there is no bitmap yet: the same spinner, centred in the
        // pane, that this screen drew for itself before.
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

        // A photo that leaves the bottom of the screen room for them gets the controls
        // there: the picture is centred in what is left above that room (the frame moved it
        // down to [PreviewFrame.top]) and the slider, the crop switch and the two buttons
        // are stacked in the bottom portion. This is the arrangement a landscape photo
        // lands in, and the one it needed: before it, the panel rode the picture's own
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
              cropForStories = cropForStories,
              onTargetSizeChange = onTargetSizeChange,
              onCropChange = onCropChange,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
            ResizeControls(
              working = working,
              onSkip = onSkip,
              onResize = onResize,
              resultingDim = calculateResultingDim(resolution, targetSize, cropForStories),
              keeping = keepsSource(resolution, targetSize, cropForStories),
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        } else if (frame.hasRoomBelow) {
          // Otherwise the photo owns the window and the controls ride it, off the frame the
          // pane measured rather than anything worked out here. What this screen did until
          // now was measure the photo for itself as `maxWidth / STORIES_ASPECT`, which is
          // this photo's height only while the crop is on: with the crop off, the sliders
          // were laid out under a 9:16 photo that was not there and the buttons were given
          // the difference between the pane and that imaginary frame - a negative number of
          // pixels on a landscape photo. The frame is the one answer to all of that.
          Box(
            modifier = Modifier.fillMaxWidth().height(frame.photoHeight),
            contentAlignment = Alignment.BottomCenter
          ) {
             ResizeOverlayContent(
               resolution = resolution,
               targetSize = targetSize,
               cropForStories = cropForStories,
               onTargetSizeChange = onTargetSizeChange,
               onCropChange = onCropChange,
               modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)
             )
          }

          val resultingDim = calculateResultingDim(resolution, targetSize, cropForStories)
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
              keeping = keepsSource(resolution, targetSize, cropForStories),
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The pane's own frame asked again outside it, off the same bitmap and the same
        // arithmetic: this slot sits below the pane and never sees what the pane settled on.
        val pane = previewFrame(preview, maxWidth, maxHeight, expectedSize)

        // Short window, or a photo that fills the pane: keep the sliders at the bottom edge.
        if (!pane.hasRoomBelow) {
          ResizeOverlayContent(
            resolution = resolution,
            targetSize = targetSize,
            cropForStories = cropForStories,
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
        val pane = previewFrame(preview, maxWidth, maxHeight, expectedSize)

        // Short window, or a photo that fills the pane: keep the buttons in their own row.
        if (!pane.hasRoomBelow) {
          val resultingDim = calculateResultingDim(resolution, targetSize, cropForStories)
          ResizeControls(
            working = working,
            onSkip = onSkip,
            onResize = onResize,
            resultingDim = resultingDim,
            keeping = keepsSource(resolution, targetSize, cropForStories),
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
  cropForStories: Boolean,
  onTargetSizeChange: (Int) -> Unit,
  onCropChange: (Boolean) -> Unit,
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

    if (res != null && !isStoriesAspect(res.first, res.second)) {
      Spacer(Modifier.height(16.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.resize_crop_option),
            style = MaterialTheme.typography.bodyLarge.copy(shadow = createShadow()),
          )
        }
        Switch(
          checked = cropForStories,
          onCheckedChange = onCropChange,
          colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
          )
        )
      }
    }
  }
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
  cropForStories: Boolean
): Pair<Int, Int>? {
  if (res == null) return null
  val (w, h) = res
  val aspect = if (cropForStories) STORIES_ASPECT else w.toFloat() / h.toFloat()
  val croppedW = if (cropForStories) {
    val current = w.toFloat() / h
    if (current > aspect) (h * aspect).roundToInt() else w
  } else w
  val finalW = min(croppedW, targetSize)
  val finalH = (finalW / aspect).roundToInt()
  return finalW to finalH
}

// --- Previews ---------------------------------------------------------------

@Composable
private fun ResizeScreenPreviewShell(
  working: Boolean = false,
  targetSize: Int = 1080,
  cropForStories: Boolean = true,
) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      ResizeScreenContent(
        preview = previewImageBitmap(),
        resolution = 4032 to 3024,
        targetSize = targetSize,
        cropForStories = cropForStories,
        working = working,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onTransform = { _, _ -> },
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

/**
 * Top of the seek bar with the crop off: nothing would change, so the button
 * reads "Keep 4032 × 3024" and hands the original bytes on.
 */
@Preview(name = "Resize · keep original", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun ResizeScreenKeepPreview() {
  ResizeScreenPreviewShell(targetSize = 4032, cropForStories = false)
}
