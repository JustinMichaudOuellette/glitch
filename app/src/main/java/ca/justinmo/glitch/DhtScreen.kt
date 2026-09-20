package ca.justinmo.glitch

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import ca.justinmo.glitch.jpeg.DhtSweep
import ca.justinmo.glitch.jpeg.HuffmanCensus
import ca.justinmo.glitch.jpeg.HuffmanCensusReader
import ca.justinmo.glitch.jpeg.HuffmanSymbol
import ca.justinmo.glitch.jpeg.HuffmanTable
import ca.justinmo.glitch.jpeg.HuffmanTables
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.jpeg.SweepEdit
import ca.justinmo.glitch.jpeg.SweepOutcome
import ca.justinmo.glitch.ui.theme.GlitchTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Long-edge cap for the preview on this screen, as on the DQT screen. */
private const val DHT_PREVIEW_CAP_PX = 1400

/**
 * Panel heights as a fraction of the window. The panel holds the edit list, so
 * it opens at half the window - the photo keeps the other half - and can be
 * dragged up to browse or down to look at the picture.
 */
private const val DHT_PANEL_MAX_FRACTION = 0.75f
private const val DHT_PANEL_OPEN_FRACTION = 0.5f
private const val DHT_PANEL_SHORT_FRACTION = 0.22f
private const val DHT_PANEL_DRAG_MIN_FRACTION = 0.08f
private const val DHT_PANEL_DISMISS_FRACTION = 0.12f
private const val DHT_PANEL_FLING_VELOCITY = 900f
private val DHT_PANEL_STOPS = listOf(
  DHT_PANEL_SHORT_FRACTION,
  DHT_PANEL_OPEN_FRACTION,
  DHT_PANEL_MAX_FRACTION,
)
private val DHT_PANEL_FALLBACK_HEIGHT = 640.dp

/**
 * How long a finger stays down on an edit row before it counts as a peek rather
 * than a tap. Short enough to feel immediate, long enough that a slightly slow
 * tap still applies.
 */
private const val HOLD_TO_PEEK_MS = 220L

/**
 * Which Huffman table an edit belongs to: its class (DC or AC) and whether it
 * lives in the chroma set. Only used for labels now - the screen no longer asks
 * the user to choose a table first, because the sweep already says which edits
 * are worth having.
 */
internal data class DhtFamily(val tableClass: Int, val chroma: Boolean)

/** The table an edit belongs to. */
internal fun familyOf(edit: SweepEdit): DhtFamily = DhtFamily(edit.tableClass, edit.chroma)

/** Title of a table, e.g. "Luma detail". */
private fun familyTitleRes(family: DhtFamily): Int = when {
  family.tableClass == 1 && !family.chroma -> R.string.dht_family_luma_detail
  family.tableClass == 0 && !family.chroma -> R.string.dht_family_luma_level
  family.tableClass == 1 -> R.string.dht_family_colour_detail
  else -> R.string.dht_family_colour_level
}

