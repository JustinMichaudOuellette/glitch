package ca.justinmo.glitch.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
  primary = White,
  onPrimary = Black,
  primaryContainer = DarkGrey,
  onPrimaryContainer = White,
  inversePrimary = Black,
  secondary = Grey,
  onSecondary = White,
  secondaryContainer = DarkGrey,
  onSecondaryContainer = White,
  tertiary = LightGrey,
  onTertiary = Black,
  tertiaryContainer = DarkGrey,
  onTertiaryContainer = White,
  background = Black,
  onBackground = White,
  surface = Black,
  onSurface = White,
  surfaceVariant = DarkGrey,
  onSurfaceVariant = LightGrey,
  surfaceTint = Color.Transparent,
  inverseSurface = White,
  inverseOnSurface = Black,
  error = White,
  onError = Black,
  errorContainer = DarkGrey,
  onErrorContainer = White,
  outline = Grey,
  outlineVariant = DarkGrey,
  scrim = Black,
  surfaceContainerLowest = Black,
  surfaceContainerLow = Black,
  surfaceContainer = Black,
  surfaceContainerHigh = DarkGrey,
  surfaceContainerHighest = DarkGrey,
)

private val LightColorScheme = lightColorScheme(
  primary = Black,
  onPrimary = White,
  primaryContainer = LightGrey,
  onPrimaryContainer = Black,
  inversePrimary = White,
  secondary = DarkGrey,
  onSecondary = White,
  secondaryContainer = LightGrey,
  onSecondaryContainer = Black,
  tertiary = Grey,
  onTertiary = White,
  tertiaryContainer = ExtraLightGrey,
  onTertiaryContainer = Black,
  background = White,
  onBackground = Black,
  surface = White,
  onSurface = Black,
  surfaceVariant = ExtraLightGrey,
  onSurfaceVariant = DarkGrey,
  surfaceTint = Color.Transparent,
  inverseSurface = Black,
  inverseOnSurface = White,
  error = Black,
  onError = White,
  errorContainer = LightGrey,
  onErrorContainer = Black,
  outline = Grey,
  outlineVariant = LightGrey,
  scrim = Black,

  surfaceContainerLowest = White,
  surfaceContainerLow = ExtraLightGrey,
  surfaceContainer = ExtraLightGrey,
  surfaceContainerHigh = LightGrey,
  surfaceContainerHighest = LightGrey,
)

@Composable
fun GlitchTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    content = content
  )
}
