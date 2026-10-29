package ca.justinmo.glitch

import androidx.compose.ui.geometry.Offset
import kotlin.math.min
import kotlin.math.roundToInt

/** How far in the crop window can be pulled, relative to the whole picture. */
internal const val MAX_CROP_ZOOM = 5f

/** How far off whole a window may be and still count as the picture untouched. */
private const val WHOLE_CROP_EPSILON = 0.002f

/** Guard for an aspect ratio divided by, so a bad header cannot produce a NaN window. */
private const val MIN_ASPECT = 0.0001f

/**
 * The part of the picture an export keeps, in proportions rather than pixels: `0,0` is
 * the upright picture's top-left corner and `1,1` its bottom-right, whatever size the
 * file or the decode is.
 *
 * One description for three callers: the native pipeline cuts the rectangle out of the
 * full-size decode, the platform fallback cuts it out of a bitmap, and the resize screen
 * draws it as the window the user keeps. A proportion means the same thing at 2400 px and
 * at 4032, which is what lets the screen's preview and the export agree without either
 * knowing the other's decode.
 */
internal data class NormalizedCrop(
  val left: Float,
  val top: Float,
  val width: Float,
  val height: Float,
) {
  val right: Float get() = left + width
  val bottom: Float get() = top + height

  /** True when the window is the picture: nothing would be cut off. */
  val isWholePicture: Boolean
    get() = left <= WHOLE_CROP_EPSILON &&
      top <= WHOLE_CROP_EPSILON &&
      right >= 1f - WHOLE_CROP_EPSILON &&
      bottom >= 1f - WHOLE_CROP_EPSILON
}

/**
 * The biggest window of [cropAspect] that fits a picture of [sourceAspect], as proportions
 * of the picture: what the crop starts as before any zoom or pan.
 *
 * No crop is the same question asked with the picture's own aspect, which is why it comes
 * back as the whole frame - and why the editor can treat all three choices the same way.
 */
internal fun coverCropSize(sourceAspect: Float, cropAspect: Float): Pair<Float, Float> {
  val source = sourceAspect.coerceAtLeast(MIN_ASPECT)
  val crop = cropAspect.coerceAtLeast(MIN_ASPECT)
  return min(1f, crop / source) to min(1f, source / crop)
}

/**
 * The window [zoom] and [pan] choose for a picture of [sourceAspect] cropped to
 * [cropAspect].
 *
 * [pan] is the distance the picture has been dragged, as a proportion of its own drawn
 * width and height, so it means the same thing whatever the pane it was dragged in. The
 * window is clamped inside the picture, which is what stops a drag from exposing a corner
 * with nothing in it.
 */
internal fun cropWindow(
  sourceAspect: Float,
  cropAspect: Float,
  zoom: Float,
  pan: Offset,
): NormalizedCrop {
  val (coverWidth, coverHeight) = coverCropSize(sourceAspect, cropAspect)
  val safeZoom = zoom.coerceIn(1f, MAX_CROP_ZOOM)
  val width = (coverWidth / safeZoom).coerceIn(MIN_ASPECT, 1f)
  val height = (coverHeight / safeZoom).coerceIn(MIN_ASPECT, 1f)
  val left = ((1f - width) / 2f - pan.x).coerceIn(0f, 1f - width)
  val top = ((1f - height) / 2f - pan.y).coerceIn(0f, 1f - height)
  return NormalizedCrop(left, top, width, height)
}

/**
 * [pan] pulled back inside the range that still keeps the window in the picture at
 * [zoom].
 *
 * The range shrinks when the zoom does - the window grows, so the same drag points further
 * outside the picture than it did - which is why this is asked again after every change
 * rather than only while a finger is down.
 */
internal fun clampedCropPan(
  pan: Offset,
  sourceAspect: Float,
  cropAspect: Float,
  zoom: Float,
): Offset {
  val (coverWidth, coverHeight) = coverCropSize(sourceAspect, cropAspect)
  val safeZoom = zoom.coerceIn(1f, MAX_CROP_ZOOM)
  val maxX = (1f - coverWidth / safeZoom) / 2f
  val maxY = (1f - coverHeight / safeZoom) / 2f
  return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/** The crop's size, in pixels of a picture [resolution] pixels big. */
internal fun cropPixelSize(resolution: Pair<Int, Int>, crop: NormalizedCrop): Pair<Int, Int> {
  val width = (crop.width * resolution.first).roundToInt().coerceAtLeast(1)
  val height = (crop.height * resolution.second).roundToInt().coerceAtLeast(1)
  return width to height
}