/**
 * Screen between [DqtScreen] and [EditScreen]: apply Huffman-table edits that
 * are **known to be good**, and nothing else.
 *
 * The first two versions of this screen handed the user the tables themselves -
 * a slider per symbol, then named choices per symbol - and both were wrong for
 * the same reason: the region is 2784 edits of which 66% do nothing and 23% of
 * the rest wreck the picture, so a browser of any kind is a 92% disappointment
 * machine. Browsing is not the job. Choosing from a worked-out list is.
 *
 * So the screen no longer shows the tables at all. It shows the `b_x4` sweep's
 * own classification ([DhtSweep]): every edit whose sample was filed as
 * something other than a wreck, with what it does and how many of the four
 * sample photos it changed. One tap applies one, the row shows as applied, and
 * tapping again takes it back. The `wrecked` folder is one chip away rather than
 * hidden, because a measurement on four photos is evidence, not a verdict - the
 * edit filed as a wreck there is sometimes the one someone wants.
 *
 * The exact-value editor - the only way to reach a value that is not one bit
 * away from the file's own - is behind "Set an exact value", labelled as
 * unclassified.
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
  val payload = remember(bytes, structure) { HuffmanTables.payloadOf(bytes, structure) }
  val classified = remember(payload) { DhtSweep.appliesTo(payload) }
  val allEdits = remember(tables, classified) {
    if (classified) DhtSweep.editsFor(tables) else emptyList()
  }

  var edits by remember(bytes) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
  var peek by remember(bytes) { mutableStateOf<Pair<Int, Int>?>(null) }
  var showInfo by remember(bytes) { mutableStateOf(false) }
  var exactValues by remember(bytes) { mutableStateOf(false) }
  var outcomes by remember(bytes) { mutableStateOf(DEFAULT_OUTCOMES) }
  var preview by remember(bytes) { mutableStateOf<Bitmap?>(null) }
  var previewBroken by remember(bytes) { mutableStateOf(false) }
  var census by remember(bytes) { mutableStateOf<HuffmanCensus?>(null) }
  var censusBlock by remember(bytes) { mutableStateOf<CensusBlock?>(null) }

  var scale by remember { mutableFloatStateOf(initialScale) }
  var offset by remember { mutableStateOf(initialOffset) }

  val applied = remember(bytes, edits, peek) {
    val all = if (peek == null) edits else edits + peek!!
    if (all.isEmpty()) bytes else bytes.copyOf().also { HuffmanTables.applyValues(it, all) }
  }
  val committed = remember(bytes, edits) {
    if (edits.isEmpty()) bytes else bytes.copyOf().also { HuffmanTables.applyValues(it, edits) }
  }

  LaunchedEffect(applied, peek) {
    val decoded = withContext(Dispatchers.Default) { decodeSampled(applied, DHT_PREVIEW_CAP_PX) }
    preview = decoded
    previewBroken = decoded == null && (edits.isNotEmpty() || peek != null)
  }

  // The census only filters: an edit whose code this photo never emits cannot
  // change anything, so it is not worth a row.
  LaunchedEffect(bytes, tables) {
    val block = if (tables.isEmpty()) CensusBlock.NO_TABLES else HuffmanCensusReader.blockFor(structure)
    if (block != null) {
      censusBlock = block
      census = null
      return@LaunchedEffect
    }
    val counted = withContext(Dispatchers.Default) { HuffmanCensusReader.read(bytes, structure, tables) }
    censusBlock = if (counted == null) CensusBlock.UNDECODABLE else null
    census = counted
  }

  BackHandler(enabled = showInfo) { showInfo = false }

  val transform: (Float, Offset) -> Unit = { zoomChange, panChange ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    offset += panChange
  }

  // The rows the panel will show: the classification, minus the outcomes the
  // user has filtered out, minus the codes this photo cannot use.
  val counted = census
  val suggestions = remember(allEdits, outcomes, counted) {
    allEdits.filter {
      it.outcome in outcomes &&
        (counted == null || counted.uses(it.tableClass, it.tableId, it.symbolIndex) > 0)
    }
  }
  val hiddenForUnused = remember(allEdits, outcomes, counted) {
    if (counted == null) {
      0
    } else {
      allEdits.count {
        it.outcome in outcomes && counted.uses(it.tableClass, it.tableId, it.symbolIndex) == 0
      }
    }
  }
  val tally = remember(allEdits) {
    allEdits.groupingBy { it.outcome }.eachCount()
  }

  DhtScreenContent(
    preview = preview?.asImageBitmap(),
    suggestions = suggestions,
    tally = tally,
    usesOf = { edit -> counted?.uses(edit.tableClass, edit.tableId, edit.symbolIndex) },
    outcomes = outcomes,
    onOutcomeToggle = { outcome ->
      outcomes = if (outcome in outcomes) outcomes - outcome else outcomes + outcome
    },
    appliedOffsets = edits.keys,
    peekOffset = peek?.first,
    classified = classified,
    hiddenForUnused = hiddenForUnused,
    censusBlock = censusBlock,
    exactValues = exactValues,
    tables = tables,
    symbolValues = { symbol -> edits[symbol.offset] ?: symbol.value },
    brokenEdit = previewBroken,
    zoomScale = scale,
    zoomOffset = offset,
    onApply = { edit ->
      peek = null
      edits = if (edits[edit.offset] == edit.to) {
        edits - edit.offset
      } else {
        edits + (edit.offset to edit.to)
      }
    },
    onPeek = { edit -> peek = edit.offset to edit.to },
    onPeekEnd = { peek = null },
    onSetValue = { symbol, value ->
      peek = null
      edits = if (value == symbol.value) edits - symbol.offset else edits + (symbol.offset to value)
    },
    onExactValues = { exactValues = it },
    onBack = onBack,
    onInfo = { showInfo = true },
    onSkip = { onContinue(bytes, scale, offset) },
    onNext = { onContinue(committed, scale, offset) },
    onResetAll = { edits = emptyMap() },
  )

  if (showInfo) {
    DhtInfoScreen(onClose = { showInfo = false })
  }
}

/**
 * The outcomes shown by default: everything the sweep kept, and none of what it
 * filed as a wreck. The wrecked chips are one tap away.
 */
