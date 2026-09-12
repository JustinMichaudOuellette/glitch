package ca.justinmo.glitch.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.STORIES_ASPECT
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.ui.theme.GlitchTheme
import ca.justinmo.glitch.R

/**
 * Standard title bar shared by every screen: a 56dp bar with an optional
 * leading action - a back arrow ([onBack]) or a close X ([onClose]) - and
 * the screen title centered next to it.
 *
 * The title is a label on every screen but two, and on those two it is a control - drawn
 * with a triangle after it, which points the way the tap goes. On the scan screen the
 * triangle points **down** and opens the Huffman tables, the deeper edit of the same file;
 * on the Huffman screen it points **up** and comes back to the scan. So the direction is
 * part of what the triangle says, and it is the reason the icon is a parameter here: down
 * is deeper into the file, up is back out of it.
 *
 * A title that does something is a smaller claim on the screen than another button would be
 * over the photo, but it has to say so - a title that silently did something would be a trap
 * - and the triangle is what says it. It also makes the title read as a menu, even though
 * what it opens is one screen rather than a list.
 *
 * [titleClickLabel] is what the tap is announced as, because the title alone does not say
 * what it does: a screen reader reads "Scan, [label], double tap to activate" rather than
 * leaving a user who cannot see the triangle to find out by pressing it. The triangle
 * itself is hidden from accessibility for the same reason - the label carries the meaning.
 */
@Composable
fun TitleBar(
  title: String,
  onBack: (() -> Unit)? = null,
  onClose: (() -> Unit)? = null,
  onInfo: (() -> Unit)? = null,
  onTitleClick: (() -> Unit)? = null,
  titleClickLabel: String = "",
  titleClickIcon: ImageVector = Icons.Filled.ArrowDropDown,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(56.dp),
  ) {
    val leading = onBack ?: onClose
    if (leading != null) {
      IconButton(
        onClick = leading,
        modifier = Modifier.align(Alignment.CenterStart),
        colors = IconButtonDefaults.iconButtonColors(
          contentColor = Color.White
        )
      ) {
        val imageVector = if (onBack != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close
        ShadowedIcon(imageVector = imageVector, contentDescription = if (onBack != null) stringResource(R.string.common_back) else stringResource(R.string.common_close))
      }
    }
    // The title is drawn the same either way. It is wrapped in a Row rather than drawn as
    // the Text it used to be so that the triangle sits in the same line box as the title,
    // and it is the *whole* of that Row that is clickable: a title whose text and triangle
    // were separately tappable would look like one control and behave like two.
    Row(
      modifier = Modifier
        .align(Alignment.Center)
        .then(
          if (onTitleClick != null) {
            Modifier.clickable(
              // Empty means no label rather than an empty one, which would be announced
              // as a pause.
              onClickLabel = titleClickLabel.ifEmpty { null },
              role = Role.Button,
              onClick = onTitleClick,
            )
          } else {
            Modifier
          }
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
          color = Color.White,
          shadow = createShadow()
        ),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (onTitleClick != null) {
        ShadowedIcon(
          imageVector = titleClickIcon,
          contentDescription = null,
          // A size down from the icon buttons either side of it: it hangs off the title
          // rather than being an action of its own, and at full size it reads as a third
          // button in the bar.
          modifier = Modifier.size(20.dp),
        )
      }
    }
    if (onInfo != null) {
      IconButton(
        onClick = onInfo,
        modifier = Modifier.align(Alignment.CenterEnd),
        colors = IconButtonDefaults.iconButtonColors(
          contentColor = Color.White
        )
      ) {
        ShadowedIcon(imageVector = Icons.Default.Info, contentDescription = stringResource(R.string.common_info))
      }
    }
  }
}

@Composable
private fun ShadowedIcon(
  imageVector: ImageVector,
  contentDescription: String?,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    Icon(
      imageVector = imageVector,
      contentDescription = null, // Hidden from accessibility
      tint = shadowColor(),
      modifier = Modifier
        .offset(x = 0.dp, y = 1.dp)
        .graphicsLayer {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = RenderEffect.createBlurEffect(
              4f,
              4f,
              Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
          }
        }
    )
    Icon(
      imageVector = imageVector,
      contentDescription = contentDescription,
    )
  }
}

