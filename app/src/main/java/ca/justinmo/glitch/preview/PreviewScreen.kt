package ca.justinmo.glitch.preview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ca.justinmo.glitch.OutputFormat
import ca.justinmo.glitch.HandOver
import ca.justinmo.glitch.PreviewImage
import ca.justinmo.glitch.R
import ca.justinmo.glitch.ZoomState
import ca.justinmo.glitch.decodeSampled
import ca.justinmo.glitch.encodedFor
import ca.justinmo.glitch.imageResolution
import ca.justinmo.glitch.saveToGallery
import ca.justinmo.glitch.scan.scanPreviewBytes
import ca.justinmo.glitch.shareImage
import ca.justinmo.glitch.ui.CircleAction
import ca.justinmo.glitch.ui.MediaPreview
import ca.justinmo.glitch.ui.PreviewUtil.previewImageBitmap
import ca.justinmo.glitch.ui.PreviewWithControls
import ca.justinmo.glitch.ui.STACKED_CONTROLS_HEIGHT
import ca.justinmo.glitch.ui.TitleBar
import ca.justinmo.glitch.ui.createShadow
import ca.justinmo.glitch.ui.previewFrame
import ca.justinmo.glitch.ui.theme.GlitchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preview decode size, matching the other screens'. */
private const val PREVIEW_CAP_PX = 2000

/**
 * The last screen: the finished picture, and the ways out of the app.
 *
 * [bytes] arrive finished. The resize screen set the size and the crop, the quantization
 * screen set how roughly the picture is stored, the scan screen flipped bits in it, and the
 * Huffman tables were retuned from that screen if the user wanted them retuned - so this
 * screen edits nothing, and has no skip, undo or flip. It shows what those steps made, and
 * decides only how the file *leaves*: shared, saved into the gallery, or written as JPEG,
 * PNG or WebP.
 *
 * The Huffman screen used to be opened from here, and moved back to the scan screen, where
 * the file it edits is still being worked on. This screen is the one place in the app that
 * changes nothing about the picture, and an edit offered from here sat oddly beside the two
 * buttons that hand the file over - the more so because a share or a save can be in flight
 * while it is on screen.
 *
 * The one thing here that is not about how the file leaves is the first button in the row:
 * restart throws the session away and starts again from the picker. It is the only action in
 * the app that loses work outright, so it is the only one that asks first - and it asks only
 * while there is something to lose, which [PreviewState.saved] is the answer to.
 *
 * That is also why the format choice belongs here rather than on an earlier screen.
 * Until this point the picture is a JPEG whose bytes *are* the edit, and re-encoding
 * it would throw away the exact damage the flips made; only here is it a file to hand
 * over, and only here does choosing a container stop being a change to the picture.
 *
 * The format, the open explanation and the pinch-zoom all outlive this screen, so they
 * are not kept here: the flow owns them and hands them in. `bytes`, the decode in flight
 * and the picture it produced stay, because they are about the frame being drawn rather
 * than about anything the user chose.
 */
