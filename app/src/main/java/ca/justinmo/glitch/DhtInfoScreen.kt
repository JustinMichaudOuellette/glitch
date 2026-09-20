package ca.justinmo.glitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Explains what the DHT screen edits: what a Huffman table is made of, why a
 * code's position in the list decides how loud a change to it is, what a symbol
 * means, and which edits cannot work.
 *
 * The numbers behind it are the ones in `DHT.md`, measured on the sample batch:
 * the luma detail table has 162 codes but only 77 of them are ever used by those
 * four photos, and the first sixteen carry almost everything.
 */
@Composable
internal fun DhtInfoScreen(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  InfoScreen(
    title = stringResource(R.string.dht_info_title),
    onClose = onClose,
    modifier = modifier,
  ) {
    InfoSection(
      title = stringResource(R.string.dht_info_intro_title),
      body = stringResource(R.string.dht_info_intro_body),
    )
    InfoSection(
      title = stringResource(R.string.dht_info_parts_title),
      body = stringResource(R.string.dht_info_parts_body),
    ) {
      Spacer(Modifier.height(12.dp))
      TablePartsDiagram()
      Spacer(Modifier.height(10.dp))
      Text(
        text = stringResource(R.string.dht_info_parts_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.dht_info_odds_title),
      body = stringResource(R.string.dht_info_odds_body),
    )
    InfoSection(
      title = stringResource(R.string.dht_info_book_title),
      body = stringResource(R.string.dht_info_book_body),
    ) {
      Spacer(Modifier.height(12.dp))
      CodeBookStrip()
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.dht_info_book_caption),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.dht_info_tiers_title),
      body = stringResource(R.string.dht_info_tiers_body),
    ) {
      Spacer(Modifier.height(10.dp))
      TierBar()
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.dht_info_tiers_caption),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.dht_info_symbol_title),
      body = stringResource(R.string.dht_info_symbol_body),
    ) {
      Spacer(Modifier.height(12.dp))
      RunSizeDiagram(run = 3, size = 4, what = stringResource(R.string.dht_info_run_small))
      Spacer(Modifier.height(14.dp))
      RunSizeDiagram(run = 9, size = 2, what = stringResource(R.string.dht_info_run_large))
      Spacer(Modifier.height(10.dp))
      Text(
        text = stringResource(R.string.dht_info_run_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.dht_info_level_title),
      body = stringResource(R.string.dht_info_level_body),
    )
    InfoSection(
      title = stringResource(R.string.dht_info_breaks_title),
      body = stringResource(R.string.dht_info_breaks_body),
    ) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.dht_info_breaks_counts),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.dht_info_breaks_dc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    InfoSection(
      title = stringResource(R.string.dht_info_limits_title),
      body = stringResource(R.string.dht_info_limit_dead),
    ) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.dht_info_limit_content),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.dht_info_limit_chroma),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(32.dp))
  }
}

// --- Illustrations ----------------------------------------------------------

/**
 * The three parts of one table, drawn to scale in meaning rather than bytes: the
 * information byte that names the table, the sixteen code-length counts, and the
 * symbol list that takes up all the rest.
 *
 * The proportions are those of the sample files, where each table is 17 header
 * bytes followed by 12 symbols (DC) or 162 (AC).
 */
@Composable
private fun TablePartsDiagram() {
  val headerColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val countsColor = MaterialTheme.colorScheme.tertiaryContainer
  val symbolsColor = MaterialTheme.colorScheme.primaryContainer
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      DiagramBlock(
        color = headerColor,
        label = stringResource(R.string.dht_info_part_info_short),
        modifier = Modifier.weight(1f),
      )
      DiagramBlock(
        color = countsColor,
        label = stringResource(R.string.dht_info_part_counts_short),
        modifier = Modifier.weight(2f),
      )
      DiagramBlock(
        color = symbolsColor,
        label = stringResource(R.string.dht_info_part_symbols_short),
        modifier = Modifier.weight(8f),
      )
    }
    Spacer(Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.dht_info_part_info),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = stringResource(R.string.dht_info_part_counts),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = stringResource(R.string.dht_info_part_symbols),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** One labelled slab of [TablePartsDiagram]. */
