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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.jpeg.CensusBlock
import ca.justinmo.glitch.jpeg.HuffmanAlternative
import ca.justinmo.glitch.jpeg.HuffmanAlternatives
import ca.justinmo.glitch.jpeg.HuffmanBehaviour
import ca.justinmo.glitch.jpeg.HuffmanCensus
import ca.justinmo.glitch.jpeg.HuffmanCensusReader
import ca.justinmo.glitch.jpeg.HuffmanSymbol
import ca.justinmo.glitch.jpeg.HuffmanTable
import ca.justinmo.glitch.jpeg.HuffmanTables
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.ui.theme.GlitchTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Long-edge cap for the preview on this screen, as on the DQT screen. */
private const val DHT_PREVIEW_CAP_PX = 1400

/**
 * Panel heights as a fraction of the window, mirroring the DQT screen so the two
 * behave the same way: opens at [DHT_PANEL_MAX_FRACTION], can be dragged down to
 * [DHT_PANEL_SHORT_FRACTION], and past [DHT_PANEL_DISMISS_FRACTION] to close.
 */
private const val DHT_PANEL_MAX_FRACTION = 0.66f
private const val DHT_PANEL_SHORT_FRACTION = 0.35f
private const val DHT_PANEL_DRAG_MIN_FRACTION = 0.08f
private const val DHT_PANEL_DISMISS_FRACTION = 0.22f
private const val DHT_PANEL_FLING_VELOCITY = 900f
private val DHT_PANEL_STOPS = listOf(DHT_PANEL_SHORT_FRACTION, DHT_PANEL_MAX_FRACTION)
private val DHT_PANEL_FALLBACK_HEIGHT = 640.dp

/**
 * How long a finger has to stay down on a behaviour chip before it counts as a
 * peek rather than a tap. Short enough to feel immediate, long enough that a
 * slightly slow tap is not mistaken for one.
 */
private const val HOLD_TO_PEEK_MS = 220L

/**
 * Where the tiers split, as a share of that table's blocks.
 *
 * These are the boundary the sample batch justifies: the destructive edits are
 * the frequently-used codes. `DHT.md`'s appendix measures AC luma's first 32
 * symbols (the ones every block uses) at 96% `bad`, while the codes past #64 -
 * the ones a photo barely touches - come in at 32% and below. One percent is
 * "a mark in under one block in a hundred"; twenty percent is "a fifth of the
 * picture", past which nothing stays subtle.
 */
internal const val TOUCH_UP_MAX_IMPACT = 0.01f
internal const val TEXTURE_MAX_IMPACT = 0.20f

/**
 * Which Huffman table a tile opens: its class (DC or AC) and whether it lives in
 * the chroma set. The AC tables carry the run/size pairs that code the block
 * detail; the DC tables carry the per-block level as a bit count, which is why
 * they are the heavy ones.
 */
internal data class DhtFamily(val tableClass: Int, val chroma: Boolean)

/**
 * The tiles in display order: the two detail tables first, then the two level
 * tables, which the screen keeps apart because they are a different kind of tool.
 */
internal val DHT_FAMILIES: List<DhtFamily> = listOf(
  DhtFamily(tableClass = 1, chroma = false),
  DhtFamily(tableClass = 1, chroma = true),
  DhtFamily(tableClass = 0, chroma = false),
  DhtFamily(tableClass = 0, chroma = true),
)

/** The level tables: every edit in them puts the rest of the scan out of step. */
internal fun isHeavyFamily(family: DhtFamily): Boolean = family.tableClass == 0

/** The table a family stands for, or null when the file does not carry it. */
internal fun tableFor(tables: List<HuffmanTable>, family: DhtFamily): HuffmanTable? =
  tables.firstOrNull { it.tableClass == family.tableClass && it.isChroma == family.chroma }

/** Title of a tile, e.g. "Luma detail". */
private fun familyTitleRes(family: DhtFamily): Int = when {
  family.tableClass == 1 && !family.chroma -> R.string.dht_family_luma_detail
  family.tableClass == 0 && !family.chroma -> R.string.dht_family_luma_level
  family.tableClass == 1 -> R.string.dht_family_colour_detail
  else -> R.string.dht_family_colour_level
}

/** One line on what the table's symbols code. */
private fun familyBlurbRes(family: DhtFamily): Int = when {
  family.tableClass == 1 && !family.chroma -> R.string.dht_blurb_luma_detail
  family.tableClass == 0 && !family.chroma -> R.string.dht_blurb_luma_level
  family.tableClass == 1 -> R.string.dht_blurb_colour_detail
  else -> R.string.dht_blurb_colour_level
}

/** The icon a tile shows: a texture pattern for detail, a level glyph for DC. */
@Composable
internal fun dhtFamilyIcon(family: DhtFamily): ImageVector = when {
  family.tableClass == 1 && !family.chroma -> ImageVector.vectorResource(R.drawable.checkered)
  family.tableClass == 1 -> ImageVector.vectorResource(R.drawable.checkered_color)
  family.tableClass == 0 && !family.chroma -> Icons.Filled.LightMode
  else -> Icons.Filled.Contrast
}

