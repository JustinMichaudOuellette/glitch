package ca.justinmo.glitch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Largest edge (px) that "Resize" scales images down to. */
internal const val RESIZE_MAX_DIM = 1400

/** JPEG quality used when re-encoding (resize / PNG-to-JPEG conversion). */
internal const val JPEG_QUALITY = 92

/** If [bytes] is already a JPEG (SOI marker) return it unchanged, else decode and re-encode. */
internal fun toJpegBytes(bytes: ByteArray): ByteArray? {
  if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return bytes
  return runCatching {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val upright = applyExifOrientation(bmp, bytes)
    val out = ByteArrayOutputStream()
    if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
    out.toByteArray()
  }.getOrNull()
}

/** Full decode of the JPEG bytes - used per glitch attempt (compare + display). */
internal fun decodeJpeg(bytes: ByteArray): Bitmap? = runCatching {
  val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
  applyExifOrientation(bmp, bytes)
}.getOrNull()

/** Decode at a reduced sample size so huge camera files don't blow up memory. */
internal fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
  val opts = BitmapFactory.Options().apply { inSampleSize = sample }
  val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
  applyExifOrientation(bmp, bytes)
}.getOrNull()

/**
 * Pixel dimensions of [bytes] as they are displayed (width x height), i.e.
 * EXIF rotations/transposes that swap the axes are taken into account.
 * Returns null when the header can't be read.
 */
internal fun imageResolution(bytes: ByteArray): Pair<Int, Int>? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  val swapAxes = exifOrientation(bytes) in setOf(
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE,
  )
  if (swapAxes) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
}.getOrNull()

/** Decode, apply EXIF orientation, optionally crop, scale to at most [targetWidth] px wide. */
internal fun resizeJpeg(bytes: ByteArray, targetWidth: Int, cropAspect: Float? = null): ByteArray {
  val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    ?: error("image could not be decoded")
  val oriented = applyExifOrientation(raw, bytes)
  val cropped = if (cropAspect != null) centeredCropBitmap(oriented, cropAspect) else null
  val src = cropped ?: oriented
  if (cropped != null && cropped !== oriented) oriented.recycle() // intermediate
  val w = src.width
  val h = src.height
  val scale = min(1f, targetWidth.toFloat() / w.toFloat())
  val scaled = if (scale < 1f) {
    Bitmap.createScaledBitmap(src, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
  } else {
    src
  }
  val out = ByteArrayOutputStream()
  check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encoding failed" }
  if (scaled !== src) scaled.recycle()
  src.recycle()
  return out.toByteArray()
}

/**
 * Crop the biggest centered rectangle of [bmp] that has [targetAspect]
 * (width / height). Does NOT recycle [bmp]: returns [bmp] itself when its
 * aspect already matches, otherwise a new cropped bitmap whose lifetime is
 * independent of the input (the caller keeps ownership of both).
 */
internal fun centeredCropBitmap(bmp: Bitmap, targetAspect: Float): Bitmap {
  val w = bmp.width
  val h = bmp.height
  val current = w.toFloat() / h.toFloat()
  if (kotlin.math.abs(current - targetAspect) < 0.001f) return bmp
  val cropW: Int
  val cropH: Int
  if (current > targetAspect) {
    // too wide: shave the left/right edges
    cropH = h
    cropW = (h * targetAspect).roundToInt().coerceIn(1, w)
  } else {
    // too tall: shave the top/bottom edges
    cropW = w
    cropH = (w / targetAspect).roundToInt().coerceIn(1, h)
  }
  val left = (w - cropW) / 2
  val top = (h - cropH) / 2
  return Bitmap.createBitmap(bmp, left, top, cropW, cropH)
}

/**
 * Cheap live preview of the export output: optionally center-crop [src] to
 * [cropAspect], then scale so the width is at most [targetWidthPx].
 * Operates on the already-decoded sample (never re-decodes the file), never
 * upscales, and never recycles [src]. Returns a new bitmap (or [src] itself
 * when no crop and no downscale is needed).
 */
internal fun previewAtTarget(
  src: Bitmap,
  targetWidthPx: Int,
  cropAspect: Float?,
): Bitmap {
  val cropped = if (cropAspect != null) centeredCropBitmap(src, cropAspect) else src
  val w = cropped.width
  val h = cropped.height
  if (w <= targetWidthPx) return cropped // already fits - nothing to scale
  val scale = targetWidthPx.toFloat() / w
  val scaled = Bitmap.createScaledBitmap(
    cropped,
    targetWidthPx,
    (h * scale).roundToInt().coerceAtLeast(1),
    true,
  )
  if (scaled !== cropped && cropped !== src) cropped.recycle() // intermediate crop is disposable
  return scaled
}

/**
 * EXIF orientation value stored in [bytes] (1 = normal if absent).
 *
 * BitmapFactory ignores EXIF orientation, so phone cameras (which save
 * portrait shots rotated + an orientation tag) would otherwise come out
 * sideways. Orientation is read with the platform ExifInterface (API 24+).
 */
internal fun exifOrientation(bytes: ByteArray): Int = runCatching {
  ExifInterface(ByteArrayInputStream(bytes))
    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

/**
 * Rotate/flip [bmp] so its pixels match the EXIF orientation of [bytes].
 * Returns the oriented bitmap; the input is recycled if a new bitmap was
 * created, so the caller must treat the result as the only owner.
 */
internal fun applyExifOrientation(bmp: Bitmap, bytes: ByteArray): Bitmap {
  when (exifOrientation(bytes)) {
    ExifInterface.ORIENTATION_NORMAL -> return bmp
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> return mirror(bmp, flipX = true)
    ExifInterface.ORIENTATION_ROTATE_180 -> return rotate(bmp, 180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> return mirror(bmp, flipY = true)
    ExifInterface.ORIENTATION_TRANSPOSE -> return rotateThenMirror(bmp, 90f)
    ExifInterface.ORIENTATION_ROTATE_90 -> return rotate(bmp, 90f)
    ExifInterface.ORIENTATION_TRANSVERSE -> return rotateThenMirror(bmp, -90f)
    ExifInterface.ORIENTATION_ROTATE_270 -> return rotate(bmp, -90f)
    else -> return bmp
  }
}

private fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
  if (degrees == 0f) return bmp
  val matrix = Matrix().apply { setRotate(degrees) }
  val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
  if (rotated !== bmp) bmp.recycle()
  return rotated
}

private fun mirror(bmp: Bitmap, flipX: Boolean = false, flipY: Boolean = false): Bitmap {
  if (!flipX && !flipY) return bmp
  val matrix = Matrix().apply {
    setScale(if (flipX) -1f else 1f, if (flipY) -1f else 1f)
  }
  val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
  if (flipped !== bmp) bmp.recycle()
  return flipped
}

private fun rotateThenMirror(bmp: Bitmap, degrees: Float): Bitmap {
  // Transpose-style orientations (EXIF 5/7): rotate, then mirror horizontally.
  val matrix = Matrix().apply {
    setRotate(degrees)
    postScale(-1f, 1f)
  }
  val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
  if (out !== bmp) bmp.recycle()
  return out
}

internal fun uriToBytes(context: Context, uri: Uri): ByteArray? = runCatching {
  context.contentResolver.openInputStream(uri)?.use { input ->
    ByteArrayOutputStream().use { out ->
      input.copyTo(out)
      out.toByteArray()
    }
  }
}.getOrNull()

internal fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(0) else null
  }
}.getOrNull()
