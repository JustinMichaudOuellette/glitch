package ca.justinmo.glitch

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-edge cap for the preview on this screen. */
private const val DQT_PREVIEW_CAP_PX = 1400

/**
 * Panel heights as a fraction of the window. The panel opens at its tallest -
 * half the window - and can be dragged down to [DQT_PANEL_SHORT_FRACTION], or
 * past [DQT_PANEL_DISMISS_FRACTION] to close.
 *
 * [DQT_PANEL_DRAG_MIN_FRACTION] is deliberately below [DQT_PANEL_DISMISS_FRACTION],
 * otherwise the clamp would stop the drag before it could ever dismiss.
 */
private const val DQT_PANEL_MAX_FRACTION = 0.5f
private const val DQT_PANEL_SHORT_FRACTION = 0.3f
private const val DQT_PANEL_DRAG_MIN_FRACTION = 0.08f
private const val DQT_PANEL_DISMISS_FRACTION = 0.2f

/**
 * Fling speed (px/s, downwards positive) that overrides the position when the
 * finger lifts: faster than this one way closes the panel, the other way opens
 * it fully. Below it the nearest stop wins.
 */
private const val DQT_PANEL_FLING_VELOCITY = 900f

/** The stops a released drag snaps to: the short one, and the half-window top. */
private val DQT_PANEL_STOPS = listOf(
  DQT_PANEL_SHORT_FRACTION,
  DQT_PANEL_MAX_FRACTION,
)

/**
 * Height the panel assumes when its parent gives it no bound at all - which is
 * what a preview (and any unbounded container) reports, and where multiplying an
 * infinite height by a fraction would blow up the layout.
 */
private val DQT_PANEL_FALLBACK_HEIGHT = 640.dp

/**
 * Which set of quantizers a tile opens: an effect family ([QuantAxis]) plus
 * whether it lives in the chroma table. `DC + chroma` is saturation and
 * `DC + luma` is brightness; "color" in the labels always means chroma.
 */
internal data class DqtFamily(val axis: QuantAxis, val chroma: Boolean, val tint: Boolean = false)

/**
 * The tiles on this screen in display order: the two that move the whole picture
 * first - they are by far the loudest - then each texture in luma and again in
 * chroma.
 */
