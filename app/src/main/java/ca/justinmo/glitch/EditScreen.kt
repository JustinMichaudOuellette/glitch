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
import androidx.compose.foundation.layout.statusBarsPadding
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
  // Two parses: the pristine source (what restart injection starts from, so
  // re-injecting never compounds) and the current base that flips are applied
  // to. They differ once restart markers have been injected.
  val sourceStructure = remember(sourceBytes) { JpegParser.parse(sourceBytes) }
  var baseBytes by remember(sourceBytes) { mutableStateOf(sourceBytes) }
  val structure = remember(baseBytes) { JpegParser.parse(baseBytes) }
  var region by remember(sourceBytes) { mutableStateOf(GlitchRegion.SCAN) }
  // Progressive files expose one knob per scan; see ScanInfo in jpeg/JpegGlitch.kt.
  var scanIndex by remember(sourceBytes) { mutableIntStateOf(0) }
  // Where the damage should begin, as a fraction of image height.
  var areaFraction by remember(sourceBytes) { mutableFloatStateOf(0.5f) }
  // How tall each restart window should be, as a fraction of image height.
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

  // Pinch-to-zoom state. It stays here (rather than in the content) because
  // undo, reset and the exported bytes all read the current zoom.
  var scale by remember { mutableFloatStateOf(initialScale) }
  var offset by remember { mutableStateOf(initialOffset) }

  fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

  LaunchedEffect(Unit) {
    val bmp = withContext(Dispatchers.Default) { decodeJpeg(sourceBytes) }
    bitmap = bmp
    if (bmp == null) error = context.getString(R.string.edit_error_decode)
  }

  val geometry: ScanGeometry? = structure.geometryFor(scanIndex)

  /** Why restart injection is unavailable for this file, or null when possible. */
  val injectBlockedReason: String? = JpegRestartInjector.unsupportedReason(sourceStructure)
  val sourceFrame = sourceStructure.frame
  val windowInterval: Int =
    if (sourceFrame != null) {
      JpegRestartInjector.intervalForWindowFraction(sourceFrame, windowFraction)
    } else {
      0
    }
  /**
   * Rewrite the pristine source with restart markers so damage stops at window
   * edges. Always re-derived from [sourceBytes] rather than the current buffer,
   * so pressing it twice cannot compound the injection.
   */
  fun injectRestarts() {
    if (injecting || busy) return
    injecting = true
    error = null
    val interval = windowInterval
    val source = sourceBytes
    val parsed = sourceStructure
    scope.launch {
      // A Huffman transcode of a multi-megapixel scan is far too much work for
      // the main thread: b.jpg is 376k blocks.
      val result = withContext(Dispatchers.Default) {
        JpegRestartInjector.inject(source, parsed, interval)
      }
      if (result == null) {
        error = context.getString(
          R.string.edit_window_failed,
          JpegRestartInjector.unsupportedReason(parsed) ?: "",
        )
        injecting = false
        return@launch
      }
      history = history + bytes
      baseBytes = result.bytes
      bytes = result.bytes
      // The transcode is lossless, so the decoded picture is unchanged and the
      // existing bitmap stays valid - no re-decode, no visual flicker.
      readout = context.getString(
        R.string.edit_window_done,
        result.restartCount,
        (result.windowFraction * 100).roundToInt(),
      )
      injecting = false
    }
  }

  /**
   * Bytes the next flip may land in.
   *
   * For scan data this is the MCU row the area slider points at: entropy data is
   * stored MCU by MCU in raster order, so a flip inside that row starts its
   * damage there.
   *
   * Two kinds of byte are always removed:
   *  - [JpegStructure.fatal] - flipping them can only make the file undecodable.
   *  - restart markers - they are their own region with their own chip, and
   *    wrecking one destroys the resync point that bounds the damage.
   */
  fun targetSpans(): List<ByteSpan> = when (region) {
    GlitchRegion.SCAN -> {
      val geom = geometry
      val base = if (geom != null) {
        listOf(geom.byteSpanForRowFraction(areaFraction))
      } else {
        structure.scan
      }
      // subtract() needs sorted, non-overlapping holes; fatal covers only
      // DQT/DHT bytes and rst only marker bytes, so they never overlap.
      ByteSpans.subtract(base, (structure.fatal + structure.rst).sortedBy { it.start })
    }
    else -> structure.flippableSpans(region)
  }

  fun flipBit() {
    if (busy || bitmap == null) return
    busy = true
    error = null
    val rgn = region
    val spans = targetSpans()
    val startBytes = bytes
    val startBmp = bitmap
    val geom = geometry
    scope.launch {
      if (spans.sumOf { it.size } == 0) {
        busy = false
        error = context.getString(
          R.string.edit_error_region,
          context.getString(regionCodeRes(rgn)),
        )
        return@launch
      }
      // Flip bits in the target bytes; roll back flips the JPEG decoder rejects
      // or that leave the picture pixel-identical, like jpg_glitch.py. Roughly a
      // third of bytes are no-ops for all 8 of their bits, which is why a single
      // press may need a few tries to find one that takes effect.
      val rng = Random(System.nanoTime())
      var acceptedBytes: ByteArray? = null
      var acceptedBmp: Bitmap? = null
      var acceptedPos = 0
      var acceptedBit = 0
      var attempts = 0
      while (attempts < JpegGlitch.MAX_FLIP_TRIES) {
        attempts++
        val probe = startBytes.copyOf()
        val flipped = JpegGlitch.flipRandomBit(probe, spans, rng) ?: break
        val decoded = withContext(Dispatchers.Default) { decodeJpeg(probe) }
        if (decoded == null) continue // undecodable - roll back, try another bit
        val visible = startBmp == null || !decoded.sameAs(startBmp)
        if (!visible) continue // no pixel change - roll back, try another bit
        acceptedBytes = probe
        acceptedBmp = decoded
        acceptedPos = flipped.first
        acceptedBit = flipped.second
        break
      }
      val newBytes = acceptedBytes
      val newBmp = acceptedBmp
      if (newBytes != null) {
        history = history + bytes
        bytes = newBytes
        bitmap = newBmp
        flips++
        val retried = if (attempts > 1) {
          context.getString(R.string.edit_flip_retried, attempts - 1)
        } else {
          ""
        }
        readout = if (geom != null) {
          val startRow = (geom.damageStartFraction(acceptedPos) * 100).roundToInt()
          if (structure.restartInterval > 0) {
            // With restarts the damage is bounded, so report both edges.
            val endRow = (geom.damageEndFraction(
              acceptedPos,
              structure.restartInterval,
            ) * 100).roundToInt()
            context.getString(
              R.string.edit_flip_readout_band,
              "0x${acceptedPos.toString(16)}",
              acceptedBit,
              startRow,
              endRow,
            ) + retried
          } else {
            context.getString(
              R.string.edit_flip_readout,
              "0x${acceptedPos.toString(16)}",
              acceptedBit,
              startRow,
            ) + retried
          }
        } else {
          context.getString(
            R.string.edit_flip_readout_plain,
            "0x${acceptedPos.toString(16)}",
            acceptedBit,
          ) + retried
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
    // Undo can step back across a restart injection, so the base (and therefore
    // the parsed structure) has to follow the buffer.
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

  // Everything below is drawn by the stateless EditScreenContent: parsing,
  // decoding, glitching and exporting stay here, the pixels are theirs.
  val shownBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

  EditScreenContent(
    state = EditUiState(
      structure = structure,
      bitmap = shownBitmap,
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
      // Derived from the pristine source, so it has to be passed in: after a
      // restart injection `structure` is no longer the file being checked.
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

/** Everything [EditScreenContent] draws. */
@Immutable
internal data class EditUiState(
  /** Structure of the buffer the next flip is applied to. */
  val structure: JpegStructure,
  /** Decoded picture, or null while it is still being decoded. */
  val bitmap: ImageBitmap?,
  val region: GlitchRegion,
  val scanIndex: Int,
  val areaFraction: Float,
  val windowFraction: Float,
  val readout: String?,
  val error: String?,
  val flips: Int,
  /** True while there is a previous buffer to step back to. */
  val canUndo: Boolean,
  val busy: Boolean,
  val injecting: Boolean,
  val exporting: Boolean,
  val zoomScale: Float,
  val zoomOffset: Offset,
  /** Why restart injection is unavailable here, or null when it can run. */
  val injectBlockedReason: String?,
)

/**
 * Stateless body of [EditScreen]: the whole editor UI, driven purely by [state]
 * and the callbacks, so it can be rendered - and previewed - without JPEG
 * bytes, a decoder or a coroutine scope.
 *
 * [state] is unpacked into local vals first, which keeps the layout below
 * identical to the one that used to live inside [EditScreen].
 */
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
  val structure = state.structure
  val bitmap = state.bitmap
  val region = state.region
  val scanIndex = state.scanIndex
  val areaFraction = state.areaFraction
  val windowFraction = state.windowFraction
  val readout = state.readout
  val error = state.error
  val flips = state.flips
  val canUndo = state.canUndo
  val busy = state.busy
  val injecting = state.injecting
  val exporting = state.exporting
  val injectBlockedReason = state.injectBlockedReason
  val scale = state.zoomScale
  val offset = state.zoomOffset

  val geometry: ScanGeometry? = structure.geometryFor(scanIndex)
  val componentNames = remember(structure) { componentShortNames(structure.frame) }
  val restartsActive = structure.restartInterval > 0
  /** Height of one restart window as a percentage of the image. */
  val activeWindowPercent: Int = geometry?.let { geom ->
    if (geom.unitRows > 0 && geom.unitsPerRow > 0) {
      (structure.restartInterval.toFloat() / geom.unitsPerRow / geom.unitRows * 100f).roundToInt()
    } else {
      0
    }
  } ?: 0

  val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
    onTransform(zoomChange, offsetChange)
  }

  Box(modifier = modifier.fillMaxSize()) {
    // Current frame (Layer 0: Background zoomable preview). The status bar
    // inset keeps the top edge of the preview flush against the status bar
    // instead of running underneath it.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      val bmp = bitmap
      if (bmp != null) {
        Image(
          bitmap = bmp,
          contentDescription = stringResource(R.string.edit_image_desc),
          modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformState)
            .graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offset.x,
              translationY = offset.y,
            ),
          contentScale = ContentScale.Fit,
          alignment = Alignment.TopCenter,
          filterQuality = FilterQuality.None,
        )
      } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
    }

    // Controls (Layer 1: Foreground UI)
    Column(modifier = Modifier.fillMaxSize()) {
      Spacer(modifier = Modifier.statusBarsPadding())
      TitleBar(title = stringResource(R.string.title_glitch), onBack = onBack)

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
            val total = structure.byteCount(r)
            val usable = structure.flippableByteCount(r)
            FilterChip(
              selected = region == r,
              enabled = usable > 0,
              onClick = { onRegionChange(r) },
              label = {
                Text(text = regionChipLabel(r, usable, total - usable))
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

        // Spell out what the selected chip means: "DQT" alone is cryptic, and
        // the three regions behave completely differently.
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(regionDescriptionRes(region)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Progressive files hide ~10 semantically different knobs behind the single
      // "Scan" chip; the DC scan is a global nuke while a DC refinement pass is
      // invisible. Pick the scan individually, then aim where its damage starts.
      if (region == GlitchRegion.SCAN) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        ) {
          if (structure.scans.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.edit_scan_section),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                  label = {
                    Text(
                      text = stringResource(
                        R.string.edit_scan_chip,
                        scan.index,
                        scanDescriptor(scan, componentNames),
                      ),
                    )
                  },
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
          Text(
            text = stringResource(
              R.string.edit_area_section,
              (areaFraction * 100).roundToInt(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Slider(
            value = areaFraction,
            onValueChange = onAreaChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
              thumbColor = MaterialTheme.colorScheme.primary,
              activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
          )
          val geom = geometry
          Text(
            text = if (geom != null) {
              stringResource(
                R.string.edit_area_row,
                (geom.unitRowFraction(unitRowFor(geom, areaFraction)) * geom.imageHeight)
                  .roundToInt(),
                geom.imageHeight,
              )
            } else {
              stringResource(R.string.edit_area_no_geometry)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Bounding: without restart markers the damage always runs to the
          // bottom of the image, so the area slider can only pick where it
          // starts. Injecting restart markers gives the decoder a place to
          // resynchronise, which bounds the damage to one window.
          Spacer(Modifier.height(12.dp))
          if (restartsActive) {
            Text(
              text = stringResource(R.string.edit_window_active, activeWindowPercent),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          } else if (injectBlockedReason != null) {
            Text(
              text = stringResource(R.string.edit_window_unsupported, injectBlockedReason),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(
              text = stringResource(
                R.string.edit_window_section,
                (windowFraction * 100).roundToInt(),
              ),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
              value = windowFraction,
              onValueChange = onWindowChange,
              valueRange = 0.02f..0.5f,
              colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary,
              ),
            )
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
            ) {
              Text(
                text = if (injecting) {
                  stringResource(R.string.edit_window_working)
                } else {
                  stringResource(R.string.edit_window_button)
                },
              )
            }
          }
        }
      }

      val skipped = structure.fatalByteCount(region)
      if (skipped > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.edit_fatal_note, skipped),
          modifier = Modifier.padding(horizontal = 24.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.weight(1f))

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 24.dp)
          .padding(top = 16.dp, bottom = 24.dp),
      ) {
        readout?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(4.dp))
        }
        error?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          Spacer(Modifier.height(8.dp))
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            onClick = onUndo,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = canUndo,
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
            onClick = onFlip,
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
            onClick = onReset,
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
            onClick = onShare,
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
            onClick = onExport,
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

/** Unit row index the area slider currently points at. */
private fun unitRowFor(geometry: ScanGeometry, areaFraction: Float): Int {
  val targetRow = (areaFraction * geometry.imageHeight).toInt()
  return (targetRow / geometry.rowsPerUnitRow.coerceAtLeast(1))
    .coerceIn(0, (geometry.unitRows - 1).coerceAtLeast(0))
}

/** "Y" / "Cb" / "Cr" for each frame component id, in frame-header order. */
private fun componentShortNames(frame: JpegFrame?): Map<Int, String> {
  val fallback = listOf("Y", "Cb", "Cr")
  val components = frame?.components ?: return emptyMap()
  return components.withIndex().associate { (index, component) ->
    component.id to (fallback.getOrNull(index) ?: "C${component.id}")
  }
}

/**
 * Human description of what a scan encodes: "DC · all", "AC 1-5 · Y",
 * "refine DC · all". In a progressive file these are the real knob labels -
 * the DC scan carries the whole image, an AC band only its frequency range.
 */
@Composable
private fun scanDescriptor(scan: ScanInfo, names: Map<Int, String>): String {
  val spectral = when {
    scan.isBaselineFull -> stringResource(R.string.edit_scan_baseline)
    scan.isDcOnly -> stringResource(R.string.edit_scan_dc)
    else -> stringResource(R.string.edit_scan_ac, scan.ss, scan.se)
  }
  val prefixed = if (scan.isRefinement) {
    stringResource(R.string.edit_scan_refine_prefix) + spectral
  } else {
    spectral
  }
  val components = if (scan.componentCount >= 3) {
    stringResource(R.string.edit_scan_components_all)
  } else {
    scan.componentIds.joinToString("+") { names[it] ?: "C$it" }
  }
  return "$prefixed · $components"
}

/**
 * Region chip text, showing how much of the region is actually usable. DHT is
 * roughly 16% fatal and 61% no-op, so it should not look like an equal choice
 * next to DQT when ~77% of it can never produce anything worth keeping.
 */
@Composable
private fun regionChipLabel(region: GlitchRegion, usable: Int, fatal: Int): String {
  val code = stringResource(regionCodeRes(region))
  return when {
    usable <= 0 && fatal <= 0 -> stringResource(R.string.edit_region_chip, code, formatBytes(0))
    usable <= 0 -> stringResource(R.string.edit_region_all_fatal, code)
    fatal <= 0 -> stringResource(R.string.edit_region_chip, code, formatBytes(usable))
    else -> stringResource(
      R.string.edit_region_chip_some_fatal,
      code,
      formatBytes(usable),
      (fatal * 100) / (usable + fatal),
    )
  }
}

// --- Previews ---------------------------------------------------------------

/**
 * Hand-built stand-in for [JpegParser.parse]: a 1080x1920 baseline frame with
 * the usual DQT / DHT / single scan layout. Previews get realistic chip labels,
 * row readouts and fatal-byte notes without shipping a sample JPEG.
 *
 * Geometry matches the frame: 2x2 chroma sampling gives 16x16 px MCUs, so
 * 1080/16 = 68 MCUs per row and 1920/16 = 120 MCU rows.
 */
private fun previewStructure(restartInterval: Int = 0): JpegStructure {
  val scan = ScanInfo(
    index = 0,
    markerOffset = 622,
    start = 640,
    endExclusive = 262_144,
    components = listOf(
      ScanComponent(id = 1, dcTable = 0, acTable = 0),
      ScanComponent(id = 2, dcTable = 1, acTable = 1),
      ScanComponent(id = 3, dcTable = 1, acTable = 1),
    ),
    ss = 0,
    se = 63,
    ah = 0,
    al = 0,
  )
  return JpegStructure(
    dqt = listOf(ByteSpan(20, 150)),
    dht = listOf(ByteSpan(180, 596)),
    scan = listOf(ByteSpan(640, 262_144)),
    rst = emptyList(),
    scans = listOf(scan),
    geometry = listOf(
      ScanGeometry(scan, unitsPerRow = 68, unitRows = 120, rowsPerUnitRow = 16, imageHeight = 1920),
    ),
    frame = JpegFrame(
      width = 1080,
      height = 1920,
      precision = 8,
      progressive = false,
      components = listOf(
        ComponentSampling(id = 1, h = 2, v = 2, quantTable = 0),
        ComponentSampling(id = 2, h = 1, v = 1, quantTable = 1),
        ComponentSampling(id = 3, h = 1, v = 1, quantTable = 1),
      ),
    ),
    // DQT table spec byte, plus the first DHT table's spec byte and its 16
    // code-length counts - the bytes a flip can never survive.
    fatal = listOf(ByteSpan(20, 21), ByteSpan(180, 197)),
    metadata = emptyList(),
    restartInterval = restartInterval,
  )
}

/**
 * Editor state for a preview. The picture is the bundled example photo: the
 * real screen decodes the picked JPEG, which no preview can do.
 */
@Composable
private fun previewEditState(
  structure: JpegStructure = previewStructure(),
  region: GlitchRegion = GlitchRegion.SCAN,
  readout: String? = null,
  error: String? = null,
  flips: Int = 0,
  canUndo: Boolean = flips > 0,
): EditUiState = EditUiState(
  structure = structure,
  bitmap = ImageBitmap.imageResource(R.drawable.example),
  region = region,
  scanIndex = 0,
  areaFraction = 0.5f,
  windowFraction = 0.125f,
  readout = readout,
  error = error,
  flips = flips,
  canUndo = canUndo,
  busy = false,
  injecting = false,
  exporting = false,
  zoomScale = 1f,
  zoomOffset = Offset.Zero,
  injectBlockedReason = null,
)

/** Shared shell for the previews: app theme, dark surface, real editor UI. */
@Composable
private fun EditScreenPreviewShell(state: EditUiState) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      EditScreenContent(
        state = state,
        onBack = {},
        onRegionChange = {},
        onScanChange = {},
        onAreaChange = {},
        onWindowChange = {},
        onInject = {},
        onFlip = {},
        onUndo = {},
        onReset = {},
        onShare = {},
        onExport = {},
        onTransform = { _, _ -> },
      )
    }
  }
}

/** Untouched file: scan chips and both sliders, nothing to undo yet. */
@Preview(name = "Edit", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenPreview() {
  EditScreenPreviewShell(previewEditState())
}

/** After a flip: readout line, and undo / reset / share / save light up. */
@Preview(name = "Edit · glitched", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenGlitchedPreview() {
  EditScreenPreviewShell(
    previewEditState(
      readout = stringResource(R.string.edit_flip_readout, "0x1f3a", 5, 50),
      flips = 1,
    ),
  )
}

/** Restart markers injected: damage is bounded, so the window control is gone. */
@Preview(name = "Edit · damage bounded", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenBoundedPreview() {
  EditScreenPreviewShell(
    previewEditState(
      // 2040 of 68x120 MCUs = a 25% tall band.
      structure = previewStructure(restartInterval = 2040),
      readout = stringResource(R.string.edit_flip_readout_band, "0x2c81", 2, 50, 62),
      flips = 3,
    ),
  )
}

/** DHT selected: its own chips, the fatal-byte note, and the failed-flip error. */
@Preview(name = "Edit · dht region", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun EditScreenDhtPreview() {
  EditScreenPreviewShell(
    previewEditState(
      region = GlitchRegion.DHT,
      error = stringResource(R.string.edit_error_no_bit, JpegGlitch.MAX_FLIP_TRIES),
    ),
  )
}
