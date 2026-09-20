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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import ca.justinmo.glitch.jpeg.HuffmanCensus
import ca.justinmo.glitch.jpeg.HuffmanCensusReader
import ca.justinmo.glitch.jpeg.HuffmanSymbol
import ca.justinmo.glitch.jpeg.HuffmanTable
import ca.justinmo.glitch.jpeg.HuffmanTables
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.SweepEdit
import ca.justinmo.glitch.jpeg.SweepOutcome
import ca.justinmo.glitch.jpeg.DhtSweep
import ca.justinmo.glitch.ui.theme.GlitchTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long-edge cap for the preview on this screen, as on the DQT screen. */
private const val DHT_PREVIEW_CAP_PX = 1400

/**
 * Panel heights as a fraction of the window, mirroring the DQT screen so the two
 * behave the same way: opens at [DHT_PANEL_MAX_FRACTION], can be dragged down to
 * [DHT_PANEL_SHORT_FRACTION], and past [DHT_PANEL_DISMISS_FRACTION] to close.
 */
private const val DHT_PANEL_MAX_FRACTION = 0.62f
private const val DHT_PANEL_SHORT_FRACTION = 0.35f
private const val DHT_PANEL_DRAG_MIN_FRACTION = 0.08f
private const val DHT_PANEL_DISMISS_FRACTION = 0.22f
private const val DHT_PANEL_FLING_VELOCITY = 900f
private val DHT_PANEL_STOPS = listOf(DHT_PANEL_SHORT_FRACTION, DHT_PANEL_MAX_FRACTION)
private val DHT_PANEL_FALLBACK_HEIGHT = 640.dp

/**
 * Which Huffman table a tile opens: its class (DC or AC) and whether it lives in
 * the chroma set. The AC tables carry the run/size pairs that code the block
 * detail; the DC tables carry the per-block level, which is why they are
 * labelled level rather than detail.
 */
internal data class DhtFamily(val tableClass: Int, val chroma: Boolean)

/**
 * The tiles in display order. The two AC tables come first because that is where
 * the range is: on the sample batch 767 of the 848 bits that change anything sit
 * in an AC symbol list (`DHT.md` section 0), and the AC tables are also the safe
 * ones - a DC symbol is a bit count, so changing one desynchronises the decoder.
 */
