package ca.justinmo.glitch

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

object PreviewUtil {
  @Composable
  fun previewImageBitmap(): ImageBitmap {
    val inputStream = LocalContext.current.assets.open("example.webp")
    val bitmap = BitmapFactory.decodeStream(inputStream)
    inputStream.close()
    return bitmap!!.asImageBitmap()
  }
}
