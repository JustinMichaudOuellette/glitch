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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import ca.justinmo.glitch.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.ui.theme.GlitchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Asset name of the bundled example photo drawn behind the start screen. */
private const val SAMPLE_ASSET = "example.webp"

/**
 * Screen 1: choose the source of the image - camera or gallery.
 *
 * The bundled example photo is drawn behind the controls as a decorative
 * backdrop: center-cropped to the 9:16 story frame and top-aligned so its top
 * edge sits flush with the status bar, the same geometry the resize and edit
 * screens use for their previews. The controls sit under that frame when the
 * window is tall enough and over its lower edge when it is not - see
 * [SourceScreenContent].
 */
@Composable
internal fun SourceScreen(onPicked: (bytes: ByteArray, res: Pair<Int, Int>, name: String) -> Unit) {
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
        if (raw == null) {
          file.delete()
          busy = false
          error = context.getString(R.string.source_error_read_capture)
        } else {
          val res = withContext(Dispatchers.Default) { imageResolution(raw) }
          file.delete()
          busy = false
          if (res == null) {
            error = context.getString(R.string.source_error_decode)
          } else {
            onPicked(raw, res, context.getString(R.string.image_source_camera))
          }
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
        if (raw == null) {
          busy = false
          error = context.getString(R.string.source_error_read_selection)
        } else {
          val res = withContext(Dispatchers.Default) { imageResolution(raw) }
          val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
            ?: context.getString(R.string.image_source_gallery)
          busy = false
          if (res == null) {
            error = context.getString(R.string.source_error_decode)
          } else {
            onPicked(raw, res, name)
          }
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
    onInfo = { toast(context.getString(R.string.app_name) + " v1.0") },
    backdrop = { contentScale ->
      backdrop?.let { bitmap ->
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = contentScale,
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
 * The photo sits in the standard [PreviewWithControls] frame: on a tall window
 * the camera / gallery buttons get their own row under it, on a short one the
 * frame runs edge to edge and they are laid over its bottom edge.
 *
 * [backdrop] is a slot so the caller owns the image: the app passes the decoded
 * example photo, previews pass a resource painter. The content hands it the
 * [ContentScale] to draw with, because the framing is part of the layout: the
 * story frame is shown whole while it fits at full width and cropped to fill the
 * window when it does not - a full-bleed photo with no letterbox bars is what an
 * overlay needs.
 */
@Composable
internal fun SourceScreenContent(
  busy: Boolean,
  error: String?,
  onCamera: () -> Unit,
  onGallery: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  onInfo: (() -> Unit)? = null,
  backdrop: @Composable BoxScope.(contentScale: ContentScale) -> Unit = {},
) {
  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      BoxWithConstraints(modifier = previewModifier, contentAlignment = Alignment.TopCenter) {
        // The story frame fills whatever the controls leave it: shown whole while
        // it fits at full width, cropped to fill the window when it does not.
        val frameHeight = maxWidth / STORIES_ASPECT
        val isCropped = frameHeight > maxHeight
        backdrop(if (isCropped) ContentScale.Crop else ContentScale.Fit)

        // Standard title bar: close (X) on the left, app title centered.
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(title = stringResource(R.string.title_glitch), onClose = onClose, onInfo = onInfo)
        }

        // If we have a gap below the image (tall window case), center the buttons there.
        // We only do this if PreviewWithControls is in "tall" mode (maxHeight >= TALL_WINDOW_HEIGHT).
        if (maxHeight >= TALL_WINDOW_HEIGHT && !isCropped) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(maxHeight - frameHeight)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
          ) {
            SourceControls(
              busy = busy,
              error = error,
              onCamera = onCamera,
              onGallery = onGallery,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        }
      }
    },
    controls = {
      // In short windows, or if the image fills the screen (cropped),
      // the buttons go in the standard bottom controls slot.
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val frameHeight = maxWidth / STORIES_ASPECT
        val isCropped = frameHeight > maxHeight
        if (maxHeight < TALL_WINDOW_HEIGHT || isCropped) {
          SourceControls(
            busy = busy,
            error = error,
            onCamera = onCamera,
            onGallery = onGallery,
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp, bottom = 24.dp)
          )
        }
      }
    },
  )
}

/**
 * Camera / gallery buttons: one fixed row, so they look the same wherever the
 * screen places them.
 */
@Composable
private fun SourceControls(
  busy: Boolean,
  error: String?,
  onCamera: () -> Unit,
  onGallery: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
      if (busy) {
        // Loading replaces the (disabled) buttons instead of squeezing them.
        Column(
          modifier = Modifier.align(Alignment.Center),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            onClick = onCamera,
            modifier = Modifier.weight(1f).height(56.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.PhotoCamera,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.action_camera),
              style = MaterialTheme.typography.titleMedium,
            )
          }
          Button(
            onClick = onGallery,
            modifier = Modifier.weight(1f).height(56.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.PhotoLibrary,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
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

// --- Previews ---------------------------------------------------------------

/**
 * Stand-in for the bundled example photo. The screen decodes
 * assets/example.webp at runtime; previews use the (identical) 9:16 copy in
 * res/drawable so no AssetManager is involved.
 */
@Composable
private fun BoxScope.SampleBackdrop(contentScale: ContentScale) {
  Image(
    bitmap = previewImageBitmap(), // res/drawable/example.webp
    contentDescription = null,
    modifier = Modifier.fillMaxSize(),
    contentScale = contentScale,
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
        backdrop = { SampleBackdrop(it) },
      )
    }
  }
}

/** Idle state: example photo, title bar, camera / gallery buttons. */
@Preview(name = "Source", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun SourceScreenPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      SourceScreenContent(
        busy = false,
        error = null,
        onCamera = {},
        onGallery = {},
        onClose = {},
        backdrop = { SampleBackdrop(it) },
      )
    }
  }
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
 * Small window: the 9:16 frame runs full height and the camera / gallery
 * buttons are overlaid on its lower edge instead of squeezing the photo.
 */
@Preview(name = "Source · small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun SourceScreenSmallScreenPreview() {
  SourceScreenPreviewShell(busy = false, error = null)
}

/** Same overlay in a short landscape window, with the photo letterboxed. */
@Preview(name = "Source · landscape", device = "spec:width=891dp,height=411dp", showBackground = true)
@Composable
private fun SourceScreenLandscapePreview() {
  SourceScreenPreviewShell(busy = false, error = null)
}