internal val DHT_FAMILIES: List<DhtFamily> = listOf(
  DhtFamily(tableClass = 1, chroma = false),
  DhtFamily(tableClass = 0, chroma = false),
  DhtFamily(tableClass = 1, chroma = true),
  DhtFamily(tableClass = 0, chroma = true),
)

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
 * Screen between [DqtScreen] and [EditScreen]: retune the **Huffman tables** by
 * editing the symbols the codes stand for, before glitching.
 *
 * The quantization screen picks *how coarsely* each frequency of the picture is
 * kept; this one picks *what each code means*. JPEG never stores a coefficient
 * directly - it stores a Huffman code whose symbol says how far along the block
 * to jump and how many bits the value needs - so rewriting a symbol rewrites
 * what every block that used that code decodes to. Nothing here is random and
 * nothing is rolled back: whatever is set goes into the bytes handed to
 * [EditScreen], exactly as on the DQT screen.
 *
 * Three things make this honest rather than a wall of 3328 bits
 * (all measured in `DHT.md`, which is generated from the sample batch):
 *
 *  * **Only the symbol bytes are editable.** Every bit of a table's information
 *    byte and of its 16 code-length counts makes the file undecodable, so those
 *    68 bytes per file are never offered - the screen says so instead.
 *  * **A symbol's position in the list is its strength dial.** The list is in
 *    code order, so symbol #0 owns the shortest code, and JPEG hands the
 *    shortest codes to the most common (run, size) pairs. The list is therefore
 *    shown in that order, strongest first.
 *  * **A code that never occurs cannot change anything.** [HuffmanCensus] walks
 *    the scan once and counts how often each code is actually used, so the
 *    screen can hide the ones this photo never emits - which on the sample batch
 *    is most of them.
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
    if (openFamily == null) panelBoundsInRoot = null
  }

  val applied = remember(bytes, edits) {
    if (edits.isEmpty()) bytes else bytes.copyOf().also { HuffmanTables.applyValues(it, edits) }
  }

  // The classification from the b_x4 sweep, by the symbol byte it belongs to:
  // eight measured bit flips per code, which the rows draw as a strip.
  val classified = remember(bytes, structure) {
    DhtSweep.appliesTo(HuffmanTables.payloadOf(bytes, structure))
  }
  val flips = remember(tables, classified) {
    if (classified) DhtSweep.editsFor(tables).groupBy { it.offset } else emptyMap()
  }

  LaunchedEffect(applied) {
    val decoded = withContext(Dispatchers.Default) { decodeSampled(applied, DHT_PREVIEW_CAP_PX) }
    preview = decoded
    // A DC symbol of 16 or more, or an offset outside the file, is the one edit
    // that can make the result stop opening - say so rather than showing nothing.
    previewBroken = decoded == null && edits.isNotEmpty()
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
      flips = flips,
      classified = classified,
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
        // Only open the panel when the tapped tile has a table behind it; the
        // pages themselves resolve their own table, so a swipe onto a tile this
        // file does not carry shows that instead of the last one's symbols.
        if (tableFor(tables, family) != null) {
          DhtTablePanel(
            family = family,
            tables = tables,
            edits = edits,
            flips = flips,
            classified = classified,
            census = census,
            censusBlock = censusBlock,
            onlyUsed = onlyUsed,
            onOnlyUsedChange = { onlyUsed = it },
            onValueChange = { symbol, value ->
              // Setting a symbol back to the value the file already has drops the
              // edit, so the counts and badges stay honest.
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

    // Drawn last, so the explanation covers the panel and the tile row.
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
  flips: Map<Int, List<SweepEdit>>,
  classified: Boolean,
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

        // The one edit that can stop the file opening - a DC category past 15 -
        // deserves a banner rather than a blank preview.
        if (brokenEdit) {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 60.dp, start = 16.dp, end = 16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer,
          ) {
            Text(
              text = stringResource(R.string.dht_broken_edit),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
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

/** Height the preview takes once `ContentScale.Fit` has letterboxed it. */
private fun fittedMediaHeight(bmp: ImageBitmap?, maxWidth: Dp, maxHeight: Dp): Dp =
  if (bmp != null && bmp.width > 0 && bmp.height > 0) {
    minOf(maxHeight, maxWidth * bmp.height / bmp.width)
  } else {
    maxHeight
  }

/** The four table tiles, one per Huffman table the file carries. */
@Composable
private fun DhtFamilyRow(
  tables: List<HuffmanTable>,
  edits: Map<Int, Int>,
  onOpenFamily: (DhtFamily) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DHT_FAMILIES.forEach { family ->
      DhtFamilyTile(
        family = family,
        table = tableFor(tables, family),
        edits = edits,
        onClick = { onOpenFamily(family) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * One table tile: an icon, the table's name, and how many of its symbols have
 * been changed. Greys out when the file has no table of that kind (a grayscale
 * file carries one DC and one AC table, so the colour tiles have nothing).
 */
@Composable
private fun DhtFamilyTile(
  family: DhtFamily,
  table: HuffmanTable?,
  edits: Map<Int, Int>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
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
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      changed > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
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

// --- The symbol panel -------------------------------------------------------

/**
 * One section of the symbol list.
 *
 * With a census the split is *what this photo does with the table* - codes it
 * emits, and codes it never does - because that is the difference between an
 * edit that shows and an edit that cannot. Without one (progressive file, or a
 * scan the walk cannot follow) the split falls back to code length, which is the
 * same ordering the census produces and the strength dial `DHT.md` measured.
 */
internal sealed interface DhtGroup {
  val key: String
  val entries: List<HuffmanSymbol>

  data class Used(override val entries: List<HuffmanSymbol>) : DhtGroup {
    override val key: String get() = "used"
  }

  data class Unused(override val entries: List<HuffmanSymbol>) : DhtGroup {
    override val key: String get() = "unused"
  }

  data class Length(val length: Int, override val entries: List<HuffmanSymbol>) : DhtGroup {
    override val key: String get() = "len$length"
  }
}

/**
 * The sections the panel shows. Used codes come first, ordered by how often the
 * photo emits them, so the loudest edit is always the first row on screen.
 */
internal fun dhtGroups(
  table: HuffmanTable,
  census: HuffmanCensus?,
  onlyUsed: Boolean,
): List<DhtGroup> {
  fun byLength(): List<DhtGroup> =
    table.entriesByLength().map { (length, entries) -> DhtGroup.Length(length, entries) }

  if (census == null) return byLength()
  val used = table.entries
    .filter { census.uses(it) > 0 }
    .sortedWith(compareByDescending<HuffmanSymbol> { census.uses(it) }.thenBy { it.index })
  val unused = table.entries.filter { census.uses(it) == 0 }
  // A table this photo never touches would otherwise list nothing at all; fall
  // back to the code-length ordering so there is always something to look at.
  if (used.isEmpty()) return byLength()
  return buildList {
    add(DhtGroup.Used(used))
    if (!onlyUsed && unused.isNotEmpty()) add(DhtGroup.Unused(unused))
  }
}

/**
 * The symbol panel: every symbol of one table, one per row, under section labels
 * that say how much the photo uses them.
 *
 * It is a plain surface over the bottom of the screen rather than a modal sheet,
 * so the preview above stays interactive - edit a symbol and watch the picture
 * change while the panel is open, exactly as on the DQT screen. It can be
 * dragged by its handle up to its tall stop, down to its short one, and down
 * past that to close.
 */
@Composable
private fun DhtTablePanel(
  family: DhtFamily,
  tables: List<HuffmanTable>,
  edits: Map<Int, Int>,
  flips: Map<Int, List<SweepEdit>>,
  classified: Boolean,
  census: HuffmanCensus?,
  censusBlock: CensusBlock?,
  onlyUsed: Boolean,
  onOnlyUsedChange: (Boolean) -> Unit,
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
            // A grayscale file carries one DC and one AC table, so the colour
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
              flips = flips,
              classified = classified,
              census = census,
              censusBlock = censusBlock,
              onlyUsed = onlyUsed,
              onOnlyUsedChange = onOnlyUsedChange,
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
 * Everything of one table: its title, what its symbols code, the census line,
 * the filter, and the symbol rows.
 */
@Composable
private fun DhtTablePage(
  family: DhtFamily,
  table: HuffmanTable,
  edits: Map<Int, Int>,
  flips: Map<Int, List<SweepEdit>>,
  classified: Boolean,
  census: HuffmanCensus?,
  censusBlock: CensusBlock?,
  onlyUsed: Boolean,
  onOnlyUsedChange: (Boolean) -> Unit,
  onValueChange: (HuffmanSymbol, Int) -> Unit,
  onResetSymbol: (HuffmanSymbol) -> Unit,
  onResetTable: (HuffmanTable) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier,
) {
  val groups = remember(table, census, onlyUsed) { dhtGroups(table, census, onlyUsed) }
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
      // What the strip under each row is. Said once, here, rather than repeated
      // on every row.
      if (classified) {
        Text(
          text = stringResource(R.string.dht_strip_legend),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          text = stringResource(R.string.dht_strip_unavailable),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = stringResource(
          if (table.isAc) R.string.dht_sheet_hint_ac else R.string.dht_sheet_hint_dc
        ),
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
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      groups.forEach { group ->
        item(key = "header-${group.key}") {
          Text(
            text = groupHeaderText(group, table, census),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
          )
        }
        items(group.entries, key = { it.offset }) { symbol ->
          DhtSymbolRow(
            symbol = symbol,
            value = edits[symbol.offset] ?: symbol.value,
            edited = edits.containsKey(symbol.offset),
            uses = census?.uses(symbol),
            flips = flips[symbol.offset].orEmpty(),
            onValueChange = { value -> onValueChange(symbol, value) },
            onReset = { onResetSymbol(symbol) },
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
  is DhtGroup.Used -> stringResource(R.string.dht_section_used, group.entries.size)
  is DhtGroup.Unused -> stringResource(R.string.dht_section_unused, group.entries.size)
  is DhtGroup.Length -> stringResource(
    R.string.dht_section_length,
    group.length,
    group.entries.size,
    stringResource(lengthWordRes(group.length, table)),
  )
}

/** Qualitative name of a code length: short codes fire most often. */
private fun lengthWordRes(length: Int, table: HuffmanTable): Int = when {
  length <= 3 -> R.string.dht_length_every_block
  length <= 8 -> R.string.dht_length_common
  length == table.maxCodeLength -> R.string.dht_length_rarest
  else -> R.string.dht_length_occasional
}

/** How often a code was used, as a short badge. */
private fun formatUses(uses: Int): String = when {
  uses <= 0 -> "0"
  uses < 1_000 -> uses.toString()
  uses < 1_000_000 -> String.format(Locale.getDefault(), "%.1fk", uses / 1_000f)
  else -> String.format(Locale.getDefault(), "%.1fM", uses / 1_000_000f)
}

/**
 * One symbol in a single row: what the code means, how often the photo uses it,
 * and a control over the whole value the symbol byte can take.
 *
 * Under the control sits [DhtBitStrip]: one cell per bit of the value, coloured
 * by what the `b_x4` sweep measured for that flip. The slider is the continuous
 * control and the strip is the measured one - the two answers to "what should
 * this byte be?", side by side, which is what earlier versions of this screen
 * got wrong by offering only one of them.
 *
 * The AC tables get a [Slider] over 0-255 - dragging it sweeps the magnitude
 * nibble first and then the run nibble, so the fine control is the one that
 * changes the picture most - and the label above it spells out the pair the
 * value stands for. The DC tables get a [NumberPickerCell] instead, for the same
 * reason the DQT screen's brightness and saturation rows do: there the symbol is
 * one number that *is* the effect, so exact single steps beat a coarse drag.
 */
@Composable
private fun DhtSymbolRow(
  symbol: HuffmanSymbol,
  value: Int,
  edited: Boolean,
  uses: Int?,
  flips: List<SweepEdit>,
  onValueChange: (Int) -> Unit,
  onReset: () -> Unit,
) {
  val maxValue = symbol.maxValue
  // Zero and "changed" read the same in every row, matching the DQT screen: a
  // changed symbol is flagged with the accent, and a DC category past what an
  // encoder emits is called out without being forbidden.
  val valueColor = when {
    edited -> MaterialTheme.colorScheme.primary
    symbol.isDc && value > HuffmanSymbol.DC_USEFUL_CATEGORY -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val rowLabel = stringResource(
    R.string.dht_row_label,
    symbol.index,
    symbol.codeLength,
    if (symbol.isDc) stringResource(R.string.dht_row_needs, value) else meaningOf(value),
  )
  val pickerLabel = if (LocalInspectionMode.current) {
    "symbol ${symbol.index}, category $value"
  } else {
    stringResource(R.string.dht_row_picker_label, symbol.index, value)
  }

  Column(
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
      Text(
        text = rowLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
      if (uses != null) {
        Text(
          text = if (uses > 0) {
            stringResource(R.string.dht_row_uses, formatUses(uses))
          } else {
            stringResource(R.string.dht_row_unused)
          },
          style = MaterialTheme.typography.labelSmall,
          color = if (uses > 0) {
            MaterialTheme.colorScheme.onSurfaceVariant
          } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
          },
          maxLines = 1,
        )
      }
    }

    if (symbol.isDc) {
      NumberPickerCell(
        value = value.coerceIn(0, maxValue),
        maxValue = maxValue,
        textColor = valueColor,
        onValueChange = onValueChange,
        modifier = Modifier.semantics { contentDescription = pickerLabel },
      )
    } else {
      Row(
        modifier = Modifier.weight(1.3f),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Slider(
          value = value.toFloat(),
          onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maxValue)) },
          valueRange = 0f..maxValue.toFloat(),
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
          modifier = Modifier.width(28.dp),
          maxLines = 1,
          color = valueColor,
        )
      }
    }

      if (edited) {
        IconButton(onClick = onReset, modifier = Modifier.size(40.dp)) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = if (LocalInspectionMode.current) {
              "reset to ${symbol.value}"
            } else {
              stringResource(R.string.dht_row_reset_to, symbol.value)
            },
          )
        }
      } else {
        // Same footprint as the button, so the controls stay lined up.
        Spacer(Modifier.size(40.dp))
      }
    }

    if (flips.isNotEmpty()) {
      DhtBitStrip(
        flips = flips,
        current = value,
        onFlip = onValueChange,
        modifier = Modifier.padding(start = 4.dp, end = 40.dp, top = 2.dp, bottom = 4.dp),
      )
    }
  }
}

/**
 * One cell per bit of the value byte, in bit order, coloured by what the `b_x4`
 * sweep measured when it flipped that bit in the four sample photos.
 *
 * This is the whole point of the strip: the sweep's classification covers every
 * one of the 3328 bits, and a user editing a number cannot be expected to hold
 * that in their head - or to reach for a bit index. Here it sits on the row it
 * belongs to, one tap away from the edit it describes.
 *
 * Grey changed nothing on those photos, green was filed as a change worth
 * keeping, red was filed as a wreck, and the darker reds wrecked more of the
 * four. A cell the sweep has no sample for (the flip stops the file decoding)
 * is drawn dim and cannot be tapped, because there is no picture behind it.
 *
 * Nothing here is hidden or blocked: red cells still apply. The classification
 * is evidence from four photos, not a verdict on this one, and the edit someone
 * likes has been known to live in the red.
 */
@Composable
private fun DhtBitStrip(
  flips: List<SweepEdit>,
  current: Int,
  onFlip: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().height(22.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    flips.sortedBy { it.bit }.forEach { flip ->
      val fatal = flip.outcome == SweepOutcome.FATAL
      val applied = current == flip.to
      // Read before the semantics block, which is not a composable scope.
      val description = flipDescription(flip)
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxSize()
          .clip(RoundedCornerShape(4.dp))
          .background(flipCellColor(flip.outcome))
          .then(
            if (applied) {
              Modifier.border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(4.dp),
              )
            } else {
              Modifier
            },
          )
          .then(
            if (fatal) {
              Modifier
            } else {
              Modifier.clickable { onFlip(flip.to) }
            },
          )
          .semantics {
            contentDescription = description
          },
      )
    }
  }
}

/** The colour of one strip cell: what the sweep filed that flip as. */
@Composable
private fun flipCellColor(outcome: SweepOutcome): androidx.compose.ui.graphics.Color {
  val base = when {
    outcome == SweepOutcome.FATAL -> MaterialTheme.colorScheme.surfaceContainerHighest
    outcome == SweepOutcome.NOTHING -> MaterialTheme.colorScheme.surfaceContainerHighest
    outcome.isWreck -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
  }
  // How many of the four photos it changed, as opacity: a flip that moved all
  // four is solid, one that moved a single photo is faint.
  val alpha = when {
    outcome == SweepOutcome.FATAL -> 0.35f
    outcome == SweepOutcome.NOTHING -> 0.55f
    else -> 0.35f + 0.16f * outcome.photosChanged
  }
  return base.copy(alpha = alpha)
}

/** What a strip cell says to a screen reader, and to the user on a long press. */
@Composable
private fun flipDescription(flip: SweepEdit): String {
  val outcome = when {
    flip.outcome == SweepOutcome.FATAL -> stringResource(R.string.dht_flip_fatal)
    flip.outcome == SweepOutcome.NOTHING -> stringResource(R.string.dht_flip_nothing)
    flip.outcome.isWreck -> stringResource(R.string.dht_flip_wreck, flip.outcome.photosChanged)
    else -> stringResource(R.string.dht_flip_kept, flip.outcome.photosChanged)
  }
  val from = if (flip.isDc) stringResource(R.string.dht_row_needs, flip.from) else meaningOf(flip.from)
  val to = if (flip.isDc) stringResource(R.string.dht_row_needs, flip.to) else meaningOf(flip.to)
  return stringResource(R.string.dht_flip_cell, flip.bit, from, to, outcome)
}

/**
 * What an AC symbol stands for in words. The packed byte is `run << 4 | size`,
 * and the two special values are the ones worth naming: a size of zero ends the
 * block (JPEG's end-of-block symbol is run 0, size 0) unless the run is 15, in
 * which case it skips sixteen zero coefficients.
 */
@Composable
private fun meaningOf(value: Int): String {
  val run = value shr 4
  val size = value and 0x0F
  return when {
    size == 0 && run == 15 -> stringResource(R.string.dht_meaning_zrl)
    size == 0 -> stringResource(R.string.dht_meaning_eob)
    else -> stringResource(R.string.dht_meaning_run_size, run, size)
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
  return listOf(
    HuffmanTable(
      tableClass = 1,
      tableId = 0,
      specOffset = 0,
      payloadIndex = 29,
      counts = counts,
      entries = entries,
      isChroma = false,
    ),
  )
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
        flips = emptyMap(),
        classified = false,
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
        flips = emptyMap(),
        classified = false,
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
