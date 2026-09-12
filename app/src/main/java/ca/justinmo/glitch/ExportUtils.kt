package ca.justinmo.glitch

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The formats a finished picture can be handed over in.
 *
 * Raw JPEG is the file itself, byte for byte; the other three say what the picture is
 * written as once it has been decoded and encoded again. [label] is what the chips on
 * the preview screen show, so the enum carries the choice and its name together.
 *
 * Raw and plain JPEG are two entries rather than one with a flag, because they come
 * out with the same name and the same type and are still different files: one *is* the
 * glitch, the other is a picture of it.
 */
internal enum class OutputFormat(
  @StringRes val label: Int,
  val extension: String,
  val mimeType: String,
) {
  RawJpeg(R.string.format_raw_jpeg, "jpg", "image/jpeg"),
  Jpeg(R.string.format_jpeg, "jpg", "image/jpeg"),
  Png(R.string.format_png, "png", "image/png"),
  Webp(R.string.format_webp, "webp", "image/webp"),
}

/**
 * Quality for the two re-encoders that take one: the app's own JPEG quality, which is
 * what the resize screen writes a resized file at. PNG ignores it.
 */
private const val REENCODE_QUALITY = JPEG_QUALITY

/**
 * The bytes to hand over when the picture is written as [format].
 *
 * Raw JPEG is the file as it stands: the glitched bytes *are* the picture, so it is
 * the one format that keeps the exact damage the flips made, and it costs nothing but
 * a copy. Every other format decodes the picture and encodes it again - the same
 * pixels in a new file, which is the only way to hand over a PNG at all, and the
 * reason a plain JPEG comes out smaller than a raw one: it is the picture as this
 * device's encoder sees it, compressed a second time, not the bytes the flips wrote.
 * Null means the file could not be decoded, or the encoder refused it.
 */
internal fun encodedFor(bytes: ByteArray, format: OutputFormat): ByteArray? = when (format) {
  OutputFormat.RawJpeg -> bytes
  else -> {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    // BitmapFactory hands back the stored grid, orientation tag unread; the encoder
    // writes no EXIF either, so turning the picture upright here is the only thing
    // that stops a camera photo being exported on its side. It is the same call the
    // previews make, so the file matches what was on screen.
    val upright = applyExifOrientation(bitmap, bytes)
    val encoder = when (format) {
      OutputFormat.Jpeg -> Bitmap.CompressFormat.JPEG
      OutputFormat.Png -> Bitmap.CompressFormat.PNG
      else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
      } else {
        @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
      }
    }
    ByteArrayOutputStream().use { out ->
      if (upright.compress(encoder, REENCODE_QUALITY, out)) out.toByteArray() else null
    }
  }
}

/**
 * What handing the finished picture over did: whether the file really went somewhere, what
 * to tell the user about it, and - for a save that worked - the [Uri] a viewer can be
 * pointed at.
 *
 * The three are kept apart because a hand-over that worked still has something to say - where
 * the file went - so the message on its own cannot answer the one question the preview's restart
 * button has to settle before it throws the session away: is there a copy of this picture in the
 * gallery? A save that failed returns a message too, and it is the difference that matters. The
 * URI is separate again because a share puts nothing in the gallery to point at, so it is null
 * for everything but a save.
 */
internal class HandOver(val saved: Boolean, val message: String?, val uri: Uri? = null)

/**
 * Write the finished picture into the gallery's Pictures/Glitch folder, with the
 * extension and MIME type of [format].
 */
internal fun saveToGallery(context: Context, bytes: ByteArray, format: OutputFormat): HandOver {
  val name = "Glitch_${timestamp()}.${format.extension}"
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
      put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Glitch")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: return HandOver(false, context.getString(R.string.export_create_failed))
    return try {
      resolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: error("no output stream")
      values.clear()
      values.put(MediaStore.Images.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      HandOver(true, context.getString(R.string.export_saved_pictures, name), uri)
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      HandOver(false, context.getString(R.string.export_failed, e.message))
    }
  } else {
    // API 26-28: write the file directly and ask MediaScanner to index it.
    return runCatching {
      val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Glitch",
      )
      dir.mkdirs()
      val file = File(dir, name)
      file.writeBytes(bytes)
      MediaScannerConnection.scanFile(
        context, arrayOf(file.absolutePath), arrayOf(format.mimeType), null,
      )
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      HandOver(true, context.getString(R.string.export_saved_path, file.absolutePath), uri)
    }.getOrElse { HandOver(false, context.getString(R.string.export_failed, it.message)) }
  }
}

/**
 * Hand the finished picture to another app: a file in our own cache, behind the
 * chooser, typed as [format].
 *
 * Reports the chooser as opened with nothing to say about it - the chooser is its own
 * feedback - and an error when it could not be opened. It is not a save: the app the user
 * picks decides what it keeps, so a share says nothing about whether there is a copy they
 * can come back to.
 */
internal fun shareImage(context: Context, bytes: ByteArray, format: OutputFormat): HandOver {
  val dir = File(context.cacheDir, "shared").apply { mkdirs() }
  val file = File(dir, "glitch_${timestamp()}.${format.extension}")
  return runCatching {
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
      type = format.mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
      Intent.createChooser(send, context.getString(R.string.preview_share_chooser)),
    )
  }.exceptionOrNull()
    ?.let { HandOver(false, context.getString(R.string.preview_share_error, it.message)) }
    ?: HandOver(false, null)
}

/** Timestamp used for generated file names, e.g. Glitch_20240907_171500.jpg. */
internal fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
