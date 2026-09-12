package ca.justinmo.glitch

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aspect ratio used for Stories crops (9 wide : 16 tall). */
private const val STORIES_ASPECT = 9f / 16f

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
  name: String,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var preview by remember(bytes) { mutableStateOf<Bitmap?>(null) }
  var resolution by remember(bytes) { mutableStateOf<Pair<Int, Int>?>(null) }
  var cropForStories by remember(bytes) { mutableStateOf(false) }
  var targetSize by remember(bytes) { mutableIntStateOf(DEFAULT_RESIZE_PX) }
  var working by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // Pinch-to-zoom state
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  val state = rememberTransformableState { zoomChange, offsetChange, _ ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    offset += offsetChange
  }

  LaunchedEffect(bytes) {
    val dims = withContext(Dispatchers.Default) { imageResolution(bytes) }
    resolution = dims
    // One cached sample large enough for the largest selectable target
    // (half the original long edge), capped for memory.
    val longEdge = dims?.let { max(it.first, it.second) }
    val masterDim = (longEdge?.let { max(RESIZE_MAX_DIM, it / 2) } ?: RESIZE_MAX_DIM)
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

  Box(modifier = Modifier.fillMaxSize()) {
    // Current frame (Layer 0: Background zoomable preview)
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val bmp = displayedPreview
      if (bmp != null) {
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = stringResource(R.string.resize_source_desc),
          modifier = Modifier
            .fillMaxSize()
            .transformable(state = state)
            .graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offset.x,
              translationY = offset.y,
            ),
          contentScale = ContentScale.Fit,
          filterQuality = FilterQuality.None,
        )
      } else {
        CircularProgressIndicator()
      }
    }

    // Controls (Layer 1: Foreground UI)
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      TitleBar(title = stringResource(R.string.resize_title), isAboveMedia = true, onBack = onBack)

      Spacer(modifier = Modifier.weight(1f))

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(top = 16.dp, bottom = 24.dp),
      ) {
        val sizeText = formatBytes(bytes.size)
        val res = resolution
        Text(
          text = if (res != null) {
            stringResource(
              R.string.resize_image_meta,
              name,
              stringResource(R.string.image_resolution, res.first, res.second),
              sizeText,
            )
          } else {
            stringResource(R.string.resize_image_meta_simple, name, sizeText)
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Pick the output size (long edge) with a seek bar: from 128 px up to
        // half of the original long edge (never below 1080 px so it's always
        // selectable). The bar snaps to a coarse grid anchored at 1080 px;
        // every stop is a multiple of 8.
        val longEdgePx = res?.let { max(it.first, it.second) }
        val maxTargetPx = (longEdgePx?.let { half -> max(DEFAULT_RESIZE_PX, half / 2) }
          ?: DEFAULT_RESIZE_PX).let { (it / 8) * 8 }
        val allowedSizes = remember(maxTargetPx) {
          allowedResizeSizes(MIN_RESIZE_PX, maxTargetPx, DEFAULT_RESIZE_PX, RESIZE_STEP_PX)
        }
        Text(
          text = stringResource(R.string.resize_preset_label, targetSize),
          modifier = Modifier.fillMaxWidth(),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
        )
        Slider(
          value = targetSize.toFloat(),
          onValueChange = { v ->
            targetSize = allowedSizes.minByOrNull { abs(it - v.roundToInt()) } ?: targetSize
          },
          valueRange = MIN_RESIZE_PX.toFloat()..maxTargetPx.toFloat(),
          modifier = Modifier.fillMaxWidth(),
        )

        // Crop toggle only makes sense when the photo isn't already 9:16.
        if (res != null && !isStoriesAspect(res.first, res.second)) {
          Spacer(Modifier.height(16.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.resize_crop_option),
                style = MaterialTheme.typography.bodyLarge,
              )
            }
            Switch(
              checked = cropForStories,
              onCheckedChange = { cropForStories = it },
            )
          }
        }
        Spacer(Modifier.height(24.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            onClick = { onContinue(bytes, scale, offset) },
            modifier = Modifier.weight(1f).height(56.dp),
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
            onClick = {
              resize(targetSize, if (cropForStories) STORIES_ASPECT else null)
            },
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = !working,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
          ) {
            Text(
              text = if (working) {
                stringResource(R.string.resize_progress)
              } else {
                stringResource(R.string.resize_button)
              },
            )
          }
        }
        error?.let {
          Spacer(Modifier.height(12.dp))
          Text(it, color = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}