/**
 * Screen between [DqtScreen] and [EditScreen]: rewrite what the Huffman codes
 * mean.
 *
 * The quantization screen is a set of dials - a quantizer is one number, and
 * bigger is reliably coarser. This region is not like that, and the screen is
 * built around the difference. A Huffman symbol is a *code book entry*: changing
 * it changes what every block that used that code decodes to, and the sample
 * batch measured the shape of that space - 66% of the edits do nothing at all,
 * 23% of them wreck the picture, and 7.6% are worth having. Handing the user 162
 * sliders in code order is a 92% disappointment machine: AC luma's first 32
 * symbols, the ones every block uses, measured 96% destructive.
 *
 * So the panel leads with the **outcome** instead:
 *
 *  * every code's reach is **predicted** from the census (`occurrences / blocks`)
 *    and the list is grouped by it - touch-ups, texture, and a gated section for
 *    the codes that rewrite the picture;
 *  * a code is edited by choosing a **named behaviour** ("ends the block", "16
 *    zeros", "value +1 bit") rather than a byte value, because only a handful of
 *    the 256 values say anything a user can picture;
 *  * **hold a chip to peek**: the preview shows that edit while the finger is
 *    down and puts it back when it lifts, so exploring costs nothing;
 *  * the exact byte is still there, one tap away, behind "custom".
 */
@Composable
internal fun DhtScreen(
  bytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
) {
  val structure = remember(bytes) { JpegParser.parse(bytes) }
  val tables = remember(bytes, structure) { HuffmanTables.parse(bytes, structure) }

  // Symbol byte offset -> the value the user set. Only touched bytes are in
  // here, so everything else in the file stays exactly as it was.
  var edits by remember(bytes) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
  // The edit under a held finger: shown in the preview, never committed.
  var peek by remember(bytes) { mutableStateOf<Pair<Int, Int>?>(null) }
  var openFamily by remember(bytes) { mutableStateOf<DhtFamily?>(null) }
  var showInfo by remember(bytes) { mutableStateOf(false) }
  var onlyUsed by remember(bytes) { mutableStateOf(true) }
  var preview by remember(bytes) { mutableStateOf<Bitmap?>(null) }
  var previewBroken by remember(bytes) { mutableStateOf(false) }

  // Which codes the photo actually uses, and why that could not be counted.
  var census by remember(bytes) { mutableStateOf<HuffmanCensus?>(null) }
  var censusBlock by remember(bytes) { mutableStateOf<CensusBlock?>(null) }

  var scale by remember { mutableFloatStateOf(initialScale) }
  var offset by remember { mutableStateOf(initialOffset) }

  var shownFamily by remember { mutableStateOf<DhtFamily?>(null) }
  var previewBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var panelBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var screenBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  LaunchedEffect(openFamily) {
    if (openFamily == null) {
      panelBoundsInRoot = null
      peek = null
    }
  }

  // What the preview is decoded from: the committed edits plus whatever is under
  // the finger right now.
  val applied = remember(bytes, edits, peek) {
    val all = if (peek == null) edits else edits + peek!!
    if (all.isEmpty()) bytes else bytes.copyOf().also { HuffmanTables.applyValues(it, all) }
  }
  // What the screen would hand on: the committed edits only.
  val committed = remember(bytes, edits) {
    if (edits.isEmpty()) bytes else bytes.copyOf().also { HuffmanTables.applyValues(it, edits) }
  }

  LaunchedEffect(applied, peek) {
    val decoded = withContext(Dispatchers.Default) { decodeSampled(applied, DHT_PREVIEW_CAP_PX) }
    preview = decoded
    // A level symbol of 16 or more is the one edit that can make the result stop
    // opening - say so rather than showing nothing.
    previewBroken = decoded == null && (edits.isNotEmpty() || peek != null)
  }

  // The census reads the file as it arrived: symbol *values* are what the user
  // edits, and the codes keep occurring exactly as often however they are
  // rewritten. It needs the tables as parsed, so it runs once per image.
  LaunchedEffect(bytes, tables) {
    val block = if (tables.isEmpty()) CensusBlock.NO_TABLES else HuffmanCensusReader.blockFor(structure)
    if (block != null) {
      censusBlock = block
      census = null
      return@LaunchedEffect
    }
    val counted = withContext(Dispatchers.Default) { HuffmanCensusReader.read(bytes, structure, tables) }
    // A null here means the walk lost the stream partway through, which is worth
    // saying rather than silently falling back to the code-length ordering.
    censusBlock = if (counted == null) CensusBlock.UNDECODABLE else null
    census = counted
  }

  BackHandler(enabled = openFamily != null) { openFamily = null }
  BackHandler(enabled = showInfo) { showInfo = false }

  val transform: (Float, Offset) -> Unit = { zoomChange, panChange ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    offset += panChange
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onGloballyPositioned { screenBoundsInRoot = it.boundsInRoot() }
      .then(
        if (showInfo) {
          Modifier
        } else {
          Modifier.previewTransformGestures(
            // Read through the state holders: this lambda is captured once, the
            // bounds are not.
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
          )
        },
      ),
  ) {
    DhtScreenContent(
      preview = preview?.asImageBitmap(),
      tables = tables,
      edits = edits,
      peeked = peek?.first,
      brokenEdit = previewBroken,
      zoomScale = scale,
      zoomOffset = offset,
      onPreviewBounds = { previewBoundsInRoot = it },
      onBack = onBack,
      onInfo = { showInfo = true },
      onOpenFamily = { family ->
        shownFamily = family
        openFamily = family
      },
      onSkip = { onContinue(bytes, scale, offset) },
      onNext = { onContinue(committed, scale, offset) },
      onResetAll = { edits = emptyMap() },
    )

    AnimatedVisibility(
      visible = openFamily != null,
      enter = slideInVertically { it },
      exit = slideOutVertically { it },
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      shownFamily?.let { family ->
        // Only open the panel when the tapped tile has a table behind it; the
        // pages themselves resolve their own table, so a swipe onto a tile this
        // file does not carry shows that instead of the last one's symbols.
        if (tableFor(tables, family) != null) {
          DhtTablePanel(
            family = family,
            tables = tables,
            edits = edits,
            peeked = peek,
            census = census,
            censusBlock = censusBlock,
            onlyUsed = onlyUsed,
            onOnlyUsedChange = { onlyUsed = it },
            onPeek = { symbol, value -> peek = symbol.offset to value },
            onPeekEnd = { peek = null },
            onValueChange = { symbol, value ->
              // Setting a symbol back to the value the file already has drops the
              // edit, so the counts and badges stay honest.
              peek = null
              edits = if (value == symbol.value) {
                edits - symbol.offset
              } else {
                edits + (symbol.offset to value)
              }
            },
            onResetSymbol = { symbol -> edits = edits - symbol.offset },
            onResetTable = { target ->
              val offsets = target.entries.mapTo(HashSet()) { it.offset }
              edits = edits.filterKeys { it !in offsets }
            },
            onFamilyChange = { openFamily = it },
            onClose = { openFamily = null },
            onBounds = { panelBoundsInRoot = it },
          )
        }
      }
    }

    // Drawn last, so the explanation covers the panel and the tile rows.
    if (showInfo) {
      DhtInfoScreen(onClose = { showInfo = false })
    }
  }
}