private val DEFAULT_OUTCOMES: Set<SweepOutcome> =
  setOf(SweepOutcome.FOUR, SweepOutcome.THREE, SweepOutcome.TWO, SweepOutcome.ONE)

/**
 * The screen's body: the preview above, the edit list in a draggable panel
 * below, and the skip / next row. Kept free of state so it can be previewed.
 */
@Composable
internal fun DhtScreenContent(
  preview: ImageBitmap?,
  suggestions: List<SweepEdit>,
  tally: Map<SweepOutcome, Int>,
  usesOf: (SweepEdit) -> Int?,
  outcomes: Set<SweepOutcome>,
  onOutcomeToggle: (SweepOutcome) -> Unit,
  appliedOffsets: Set<Int>,
  peekOffset: Int?,
  classified: Boolean,
  hiddenForUnused: Int,
  censusBlock: CensusBlock?,
  exactValues: Boolean,
  tables: List<HuffmanTable>,
  symbolValues: (HuffmanSymbol) -> Int,
  brokenEdit: Boolean,
  zoomScale: Float,
  zoomOffset: Offset,
  onApply: (SweepEdit) -> Unit,
  onPeek: (SweepEdit) -> Unit,
  onPeekEnd: () -> Unit,
  onSetValue: (HuffmanSymbol, Int) -> Unit,
  onExactValues: (Boolean) -> Unit,
  onBack: () -> Unit,
  onInfo: () -> Unit,
  onSkip: () -> Unit,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize()) {
    if (preview != null) {
      Image(
        bitmap = preview,
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

    DhtTopBar(
      onBack = onBack,
      onInfo = onInfo,
      onSkip = onSkip,
      onNext = onNext,
      resets = appliedOffsets.isNotEmpty(),
      changed = appliedOffsets.size,
      onResetAll = onResetAll,
    )

    if (peekOffset != null) {
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

    DhtPanel(
      suggestions = suggestions,
      tally = tally,
      usesOf = usesOf,
      outcomes = outcomes,
      onOutcomeToggle = onOutcomeToggle,
      appliedOffsets = appliedOffsets,
      peekOffset = peekOffset,
      classified = classified,
      hiddenForUnused = hiddenForUnused,
      censusBlock = censusBlock,
      exactValues = exactValues,
      tables = tables,
      symbolValues = symbolValues,
      onApply = onApply,
      onPeek = onPeek,
      onPeekEnd = onPeekEnd,
      onSetValue = onSetValue,
      onExactValues = onExactValues,
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

/** Title, actions and the reset, over the top of the photo. */
@Composable
private fun DhtTopBar(
  onBack: () -> Unit,
  onInfo: () -> Unit,
  onSkip: () -> Unit,
  onNext: () -> Unit,
  resets: Boolean,
  changed: Int,
  onResetAll: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    TitleBar(
      title = stringResource(R.string.dht_title),
      onBack = onBack,
      onInfo = onInfo,
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(
        onClick = onSkip,
        modifier = Modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          contentColor = MaterialTheme.colorScheme.onSurface,
        ),
      ) { Text(text = stringResource(R.string.dht_skip), maxLines = 1) }
      if (resets) {
        TextButton(onClick = onResetAll) {
          Text(
            text = stringResource(R.string.dht_reset),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
        }
      }
      Spacer(Modifier.weight(1f))
      Button(
        onClick = onNext,
        modifier = Modifier.height(44.dp),
      ) {
        Text(
          text = if (changed == 0) {
            stringResource(R.string.dht_next)
          } else {
            stringResource(R.string.dht_next_edited, changed)
          },
          maxLines = 1,
        )
      }
    }
  }
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

/**
 * The edit list, in a panel that can be dragged between a strip, half the
 * window and three quarters of it.
 *
 * Kept as a plain surface in the same window rather than a modal sheet, so the
 * photo above stays live: hold a row to see what it does, then let go.
 */
@Composable
private fun DhtPanel(
  suggestions: List<SweepEdit>,
  tally: Map<SweepOutcome, Int>,
  usesOf: (SweepEdit) -> Int?,
  outcomes: Set<SweepOutcome>,
  onOutcomeToggle: (SweepOutcome) -> Unit,
  appliedOffsets: Set<Int>,
  peekOffset: Int?,
  classified: Boolean,
  hiddenForUnused: Int,
  censusBlock: CensusBlock?,
  exactValues: Boolean,
  tables: List<HuffmanTable>,
  symbolValues: (HuffmanSymbol) -> Int,
  onApply: (SweepEdit) -> Unit,
  onPeek: (SweepEdit) -> Unit,
  onPeekEnd: () -> Unit,
  onSetValue: (HuffmanSymbol, Int) -> Unit,
  onExactValues: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  initialFraction: Float = DHT_PANEL_OPEN_FRACTION,
) {
  BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    // An unbounded parent (a preview) reports an infinite height; scaling that
    // by the panel fraction throws, so fall back to a sane window.
    val windowHeight = if (maxHeight.value.isFinite()) maxHeight else DHT_PANEL_FALLBACK_HEIGHT
    val windowHeightPx = with(LocalDensity.current) { windowHeight.toPx() }.coerceAtLeast(1f)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(initialFraction) }
    var targetFraction by remember { mutableFloatStateOf(initialFraction) }
    val fraction by animateFloatAsState(
      targetValue = if (dragging) dragFraction else targetFraction,
      animationSpec = if (dragging) snap() else tween(durationMillis = 200),
    )

    val dragState = rememberDraggableState { delta ->
      dragFraction = (dragFraction - delta / windowHeightPx)
        .coerceIn(DHT_PANEL_DRAG_MIN_FRACTION, DHT_PANEL_MAX_FRACTION)
    }
    fun settleDrag(velocityY: Float = 0f) {
      targetFraction = when {
        velocityY > DHT_PANEL_FLING_VELOCITY -> DHT_PANEL_SHORT_FRACTION
        velocityY < -DHT_PANEL_FLING_VELOCITY -> DHT_PANEL_MAX_FRACTION
        dragFraction < DHT_PANEL_DISMISS_FRACTION -> DHT_PANEL_SHORT_FRACTION
        else -> DHT_PANEL_STOPS.minBy { stop -> kotlin.math.abs(stop - dragFraction) }
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
        .nestedScroll(listDrag),
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
            .padding(vertical = 10.dp),
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

        if (exactValues) {
          DhtExactValues(
            tables = tables,
            symbolValues = symbolValues,
            appliedOffsets = appliedOffsets,
            onSetValue = onSetValue,
            onBack = { onExactValues(false) },
            modifier = Modifier.fillMaxWidth().weight(1f),
          )
        } else {
          DhtSuggestions(
            suggestions = suggestions,
            tally = tally,
            usesOf = usesOf,
            outcomes = outcomes,
            onOutcomeToggle = onOutcomeToggle,
            appliedOffsets = appliedOffsets,
            peekOffset = peekOffset,
            classified = classified,
            hiddenForUnused = hiddenForUnused,
            censusBlock = censusBlock,
            onApply = onApply,
            onPeek = onPeek,
            onPeekEnd = onPeekEnd,
            onExactValues = { onExactValues(true) },
            modifier = Modifier.fillMaxWidth().weight(1f),
          )
        }
      }
    }
  }
}

/**
 * The suggestions: a header that says where they came from, the filter chips
 * (which are the sweep's own folders), and one row per classified edit.
 */
@Composable
private fun DhtSuggestions(
  suggestions: List<SweepEdit>,
  tally: Map<SweepOutcome, Int>,
  usesOf: (SweepEdit) -> Int?,
  outcomes: Set<SweepOutcome>,
  onOutcomeToggle: (SweepOutcome) -> Unit,
  appliedOffsets: Set<Int>,
  peekOffset: Int?,
  classified: Boolean,
  hiddenForUnused: Int,
  censusBlock: CensusBlock?,
  onApply: (SweepEdit) -> Unit,
  onPeek: (SweepEdit) -> Unit,
  onPeekEnd: () -> Unit,
  onExactValues: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val rows = remember(suggestions, outcomes) {
    // Grouped by how many sample photos changed, strongest first: the chips
    // filter, the headings say what is left.
    OUTCOME_ORDER
      .filter { it in outcomes }
      .mapNotNull { outcome ->
        val entries = suggestions.filter { it.outcome == outcome }
        if (entries.isEmpty()) null else outcome to entries
      }
  }

  Column(modifier = modifier) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      if (classified) {
        Text(
          text = stringResource(R.string.dht_sweep_header, suggestions.size),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stringResource(R.string.dht_sweep_origin),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hiddenForUnused > 0) {
          Text(
            text = stringResource(R.string.dht_sweep_hidden, hiddenForUnused),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = stringResource(R.string.dht_sweep_gesture),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          text = stringResource(R.string.dht_sweep_unavailable),
          style = MaterialTheme.typography.bodyMedium,
        )
        censusBlockHintRes(censusBlock)?.let { hint ->
          Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OUTCOME_ORDER.forEach { outcome ->
          val count = tally[outcome] ?: 0
          if (count == 0) return@forEach
          DhtOutcomeChip(
            outcome = outcome,
            count = count,
            selected = outcome in outcomes,
            onClick = { onOutcomeToggle(outcome) },
          )
        }
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      rows.forEach { (outcome, entries) ->
        item(key = "head-${outcome.name}") {
          Text(
            text = stringResource(sectionTitleRes(outcome), entries.size),
            style = MaterialTheme.typography.labelLarge,
            color = if (outcome.isWreck) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
          )
        }
        items(entries, key = { "e-${it.offset}-${it.bit}" }) { edit ->
          DhtEditRow(
            edit = edit,
            applied = appliedOffsets.contains(edit.offset),
            peeking = peekOffset == edit.offset,
            uses = usesOf(edit),
            onApply = { onApply(edit) },
            onPeek = { onPeek(edit) },
            onPeekEnd = onPeekEnd,
          )
        }
      }
      item {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          TextButton(onClick = onExactValues) {
            Text(text = stringResource(R.string.dht_exact_open))
          }
          Text(
            text = stringResource(R.string.dht_exact_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(16.dp))
        }
      }
    }
  }
}

/** The four sample-photo counts, strongest first, then the wrecks. */
private val OUTCOME_ORDER = listOf(
  SweepOutcome.FOUR,
  SweepOutcome.THREE,
  SweepOutcome.TWO,
  SweepOutcome.ONE,
  SweepOutcome.WRECK_ALL,
  SweepOutcome.WRECK_THREE,
  SweepOutcome.WRECK_TWO,
  SweepOutcome.WRECK_ONE,
)

private fun sectionTitleRes(outcome: SweepOutcome): Int = when (outcome) {
  SweepOutcome.FOUR -> R.string.dht_section_four
  SweepOutcome.THREE -> R.string.dht_section_three
  SweepOutcome.TWO -> R.string.dht_section_two
  SweepOutcome.ONE -> R.string.dht_section_one
  else -> R.string.dht_section_wrecked
}

/** One filter chip, labelled with the folder it stands for and its size. */
@Composable
private fun DhtOutcomeChip(
  outcome: SweepOutcome,
  count: Int,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val label = when (outcome) {
    SweepOutcome.FOUR -> R.string.dht_filter_four
    SweepOutcome.THREE -> R.string.dht_filter_three
    SweepOutcome.TWO -> R.string.dht_filter_two
    SweepOutcome.ONE -> R.string.dht_filter_one
    SweepOutcome.WRECK_ALL -> R.string.dht_filter_wrecked
    else -> R.string.dht_filter_wrecked_some
  }
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text = stringResource(label, count)) },
    colors = FilterChipDefaults.filterChipColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      labelColor = MaterialTheme.colorScheme.onSurface,
      selectedContainerColor = if (outcome.isWreck) {
        MaterialTheme.colorScheme.errorContainer
      } else {
        MaterialTheme.colorScheme.primary
      },
      selectedLabelColor = if (outcome.isWreck) {
        MaterialTheme.colorScheme.onErrorContainer
      } else {
        MaterialTheme.colorScheme.onPrimary
      },
    ),
  )
}