internal fun createShadow(): Shadow = Shadow(
  color = shadowColor(),
  offset = Offset(0f, 2f),
  blurRadius = 4f
)

internal fun shadowColor(): Color = Color.Black.copy(alpha = 0.5f)

/** Centered full-screen spinner with a caption. */
@Composable
internal fun LoadingScreen(text: String) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * Window height from which the media keeps the whole window minus the bottom
 * button row. Below it the media fills the window and the controls are laid over
 * its bottom edge, so a small window never squeezes the preview into a strip
 * just to protect the buttons.
 */
val TALL_WINDOW_HEIGHT = 640.dp

/**
 * Height the bottom portion of a screen needs when the photo leaves it that much room: a
 * screen's controls - the sliders or chips it aims an edit with, and the row of action
 * buttons under them - take that much, and a pane with that much spare under the photo is
 * a pane whose controls can go *below* the picture instead of over it.
 *
 * It is one number for every screen rather than one each, because the arrangement it
 * decides is the same one everywhere and a screen whose controls are a little shorter
 * simply has a little more air in the band. The number is the busiest of them: the scan
 * screen's two sliders with their readouts and the action row beneath them.
 */
val STACKED_CONTROLS_HEIGHT = 260.dp

/**
 * The standard screen layout, used by every screen so the media and the buttons
 * always line up the same way.
 *
 * On a tall window the media keeps the whole window minus the bottom button row:
 * [overlay] is drawn *over* the media (bottom-aligned, so the media still runs
 * edge to edge behind it) and [controls] - the action buttons, and nothing else -
 * gets its own row underneath. On a short window the media fills the window and
 * both slots are laid over its bottom edge behind a scrim, so a small window
 * never squeezes the preview into a strip to protect the buttons.
 *
 * The preview slot is handed the [Modifier] to fill, so a caller that needs the
 * size - to choose between fitting and cropping the photo - can wrap it in a
 * BoxWithConstraints.
 */
@Composable
internal fun PreviewWithControls(
  preview: @Composable (Modifier) -> Unit,
  controls: @Composable ColumnScope.() -> Unit,
  modifier: Modifier = Modifier,
  overlay: @Composable ColumnScope.() -> Unit = {},
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    if (maxHeight >= TALL_WINDOW_HEIGHT) {
      Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
          preview(Modifier.fillMaxSize())

          // Overlay controls sit on the media, so it keeps running behind them.
          Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { overlay() }
        }

        // No weight: the bottom button row keeps its own height and the media
        // takes every pixel it leaves over.
        controls()
      }
    } else {
      Box(modifier = Modifier.fillMaxSize()) {
        preview(Modifier.fillMaxSize())

        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
          // Scrim: the controls and any error text stay readable over the photo.
          Box(
            modifier = Modifier
              .matchParentSize()
              .background(
                Brush.verticalGradient(
                  listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                ),
              ),
          )
          Column(modifier = Modifier.fillMaxWidth()) {
            overlay()
            controls()
          }
        }
      }
    }
  }
}

/**
 * Where a photo ended up inside the pane it was drawn in.
 *
 * One rule draws every screen's photo - at the pane's full width and centred, fitted whole
 * when that fits and covered (the overflow trimmed off the bottom) when it does not - and
 * every screen then needs the same arithmetic about the result: where the photo's own edges
 * are, how big a row of the file came out, and how much room is left under it for buttons.
 * That arithmetic lives here, once.
 *
 * Where the photo sits vertically depends on what is left for the controls, and that is the
 * second thing this carries: a photo with a screen's worth of room under it is moved down to
 * the middle of the space above them ([top]), so the picture is not stuck to the top of a
 * window with a quarter of the screen empty underneath, and its screen's controls do not
 * have to be drawn over it. A photo that owns the window - 9:16 on a phone - keeps the top,
 * which is what leaves the band under it for the buttons.
 */