/**
 * Stateless body of [DhtScreen]: the preview with the table tiles over its
 * bottom edge and the skip / next row under it, laid out exactly like the DQT
 * screen. Decoding, byte editing and the code census live in [DhtScreen], so
 * this can be rendered - and previewed - on its own.
 */
@Composable
internal fun DhtScreenContent(
  preview: ImageBitmap?,
  tables: List<HuffmanTable>,
  edits: Map<Int, Int>,
  zoomScale: Float,
  zoomOffset: Offset,
  onPreviewBounds: (Rect) -> Unit,
  onBack: () -> Unit,
  onInfo: () -> Unit,
  onOpenFamily: (DhtFamily) -> Unit,
  onSkip: () -> Unit,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  modifier: Modifier = Modifier,
  brokenEdit: Boolean = false,
  peeked: Int? = null,
) {
  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      BoxWithConstraints(
        modifier = previewModifier.onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) },
        contentAlignment = Alignment.TopCenter,
      ) {
        val bmp = preview
        val photoHeight = fittedMediaHeight(bmp, maxWidth, maxHeight)
        val roomBelow = maxHeight >= TALL_WINDOW_HEIGHT && photoHeight < maxHeight

        if (bmp != null) {
          Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.dht_preview_desc),
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
          TitleBar(
            title = stringResource(R.string.dht_title),
            onBack = onBack,
            onInfo = onInfo,
          )
        }

        // Two banners at most, and a peek takes precedence: it is transient and
        // the user is looking at it right now.
        if (peeked != null) {
          Banner(
            text = stringResource(R.string.dht_peeking),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        } else if (brokenEdit) {
          Banner(
            text = stringResource(R.string.dht_broken_edit),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
          )
        }

        if (roomBelow) {
          Box(
            modifier = Modifier.fillMaxWidth().height(photoHeight),
            contentAlignment = Alignment.BottomCenter,
          ) {
            DhtFamilyRow(tables = tables, edits = edits, onOpenFamily = onOpenFamily)
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(maxHeight - photoHeight)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
          ) {
            DhtActionsRow(
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
        val photoHeight = fittedMediaHeight(preview, maxWidth, maxHeight)
        if (maxHeight < TALL_WINDOW_HEIGHT || photoHeight >= maxHeight) {
          DhtFamilyRow(
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
        val photoHeight = fittedMediaHeight(preview, maxWidth, maxHeight)
        if (maxHeight < TALL_WINDOW_HEIGHT || photoHeight >= maxHeight) {
          DhtActionsRow(
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

/** A one-line banner over the preview, under the title bar. */
@Composable
private fun Banner(
  text: String,
  container: androidx.compose.ui.graphics.Color,
  content: androidx.compose.ui.graphics.Color,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 60.dp, start = 16.dp, end = 16.dp),
    shape = RoundedCornerShape(8.dp),
    color = container,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = content,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
  }
}

/** Height the preview takes once `ContentScale.Fit` has letterboxed it. */
private fun fittedMediaHeight(bmp: ImageBitmap?, maxWidth: Dp, maxHeight: Dp): Dp =
  if (bmp != null && bmp.width > 0 && bmp.height > 0) {
    minOf(maxHeight, maxWidth * bmp.height / bmp.width)
  } else {
    maxHeight
  }

/**
 * The table tiles, in two rows: the two detail tables, then the two level tables
 * under their own caption.
 *
 * The split is the point. The detail tables are where the usable edits live and
 * the level tables are a 93-100% destructive tool on the sample batch, so
 * presenting them as four equivalent tiles - as this screen first did - invites
 * exactly the mistake of landing on a wreck while looking for a touch-up.
 */
@Composable
private fun DhtFamilyRow(
  tables: List<HuffmanTable>,
  edits: Map<Int, Int>,
  onOpenFamily: (DhtFamily) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DHT_FAMILIES.filterNot { isHeavyFamily(it) }.forEach { family ->
        DhtFamilyTile(
          family = family,
          table = tableFor(tables, family),
          edits = edits,
          onClick = { onOpenFamily(family) },
          modifier = Modifier.weight(1f),
        )
      }
    }

    Spacer(Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.dht_heavy_caption),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DHT_FAMILIES.filter { isHeavyFamily(it) }.forEach { family ->
        DhtFamilyTile(
          family = family,
          table = tableFor(tables, family),
          edits = edits,
          onClick = { onOpenFamily(family) },
          modifier = Modifier.weight(1f),
          heavy = true,
        )
      }
    }
  }
}

/**
 * One table tile: an icon, the table's name, and how many of its symbols have
 * been changed. Greys out when the file has no table of that kind (a greyscale
 * file carries one DC and one AC table, so the colour tiles have nothing).
 *
 * [heavy] tiles wear the error container instead of the surface colour, so the
 * two level tables read as the different kind of tool they are.
 */
@Composable
private fun DhtFamilyTile(
  family: DhtFamily,
  table: HuffmanTable?,
  edits: Map<Int, Int>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  heavy: Boolean = false,
) {
  val changed = table?.entries?.count { edits.containsKey(it.offset) } ?: 0
  val enabled = table != null
  Surface(
    modifier = modifier
      .padding(4.dp)
      .height(72.dp)
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = when {
      !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
      changed > 0 -> MaterialTheme.colorScheme.primaryContainer
      heavy -> MaterialTheme.colorScheme.errorContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      changed > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
      heavy -> MaterialTheme.colorScheme.onErrorContainer
      else -> MaterialTheme.colorScheme.onSurface
    },
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = dhtFamilyIcon(family),
        contentDescription = null,
        modifier = Modifier.size(22.dp),
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = stringResource(familyTitleRes(family)),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
      )
      if (changed > 0) {
        Text(
          text = stringResource(R.string.dht_tile_changed, changed),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    }
  }
}

/** Skip / reset / next, the same shape as the DQT screen's action row. */
@Composable
private fun DhtActionsRow(
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
    ) { Text(text = stringResource(R.string.dht_skip)) }

    if (edits.isNotEmpty()) {
      TextButton(onClick = onResetAll) {
        Text(
          text = stringResource(R.string.dht_reset),
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
          stringResource(R.string.dht_next)
        } else {
          stringResource(R.string.dht_next_edited, edits.size)
        },
        maxLines = 1,
      )
    }
  }
}

// --- Grouping ---------------------------------------------------------------

/**
 * How far an edit reaches, as a share of that table's blocks. This is the axis
 * the panel is ordered by, because it is the one the measurements say predicts
 * the outcome.
 */
internal enum class DhtTier { TouchUp, Texture, Rewrite }

/** The tier an impact share falls in. */
internal fun tierOf(impact: Float): DhtTier = when {
  impact < TOUCH_UP_MAX_IMPACT -> DhtTier.TouchUp
  impact <= TEXTURE_MAX_IMPACT -> DhtTier.Texture
  else -> DhtTier.Rewrite
}

/**
 * One section of the symbol list.
 *
 * With a census the split is what the photo does with each code: how far the
 * edit reaches, and - behind the filter - the codes it never emits at all.
 * Without one (progressive file, or a scan the walk cannot follow) the split
 * falls back to code length, which is the same ordering the census produces.
 */
internal sealed interface DhtGroup {
  val key: String
  val entries: List<HuffmanSymbol>

  data class Tier(val tier: DhtTier, override val entries: List<HuffmanSymbol>) : DhtGroup {
    override val key: String get() = "tier-$tier"
  }

  data class Unused(override val entries: List<HuffmanSymbol>) : DhtGroup {
    override val key: String get() = "unused"
  }

  data class Length(val length: Int, override val entries: List<HuffmanSymbol>) : DhtGroup {
    override val key: String get() = "len$length"
  }
}

/**
 * The sections the panel shows, gentlest first.
 *
 * Inside a tier the list runs from the code that reaches furthest down to the
 * one that barely appears: a touch-up at 0.9% of blocks is a visible mark, one
 * at 0.001% is a speck, and the screen has no business sending the user to the
 * speck when the mark is right there.
 */
internal fun dhtGroups(
  table: HuffmanTable,
  census: HuffmanCensus?,
  onlyUsed: Boolean,
): List<DhtGroup> {
  fun byLength(): List<DhtGroup> =
    table.entriesByLength().map { (length, entries) -> DhtGroup.Length(length, entries) }

  if (census == null) return byLength()
  val used = table.entries.filter { census.uses(it) > 0 }
  // A table this photo never touches would otherwise list nothing at all; fall
  // back to the code-length ordering so there is always something to look at.
  if (used.isEmpty()) return byLength()

  val unused = table.entries.filter { census.uses(it) == 0 }
  return buildList {
    DhtTier.entries.forEach { tier ->
      val entries = used
        .filter { tierOf(census.impact(it)) == tier }
        .sortedByDescending { census.impact(it) }
      if (entries.isNotEmpty()) add(DhtGroup.Tier(tier, entries))
    }
    if (!onlyUsed && unused.isNotEmpty()) add(DhtGroup.Unused(unused))
  }
}

/** How many of [table]'s codes fall in [tier] for this photo. */
internal fun dhtTierCount(
  table: HuffmanTable,
  census: HuffmanCensus?,
  tier: DhtTier,
): Int {
  if (census == null) return 0
  return table.entries.count { census.uses(it) > 0 && tierOf(census.impact(it)) == tier }
}

// --- The symbol panel -------------------------------------------------------

/**
 * The symbol panel: every code of one table that this photo uses, one per row,
 * under the tier it belongs to.
 *
 * It is a plain surface over the bottom of the screen rather than a modal sheet,
 * so the preview above stays interactive - peek at an edit and watch the picture
 * change while the panel is open, exactly as on the DQT screen.
 */
@Composable
private fun DhtTablePanel(
  family: DhtFamily,
  tables: List<HuffmanTable>,
  edits: Map<Int, Int>,
  peeked: Pair<Int, Int>?,
  census: HuffmanCensus?,
  censusBlock: CensusBlock?,
  onlyUsed: Boolean,
  onOnlyUsedChange: (Boolean) -> Unit,
  onPeek: (HuffmanSymbol, Int) -> Unit,
  onPeekEnd: () -> Unit,
  onValueChange: (HuffmanSymbol, Int) -> Unit,
  onResetSymbol: (HuffmanSymbol) -> Unit,
  onResetTable: (HuffmanTable) -> Unit,
  onFamilyChange: (DhtFamily) -> Unit,
  onClose: () -> Unit,
  onBounds: (Rect) -> Unit,
  initialFraction: Float = DHT_PANEL_MAX_FRACTION,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    // An unbounded parent (a preview) reports an infinite height; scaling that by
    // the panel fraction throws, so fall back to a sane window.
    val windowHeight = if (maxHeight.value.isFinite()) maxHeight else DHT_PANEL_FALLBACK_HEIGHT
    val windowHeightPx = with(LocalDensity.current) { windowHeight.toPx() }.coerceAtLeast(1f)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(initialFraction) }
    var targetFraction by remember { mutableFloatStateOf(initialFraction) }
    val fraction by animateFloatAsState(
      targetValue = if (dragging) dragFraction else targetFraction,
      animationSpec = if (dragging) snap() else tween(durationMillis = 200),
    )

    val pagerState = rememberPagerState(
      initialPage = DHT_FAMILIES.indexOf(family).coerceAtLeast(0),
    ) { DHT_FAMILIES.size }
    // `initialPage` only applies the first time a pager is created, and this one
    // stays composed while it slides out, so land on the tapped tile explicitly.
    LaunchedEffect(family) {
      val index = DHT_FAMILIES.indexOf(family)
      if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    LaunchedEffect(pagerState.currentPage) {
      onFamilyChange(DHT_FAMILIES[pagerState.currentPage])
    }

    val dragState = rememberDraggableState { delta ->
      dragFraction = (dragFraction - delta / windowHeightPx)
        .coerceIn(DHT_PANEL_DRAG_MIN_FRACTION, DHT_PANEL_MAX_FRACTION)
    }
    fun settleDrag(velocityY: Float = 0f) {
      when {
        velocityY > DHT_PANEL_FLING_VELOCITY -> {
          targetFraction = dragFraction
          onClose()
        }
        velocityY < -DHT_PANEL_FLING_VELOCITY -> targetFraction = DHT_PANEL_MAX_FRACTION
        dragFraction < DHT_PANEL_DISMISS_FRACTION -> {
          targetFraction = dragFraction
          onClose()
        }
        else -> targetFraction =
          DHT_PANEL_STOPS.minBy { stop -> kotlin.math.abs(stop - dragFraction) }
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
    // A scrollable child always beats a parent's drag detector, so the list takes
    // the drag while it has room; this picks up what it could not use and moves
    // the panel, which is what makes the sheet draggable from anywhere on it.
    val listDrag = object : NestedScrollConnection {
      override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        // Only a finger moves the panel: a fling arriving at the end of the list
        // must not carry on into pulling the sheet down and dismissing it.
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y == 0f) return Offset.Zero
        dragging = true
        dragFraction = (dragFraction - available.y / windowHeightPx)
          .coerceIn(DHT_PANEL_DRAG_MIN_FRACTION, DHT_PANEL_MAX_FRACTION)
        return Offset(0f, available.y)
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        settleDrag(available.y)
        return available
      }

      override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
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
          val pageFamily = DHT_FAMILIES[page]
          val pageTable = tableFor(tables, pageFamily)
          if (pageTable == null) {
            // A greyscale file carries one DC and one AC table, so the colour
            // tiles have nothing behind them. Say that rather than showing the
            // previous page's symbols.
            Box(
              modifier = Modifier.fillMaxSize().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.dht_table_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          } else {
            DhtTablePage(
              family = pageFamily,
              table = pageTable,
              edits = edits,
              peeked = peeked,
              census = census,
              censusBlock = censusBlock,
              onlyUsed = onlyUsed,
              onOnlyUsedChange = onOnlyUsedChange,
              onPeek = onPeek,
              onPeekEnd = onPeekEnd,
              onValueChange = onValueChange,
              onResetSymbol = onResetSymbol,
              onResetTable = onResetTable,
              onClose = onClose,
              modifier = dragModifier,
            )
          }
        }
      }
    }
  }
}

