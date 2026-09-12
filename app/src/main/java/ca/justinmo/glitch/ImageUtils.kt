package ca.justinmo.glitch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import ca.justinmo.glitch.jpeg.JpegNative
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.scale

/** Largest edge (px) that "Resize" scales images down to. */
internal const val RESIZE_MAX_DIM = 1400

/** JPEG quality used when re-encoding (resize / PNG-to-JPEG conversion). */
internal const val JPEG_QUALITY = 92

/** Aspect ratio used for Stories crops (9 wide : 16 tall). */
internal const val STORIES_ASPECT = 9f / 16f

/**
 * Encode [bmp] as a JPEG through the app's own encoder.
 *
 * Every JPEG this app writes goes through libjpeg-turbo: q92 with 4:2:0 and the
 * standard Annex K Huffman tables, which is what the `b_x4` sweep's files carry,
 * so a file made here is the same kind of file the classification was measured
 * on. Returns null when the native library is missing.
 *
 * None of these files carry EXIF - the encoder writes no metadata at all - so [bmp]
 * has to be the way up already: see [applyExifOrientation]. A file this app writes
 * is upright in its pixels, and a file it merely hands on keeps the orientation tag
 * it arrived with; those two are the only states there are, which is what stops the
 * picture turning when a later step decodes it again.
 */
internal fun encodeJpeg(bmp: Bitmap, quality: Int = JPEG_QUALITY): ByteArray? {
  val w = bmp.width
  val h = bmp.height
  if (w <= 0 || h <= 0) return null
  val pixels = IntArray(w * h)
  bmp.getPixels(pixels, 0, w, 0, 0, w, h)
  return JpegNative.encodeArgb(pixels, w, h, quality, JpegNative.SUBSAMPLING_420)
}

/** If [bytes] is already a JPEG (SOI marker) return it unchanged, else decode and re-encode. */
internal fun toJpegBytes(bytes: ByteArray): ByteArray? {
  if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return bytes
  return runCatching {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val upright = applyExifOrientation(bmp, bytes)
    encodeJpeg(upright)?.let { return@runCatching it }
    val out = ByteArrayOutputStream()
    if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
    out.toByteArray()
  }.getOrNull()
}

/**
 * Decode at a reduced size so huge camera files don't blow up memory.
 *
 * The decoding itself is [JpegNative] when the library is there - one libjpeg for
 * every device, which is what makes a glitch look the same on two phones, and
 * what makes the `b_x4` sweep's classification describe what this app shows. The
 * platform decoder is the fallback, and EXIF orientation is applied either way
 * because libjpeg does not read it.
 */
fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? = runCatching {
  nativeDecode(bytes, maxDim)?.let { return@runCatching it }
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
 * Decode through the app's own codec, with EXIF orientation applied.
 *
 * A damaged stream is refused here rather than guessed at, which is the same
 * policy the sweep recorded, so null means "this file does not decode" - the
 * caller's cue to use the platform decoder, which may be more forgiving.
 */
private fun nativeDecode(bytes: ByteArray, maxDim: Int): Bitmap? {
  val decoded = if (maxDim > 0) {
    JpegNative.decodePreviewBitmap(bytes, maxDim)
  } else {
    JpegNative.decodeBitmap(bytes, maxDim)
  } ?: return null
  return applyExifOrientation(decoded, bytes)
}

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
  // The reference pipeline in one call: decode, crop, Lanczos, re-encode at q92
  // with 4:2:0 - the settings the b_x4 sweep's files carry, so a resized photo
  // here is the same pixels the sweep measured.
  //
  // The orientation goes with it, and it has to. A phone camera saves a portrait
  // shot as a landscape grid plus an EXIF orientation tag; libjpeg hands back the
  // grid and this pipeline writes a file with no EXIF of its own, so without the
  // tag a captured photo came back lying on its side - and, worse, with nothing
  // left in the file to say it should be turned, so the sideways copy is what
  // every later step decoded. That was the rotation that appeared the moment a
  // quantization-table bit was flipped: the flip is just the first re-decode.
  JpegNative.resizeReference(bytes, targetWidth, cropAspect, exifOrientation(bytes))
    ?.let { return it }

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
    src.scale((w * scale).roundToInt(), (h * scale).roundToInt())
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
  // The resampler the export will use, so the preview is the file.
  JpegNative.scaleBitmap(src, targetWidthPx, cropAspect)?.let { return it }
  val cropped = if (cropAspect != null) centeredCropBitmap(src, cropAspect) else src
  val w = cropped.width
  val h = cropped.height
  if (w <= targetWidthPx) return cropped // already fits - nothing to scale
  val scale = targetWidthPx.toFloat() / w
  val scaled = cropped.scale(targetWidthPx, (h * scale).roundToInt().coerceAtLeast(1))
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
