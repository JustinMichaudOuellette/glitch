package ca.justinmo.glitch.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.ui.InfoScreen
import ca.justinmo.glitch.ui.InfoSection
import ca.justinmo.glitch.ui.theme.GlitchTheme
import ca.justinmo.glitch.R

/**
 * Explains what the scan screen edits: that the scan data *is* the picture rather than a
 * table describing it, why a press of flip walks the row instead of rolling for a bit, what
 * the selected row means, and what restart markers do for a glitch - the one
 * control here that is not a glitch at all but a way of bounding one.
 *
 * The parts worth drawing get drawn: the band is a row of 8x8 blocks rather than a row of
 * pixels, which is easier to see than to read, and the reach of one bad bit between two
 * markers is the whole reason the window slider and its button exist.
 */
@Composable
internal fun ScanInfoScreen(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  InfoScreen(
    title = stringResource(R.string.scan_info_title),
    onClose = onClose,
    modifier = modifier,
  ) {
    InfoSection(
      title = stringResource(R.string.scan_info_intro_title),
      body = stringResource(R.string.scan_info_intro_body),
    )
    InfoSection(
      title = stringResource(R.string.scan_info_flip_title),
      body = stringResource(R.string.scan_info_flip_body),
    )
    InfoSection(
      title = stringResource(R.string.scan_info_row_title),
      body = stringResource(R.string.scan_info_row_body),
    ) {
      Spacer(Modifier.height(12.dp))
      RowBandDiagram()
      Spacer(Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.scan_info_row_caption),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.scan_info_window_title),
      body = stringResource(R.string.scan_info_window_body),
    ) {
      Spacer(Modifier.height(12.dp))
      WindowDiagram()
      Spacer(Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.scan_info_window_caption),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.scan_info_window_button),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.scan_info_actions_title),
      body = stringResource(R.string.scan_info_actions_body),
    ) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.scan_info_actions_next),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.scan_info_tables_title),
      body = stringResource(R.string.scan_info_tables_body),
    )
    InfoSection(
      title = stringResource(R.string.scan_info_limits_title),
      body = stringResource(R.string.scan_info_limits_body),
    )
    Spacer(Modifier.height(32.dp))
  }
}

// --- Illustrations ----------------------------------------------------------

/**
 * The picture as a grid of blocks with one row of it lit: the row the next press of flip
 * lands in.
 *
 * The point of drawing it rather than saying it is the shape - the lit row runs the whole
 * width and is one block tall, not one pixel tall, which is why a band that looks like a
 * line on a phone is eight rows of pixels in the file, and why it steps rather than slides
 * as the slider is dragged.
 */
@Composable
private fun RowBandDiagram() {
  val block = MaterialTheme.colorScheme.surfaceContainerHigh
  val band = MaterialTheme.colorScheme.primary
  Canvas(modifier = Modifier.fillMaxWidth().height(104.dp)) {
    val gap = 3.dp.toPx()
    val cellWidth = (size.width - gap * (BAND_COLUMNS - 1)) / BAND_COLUMNS
    val cellHeight = (size.height - gap * (BAND_ROWS - 1)) / BAND_ROWS
    for (row in 0 until BAND_ROWS) {
      for (column in 0 until BAND_COLUMNS) {
        drawRect(
          color = if (row == BAND_ROW) band else block,
          topLeft = Offset(column * (cellWidth + gap), row * (cellHeight + gap)),
          size = Size(cellWidth, cellHeight),
        )
      }
    }
  }
}

/** Blocks drawn across [RowBandDiagram]; enough width to read as a picture. */
private const val BAND_COLUMNS = 6

/** Block rows drawn down [RowBandDiagram]. */
private const val BAND_ROWS = 8

/** Which of them is lit: wherever the band is, one of them is. */
private const val BAND_ROW = 3

/**
 * The scan data as one long bar, with the restart markers as ticks across it and the
 * damage from a single wrong bit filling the space between two of them.
 *
 * Read left to right it says what the window slider buys: inside a run the decoder has no
 * way to know a bit is wrong and keeps going, and a marker is where it is allowed to start
 * again - so the same wrong bit damages one band instead of everything after it.
 */
@Composable
private fun WindowDiagram() {
  val data = MaterialTheme.colorScheme.surfaceContainerHigh
  val damage = MaterialTheme.colorScheme.tertiary
  val marker = MaterialTheme.colorScheme.onSurface
  Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
    val barHeight = size.height * 0.5f
    val top = (size.height - barHeight) / 2f
    val step = size.width / MARKER_SPANS
    drawRect(color = data, topLeft = Offset(0f, top), size = Size(size.width, barHeight))
    // The damage, in the span that follows the second marker.
    drawRect(color = damage, topLeft = Offset(step * 2, top), size = Size(step, barHeight))
    for (i in 1 until MARKER_SPANS) {
      val x = step * i
      drawLine(
        color = marker,
        start = Offset(x, top - 7.dp.toPx()),
        end = Offset(x, top + barHeight + 7.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
      )
    }
  }
}

/** Spans of scan data drawn in [WindowDiagram], separated by markers. */
private const val MARKER_SPANS = 5

// --- Previews ---------------------------------------------------------------

@Preview(name = "Scan info", device = "spec:width=411dp,height=2000dp", showBackground = true)
@Composable
private fun ScanInfoScreenPreview() {
  GlitchTheme {
    ScanInfoScreen(onClose = {})
  }
}