internal data class PreviewFrame(
  /** The pane the photo was drawn in. */
  val paneWidth: Dp,
  val paneHeight: Dp,
  /** The photo as drawn: its own size, letterbox bars excluded. */
  val photoWidth: Dp,
  val photoHeight: Dp,
  /** Left edge of the drawn photo, from the left edge of the pane. */
  val left: Dp,
  /**
   * Top edge of the drawn photo, from the top edge of the pane. Zero unless the photo was
   * moved down to make room for the controls below it - see [stacksControls].
   */
  val top: Dp,
  /** True when the photo covers the pane rather than fitting inside it. */
  val coversPane: Boolean,
) {
  /**
   * Height left under the photo. This is where a tall window keeps its buttons, so
   * that the photo can run to its full size instead of being squeezed by them - the
   * arrangement the editor, the resize screen and the two table screens all use.
   */
  val roomBelow: Dp get() = (paneHeight - photoHeight).coerceAtLeast(0.dp)

  /** True when the window is tall enough and there is room under the photo to use. */
  val hasRoomBelow: Boolean
    get() = paneHeight >= TALL_WINDOW_HEIGHT && photoHeight < paneHeight

  /**
   * True when the room under the photo is enough for a screen's controls - the sliders or
   * chips it aims with and the action row under them - so they are drawn *below* the
   * picture rather than over its bottom edge.
   *
   * They have to be drawn over the picture when there is nowhere else for them: a 9:16
   * photo at the width of a phone owns the window, and a panel put under it would either
   * cover the bottom of the picture anyway or shrink the pane until the picture no longer
   * fitted - which is the cropping this app went to some trouble to stop doing. A picture
   * that leaves [STACKED_CONTROLS_HEIGHT] or more underneath it has no such excuse, and it
   * is the case a landscape photo is always in.
   */
  val stacksControls: Boolean
    get() = roomBelow >= STACKED_CONTROLS_HEIGHT
}

/**
 * The pane's half of the cover rule: true when the pane is shorter than a 9:16 frame of
 * its own width, which is the shape this app's pictures are cropped to.
 *
 * It is only half of it. Whether a *given* photo covers the pane also depends on the
 * photo - see [previewFrame] - and this is what stands in when there is no photo to
 * measure yet, which is the case the layout is first settled in.
 *
 * The overlay and controls slots of [PreviewWithControls] sit outside the pane and
 * have to work the same thing out for themselves, which is why this is a plain
 * function rather than something [MediaPreview] keeps to itself.
 */
internal fun photoCoversPane(paneWidth: Dp, paneHeight: Dp): Boolean =
  paneWidth / STORIES_ASPECT > paneHeight

/**
 * The [PreviewFrame] a photo of this size gets in a pane of this size: the same
 * arithmetic [MediaPreview] lays out with, for callers that are not the pane.
 *
 * [expectedSize] is what the photo is *going* to measure, in file pixels, for the frames
 * before [bitmap] has arrived - and it matters more than it sounds. A screen that only knows
 * how big the photo will be once it has decoded it has to lay itself out for the stand-in
 * below, which is a picture the size of the whole pane; the controls are put where a
 * pane-filling photo would want them, and then move when the real one lands. Every screen can
 * read the real numbers out of the file's header for nothing, so the layout can be right from
 * the first frame and only the pixels arrive late.
 */
internal fun previewFrame(
  bitmap: ImageBitmap?,
  paneWidth: Dp,
  paneHeight: Dp,
  expectedSize: Pair<Int, Int>? = null,
  coversPane: Boolean = photoCoversPane(paneWidth, paneHeight),
): PreviewFrame {
  val width = bitmap?.width?.takeIf { it > 0 } ?: expectedSize?.first ?: 0
  val height = bitmap?.height?.takeIf { it > 0 } ?: expectedSize?.second ?: 0
  if (width <= 0 || height <= 0) {
    // Nothing to measure, not even a header: the photo stands in for the whole pane, and
    // the layout is settled on that until something better is known.
    return PreviewFrame(paneWidth, paneHeight, paneWidth, paneHeight, 0.dp, 0.dp, coversPane)
  }
  return previewFrame(width, height, paneWidth, paneHeight, coversPane)
}

