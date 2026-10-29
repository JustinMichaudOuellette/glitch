package ca.justinmo.glitch.resize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.MAX_CROP_ZOOM
import ca.justinmo.glitch.coverCropSize
import ca.justinmo.glitch.ui.PreviewFrame
import ca.justinmo.glitch.ui.previewFrame
import kotlin.math.roundToInt

/**
 * Where the photograph goes in the crop editor's pane: its own frame, fitted whole.
 *
 * This is the one screen whose picture must not cover its pane. What is being chosen is
 * the window the export keeps, and the parts it throws away are shown darkened rather than
 * clipped - so the whole photograph has to be on screen for them to be seen at all. The
 * crop window is then inscribed in this box, which means the furniture laid out around
 * "the photo" is around the photograph rather than around the export.
 */
internal fun cropPreviewFrame(
  sourceSize: Pair<Int, Int>?,
  paneWidth: Dp,
  paneHeight: Dp,
): PreviewFrame {
  val width = sourceSize?.first ?: 0
  val height = sourceSize?.second ?: 0
  if (width <= 0 || height <= 0) {
    return PreviewFrame(
      paneWidth = paneWidth,
      paneHeight = paneHeight,
      photoWidth = paneWidth,
      photoHeight = paneHeight,
      left = 0.dp,
      top = 0.dp,
      coversPane = false,
    )
  }
  return previewFrame(width, height, paneWidth, paneHeight, coversPane = false)
}

/**
 * The resize screen's preview: the whole picture, with the part the export will keep shown
 * through a window and everything the crop throws away dimmed underneath a scrim.
 *
 * The picture can be dragged and pinched, which is how the window is aimed: the window
 * itself stays where the layout put it, so the finger moves the photograph rather than the
 * crop - the same thing, but the frame's edges never leave the screen while it happens.
 *
 * The scrim is drawn in four bands around the window rather than as one rectangle with a
 * hole, because a hole needs a separate layer and this way the discarded part of the
 * photograph stays visible: a crop is easier to judge when what it is cutting is still
 * there, just darker.
 */