internal val DQT_FAMILIES: List<DqtFamily> = listOf(
  DqtFamily(QuantAxis.DC, chroma = false, tint = true),
  DqtFamily(QuantAxis.DC, chroma = true, tint = true),
  DqtFamily(QuantAxis.HORIZONTAL, chroma = false,),
  DqtFamily(QuantAxis.HORIZONTAL, chroma = true,),
  DqtFamily(QuantAxis.VERTICAL, chroma = false,),
  DqtFamily(QuantAxis.VERTICAL, chroma = true,),
  DqtFamily(QuantAxis.CHECKERED, chroma = false,),
  DqtFamily(QuantAxis.CHECKERED, chroma = true,),
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
 * Zoom and pan for the whole screen.
 *
 * The other screens put `transformable` on the image, which works because
 * nothing sits on top of it. Here the pattern row is drawn *over* the photo and
 * the bits panel slides over both, and a gesture that lands on either of them
 * never reaches the image - so this sits on the screen root instead, sees every
 * gesture first (a child cannot hide events from its parent) and decides:
 *
 * * **two or more fingers** zoom and pan, wherever they land - including on the
 *   pattern row and on the open panel;
 * * **one finger** pans only when the gesture starts on the photo and outside
 *   the panel, so the panel's list still scrolls and its buttons still tap. A
 *   tap is never consumed, because panning only starts past the touch slop.
 */
private fun Modifier.dqtTransformGestures(
  previewBoundsProvider: () -> Rect?,
  panelBoundsProvider: () -> Rect?,
  onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
  val slop = viewConfiguration.touchSlop
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val preview = previewBoundsProvider()
    val panel = panelBoundsProvider()
    val panAllowed = preview != null && preview.contains(down.position) &&
      (panel == null || !panel.contains(down.position))

    var lastSpan = 0f
    var lastCentroid = Offset.Zero
    var pansBy = 0f
    var panning = false
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val pressed = event.changes.filter { it.pressed }
      if (pressed.isEmpty()) break

      if (pressed.size >= 2) {
        val centroid = pressed.fold(Offset.Zero) { sum, change -> sum + change.position } /
          pressed.size.toFloat()
        val span = pressed.fold(0f) { sum, change ->
          sum + (change.position - centroid).getDistance()
        } / pressed.size
        if (lastSpan > 0f && span > 0f) {
          onTransform((span / lastSpan).coerceIn(0.5f, 2f), centroid - lastCentroid)
        }
        lastSpan = span
        lastCentroid = centroid
        panning = false
        pressed.forEach { it.consume() }
      } else {
        lastSpan = 0f
        val change = pressed.first()
        if (!panAllowed) continue
        val delta = change.position - change.previousPosition
        pansBy += delta.getDistance()
        if (!panning && pansBy > slop) panning = true
        if (panning && delta != Offset.Zero) {
          onTransform(1f, delta)
          change.consume()
        }
      }
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
  // one it showed; its bounds are dropped with it, so a closed panel cannot go
  // on blocking pans over the photo.
  var shownFamily by remember { mutableStateOf<DqtFamily?>(null) }
  var previewBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var panelBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var screenBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  LaunchedEffect(openFamily) {
    if (openFamily == null) panelBoundsInRoot = null
  }

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
      .dqtTransformGestures(
        // Read through the state holders, so the bounds are current whenever a
        // gesture arrives (this lambda is captured once, the values are not).
        previewBoundsProvider = {
          val screen = screenBoundsInRoot
          val preview = previewBoundsInRoot
          if (screen == null || preview == null) null
          else preview.translate(-screen.topLeft)
        },
        panelBoundsProvider = {
          val screen = screenBoundsInRoot
          val panel = panelBoundsInRoot
          if (screen == null || panel == null) null
          else panel.translate(-screen.topLeft)
        },
        onTransform = transform,
      ),
  ) {
    DqtScreenContent(
      preview = preview?.asImageBitmap(),
      tables = tables,
      edits = edits,
      zoomScale = scale,
      zoomOffset = offset,
      onPreviewBounds = { previewBoundsInRoot = it },
      onBack = onBack,
      // Set the shown family with the open one, so the panel is created on the
      // tapped family rather than reusing the page it was left on.
      onOpenFamily = { family ->
        shownFamily = family
        openFamily = family
      },
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
          onValueChange = { entry, value ->
            // Setting a quantizer back to the value the file already has drops
            // the edit, so the counts and badges stay honest.
            edits = if (value == entry.value) {
              edits - entry.offset
            } else {
              edits + (entry.offset to value)
            }
          },
          onResetEntry = { entry -> edits = edits - entry.offset },
          onResetFamily = { target ->
            val offsets = entriesFor(tables, target).mapTo(HashSet()) { it.offset }
            edits = edits.filterKeys { it !in offsets }
          },
          onFamilyChange = { openFamily = it },
          onClose = { openFamily = null },
          onBounds = { panelBoundsInRoot = it },
        )
      }
    }
  }
}

/**
 * Stateless body of [DqtScreen]: the preview with the pattern row over its
 * bottom edge and the skip / next row centred in the space under it, laid out
 * like the resize screen. Decoding, byte editing and zoom state live in
 * [DqtScreen], so this can be rendered - and previewed - on its own.
 */