/**
 * The same arithmetic on the photo's size in pixels rather than on the photo.
 *
 * Split out for one reason: this is where every screen's layout is decided - whether
 * the picture is cut down or shown whole, where its edges are, how much room is left
 * under it - and it is pure arithmetic on numbers, so it can be checked by
 * `PreviewFrameTest` rather than by looking at a phone. The alternative was four
 * screens whose furniture is placed off an answer nobody could see was wrong.
 */
internal fun previewFrame(
  photoWidthPx: Int,
  photoHeightPx: Int,
  paneWidth: Dp,
  paneHeight: Dp,
  coversPane: Boolean = photoCoversPane(paneWidth, paneHeight),
): PreviewFrame {
  val widthPerPixel = paneWidth / photoWidthPx.toFloat()
  val heightPerPixel = paneHeight / photoHeightPx.toFloat()
  // The second half of the cover rule, and the one that is about the *photo* rather than
  // about the pane: a picture is only covered when drawing it at the pane's full width
  // would still be taller than the pane. That is the one case where covering gains
  // anything - the pane gets filled instead of the picture being cut down to a slice of
  // itself - and it is the case a landscape photo is never in. Asked of the pane alone it
  // was true on every phone, so a 4:3 photo was blown up to the height of the window and
  // had two thirds of its width cut off, with the buttons drawn over it because the pane
  // had no room left below. Now a picture that fits at full width simply fits: whole,
  // centred, with the room it leaves under itself going to the buttons.
  val covers = coversPane && widthPerPixel * photoHeightPx > paneHeight
  val perFilePixel =
    if (covers) maxOf(widthPerPixel, heightPerPixel) else minOf(widthPerPixel, heightPerPixel)
  val photoWidth = perFilePixel * photoWidthPx
  val photoHeight = perFilePixel * photoHeightPx
  // Where the photo sits, which is the other half of giving the controls a place to go: a
  // picture with a screen's worth of room under it is centred in what is left above that
  // room, so it reads as the subject of the screen rather than as something stuck to the
  // top of it with a gap underneath. A picture that leaves less than that keeps the top,
  // because the band under it is the button row's and the photo has to run to its own edge
  // for the furniture to line up with it.
  val roomBelow = paneHeight - photoHeight
  val top = if (roomBelow >= STACKED_CONTROLS_HEIGHT) {
    (roomBelow - STACKED_CONTROLS_HEIGHT) / 2
  } else {
    0.dp
  }
  return PreviewFrame(
    paneWidth = paneWidth,
    paneHeight = paneHeight,
    photoWidth = photoWidth,
    photoHeight = photoHeight,
    left = (paneWidth - photoWidth) / 2,
    top = top,
    coversPane = covers,
  )
}

/**
 * The preview every screen shows: the photo, drawn the one way this app draws it,
 * and whatever that screen puts over it.
 *
 * The screen brings its own zoom, its own decode and, through [content], its own
 * furniture - the title bar, a row of pattern buttons on the photo's bottom edge,
 * the band a flip is aimed at - and gets the [PreviewFrame] back, so none of it has
 * to work out again where the photo went. This is deliberately the only place the
 * photo itself is drawn. Each screen used to hold its own copy of the same twenty
 * lines and they drifted: the scan screen took the same crop rule the editor uses,
 * but its controls took the height the editor leaves to the preview, so the rule
 * was true on every phone and the photo was cut top and bottom every time.
 *
 * [gesture] is for the photo itself. A screen whose pinch works anywhere on the
 * screen - over a table panel and its rows as well as the picture - puts its gesture
 * on the screen instead and leaves this empty.
 */
