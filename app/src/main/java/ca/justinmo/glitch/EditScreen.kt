package ca.justinmo.glitch

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ca.justinmo.glitch.jpeg.ByteSpan
import ca.justinmo.glitch.jpeg.ByteSpans
import ca.justinmo.glitch.jpeg.ComponentSampling
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.jpeg.JpegFrame
import ca.justinmo.glitch.jpeg.JpegGlitch
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.JpegRestartInjector
import ca.justinmo.glitch.jpeg.JpegStructure
import ca.justinmo.glitch.jpeg.ScanComponent
import ca.justinmo.glitch.jpeg.ScanGeometry
import ca.justinmo.glitch.jpeg.ScanInfo
import ca.justinmo.glitch.ui.theme.GlitchTheme
import java.io.File
import kotlin.math.roundToInt
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
  val sourceStructure = remember(sourceBytes) { JpegParser.parse(sourceBytes) }
  var baseBytes by remember(sourceBytes) { mutableStateOf(sourceBytes) }
  val structure = remember(baseBytes) { JpegParser.parse(baseBytes) }
  var region by remember(sourceBytes) { mutableStateOf(GlitchRegion.SCAN) }
  var scanIndex by remember(sourceBytes) { mutableIntStateOf(0) }
  var areaFraction by remember(sourceBytes) { mutableFloatStateOf(0.5f) }
  var windowFraction by remember(sourceBytes) { mutableFloatStateOf(0.125f) }
  var readout by remember(sourceBytes) { mutableStateOf<String?>(null) }
  var bytes by remember { mutableStateOf(sourceBytes) }
  var history by remember { mutableStateOf(emptyList<ByteArray>()) }
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  var flips by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var injecting by remember { mutableStateOf(false) }
  var exporting by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  var scale by remember { mutableFloatStateOf(initialScale) }
  var offset by remember { mutableStateOf(initialOffset) }

  fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

  LaunchedEffect(Unit) {
    val bmp = withContext(Dispatchers.Default) { decodeJpeg(sourceBytes) }
    bitmap = bmp
    if (bmp == null) error = context.getString(R.string.edit_error_decode)
  }

  val injectBlockedReason: String? = JpegRestartInjector.unsupportedReason(sourceStructure)

  fun injectRestarts() {
    if (injecting || busy) return
    injecting = true
    error = null
    val interval = if (sourceStructure.frame != null) {
      JpegRestartInjector.intervalForWindowFraction(sourceStructure.frame, windowFraction)
    } else 0
    scope.launch {
      val result = withContext(Dispatchers.Default) {
        JpegRestartInjector.inject(sourceBytes, sourceStructure, interval)
      }
      if (result == null) {
        error = context.getString(
          R.string.edit_window_failed,
          JpegRestartInjector.unsupportedReason(sourceStructure) ?: "",
        )
        injecting = false
        return@launch
      }
      history = history + bytes
      baseBytes = result.bytes
      bytes = result.bytes
      readout = context.getString(
        R.string.edit_window_done,
        result.restartCount,
        (result.windowFraction * 100).roundToInt(),
      )
      injecting = false
    }
  }

  fun flipBit() {
    if (busy || bitmap == null) return
    busy = true
    error = null
    val currentRegion = region
    val structureForTarget = structure
    val currentAreaFraction = areaFraction
    val currentScanIndex = scanIndex
    
    scope.launch {
      val geom = structureForTarget.geometryFor(currentScanIndex)
      val targetSpans = when (currentRegion) {
        GlitchRegion.SCAN -> {
          val base = if (geom != null) {
            listOf(geom.byteSpanForRowFraction(currentAreaFraction))
          } else {
            structureForTarget.scan
          }
          ByteSpans.subtract(base, (structureForTarget.fatal + structureForTarget.rst).sortedBy { it.start })
        }
        else -> structureForTarget.flippableSpans(currentRegion)
      }

      if (targetSpans.sumOf { it.size } == 0) {
        busy = false
        error = context.getString(R.string.edit_error_region, context.getString(regionCodeRes(currentRegion)))
        return@launch
      }

      val rng = Random(System.nanoTime())
      var acceptedBytes: ByteArray? = null
      var acceptedBmp: Bitmap? = null
      var acceptedPos = 0
      var acceptedBit = 0
      var attempts = 0
      val startBytes = bytes
      val startBmp = bitmap

      while (attempts < JpegGlitch.MAX_FLIP_TRIES) {
        attempts++
        val probe = startBytes.copyOf()
        val flipped = JpegGlitch.flipRandomBit(probe, targetSpans, rng) ?: break
        val decoded = withContext(Dispatchers.Default) { decodeJpeg(probe) }
        if (decoded == null) continue
        if (startBmp != null && decoded.sameAs(startBmp)) continue
        acceptedBytes = probe
        acceptedBmp = decoded
        acceptedPos = flipped.first
        acceptedBit = flipped.second
        break
      }

      if (acceptedBytes != null) {
        history = history + bytes
        bytes = acceptedBytes
        bitmap = acceptedBmp
        flips++
        val retried = if (attempts > 1) context.getString(R.string.edit_flip_retried, attempts - 1) else ""
        readout = if (geom != null) {
          val startRow = (geom.damageStartFraction(acceptedPos) * 100).roundToInt()
          if (structureForTarget.restartInterval > 0) {
            val endRow = (geom.damageEndFraction(acceptedPos, structureForTarget.restartInterval) * 100).roundToInt()
            context.getString(R.string.edit_flip_readout_band, "0x${acceptedPos.toString(16)}", acceptedBit, startRow, endRow) + retried
          } else {
            context.getString(R.string.edit_flip_readout, "0x${acceptedPos.toString(16)}", acceptedBit, startRow) + retried
          }
        } else {
          context.getString(R.string.edit_flip_readout_plain, "0x${acceptedPos.toString(16)}", acceptedBit) + retried
        }
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
    baseBytes = prevBytes
    bytes = prevBytes
    flips--
    error = null
    readout = null
    busy = true
    scope.launch {
      val bmp = withContext(Dispatchers.Default) { decodeJpeg(prevBytes) }
      bitmap = bmp
      busy = false
    }
  }

  fun reset() {
    baseBytes = sourceBytes
    bytes = sourceBytes
    history = emptyList()
    flips = 0
    error = null
    readout = null
    scale = 1f
    offset = Offset.Zero
    scope.launch {
      val bmp = withContext(Dispatchers.Default) { decodeJpeg(sourceBytes) }
      bitmap = bmp
    }
  }

  fun doSave() {
    exporting = true
    scope.launch {
      val msg = withContext(Dispatchers.IO) { saveToGallery(context, bytes) }
      exporting = false
      toast(msg)
    }
  }

  val exportPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted -> if (granted) doSave() else toast(context.getString(R.string.edit_error_permission)) }

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
      context.startActivity(Intent.createChooser(send, context.getString(R.string.edit_share_chooser)))
    }.onFailure { toast(context.getString(R.string.edit_share_error, it.message)) }
  }

  EditScreenContent(
    state = EditUiState(
      structure = structure,
      bitmap = bitmap?.asImageBitmap(),
      region = region,
      scanIndex = scanIndex,
      areaFraction = areaFraction,
      windowFraction = windowFraction,
      readout = readout,
      error = error,
      flips = flips,
      canUndo = history.isNotEmpty(),
      busy = busy,
      injecting = injecting,
      exporting = exporting,
      zoomScale = scale,
      zoomOffset = offset,
      injectBlockedReason = injectBlockedReason,
    ),
    onBack = onBack,
    onRegionChange = { region = it },
    onScanChange = { scanIndex = it },
    onAreaChange = { areaFraction = it },
    onWindowChange = { windowFraction = it },
    onInject = ::injectRestarts,
    onFlip = ::flipBit,
    onUndo = ::undo,
    onReset = ::reset,
    onShare = ::share,
    onExport = ::export,
    onTransform = { zoomChange, offsetChange ->
      scale = (scale * zoomChange).coerceIn(0.5f, 5f)
      offset += offsetChange
    },
  )
}