@Composable
internal fun DqtScreenContent(
  preview: ImageBitmap?,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  zoomScale: Float,
  zoomOffset: Offset,
  onPreviewBounds: (Rect) -> Unit,
  onBack: () -> Unit,
  onOpenFamily: (DqtFamily) -> Unit,
  onSkip: () -> Unit,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      BoxWithConstraints(
        modifier = previewModifier.onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) },
        contentAlignment = Alignment.TopCenter,
      ) {
        val bmp = preview
        // Height the fitted photo really occupies, so the buttons can be centred
        // in whatever is left under it - the resize screen's arrangement.
        val photoHeight = fittedPhotoHeight(bmp, maxWidth, maxHeight)
        val roomBelow = maxHeight >= TALL_WINDOW_HEIGHT && photoHeight < maxHeight

        if (bmp != null) {
          Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.dqt_preview_desc),
            modifier = Modifier
              .fillMaxSize()
              .graphicsLayer(
                scaleX = zoomScale,
                scaleY = zoomScale,
                translationX = zoomOffset.x,
                translationY = zoomOffset.y,
              ),
            contentScale = ContentScale.Fit,
            alignment = Alignment.TopCenter,
            filterQuality = FilterQuality.None,
          )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
          TitleBar(title = stringResource(R.string.dqt_title), onBack = onBack)
        }

        if (roomBelow) {
          // Pattern row over the bottom edge of the photo.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(photoHeight),
            contentAlignment = Alignment.BottomCenter,
          ) {
            DqtFamilyRow(
              tables = tables,
              edits = edits,
              onOpenFamily = onOpenFamily,
            )
          }

          // Skip / next centred in the space below the photo.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(maxHeight - photoHeight)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
          ) {
            DqtActionsRow(
              edits = edits,
              onSkip = onSkip,
              onNext = onNext,
              onResetAll = onResetAll,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val photoHeight = fittedPhotoHeight(preview, maxWidth, maxHeight)
        // Short window, or a photo that fills the height: the pattern row goes
        // over the media instead, as on the other screens.
        if (maxHeight < TALL_WINDOW_HEIGHT || photoHeight >= maxHeight) {
          DqtFamilyRow(
            tables = tables,
            edits = edits,
            onOpenFamily = onOpenFamily,
            modifier = Modifier.padding(top = 16.dp),
          )
        }
      }
    },
    controls = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val photoHeight = fittedPhotoHeight(preview, maxWidth, maxHeight)
        if (maxHeight < TALL_WINDOW_HEIGHT || photoHeight >= maxHeight) {
          DqtActionsRow(
            edits = edits,
            onSkip = onSkip,
            onNext = onNext,
            onResetAll = onResetAll,
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp, bottom = 24.dp),
          )
        }
      }
    },
  )
}

/**
 * Height the preview takes once `ContentScale.Fit` has letterboxed it inside
 * [maxWidth] x [maxHeight]. Everything below that is free for the buttons.
 */
private fun fittedPhotoHeight(bmp: ImageBitmap?, maxWidth: Dp, maxHeight: Dp): Dp =
  if (bmp != null && bmp.width > 0 && bmp.height > 0) {
    minOf(maxHeight, maxWidth * bmp.height / bmp.width)
  } else {
    maxHeight
  }