/**
 * One classified edit: where it is, what it does to the code, how many of the
 * sample photos it changed, and how often this photo uses that code.
 *
 * The whole row is the target - tap to apply or take back, hold to see it first.
 */
@Composable
private fun DhtEditRow(
  edit: SweepEdit,
  applied: Boolean,
  peeking: Boolean,
  uses: Int?,
  onApply: () -> Unit,
  onPeek: () -> Unit,
  onPeekEnd: () -> Unit,
) {
  val accent = if (edit.outcome.isWreck) {
    MaterialTheme.colorScheme.error
  } else {
    MaterialTheme.colorScheme.primary
  }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 2.dp)
      .tapOrHold(onTap = onApply, onHoldStart = onPeek, onHoldEnd = onPeekEnd),
    shape = RoundedCornerShape(10.dp),
    color = when {
      peeking -> accent
      applied -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    contentColor = when {
      peeking -> MaterialTheme.colorScheme.surface
      applied -> MaterialTheme.colorScheme.onPrimaryContainer
      else -> MaterialTheme.colorScheme.onSurface
    },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (edit.isDc) Icons.Filled.LightMode else Icons.Filled.Contrast,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = if (applied || peeking) {
          androidx.compose.material3.LocalContentColor.current
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
      Spacer(Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = if (uses != null) {
            stringResource(
              R.string.dht_row_edit_uses,
              stringResource(familyTitleRes(familyOf(edit))),
              edit.symbolIndex,
              formatUses(uses),
            )
          } else {
            stringResource(
              R.string.dht_row_edit,
              stringResource(familyTitleRes(familyOf(edit))),
              edit.symbolIndex,
            )
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Text(
          text = stringResource(
            R.string.dht_row_change,
            meaningOf(edit.tableClass, edit.from),
            meaningOf(edit.tableClass, edit.to),
          ),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
        )
      }
      Spacer(Modifier.width(8.dp))
      if (applied) {
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = stringResource(R.string.dht_row_applied),
          modifier = Modifier.size(20.dp),
        )
      } else {
        Text(
          text = stringResource(R.string.dht_row_apply),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = accent,
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * The exact-value editor: every symbol of every table with a control over the
 * whole byte.
 *
 * This is deliberately behind its own button and labelled as unclassified. It
 * exists because a value that is more than one bit away from the file's own -
 * the kind a slider finds - has no measurement behind it, and the screen should
 * say that rather than pretend.
 */
@Composable
private fun DhtExactValues(
  tables: List<HuffmanTable>,
  symbolValues: (HuffmanSymbol) -> Int,
  appliedOffsets: Set<Int>,
  onSetValue: (HuffmanSymbol, Int) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.dht_exact_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) {
          Text(text = stringResource(R.string.dht_exact_close))
        }
      }
      Text(
        text = stringResource(R.string.dht_exact_body),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      tables.forEach { table ->
        item(key = "t-${table.tableClass}-${table.tableId}") {
          Text(
            text = stringResource(
              if (table.isChroma) R.string.dht_table_chroma else R.string.dht_table_luma,
            ) + " · " + stringResource(
              if (table.isAc) R.string.dht_table_ac else R.string.dht_table_dc,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
          )
        }
        items(table.entries, key = { "s-${it.offset}" }) { symbol ->
          DhtExactRow(
            symbol = symbol,
            value = symbolValues(symbol),
            edited = appliedOffsets.contains(symbol.offset),
            onValueChange = { value -> onSetValue(symbol, value) },
          )
        }
      }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }
}

/** One symbol with a slider over its whole byte. */
@Composable
private fun DhtExactRow(
  symbol: HuffmanSymbol,
  value: Int,
  edited: Boolean,
  onValueChange: (Int) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.dht_row_index, symbol.index),
      style = MaterialTheme.typography.labelMedium,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(36.dp),
      maxLines = 1,
    )
    Text(
      text = meaningOf(symbol.tableClass, value),
      style = MaterialTheme.typography.bodySmall,
      color = if (edited) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.onSurface
      },
      modifier = Modifier.weight(1f),
      maxLines = 1,
    )
    Slider(
      value = value.toFloat(),
      onValueChange = { onValueChange(it.roundToInt().coerceIn(0, symbol.maxValue)) },
      valueRange = 0f..symbol.maxValue.toFloat(),
      modifier = Modifier.weight(1.2f).systemGestureExclusion(),
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
      modifier = Modifier.width(34.dp),
      maxLines = 1,
      color = if (edited) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    )
  }
}

