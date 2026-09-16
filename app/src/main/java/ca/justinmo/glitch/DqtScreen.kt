package ca.justinmo.glitch

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.jpeg.ByteSpan
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.JpegStructure
import ca.justinmo.glitch.jpeg.QuantAxis
import ca.justinmo.glitch.jpeg.QuantEntry
import ca.justinmo.glitch.jpeg.QuantTable
import ca.justinmo.glitch.jpeg.QuantTables
import ca.justinmo.glitch.ui.theme.GlitchTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-edge cap for the preview on this screen. */
private const val DQT_PREVIEW_CAP_PX = 1400

/** How much of the window the bits panel takes when it is open. */
private const val DQT_PANEL_HEIGHT_FRACTION = 0.62f

/**
 * Which set of quantizers a tile opens: an effect family ([QuantAxis]) plus
 * whether it lives in the chroma table. `DC + chroma` is saturation and
 * `DC + luma` is brightness; "color" in the labels always means chroma.
 */
internal data class DqtFamily(val axis: QuantAxis, val chroma: Boolean)

/**
 * The tiles on this screen in display order: the two that move the whole picture
 * first - they are by far the loudest - then each texture in luma and again in
 * chroma.
 */
internal val DQT_FAMILIES: List<DqtFamily> = listOf(
  DqtFamily(QuantAxis.DC, chroma = false),
  DqtFamily(QuantAxis.DC, chroma = true),
  DqtFamily(QuantAxis.HORIZONTAL, chroma = false),
  DqtFamily(QuantAxis.HORIZONTAL, chroma = true),
  DqtFamily(QuantAxis.VERTICAL, chroma = false),
  DqtFamily(QuantAxis.VERTICAL, chroma = true),
  DqtFamily(QuantAxis.CHECKERED, chroma = false),
  DqtFamily(QuantAxis.CHECKERED, chroma = true),
)

/** Quantizers of [family] in [tables], coarsest frequency first. */
internal fun entriesFor(tables: List<QuantTable>, family: DqtFamily): List<QuantEntry> =
  tables.filter { it.isChroma == family.chroma }
    .flatMap { it.entriesFor(family.axis) }
    .sortedWith(compareBy({ maxOf(it.u, it.v) }, { it.u }, { it.v }))

/** Title of a family tile, e.g. "Checkered color". */
private fun familyTitleRes(family: DqtFamily): Int = when (family.axis) {
  QuantAxis.DC ->
    if (family.chroma) R.string.dqt_family_saturation else R.string.dqt_family_brightness
  QuantAxis.HORIZONTAL ->
    if (family.chroma) R.string.dqt_family_horizontal_color else R.string.dqt_family_horizontal
  QuantAxis.VERTICAL ->
    if (family.chroma) R.string.dqt_family_vertical_color else R.string.dqt_family_vertical
  QuantAxis.CHECKERED ->
    if (family.chroma) R.string.dqt_family_checkered_color else R.string.dqt_family_checkered
}

/** One line on what the family does to the picture. */
private fun familyBlurbRes(family: DqtFamily): Int = when (family.axis) {
  QuantAxis.DC -> if (family.chroma) {
    R.string.dqt_blurb_saturation
  } else {
    R.string.dqt_blurb_brightness
  }
  QuantAxis.HORIZONTAL ->
    if (family.chroma) R.string.dqt_blurb_horizontal_color else R.string.dqt_blurb_horizontal
  QuantAxis.VERTICAL ->
    if (family.chroma) R.string.dqt_blurb_vertical_color else R.string.dqt_blurb_vertical
  QuantAxis.CHECKERED ->
    if (family.chroma) R.string.dqt_blurb_checkered_color else R.string.dqt_blurb_checkered
}

/** Qualitative name of a scale, 1 (coarsest) to 7 (finest). */
private fun scaleWordRes(scale: Int): Int = when (scale) {
  1 -> R.string.dqt_scale_coarsest
  2 -> R.string.dqt_scale_coarse
  3 -> R.string.dqt_scale_medium
  4 -> R.string.dqt_scale_fine
  else -> R.string.dqt_scale_subpixel
}

/** Size text such as "4.0 px" or "0.8-1.0 px" for one section. */
private fun sizeText(entries: List<QuantEntry>): String {
  val widths = entries.map { it.bandPx }
  val low = widths.min()
  val high = widths.max()
  return if (high - low < 0.05f) {
    String.format(Locale.getDefault(), "%.1f px", low)
  } else {
    String.format(Locale.getDefault(), "%.1f-%.1f px", low, high)
  }
}