/**
 * One row of the panel's list: a section heading or a code.
 *
 * The list is flattened up front rather than built with nested `items` calls so
 * that a row has an index the screen can scroll to - which is how an already
 * edited code is found again when the table is reopened.
 */
private sealed interface DhtListRow {
  val key: String

  data class Header(val group: DhtGroup) : DhtListRow {
    override val key: String get() = "h-${group.key}"
  }

  data class Code(val entry: HuffmanSymbol) : DhtListRow {
    override val key: String get() = "c-${entry.offset}"
  }
}

/** Everything of one table: what it is, what this photo does with it, the rows. */
@Composable
private fun DhtTablePage(
  family: DhtFamily,
  table: HuffmanTable,
  edits: Map<Int, Int>,
  peeked: Pair<Int, Int>?,
  census: HuffmanCensus?,
  censusBlock: CensusBlock?,
  onlyUsed: Boolean,
  onOnlyUsedChange: (Boolean) -> Unit,
  onPeek: (HuffmanSymbol, Int) -> Unit,
  onPeekEnd: () -> Unit,
  onValueChange: (HuffmanSymbol, Int) -> Unit,
  onResetSymbol: (HuffmanSymbol) -> Unit,
  onResetTable: (HuffmanTable) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier,
) {
  val groups = remember(table, census, onlyUsed) { dhtGroups(table, census, onlyUsed) }
  val rows = remember(groups) {
    groups.flatMap { group ->
      buildList {
        add(DhtListRow.Header(group))
        group.entries.forEach { add(DhtListRow.Code(it)) }
      }
    }
  }
  val listState = rememberLazyListState()
  // Opening a table that has already been edited goes straight to the first edit:
  // a code someone found and liked is exactly what they come back for, and it
  // can be dozens of rows down.
  LaunchedEffect(table) {
    val first = rows.indexOfFirst {
      it is DhtListRow.Code && edits.containsKey(it.entry.offset)
    }
    if (first > 0) listState.scrollToItem(first)
  }
  val changed = table.entries.count { edits.containsKey(it.offset) }
  val usedCount = census?.usedSymbolCount(table)

  Column(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(modifier)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(familyTitleRes(family)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = if (usedCount != null) {
              stringResource(R.string.dht_sheet_counts, usedCount, table.symbolCount)
            } else {
              stringResource(R.string.dht_sheet_symbols, table.symbolCount)
            },
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

      // The level tables get the warning instead of the usual hint: every edit in
      // them changes how many bits every later block is read at.
      if (isHeavyFamily(family)) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.errorContainer,
        ) {
          Text(
            text = stringResource(R.string.dht_heavy_banner),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }

      Text(
        text = stringResource(
          when {
            census != null -> R.string.dht_sheet_hint_census
            censusBlock != null && censusBlock != CensusBlock.NO_TABLES ->
              censusBlockHintRes(censusBlock)
            else -> R.string.dht_sheet_hint_length
          },
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = stringResource(R.string.dht_sheet_hint_gesture),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        if (census != null) {
          FilterChip(
            selected = onlyUsed,
            onClick = { onOnlyUsedChange(!onlyUsed) },
            label = { Text(text = stringResource(R.string.dht_sheet_only_used)) },
            colors = FilterChipDefaults.filterChipColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              labelColor = MaterialTheme.colorScheme.onSurface,
              selectedContainerColor = MaterialTheme.colorScheme.primary,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
          )
        }
        Spacer(Modifier.weight(1f))
        TextButton(
          onClick = { onResetTable(table) },
          enabled = changed > 0,
        ) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(text = stringResource(R.string.dht_sheet_reset))
        }
      }
    }

    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      items(count = rows.size, key = { rows[it].key }) { index ->
        when (val row = rows[index]) {
          is DhtListRow.Header -> Text(
            text = groupHeaderText(row.group, table, census),
            style = MaterialTheme.typography.labelLarge,
            color = if (row.group is DhtGroup.Tier && row.group.tier == DhtTier.Rewrite) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
          )
          is DhtListRow.Code -> DhtSymbolRow(
            symbol = row.entry,
            value = edits[row.entry.offset] ?: row.entry.value,
            edited = edits.containsKey(row.entry.offset),
            peekedValue = peeked?.takeIf { it.first == row.entry.offset }?.second,
            impact = census?.impact(row.entry),
            uses = census?.uses(row.entry),
            onPeek = { value -> onPeek(row.entry, value) },
            onPeekEnd = onPeekEnd,
            onValueChange = { value -> onValueChange(row.entry, value) },
            onReset = { onResetSymbol(row.entry) },
          )
        }
      }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }
}

