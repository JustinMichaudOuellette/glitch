package ca.justinmo.glitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.ui.theme.GlitchTheme

/**
 * Explains why the resize step is worth taking before glitching: JPEG works in
 * 8x8 squares, so the smaller the picture, the bigger a share of it each square
 * covers - and the fewer squares there are to redo on every change.
 *
 * Illustrated with vectors: the block grids and the work bars are drawn with
 * `Canvas`, nothing is generated as a bitmap.
 */
@Composable
internal fun ResizeInfoScreen(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  InfoScreen(
    title = stringResource(R.string.resize_info_title),
    onClose = onClose,
    modifier = modifier,
  ) {
    InfoSection(
      title = stringResource(R.string.resize_info_blocks_title),
      body = stringResource(R.string.resize_info_blocks_body),
    ) {
      Spacer(Modifier.height(12.dp))
      BlockGridPanel(
        columns = 8,
        rows = 8,
        caption = stringResource(R.string.resize_info_blocks_one),
      )
    }
    InfoSection(
      title = stringResource(R.string.resize_info_size_title),
      body = stringResource(R.string.resize_info_size_body),
    ) {
      Spacer(Modifier.height(12.dp))
      BlockSizeComparison()
    }
    InfoSection(
      title = stringResource(R.string.resize_info_speed_title),
      body = stringResource(R.string.resize_info_speed_body),
    ) {
      Spacer(Modifier.height(14.dp))
      BlockCountBars(
        stringResource(R.string.resize_info_speed_original) to 120_000,
        stringResource(R.string.resize_info_speed_default) to 13_770,
        stringResource(R.string.resize_info_speed_small) to 7_500,
      )
    }
    InfoSection(
      title = stringResource(R.string.resize_info_controls_title),
      body = stringResource(R.string.resize_info_controls_body),
    )
    Spacer(Modifier.height(32.dp))
  }
}

/**
 * One 8x8 block, enlarged: the unit JPEG stores. The grid lines are the block's
 * own pixels, so the caption can point at them.
 */
@Composable
private fun BlockGridPanel(
  columns: Int,
  rows: Int,
  caption: String,
  modifier: Modifier = Modifier,
) {
  val fill = MaterialTheme.colorScheme.surfaceContainerHighest
  val line = MaterialTheme.colorScheme.primary
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Canvas(
      modifier = Modifier
        .size(width = 160.dp, height = 160.dp)
        .clip(RoundedCornerShape(6.dp)),
    ) {
      drawRect(color = fill)
      val stepX = size.width / columns
      val stepY = size.height / rows
      val stroke = (minOf(stepX, stepY) * 0.08f).coerceAtLeast(1f)
      for (column in 0..columns) {
        drawLine(
          color = line.copy(alpha = 0.85f),
          start = Offset(column * stepX, 0f),
          end = Offset(column * stepX, size.height),
          strokeWidth = stroke,
        )
      }
      for (row in 0..rows) {
        drawLine(
          color = line.copy(alpha = 0.85f),
          start = Offset(0f, row * stepY),
          end = Offset(size.width, row * stepY),
          strokeWidth = stroke,
        )
      }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      text = caption,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * The same picture at two sizes, shown the same size on screen. Both are built
 * from 8-pixel blocks, so the small one has far fewer of them and each covers
 * much more of the frame - which is what makes a table edit so much plainer to
 * see there.
 */
@Composable
private fun BlockSizeComparison() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    BlockGridPanel(
      columns = 8,
      rows = 8,
      caption = stringResource(R.string.resize_info_size_small),
      modifier = Modifier.weight(1f),
    )
    BlockGridPanel(
      columns = 32,
      rows = 32,
      caption = stringResource(R.string.resize_info_size_large),
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * How many blocks each size has to describe, as bars on one scale. A quarter of
 * the width and height leaves a sixteenth of the work.
 */
@Composable
private fun BlockCountBars(vararg rows: Pair<String, Int>) {
  val fill = MaterialTheme.colorScheme.primary
  val track = MaterialTheme.colorScheme.surfaceContainerHighest
  val largest = rows.maxOf { it.second }.coerceAtLeast(1)

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    rows.forEach { (label, blocks) ->
      Column {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
          val height = size.height
          drawRect(
            color = track,
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(size.width, height),
          )
          val width = (size.width * blocks / largest).coerceAtLeast(6.dp.toPx())
          drawRect(
            color = fill,
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(width, height),
          )
        }
      }
    }
    Spacer(Modifier.height(2.dp))
    Text(
      text = stringResource(R.string.resize_info_speed_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// --- Previews ---------------------------------------------------------------

@Preview(name = "Resize info", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun ResizeInfoScreenPreview() {
  GlitchTheme {
    ResizeInfoScreen(onClose = {})
  }
}
