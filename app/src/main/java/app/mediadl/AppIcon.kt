package app.mediadl

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The launcher icon follows the accent colour. Android can't recolour an icon on the fly, so the
 * manifest has one alias per icon colour and this switches on the one closest to the accent.
 */
object AppIcon {
    /** Alias suffix and its colour; must match the aliases in the manifest and colors.xml. */
    private val PRESETS = listOf(
        "Red" to 0xFF2442,
        "Orange" to 0xF26B21,
        "Green" to 0x2FA66A,
        "Teal" to 0x0E9AA7,
        "Blue" to 0x5577E6,
        "Violet" to 0x8452E6,
        "Pink" to 0xE8467F,
    )
    private const val DEFAULT = "Red"

    /** The preset whose colour is nearest to [color]. */
    fun nearest(color: Color): String {
        val argb = color.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return PRESETS.minBy { (_, rgb) ->
            val dr = r - ((rgb shr 16) and 0xFF)
            val dg = g - ((rgb shr 8) and 0xFF)
            val db = b - (rgb and 0xFF)
            dr * dr + dg * dg + db * db
        }.first
    }

    /** Shows the icon closest to [accent], or the default red one when [accent] is null. */
    fun apply(context: Context, accent: Color?) {
        val target = if (accent == null) DEFAULT else nearest(accent)
        val manager = context.packageManager
        fun component(name: String) = ComponentName(context.packageName, "app.mediadl.Icon$name")
        fun isEnabled(name: String) = when (manager.getComponentEnabledSetting(component(name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> name == DEFAULT // not touched yet: the manifest default
        }
        runCatching {
            if (!isEnabled(target)) {
                manager.setComponentEnabledSetting(component(target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            PRESETS.map { it.first }.filter { it != target && isEnabled(it) }.forEach { name ->
                manager.setComponentEnabledSetting(component(name), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
