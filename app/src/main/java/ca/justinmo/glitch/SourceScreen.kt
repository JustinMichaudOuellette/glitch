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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 1: choose the source of the image - camera or gallery.
 */
@Composable
internal fun SourceScreen(onPicked: (bytes: ByteArray, name: String) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  var sampleBitmap by remember { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      runCatching {
        context.assets.open("example.webp").use { input ->
          BitmapFactory.decodeStream(input)
        }
      }.getOrNull()
    }?.let { sampleBitmap = it }
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

  fun loadSampleImage() {
    scope.launch {
      busy = true
      error = null
      val raw = withContext(Dispatchers.IO) {
        runCatching {
          context.assets.open("example.webp").use { input ->
            input.readBytes()
          }
        }.getOrNull()
      }
      busy = false
      if (raw == null) {
        error = "Could not read sample image"
      } else {
        accept(raw, "example.webp")
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

  val activity = context as? Activity

  Column(modifier = Modifier.fillMaxSize()) {
    // Standard title bar: close (X) on the left, app title centered.
    TitleBar(title = stringResource(R.string.title_glitch), isAboveMedia = false, onClose = { activity?.finish() })

    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = stringResource(R.string.source_tagline),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(32.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        FilledTonalButton(
          onClick = ::launchCamera,
          modifier = Modifier.weight(1f).aspectRatio(1f),
          enabled = !busy,
          shape = RoundedCornerShape(12.dp),
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.PhotoCamera,
              contentDescription = null,
              modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.action_camera),
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }
        FilledTonalButton(
          onClick = {
            error = null
            galleryLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
          },
          modifier = Modifier.weight(1f).aspectRatio(1f),
          enabled = !busy,
          shape = RoundedCornerShape(12.dp),
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.PhotoLibrary,
              contentDescription = null,
              modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.action_gallery),
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }
      }

      Spacer(Modifier.height(32.dp))
      Text(
        text = "Or try a sample image:",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(12.dp))
      sampleBitmap?.let { bitmap ->
        Card(
          onClick = { loadSampleImage() },
          modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
          enabled = !busy,
          shape = RoundedCornerShape(12.dp),
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            Image(
              bitmap = bitmap.asImageBitmap(),
              contentDescription = "Sample Image",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          }
        }
      }

      if (busy) {
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.source_loading_image),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      error?.let {
        Spacer(Modifier.height(16.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
      }
    }
  }
}
