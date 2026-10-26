package app.mediadl

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color

/**
 * The launcher logo styles. Android can't restyle an icon on the fly, so the manifest has one alias per
 * style ([alias] is its name there) and [AppIcon] switches on the chosen one. The colours here only draw
 * the in-app preview; the real icons are the adaptive icons in res/mipmap-anydpi-v26.
 */
enum class AppLogo(
    val key: String,
    val label: String,
    val alias: String,
    /** Background, top-left to bottom-right (the same colour twice when it is flat). */
    val background: List<Color>,
    val top: Color,
    val left: Color,
    val right: Color,
) {
    Light("light", "Light", "Light", listOf(Color(0xFFEAF1FF), Color(0xFFEAF1FF)), Color(0xFF17308F), Color(0xFF2F6BFF), Color(0xFF5A9BFF)),
    Dark("dark", "Dark", "Dark", listOf(Color(0xFF121A45), Color(0xFF050818)), Color(0xFF8FBBFF), Color(0xFF2F6BFF), Color(0xFF5A9BFF)),
    Gradient("gradient", "Gradient", "Gradient", listOf(Color(0xFF4C8DFF), Color(0xFF1F4FE0)), Color(0xFF14298A), Color(0xFFCFE0FF), Color(0xFFFFFFFF)),
    Ink("ink", "Ink", "Ink", listOf(Color(0xFF15142E), Color(0xFF15142E)), Color(0xFFFFFFFF), Color(0xFF8FB5FF), Color(0xFFCFE0FF));

    companion object {
        val DEFAULT = Light

        /** The style saved under [key]; anything unknown (an older build's value) falls back to the default. */
        fun fromKey(key: String?): AppLogo = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

object AppIcon {
    /** Shows the launcher icon for [logo] and hides the others. */
    fun apply(context: Context, logo: AppLogo) {
        val manager = context.packageManager
        fun component(style: AppLogo) = ComponentName(context.packageName, "app.mediadl.Icon${style.alias}")
        fun isEnabled(style: AppLogo) = when (manager.getComponentEnabledSetting(component(style))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> style == AppLogo.DEFAULT // not touched yet: the manifest default
        }
        runCatching {
            // Enable the new one first so there is never a moment with no launcher icon.
            if (!isEnabled(logo)) {
                manager.setComponentEnabledSetting(component(logo), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            AppLogo.entries.filter { it != logo && isEnabled(it) }.forEach { style ->
                manager.setComponentEnabledSetting(component(style), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
