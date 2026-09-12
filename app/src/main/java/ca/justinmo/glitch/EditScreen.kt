package ca.justinmo.glitch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.jpeg.JpegGlitch
import ca.justinmo.glitch.jpeg.JpegParser
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 3: flip random bits inside the JPEG byte regions and export the result.
 */
@Composable
internal fun EditScreen(
  sourceBytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val structure = remember(sourceBytes) { JpegParser.parse(sourceBytes) }

  var region by remember { mutableStateOf(GlitchRegion.DQT
  ) }
  var bytes by remember { mutableStateOf(sourceBytes) }
  var history by remember { mutableStateOf(emptyList<ByteArray>()) }
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  var flips by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var exporting by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // Pinch-to-zoom state
  var scale by remember { mutableFloatStateOf(initialScale) }
  var offset by remember { mutableStateOf(initialOffset) }
  val state = rememberTransformableState { zoomChange, offsetChange, _ ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    offset += offsetChange
  }

  fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

  LaunchedEffect(Unit) {
    val bmp = withContext(Dispatchers.Default) { decodeJpeg(sourceBytes) }
    bitmap = bmp
    if (bmp == null) error = context.getString(R.string.edit_error_decode)
  }

  fun flipBit() {
    if (busy || bitmap == null) return
    busy = true
    error = null
    val rgn = region
    val spans = structure.spansOf(rgn)
    val startBytes = bytes
    val startBmp = bitmap
    scope.launch {
      if (spans.sumOf { it.size } == 0) {
        busy = false
        error = context.getString(
          R.string.edit_error_region,
          context.getString(regionCodeRes(rgn)),
        )
        return@launch
      }
      // Flip one random bit in the region; roll back flips the JPEG decoder
      // rejects or that leave the picture pixel-identical, like jpg_glitch.py.
      val rng = Random(System.nanoTime())
      var acceptedBytes: ByteArray? = null
      var acceptedBmp: Bitmap? = null
      var attempts = 0
      while (attempts < JpegGlitch.MAX_FLIP_TRIES) {
        attempts++
        val probe = startBytes.copyOf()
        JpegGlitch.flipRandomBit(probe, spans, rng) ?: break
        val decoded = withContext(Dispatchers.Default) { decodeJpeg(probe) }
        if (decoded == null) continue // undecodable - roll back, try another bit
        val visible = startBmp == null || !decoded.sameAs(startBmp)
        if (!visible) continue // no pixel change - roll back, try another bit
        acceptedBytes = probe
        acceptedBmp = decoded
        break
      }
      val newBytes = acceptedBytes
      val newBmp = acceptedBmp
      if (newBytes != null) {
        history = history + bytes
        bytes = newBytes
        bitmap = newBmp
        flips++
      } else {
        error = context.getString(R.string.edit_error_no_bit, attempts)
      }
      busy = false
    }
  }

  fun undo() {
    if (history.isEmpty() || busy) return
    val prevBytes = history.last()
    history = history.dropLast(1)
    bytes = prevBytes
    flips--
    error = null
    busy = true
    scope.launch {
      val bmp = withContext(Dispatchers.Default) { decodeJpeg(prevBytes) }
      bitmap = bmp
      busy = false
    }
  }

  fun reset() {
    bytes = sourceBytes
    history = emptyList()
    flips = 0
    error = null
    scale = 1f
    offset = Offset.Zero
    scope.launch {
      val bmp = withContext(Dispatchers.Default) { decodeJpeg(sourceBytes) }
      bitmap = bmp
    }
  }

  // --- Export: save to the gallery ---
  fun doSave() {
    if (exporting) return
    exporting = true
    scope.launch {
      val message = withContext(Dispatchers.IO) { saveToGallery(context, bytes) }
      exporting = false
      toast(message)
    }
  }

  val exportPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      doSave()
    } else {
      toast(context.getString(R.string.edit_error_permission))
    }
  }

  fun export() {
    if (exporting) return
    val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
      PackageManager.PERMISSION_GRANTED
    if (needsPermission) {
      exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
      doSave()
    }
  }

  // --- Share the current bytes through the system share sheet ---
  fun share() {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "glitch_${timestamp()}.jpg")
    runCatching {
      file.writeBytes(bytes)
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(
        Intent.createChooser(send, context.getString(R.string.edit_share_chooser)),
      )
    }.onFailure {
      toast(context.getString(R.string.edit_share_error, it.message))
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // Current frame (Layer 0: Background zoomable preview)
    Box(
      modifier = Modifier
        .fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val bmp = bitmap
      if (bmp != null) {
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = stringResource(R.string.edit_image_desc),
          modifier = Modifier
            .fillMaxSize()
            .transformable(state = state)
            .graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offset.x,
              translationY = offset.y,
            ),
          contentScale = ContentScale.Fit,
        )
      } else {
        CircularProgressIndicator()
      }
    }

    // Controls (Layer 1: Foreground UI)
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      TitleBar(title = stringResource(R.string.title_glitch), isAboveMedia = true, onBack = onBack)

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(top = 8.dp),
      ) {
        // Region chips (mirrors the 1-4 keys of jpg_glitch.py)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          GlitchRegion.entries.forEach { r ->
            FilterChip(
              selected = region == r,
              enabled = structure.byteCount(r) > 0,
              onClick = { region = r },
              label = {
                Text(
                  text = stringResource(
                    R.string.edit_region_chip,
                    stringResource(regionCodeRes(r)),
                    formatBytes(structure.byteCount(r)),
                  ),
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
              )
            )
          }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(top = 16.dp, bottom = 24.dp),
      ) {
        error?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          Spacer(Modifier.height(8.dp))
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            onClick = ::undo,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = history.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            elevation = ButtonDefaults.buttonElevation(
              defaultElevation = 2.dp,
              pressedElevation = 8.dp
            )
          ) {
            Text(text = stringResource(R.string.edit_undo))
          }
          Button(
            onClick = ::flipBit,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = !busy && bitmap != null,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
              disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
              disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            elevation = ButtonDefaults.buttonElevation(
              defaultElevation = 4.dp,
              pressedElevation = 12.dp,
              disabledElevation = 0.dp
            )
          ) {
            if (busy) {
              CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
              Spacer(Modifier.size(12.dp))
              Text(stringResource(R.string.edit_glitching))
            } else {
              Text(
                text = stringResource(R.string.edit_flip_button),
                style = MaterialTheme.typography.titleMedium,
              )
            }
          }
        }

        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = ::reset,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = flips > 0,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            elevation = ButtonDefaults.buttonElevation(
              defaultElevation = 2.dp,
              pressedElevation = 8.dp
            )
          ) {
            Text(stringResource(R.string.edit_reset))
          }
          Button(
            onClick = ::share,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = flips > 0,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            elevation = ButtonDefaults.buttonElevation(
              defaultElevation = 2.dp,
              pressedElevation = 8.dp
            )
          ) {
            Text(stringResource(R.string.edit_share))
          }
          Button(
            onClick = ::export,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = !exporting && flips > 0,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            elevation = ButtonDefaults.buttonElevation(
              defaultElevation = 2.dp,
              pressedElevation = 8.dp
            )
          ) {
            Text(
              text = if (exporting) {
                stringResource(R.string.edit_saving)
              } else {
                stringResource(R.string.edit_export)
              },
            )
          }
        }
      }
    }
  }
}
