package ca.justinmo.glitch.source

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import ca.justinmo.glitch.R
import ca.justinmo.glitch.ui.InfoScreen
import ca.justinmo.glitch.ui.InfoSection
import ca.justinmo.glitch.ui.theme.GlitchTheme

/**
 * What this app is, for the first screen - the one place a new user has nothing
 * else to read.
 *
 * The other info screens explain the step they sit on, because by then there is a
 * step to explain. This one has the whole app to account for: what it is and which
 * version, where it came from, the three parts of a JPEG it edits, and a way to
 * pass it on.
 *
 * The three edits are laid out as a grid rather than as three sentences, so their
 * descriptions all start on the same line: see [StepRow] and [rememberTitleColumn].
 *
 * The version is read from the package rather than written here twice: it is the
 * one string on this screen that a release changes.
 */
@Composable
internal fun SourceInfoScreen(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  // Check if rendering in Android Studio Preview (inspection mode) to avoid calling
  // context.packageManager.getPackageInfo on layoutlib's mock context, which throws exceptions and breaks Compose Preview rendering.
  val isInspectionMode = LocalInspectionMode.current
  val version = remember(context, isInspectionMode) {
    if (isInspectionMode) {
      "1.0"
    } else {
      runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
      }.getOrNull().orEmpty()
    }
  }
  val creditText = stringResource(R.string.source_info_credit)
  val creditUrl = "https://www.justinmo.ca"
  val author = stringResource(R.string.source_info_author)
  val shareLabel = stringResource(R.string.source_info_share)
  val shareChooser = stringResource(R.string.source_info_share_chooser)
  val shareText = "https://github.com/JustinMichaudOuellette/glitch"

  InfoScreen(
    title = stringResource(R.string.about),
    onClose = onClose,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier
          .fillMaxWidth()
          .height(148.dp)
          .clipToBounds()
          .graphicsLayer {
            scaleX = 1.98f
            scaleY = 1.98f
          },
        tint = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Thin,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
      if (version.isNotEmpty()) {
        Text(
          text = stringResource(R.string.source_info_version, version),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.padding(top = 8.dp),
          textAlign = TextAlign.Center,
        )
      }
      Text(
        text = buildAnnotatedString {
          append("$creditText ")
          withLink(
            LinkAnnotation.Url(
              creditUrl,
              TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
            ),
          ) {
            append(author)
          }
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
      )
      FilledTonalButton(
        onClick = {
          val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
          }
          context.startActivity(Intent.createChooser(send, shareChooser))
        },
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 20.dp),
      ) {
        Icon(Icons.Outlined.Share, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(shareLabel)
      }
    }

    Spacer(Modifier.height(28.dp))

    InfoSection(
      title = stringResource(R.string.source_info_history_title),
      body = stringResource(R.string.source_info_history_body),
    )

    InfoSection(
      title = stringResource(R.string.source_info_what_title),
      body = stringResource(R.string.source_info_what_body),
    ) {
      Spacer(Modifier.height(12.dp))
      // The three edits, as a grid of two columns: the title of each and what it does. The title
      // column is as wide as the widest title needs - which is what the grid is for, since a
      // column sized per row would start every description at a different place down the list.
      //
      // That width is *measured* rather than picked: how wide a title is depends on the font, on
      // the user's text size and on the language, so a number written here would be right on one
      // phone and wrong on the next. It is also capped, because the title column and the
      // description column are sharing one row: a title long enough to want all of it would
      // leave the descriptions beside it nothing to be read in.
      val titles = listOf(
        stringResource(R.string.source_info_step_dqt_title),
        stringResource(R.string.source_info_step_scan_title),
        stringResource(R.string.source_info_step_dht_title),
      )
      val descriptions = listOf(
        stringResource(R.string.source_info_step_dqt_description),
        stringResource(R.string.source_info_step_scan_description),
        stringResource(R.string.source_info_step_dht_description),
      )
      val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val titleWidth = rememberTitleColumn(titles, titleStyle, maxWidth * TITLE_COLUMN_SHARE)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          titles.indices.forEach { index ->
            StepRow(
              title = titles[index],
              description = descriptions[index],
              titleWidth = titleWidth,
              titleStyle = titleStyle,
            )
          }
        }
      }
    }

    Spacer(Modifier.height(32.dp))
  }
}

/**
 * The width of the title column: as wide as the widest of [titles] needs, and never wider than
 * [limit].
 *
 * [limit] is the half of this that is a judgement rather than a measurement. The two columns are
 * sharing a row, so a title that wanted all of it would leave the description beside it nothing
 * to be read in - and two unreadable columns are worse than one short one. Everything up to that
 * limit is measured, so the common case is exactly as wide as the widest title.
 */
@Composable
private fun rememberTitleColumn(titles: List<String>, style: TextStyle, limit: Dp): Dp {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  // Keyed on the density because the measurement is in pixels: a change in text size or display
  // scale has to measure again rather than keep the width the old one came to.
  val widest = remember(titles, style, density) {
    titles.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
  }
  return minOf(with(density) { widest.toDp() }, limit)
}

/**
 * One row of that grid: a title, and the description it belongs to.
 *
 * The title is given the width the widest of the three needs rather than its own, which is what
 * makes the rows a grid: the descriptions all begin on the same line, and the eye can run down
 * either column on its own. The two are aligned at the top rather than on a baseline, because a
 * description is allowed to run to several lines and its title has to start beside the first.
 */
@Composable
private fun StepRow(
  title: String,
  description: String,
  titleWidth: Dp,
  titleStyle: TextStyle,
) {
  Row(verticalAlignment = Alignment.Top) {
    Text(
      text = title,
      style = titleStyle,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.width(titleWidth),
    )
    Spacer(Modifier.width(12.dp))
    Text(
      text = description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
  }
}

/** Share of a step row the title column may take before the descriptions stop being readable. */
private const val TITLE_COLUMN_SHARE = 0.45f

// --- Previews ---------------------------------------------------------------

@Preview(name = "Source info", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun SourceInfoScreenPreview() {
  GlitchTheme {
    SourceInfoScreen(onClose = {})
  }
}
