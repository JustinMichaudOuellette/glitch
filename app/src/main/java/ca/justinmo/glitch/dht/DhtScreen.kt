package ca.justinmo.glitch.dht

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.ArrowDropUp
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.ui.MediaPreview
import ca.justinmo.glitch.ui.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.dqt.NumberPickerCell
import ca.justinmo.glitch.dqt.previewTransformGestures
import ca.justinmo.glitch.jpeg.JpegParser
import ca.justinmo.glitch.ui.theme.GlitchTheme
import ca.justinmo.glitch.PreviewImage
import ca.justinmo.glitch.R
import ca.justinmo.glitch.ZoomState
import ca.justinmo.glitch.decodeSampled
import ca.justinmo.glitch.imageResolution
import ca.justinmo.glitch.ui.PreviewWithControls
import ca.justinmo.glitch.ui.ScanActionRow
import ca.justinmo.glitch.ui.STACKED_CONTROLS_HEIGHT
import ca.justinmo.glitch.ui.TitleBar
import ca.justinmo.glitch.ui.previewFrame
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
  family.tableClass == 1 -> R.string.dht_family_color_detail
  else -> R.string.dht_family_color_level
}

/** One line on what the table's symbols code. */
private fun familyBlurbRes(family: DhtFamily): Int = when {
  family.tableClass == 1 && !family.chroma -> R.string.dht_blurb_luma_detail
  family.tableClass == 0 && !family.chroma -> R.string.dht_blurb_luma_level
  family.tableClass == 1 -> R.string.dht_blurb_color_detail
  else -> R.string.dht_blurb_color_level
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
 * The deepest of the three editing screens, opened from the title of
 * [ca.justinmo.glitch.scan.ScanScreen], and confirming it carries on from there: retune the
 * **Huffman tables** by editing the symbols the codes stand for.
 *
 * It is not a stop on the flow's path - the quantization screen is the table screen that
 * is, because it sets how the picture is stored before the scan flips bits in it. This one
 * is reached from the scan instead, because the scan is the step whose file it edits, and
 * it is the deepest edit of the three and most pictures do not need it: one rewritten
 * symbol changes whole runs of blocks at once, so it must not stand between a user and the
 * scan. Its two ways out go different places, and that is the shape of a detour rather than
 * a step: back (and the triangle on the title) returns to the scan screen with nothing
 * applied, and next hands the edited file on to the final preview, as the next button of
 * any step does.
 *
 * The quantization screen picks *how coarsely* each frequency of the picture is
 * kept; this one picks *what each code means*. JPEG never stores a coefficient
 * directly - it stores a Huffman code whose symbol says how far along the block
 * to jump and how many bits the value needs - so rewriting a symbol rewrites
 * what every block that used that code decodes to. Nothing is rolled back:
 * whatever is set goes into the bytes handed back to
 * [ca.justinmo.glitch.scan.ScanScreen], exactly as on the DQT screen.
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
 *
 * What the user decides here - the symbol edits and the undo trail behind them, which
 * table is open, which switches are on - is held in [state] so that it survives the
 * screen: walking back to the scan and opening this screen again must meet the same
 * edits rather than clean tables. The pinch-zoom comes from [zoom] for the same reason,
 * and is the one the flow shares between its steps. Everything read off the file is left
 * local.
 *
 * That trail is a *difference* per step rather than a copy of the edits map per step,
 * which follows from where the trail now lives: a stack that only has to outlast the
 * frame can afford to carry the whole map per press, and one that lasts as long as the
 * session cannot. [DhtState.history] says the rest.
 */
@Composable
internal fun DhtScreen(
  bytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  onBack: () -> Unit,
  onContinue: (bytes: ByteArray, scale: Float, offset: Offset) -> Unit,
  // The zoom and the edits are handed in rather than remembered in here, because both
  // belong to the edit rather than to the frame: the flow keeps one zoom across its
  // steps, and this step's tables have to still be the tables they were when the user
  // walks back to them. The defaults keep the screen usable on its own - a preview, or
  // a caller with nothing to hand in - by doing exactly what the removed `remember`
  // did: a zoom started where [initialScale] / [initialOffset] say, and no edits.
  zoom: ZoomState = remember(initialScale, initialOffset) { ZoomState(initialScale, initialOffset) },
  /**
   * The flow's shared decoded preview: the first frame here draws the picture the scan screen
   * was showing rather than a blank over a re-decode of the same file with the tables one edit
   * behind - which is exactly what the user is in the middle of doing.
   */
  previewImage: PreviewImage = remember { PreviewImage() },
  state: DhtState = remember { DhtState() },
) {
  val structure = remember(bytes) { JpegParser.parse(bytes) }
  val tables = remember(bytes, structure) { HuffmanTables.parse(bytes, structure) }

  // What the photo measures, from the header, with the EXIF rotation the decoder applies:
  // the tile row and the buttons are placed off the frame, so knowing the shape before the
  // decode lands is what keeps them still.
  val shape = remember(bytes) { imageResolution(bytes) }

  // The edits, the undo trail, the open sheet and the panel's switches are not here:
  // they live in [state], which the view model owns, because they have to outlive this
  // composable. Coming back to this screen from the scan must show the edits that
  // were made, not a clean set of tables; a `remember` would throw them away with the
  // composition. The comments that used to explain what each one was for are on the
  // fields in [DhtState], where the same reasoning now applies - including why the
  // undo trail records the difference each edit made rather than the map either side
  // of it, which is a question that only arises because the trail outlives the screen.
  //
  // What deliberately stays local is everything read off the file and the edits - the
  // parsed structure and tables, the sweep classification, the census and its block
  // reason, the decoded preview and its "no longer decodes" flag, and the measured
  // bounds. They are rebuilt from the bytes this screen is handed, and a copy carried
  // across navigation would describe a file that is no longer underneath.
  var preview by remember(bytes) {
    mutableStateOf(previewImage.showable(bytes, shape))
  }
  var previewBroken by remember(bytes) { mutableStateOf(false) }

  // Which codes the photo actually uses, and why that could not be counted.
  var census by remember(bytes) { mutableStateOf<HuffmanCensus?>(null) }
  var censusBlock by remember(bytes) { mutableStateOf<CensusBlock?>(null) }
  // True once the attempt has finished, whether it produced a census or a reason.
  // Flip waits for it so an early press cannot land on a symbol this photo never
  // emits - before the count lands every symbol would look used.
  var censusDone by remember(bytes) { mutableStateOf(false) }

  // The tile the sliding sheet is drawing, and the boxes the pinch measures against.
  // All of them are about the frame rather than about the file: which table is open is
  // [state.openFamily], and this is only the copy the exit animation needs, while the
  // bounds are measurements of a layout that is measured again when the screen comes
  // back - a remembered rectangle would point at where things used to be.
  var shownFamily by remember { mutableStateOf<DhtFamily?>(null) }
  var previewBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var panelBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  var screenBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
  LaunchedEffect(state.openFamily) {
    if (state.openFamily == null) panelBoundsInRoot = null
  }

  val applied = remember(bytes, state.edits) {
    if (state.edits.isEmpty()) bytes
    else bytes.copyOf().also { HuffmanTables.applyValues(it, state.edits) }
  }

  // The classification from the b_x4 sweep, by the symbol byte it belongs to:
  // eight measured bit flips per code, which the rows draw as a strip.
  val classified = remember(bytes, structure) {
    DhtSweep.appliesTo(HuffmanTables.payloadOf(bytes, structure))
  }
  val flips = remember(tables, classified) {
    if (classified) DhtSweep.editsFor(tables).groupBy { it.offset } else emptyMap()
  }

  LaunchedEffect(applied, shape) {
    if (previewImage.covers(applied, DHT_PREVIEW_CAP_PX)) {
      previewImage.showable(applied, shape)?.let { preview = it }
      previewBroken = false
      return@LaunchedEffect
    }
    val decoded = withContext(Dispatchers.Default) {
      decodeSampled(
        applied,
        DHT_PREVIEW_CAP_PX
      )
    }
    // A DC symbol of 16 or more, or an offset outside the file, is the one edit that can
    // make the result stop opening - say so rather than showing nothing.
    previewBroken = decoded == null && state.edits.isNotEmpty()
    when {
      decoded != null -> {
        previewImage.put(applied, DHT_PREVIEW_CAP_PX, decoded)
        preview = decoded
      }
      // The deliberate blank: the banner below explains it, and a picture that no longer
      // comes out of the file is the one thing worse to show than an empty pane.
      previewBroken -> preview = null
      // Anything else keeps what is on screen, which is the last step's frame of this same
      // picture rather than nothing at all.
    }
  }

  // The census reads the file as it arrived: symbol *values* are what the user
  // edits, and the codes keep occurring exactly as often however they are
  // rewritten. It needs the tables as parsed, so it runs once per image.
  LaunchedEffect(bytes, tables) {
    val block = if (tables.isEmpty()) CensusBlock.NO_TABLES else HuffmanCensusReader.blockFor(structure)
    if (block != null) {
      censusBlock = block
      census = null
      censusDone = true
      return@LaunchedEffect
    }
    val counted = withContext(Dispatchers.Default) { HuffmanCensusReader.read(bytes, structure, tables) }
    // A null here means the walk lost the stream partway through, which is worth
    // saying rather than silently falling back to the code-length ordering.
    censusBlock = if (counted == null) CensusBlock.UNDECODABLE else null
    census = counted
    censusDone = true
  }

  // Where a press of Flip may land: only codes this photo emits (when the census
  // could be read) and only symbols with a bit the sweep found better than fatal
  // or a no-op. The strip still offers every classified bit by hand; this pool is
  // for the random draw alone.
  val flipCandidates = remember(tables, flips, census) {
    DhtSweep.randomFlipCandidates(tables, flips, census)
  }

  BackHandler(enabled = state.openFamily != null) { state.openFamily = null }
  BackHandler(enabled = state.showInfo) { state.showInfo = false }

  // The pinch writes through to [zoom] rather than to a copy of it, so the framing the
  // user leaves here is the framing the scan screen behind this one goes on showing - and
  // the one this screen is drawn with again when it is opened from there.
  val transform: (Float, Offset) -> Unit = { zoomChange, panChange ->
    zoom.scale = (zoom.scale * zoomChange).coerceIn(0.5f, 5f)
    zoom.offset += panChange
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onGloballyPositioned { screenBoundsInRoot = it.boundsInRoot() }
      .then(
        if (state.showInfo) {
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
      expectedSize = shape,
      tables = tables,
      edits = state.edits,
      flips = flips,
      classified = classified,
      brokenEdit = previewBroken,
      zoomScale = zoom.scale,
      zoomOffset = zoom.offset,
      onPreviewBounds = { previewBoundsInRoot = it },
      onBack = onBack,
      onInfo = { state.showInfo = true },
      onOpenFamily = { family ->
        shownFamily = family
        state.openFamily = family
      },
      onNext = { onContinue(applied, zoom.scale, zoom.offset) },
      onResetAll = {
        // Clearing everything is an edit like any other, so undo can bring the whole
        // map back. What goes on the stack is the difference, so this records one entry
        // per edited symbol and nothing at all when there is nothing to clear - the
        // reset button is greyed out until there is, so that second case is a no-op
        // either way.
        val next = emptyMap<Int, Int>()
        state.history.record(before = state.edits, after = next)
        state.edits = next
        state.lastEdited = null
      },
      onUndo = {
        // Only a stack with something on it walks a step back: an empty one leaves the
        // edits exactly as they are, and has to leave the drag guard alone too.
        if (!state.history.isEmpty) {
          state.edits = state.history.undo(state.edits)
          state.lastEdited = null
        }
      },
      // Read off the stack itself rather than off a copy of it: the holder is Compose
      // state, so a step pushed by any edit below re-draws this row with undo live.
      canUndo = !state.history.isEmpty,
      // Flip is offered only when the draw has somewhere useful to land: the
      // census has finished, the file's tables match the sweep, and at least one
      // symbol this photo emits has a bit that is neither fatal nor a no-op.
      // Before that, every symbol looks used and a press could land in bytes the
      // decoder never reads - or fall through to a fatal bit.
      canFlip = censusDone && flipCandidates.isNotEmpty(),
      onFlip = {
        // The symbol comes from the live pool built above. The bit is weighted by
        // the sweep inside it, with the categories that changed nothing or killed
        // the file left out - they stay tappable on the strip, where they are
        // evidence to choose deliberately rather than a random landing.
        if (flipCandidates.isNotEmpty()) {
          // Seeded from the clock, so the draw differs between runs of the app and
          // between presses. The press number rides along with it because a seed of
          // milliseconds alone would repeat for two presses inside the same one - and
          // it is counted in [state], so the count carries on where it left off when
          // the user walks back to this step rather than starting from zero again.
          val random = kotlin.random.Random(System.currentTimeMillis() + state.flipStep)
          val symbol = flipCandidates[random.nextInt(flipCandidates.size)]
          val target = symbol.offset
          val current = state.edits[target] ?: symbol.value
          val candidates = flips[target].orEmpty()
            .filter { it.outcome != SweepOutcome.FATAL && it.outcome != SweepOutcome.NOTHING }
            .map { it.bit to it.outcome }
          val bit = FlipChoice.choose(candidates, random)
          if (bit != null) {
            // The new map is built first and the change recorded before it is handed
            // over: what goes on the stack is the *difference* between the two - this
            // symbol, the value it had and the value it now has - which is all undo
            // needs, and a few bytes rather than another copy of the map.
            val next = state.edits + (target to (current xor (1 shl bit)))
            state.history.record(before = state.edits, after = next)
            state.edits = next
            state.lastEdited = target
            state.flipStep += 1
          }
        }
      },
    )

    AnimatedVisibility(
      visible = state.openFamily != null,
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
            edits = state.edits,
            flips = flips,
            classified = classified,
            census = census,
            censusBlock = censusBlock,
            onlyUsed = state.onlyUsed,
            onOnlyUsedChange = { state.onlyUsed = it },
            // The slider and the strip under the row both arrive here, so this is the
            // one place a symbol's value changes. The slider reports every step of a
            // drag and the strip reports one tap, and the two have to leave the same
            // kind of trail behind them.
            onValueChange = { symbol, value ->
              // Setting a symbol back to the value the file already has drops the
              // edit, so the counts and badges stay honest.
              val next = if (value == symbol.value) {
                state.edits - symbol.offset
              } else {
                state.edits + (symbol.offset to value)
              }
              // A drag reports continuously, so only the first change to a symbol
              // records a step: one press of undo puts back what the whole drag swept
              // away, rather than one tick of it. What is recorded is the difference,
              // so that single entry carries the value the symbol had when the drag
              // began - and "no entry at all" where the file's own value was showing.
              //
              // A tick that lands back on the value that was already there changes
              // nothing and so records nothing; leaving [DhtState.lastEdited] alone
              // then is what stops the real change that follows it in the same drag
              // from being swallowed as if it had been recorded.
              if (state.lastEdited != symbol.offset) {
                if (next != state.edits) {
                  state.history.record(before = state.edits, after = next)
                  state.lastEdited = symbol.offset
                }
              }
              state.edits = next
            },
            onResetSymbol = { symbol ->
              // Clearing a symbol is an edit like any other, so undo brings the value
              // back - but only when there was something set to clear, so a press on a
              // clean row leaves the trail alone. The drag guard is dropped with it:
              // the next drag on this symbol has to leave a step of its own rather
              // than be counted as part of the edit that has just been taken back.
              if (state.edits.containsKey(symbol.offset)) {
                val next = state.edits - symbol.offset
                state.history.record(before = state.edits, after = next)
                state.edits = next
                state.lastEdited = null
              }
            },
            onResetTable = { target ->
              val offsets = target.entries.mapTo(HashSet()) { it.offset }
              // The same rule one table up: only a table that carries edits leaves a
              // step, and what it leaves is the difference - the symbols it cleared,
              // each with the value it was showing.
              if (state.edits.keys.any { it in offsets }) {
                val next = state.edits.filterKeys { it !in offsets }
                state.history.record(before = state.edits, after = next)
                state.edits = next
                state.lastEdited = null
              }
            },
            onFamilyChange = { state.openFamily = it },
            onClose = { state.openFamily = null },
            onBounds = { panelBoundsInRoot = it },
          )
        }
      }
    }

    // Drawn last, so the explanation covers the panel and the tile row.
    if (state.showInfo) {
      DhtInfoScreen(onClose = { state.showInfo = false })
    }
  }
}

