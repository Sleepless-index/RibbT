package app.mediadl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** The blue of the app logo's cube. */
val DefaultAccent = Color(0xFF2F6BFF)

/**
 * The accent colour the user picked. It lives in snapshot state, so everything that reads [Accent]
 * while composing or drawing updates the moment it changes. Never copy it into a top-level `val`
 * (that would freeze the colour that was current at startup); use a getter instead.
 */
private var accentColor by mutableStateOf(DefaultAccent)

val Accent: Color get() = accentColor
val AccentDark: Color get() = lerp(accentColor, Color.Black, 0.12f)
val AccentRing: Color get() = accentColor.copy(alpha = 0.18f)

/** Text and icons drawn on top of the accent: white, or near-black when the accent is a light colour. */
val AccentOn: Color get() = if (accentColor.luminance() > 0.55f) Color(0xFF1C1C1E) else Color.White

fun applyAccent(color: Color) {
    accentColor = color.copy(alpha = 1f)
}

data class Palette(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val sep: Color,
    val line: Color,
    /** A tinted fill for inputs and segmented controls that sit inside a card. */
    val soft: Color,
    val errBg: Color,
    val err: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF6F3EE), card = Color(0xFFFFFFFF), text = Color(0xFF19161B), muted = Color(0xFF6B656F),
    sep = Color(0x0F000000), line = Color(0xFFE8E2DA), soft = Color(0xFFEFEAE3), errBg = Color(0xFFFFECEF), err = Color(0xFFB3122A),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0E0D10), card = Color(0xFF19181C), text = Color(0xFFF5F2F6), muted = Color(0xFFA39EA9),
    sep = Color(0x0FFFFFFF), line = Color(0xFF2A282E), soft = Color(0xFF242227), errBg = Color(0xFF2B1319), err = Color(0xFFFF8A9B),
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
            primary = Accent, onPrimary = AccentOn, background = palette.bg, onBackground = palette.text,
            surface = palette.card, onSurface = palette.text, surfaceContainer = palette.card,
            surfaceContainerHigh = palette.card, surfaceVariant = palette.line, onSurfaceVariant = palette.muted,
        )
    } else {
        lightColorScheme(
            primary = Accent, onPrimary = AccentOn, background = palette.bg, onBackground = palette.text,
            surface = palette.card, onSurface = palette.text, surfaceContainer = palette.card,
            surfaceContainerHigh = palette.card, surfaceVariant = palette.line, onSurfaceVariant = palette.muted,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