/** How often a code was used, as a short count. */
private fun formatUses(uses: Int): String = when {
  uses <= 0 -> "0"
  uses < 1_000 -> uses.toString()
  uses < 1_000_000 -> String.format(Locale.getDefault(), "%.1fk", uses / 1_000f)
  else -> String.format(Locale.getDefault(), "%.1fM", uses / 1_000_000f)
}

/** What a symbol byte stands for, in words. */
@Composable
private fun meaningOf(tableClass: Int, value: Int): String {
  if (tableClass == 0) return stringResource(R.string.dht_row_needs, value)
  val run = value shr 4
  val size = value and 0x0F
  return when {
    size == 0 && run == 15 -> stringResource(R.string.dht_meaning_zrl)
    size == 0 -> stringResource(R.string.dht_meaning_eob)
    else -> stringResource(R.string.dht_meaning_run_size, run, size)
  }
}

/** Why the code census could not be taken, as a string resource. */
private fun censusBlockHintRes(block: CensusBlock?): Int? = when (block) {
  null -> null
  CensusBlock.NO_FRAME -> R.string.dht_census_no_frame
  CensusBlock.NOT_SINGLE_SCAN -> R.string.dht_census_not_single_scan
  CensusBlock.NOT_INTERLEAVED -> R.string.dht_census_not_interleaved
  CensusBlock.HAS_RESTARTS -> R.string.dht_census_has_restarts
  CensusBlock.NO_TABLES -> R.string.dht_census_no_tables
  CensusBlock.UNDECODABLE -> R.string.dht_census_undecodable
}