@Composable
internal fun CropPreview(
  bitmap: ImageBitmap?,
  sourceSize: Pair<Int, Int>?,
  cropAspect: Float,
  zoom: Float,
  pan: Offset,
  description: String,
  onTransform: (zoom: Float, pan: Offset) -> Unit,
  enabled: Boolean = true,
  placeholder: @Composable () -> Unit = {},
  modifier: Modifier = Modifier,
  content: @Composable BoxWithConstraintsScope.(PreviewFrame) -> Unit = {},
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val aspect = cropAspect.coerceAtLeast(0.0001f)
    val frame = cropPreviewFrame(sourceSize, maxWidth, maxHeight)

    val sourceAspect = when {
      sourceSize != null && sourceSize.second > 0 ->
        sourceSize.first.toFloat() / sourceSize.second
      bitmap != null && bitmap.height > 0 ->
        bitmap.width.toFloat() / bitmap.height
      else -> 1f
    }

    // The window at rest is the biggest one of the chosen shape the picture holds, drawn
    // inside the picture rather than filling the pane: that is what keeps the discarded
    // part on screen for the scrim to darken.
    val (coverWidth, coverHeight) = coverCropSize(sourceAspect, aspect)
    val frameWidth = frame.photoWidth * coverWidth
    val frameHeight = frame.photoHeight * coverHeight
    val frameLeft = frame.left + (frame.photoWidth - frameWidth) / 2
    val frameTop = frame.top + (frame.photoHeight - frameHeight) / 2

    val safeZoom = zoom.coerceIn(1f, MAX_CROP_ZOOM)
    val windowWidth = (coverWidth / safeZoom).coerceAtLeast(0.0001f)
    val windowHeight = (coverHeight / safeZoom).coerceAtLeast(0.0001f)
    val windowLeft = ((1f - windowWidth) / 2f - pan.x).coerceIn(0f, 1f - windowWidth)
    val windowTop = ((1f - windowHeight) / 2f - pan.y).coerceIn(0f, 1f - windowHeight)

    val density = LocalDensity.current
    val frameLeftPx = with(density) { frameLeft.toPx() }
    val frameTopPx = with(density) { frameTop.toPx() }
    val frameWidthPx = with(density) { frameWidth.toPx() }
    val frameHeightPx = with(density) { frameHeight.toPx() }

    // The gesture lambda is created once per pane shape, so it reads the live values
    // through these rather than being relaunched on every frame of a pinch.
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestOnTransform by rememberUpdatedState(onTransform)

    Box(
      modifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        .semantics { contentDescription = description }
        .pointerInput(
          enabled,
          frameLeftPx,
          frameTopPx,
          frameWidthPx,
          frameHeightPx,
          sourceAspect,
          aspect,
        ) {
          if (!enabled) return@pointerInput
          // The gesture loop is relaunched only when the pane or the frame changes, so it
          // carries the live zoom and pan itself: two events can arrive between
          // recompositions, and the second has to build on the first.
          var zoomNow = latestZoom.coerceIn(1f, MAX_CROP_ZOOM)
          var panNow = latestPan
          detectTransformGestures { centroid, panPx, zoomChange, _ ->
            val (coverW, coverH) = coverCropSize(sourceAspect, aspect)
            val oldZoom = zoomNow
            val newZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_CROP_ZOOM)
            val oldWidth = coverW / oldZoom
            val oldHeight = coverH / oldZoom
            val newWidth = coverW / newZoom
            val newHeight = coverH / newZoom
            // Where the pinch is, as a fraction of the window: the picture is scaled about
            // that point, so the part under the fingers stays under them.
            val qx = ((centroid.x - frameLeftPx) / frameWidthPx).coerceIn(0f, 1f)
            val qy = ((centroid.y - frameTopPx) / frameHeightPx).coerceIn(0f, 1f)
            val imageWidth = frameWidthPx / newWidth
            val imageHeight = frameHeightPx / newHeight
            val maxX = (1f - newWidth) / 2f
            val maxY = (1f - newHeight) / 2f
            val nextPan = Offset(
              (panNow.x + (oldWidth - newWidth) * (0.5f - qx) + panPx.x / imageWidth)
                .coerceIn(-maxX, maxX),
              (panNow.y + (oldHeight - newHeight) * (0.5f - qy) + panPx.y / imageHeight)
                .coerceIn(-maxY, maxY),
            )
            zoomNow = newZoom
            panNow = nextPan
            latestOnTransform(newZoom, nextPan)
          }
        },
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
          // The window is a fraction of the picture; draw the picture so that fraction
          // lands exactly under it. The rest extends past the window and is dimmed below.
          val imageWidth = frameWidthPx / windowWidth
          val imageHeight = frameHeightPx / windowHeight
          drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(
              (frameLeftPx - windowLeft * imageWidth).roundToInt(),
              (frameTopPx - windowTop * imageHeight).roundToInt(),
            ),
            dstSize = IntSize(
              imageWidth.roundToInt().coerceAtLeast(1),
              imageHeight.roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.Medium,
          )
        }

        // Everything the crop leaves out, including the margins of the pane, is darkened:
        // still readable as a picture, never mistakable for the part that is kept.
        val scrim = Color.Black.copy(alpha = 0.62f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, frameTopPx))
        drawRect(
          scrim,
          topLeft = Offset(0f, frameTopPx + frameHeightPx),
          size = Size(size.width, size.height - frameTopPx - frameHeightPx),
        )
        drawRect(
          scrim,
          topLeft = Offset(0f, frameTopPx),
          size = Size(frameLeftPx, frameHeightPx),
        )
        drawRect(
          scrim,
          topLeft = Offset(frameLeftPx + frameWidthPx, frameTopPx),
          size = Size(size.width - frameLeftPx - frameWidthPx, frameHeightPx),
        )

        // The edge of the crop, so it reads as one even on a photograph that is dark
        // against dark or light against light: a dark band under a white line, the same
        // trick the title bar's text uses.
        val border = 1.dp.toPx()
        drawRect(
          color = Color.Black.copy(alpha = 0.55f),
          topLeft = Offset(frameLeftPx - border, frameTopPx - border),
          size = Size(frameWidthPx + border * 2f, frameHeightPx + border * 2f),
          style = Stroke(width = border * 2f),
        )
        drawRect(
          color = Color.White.copy(alpha = 0.95f),
          topLeft = Offset(frameLeftPx, frameTopPx),
          size = Size(frameWidthPx, frameHeightPx),
          style = Stroke(width = border),
        )
      }

      if (bitmap == null) placeholder()
    }

    content(frame)
  }
}
