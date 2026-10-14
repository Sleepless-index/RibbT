package app.mediadl

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mediadl", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit().putString("baseUrl", value.trim().trimEnd('/').ifBlank { DEFAULT_URL }).apply()

    /** "builtin" runs the server inside the app; "custom" talks to the address below (Termux, a PC…). */
    var serverMode: String
        get() = sp.getString("serverMode", null) ?: if (sp.contains("baseUrl")) "custom" else "builtin"
        set(value) = sp.edit().putString("serverMode", value).apply()

    val builtIn: Boolean get() = serverMode == "builtin"

    /** The address every request goes to. */
    val serverUrl: String get() = if (builtIn) BuiltInServer.URL else baseUrl

    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(value) = sp.edit().putString("theme", value).apply()

    var saveTo: String
        get() = sp.getString("saveTo", "downloads") ?: "downloads"
        set(value) = sp.edit().putString("saveTo", value).apply()

    /** Tree URI picked with the system folder picker (SD card or any folder). */
    var folderUri: String
        get() = sp.getString("folderUri", "") ?: ""
        set(value) = sp.edit().putString("folderUri", value).apply()

    var folderName: String
        get() = sp.getString("folderName", "") ?: ""
        set(value) = sp.edit().putString("folderName", value).apply()

    /** "stable" or "nightly" - which yt-dlp build the update button installs. */
    var ytdlpChannel: String
        get() = sp.getString("ytdlpChannel", "stable") ?: "stable"
        set(value) = sp.edit().putString("ytdlpChannel", value).apply()

    var layout: String
        get() = sp.getString("layout", "carousel") ?: "carousel"
        set(value) = sp.edit().putString("layout", value).apply()

    /** "system" follows Android's remove-animations switch; "on" / "off" override it for the mascot. */
    var reduceMotion: String
        get() = sp.getString("reduceMotion", "system") ?: "system"
        set(value) = sp.edit().putString("reduceMotion", value).apply()

    var pasteAndGo: Boolean
        get() = sp.getBoolean("pasteAndGo", true)
        set(value) = sp.edit().putBoolean("pasteAndGo", value).apply()

    var lastLink: String
        get() = sp.getString("lastLink", "") ?: ""
        set(value) = sp.edit().putString("lastLink", value).apply()

    var settingsJson: String?
        get() = sp.getString("settingsJson", null)
        set(value) = sp.edit().putString("settingsJson", value).apply()

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:5000"
    }
}
