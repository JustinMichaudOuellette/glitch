package ca.justinmo.glitch

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Write the glitched JPEG into the gallery's Pictures/Glitch folder.
 * Returns a human-readable status message.
 */
internal fun saveToGallery(context: Context, bytes: ByteArray): String {
  val name = "Glitch_${timestamp()}.jpg"
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Glitch")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: return context.getString(R.string.export_create_failed)
    return try {
      resolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: error("no output stream")
      values.clear()
      values.put(MediaStore.Images.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      context.getString(R.string.export_saved_pictures, name)
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      context.getString(R.string.export_failed, e.message)
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
        context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null,
      )
      context.getString(R.string.export_saved_path, file.absolutePath)
    }.getOrElse { context.getString(R.string.export_failed, it.message) }
  }
}

/** Timestamp used for generated file names, e.g. Glitch_20240907_171500.jpg. */
internal fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