@Composable
internal fun PreviewScreen(
  bytes: ByteArray,
  initialScale: Float = 1f,
  initialOffset: Offset = Offset.Zero,
  /**
   * The picture to show before the file's own decode arrives - nothing on a device,
   * where the decode is immediate, but the sample asset in a preview, where the
   * decoder is an .so that a preview on the host cannot load.
   */
  examplePreview: ImageBitmap? = null,
  /**
   * The pinch-zoom, shared across the flow: the picture is framed here the way the
   * earlier steps left it, and is found the same way when the flow comes forward to this
   * step again. The default is for a caller with nothing to share - a preview, or this
   * screen composed on its own - and starts it exactly where it used to start.
   */
  zoom: ZoomState = remember(initialScale, initialOffset) {
    ZoomState(initialScale, initialOffset)
  },
  /**
   * The flow's shared decoded preview, so this screen's first frame draws the picture the last
   * step was showing instead of a blank and a re-decode of the same file. See [PreviewImage].
   */
  previewImage: PreviewImage = remember { PreviewImage() },
  /** What the user chose here, held by the flow so walking back does not lose it. */
  state: PreviewState = remember { PreviewState() },
  /**
   * Throw this session away and start again, which the flow does by putting the first screen
   * back with its picker already open - so this screen only asks for it. It is only reached
   * through [restart], which is where the asking happens.
   */
  onRestart: () -> Unit,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // What this screen chose lives in the holder, not in `remember`. The format is the whole
  // of its choice, and this is the last step of the flow: the only way back to it is
  // forward again through the steps, so a screen that came up with its format forgotten
  // would mean the picture quietly saving as something other than what was chosen. The
  // explanation goes with it, because a panel that was open is part of where the user was.
  //
  // The holder's own default is raw JPEG, which is the right one: it is the only format
  // that hands over the bytes the flips made, so it is the one a user who does not go
  // looking for the choice should get.
  var busy by remember(bytes) { mutableStateOf(false) }

  // The photo's own size, read out of the header for nothing: what the preview is going to
  // measure, which is what lets the pane be laid out for the picture that is coming rather
  // than for one the size of the whole pane. It is also what tells a reused frame from the
  // last step whether it is still the right shape to show here.
  val shape = remember(bytes) { imageResolution(bytes) }

  var preview by remember {
    mutableStateOf(previewImage.showable(bytes, shape) ?: examplePreview?.asAndroidBitmap())
  }
  // Whether the restart button is asking first. Not in the holder: a dialog is up for as long
  // as it is on screen and nothing else, and one that came back after a configuration change
  // would be asking a question the user has already moved on from.
  var confirmingRestart by remember { mutableStateOf(false) }

  // Where a save or a failure reports itself, in the app's own material surface rather than
  // the platform's toast. The save's own snackbar is the one with an action: a "View" button
  // that opens the file it just wrote, which is the only place the app can offer to show the
  // user what left it.
  val snackbarHostState = remember { SnackbarHostState() }

  // The explanation is the only thing here that can be "on top", so back closes it
  // first and the screen itself second. The dialog closes itself: it owns back while it is
  // up, and dismissing it is what back means there.
  BackHandler(enabled = state.showInfo) { state.showInfo = false }

  LaunchedEffect(bytes, shape) {
    if (previewImage.covers(bytes, PREVIEW_CAP_PX)) {
      previewImage.showable(bytes, shape)?.let { preview = it }
      return@LaunchedEffect
    }
    // Only a successful decode replaces the picture: on a device it always is one, and
    // where it is not - a host preview, or a file too damaged to read - keeping what is
    // already on screen beats blanking it.
    val decoded = withContext(Dispatchers.Default) { decodeSampled(bytes, PREVIEW_CAP_PX) }
    if (decoded != null) {
      previewImage.put(bytes, PREVIEW_CAP_PX, decoded)
      preview = decoded
    }
  }

  // Report a message in the snackbar. A refused permission, a save that failed and a share
  // that failed all come through here; a save that worked does not, because that one carries
  // the View action and is raised where the file's URI is known.
  fun notify(message: String) {
    scope.launch {
      snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
    }
  }

  // The "View" of a finished save: hand the file the app just wrote to whatever app shows
  // pictures, and say so in the snackbar when there is nothing on the device willing to.
  fun viewSaved(uri: Uri, mimeType: String) {
    scope.launch {
      val opened = runCatching {
        context.startActivity(
          Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          },
        )
      }.isSuccess
      if (!opened) {
        snackbarHostState.showSnackbar(context.getString(R.string.export_view_error))
      }
    }
  }

  fun handOver(share: Boolean) {
    if (busy) return
    busy = true
    // Off the main thread. Handing over the JPEG is a file write, but a PNG or a WebP
    // is a full decode and a re-encode of the picture, and a picture large enough to
    // have been worth resizing takes long enough to drop frames while it happens.
    scope.launch {
      val encoded = withContext(Dispatchers.Default) { encodedFor(bytes, state.format) }
      val result = if (encoded == null) {
        HandOver(
          saved = false,
          message = context.getString(
            R.string.preview_encode_failed,
            context.getString(state.format.label),
          ),
        )
      } else {
        withContext(Dispatchers.IO) {
          if (share) shareImage(context, encoded, state.format)
          else saveToGallery(context, encoded, state.format)
        }
      }
      // A save that worked is the one thing that keeps the restart button from asking whether
      // the user is sure: the picture is in the gallery, so restarting costs them nothing. A
      // save that failed says the opposite, however it reads in the message.
      busy = false
      if (result.saved) {
        state.markSaved()
        val uri = result.uri
        // The message is fixed rather than the file's path: the user asked for the picture to
        // be in the gallery, and "View" is the way to go look at it. Sharing says nothing on
        // the way out - the chooser is its own feedback - so every other outcome, including
        // the share that failed, reports through the plain message path.
        scope.launch {
          val outcome = snackbarHostState.showSnackbar(
            message = context.getString(R.string.export_saved),
            actionLabel = context.getString(R.string.export_view),
            duration = SnackbarDuration.Long,
          )
          if (outcome == SnackbarResult.ActionPerformed && uri != null) {
            viewSaved(uri, state.format.mimeType)
          }
        }
      } else {
        result.message?.let { notify(it) }
      }
    }
  }

  fun restart() {
    // The picture is in the gallery, or the user has just said they do not mind losing it.
    if (state.saved) onRestart() else confirmingRestart = true
  }

  val savePermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) handOver(share = false) else notify(context.getString(R.string.preview_permission))
  }

  fun save() {
    // API 26-28 write straight into the pictures folder, which needs the permission;
    // from Q on the gallery insert does not.
    val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
      PackageManager.PERMISSION_GRANTED
    if (needsPermission) {
      savePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
      handOver(share = false)
    }
  }

  PreviewWithControls(
    preview = { paneModifier ->
      MediaPreview(
        bitmap = preview?.asImageBitmap(),
        contentDescription = stringResource(R.string.preview_image_desc),
        modifier = paneModifier,
        zoomScale = zoom.scale,
        zoomOffset = zoom.offset,
        // The pinch belongs to the photo here, as on the scan and resize screens: the
        // only things over the picture are the format row and the quiet button under it,
        // and a gesture that worked over those as well would fight the chip or the button
        // that is under the finger. It writes into the shared zoom rather than a copy, so
        // the framing is still here on the way back - including on the way back from the
        // Huffman screen, which is opened from this one.
        gesture = Modifier.transformable(
          rememberTransformableState { pinch, pan, _ ->
            zoom.scale = (zoom.scale * pinch).coerceIn(0.5f, 5f)
            zoom.offset += pan
          },
        ),
        // Medium: the decode is capped at 2000 px against a 1080 px window, so the GPU
        // minifies it by about half, and sampling a minified bitmap without mipmaps
        // turns fine texture into a moire - the same reason the scan screen, which
        // shares this cap, is drawn this way.
        filterQuality = FilterQuality.Medium,
        expectedSize = shape,
        placeholder = { Spacer(Modifier.fillMaxSize()) },
      ) { frame ->
        Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
          TitleBar(
            title = stringResource(R.string.preview_title),
            onBack = onBack,
            onInfo = { state.showInfo = true },
          )
        }

        // A photo with a screen's worth of room under it keeps that room for the controls:
        // the format row and the two buttons are stacked in the bottom portion and the
        // picture is centred above them, rather than the chips riding a bottom edge that on
        // a landscape photo is halfway up the screen.
        if (frame.stacksControls) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .height(STACKED_CONTROLS_HEIGHT)
              .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.SpaceBetween,
          ) {
            PreviewFormatRow(
              format = state.format,
              onFormatChange = { state.format = it },
              enabled = !busy,
              modifier = Modifier.padding(horizontal = 24.dp),
            )
            PreviewActions(
              busy = busy,
              onRestart = { restart() },
              onShare = { handOver(share = true) },
              onSave = { save() },
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        } else if (frame.hasRoomBelow) {
          // Otherwise the photo owns the window: the format row - the chips and the note
          // under them - sits over the bottom edge of the photo, and the two buttons take
          // the room under it. The format row has to be reachable before the picture is
          // handed over, so it cannot live in the controls slot: that slot's height comes
          // out of the preview, and a preview squeezed below 9:16 is a cropped picture -
          // which is exactly what this screen exists to show whole.
          Box(
            modifier = Modifier.fillMaxWidth().height(frame.photoHeight),
            contentAlignment = Alignment.BottomCenter,
          ) {
            PreviewFormatRow(
              format = state.format,
              onFormatChange = { state.format = it },
              enabled = !busy,
              modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(frame.roomBelow)
              .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
          ) {
            PreviewActions(
              busy = busy,
              onRestart = { restart() },
              onShare = { handOver(share = true) },
              onSave = { save() },
              modifier = Modifier.padding(horizontal = 24.dp),
            )
          }
        }
      }
    },
    // On a short window, or one the photo already covers, both go over the media
    // instead - the skeleton's other arrangement, and the same test the pane makes.
    overlay = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pane = previewFrame(preview?.asImageBitmap(), maxWidth, maxHeight, shape)
        if (!pane.hasRoomBelow) {
          PreviewFormatRow(
            format = state.format,
            onFormatChange = { state.format = it },
            enabled = !busy,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp),
          )
        }
      }
    },
    controls = {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pane = previewFrame(preview?.asImageBitmap(), maxWidth, maxHeight, shape)
        if (!pane.hasRoomBelow) {
          PreviewActions(
            busy = busy,
            onRestart = { restart() },
            onShare = { handOver(share = true) },
            onSave = { save() },
            modifier = Modifier
              .navigationBarsPadding()
              .padding(horizontal = 24.dp)
              .padding(top = 16.dp, bottom = 24.dp),
          )
        }
      }
    },
  )

  // Drawn after the screen itself, so it covers it. The app composes a screen inside
  // the flow's own Box, and this fills that Box rather than the preview pane - which
  // is what keeps the buttons below from staying live underneath the explanation.
  if (state.showInfo) {
    PreviewInfoScreen(onClose = { state.showInfo = false })
  }

  // Where a save or a failure reports itself: the app's own material surface, at the bottom
  // of the pane, rather than the platform toast it used before. It fills the flow's Box the
  // way the explanation does - the box holds nothing but the snackbar, so the rest of the
  // screen keeps the touches - and sits over the controls for the few seconds it is up.
  //
  // The default Snackbar paints itself in inverseSurface, which this dark-only scheme sets to
  // white, so it would come up as a light box in a black screen. The save's report stays dark
  // instead: a raised dark grey with the app's light text, matching the rest of the surfaces.
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.BottomCenter,
  ) {
    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp),
    ) { data ->
      Snackbar(
        snackbarData = data,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        actionColor = MaterialTheme.colorScheme.primary,
        actionContentColor = MaterialTheme.colorScheme.primary,
      )
    }
  }

  // The one thing on this screen that throws work away, so the one thing that asks first -
  // and only while there is something to throw away: a picture already in the gallery is not
  // lost by restarting, and a dialog in front of a button that costs nothing is just a
  // button that does not work. The dialog is a window of its own, so it is drawn over the
  // explanation as well if both are somehow up.
  if (confirmingRestart) {
    AlertDialog(
      onDismissRequest = { confirmingRestart = false },
      title = { Text(text = stringResource(R.string.preview_restart_title)) },
      text = { Text(text = stringResource(R.string.preview_restart_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingRestart = false
            onRestart()
          },
        ) {
          Text(text = stringResource(R.string.preview_restart_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmingRestart = false }) {
          Text(text = stringResource(R.string.preview_restart_cancel))
        }
      },
    )
  }
}

