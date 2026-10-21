package app.mediadl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    val serverUrl: String get() = if (builtIn) BuiltInServer.url else baseUrl

    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(value) = sp.edit().putString("theme", value).apply()

    /** The accent colour as ARGB. */
    var accent: Int
        get() = sp.getInt("accent", DEFAULT_ACCENT)
        set(value) = sp.edit().putInt("accent", value).apply()

    /** The last custom accent (ARGB), kept so switching back to the default doesn't lose it; 0 when none. */
    var customAccent: Int
        get() = sp.getInt("custom_accent", 0)
        set(value) = sp.edit().putInt("custom_accent", value).apply()

    /** Save playlists and mixes as audio instead of video. */
    var playlistAudio: Boolean
        get() = sp.getBoolean("playlistAudio", false)
        set(value) = sp.edit().putBoolean("playlistAudio", value).apply()

    /** "downloads" (internal storage), "sdcard" or "folder". The old "gallery" choice is gone and maps to "downloads". */
    var saveTo: String
        get() = (sp.getString("saveTo", "downloads") ?: "downloads").let { if (it == "gallery") "downloads" else it }
        set(value) = sp.edit().putString("saveTo", value).apply()

    /** Put files in Videos / Images / Audio subfolders inside RibbT's folder. */
    var sortByType: Boolean
        get() = sp.getBoolean("sortByType", true)
        set(value) = sp.edit().putBoolean("sortByType", value).apply()

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

    var debugInfo: Boolean
        get() = sp.getBoolean("debugInfo", false)
        set(value) = sp.edit().putBoolean("debugInfo", value).apply()

    var lastLink: String
        get() = sp.getString("lastLink", "") ?: ""
        set(value) = sp.edit().putString("lastLink", value).apply()

    /** Downloads handed to the server and not saved yet. Entries older than the server keeps files are ignored. */
    @Synchronized
    fun pendingJobs(): List<PendingJob> {
        val raw = sp.getString("pendingJobs", null) ?: return emptyList()
        val cutoff = System.currentTimeMillis() - PENDING_MAX_AGE_MS
        return runCatching { JSONArray(raw).mapObjects { it.toPendingJob() } }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.started >= cutoff }
    }

    @Synchronized
    // Files saved on this phone, by the link they came from, so History can open or share them later.
    private fun readSaved(): MutableList<Pair<String, List<SavedRef>>> {
        val raw = sp.getString("savedFiles", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val files = item.getJSONArray("files")
                item.getString("link") to (0 until files.length()).map { i ->
                    val file = files.getJSONObject(i)
                    SavedRef(file.optString("uri"), file.optString("mime"), file.optString("name"))
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeSaved(list: List<Pair<String, List<SavedRef>>>) {
        val array = JSONArray()
        for ((link, files) in list) {
            array.put(
                JSONObject().put("link", link).put(
                    "files",
                    JSONArray().apply {
                        files.forEach { put(JSONObject().put("uri", it.uri).put("mime", it.mime).put("name", it.name)) }
                    },
                ),
            )
        }
        sp.edit().putString("savedFiles", array.toString()).apply()
    }

    fun savedFiles(link: String): List<SavedRef> = readSaved().firstOrNull { it.first == link }?.second.orEmpty()

    fun addSavedFile(link: String, ref: SavedRef) {
        val list = readSaved()
        val index = list.indexOfFirst { it.first == link }
        val files = if (index >= 0) list.removeAt(index).second.filter { it.uri != ref.uri } else emptyList()
        list.add(link to (files + ref))
        writeSaved(list.takeLast(300))
    }

    fun removeSavedFiles(link: String) = writeSaved(readSaved().filter { it.first != link })

    fun addPendingJob(job: PendingJob) = savePending(pendingJobs().filter { it.id != job.id } + job)

    @Synchronized
    fun removePendingJob(id: String) = savePending(pendingJobs().filter { it.id != id })

    private fun savePending(list: List<PendingJob>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        sp.edit().putString("pendingJobs", array.toString()).apply()
    }

    var settingsJson: String?
        get() = sp.getString("settingsJson", null)
        set(value) = sp.edit().putString("settingsJson", value).apply()

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:5000"
        const val DEFAULT_ACCENT = 0xFFFF2442.toInt()
        private const val PENDING_MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}
