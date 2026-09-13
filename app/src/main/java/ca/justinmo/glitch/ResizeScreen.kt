package ca.justinmo.glitch

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * Default / common export size (px). Sizes above it stay available up to
 * half of the original image's long edge.
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
 * Screen 2: shrink the JPEG first (faster glitching) or keep it untouched.
 */
@Composable
internal fun ResizeScreen(
  bytes: ByteArray,
  resolution: Pair<Int, Int>,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var preview by remember(bytes) { mutableStateOf<Bitmap?>(null) }
  var currentResolution by remember(bytes) { mutableStateOf(resolution) }
  var cropForStories by remember(bytes) { mutableStateOf(true) }
  var targetSize by remember(bytes) { mutableIntStateOf(DEFAULT_RESIZE_PX) }
  var working by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // Pinch-to-zoom state. The gesture itself is recognised by the content (it
  // owns the modifier that needs the layout), so this is only the zoom/pan it
  // reports back.
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }

  LaunchedEffect(bytes) {
    // One cached sample large enough for the largest selectable target
    // (half the original long edge), capped for memory.
    val longEdge = max(resolution.first, resolution.second)
    val masterDim = max(RESIZE_MAX_DIM, longEdge / 2)
      .coerceAtMost(MASTER_PREVIEW_CAP_PX)
    preview = withContext(Dispatchers.Default) { decodeSampled(bytes, maxDim = masterDim) }
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
      result.onSuccess { onContinue(it, scale, offset) }
        .onFailure { error = context.getString(R.string.resize_error, it.message) }
    }
  }

  // Decoding, the zoom gesture and the encode stay here; the whole visible
  // layout is drawn by the stateless ResizeScreenContent below, which is what
  // the @Preview functions render.
  ResizeScreenContent(
    preview = displayedPreview?.asImageBitmap(),
    resolution = currentResolution,
    targetSize = targetSize,
    cropForStories = cropForStories,
    working = working,
    zoomScale = scale,
    zoomOffset = offset,
    onTransform = { zoomChange, panChange ->
      scale = (scale * zoomChange).coerceIn(0.5f, 5f)
      offset += panChange
    },
    onTargetSizeChange = { targetSize = it },
    onCropChange = { cropForStories = it },
    onBack = onBack,
    onSkip = { onContinue(bytes, scale, offset) },
    onResize = { resize(targetSize, if (cropForStories) STORIES_ASPECT else null) },
  )
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
      BoxWithConstraints(
        modifier = previewModifier,
        contentAlignment = Alignment.TopCenter,
      ) {
        val bmp = preview
        val photoHeight = maxWidth / STORIES_ASPECT
        val isCropped = photoHeight > maxHeight

        if (bmp != null) {
          Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.resize_source_desc),
            modifier = Modifier
              .fillMaxSize()
              .transformable(state = state)
              .graphicsLayer(
                scaleX = zoomScale,
                scaleY = zoomScale,
                translationX = zoomOffset.x,
                translationY = zoomOffset.y,
              ),
            contentScale = if (isCropped) ContentScale.Crop else ContentScale.Fit,
            alignment = Alignment.TopCenter,
            filterQuality = FilterQuality.None,
          )
        } else {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }

        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(title = stringResource(R.string.resize_title), onBack = onBack)
        }

        // Tall window case
        if (maxHeight >= TALL_WINDOW_HEIGHT && !isCropped) {
          // "Rest of controls" -> Aligned to the bottom of the preview
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(photoHeight)
              .align(Alignment.TopCenter),
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

          // "Last row of buttons" -> Below the preview (centered in gap)
          val resultingDim = calculateResultingDim(resolution, targetSize, cropForStories)
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(maxHeight - photoHeight)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
          ) {
            ResizeControls(
              working = working,
              onSkip = onSkip,
              onResize = onResize,
              resultingDim = resultingDim,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val photoHeight = maxWidth / STORIES_ASPECT
        val isCropped = photoHeight > maxHeight

        // Short window or cropped: keep sliders at the bottom edge.
        if (maxHeight < TALL_WINDOW_HEIGHT || isCropped) {
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
        val photoHeight = maxWidth / STORIES_ASPECT
        val isCropped = photoHeight > maxHeight

        // Short window or cropped: keep buttons in their own row at the bottom.
        if (maxHeight < TALL_WINDOW_HEIGHT || isCropped) {
          val resultingDim = calculateResultingDim(resolution, targetSize, cropForStories)
          ResizeControls(
            working = working,
            onSkip = onSkip,
            onResize = onResize,
            resultingDim = resultingDim,
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
    val widthPx = res?.first
    val maxTargetPx = (widthPx?.let { half -> max(DEFAULT_RESIZE_PX, half / 2) }
      ?: DEFAULT_RESIZE_PX).let { (it / 8) * 8 }
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
      modifier = Modifier.fillMaxWidth(),
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
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
        ) {
          SliderDefaults.Track(
            sliderState = sliderState,
            colors = SliderDefaults.colors()
          )
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
        text = if (working) {
          stringResource(R.string.resize_progress)
        } else {
          if (resultingDim != null) {
            stringResource(R.string.resize_button_to, resultingDim.first, resultingDim.second)
          } else {
            stringResource(R.string.resize_button)
          }
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
private fun ResizeScreenPreviewShell(working: Boolean = false) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      ResizeScreenContent(
        preview = ImageBitmap.imageResource(R.drawable.example),
        resolution = 4032 to 3024,
        targetSize = 1080,
        cropForStories = true,
        working = working,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onTransform = { _, _ -> },
        onTargetSizeChange = {},
        onCropChange = {},
        onBack = {},
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