/** The eight pattern buttons, one per effect family. */
@Composable
private fun DqtFamilyRow(
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onOpenFamily: (DqtFamily) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DQT_FAMILIES.forEach { family ->
      DqtFamilyIconButton(
        family = family,
        tables = tables,
        edits = edits,
        onClick = { onOpenFamily(family) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * Skip / reset / next. Skip keeps its place on the left and next wraps its own
 * text, right aligned, so the pair reads as one leading and one trailing action.
 */
@Composable
private fun DqtActionsRow(
  edits: Map<Int, Int>,
  onSkip: () -> Unit,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
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

    Spacer(Modifier.weight(1f))

    Button(
      onClick = onNext,
      modifier = Modifier.height(56.dp),
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
    ) {
      Text(
        text = if (edits.isEmpty()) {
          stringResource(R.string.dqt_next)
        } else {
          stringResource(R.string.dqt_next_edited, edits.size)
        },
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun familyIcon(family: DqtFamily): ImageVector = when (family.axis) {
  QuantAxis.DC -> if (family.chroma) Icons.Filled.Contrast else Icons.Filled.LightMode
  QuantAxis.HORIZONTAL -> if (family.chroma) ImageVector.vectorResource(R.drawable.horizontal_color) else ImageVector.vectorResource(R.drawable.horizontal)
  QuantAxis.VERTICAL -> if (family.chroma) ImageVector.vectorResource(R.drawable.vertical_color) else ImageVector.vectorResource(R.drawable.vertical)
  QuantAxis.CHECKERED -> if (family.chroma) ImageVector.vectorResource(R.drawable.checkered_color) else ImageVector.vectorResource(R.drawable.checkered)
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
      .padding(4.dp)
      .aspectRatio(1f)
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
        modifier = Modifier.size(24.dp),
        tint = if (family.tint) LocalContentColor.current else Color.Unspecified,
      )
    }
  }
}

/**
 * The pattern panel: every quantizer of the current family, one per row, under
 * section labels that say how fine the row's pattern is. Each row is a label, a
 * slider and a reset, and swiping left or right pages through the other
 * families without leaving the panel.
 *
 * The panel is a plain surface over the bottom of the screen rather than a modal
 * sheet, so the preview above stays interactive - pinch it to inspect the effect
 * while the panel is open. It can be dragged by its handle: up to its tall stop,
 * down to its short one, and down past that to close.
 */
@Composable
private fun DqtBitsPanel(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onValueChange: (QuantEntry, Int) -> Unit,
  onResetEntry: (QuantEntry) -> Unit,
  onResetFamily: (DqtFamily) -> Unit,
  onFamilyChange: (DqtFamily) -> Unit,
  onClose: () -> Unit,
  onBounds: (Rect) -> Unit,
  initialFraction: Float = DQT_PANEL_MAX_FRACTION,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    // A preview - or any parent that leaves the height unbounded - reports an
    // infinite max height, and scaling that by the panel fraction throws. Fall
    // back to a sane window instead.
    val windowHeight = if (maxHeight.value.isFinite()) maxHeight else DQT_PANEL_FALLBACK_HEIGHT
    val windowHeightPx = with(LocalDensity.current) { windowHeight.toPx() }.coerceAtLeast(1f)

    // Where the panel sits while it is being dragged, and where it snaps to when
    // the finger lifts. Following the finger exactly (snap) and animating the
    // rest keeps the drag glued to the touch without a springy lag.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(initialFraction) }
    var targetFraction by remember { mutableFloatStateOf(initialFraction) }
    val fraction by animateFloatAsState(
      targetValue = if (dragging) dragFraction else targetFraction,
      animationSpec = if (dragging) snap() else tween(durationMillis = 200),
    )

    val pagerState = rememberPagerState(
      initialPage = DQT_FAMILIES.indexOf(family).coerceAtLeast(0),
    ) { DQT_FAMILIES.size }
    // Land on the family that was tapped. `initialPage` only applies the first
    // time this pager is created, and the panel stays composed while it slides
    // out (and keeps the last family it showed), so without this an already
    // built pager would open on whatever page it was left on.
    LaunchedEffect(family) {
      val index = DQT_FAMILIES.indexOf(family)
      if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    // Report the settled page, so the screen's idea of the open family keeps up
    // with the swipes (and closing reopens where the user left off).
    LaunchedEffect(pagerState.currentPage) {
      onFamilyChange(DQT_FAMILIES[pagerState.currentPage])
    }

    // One drag for every grab target: the handle, the page header, and (through
    // the nested-scroll connection below) the quantizer list itself.
    val dragState = rememberDraggableState { delta ->
      dragFraction = (dragFraction - delta / windowHeightPx)
        .coerceIn(DQT_PANEL_DRAG_MIN_FRACTION, DQT_PANEL_MAX_FRACTION)
    }
    /**
     * Where the panel goes when the finger lifts: a flick decides it, otherwise
     * the nearest stop. [velocityY] is in px/s, positive downwards.
     */
    fun settleDrag(velocityY: Float = 0f) {
      when {
        velocityY > DQT_PANEL_FLING_VELOCITY -> {
          // Flicked down: leave, from whatever height it was at. Freeze at that
          // height first, so it slides away at that size instead of springing
          // back up on the way out.
          targetFraction = dragFraction
          onClose()
        }
        velocityY < -DQT_PANEL_FLING_VELOCITY -> {
          // Flicked up: open as far as it goes.
          targetFraction = DQT_PANEL_MAX_FRACTION
        }
        dragFraction < DQT_PANEL_DISMISS_FRACTION -> {
          targetFraction = dragFraction
          onClose()
        }
        else -> {
          // Neither flick nor past the dismissal height: snap to the nearest stop.
          targetFraction = DQT_PANEL_STOPS.minBy { stop -> kotlin.math.abs(stop - dragFraction) }
        }
      }
    }
    val dragModifier = Modifier.draggable(
      orientation = Orientation.Vertical,
      state = dragState,
      onDragStarted = {
        dragFraction = fraction
        dragging = true
      },
      onDragStopped = { velocity ->
        dragging = false
        settleDrag(velocity)
      },
    )
    // A scrollable child always beats a parent's own drag detector, so a drag
    // inside the list cannot be taken by [dragModifier]. This instead takes the
    // scroll the list could not use: while the list has room it consumes
    // everything, and once it is at its end the leftover arrives here and moves
    // the panel - which is what makes the sheet draggable from anywhere on it.
    val listDrag = object : NestedScrollConnection {
      override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        // Only a finger moves the panel. Scroll left over from a *fling* is the
        // list arriving at its end with momentum to spare - a flick from the
        // bottom of the list back up to the top must not carry on into pulling
        // the sheet down, which would collapse and then dismiss it.
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y == 0f) return Offset.Zero
        dragging = true
        dragFraction = (dragFraction - available.y / windowHeightPx)
          .coerceIn(DQT_PANEL_DRAG_MIN_FRACTION, DQT_PANEL_MAX_FRACTION)
        return Offset(0f, available.y)
      }

      /**
       * Called when the finger lifts, before the list's fling runs - which is
       * why settling happens here and not in [onPostFling]: that one only fires
       * once the fling animation has finished, so a flick that left the list
       * still travelling would hold the panel at its dragged height for the
       * length of that animation before it finally closed.
       */
      override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        settleDrag(available.y)
        // The panel took the gesture; there is nothing left for the list to do.
        return available
      }

      override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // Safety net for a gesture that reached the fling phase without passing
        // through [onPreFling].
        if (dragging) {
          dragging = false
          settleDrag(available.y)
        }
        return Velocity.Zero
      }
    }

    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(windowHeight * fraction)
        .nestedScroll(listDrag)
        .onGloballyPositioned { onBounds(it.boundsInRoot()) },
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      tonalElevation = 3.dp,
      shadowElevation = 12.dp,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // Grab handle. The panel can be dragged from the header and from inside
        // the list as well, but this is the one target that always works, even
        // with the list scrolled.
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            .padding(vertical = 12.dp),
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

        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
          DqtFamilyPage(
            family = DQT_FAMILIES[page],
            tables = tables,
            edits = edits,
            onValueChange = onValueChange,
            onResetEntry = onResetEntry,
            onResetFamily = onResetFamily,
            onClose = onClose,
            headerModifier = dragModifier,
          )
        }
      }
    }
  }
}

