package ca.justinmo.glitch

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import ca.justinmo.glitch.ui.theme.GlitchTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
    // One view model for the whole app, owned by the activity: the flow is one edit,
    // and its state has to outlive both the screens inside it and any configuration
    // change. It is fetched here rather than inside the composable so that the flow can
    // be put on the right step *before* the first frame - otherwise a photo shared into
    // the app would flash the picker before the loading screen appeared.
    val state = ViewModelProvider(this)[GlitchViewModel::class.java]
    val shared = sharedImageUri(intent)
    state.start(hasSharedImage = shared != null)
    setContent {
      GlitchTheme {
        GlitchApp(state = state, sharedImageUri = shared)
      }
    }
  }

  /**
   * The image another app shared into us (ACTION_SEND with an image MIME
   * type), or null when the app was launched normally. The Uri comes with a
   * temporary read grant from the sending app.
   */
  private fun sharedImageUri(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_SEND) return null
    val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    if (uri != null) return uri
    // Fall back to ClipData (some senders only put the stream there).
    val clip = intent.clipData ?: return null
    for (i in 0 until clip.itemCount) {
      val item = clip.getItemAt(i).uri ?: continue
      val type = runCatching { contentResolver.getType(item) }.getOrNull()
      if (type == null || type.startsWith("image/")) return item
    }
    return null
  }
}
