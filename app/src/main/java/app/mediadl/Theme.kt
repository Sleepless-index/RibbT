package app.mediadl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF2442)
val AccentDark = Color(0xFFE01A37)
val AccentRing = Color(0x2EFF2442)

data class Palette(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val sep: Color,
    val line: Color,
    val errBg: Color,
    val err: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF4F4F4), card = Color(0xFFFFFFFF), text = Color(0xFF1C1C1E), muted = Color(0xFF8A8A90),
    sep = Color(0x0F000000), line = Color(0xFFE6E6E8), errBg = Color(0xFFFFF0F2), err = Color(0xFFB4122B),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0E0F11), card = Color(0xFF1C1C1F), text = Color(0xFFF2F2F4), muted = Color(0xFF8F8F98),
    sep = Color(0x0FFFFFFF), line = Color(0xFF2C2C31), errBg = Color(0xFF2A1217), err = Color(0xFFFF8A9B),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun isDarkMode(theme: String): Boolean = when (theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = Accent, onPrimary = Color.White, background = palette.bg, onBackground = palette.text,
            surface = palette.card, onSurface = palette.text, surfaceContainer = palette.card,
            surfaceContainerHigh = palette.card, surfaceVariant = palette.line, onSurfaceVariant = palette.muted,
        )
    } else {
        lightColorScheme(
            primary = Accent, onPrimary = Color.White, background = palette.bg, onBackground = palette.text,
            surface = palette.card, onSurface = palette.text, surfaceContainer = palette.card,
            surfaceContainerHigh = palette.card, surfaceVariant = palette.line, onSurfaceVariant = palette.muted,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
