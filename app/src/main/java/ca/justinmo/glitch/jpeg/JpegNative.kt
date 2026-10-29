package ca.justinmo.glitch.jpeg

import android.graphics.Bitmap
import ca.justinmo.glitch.NormalizedCrop

/**
 * The app's own JPEG decoder and encoder, over libjpeg-turbo built into the app.
 *
 * The platform's JPEG is Skia's, which is not one implementation but one per OEM
 * and Android version: the same glitched bytes render differently on two phones,
 * and neither is the decoder the `b_x4` sweep in `DHT.md` was measured with. This
 * is one library at one version, so a byte flip means the same thing everywhere -
 * and means the same thing it meant in the sweep whose folders color the editor.
 *
 * Two settings are deliberate and load bearing, both matching the sweep:
 * standard (Annex K) Huffman tables rather than optimised ones, so the sweep's
 * bit indices still describe the file; and **strict** failure on a damaged
 * stream, so the bits the classification has no sample for are the bits this
 * refuses, rather than the bits Skia feels like guessing at.
 *
 * [isAvailable] is false when the native library did not load, which is the
 * signal for callers to fall back to the platform codec.
 */
internal object JpegNative {

  /** Quality the reference pipeline writes, and the app re-encodes at. */
  const val REFERENCE_QUALITY = 92

  /** Subsampling the reference pipeline uses: 4:2:0. */
  const val SUBSAMPLING_420 = 2

  /** 4:4:4, for callers that want the detail kept. */
  const val SUBSAMPLING_444 = 0

  /** True when the library loaded; false means "use the platform codec". */
  val isAvailable: Boolean = try {
    System.loadLibrary("jpegpipe")
    true
  } catch (error: UnsatisfiedLinkError) {
    false
  }

  /** Which libjpeg the app is running, for a diagnostics readout. */
  external fun version(): String

  /**
   * Per quantization-table id, a bitmask of the zigzag coefficients that are
   * non-zero somewhere in the file.
   *
   * Bit `k` set means table `id`'s quantizer for coefficient `k` can change the
   * picture: the decoder multiplies the coefficient by that quantizer, so a
   * coefficient that is zero in every block stays zero whatever the quantizer
   * says. Null when the stream does not decode; the caller then has to show
   * every quantizer rather than hide rows it cannot judge.
   */
  external fun coefficientMasks(jpeg: ByteArray): LongArray?

  /**
   * [coefficientMasks] with the missing-library fallback folded in: null means
   * "cannot tell", not "nothing is live".
   */
  fun liveCoefficientMasks(jpeg: ByteArray): LongArray? =
    if (isAvailable) coefficientMasks(jpeg) else null

  /**
   * Decode [jpeg], scaled so its longest edge is at most [maxDim] pixels.
   *
   * Returns 0xAARRGGBB pixels, with the decoded size written into [outSize] as
   * width then height, or null when the stream does not decode - which is the
   * strict behaviour the sweep recorded, not a bug.
   */
  external fun decodeArgb(jpeg: ByteArray, maxDim: Int, outSize: IntArray): IntArray?

  /**
   * Encode [argb] (0xAARRGGBB) as a baseline JPEG at [quality], with
   * [subsampling] one of the constants above.
   */
  external fun encodeArgb(
    argb: IntArray,
    width: Int,
    height: Int,
    quality: Int,
    subsampling: Int,
  ): ByteArray?

  /**
   * The reference pipeline in one call: decode, turn the picture upright, optionally
   * crop it, Lanczos-resize to [targetWidth] and re-encode.
   *
   * One call because the intermediate pixels are the expensive part: a 12 MP
   * photo is 48 MB of RGB, and there is no reason for it to cross into the Java
   * heap on its way from the decoder to the encoder.
   *
   * The crop is either centre-only, by [cropAspect], or an exact rectangle in
   * [cropLeft], [cropTop], [cropWidth] and [cropHeight] - proportions of the upright
   * picture. The rectangle wins when it has a width and a height; a [cropAspect] at or
   * below zero means no crop, and a [targetWidth] at or above the source leaves the
   * size alone.
   *
   * [orientation] is the EXIF orientation of [jpeg] - the numbers
   * `ExifInterface.ORIENTATION_*` uses, 1 meaning "already upright". libjpeg does
   * not read that tag and this pipeline writes a file carrying no EXIF of its own,
   * so the rotation has to happen in here: a camera photo whose stored grid is
   * sideways would otherwise be handed on sideways with nothing left to say so.
   * It is applied before the crop and the scale, which are both measured on the
   * upright picture - so the rectangle is too.
   */
  external fun resizeJpeg(
    jpeg: ByteArray,
    targetWidth: Int,
    quality: Int,
    subsampling: Int,
    cropAspect: Float,
    cropLeft: Float,
    cropTop: Float,
    cropWidth: Float,
    cropHeight: Float,
    orientation: Int,
  ): ByteArray?