/**
 * The output format, as chips and a line saying what the choice means.
 *
 * Chips rather than a menu, like the editor's region row: four options, all visible,
 * and the one control on this screen that changes what the other two buttons do. The
 * note underneath is the part worth keeping - "JPEG" and "PNG" tell you nothing about
 * whether your glitch survives the trip, and the answer is different for each.
 *
 * Nothing else lives in this group. The Huffman screen used to be opened from a quiet
 * button under that note, and it moved to the scan screen's title: the tables are an edit
 * of the file, and by this point the file is finished - what is left here is only the
 * choice of what it is written as.
 */
@Composable
private fun PreviewFormatRow(
  format: OutputFormat,
  onFormatChange: (OutputFormat) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.preview_format_section),
      style = MaterialTheme.typography.labelMedium.copy(
        color = Color.White,
        shadow = createShadow()
      )
    )
    Spacer(Modifier.height(6.dp))
    // Four chips whose labels are of three different lengths will not divide a narrow
    // window evenly, so the row scrolls instead of wrapping or squeezing: every chip
    // keeps the width its own label needs.
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutputFormat.entries.forEach { option ->
        FilterChip(
          selected = option == format,
          enabled = enabled,
          onClick = { onFormatChange(option) },
          label = { Text(text = stringResource(option.label)) },
          colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
          ),
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Text(
      text = stringResource(formatNoteRes(format)),
      style = MaterialTheme.typography.bodySmall.copy(
        color = Color.White,
        shadow = createShadow()
      ),
      minLines = 2,
    )
  }
}

