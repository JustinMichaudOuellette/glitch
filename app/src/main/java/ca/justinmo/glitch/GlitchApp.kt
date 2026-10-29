package ca.justinmo.glitch

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ca.justinmo.glitch.dht.DhtScreen
import ca.justinmo.glitch.dqt.DqtScreen
import ca.justinmo.glitch.preview.PreviewScreen
import ca.justinmo.glitch.resize.ResizeScreen
import ca.justinmo.glitch.scan.ScanScreen
import ca.justinmo.glitch.source.SourceScreen
import ca.justinmo.glitch.ui.LoadingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Five-step flow, drawn from the one [GlitchViewModel] that holds it:
 *  1. [ca.justinmo.glitch.source.SourceScreen] - pick a photo (camera or gallery)
 *  2. [ca.justinmo.glitch.resize.ResizeScreen] - resize the JPEG or keep it as-is
 *  3. [ca.justinmo.glitch.dqt.DqtScreen] - optionally retune the quantization tables (how roughly each
 *     frequency is kept)
 *  4. [ca.justinmo.glitch.scan.ScanScreen] - optionally flip bits in the scan data, and optionally
 *     retune the Huffman tables through the triangle on its title
 *  5. [ca.justinmo.glitch.preview.PreviewScreen] - look at the result, then share it, save it or
 *     change the format it is written as
 *
 * Quantization is a stop on that path because it sets how the picture is stored before the
 * scan flips bits in it. [DhtScreen] is not: the Huffman tables are the deepest edit of the
 * three - rewriting what each code means changes whole runs of blocks at once - and most
 * pictures do not need them, so they are opened from the title of the scan screen, which is
 * the step whose file they are worth looking at. They are a detour in both directions:
 * backing out of them lands back on the scan with nothing applied, and confirming them
 * carries on to the preview - which then comes back *here* rather than to the scan, because
 * the preview's back goes to whatever handed it the file. The flow stays a path of five
 * screens, and the tables are a detour off the fourth.
 *
 * Nothing here is `remember`ed. The step, the bytes being worked on, the shared zoom and
 * each screen's own state all live in [GlitchViewModel], which is what makes walking
 * back a step show the step that was just left rather than a fresh one - and what keeps
 * the flow itself across a configuration change. This function only draws the current
 * step and moves between them.
 *
 * When [sharedImageUri] is set (another app shared an image into us via ACTION_SEND),
 * the picker step is skipped: the image is loaded and the flow starts at step 2.
 */