/**
 * Two-finger zoom for gestures that start *outside* the preview.
 *
 * The preview zooms and pans itself with `transformable`, exactly like the
 * resize and edit screens, so a pinch there needs nothing extra. The bits panel
 * is drawn over the lower part of the window though, and a pinch that starts on
 * it would only scroll the list and leave the picture alone - so this modifier
 * sits on the whole screen, sees every gesture first (a panel above it cannot
 * hide events from its parent) and takes over the ones with two or more fingers.
 *
 * Single-finger gestures are never touched: list scrolling, tile taps and the
 * preview's own pan all keep working. A gesture that starts on the preview is
 * left alone as well, so the two handlers can never zoom twice for one pinch.
 */
private fun Modifier.pinchOutsidePreview(
  previewBoundsProvider: () -> Rect?,
  onZoom: (zoomChange: Float, panChange: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val bounds = previewBoundsProvider()
    if (bounds == null || bounds.contains(down.position)) {
      return@awaitEachGesture // the preview owns this gesture
    }

    var lastSpan = 0f
    var lastCentroid = Offset.Zero
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val pressed = event.changes.filter { it.pressed }
      if (pressed.isEmpty()) break
      if (pressed.size < 2) {
        lastSpan = 0f // one finger left: not a pinch, let the child have it
        continue
      }
      val centroid = pressed.fold(Offset.Zero) { sum, change -> sum + change.position } /
        pressed.size.toFloat()
      val span = pressed.fold(0f) { sum, change ->
        sum + (change.position - centroid).getDistance()
      } / pressed.size
      if (lastSpan > 0f && span > 0f) {
        onZoom(
          (span / lastSpan).coerceIn(0.5f, 2f),
          centroid - lastCentroid,
        )
      }
      lastSpan = span
      lastCentroid = centroid
      pressed.forEach { it.consume() }
    }
  }
}

/**
 * Screen between [ResizeScreen] and [EditScreen]: adjust the quantization tables
 * before glitching.
 *
 * The tables decide how coarsely each frequency of the picture is stored, so
 * editing them damages one chosen pattern instead of random bytes: the eight
 * tiles pick the pattern and the panel behind a tile edits the bytes of the
 * quantizers that draw it, one quantizer per row. Nothing here is random and
 * nothing is rolled back - whatever is set goes into the bytes handed to
 * [EditScreen].
 *
 * The panel is deliberately **not** a `ModalBottomSheet`: that renders in its own
 * window behind a scrim and swallows every touch aimed at this screen, which
 * would stop the preview from being pinch-zoomed while a pattern is open. This
 * panel is a plain surface in the same window, so the preview above it stays
 * live and pinching keeps working - on the preview, and (through
 * [pinchOutsidePreview]) anywhere else on the screen too.
 */