/**
 * The three things this screen can do with the session: start over with another photo, share
 * it, or save it.
 *
 * Restart leads the row, which is where the leftmost button of a row like this belongs: it is
 * the one that leaves rather than the one that hands the picture over, so it is not the
 * primary - save keeps that, as the action this screen exists for - and it is drawn away from
 * the other two by sitting first.
 */
@Composable
private fun PreviewActions(
  busy: Boolean,
  onRestart: () -> Unit,
  onShare: () -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    // Three buttons sharing the row between them, as on the editing screens: the same height
    // and the same gap, so the width is the only thing that changes when a screen has three of
    // these instead of four.
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircleAction(
      icon = Icons.Outlined.RestartAlt,
      contentDescription = stringResource(R.string.preview_restart),
      // Greyed out while a share or a save is in flight, like the other two: that hand-over is
      // reading the file this button is about to throw away.
      enabled = !busy,
      onClick = onRestart,
      modifier = Modifier.weight(1f),
    )
    CircleAction(
      icon = Icons.Outlined.Share,
      contentDescription = stringResource(R.string.preview_share),
      enabled = !busy,
      onClick = onShare,
      modifier = Modifier.weight(1f),
    )
    CircleAction(
      icon = Icons.Outlined.Save,
      contentDescription = stringResource(R.string.preview_save),
      enabled = !busy,
      primary = true,
      onClick = onSave,
      modifier = Modifier.weight(1f),
    )
  }
}

/** What handing the picture over as [format] does to it, in one line. */
private fun formatNoteRes(format: OutputFormat): Int = when (format) {
  OutputFormat.RawJpeg -> R.string.preview_format_raw_jpeg_note
  OutputFormat.Jpeg -> R.string.preview_format_jpeg_note
  OutputFormat.Png -> R.string.preview_format_png_note
  OutputFormat.Webp -> R.string.preview_format_webp_note
}

@Preview(name = "Preview", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun PreviewScreenPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      PreviewScreen(
        bytes = scanPreviewBytes(),
        examplePreview = previewImageBitmap(),
        onRestart = {},
        onBack = {},
      )
    }
  }
}

@Preview(name = "Preview - small screen", device = "spec:width=320dp,height=568dp", showBackground = true)
@Composable
private fun PreviewScreenSmallPreview() {
  GlitchTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      PreviewScreen(
        bytes = scanPreviewBytes(),
        examplePreview = previewImageBitmap(),
        onRestart = {},
        onBack = {},
      )
    }
  }
}