/** Why the code census could not be taken, as a string resource. */
private fun censusBlockHintRes(block: CensusBlock): Int = when (block) {
  CensusBlock.NO_FRAME -> R.string.dht_census_no_frame
  CensusBlock.NOT_SINGLE_SCAN -> R.string.dht_census_not_single_scan
  CensusBlock.NOT_INTERLEAVED -> R.string.dht_census_not_interleaved
  CensusBlock.HAS_RESTARTS -> R.string.dht_census_has_restarts
  CensusBlock.NO_TABLES -> R.string.dht_census_no_tables
  CensusBlock.UNDECODABLE -> R.string.dht_census_undecodable
}

/** The label over a section of the symbol list. */
@Composable
private fun groupHeaderText(
  group: DhtGroup,
  table: HuffmanTable,
  census: HuffmanCensus?,
): String = when (group) {
  is DhtGroup.Tier -> stringResource(
    when (group.tier) {
      DhtTier.TouchUp -> R.string.dht_tier_touch_up
      DhtTier.Texture -> R.string.dht_tier_texture
      DhtTier.Rewrite -> R.string.dht_tier_rewrite
    },
    group.entries.size,
  ) + " - " + stringResource(
    when (group.tier) {
      DhtTier.TouchUp -> R.string.dht_tier_touch_up_note
      DhtTier.Texture -> R.string.dht_tier_texture_note
      DhtTier.Rewrite -> R.string.dht_tier_rewrite_note
    },
  )
  is DhtGroup.Unused -> stringResource(R.string.dht_section_unused, group.entries.size)
  is DhtGroup.Length -> stringResource(
    R.string.dht_section_length,
    group.length,
    group.entries.size,
    stringResource(lengthWordRes(group.length, table)),
  )
}