  /**
   * Decode for display, landing on an exact long edge of [maxDim] when the image
   * is larger than that.
   *
   * Distinct from [decodeArgb] because a libjpeg N/8 decode would quantise the
   * size - a 1400 cap on a 4032 px photo comes out at 1008 - and these screens
   * exist to show fine detail. Small images are decoded in full.
   */
  external fun decodePreviewArgb(jpeg: ByteArray, maxDim: Int, outSize: IntArray): IntArray?

  /**
   * The same pipeline without the encoder: decode, optionally centre-crop and
   * Lanczos-resize, returning 0xAARRGGBB pixels and the size in [outSize].
   *
   * This is what the parity tests compare against Pillow's LANCZOS, and what a
   * screen can use when it wants the scaled picture rather than a file.
   */
  external fun scaleArgb(
    jpeg: ByteArray,
    targetWidth: Int,
    cropAspect: Float,
    outSize: IntArray,
  ): IntArray?

  /**
   * Crop and Lanczos-resize pixels the caller already has, returning 0xAARRGGBB.
   *
   * For the resize screen, whose live preview has to use the resampler the
   * export will use - otherwise the picture on screen is not the picture in the
   * file. [cropAspect] at or below zero means no crop, and the size of the
   * result is written into [outSize] as width then height: a crop changes both
   * dimensions, and a target width above the *cropped* width leaves the size
   * alone rather than scaling up, so neither can be worked out from the input.
   */
  external fun scalePixelsArgb(
    argb: IntArray,
    width: Int,
    height: Int,
    targetWidth: Int,
    cropAspect: Float,
    outSize: IntArray,
  ): IntArray?

  /**
   * Resize a bitmap through the app's own resampler, or null when the native
   * library is missing so the caller can fall back to the platform scaler.
   *
   * The size comes back from the native side rather than being recomputed here:
   * with a 9:16 crop of a 4:3 photo the cropped strip can already be narrower
   * than the target, in which case the preview is the cropped strip, not a
   * scale of it - and guessing otherwise read rows at the wrong stride and, at
   * some widths, quietly handed the screen to Skia's scaler instead.
   */
  fun scaleBitmap(source: Bitmap, targetWidth: Int, cropAspect: Float?): Bitmap? {
    if (!isAvailable) return null
    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val size = IntArray(2)
    val scaled = scalePixelsArgb(
      argb = pixels,
      width = width,
      height = height,
      targetWidth = targetWidth,
      cropAspect = cropAspect ?: 0f,
      outSize = size,
    ) ?: return null
    val outWidth = size[0]
    val outHeight = size[1]
    if (outWidth <= 0 || outHeight <= 0 || scaled.size < outWidth * outHeight) return null
    return Bitmap.createBitmap(scaled, 0, outWidth, outWidth, outHeight, Bitmap.Config.ARGB_8888)
  }

  /** Decode to a [Bitmap] at full size, or null when the stream does not decode. */
  fun decodeBitmap(jpeg: ByteArray, maxDim: Int): Bitmap? =
    bitmapOf(jpeg, maxDim, preview = false)

  /**
   * Decode to a [Bitmap] for display, with the long edge landing on exactly
   * [maxDim] when the image is bigger. Null when the stream does not decode.
   */
  fun decodePreviewBitmap(jpeg: ByteArray, maxDim: Int): Bitmap? =
    bitmapOf(jpeg, maxDim, preview = true)

  private fun bitmapOf(jpeg: ByteArray, maxDim: Int, preview: Boolean): Bitmap? {
    if (!isAvailable) return null
    val size = IntArray(2)
    val pixels = if (preview) {
      decodePreviewArgb(jpeg, maxDim, size)
    } else {
      decodeArgb(jpeg, maxDim, size)
    } ?: return null
    val width = size[0]
    val height = size[1]
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    return Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888)
  }

  /**
   * Resize through the reference pipeline, falling back to null when the native
   * library is missing so the caller can use the platform path instead.
   *
   * [crop] is the exact rectangle to keep; when it is null the old centre-only crop
   * by [cropAspect] is used, which is what the parity tests were written against.
   *
   * [orientation] is the input's EXIF orientation, as [resizeJpeg] takes it. The
   * default is 1 - "the stored grid is already the picture" - so a caller with no
   * tag to report gets the pipeline it always got, byte for byte.
   */
  fun resizeReference(
    jpeg: ByteArray,
    targetWidth: Int,
    cropAspect: Float? = null,
    crop: NormalizedCrop? = null,
    orientation: Int = 1,
  ): ByteArray? {
    if (!isAvailable) return null
    return resizeJpeg(
      jpeg = jpeg,
      targetWidth = targetWidth,
      quality = REFERENCE_QUALITY,
      subsampling = SUBSAMPLING_420,
      cropAspect = cropAspect ?: 0f,
      cropLeft = crop?.left ?: 0f,
      cropTop = crop?.top ?: 0f,
      cropWidth = crop?.width ?: 0f,
      cropHeight = crop?.height ?: 0f,
      orientation = orientation,
    )
  }
}