/**
 * Stateless body of [DhtScreen]: the preview with the table tiles over its
 * bottom edge and the action row under it, laid out exactly like the DQT
 * screen. Decoding, byte editing and the code census live in [DhtScreen], so
 * this can be rendered - and previewed - on its own.
 *
 * The pane itself is the shared [MediaPreview], so where the photo ended up -
 * and whether it left room under itself - is handed back rather than worked out
 * here as well. The overlay and control slots sit outside that pane and still
 * have to ask the same question, which is what [previewFrame] is for: one copy
 * of the arithmetic, so the rows cannot be drawn twice or not at all.
 */
@Composable
internal fun DhtScreenContent(
  preview: ImageBitmap?,
  /**
   * What the photo measures, for the frames before [preview] has arrived - so the tile row and
   * the buttons are laid out where the picture will be rather than where a pane-filling stand-in
   * would be, and do not move when the decode lands.
   */
  expectedSize: Pair<Int, Int>? = null,
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
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  onUndo: () -> Unit,
  onFlip: () -> Unit,
  canUndo: Boolean,
  canFlip: Boolean,
  modifier: Modifier = Modifier,
  brokenEdit: Boolean = false,
) {
  PreviewWithControls(
    modifier = modifier,
    preview = { previewModifier ->
      // The pane is the shared MediaPreview: it owns the one way this app draws a
      // photo, and the rule that a pane too short for a 9:16 frame gets a covering
      // photo rather than a letterboxed one, so this screen no longer works any of
      // that out for itself. What it gets back is a PreviewFrame - where the
      // picture actually landed - which is what the furniture below is measured
      // against: the tile row rides the photo's bottom edge, and the buttons take
      // the band under it. Sampling is left at the shared default: this screen's
      // decode is capped at roughly the size it is drawn, so nearest-neighbour
      // keeps the block edges of a glitch exact, exactly as the old local Image
      // asked for.
      MediaPreview(
        bitmap = preview,
        contentDescription = stringResource(R.string.dht_preview_desc),
        modifier = previewModifier.onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) },
        zoomScale = zoomScale,
        zoomOffset = zoomOffset,
        expectedSize = expectedSize,
        // Deliberately no gesture: this screen's pinch lives on the outer Box (see
        // [DhtScreen]) so that it keeps working over the symbol panel as well as
        // over the picture, and a detector on the photo would take those drags
        // first and leave the panel unable to pan.
      ) { frame ->
        Box(modifier = Modifier.fillMaxWidth()) {
          TitleBar(
            title = stringResource(R.string.dht_title),
            onBack = onBack,
            onInfo = onInfo,
            // The mirror of the scan screen's title: that one carries a downward triangle
            // and opens these tables, and this one carries an upward triangle and comes
            // back to it. It calls the same thing the back arrow does - the triangle is
            // the way back said out loud, for a user who arrived by tapping a triangle and
            // is looking for the one that undoes it.
            onTitleClick = onBack,
            titleClickLabel = stringResource(R.string.dht_back_to_scan),
            titleClickIcon = Icons.Filled.ArrowDropUp,
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

        // A photo with a screen's worth of room under it keeps that room for the controls:
        // the tile row and the action row are stacked in the bottom portion with the picture
        // centred above them, rather than the tiles riding a bottom edge that on a landscape
        // photo is halfway up the screen.
        if (frame.stacksControls) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .height(STACKED_CONTROLS_HEIGHT)
              .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.SpaceBetween,
          ) {
            DhtFamilyRow(tables = tables, edits = edits, onOpenFamily = onOpenFamily)
            DhtActionsRow(
              edits = edits,
              onNext = onNext,
              onResetAll = onResetAll,
              onUndo = onUndo,
              onFlip = onFlip,
              canUndo = canUndo,
              canFlip = canFlip,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        } else if (frame.hasRoomBelow) {
          Box(
            modifier = Modifier.fillMaxWidth().height(frame.photoHeight),
            contentAlignment = Alignment.BottomCenter,
          ) {
            DhtFamilyRow(tables = tables, edits = edits, onOpenFamily = onOpenFamily)
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(frame.roomBelow)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
          ) {
            DhtActionsRow(
              edits = edits,
              onNext = onNext,
              onResetAll = onResetAll,
              onUndo = onUndo,
              onFlip = onFlip,
              canUndo = canUndo,
              canFlip = canFlip,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        }
      }
    },
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The pane's own rule, asked again from outside the pane: MediaPreview only
        // draws the tile row itself when the photo left room below it, so this slot
        // draws it in every other case. The frame comes from the shared arithmetic
        // rather than a second copy of it, so the two cannot drift apart.
        val frame = previewFrame(preview, maxWidth, maxHeight, expectedSize)
        if (!frame.hasRoomBelow) {
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
        // Same rule as the pane's, asked again from outside it: with no room under
        // the photo the buttons have to sit over the picture, in the scrim
        // PreviewWithControls draws for a window too short for a separate row.
        val frame = previewFrame(preview, maxWidth, maxHeight, expectedSize)
        if (!frame.hasRoomBelow) {
          DhtActionsRow(
            edits = edits,
            onNext = onNext,
            onResetAll = onResetAll,
            onUndo = onUndo,
            onFlip = onFlip,
            canUndo = canUndo,
            canFlip = canFlip,
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
 * file carries one DC and one AC table, so the color tiles have nothing).
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

/**
 * Reset / undo / flip / next, on the shared action bar.
 *
 * All four are here, and this paragraph used to say otherwise: it claimed the screen
 * had three of them because its controls *set* a symbol rather than flipping a bit, so
 * there was "nothing to flip", and because its edits were "a map that is reset rather
 * than a history that is unwound", so there was nothing to undo. Both halves were
 * wrong about the code beneath them. A symbol can be flipped with nothing to lean on -
 * the strip under each row offers the eight flips the `b_x4` sweep measured, so flip
 * is a real action here and not a stand-in - and every edit on this screen records what
 * it changed on [DhtState.history] before it lands, so undo walks back through them one
 * press at a time. What it records is the difference - the symbols this edit moved and
 * the values they had - rather than a copy of the edits map, because the stack now
 * outlives the screen and a map per press would grow with the session. Reset stays put
 * and greys out until there is something to clear, the same as on the DQT screen: a row
 * whose buttons come and go is harder to aim at than one that always has the same four
 * in the same places.
 *
 * The four flags are worked out on the recomposition that drew the row, and none of
 * them is remembered anywhere: `edits` is [DhtState.edits] and `canUndo` is
 * `!state.history.isEmpty`, both read in the screen body a frame above, and flip is
 * offered only while the draw has a symbol this photo uses with a bit the sweep
 * found worth taking - the pool is rebuilt whenever the census or the classification
 * changes. That is the part that can go wrong with a bar like this: a flag taken from a
 * copy - a `remember`ed map, a val hoisted above the recomposing scope, a count taken
 * before the holder moved - freezes the button on the frame it was drawn on, which is
 * exactly a reset that stays grey after the first edit. `canUndo` is the stack's own
 * `isEmpty` rather than a call made over it, so what the button hangs on is read off the
 * holder the edits themselves write to.
 */
@Composable
private fun DhtActionsRow(
  edits: Map<Int, Int>,
  onNext: () -> Unit,
  onResetAll: () -> Unit,
  onUndo: () -> Unit,
  onFlip: () -> Unit,
  canUndo: Boolean,
  canFlip: Boolean,
  modifier: Modifier = Modifier,
) {
  ScanActionRow(
    modifier = modifier,
    onNext = onNext,
    nextEnabled = true,
    // Reset stays put and greys out until there is something to clear: a row whose
    // buttons come and go is harder to aim at than one that always has the same four
    // in the same places.
    onReset = onResetAll,
    resetEnabled = edits.isNotEmpty(),
    onUndo = onUndo,
    undoEnabled = canUndo,
    onFlip = onFlip,
    flipEnabled = canFlip,
  )
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
 * The sections the panel shows, and the order the rows are listed in.
 *
 * The sections are the census's split - codes this photo emits, and codes it
 * never does - because that is the difference between an edit that shows and an
 * edit that cannot.
 *
 * Within a section the rows run from the highest symbol index down. On a level
 * table the index is the step the code carries, so the list opens with the
 * biggest one the photo uses; on a detail table it is how far along the block the
 * code reaches. That is the opposite of ordering them by how often the photo
 * emits them, which is what this did: frequency puts the smallest steps first,
 * and those are the edits that change least.
 */
internal fun dhtGroups(
  table: HuffmanTable,
  census: HuffmanCensus?,
  onlyUsed: Boolean,
): List<DhtGroup> {
  fun byLength(): List<DhtGroup> =
    table.entriesByLength().map { (length, entries) ->
      DhtGroup.Length(length, entries.sortedByDescending { it.index })
    }

  if (census == null) return byLength()
  val used = table.entries
    .filter { census.uses(it) > 0 }
    .sortedByDescending { it.index }
  val unused = table.entries
    .filter { census.uses(it) == 0 }
    .sortedByDescending { it.index }
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
            // A grayscale file carries one DC and one AC table, so the color
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
 * Under the control sits [DhtBitStrip]: one cell per bit of the value, colored
 * by what the `b_x4` sweep measured for that flip. The slider is the continuous
 * control and the strip is the measured one - the two answers to "what should
 * this byte be?", side by side, which is what earlier versions of this screen
 * got wrong by offering only one of them.
 *
 * The AC tables get a [Slider] over 0-255 - dragging it sweeps the magnitude
 * nibble first and then the run nibble, so the fine control is the one that
 * changes the picture most - and the label above it spells out the pair the
 * value stands for. The DC tables get a [ca.justinmo.glitch.dqt.NumberPickerCell] instead, for the same
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
 * One cell per bit of the value byte, in bit order, colored by what the `b_x4`
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

/** The color of one strip cell: what the sweep filed that flip as. */
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
        onNext = {},
        onResetAll = {},
        onUndo = {},
        onFlip = {},
        canUndo = false,
        canFlip = false,
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
        onNext = {},
        onResetAll = {},
        onUndo = {},
        onFlip = {},
        canUndo = false,
        canFlip = false,
      )
    }
  }
}