/** Qualitative name of a code length, used when there is no census. */
private fun lengthWordRes(length: Int, table: HuffmanTable): Int = when {
  length <= 3 -> R.string.dht_length_every_block
  length <= 8 -> R.string.dht_length_common
  length == table.maxCodeLength -> R.string.dht_length_rarest
  else -> R.string.dht_length_occasional
}

/** Share of blocks an edit reaches, as a short percentage. */
private fun formatImpact(impact: Float): String = when {
  impact <= 0f -> "0%"
  impact < 0.001f -> "<0.1%"
  impact < 0.10f -> String.format(Locale.getDefault(), "%.1f%%", impact * 100)
  else -> String.format(Locale.getDefault(), "%.0f%%", impact * 100)
}

/** How often a code was used, as a short count. */
private fun formatUses(uses: Int): String = when {
  uses <= 0 -> "0"
  uses < 1_000 -> uses.toString()
  uses < 1_000_000 -> String.format(Locale.getDefault(), "%.1fk", uses / 1_000f)
  else -> String.format(Locale.getDefault(), "%.1fM", uses / 1_000_000f)
}

/**
 * One code: what it means now, how far changing it would reach, the named
 * behaviours it can be swapped to, and - behind "custom" - the exact byte.
 *
 * The chips are the edit. Tapping one keeps it; holding one shows it until the
 * finger lifts, which is how the screen is meant to be explored: the sample
 * batch says most of this table is destructive, so looking before keeping is the
 * whole game.
 */