@Composable
internal fun GlitchApp(state: GlitchViewModel, sharedImageUri: Uri? = null) {
  val context = LocalContext.current

  fun backToSource() {
    // Going back to the first screen means the user wants a different photo, and the
    // only useful thing that screen can do is the picker they already chose once - so
    // it is asked for on arrival rather than waiting for the same button to be tapped
    // again.
    state.resumePicker = state.lastSourceFlow
    state.step = GlitchViewModel.Step.Source
  }

  // OS back navigation walks up the screen stack: Preview -> Scan -> Dqt -> Resize ->
  // Source, in the flow's own order - except at the preview, which goes back to whatever
  // handed it the file: the scan screen on that path, and the Huffman tables when they were
  // the thing confirmed. The Huffman screen itself is the one step off the path, and back
  // there returns to the scan screen it was opened from rather than to whatever would sit
  // next to it in a list. None of it throws anything away: every step keeps its own state in
  // the view model, so coming back to one finds it as it was left. On the Source screen (and
  // while a shared image is still loading) the default behaviour applies, which finishes the
  // activity.
  BackHandler(
    enabled = state.step == GlitchViewModel.Step.Preview ||
      state.step == GlitchViewModel.Step.Scan ||
      state.step == GlitchViewModel.Step.Dht ||
      state.step == GlitchViewModel.Step.Dqt ||
      state.step == GlitchViewModel.Step.Resize,
  ) {
    when (state.step) {
      GlitchViewModel.Step.Preview -> state.backFromPreview()
      GlitchViewModel.Step.Scan -> state.step = GlitchViewModel.Step.Dqt
      GlitchViewModel.Step.Dht -> state.closeDht()
      GlitchViewModel.Step.Dqt -> state.step = GlitchViewModel.Step.Resize
      else -> backToSource()
    }
  }

  // Load an image shared from another app (works for JPEG, PNG, HEIC, ...).
  LaunchedEffect(sharedImageUri) {
    val uri = sharedImageUri ?: return@LaunchedEffect
    val raw = withContext(Dispatchers.IO) { uriToBytes(context, uri) }
    val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
      ?: context.getString(R.string.image_source_shared)
    if (raw == null) {
      Toast.makeText(
        context, context.getString(R.string.shared_error_read), Toast.LENGTH_LONG,
      ).show()
      state.step = GlitchViewModel.Step.Source
      return@LaunchedEffect
    }
    val jpeg = withContext(Dispatchers.Default) { toJpegBytes(raw) }
    if (jpeg == null) {
      Toast.makeText(
        context, context.getString(R.string.shared_error_decode), Toast.LENGTH_LONG,
      ).show()
      state.step = GlitchViewModel.Step.Source
    } else {
      val res = withContext(Dispatchers.Default) { imageResolution(jpeg) }
      if (res == null) {
        Toast.makeText(
          context, context.getString(R.string.shared_error_decode), Toast.LENGTH_LONG,
        ).show()
        state.step = GlitchViewModel.Step.Source
      } else {
        state.sourceBytes = jpeg
        state.sourceResolution = res
        state.sourceName = name
        state.freshFromResize()
        state.step = GlitchViewModel.Step.Resize
      }
    }
  }

  Surface(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize()) {
      when (state.step) {
        GlitchViewModel.Step.Loading -> Box(Modifier.safeDrawingPadding()) {
          LoadingScreen(stringResource(R.string.loading_shared_image))
        }
        GlitchViewModel.Step.Source -> Box(Modifier.safeDrawingPadding()) {
          SourceScreen(
            resume = state.resumePicker,
            onResumeConsumed = { state.resumePicker = null },
            onPicked = { picked, res, name, flow ->
              state.lastSourceFlow = flow
              state.resumePicker = null
              state.sourceBytes = picked
              state.sourceResolution = res
              state.sourceName = name
              // A different photo: everything the later steps decided about the last
              // one is about the last one.
              state.freshFromResize()
              state.step = GlitchViewModel.Step.Resize
            },
          )
        }
        GlitchViewModel.Step.Resize -> if (state.sourceBytes != null && state.sourceResolution != null) {
          Box(Modifier.safeDrawingPadding()) {
            ResizeScreen(
              bytes = state.sourceBytes!!,
              resolution = state.sourceResolution!!,
              previewImage = state.previewImage,
              state = state.resize,
              onBack = { backToSource() },
              onContinue = { resized, _, _ ->
                // The file and its frame changed under the pinch, so the framing does not
                // carry: the step after this one meets its picture fitted.
                state.resetZoom()
                state.advance(GlitchViewModel.Step.Resize, resized)
                state.step = GlitchViewModel.Step.Dqt
              },
            )
          }
        }
        GlitchViewModel.Step.Dqt -> if (state.workingBytes != null) {
          Box(Modifier.safeDrawingPadding()) {
            DqtScreen(
              bytes = state.workingBytes!!,
              zoom = state.zoom,
              previewImage = state.previewImage,
              state = state.dqt,
              onBack = { state.step = GlitchViewModel.Step.Resize },
              onContinue = { adjusted, _, _ ->
                state.advance(GlitchViewModel.Step.Dqt, adjusted)
                state.step = GlitchViewModel.Step.Scan
              },
            )
          }
        }
        // The Huffman screen is opened from the scan screen's title and has two ways out,
        // which go to different places: confirming it carries on to the final preview, the
        // way any step's next does, and the back arrow - which the title's upward triangle
        // also is - returns to the scan screen the trip was opened from, handing nothing on.
        // Backing out is not "the previous screen in a list": the scan screen is still holding
        // the file the tables were opened on, so there is nothing to hand it.
        //
        // It reads `dhtInput` rather than `workingBytes`, which is the whole reason that
        // field exists: the scan screen shows a file built from its own stack, and the
        // tables have to be edited on the file the user is looking at rather than on the
        // pre-flip bytes the step arrived with.
        GlitchViewModel.Step.Dht -> if (state.dhtInput != null) {
          Box(Modifier.safeDrawingPadding()) {
            DhtScreen(
              bytes = state.dhtInput!!,
              zoom = state.zoom,
              previewImage = state.previewImage,
              state = state.dht,
              // Back hands nothing on: the tables were looked at and nothing was applied,
              // so the file the scan screen is holding is the file it keeps.
              onBack = { state.closeDht() },
              onContinue = { adjusted, _, _ ->
                state.closeDht(adjusted, then = GlitchViewModel.Step.Preview)
              },
            )
          }
        }
        GlitchViewModel.Step.Scan -> if (state.workingBytes != null) {
          Box(Modifier.safeDrawingPadding()) {
            ScanScreen(
              bytes = state.workingBytes!!,
              zoom = state.zoom,
              previewImage = state.previewImage,
              state = state.scan,
              // The way off the flow's path: the Huffman screen is opened from this
              // screen's title, and its back arrow comes back here.
              onOpenHuffman = { state.openDht(it) },
              onBack = { state.step = GlitchViewModel.Step.Dqt },
              onContinue = { scanned, _, _ ->
                // The preview is told this step handed the file over, so its back comes
                // here rather than to the tables when the tables are what was confirmed.
                state.toPreview(GlitchViewModel.Step.Scan, scanned)
              },
            )
          }
        }
        GlitchViewModel.Step.Preview -> if (state.workingBytes != null) {
          Box(Modifier.safeDrawingPadding()) {
            PreviewScreen(
              bytes = state.workingBytes!!,
              zoom = state.zoom,
              previewImage = state.previewImage,
              state = state.preview,
              // Start again: back to the first screen with the picker already open. The
              // confirmation, if the picture has not been saved, is the screen's own.
              onRestart = { state.restart() },
              // Back goes to whatever made the file - the scan screen, or the Huffman
              // tables when they were confirmed onto it.
              onBack = { state.backFromPreview() },
            )
          }
        }
      }
    }
  }
}