@Composable
internal fun DqtScreen(
  bytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
) {
  val structure = remember(bytes) { JpegParser.parse(bytes) }
  val tables = remember(bytes, structure) { QuantTables.parse(bytes, structure) }

  // Quantizer offset -> the value the user set. Only touched bytes are in here,
  // so everything else in the file stays exactly as it was.
  var edits by remember(bytes) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
  var openFamily by remember(bytes) { mutableStateOf<DqtFamily?>(null) }
  var preview by remember(bytes) { mutableStateOf<Bitmap?>(null) }

  // Zoom, starting where the resize screen left it and handed on to the editor,
  // exactly as on the other screens.
  var scale by remember { mutableFloatStateOf(initialScale) }
  var offset by remember { mutableStateOf(initialOffset) }

  // The panel animates out after the family is cleared, so it keeps the last
  // one it showed.
  var shownFamily by remember { mutableStateOf<DqtFamily?>(null) }
  LaunchedEffect(openFamily) { openFamily?.let { shownFamily = it } }

  var previewBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var screenBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

  val applied = remember(bytes, tables, edits) {
    if (edits.isEmpty()) bytes else bytes.copyOf().also {
      QuantTables.applyValues(it, tables, edits)
    }
  }

  LaunchedEffect(applied) {
    preview = withContext(Dispatchers.Default) { decodeSampled(applied, DQT_PREVIEW_CAP_PX) }
  }

  // Back closes the open pattern first, then the screen.
  BackHandler(enabled = openFamily != null) { openFamily = null }

  val transform: (Float, Offset) -> Unit = { zoomChange, panChange ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    offset += panChange
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onGloballyPositioned { screenBoundsInRoot = it.boundsInRoot() }
      .pinchOutsidePreview(
        // Read through the state holders, so the bounds are current whenever a
        // gesture arrives (this lambda is captured once, the values are not).
        previewBoundsProvider = {
          val screen = screenBoundsInRoot
          val preview = previewBoundsInRoot
          if (screen == null || preview == null) null
          else preview.translate(-screen.topLeft)
        },
        onZoom = transform,
      ),
  ) {
    DqtScreenContent(
      preview = preview?.asImageBitmap(),
      tables = tables,
      edits = edits,
      zoomScale = scale,
      zoomOffset = offset,
      onTransform = transform,
      onPreviewBounds = { previewBoundsInRoot = it },
      onBack = onBack,
      onOpenFamily = { openFamily = it },
      onSkip = { onContinue(bytes, scale, offset) },
      onNext = { onContinue(applied, scale, offset) },
      onResetAll = { edits = emptyMap() },
    )

    AnimatedVisibility(
      visible = openFamily != null,
      enter = slideInVertically { it },
      exit = slideOutVertically { it },
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      shownFamily?.let { family ->
        DqtBitsPanel(
          family = family,
          tables = tables,
          edits = edits,
          onToggleBit = { entry, bit ->
            val current = edits[entry.offset] ?: entry.value
            edits = edits + (entry.offset to (current xor (1 shl bit)))
          },
          onResetEntry = { entry -> edits = edits - entry.offset },
          onResetFamily = {
            val offsets = entriesFor(tables, family).mapTo(HashSet()) { it.offset }
            edits = edits.filterKeys { it !in offsets }
          },
          onClose = { openFamily = null },
        )
      }
    }
  }
}

/**
 * Stateless body of [DqtScreen]: the preview with the eight pattern tiles and
 * the skip / next row under it. Decoding, byte editing and zoom state live in
 * [DqtScreen], so this can be rendered - and previewed - on its own.
 */