@Composable
internal fun MediaPreview(
  bitmap: ImageBitmap?,
  contentDescription: String,
  modifier: Modifier = Modifier,
  zoomScale: Float = 1f,
  zoomOffset: Offset = Offset.Zero,
  gesture: Modifier = Modifier,
  /**
   * How the photo is resampled as it is drawn. Nearest-neighbour where the decode is
   * capped to about the size it is shown at, which keeps the block edges of a glitch
   * exact; a filtering mode where the preview is by definition a different size from
   * what is on screen - the resize screen and the editor, whose decode is capped at
   * 2000 px against a 1080 px window, where sampling without mipmaps is what turns
   * fine texture into a moire.
   */
  filterQuality: FilterQuality = FilterQuality.None,
  /**
   * What the photo will measure, in file pixels, while [bitmap] is still on its way - so the
   * pane can be laid out for the picture that is coming rather than for one that fills it.
   * See [previewFrame]: without it the furniture of a screen sits where a pane-filling photo
   * would want it for the first frames of every step, and then moves.
   */
  expectedSize: Pair<Int, Int>? = null,
  /** Shown while there is no bitmap yet. */
  placeholder: (@Composable () -> Unit)? = null,
  content: @Composable BoxWithConstraintsScope.(PreviewFrame) -> Unit = {},
) {
  BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
    val frame = previewFrame(bitmap, maxWidth, maxHeight, expectedSize)
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = Modifier
          .fillMaxSize()
          // The photo is drawn where the frame says it is. The inset is what moves it down
          // when the room under it is going to the controls ([PreviewFrame.top]) - the pane
          // it fits inside is shortened by exactly that much, which leaves the fitting
          // arithmetic alone: the photo is still drawn at the pane's full width, and the
          // frame still measures it from this edge rather than the pane's. Zero on every
          // screen that lays its controls over the picture, so nothing moves there.
          .padding(top = frame.top)
          .then(gesture)
          .graphicsLayer(
            scaleX = zoomScale,
            scaleY = zoomScale,
            translationX = zoomOffset.x,
            translationY = zoomOffset.y,
          ),
        contentScale = if (frame.coversPane) ContentScale.Crop else ContentScale.Fit,
        alignment = Alignment.TopCenter,
        filterQuality = filterQuality,
      )
    } else {
      placeholder?.invoke()
    }
    content(frame)
  }
}

/** Human-readable byte count (e.g. 1.4 MB / 320 B). */
@Composable
internal fun formatBytes(count: Int): String = when {
  count >= 1_000_000 -> stringResource(R.string.bytes_mb, count / 1_000_000f)
  count >= 1_000 -> stringResource(R.string.bytes_kb, count / 1_000f)
  else -> stringResource(R.string.bytes_b, count)
}

/**
 * One titled block of an explanation screen: a heading, a paragraph, and an
 * optional illustration underneath.
 */
@Composable
internal fun InfoSection(
  title: String,
  body: String,
  content: @Composable () -> Unit = {},
) {
  Spacer(Modifier.height(22.dp))
  Text(
    text = title,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(4.dp))
  Text(
    text = body,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  content()
}

/**
 * Full-screen explanation drawn over a screen: title bar plus a scrolling body.
 *
 * It is a plain composable in the same window as the screen underneath, not a
 * separate destination, so opening it never costs that screen its state.
 */
@Composable
internal fun InfoScreen(
  title: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Column(modifier = Modifier.fillMaxSize()) {
      TitleBar(title = title, onBack = onClose)
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp),
        content = content,
      )
    }
  }
}

/** String resource id of the short chip label for a glitch region. */
internal fun regionCodeRes(region: GlitchRegion): Int = when (region) {
  GlitchRegion.DQT -> R.string.region_code_dqt
  GlitchRegion.DHT -> R.string.region_code_dht
  GlitchRegion.SCAN -> R.string.region_code_scan
  GlitchRegion.RST -> R.string.region_code_rst
}

/** String resource id of the long description for a glitch region. */
internal fun regionDescriptionRes(region: GlitchRegion): Int = when (region) {
  GlitchRegion.DQT -> R.string.region_desc_dqt
  GlitchRegion.DHT -> R.string.region_desc_dht
  GlitchRegion.SCAN -> R.string.region_desc_scan
  GlitchRegion.RST -> R.string.region_desc_rst
}

// --- Previews ---------------------------------------------------------------

/** Loading state shown while [GlitchApp] imports an image shared into the app. */
@Preview(name = "Loading", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun LoadingScreenPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      LoadingScreen(stringResource(R.string.loading_shared_image))
    }
  }
}

/** Loading state shown by the source screen while a picked image is processed. */
@Preview(name = "Loading · image", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun LoadingScreenImagePreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      LoadingScreen(stringResource(R.string.source_loading_image))
    }
  }
}
