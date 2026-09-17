package ca.justinmo.glitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ca.justinmo.glitch.jpeg.QUANT_U
import ca.justinmo.glitch.jpeg.QUANT_V
import ca.justinmo.glitch.jpeg.QuantAxis
import ca.justinmo.glitch.ui.theme.GlitchTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Explains what the DQT screen edits: the 64 per-block patterns JPEG works with,
 * which of them the eight buttons group together, and what moving a quantizer
 * does to the picture.
 *
 * Everything here is drawn from the DCT basis functions themselves - the maths
 * the coefficients stand for - and the illustrations are **vectors**: the 64
 * pattern grid is one [ImageVector] built from the basis maths (a cell per
 * sampled value, posterised into shade levels), so opening this screen does no
 * pixel work at all.
 */
@Composable
internal fun DqtInfoScreen(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  InfoScreen(
    title = stringResource(R.string.dqt_info_title),
    onClose = onClose,
    modifier = modifier,
  ) {
    InfoSection(
          title = stringResource(R.string.dqt_info_intro_title),
          body = stringResource(R.string.dqt_info_intro_body),
        )
        InfoSection(
          title = stringResource(R.string.dqt_info_basis_title),
          body = stringResource(R.string.dqt_info_basis_body),
        ) {
          Spacer(Modifier.height(10.dp))
          BasisGrid()
          Spacer(Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.dqt_info_basis_axes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        InfoSection(
          title = stringResource(R.string.dqt_info_family_title),
          body = stringResource(R.string.dqt_info_family_body),
        ) {
          Spacer(Modifier.height(10.dp))
          BasisGrid(ringFamilies = true)
          Spacer(Modifier.height(12.dp))
          FamilyLegend()
        }
        InfoSection(
          title = stringResource(R.string.dqt_info_quant_title),
          body = stringResource(R.string.dqt_info_quant_body),
        ) {
          Spacer(Modifier.height(12.dp))
          QuantizerExample(value = 2, label = stringResource(R.string.dqt_info_quant_fine))
          Spacer(Modifier.height(10.dp))
          QuantizerExample(value = 24, label = stringResource(R.string.dqt_info_quant_coarse))
          Spacer(Modifier.height(10.dp))
          QuantizerExample(value = 0, label = stringResource(R.string.dqt_info_quant_zero))
        }
        InfoSection(
          title = stringResource(R.string.dqt_info_bits_title),
          body = stringResource(R.string.dqt_info_bits_body),
        ) {
          Spacer(Modifier.height(12.dp))
          BitValueRow()
          Spacer(Modifier.height(10.dp))
          Text(
            text = stringResource(R.string.dqt_info_bits_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        InfoSection(
          title = stringResource(R.string.dqt_info_limits_title),
          body = stringResource(R.string.dqt_info_limit_dead),
        ) {
          Spacer(Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.dqt_info_limit_quiet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.dqt_info_limit_chroma),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(32.dp))
  }
}

// --- The pattern grid -------------------------------------------------------

/** Cells per tile side. 12 keeps the finest patterns readable as vectors. */
private const val GRID_CELLS = 12

/** Shade steps the basis values are posterised into, one vector path each. */
private const val GRID_SHADES = 12

/** Vector units per tile; the whole grid is therefore 800x800 units. */
private const val TILE_UNITS = 100f

/** Gap between a tile's cells and the tile edge, in the same units. */
private const val TILE_INSET = 3f

/**
 * The 64 patterns of an 8x8 block as one vector: the DCT basis function of every
 * coefficient, sampled over the block. Coefficient (u, v) wiggles `u` times
 * horizontally and `v` times vertically, so the top-left tile is flat (the block
 * average) and the bottom-right holds the finest detail a block can carry.
 *
 * With [ringFamilies] the tiles each family of buttons covers are outlined in
 * the colour [FamilyLegend] gives them.
 *
 * Nothing here allocates pixels: the grid is a normal vector drawable, built once
 * per colour scheme and then rasterised by the same path as any other icon.
 */
@Composable
private fun BasisGrid(
  modifier: Modifier = Modifier,
  ringFamilies: Boolean = false,
) {
  val low = MaterialTheme.colorScheme.surfaceContainerHighest
  val high = MaterialTheme.colorScheme.primary
  val ringColors = QuantAxis.entries.map { familyColor(it) }
  val grid = remember(ringFamilies, low, high, ringColors) {
    basisGridVector(ringFamilies, low, high, ringColors)
  }

  Image(
    imageVector = grid,
    contentDescription = stringResource(R.string.dqt_info_basis_desc),
    modifier = modifier.fillMaxWidth().aspectRatio(1f),
  )
}

/** Colour each family is drawn in, shared by the grid rings and the legend. */
@Composable
private fun familyColor(axis: QuantAxis): Color = when (axis) {
  QuantAxis.DC -> MaterialTheme.colorScheme.primary
  QuantAxis.HORIZONTAL -> MaterialTheme.colorScheme.secondary
  QuantAxis.VERTICAL -> MaterialTheme.colorScheme.tertiary
  QuantAxis.CHECKERED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Builds the whole 64-tile grid as a single vector.
 *
 * One path per shade level: every cell is bucketed by how bright the basis is
 * there, and all the cells of a bucket go into one path, so the finished grid is
 * a handful of paths rather than thousands of little pictures.
 */
private fun basisGridVector(
  ringFamilies: Boolean,
  low: Color,
  high: Color,
  ringColors: List<Color>,
): ImageVector {
  val builder = ImageVector.Builder(
    name = "dqt-basis",
    defaultWidth = (TILE_UNITS * 8f).dp,
    defaultHeight = (TILE_UNITS * 8f).dp,
    viewportWidth = TILE_UNITS * 8f,
    viewportHeight = TILE_UNITS * 8f,
  )

  // The cosine either side of a cell centre, once per frequency: eight rows of
  // samples shared by all 64 tiles.
  val axis = Array(8) { frequency ->
    FloatArray(GRID_CELLS) { cell ->
      val position = (cell + 0.5) / GRID_CELLS * 8.0
      cos(PI * frequency * (2.0 * position + 1.0) / 16.0).toFloat()
    }
  }

  // Which shade level each cell lands in, in tile order.
  val levels = IntArray(64 * GRID_CELLS * GRID_CELLS)
  var index = 0
  for (v in 0 until 8) {
    for (u in 0 until 8) {
      for (row in 0 until GRID_CELLS) {
        val vertical = axis[v][row]
        for (column in 0 until GRID_CELLS) {
          val value = vertical * axis[u][column]
          levels[index++] =
            (((value + 1f) * 0.5f) * (GRID_SHADES - 1)).roundToInt().coerceIn(0, GRID_SHADES - 1)
        }
      }
    }
  }

  val span = (TILE_UNITS - TILE_INSET * 2f) / GRID_CELLS
  for (level in 0 until GRID_SHADES) {
    builder.path(fill = SolidColor(lerp(low, high, (level + 0.5f) / GRID_SHADES))) {
      var cell = 0
      for (v in 0 until 8) {
        for (u in 0 until 8) {
          for (row in 0 until GRID_CELLS) {
            for (column in 0 until GRID_CELLS) {
              if (levels[cell++] == level) {
                rect(
                  x = u * TILE_UNITS + TILE_INSET + column * span,
                  y = v * TILE_UNITS + TILE_INSET + row * span,
                  width = span,
                  height = span,
                )
              }
            }
          }
        }
      }
    }
  }

  if (ringFamilies) {
    QuantAxis.entries.forEach { axis ->
      val colour = ringColors.getOrElse(axis.ordinal) { low }
      builder.path(
        stroke = SolidColor(colour),
        strokeLineWidth = if (axis == QuantAxis.CHECKERED) 4f else 2f,
      ) {
        for (v in 0 until 8) {
          for (u in 0 until 8) {
            if (QuantAxis.of(u, v) == axis) {
              rect(
                x = u * TILE_UNITS + 1f,
                y = v * TILE_UNITS + 1f,
                width = TILE_UNITS - 2f,
                height = TILE_UNITS - 2f,
              )
            }
          }
        }
      }
    }
  }

  return builder.build()
}

/** Appends a rectangle to a vector path. */
private fun PathBuilder.rect(x: Float, y: Float, width: Float, height: Float) {
  moveTo(x, y)
  lineTo(x + width, y)
  lineTo(x + width, y + height)
  lineTo(x, y + height)
  close()
}

/** Which family each colour means, labelled with the flip screen's own icons. */
@Composable
private fun FamilyLegend() {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
      QuantAxis.DC to R.string.dqt_family_brightness,
      QuantAxis.HORIZONTAL to R.string.dqt_family_horizontal,
      QuantAxis.VERTICAL to R.string.dqt_family_vertical,
      QuantAxis.CHECKERED to R.string.dqt_family_checkered,
    ).forEach { (axis, labelRes) ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(familyColor(axis)),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
          imageVector = familyIcon(DqtFamily(axis, chroma = false)),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = stringResource(labelRes),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
    Spacer(Modifier.height(2.dp))
    Text(
      text = stringResource(R.string.dqt_info_family_colour),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * A quantizer value drawn as what it does to one pattern: the ramp is kept in as
 * many steps as the quantizer allows. Small values stay smooth, large ones
 * flatten into bands, and zero leaves nothing at all.
 */
@Composable
private fun QuantizerExample(value: Int, label: String) {
  val low = MaterialTheme.colorScheme.surfaceContainerHighest
  val high = MaterialTheme.colorScheme.primary
  val bands = when {
    value <= 0 -> 1
    value < 8 -> 32
    value < 32 -> 6
    else -> 3
  }

  Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(modifier = Modifier.size(width = 96.dp, height = 44.dp)) {
      val step = size.width / bands
      for (i in 0 until bands) {
        drawRect(
          color = lerp(low, high, (i + 0.5f) / bands),
          topLeft = Offset(i * step, 0f),
          size = Size(step + 1f, size.height),
        )
      }
    }
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
        text = stringResource(R.string.dqt_info_quant_value, value),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The eight bits of a quantizer byte, over the amount each one is worth. */
@Composable
private fun BitValueRow() {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    for (bit in 7 downTo 0) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.dqt_bit_label, bit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Spacer(Modifier.height(3.dp))
        Text(
          text = (1 shl bit).toString(),
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
  }
}

// --- Previews ---------------------------------------------------------------

/** The grid on its own, the way a picture of the 64 patterns is usually shown. */
@Preview(name = "DCT basis grid", device = "spec:width=411dp,height=600dp", showBackground = true)
@Composable
private fun BasisGridPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.padding(16.dp)) {
        BasisGrid()
        Spacer(Modifier.height(12.dp))
        BasisGrid(ringFamilies = true)
      }
    }
  }
}

@Preview(name = "DQT info", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun DqtInfoScreenPreview() {
  GlitchTheme {
    DqtInfoScreen(onClose = {})
  }
}
