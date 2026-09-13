package ca.justinmo.glitch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.jpeg.GlitchRegion
import ca.justinmo.glitch.ui.theme.GlitchTheme

/**
 * Standard title bar shared by every screen: a 56dp bar with an optional
 * leading action - a back arrow ([onBack]) or a close X ([onClose]) - and
 * the screen title centered next to it.
 */
@Composable
internal fun TitleBar(
  title: String,
  onBack: (() -> Unit)? = null,
  onClose: (() -> Unit)? = null,
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
          containerColor = Color.Black.copy(alpha = 0.4f),
          contentColor = Color.White
        )
      ) {
        Icon(
          imageVector = if (onBack != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
          contentDescription = if (onBack != null) {
            stringResource(R.string.common_back)
          } else {
            stringResource(R.string.common_close)
          },
        )
      }
    }
    Text(
      text = title,
      modifier = Modifier.align(Alignment.Center),
      style = MaterialTheme.typography.titleLarge.copy(
        color = Color.White,
        shadow = createShadow()
      ),
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

internal fun createShadow(): Shadow = Shadow(
  color = Color.Black.copy(alpha = 0.5f),
  offset = Offset(0f, 2f),
  blurRadius = 4f
)

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
internal val TALL_WINDOW_HEIGHT = 640.dp

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

/** Human-readable byte count (e.g. 1.4 MB / 320 B). */
@Composable
internal fun formatBytes(count: Int): String = when {
  count >= 1_000_000 -> stringResource(R.string.bytes_mb, count / 1_000_000f)
  count >= 1_000 -> stringResource(R.string.bytes_kb, count / 1_000f)
  else -> stringResource(R.string.bytes_b, count)
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
