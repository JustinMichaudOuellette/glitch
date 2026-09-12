package ca.justinmo.glitch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

import ca.justinmo.glitch.R
import ca.justinmo.glitch.ui.theme.GlitchTheme

/** Gap between the buttons of a bottom row. They take everything else between them. */
private val BUTTON_GAP = 12.dp

/**
 * One action of a screen's bottom row: an icon, and no label.
 *
 * The label does not disappear, it moves: an icon-only button has to say what it does
 * to a screen reader, and [contentDescription] is where that goes.
 *
 * The shape is what makes the row read as a row - one height, and the width shared out
 * between them - so the height is fixed here and the width is the caller's. A button
 * handed `Modifier.weight(1f)` fills its share of the row and comes out a capsule,
 * which is what these rows are now; one handed nothing stays the 56 dp circle the
 * rows used to be made of.
 */
@Composable
internal fun CircleAction(
  icon: ImageVector,
  contentDescription: String,
  enabled: Boolean,
  primary: Boolean = false,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FilledIconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.defaultMinSize(minWidth = 56.dp).height(56.dp),
    colors = if (primary) {
      IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      )
    } else {
      IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
      )
    },
  ) {
    Icon(imageVector = icon, contentDescription = contentDescription)
  }
}

/**
 * The actions a step of the flow ends with, in the order they read:
 *
 *  - **reset** clears the edits and stays;
 *  - **undo** takes back the last one;
 *  - **flip** applies the next edit the controls aim at, and is the primary action;
 *  - **next** takes the edited file on, and is the other one.
 *
 * Undo sits next to flip so the two that work on the same thing are neighbours, and next
 * sits at the end, where the way out of a screen belongs.
 *
 * A **skip** slot used to lead the row on the three screens whose edits live in the file:
 * it handed on the file as it arrived, dropping whatever had been done here. It is gone
 * because it was the same thing as reset followed by next, said in one press - and it was
 * the one button in the row that could throw a session's work away without the user having
 * asked for it twice. Reset is the safe half of that pair and it stays visible and merely
 * disables, which is what the row is for. Skip survives on the resize screen alone, where
 * it is not the same thing at all: there, not resizing is a decision about the file that
 * reset cannot express.
 *
 * Not every step has all four. A screen whose controls *set* a value rather than flip a bit
 * has nothing to flip, and one that keeps no history has nothing to undo - so a slot given
 * no callback is not drawn, and the rest share the row out between them, each one wider
 * than it was when there were more.
 *
 * That is a shape, not a licence to drop the two: a screen ported onto this bar is
 * expected to be *able* to flip and undo, because those two are what make it a tool
 * rather than a form - flip is how an edit is aimed, and undo is what makes aiming
 * it safe to try. Before leaving a slot empty, work out what the screen's flip and
 * undo actually are; only leave it empty when there is genuinely nothing there, and
 * say so out loud in the screen's own KDoc rather than silently.
 */
@Composable
internal fun ScanActionRow(
  onNext: () -> Unit,
  nextEnabled: Boolean,
  onReset: (() -> Unit)? = null,
  resetEnabled: Boolean = false,
  onUndo: (() -> Unit)? = null,
  undoEnabled: Boolean = false,
  onFlip: (() -> Unit)? = null,
  flipEnabled: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    // The buttons take the width between them rather than sitting in circles with the
    // space left over around them: a gap this wide, and a weight apiece, so every slot
    // that is drawn is the same size whatever the row happens to hold.
    horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
  ) {
    if (onReset != null) {
      CircleAction(
        icon = Icons.Outlined.RestartAlt,
        contentDescription = stringResource(R.string.edit_reset),
        enabled = resetEnabled,
        onClick = onReset,
        modifier = Modifier.weight(1f),
      )
    }
    if (onUndo != null) {
      CircleAction(
        icon = Icons.AutoMirrored.Outlined.Undo,
        contentDescription = stringResource(R.string.edit_undo),
        enabled = undoEnabled,
        onClick = onUndo,
        modifier = Modifier.weight(1f),
      )
    }
    if (onFlip != null) {
      CircleAction(
        icon = Icons.Outlined.Bolt,
        contentDescription = stringResource(R.string.edit_flip_button),
        enabled = flipEnabled,
        primary = true,
        onClick = onFlip,
        modifier = Modifier.weight(1f),
      )
    }
    CircleAction(
      icon = Icons.Outlined.Check,
      contentDescription = stringResource(R.string.scan_next),
      enabled = nextEnabled,
      primary = true,
      onClick = onNext,
      modifier = Modifier.weight(1f),
    )
  }
}

@Preview(name = "Actions", device = "spec:width=411dp,height=200dp", showBackground = true)
@Composable
private fun ScanActionRowPreview() {
  GlitchTheme {
    androidx.compose.material3.Surface(modifier = Modifier.fillMaxWidth()) {
      ScanActionRow(
        onReset = {},
        onUndo = {},
        onFlip = {},
        onNext = {},
        resetEnabled = false,
        undoEnabled = true,
        flipEnabled = true,
        nextEnabled = true,
      )
    }
  }
}