/**
 * Everything of one family: its title, what it does, and its quantizer rows.
 *
 * [headerModifier] carries the panel drag, so the title block can be used to
 * move the sheet - the close button rides along in the title row, where it sits
 * next to the thing it closes.
 */
@Composable
private fun DqtFamilyPage(
  family: DqtFamily,
  tables: List<QuantTable>,
  edits: Map<Int, Int>,
  onValueChange: (QuantEntry, Int) -> Unit,
  onResetEntry: (QuantEntry) -> Unit,
  onResetFamily: (DqtFamily) -> Unit,
  onClose: () -> Unit,
  headerModifier: Modifier,
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

  Column(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(headerModifier)) {
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
          Text(
            text = stringResource(familyBlurbRes(family)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Top)) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.common_close),
          )
        }
      }
      if (family.axis != QuantAxis.DC) {
        Text(
          text = stringResource(R.string.dqt_sheet_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { onResetFamily(family) }) {
        Icon(
          imageVector = Icons.Filled.Refresh,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = stringResource(R.string.dqt_sheet_reset))
      }
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
              modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
            )
          }
          items(group, key = { it.offset }) { entry ->
            QuantizerRow(
              entry = entry,
              value = edits[entry.offset] ?: entry.value,
              edited = edits.containsKey(entry.offset),
              onValueChange = { value -> onValueChange(entry, value) },
              onReset = { onResetEntry(entry) },
            )
          }
        }
        item { Spacer(Modifier.height(24.dp)) }
      }
    }
}

/**
 * One quantizer in a single row: a short label, a slider over every value the
 * byte can hold (0-255 for the usual 8-bit table), the value it is set to, and a
 * reset button once it differs from the file.
 *
 * Dragging right makes the quantizer coarser - that frequency is stored more
 * roughly and rings harder - and dragging to 0 deletes it outright, which is why
 * the value turns red there.
 */
