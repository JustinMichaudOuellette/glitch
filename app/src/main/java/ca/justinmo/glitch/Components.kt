package ca.justinmo.glitch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.jpeg.GlitchRegion

/**
 * Standard title bar shared by every screen: a 56dp bar with an optional
 * leading action - a back arrow ([onBack]) or a close X ([onClose]) - and
 * the screen title centered next to it.
 */
@Composable
internal fun TitleBar(
  title: String,
  isAboveMedia: Boolean,
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
        shadow = Shadow(
          color = Color.Black.copy(alpha = 0.5f),
          offset = Offset(0f, 2f),
          blurRadius = 4f
        )
      ),
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

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
}