@Composable
internal fun DqtScreenContent(
  preview: ImageBitmap?,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  zoomScale: Float,
  zoomOffset: Offset,
  onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
  onPreviewBounds: (Rect) -> Unit,
  onBack: () -> Unit,
  onOpenFamily: (DqtFamily) -> Unit,
  onSkip: () -> Unit,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
    onTransform(zoomChange, offsetChange)
  }

  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      Box(
        modifier = previewModifier.onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) },
        contentAlignment = Alignment.Center,
      ) {
        val bmp = preview
        if (bmp != null) {
          Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.dqt_preview_desc),
            modifier = Modifier
              .fillMaxSize()
              .transformable(state = transformState)
              .graphicsLayer(
                scaleX = zoomScale,
                scaleY = zoomScale,
                translationX = zoomOffset.x,
                translationY = zoomOffset.y,
              ),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
          )
        }
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(title = stringResource(R.string.dqt_title), onBack = onBack)
        }
      }
    },
    controls = {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          DQT_FAMILIES.forEach { family ->
            DqtFamilyIconButton(
              family = family,
              tables = tables,
              edits = edits,
              onClick = { onOpenFamily(family) },
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            onClick = onSkip,
            modifier = Modifier.height(56.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
          ) { Text(text = stringResource(R.string.dqt_skip)) }

          if (edits.isNotEmpty()) {
            TextButton(onClick = onResetAll) {
              Text(
                text = stringResource(R.string.dqt_reset),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
          }

          Button(
            onClick = onNext,
            modifier = Modifier.weight(1f).height(56.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
          ) {
            Text(
              text = if (edits.isEmpty()) {
                stringResource(R.string.dqt_next)
              } else {
                stringResource(R.string.dqt_next_edited, edits.size)
              },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    },
  )
}

private fun familyIcon(family: DqtFamily): ImageVector = when (family.axis) {
  QuantAxis.DC -> if (family.chroma) Icons.Filled.Palette else Icons.Filled.LightMode
  QuantAxis.HORIZONTAL -> if (family.chroma) Icons.Filled.ViewStream else Icons.Filled.HorizontalSplit
  QuantAxis.VERTICAL -> if (family.chroma) Icons.Filled.ViewColumn else Icons.Filled.VerticalSplit
  QuantAxis.CHECKERED -> if (family.chroma) Icons.Filled.Texture else Icons.Filled.GridOn
}

/** One pattern icon button: icon representing the family area/type. */
@Composable
private fun DqtFamilyIconButton(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val entries = entriesFor(tables, family)
  val changed = entries.count { edits.containsKey(it.offset) }
  val enabled = entries.isNotEmpty()

  Surface(
    modifier = modifier
      .size(48.dp)
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = when {
      !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
      changed > 0 -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      changed > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
      else -> MaterialTheme.colorScheme.onSurface
    },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = familyIcon(family),
        contentDescription = stringResource(familyTitleRes(family)),
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

/**
 * The pattern panel: every quantizer of one family, one per row, under section
 * labels that say how fine the row's pattern is. Tapping a bit inside a row
 * toggles that bit of the quantizer byte, which is what gets written to the
 * file.
 *
 * This is a plain surface over the bottom of the screen rather than a modal
 * sheet, so the preview above stays interactive - pinch it to inspect the effect
 * while the panel is open.
 */
@Composable
private fun DqtBitsPanel(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onToggleBit: (QuantEntry, Int) -> Unit,
  onResetEntry: (QuantEntry) -> Unit,
  onResetFamily: () -> Unit,
  onClose: () -> Unit,
) {
  val entries = remember(tables, family) { entriesFor(tables, family) }
  // Sections are grouped by scale - the dominant frequency of the pattern -
  // coarsest first. DC has no scale at all, so it gets a single labelled group.
  val sections: List<Pair<Int?, List<QuantEntry>>> = remember(entries, family) {
    if (family.axis == QuantAxis.DC) {
      listOf(null to entries)
    } else {
      entries.groupBy { maxOf(it.u, it.v) }
        .toSortedMap()
        .map { (scale, group) -> scale to group }
    }
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(DQT_PANEL_HEIGHT_FRACTION),
    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 3.dp,
    shadowElevation = 12.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      // Drag handle: the panel is not modal, so it is worth signalling that it
      // can be pushed away again.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .width(36.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(familyTitleRes(family)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(
              R.string.dqt_sheet_counts,
              entries.size,
              stringResource(
                if (family.chroma) R.string.dqt_table_chroma else R.string.dqt_table_luma
              ),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onClose) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.common_close),
          )
        }
      }

      Text(
        text = stringResource(familyBlurbRes(family)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (family.axis != QuantAxis.DC) {
        Text(
          text = stringResource(R.string.dqt_sheet_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onResetFamily) {
        Icon(
          imageVector = Icons.Filled.Refresh,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = stringResource(R.string.dqt_sheet_reset))
      }

      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        sections.forEach { (scale, group) ->
          item(key = "header-$scale") {
            Text(
              text = if (scale == null) {
                stringResource(R.string.dqt_section_dc)
              } else {
                stringResource(
                  if (family.axis == QuantAxis.CHECKERED) {
                    R.string.dqt_section_cells
                  } else {
                    R.string.dqt_section_bands
                  },
                  stringResource(scaleWordRes(scale)),
                  sizeText(group),
                )
              },
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
          }
          items(group, key = { it.offset }) { entry ->
            QuantizerRow(
              entry = entry,
              value = edits[entry.offset] ?: entry.value,
              edited = edits.containsKey(entry.offset),
              onToggleBit = { bit -> onToggleBit(entry, bit) },
              onReset = { onResetEntry(entry) },
            )
          }
        }
        item { Spacer(Modifier.height(24.dp)) }
      }
    }
  }
}

/**
 * One quantizer row: which coefficient it is, the value it has now against the
 * value in the file, and its bits as tappable cells.
 */
@Composable
private fun QuantizerRow(
  entry: QuantEntry,
  value: Int,
  edited: Boolean,
  onToggleBit: (Int) -> Unit,
  onReset: () -> Unit,
) {
  Surface(
    color = if (edited) {
      MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
      MaterialTheme.colorScheme.surfaceContainerLowest
    },
    shape = RoundedCornerShape(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.dqt_row_coeff, entry.coefficient, entry.u, entry.v),
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = if (edited) {
            stringResource(R.string.dqt_row_value_edited, entry.value, value)
          } else {
            stringResource(R.string.dqt_row_value, entry.value)
          },
          style = MaterialTheme.typography.labelMedium,
          fontFamily = FontFamily.Monospace,
          color = if (edited) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
        if (edited) {
          Spacer(Modifier.width(4.dp))
          TextButton(onClick = onReset) {
            Text(
              text = stringResource(R.string.dqt_row_reset),
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (bit in 0 until entry.bitCount) {
          BitCell(
            bit = bit,
            set = (value shr bit) and 1 == 1,
            onClick = { onToggleBit(bit) },
            modifier = Modifier.weight(1f),
          )
        }
      }

      Text(
        text = if (edited) directionText(entry.value, value) else
          stringResource(R.string.dqt_row_tap_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

/** What the edit did to that frequency (see DQT.md section 2). */
@Composable
private fun directionText(before: Int, after: Int): String = when {
  after == 0 -> stringResource(R.string.dqt_dir_zero)
  after > before -> stringResource(R.string.dqt_dir_coarser)
  after < before -> stringResource(R.string.dqt_dir_finer)
  else -> stringResource(R.string.dqt_dir_same)
}

/** One bit of a quantizer; tapping flips it. */
@Composable
private fun BitCell(
  bit: Int,
  set: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .height(34.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(
        if (set) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.surfaceContainerHighest
        },
      )
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = if (set) "1" else "0",
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (set) FontWeight.Bold else FontWeight.Normal,
        color = if (set) {
          MaterialTheme.colorScheme.onPrimary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
      Text(
        text = stringResource(R.string.dqt_bit_label, bit),
        style = MaterialTheme.typography.labelSmall,
        color = if (set) {
          MaterialTheme.colorScheme.onPrimary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
      )
    }
  }
}

// --- Previews ---------------------------------------------------------------

/**
 * A stand-in quantization payload for the previews: two 8-bit tables with the
 * q92 values the batch in DQT.md was made with, so the tiles and sections show
 * realistic counts without needing a JPEG fixture.
 */
private fun previewTables(): List<QuantTable> {
  val luma = intArrayOf(
    3, 2, 2, 2, 2, 2, 3, 2, 2, 2, 3, 3, 3, 3, 4, 6, 4, 4, 4, 4, 4, 8, 6, 6, 5,
    6, 9, 8, 10, 10, 9, 8, 9, 9, 10, 12, 15, 12, 10, 11, 14, 11, 9, 9, 13, 17,
    13, 14, 15, 16, 16, 17, 16, 10, 12, 18, 19, 18, 16, 19, 15, 16, 16, 16,
  )
  val chromaHead = intArrayOf(3, 3, 3, 4, 3, 4, 8, 4, 4, 8, 16, 11, 9, 11)
  val chroma = IntArray(64) { if (it < chromaHead.size) chromaHead[it] else 16 }
  val data = ByteArray(130)
  data[0] = 0x00
  for (i in 0 until 64) data[1 + i] = luma[i].toByte()
  data[65] = 0x01
  for (i in 0 until 64) data[66 + i] = chroma[i].toByte()
  val structure = JpegStructure(
    dqt = listOf(ByteSpan(0, 65), ByteSpan(65, 130)),
    dht = emptyList(),
    scan = emptyList(),
    rst = emptyList(),
    scans = emptyList(),
    geometry = emptyList(),
    frame = null,
    fatal = emptyList(),
    metadata = emptyList(),
  )
  return QuantTables.parse(data, structure)
}

@Composable
private fun DqtScreenPreviewShell(edits: Map<Int, Int>) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      DqtScreenContent(
        preview = null,
        tables = previewTables(),
        edits = edits,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onTransform = { _, _ -> },
        onPreviewBounds = {},
        onBack = {},
        onOpenFamily = {},
        onSkip = {},
        onNext = {},
        onResetAll = {},
      )
    }
  }
}

@Preview(name = "DQT", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtScreenPreview() {
  DqtScreenPreviewShell(edits = emptyMap())
}

@Preview(name = "DQT - edited", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtScreenEditedPreview() {
  // Two edited quantizers, so the tile badges and the next label show.
  DqtScreenPreviewShell(edits = mapOf(9 to 3, 41 to 130))
}

@Preview(name = "DQT - small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun DqtScreenSmallPreview() {
  DqtScreenPreviewShell(edits = emptyMap())
}
