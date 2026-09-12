package ca.justinmo.glitch

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppMode { Loading, Source, Resize, Edit }

/**
 * Three-step flow:
 *  1. [SourceScreen] - pick a photo (camera or gallery)
 *  2. [ResizeScreen] - resize the JPEG or keep it as-is
 *  3. [EditScreen] - flip bits in the JPEG bytes and export the result
 *
 * When [sharedImageUri] is set (another app shared an image into us via
 * ACTION_SEND), the picker step is skipped: the image is loaded and the
 * flow starts at step 2.
 */
@Composable
fun GlitchApp(sharedImageUri: Uri? = null) {
  val context = LocalContext.current
  var mode by remember {
    mutableStateOf(if (sharedImageUri == null) AppMode.Source else AppMode.Loading)
  }
  var sourceBytes by remember { mutableStateOf<ByteArray?>(null) }
  var sourceName by remember { mutableStateOf("image") }

  // Shared zoom state
  var zoomScale by remember { mutableFloatStateOf(1f) }
  var zoomOffset by remember { mutableStateOf(Offset.Zero) }

  // OS back navigation walks up the screen stack: Edit -> Resize -> Source.
  // On the Source screen (and while a shared image is still loading) the
  // default behaviour applies, which finishes the activity.
  BackHandler(enabled = mode == AppMode.Edit || mode == AppMode.Resize) {
    mode = if (mode == AppMode.Edit) AppMode.Resize else AppMode.Source
  }

  // Load an image shared from another app (works for JPEG, PNG, HEIC, ...).
  LaunchedEffect(sharedImageUri) {
    val uri = sharedImageUri ?: return@LaunchedEffect
    val raw = withContext(Dispatchers.IO) { uriToBytes(context, uri) }
    val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
      ?: context.getString(R.string.image_source_shared)
    if (raw == null) {
      Toast.makeText(
        context, context.getString(R.string.shared_error_read), Toast.LENGTH_LONG,
      ).show()
      mode = AppMode.Source
      return@LaunchedEffect
    }
    val jpeg = withContext(Dispatchers.Default) { toJpegBytes(raw) }
    if (jpeg == null) {
      Toast.makeText(
        context, context.getString(R.string.shared_error_decode), Toast.LENGTH_LONG,
      ).show()
      mode = AppMode.Source
    } else {
      sourceBytes = jpeg
      sourceName = name
      mode = AppMode.Resize
    }
  }

  Surface(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      val bytes = sourceBytes
      when (mode) {
        AppMode.Loading -> Box(Modifier.safeDrawingPadding()) {
          LoadingScreen(stringResource(R.string.loading_shared_image))
        }
        AppMode.Source -> Box(Modifier.safeDrawingPadding()) {
          SourceScreen(
            onPicked = { picked, name ->
              sourceBytes = picked
              sourceName = name
              mode = AppMode.Resize
            },
          )
        }
        AppMode.Resize -> if (bytes != null) {
          ResizeScreen(
            bytes = bytes,
            name = sourceName,
            onBack = { mode = AppMode.Source },
            onContinue = { resized, scale, offset ->
              sourceBytes = resized
              zoomScale = scale
              zoomOffset = offset
              mode = AppMode.Edit
            },
          )
        }
        AppMode.Edit -> if (bytes != null) {
          EditScreen(
            sourceBytes = bytes,
            initialScale = zoomScale,
            initialOffset = zoomOffset,
            onBack = { mode = AppMode.Resize },
          )
        }
      }
    }
  }
}