@Composable
private fun DiagramBlock(
  color: Color,
  label: String,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .height(44.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(color),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * Every code of the luma detail table, drawn as a bar: the tallest, brightest
 * bars on the left are the short codes JPEG hands to the most common blocks, and
 * the long flat tail on the right is the 125 sixteen-bit codes that these photos
 * never emit at all. Editing a bar on the left changes the picture; editing one
 * on the right does nothing here.
 */
@Composable
private fun CodeBookStrip() {
  val lengths = remember { AC_LUMA_CODE_LENGTHS }
  val hot = MaterialTheme.colorScheme.primary
  val cold = MaterialTheme.colorScheme.surfaceContainerHighest
  Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
    val gap = 0.5f
    val step = size.width / lengths.size
    for (i in lengths.indices) {
      // 2 bits is the shortest code in this table, 16 the longest.
      val t = ((lengths[i] - 2) / 14f).coerceIn(0f, 1f)
      val barHeight = size.height * (1f - 0.72f * t)
      drawRect(
        color = lerp(hot, cold, t),
        topLeft = Offset(i * step, size.height - barHeight),
        size = Size((step - gap).coerceAtLeast(0.5f), barHeight),
      )
    }
  }
}

/** One line of an 8x8 block: the DC coefficient, the skipped zeros, the coded value. */
@Composable
private fun RunSizeDiagram(run: Int, size: Int, what: String) {
  val zero = MaterialTheme.colorScheme.surfaceContainerHigh
  val dc = MaterialTheme.colorScheme.surfaceContainerHighest
  val coded = MaterialTheme.colorScheme.primary
  val rest = MaterialTheme.colorScheme.surfaceContainerLow
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      for (i in 0 until RUN_SIZE_CELLS) {
        val color = when {
          i == 0 -> dc
          i <= run -> zero
          i == run + 1 -> coded
          else -> rest
        }
        Box(
          modifier = Modifier
            .weight(1f)
            .height(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
        )
      }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      text = what,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = stringResource(R.string.dht_info_run_packed, (run shl 4) or size),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Coefficients drawn per row of [RunSizeDiagram]; enough to show a long skip. */
private const val RUN_SIZE_CELLS = 16

/**
 * The three tiers as one bar, split the way the screen splits them: a thin
 * gentle end and a wide destructive one.
 *
 * The widths are the sample batch's numbers rather than a drawing of them - of
 * the 840 edits that changed a picture at all, 211 were worth keeping.
 */
@Composable
private fun TierBar() {
  val touch = MaterialTheme.colorScheme.primary
  val texture = MaterialTheme.colorScheme.tertiary
  val rewrite = MaterialTheme.colorScheme.error
  val track = MaterialTheme.colorScheme.surfaceContainerHighest
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(16.dp)
      .clip(RoundedCornerShape(8.dp)),
  ) {
    // 211 usable, 629 destructive, out of the 2784 edits the region offers.
    Box(modifier = Modifier.weight(211f).fillMaxSize().background(touch))
    Box(modifier = Modifier.weight(1848f).fillMaxSize().background(track))
    Box(modifier = Modifier.weight(629f).fillMaxSize().background(rewrite))
  }
  Spacer(Modifier.height(8.dp))
  LegendDot(color = touch, text = stringResource(R.string.dht_info_tiers_keep))
  LegendDot(color = track, text = stringResource(R.string.dht_info_tiers_nothing))
  LegendDot(color = rewrite, text = stringResource(R.string.dht_info_tiers_wreck))
}

/** One colour key under [TierBar]. */
@Composable
private fun LegendDot(color: Color, text: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
    Spacer(Modifier.width(8.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Code lengths of the JPEG standard AC luminance table, in symbol order:
 * 2,2 then 3, 4,4,4, 5,5,5, 6,6 and so on down to the 125 sixteen-bit codes.
 * Transcribed from the table the sample files carry (and from `DHT.md`
 * section 5), so the strip shows the real shape rather than a drawing of one.
 */
private val AC_LUMA_CODE_LENGTHS: IntArray = run {
  // 37 codes of length 2-15, then the 125 sixteen-bit codes that fill the table.
  val head = intArrayOf(
    2, 2, 3, 4, 4, 4, 5, 5, 5, 6, 6, 7, 7, 7, 7, 8, 8, 8,
    9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 11, 11, 11, 11,
    12, 12, 12, 12, 15,
  )
  IntArray(162) { if (it < head.size) head[it] else 16 }
}