@Composable
private fun QuantizerRow(
  entry: QuantEntry,
  value: Int,
  edited: Boolean,
  onValueChange: (Int) -> Unit,
  onReset: () -> Unit,
) {
  val maxValue = (1 shl entry.bitCount) - 1
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 2.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Slider(
      value = value.toFloat(),
      onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maxValue)) },
      valueRange = 0f..maxValue.toFloat(),
      modifier = Modifier.weight(1f).padding(start = 16.dp),
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
      ),
    )

    Text(
      text = value.toString(),
      style = MaterialTheme.typography.labelMedium,
      fontFamily = FontFamily.Monospace,
      textAlign = TextAlign.End,
      // Wide enough for a 16-bit table's five digits.
      modifier = Modifier.width(32.dp),
      maxLines = 1,
      color = when {
        value == 0 -> MaterialTheme.colorScheme.error
        edited -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      },
    )

    if (edited) {
      IconButton(onClick = onReset, modifier = Modifier.size(48.dp)) {
        Icon(
          imageVector = Icons.Filled.Refresh,
          // Use LocalInspectionMode to provide a fallback string during Compose Previews
          // to bypass any potential out-of-sync layoutlib/Preview cache NoSuchFieldError issues.
          contentDescription = if (LocalInspectionMode.current) {
            "reset to ${entry.value}"
          } else {
            stringResource(R.string.dqt_row_reset_to, entry.value)
          },
        )
      }
    } else {
      // Same footprint as the button, so the sliders stay lined up.
      Spacer(Modifier.size(48.dp))
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
        // The stand-in photo other screens preview with: without it the layout
        // falls back to the no-room-below arrangement, which is not what a real
        // photo looks like.
        preview = ImageBitmap.imageResource(R.drawable.example),
        tables = previewTables(),
        edits = edits,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
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

/**
 * The bits panel where it really sits: over the bottom of the photo, on a family
 * with every scale. [fraction] stands in for the drag position, so the stops can
 * be checked without a finger.
 */
@Composable
private fun DqtBitsPanelPreviewShell(
  family: DqtFamily,
  edits: Map<Int, Int>,
  fraction: Float = DQT_PANEL_MAX_FRACTION,
) {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize()) {
        Image(
          bitmap = ImageBitmap.imageResource(R.drawable.example),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        // Bottom-aligned, the way AnimatedVisibility places it on the screen.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
          DqtBitsPanel(
            family = family,
            tables = previewTables(),
            edits = edits,
            onValueChange = { _, _ -> },
            onResetEntry = {},
            onResetFamily = {},
            onFamilyChange = {},
            onClose = {},
            onBounds = {},
            initialFraction = fraction,
          )
        }
      }
    }
  }
}

@Preview(name = "DQT panel - checkered", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelCheckeredPreview() {
  // Offsets are into the stand-in payload: luma coefficient 4 (offset 5) set to
  // 130, coefficient 12 (offset 13) zeroed, so both a changed row and the
  // deleted-frequency colour show.
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.CHECKERED, chroma = false,),
    edits = mapOf(5 to 130, 13 to 0),
  )
}

@Preview(name = "DQT panel - saturation", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelDcPreview() {
  // Chroma DC (table 1, coefficient 0) - the single-row case.
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.DC, chroma = true,),
    edits = mapOf(66 to 40),
  )
}

@Preview(name = "DQT panel - short stop", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelShortPreview() {
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.VERTICAL, chroma = false,),
    // Luma coefficient 5 (2,0) - in the vertical family, so the row shows edited.
    edits = mapOf(6 to 34),
    fraction = DQT_PANEL_SHORT_FRACTION,
  )
}

@Preview(name = "DQT panel - max height", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtBitsPanelMaxPreview() {
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.HORIZONTAL, chroma = true,),
    // Chroma coefficient 2 (0,1) - the first horizontal row of the chroma table.
    edits = mapOf(68 to 96),
    fraction = DQT_PANEL_MAX_FRACTION,
  )
}

@Preview(name = "DQT panel - small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun DqtBitsPanelSmallPreview() {
  DqtBitsPanelPreviewShell(
    family = DqtFamily(QuantAxis.HORIZONTAL, chroma = false,),
    edits = mapOf(3 to 19),
  )
}