@Immutable
internal data class EditUiState(
  val structure: JpegStructure,
  val bitmap: ImageBitmap?,
  val region: GlitchRegion,
  val scanIndex: Int,
  val areaFraction: Float,
  val windowFraction: Float,
  val readout: String?,
  val error: String?,
  val flips: Int,
  val canUndo: Boolean,
  val busy: Boolean,
  val injecting: Boolean,
  val exporting: Boolean,
  val zoomScale: Float,
  val zoomOffset: Offset,
  val injectBlockedReason: String?,
)

@Composable
internal fun EditScreenContent(
  state: EditUiState,
  onBack: () -> Unit,
  onRegionChange: (GlitchRegion) -> Unit,
  onScanChange: (Int) -> Unit,
  onAreaChange: (Float) -> Unit,
  onWindowChange: (Float) -> Unit,
  onInject: () -> Unit,
  onFlip: () -> Unit,
  onUndo: () -> Unit,
  onReset: () -> Unit,
  onShare: () -> Unit,
  onExport: () -> Unit,
  onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
  modifier: Modifier = Modifier,
) {
  val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
    onTransform(zoomChange, offsetChange)
  }

  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      BoxWithConstraints(
        modifier = previewModifier,
        contentAlignment = Alignment.TopCenter,
      ) {
        val bmp = state.bitmap
        val photoHeight = if (bmp != null) maxWidth * (bmp.height.toFloat() / bmp.width.toFloat()) else 0.dp
        val isCropped = photoHeight > maxHeight

        if (bmp != null) {
          Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.edit_image_desc),
            modifier = Modifier
              .fillMaxSize()
              .transformable(state = transformState)
              .graphicsLayer(
                scaleX = state.zoomScale,
                scaleY = state.zoomScale,
                translationX = state.zoomOffset.x,
                translationY = state.zoomOffset.y,
              ),
            contentScale = if (isCropped) ContentScale.Crop else ContentScale.Fit,
            alignment = Alignment.TopCenter,
            filterQuality = FilterQuality.None,
          )
        } else {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }

        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(title = stringResource(R.string.title_glitch), onBack = onBack)
        }

        // Tall window case
        if (maxHeight >= TALL_WINDOW_HEIGHT && !isCropped) {
          // "Rest of controls" -> Aligned to the bottom of the preview
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(photoHeight)
              .align(Alignment.TopCenter),
            contentAlignment = Alignment.BottomCenter
          ) {
            EditOverlayContent(
              state = state,
              onRegionChange = onRegionChange,
              onScanChange = onScanChange,
              onAreaChange = onAreaChange,
              onWindowChange = onWindowChange,
              onInject = onInject,
              onFlip = onFlip,
              onUndo = onUndo,
              modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)
            )
          }

          // "Last row of buttons" -> Below the preview (centered in gap)
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(maxHeight - photoHeight)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
          ) {
            EditControls(
              flips = state.flips,
              exporting = state.exporting,
              onReset = onReset,
              onShare = onShare,
              onExport = onExport,
              modifier = Modifier.padding(horizontal = 24.dp)
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bmp = state.bitmap
        val photoHeight = if (bmp != null) maxWidth * (bmp.height.toFloat() / bmp.width.toFloat()) else 0.dp
        val isCropped = photoHeight > maxHeight

        if (maxHeight < TALL_WINDOW_HEIGHT || isCropped) {
          EditOverlayContent(
            state = state,
            onRegionChange = onRegionChange,
            onScanChange = onScanChange,
            onAreaChange = onAreaChange,
            onWindowChange = onWindowChange,
            onInject = onInject,
            onFlip = onFlip,
            onUndo = onUndo,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp)
          )
        }
      }
    },
    controls = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bmp = state.bitmap
        val photoHeight = if (bmp != null) maxWidth * (bmp.height.toFloat() / bmp.width.toFloat()) else 0.dp
        val isCropped = photoHeight > maxHeight

        if (maxHeight < TALL_WINDOW_HEIGHT || isCropped) {
          EditControls(
            flips = state.flips,
            exporting = state.exporting,
            onReset = onReset,
            onShare = onShare,
            onExport = onExport,
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 12.dp, bottom = 24.dp)
          )
        }
      }
    },
  )
}