/** What happened to a press before it either lifted or was taken away. */
private enum class PressOutcome { Lifted, Taken }

/**
 * Tap to apply, hold to peek.
 *
 * A press that lifts inside [HOLD_TO_PEEK_MS] applies the edit, so a slightly
 * slow tap still works. One still down after it peeks, and the peek ends when
 * the finger goes or the gesture is cancelled - [waitForUpOrCancellation]
 * returns null either way, so a peek cannot outlive its touch. A press the list
 * takes for a scroll is dropped rather than turned into a peek, which is why the
 * loop watches `isConsumed` as well as `pressed`.
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
        onHoldStart()
        waitForUpOrCancellation()
        onHoldEnd()
      }
    }
  }
}

// --- Previews ---------------------------------------------------------------

@Preview(name = "DHT", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DhtScreenPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      DhtScreenContent(
        preview = previewImageBitmap(),
        suggestions = emptyList(),
        tally = emptyMap(),
        usesOf = { null },
        outcomes = DEFAULT_OUTCOMES,
        onOutcomeToggle = {},
        appliedOffsets = emptySet(),
        peekOffset = null,
        classified = false,
        hiddenForUnused = 0,
        censusBlock = null,
        exactValues = false,
        tables = emptyList(),
        symbolValues = { it.value },
        brokenEdit = false,
        zoomScale = 1f,
        zoomOffset = Offset.Zero,
        onApply = {},
        onPeek = {},
        onPeekEnd = {},
        onSetValue = { _, _ -> },
        onExactValues = {},
        onBack = {},
        onInfo = {},
        onSkip = {},
        onNext = {},
        onResetAll = {},
      )
    }
  }
}
