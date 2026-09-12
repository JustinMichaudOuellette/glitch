package ca.justinmo.glitch.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.OutputFormat
import ca.justinmo.glitch.R
import ca.justinmo.glitch.ui.InfoScreen
import ca.justinmo.glitch.ui.InfoSection
import ca.justinmo.glitch.ui.theme.GlitchTheme

/**
 * Explains what the last screen is for: why nothing on it changes the picture, what
 * each of the three formats does to the file, and where saving and sharing actually
 * put it.
 */
@Composable
internal fun PreviewInfoScreen(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  InfoScreen(
    title = stringResource(R.string.preview_info_title),
    onClose = onClose,
    modifier = modifier,
  ) {
    InfoSection(
      title = stringResource(R.string.preview_info_intro_title),
      body = stringResource(R.string.preview_info_intro_body),
    )
    InfoSection(
      title = stringResource(R.string.preview_info_formats_title),
      body = stringResource(R.string.preview_info_formats_body),
    ) {
      FormatCards()
    }
    InfoSection(
      title = stringResource(R.string.preview_info_save_title),
      body = stringResource(R.string.preview_info_save_body),
    )
  }
}

/**
 * The three formats as file cards, with the selected one in color.
 *
 * Cards and not a table or a paragraph: the difference between the three is not what
 * they are called, it is whether the bytes you see are the bytes the flips made - and
 * that one line under each name says it faster than any amount of prose about
 * containers.
 */
@Composable
private fun FormatCards() {
  Spacer(Modifier.height(12.dp))
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutputFormat.entries.forEach { option ->
      FormatCard(
        format = option,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun FormatCard(format: OutputFormat, modifier: Modifier = Modifier) {
  val container = MaterialTheme.colorScheme.surfaceContainerHigh
  val content = MaterialTheme.colorScheme.onSurface
  Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = container) {
    Column(modifier = Modifier.padding(10.dp)) {
      Text(
        text = stringResource(format.label),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = content,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = stringResource(
          if (format == OutputFormat.RawJpeg) {
            R.string.preview_info_card_bytes
          } else {
            R.string.preview_info_card_reencoded
          },
        ),
        style = MaterialTheme.typography.labelSmall,
        color = content,
      )
    }
  }
}

@Preview(name = "Preview info", device = "spec:width=411dp,height=1000dp", showBackground = true)
@Composable
private fun PreviewInfoScreenPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      PreviewInfoScreen(onClose = {})
    }
  }
}