@Composable
private fun DhtSymbolRow(
  symbol: HuffmanSymbol,
  value: Int,
  edited: Boolean,
  peekedValue: Int?,
  impact: Float?,
  uses: Int?,
  onPeek: (Int) -> Unit,
  onPeekEnd: () -> Unit,
  onValueChange: (Int) -> Unit,
  onReset: () -> Unit,
) {
  var custom by remember(symbol.offset) { mutableStateOf(false) }
  // Taken from the value in force right now, not the one the file shipped with:
  // that is what lets a second tap carry on from the first instead of offering
  // the same neighbourhood again (see HuffmanAlternatives.forValue).
  val alternatives = remember(symbol.offset, value) {
    HuffmanAlternatives.forValue(symbol.tableClass, value)
  }
  val shown = peekedValue ?: value
  val tier = impact?.let { tierOf(it) }
  val accent = when (tier) {
    DhtTier.Rewrite -> MaterialTheme.colorScheme.error
    DhtTier.Texture -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
  }

  Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.dht_row_index, symbol.index),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(34.dp),
        maxLines = 1,
      )
      Text(
        text = describe(
          tableClass = symbol.tableClass,
          value = shown,
          codeLength = symbol.codeLength,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (peekedValue != null) accent else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
      )
      if (impact != null) {
        Text(
          text = formatImpact(impact),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = accent,
          maxLines = 1,
        )
      }
      IconButton(onClick = { custom = !custom }, modifier = Modifier.size(36.dp)) {
        Icon(
          imageVector = Icons.Filled.Tune,
          contentDescription = stringResource(R.string.dht_row_custom),
          modifier = Modifier.size(18.dp),
          tint = if (custom) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (edited) {
        IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = if (LocalInspectionMode.current) {
              "reset to ${symbol.value}"
            } else {
              stringResource(R.string.dht_row_reset_to, symbol.value)
            },
            modifier = Modifier.size(18.dp),
          )
        }
      } else {
        Spacer(Modifier.size(36.dp))
      }
    }

    // The exact byte is shown, not just the words: a value someone has found and
    // liked (97, say) has to be readable off the row and reproducible.
    Text(
      text = if (uses != null) {
        stringResource(R.string.dht_row_facts, value, formatUses(uses))
      } else {
        stringResource(R.string.dht_row_value, value)
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )

    if (alternatives.isNotEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        alternatives.forEach { alternative ->
          BehaviourChip(
            label = stringResource(behaviourLabelRes(alternative.behaviour)),
            selected = value == alternative.value,
            peeking = peekedValue == alternative.value,
            accent = accent,
            onTap = { onValueChange(alternative.value) },
            onHoldStart = { onPeek(alternative.value) },
            onHoldEnd = onPeekEnd,
          )
        }
      }
    }

    if (custom) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (symbol.isDc) {
          NumberPickerCell(
            value = value.coerceIn(0, symbol.maxValue),
            maxValue = symbol.maxValue,
            textColor = accent,
            onValueChange = onValueChange,
            modifier = Modifier.semantics {
              contentDescription = "code ${symbol.index}, category $value"
            },
          )
        } else {
          Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, symbol.maxValue)) },
            valueRange = 0f..symbol.maxValue.toFloat(),
            modifier = Modifier.weight(1f).systemGestureExclusion(),
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
            modifier = Modifier.width(32.dp),
            maxLines = 1,
            color = accent,
          )
        }
      }
    }
  }
}

/** What a symbol byte stands for, in words. */
@Composable
private fun describe(tableClass: Int, value: Int, codeLength: Int): String {
  val kind = if (tableClass == 0) {
    stringResource(R.string.dht_row_needs, value)
  } else {
    val run = value shr 4
    val size = value and 0x0F
    when {
      size == 0 && run == 15 -> stringResource(R.string.dht_meaning_zrl)
      size == 0 -> stringResource(R.string.dht_meaning_eob)
      else -> stringResource(R.string.dht_meaning_run_size, run, size)
    }
  }
  return stringResource(R.string.dht_row_label, codeLength, kind)
}

