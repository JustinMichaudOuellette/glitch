package ca.justinmo.glitch

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ca.justinmo.glitch.ui.theme.GlitchTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asset name of the bundled example photo drawn behind the start screen. */
private const val SAMPLE_ASSET = "example.webp"

/** Least height kept for the camera / gallery controls under the preview. */
private val MIN_CONTROLS_HEIGHT = 96.dp

/**
 * Screen 1: choose the source of the image - camera or gallery.
 *
 * The bundled example photo is drawn behind the controls as a decorative
 * backdrop: center-cropped to the 9:16 story frame and top-aligned so its top
 * edge sits flush with the status bar, the same geometry the resize and edit
 * screens use for their previews.
 */
@Composable
internal fun SourceScreen(onPicked: (bytes: ByteArray, name: String) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  var backdrop by remember { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(Unit) {
    backdrop = withContext(Dispatchers.IO) {
      runCatching {
        context.assets.open(SAMPLE_ASSET).use { input ->
          BitmapFactory.decodeStream(input)
        }
      }.getOrNull()?.let { decoded -> centeredCropBitmap(decoded, STORIES_ASPECT) }
    }
  }

  fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

  // Turns arbitrary image bytes into JPEG bytes (PNG etc. are re-encoded).
  fun accept(raw: ByteArray, name: String) {
    scope.launch {
      busy = true
      error = null
      val jpeg = withContext(Dispatchers.Default) { toJpegBytes(raw) }
      busy = false
      if (jpeg == null) {
        error = context.getString(R.string.source_error_decode)
      } else {
        onPicked(jpeg, name)
      }
    }
  }

  // --- Camera capture (writes a JPEG into our cache via FileProvider) ---
  var pendingCapture by remember { mutableStateOf<File?>(null) }
  val cameraLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture(),
  ) { ok ->
    val file = pendingCapture
    pendingCapture = null
    if (ok && file != null) {
      scope.launch {
        busy = true
        error = null
        val raw = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
        file.delete()
        busy = false
        if (raw == null) {
          error = context.getString(R.string.source_error_read_capture)
        } else {
          onPicked(raw, context.getString(R.string.image_source_camera))
        }
      }
    }
  }

  fun launchCamera() {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    pendingCapture = file
    error = null
    runCatching { cameraLauncher.launch(uri) }
      .onFailure {
        pendingCapture = null
        toast(context.getString(R.string.source_error_no_camera_app))
      }
  }

  // --- Gallery (system photo picker, no permission needed) ---
  val galleryLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        busy = true
        error = null
        val raw = withContext(Dispatchers.IO) { uriToBytes(context, uri) }
        val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
          ?: context.getString(R.string.image_source_gallery)
        busy = false
        if (raw == null) {
          error = context.getString(R.string.source_error_read_selection)
        } else {
          accept(raw, name)
        }
      }
    }
  }

  // Launchers, assets and coroutines stay here; the whole visible layout is
  // drawn by the stateless SourceScreenContent below, which is what the
  // @Preview functions render.
  val activity = context as? Activity

  SourceScreenContent(
    busy = busy,
    error = error,
    onCamera = ::launchCamera,
    onGallery = {
      error = null
      galleryLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
      )
    },
    onClose = { activity?.finish() },
    backdrop = {
      backdrop?.let { bitmap ->
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
          alignment = Alignment.TopCenter,
        )
      }
    },
  )
}

/**
 * Stateless body of [SourceScreen]: everything the screen draws, driven purely
 * by its arguments. All picking, loading and error plumbing lives in
 * [SourceScreen], so this part can be rendered - and previewed - on its own.
 *
 * [backdrop] is a slot so the caller owns the image: the app passes the decoded
 * example photo, previews pass a resource painter.
 */
@Composable
internal fun SourceScreenContent(
  busy: Boolean,
  error: String?,
  onCamera: () -> Unit,
  onGallery: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  backdrop: @Composable BoxScope.() -> Unit = {},
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    // Preview: the example photo in its 9:16 story frame, flush with the status
    // bar (the caller already pads for the system bars). A short window shrinks
    // the preview rather than squeezing the controls below it.
    val previewHeight = (maxWidth / STORIES_ASPECT)
      .coerceAtMost(maxHeight - MIN_CONTROLS_HEIGHT)
      .coerceAtLeast(0.dp)

    Column(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(previewHeight),
      ) {
        backdrop()

        // Standard title bar: close (X) on the left, app title centered.
        TitleBar(title = stringResource(R.string.title_glitch), onClose = onClose)
      }

      // Camera / gallery: below the preview and stretched over the vertical
      // space that is left, instead of sitting square in the middle.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .padding(horizontal = 24.dp)
          .padding(top = 16.dp, bottom = 24.dp),
      ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
          if (busy) {
            // Loading replaces the (disabled) buttons instead of squeezing them.
            Column(
              modifier = Modifier.align(Alignment.Center),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator()
              Spacer(Modifier.height(8.dp))
              Text(
                text = stringResource(R.string.source_loading_image),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          } else {
            Row(
              modifier = Modifier.fillMaxSize(),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              FilledTonalButton(
                onClick = onCamera,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
              ) {
                Row {
                  Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = stringResource(R.string.action_camera),
                    style = MaterialTheme.typography.titleMedium,
                  )
                }
              }
              FilledTonalButton(
                onClick = onGallery,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
              ) {
                Row {
                  Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = stringResource(R.string.action_gallery),
                    style = MaterialTheme.typography.titleMedium,
                  )
                }
              }
            }
          }
        }

        error?.let {
          Spacer(Modifier.height(12.dp))
          Text(
            text = it,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

// --- Previews ---------------------------------------------------------------

/**
 * Stand-in for the bundled example photo. The screen decodes
 * assets/example.webp at runtime; previews use the (identical) 9:16 copy in
 * res/drawable so no AssetManager is involved.
 */
@Composable
private fun BoxScope.SampleBackdrop() {
  Image(
    painter = painterResource(R.drawable.example), // res/drawable/example.webp
    contentDescription = null,
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Fit,
    alignment = Alignment.TopCenter,
  )
}

/** Shared shell for the previews: app theme, dark surface, real screen body. */
@Composable
private fun SourceScreenPreviewShell(busy: Boolean, error: String?) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      SourceScreenContent(
        busy = busy,
        error = error,
        onCamera = {},
        onGallery = {},
        onClose = {},
        backdrop = { SampleBackdrop() },
      )
    }
  }
}

/** Idle state: example photo, title bar, camera / gallery buttons. */
@Preview(name = "Source", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun SourceScreenPreview() {
  SourceScreenPreviewShell(busy = false, error = null)
}

/** Loading state shown while an image is being read / re-encoded. */
@Preview(name = "Source · loading", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun SourceScreenLoadingPreview() {
  SourceScreenPreviewShell(busy = true, error = null)
}

/** Inline error, e.g. the picked image could not be decoded. */
@Preview(name = "Source · error", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun SourceScreenErrorPreview() {
  SourceScreenPreviewShell(busy = false, error = stringResource(R.string.source_error_decode))
}

/**
 * Short window: the 9:16 frame is capped so the controls keep their
 * [MIN_CONTROLS_HEIGHT] - the preview shrinks instead of the buttons.
 */
@Preview(name = "Source · short window", device = "spec:width=891dp,height=411dp", showBackground = true)
@Composable
private fun SourceScreenShortWindowPreview() {
  SourceScreenPreviewShell(busy = false, error = null)
}