@Composable
private fun EditOverlayContent(
  state: EditUiState,
  onRegionChange: (GlitchRegion) -> Unit,
  onScanChange: (Int) -> Unit,
  onAreaChange: (Float) -> Unit,
  onWindowChange: (Float) -> Unit,
  onInject: () -> Unit,
  onFlip: () -> Unit,
  onUndo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val structure = state.structure
  val region = state.region
  val scanIndex = state.scanIndex
  val areaFraction = state.areaFraction
  val windowFraction = state.windowFraction
  val readout = state.readout
  val error = state.error
  val canUndo = state.canUndo
  val busy = state.busy
  val injecting = state.injecting
  val injectBlockedReason = state.injectBlockedReason

  val geometry = structure.geometryFor(scanIndex)
  val componentNames = remember(structure) { componentShortNames(structure.frame) }
  val restartsActive = structure.restartInterval > 0
  val activeWindowPercent = geometry?.let { geom ->
    if (geom.unitRows > 0 && geom.unitsPerRow > 0) {
      (structure.restartInterval.toFloat() / geom.unitsPerRow / geom.unitRows * 100f).roundToInt()
    } else 0
  } ?: 0

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      GlitchRegion.entries.forEach { r ->
        val total = structure.byteCount(r)
        val usable = structure.flippableByteCount(r)
        FilterChip(
          selected = region == r,
          enabled = usable > 0,
          onClick = { onRegionChange(r) },
          label = { Text(text = regionChipLabel(r, usable, total - usable)) },
          colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
          )
        )
      }
    }

    Spacer(Modifier.height(4.dp))
    Text(
      text = stringResource(regionDescriptionRes(region)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (region == GlitchRegion.SCAN) {
      if (structure.scans.size > 1) {
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.edit_scan_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          structure.scans.forEach { scan ->
            FilterChip(
              selected = scanIndex == scan.index,
              enabled = scan.byteCount > 0,
              onClick = { onScanChange(scan.index) },
              label = { Text(text = stringResource(R.string.edit_scan_chip, scan.index, scanDescriptor(scan, componentNames))) },
              colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                selectedLabelColor = MaterialTheme.colorScheme.onTertiary
              )
            )
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      Text(text = stringResource(R.string.edit_area_section, (areaFraction * 100).roundToInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Slider(value = areaFraction, onValueChange = onAreaChange, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
      Text(
        text = if (geometry != null) stringResource(R.string.edit_area_row, (geometry.unitRowFraction(unitRowFor(geometry, areaFraction)) * geometry.imageHeight).roundToInt(), geometry.imageHeight)
        else stringResource(R.string.edit_area_no_geometry),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(Modifier.height(12.dp))
      if (restartsActive) {
        Text(text = stringResource(R.string.edit_window_active, activeWindowPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      } else if (injectBlockedReason != null) {
        Text(text = stringResource(R.string.edit_window_unsupported, injectBlockedReason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        Text(text = stringResource(R.string.edit_window_section, (windowFraction * 100).roundToInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = windowFraction, onValueChange = onWindowChange, valueRange = 0.02f..0.5f, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.tertiary, activeTrackColor = MaterialTheme.colorScheme.tertiary))
        Button(
          onClick = onInject,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          enabled = !injecting && !busy,
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
          ),
        ) { Text(text = if (injecting) stringResource(R.string.edit_window_working) else stringResource(R.string.edit_window_button)) }
      }
    }

    val skipped = structure.fatalByteCount(region)
    if (skipped > 0) {
      Spacer(Modifier.height(4.dp))
      Text(text = stringResource(R.string.edit_fatal_note, skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    readout?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
        onClick = onUndo,
        modifier = Modifier.weight(1f).height(56.dp),
        enabled = canUndo,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 8.dp)
      ) { Text(text = stringResource(R.string.edit_undo)) }
      Button(
        onClick = onFlip,
        modifier = Modifier.weight(1f).height(56.dp),
        enabled = !busy && state.bitmap != null,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 12.dp)
      ) {
        if (busy) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
          Spacer(Modifier.size(12.dp))
          Text(stringResource(R.string.edit_glitching))
        } else {
          Text(text = stringResource(R.string.edit_flip_button), style = MaterialTheme.typography.titleMedium)
        }
      }
    }
  }
}

@Composable
private fun EditControls(
  flips: Int,
  exporting: Boolean,
  onReset: () -> Unit,
  onShare: () -> Unit,
  onExport: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      onClick = onReset,
      modifier = Modifier.weight(1f).height(56.dp),
      enabled = flips > 0,
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 8.dp)
    ) { Text(stringResource(R.string.edit_reset)) }
    Button(
      onClick = onShare,
      modifier = Modifier.weight(1f).height(56.dp),
      enabled = flips > 0,
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 8.dp)
    ) { Text(stringResource(R.string.edit_share)) }
    Button(
      onClick = onExport,
      modifier = Modifier.weight(1f).height(56.dp),
      enabled = !exporting && flips > 0,
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 8.dp)
    ) { Text(text = if (exporting) stringResource(R.string.edit_saving) else stringResource(R.string.edit_export)) }
  }
}

private fun unitRowFor(geometry: ScanGeometry, areaFraction: Float): Int {
  val targetRow = (areaFraction * geometry.imageHeight).toInt()
  return (targetRow / geometry.rowsPerUnitRow.coerceAtLeast(1)).coerceIn(0, (geometry.unitRows - 1).coerceAtLeast(0))
}

private fun componentShortNames(frame: JpegFrame?): Map<Int, String> {
  val fallback = listOf("Y", "Cb", "Cr")
  return frame?.components?.withIndex()?.associate { (index, component) -> component.id to (fallback.getOrNull(index) ?: "C${component.id}") } ?: emptyMap()
}

@Composable
private fun scanDescriptor(scan: ScanInfo, names: Map<Int, String>): String {
  val spectral = when {
    scan.isBaselineFull -> stringResource(R.string.edit_scan_baseline)
    scan.isDcOnly -> stringResource(R.string.edit_scan_dc)
    else -> stringResource(R.string.edit_scan_ac, scan.ss, scan.se)
  }
  val prefixed = if (scan.isRefinement) stringResource(R.string.edit_scan_refine_prefix) + spectral else spectral
  val components = if (scan.componentCount >= 3) stringResource(R.string.edit_scan_components_all) else scan.componentIds.joinToString("+") { names[it] ?: "C$it" }
  return "$prefixed · $components"
}

@Composable
private fun regionChipLabel(region: GlitchRegion, usable: Int, fatal: Int): String {
  val code = stringResource(regionCodeRes(region))
  return when {
    usable <= 0 && fatal <= 0 -> stringResource(R.string.edit_region_chip, code, formatBytes(0))
    usable <= 0 -> stringResource(R.string.edit_region_all_fatal, code)
    fatal <= 0 -> stringResource(R.string.edit_region_chip, code, formatBytes(usable))
    else -> stringResource(R.string.edit_region_chip_some_fatal, code, formatBytes(usable), (fatal * 100) / (usable + fatal))
  }
}

private fun previewStructure(restartInterval: Int = 0): JpegStructure {
  val scan = ScanInfo(0, 622, 640, 262_144, listOf(ScanComponent(1, 0, 0), ScanComponent(2, 1, 1), ScanComponent(3, 1, 1)), 0, 63, 0, 0)
  return JpegStructure(listOf(ByteSpan(20, 150)), listOf(ByteSpan(180, 596)), listOf(ByteSpan(640, 262_144)), emptyList(), listOf(scan), listOf(ScanGeometry(scan, 68, 120, 16, 1920)), JpegFrame(1080, 1920, 8, false, listOf(ComponentSampling(1, 2, 2, 0), ComponentSampling(2, 1, 1, 1), ComponentSampling(3, 1, 1, 1))), listOf(ByteSpan(20, 21), ByteSpan(180, 197)), emptyList(), emptyList(), restartInterval)
}

@Composable
private fun previewEditState(
  structure: JpegStructure = previewStructure(),
  region: GlitchRegion = GlitchRegion.SCAN,
  readout: String? = null,
  error: String? = null,
  flips: Int = 0,
): EditUiState = EditUiState(structure, ImageBitmap.imageResource(R.drawable.example), region, 0, 0.5f, 0.125f, readout, error, flips, flips > 0, false, false, false, 1f, Offset.Zero, null)

@Composable
private fun EditScreenPreviewShell(state: EditUiState) {
  GlitchTheme { Surface(modifier = Modifier.fillMaxSize()) { EditScreenContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
}

@Preview(name = "Edit", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenPreview() { EditScreenPreviewShell(previewEditState()) }

@Preview(name = "Edit · glitched", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenGlitchedPreview() { EditScreenPreviewShell(previewEditState(readout = stringResource(R.string.edit_flip_readout, "0x1f3a", 5, 50), flips = 1)) }

@Preview(name = "Edit · damage bounded", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenBoundedPreview() { EditScreenPreviewShell(previewEditState(structure = previewStructure(2040), readout = stringResource(R.string.edit_flip_readout_band, "0x2c81", 2, 50, 62), flips = 3)) }

@Preview(name = "Edit · dht region", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenDhtPreview() { EditScreenPreviewShell(previewEditState(region = GlitchRegion.DHT, error = stringResource(R.string.edit_error_no_bit, JpegGlitch.MAX_FLIP_TRIES))) }

@Preview(name = "Edit · small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun EditScreenSmallScreenPreview() { EditScreenPreviewShell(previewEditState(flips = 1)) }