/** Title of a behaviour chip. */
private fun behaviourLabelRes(behaviour: HuffmanBehaviour): Int = when (behaviour) {
  HuffmanBehaviour.END_OF_BLOCK -> R.string.dht_behaviour_end_of_block
  HuffmanBehaviour.ZERO_RUN -> R.string.dht_behaviour_zero_run
  HuffmanBehaviour.VALUE_WIDER -> R.string.dht_behaviour_value_wider
  HuffmanBehaviour.VALUE_NARROWER -> R.string.dht_behaviour_value_narrower
  HuffmanBehaviour.SKIP_FURTHER -> R.string.dht_behaviour_skip_further
  HuffmanBehaviour.SKIP_EARLIER -> R.string.dht_behaviour_skip_earlier
  HuffmanBehaviour.TINY_DETAIL -> R.string.dht_behaviour_tiny_detail
  HuffmanBehaviour.LEVEL_WIDER -> R.string.dht_behaviour_level_wider
  HuffmanBehaviour.LEVEL_NARROWER -> R.string.dht_behaviour_level_narrower
  HuffmanBehaviour.LEVEL_FLAT -> R.string.dht_behaviour_level_flat
}

/**
 * A behaviour chip. Tap keeps the edit; holding peeks at it.
 *
 * The two are told apart by how long the finger stays down ([HOLD_TO_PEEK_MS]),
 * which is the usual long-press split and the only way to have both gestures on
 * one target - a tap that committed *and* peeked would be the same gesture with
 * two meanings.
 */
@Composable
private fun BehaviourChip(
  label: String,
  selected: Boolean,
  peeking: Boolean,
  accent: androidx.compose.ui.graphics.Color,
  onTap: () -> Unit,
  onHoldStart: () -> Unit,
  onHoldEnd: () -> Unit,
) {
  Surface(
    modifier = Modifier.tapOrHold(onTap = onTap, onHoldStart = onHoldStart, onHoldEnd = onHoldEnd),
    shape = RoundedCornerShape(8.dp),
    color = when {
      peeking -> accent
      selected -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = when {
      peeking -> MaterialTheme.colorScheme.surface
      selected -> MaterialTheme.colorScheme.onPrimaryContainer
      else -> MaterialTheme.colorScheme.onSurface
    },
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
    )
  }
}

/** What happened to a press before it either lifted or was taken away. */
private enum class PressOutcome { Lifted, Taken }

/**
 * Tap to keep, hold to peek.
 *
 * A press that lifts inside [HOLD_TO_PEEK_MS] is a tap, so a slightly slow tap
 * still commits. One still down after it is a peek, and the peek ends when the
 * finger goes or when the gesture is cancelled - [waitForUpOrCancellation]
 * returns null either way, so a peek cannot outlive its touch.
 *
 * A press that something else consumes - the chip row scrolls sideways, and a
 * drag belongs to the scroll - is dropped rather than turned into a peek, which
 * is why the loop watches `isConsumed` as well as `pressed`.
 */
private fun Modifier.tapOrHold(
  onTap: () -> Unit,
  onHoldStart: () -> Unit,
  onHoldEnd: () -> Unit,
): Modifier = pointerInput(onTap, onHoldStart, onHoldEnd) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val outcome = withTimeoutOrNull(HOLD_TO_PEEK_MS) {
      while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
          ?: return@withTimeoutOrNull PressOutcome.Taken
        if (!change.pressed) return@withTimeoutOrNull PressOutcome.Lifted
        if (change.isConsumed) return@withTimeoutOrNull PressOutcome.Taken
      }
      @Suppress("UNREACHABLE_CODE") PressOutcome.Lifted
    }
    when (outcome) {
      PressOutcome.Lifted -> {
        down.consume()
        onTap()
      }
      PressOutcome.Taken -> Unit
      null -> {
        // Still down after the threshold: peek until the finger goes.
        onHoldStart()
        waitForUpOrCancellation()
        onHoldEnd()
      }
    }
  }
}

// --- Previews ---------------------------------------------------------------

private fun previewTables(): List<HuffmanTable> {
  val counts = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125)
  val symbolCount = counts.sum()
  val data = ByteArray(17 + symbolCount)
  val entries = (0 until symbolCount).map { i ->
    HuffmanSymbol(1, 0, i, 17 + i, 9, data)
  }
  return listOf(HuffmanTable(1, 0, 0, counts, entries, isChroma = false))
}

@Preview(name = "DHT", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DhtScreenPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      DhtScreenContent(
        preview = previewImageBitmap(),
        tables = previewTables(),
        edits = emptyMap(),
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onPreviewBounds = {},
        onBack = {},
        onInfo = {},
        onOpenFamily = {},
        onSkip = {},
        onNext = {},
        onResetAll = {},
      )
    }
  }
}

@Preview(name = "DHT · edited", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DhtScreenEditedPreview() {
  val tables = previewTables()
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      DhtScreenContent(
        preview = previewImageBitmap(),
        tables = tables,
        edits = mapOf(tables.first().entries[2].offset to 8),
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onPreviewBounds = {},
        onBack = {},
        onInfo = {},
        onOpenFamily = {},
        onSkip = {},
        onNext = {},
        onResetAll = {},
      )
    }
  }
}

@Preview(name = "DHT · peeking", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DhtScreenPeekPreview() {
  val tables = previewTables()
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      DhtScreenContent(
        preview = previewImageBitmap(),
        tables = tables,
        edits = emptyMap(),
        peeked = tables.first().entries[2].offset,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onPreviewBounds = {},
        onBack = {},
        onInfo = {},
        onOpenFamily = {},
        onSkip = {},
        onNext = {},
        onResetAll = {},
      )
    }
  }
}
