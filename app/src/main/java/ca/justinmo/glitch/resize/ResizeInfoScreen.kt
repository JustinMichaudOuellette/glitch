package ca.justinmo.glitch.resize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.R
import ca.justinmo.glitch.ui.InfoScreen
import ca.justinmo.glitch.ui.InfoSection
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
        stringResource(R.string.resize_info_speed_original) to 250_852,
        stringResource(R.string.resize_info_speed_default) to 62_713,
        stringResource(R.string.resize_info_speed_small) to 32_400,
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
 * One 8x8 block, enlarged: the unit JPEG stores. The squares of the checkerboard are the block's
 * own pixels, so the caption can point at them - and at how many of them there are, which is what
 * the pair of panels further down is about.
 */
@Composable
private fun BlockGridPanel(
  columns: Int,
  rows: Int,
  caption: String,
  modifier: Modifier = Modifier,
) {
  // Two tones rather than a fill with a grid of lines over it. At eight squares across, lines
  // would do; at thirty-two they are most of the panel, and a square that size has to be counted
  // rather than traced. Alternating them instead means the count is what the eye picks up, which
  // is exactly the comparison the two panels below are making.
  val light = MaterialTheme.colorScheme.surfaceContainerHighest
  val dark = MaterialTheme.colorScheme.primary
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Canvas(
      modifier = Modifier
        .size(width = 160.dp, height = 160.dp),
    ) {
      val stepX = size.width / columns
      val stepY = size.height / rows
      drawRect(color = light)
      for (row in 0 until rows) {
        for (column in 0 until columns) {
          // Every other square: the ones left alone are the first tone showing through.
          if ((row + column) % 2 == 0) continue
          drawRect(
            color = dark,
            topLeft = Offset(column * stepX, row * stepY),
            size = Size(stepX, stepY),
          )
        }
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
      columns = 16,
      rows = 16,
      caption = stringResource(R.string.resize_info_size_small),
      modifier = Modifier.weight(1f),
    )
    BlockGridPanel(
      columns = 48,
      rows = 48,
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
  val largest = rows.maxOf { it.second }

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
            size = Size(size.width, height),
          )
          val width = (size.width * blocks / largest).coerceAtLeast(6.dp.toPx())
          drawRect(
            color = fill,
            topLeft = Offset.Zero,
            size = Size(width, height),
          )
        }
      }
    }
  }
}

// --- Previews ---------------------------------------------------------------

@Preview(name = "Resize info", device = "spec:width=411dp,height=1200dp", showBackground = true)
@Composable
private fun ResizeInfoScreenPreview() {
  GlitchTheme {
    ResizeInfoScreen(onClose = {})
  }
}
