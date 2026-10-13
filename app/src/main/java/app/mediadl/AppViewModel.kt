package app.mediadl

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

private val LINK_HINT = Regex("https?://|\\w\\.\\w{2,}/", RegexOption.IGNORE_CASE)

fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = Prefs(application)
    private val api = Api(application, prefs)

    var tab by mutableStateOf(Tab.Home)

    /** Open Settings subpage (a section title, see SettingsScreen); null shows the list of sections. */
    var settingsPage by mutableStateOf<String?>(null)

    var online by mutableStateOf<Boolean?>(null)

    /** Reached the server at least once since launch (a later drop is shown as a pill, not a full card). */
    var everOnline by mutableStateOf(false)

    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<ErrorInfo?>(null)
    var post by mutableStateOf<Post?>(null)
    var pasteReady by mutableStateOf(false)
    val downloads = mutableStateMapOf<String, DlState>()
    val toasts = mutableStateListOf<DlToast>()
    var idea by mutableStateOf(false)
    var hopSignal by mutableIntStateOf(0)
    var savedPulse by mutableIntStateOf(0)

    var platforms by mutableStateOf<List<Platform>>(emptyList())
    var history = mutableStateListOf<HistoryEntry>()
    var server by mutableStateOf<ServerSettings?>(null)

    var theme by mutableStateOf(prefs.theme)
    var saveTo by mutableStateOf(prefs.saveTo)
    var pasteAndGo by mutableStateOf(prefs.pasteAndGo)
    var layout by mutableStateOf(prefs.layout)
    var folderName by mutableStateOf(prefs.folderName)

    /** null = still checking, "" = yt-dlp isn't installed on the server. */
    var ytdlpVersion by mutableStateOf<String?>(null)
    var ytdlpStatus by mutableStateOf<String?>(null)
    var ytdlpBusy by mutableStateOf(false)
    var ytdlpChannel by mutableStateOf(prefs.ytdlpChannel)
    var baseUrl by mutableStateOf(prefs.baseUrl)
    var serverMode by mutableStateOf(prefs.serverMode)

    /** True while the built-in server is still booting (a few seconds after launch). */
    var starting by mutableStateOf(false)

    private var parseJob: Job? = null
    private var ideaJob: Job? = null
    private var parseToken = 0
    private var toastCounter = 0L
    private val savedCounts = HashMap<String, Int>()
    private val savedSizes = HashMap<String, LongArray>()

    init {
        if (prefs.builtIn) BuiltInServer.start(application)
        refresh()
    }

    fun absolute(path: String?): String? = if (path.isNullOrBlank()) null else prefs.serverUrl + path

    fun refresh() {
        viewModelScope.launch {
            var reachable = api.ping()
            if (!reachable && prefs.builtIn) {
                // The built-in server takes a few seconds to boot (longer right after an install).
                starting = true
                online = null
                var waited = 0
                while (!reachable && BuiltInServer.failure == null && waited < 120) {
                    delay(500)
                    waited++
                    reachable = api.ping()
                }
                starting = false
            }
            online = reachable
            if (reachable) {
                everOnline = true
                runCatching { platforms = api.platforms() }
                runCatching {
                    val loaded = api.serverSettings()
                    prefs.settingsJson = loaded.settings.toString()
                    server = loaded
                }
                loadHistory()
                loadYtdlp()
            }
        }
    }

    // History filters: "" means all.
    var historyPlatform by mutableStateOf("")
    var historyType by mutableStateOf("")
    var historySort by mutableStateOf("newest")

    /** History after the platform / type filters and the chosen order. */
    val visibleHistory: List<HistoryEntry>
        get() {
            val filtered = history.filter { entry ->
                (historyPlatform.isEmpty() || entry.platform == historyPlatform) &&
                    (historyType.isEmpty() || entry.hasType(historyType))
            }
            return when (historySort) {
                "oldest" -> filtered.sortedBy { it.time }
                "largest" -> filtered.sortedByDescending { it.totalBytes }
                else -> filtered.sortedByDescending { it.time }
            }
        }

    fun loadHistory() {
        viewModelScope.launch {
            runCatching { api.history() }.onSuccess {
                history.clear()
                history.addAll(it)
            }
        }
    }

    fun updateServerMode(value: String) {
        if (value == prefs.serverMode) return
        prefs.serverMode = value
        serverMode = value
        if (value == "builtin") BuiltInServer.start(getApplication()) else BuiltInServer.stop(getApplication())
        online = null
        ytdlpVersion = null
        savedPulse++
        refresh()
    }

    val builtInFailure: String? get() = BuiltInServer.failure

    fun saveBaseUrl(value: String) {
        prefs.baseUrl = value
        baseUrl = prefs.baseUrl
        online = null
        refresh()
    }

    fun updateTheme(value: String) { prefs.theme = value; theme = value; savedPulse++ }
    fun updateSaveTo(value: String) { prefs.saveTo = value; saveTo = value; savedPulse++ }
    fun updatePasteAndGo(value: Boolean) { prefs.pasteAndGo = value; pasteAndGo = value; savedPulse++ }
    fun updateLayout(value: String) { prefs.layout = value; layout = value; savedPulse++ }
    fun updateYtdlpChannel(value: String) { prefs.ytdlpChannel = value; ytdlpChannel = value; savedPulse++ }

    /** The system folder picker returned a folder (an SD card or any other location). */
    fun chooseFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val label = folderLabel(uri)
        prefs.folderUri = uri.toString()
        prefs.folderName = label
        folderName = label
        updateSaveTo("folder")
    }

    private fun folderLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        val volume = id.substringBefore(":", "")
        val path = id.substringAfter(":", "").trim('/')
        val root = if (volume.equals("primary", true)) "Internal storage" else "SD card"
        return if (path.isEmpty()) root else if (root == "Internal storage") path else "$root/$path"
    }

    fun loadYtdlp() {
        viewModelScope.launch {
            runCatching { api.ytdlpVersion() }.onSuccess { ytdlpVersion = it ?: "" }
        }
    }

    fun updateYtdlp() {
        if (ytdlpBusy) return
        ytdlpBusy = true
        ytdlpStatus = "Updating… this can take a minute."
        val toast = addToast("yt-dlp", "Updating…")
        viewModelScope.launch {
            try {
                val result = api.ytdlpUpdate(ytdlpChannel)
                result.after?.let { ytdlpVersion = it }
                val text = if (result.updated) "Updated to ${result.after}" else "Already up to date"
                ytdlpStatus = null
                finishToast(toast, ToastPhase.Ok, text)
            } catch (e: ApiException) {
                val reason = if (e.offline) {
                    online = false
                    "Server offline"
                } else {
                    e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "Update failed" }
                }
                ytdlpStatus = "Update failed · $reason"
                finishToast(toast, ToastPhase.Bad, "Failed · $reason")
            } finally {
                ytdlpBusy = false
            }
        }
    }

    fun settingValue(path: String): Any? {
        val current = server?.settings ?: return null
        val (section, key) = path.split(".")
        return current.optJSONObject(section)?.opt(key)
    }

    fun updateServerSetting(path: String, value: Any) {
        val current = server ?: return
        val (section, key) = path.split(".")
        val copy = JSONObject(current.settings.toString())
        (copy.optJSONObject(section) ?: JSONObject().also { copy.put(section, it) }).put(key, value)
        prefs.settingsJson = copy.toString()
        server = current.copy(settings = copy)
        savedPulse++
    }

    private fun platformNames(joiner: String): String {
        val labels = platforms.map { it.label }
        return when {
            labels.isEmpty() -> "a supported site"
            labels.size == 1 -> labels[0]
            else -> labels.dropLast(1).joinToString(", ") + " $joiner " + labels.last()
        }
    }

    private fun pulseIdea() {
        ideaJob?.cancel()
        idea = true
        ideaJob = viewModelScope.launch {
            delay(2200)
            idea = false
        }
    }

    fun parse(raw: String = input) {
        error = null
        val text = raw.trim()
        if (text.isEmpty()) {
            error = ErrorInfo("No link yet", "Paste a link from ${platformNames("or")}, then tap Parse.")
            return
        }
        val hosts = platforms.flatMap { it.hosts }
        val link = Links.extract(text, hosts)
        if (link.isEmpty()) {
            error = ErrorInfo("No link found", "Copy the link of a post from ${platformNames("or")} and paste it here.")
            return
        }
        val host = Links.hostOf(link)
        if (hosts.isNotEmpty() && !Links.supported(host, hosts)) {
            error = ErrorInfo(
                if (host.isNotEmpty()) "$host isn’t supported" else "Unsupported link",
                "Supported: ${platformNames("and")}. See Settings → Supported services for the full list.",
            )
            return
        }

        parseJob?.cancel()
        ideaJob?.cancel()
        idea = false
        val token = ++parseToken
        input = link
        loading = true
        post = null
        downloads.clear()
        savedCounts.remove(link)
        savedSizes.remove(link)
        parseJob = viewModelScope.launch {
            try {
                post = api.parse(link)
                online = true
                pulseIdea()
            } catch (e: ApiException) {
                if (e.offline) {
                    markOffline()
                } else {
                    report(
                        ErrorText.explain(
                            e.message,
                            "Couldn’t read that link",
                            "Check that the post is public and the link opens in your browser, then try again.",
                        ),
                    )
                }
            } finally {
                if (token == parseToken) loading = false
            }
        }
    }

    fun reset() {
        parseJob?.cancel()
        ideaJob?.cancel()
        idea = false
        parseToken++
        loading = false
        input = ""
        post = null
        error = null
        downloads.clear()
        pasteReady = false
    }

    fun receiveShared(text: String) {
        tab = Tab.Home
        input = text
        parse(text)
    }

    fun pasteFromClipboard(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) {
            error = ErrorInfo("Clipboard is empty", "Copy a link first, then tap paste again.")
            return
        }
        error = null
        input = clean
        pasteReady = false
        if (LINK_HINT.containsMatchIn(clean)) hopSignal++
    }

    fun checkClipboard(text: String?) {
        if (tab != Tab.Home || input.isNotBlank() || loading) {
            pasteReady = false
            return
        }
        val hosts = platforms.flatMap { it.hosts }
        val link = if (text.isNullOrBlank() || hosts.isEmpty()) "" else Links.extract(text, hosts)
        if (link.isEmpty() || !Links.supported(Links.hostOf(link), hosts)) {
            pasteReady = false
            return
        }
        if (pasteAndGo && link != prefs.lastLink) {
            prefs.lastLink = link
            pasteReady = false
            parse(link)
        } else {
            pasteReady = true
        }
    }

    private fun addToast(name: String, status: String): Long {
        val id = ++toastCounter
        toasts.add(DlToast(id, name, status, null, ToastPhase.Active))
        return id
    }

    private fun updateToast(id: Long, transform: (DlToast) -> DlToast) {
        val index = toasts.indexOfFirst { it.id == id }
        if (index >= 0) toasts[index] = transform(toasts[index])
    }

    /** Shows a failure as a toast (and flags the mascot) instead of a box in the page. */
    fun report(info: ErrorInfo) {
        error = info.copy(inline = false)
        if (toasts.any { it.phase == ToastPhase.Bad && it.name == info.title }) return
        val id = ++toastCounter
        toasts.add(DlToast(id, info.title, info.hint.orEmpty(), null, ToastPhase.Bad))
        viewModelScope.launch {
            delay(7000)
            dismissToast(id)
        }
    }

    /** The server stopped answering: one toast per drop, then the pill near the mascot. */
    private fun markOffline() {
        val wasOffline = online == false
        online = false
        if (!wasOffline && everOnline) report(ErrorText.offline(prefs.builtIn))
        else error = ErrorText.offline(prefs.builtIn).copy(inline = false)
    }

    fun dismissToast(id: Long) {
        toasts.removeAll { it.id == id }
    }

    private fun finishToast(id: Long, phase: ToastPhase, status: String) {
        val name = toasts.firstOrNull { it.id == id }?.name.orEmpty()
        Notifier.download(getApplication(), id, name, status, ok = phase == ToastPhase.Ok)
        updateToast(id) { it.copy(status = status, progress = null, phase = phase) }
        if (phase == ToastPhase.Ok) hopSignal++
        viewModelScope.launch {
            delay(if (phase == ToastPhase.Ok) 3500 else 6000)
            dismissToast(id)
        }
    }

    private fun progressText(position: Int, total: Int, fraction: Float?, received: Long): String {
        val percent = fraction?.let { (it * 100).toInt().coerceIn(0, 99) }
        if (total > 1) {
            val base = "Downloading $position of $total"
            return if (percent != null) "$base · $percent%" else base
        }
        val bytes = formatBytes(received)
        return when {
            percent != null -> "Downloading · $percent%" + if (bytes.isNotEmpty()) " · $bytes" else ""
            bytes.isNotEmpty() -> "Downloading · $bytes"
            else -> "Downloading"
        }
    }

    fun download(post: Post, spec: DownloadSpec, pending: String = "Preparing…") {
        if (downloads[spec.url] is DlState.Busy) return
        downloads[spec.url] = DlState.Busy(null)
        val toast = addToast(spec.name, pending)
        viewModelScope.launch {
            val folder = saveOne(post, spec, toast, 1, 1, true)
            if (folder != null) {
                finishToast(toast, ToastPhase.Ok, "Saved to $folder")
                delay(1800)
                if (downloads[spec.url] is DlState.Done) downloads.remove(spec.url)
            }
        }
    }

    fun downloadAll(post: Post, items: List<MediaItem>) {
        val specs = items.mapNotNull { it.downloads.firstOrNull() }
        val key = "all:" + post.link
        if (specs.isEmpty() || downloads[key] is DlState.Busy) return
        downloads[key] = DlState.Busy(null)
        val toast = addToast("${specs.size} files", "Preparing…")
        viewModelScope.launch {
            var folder: String? = null
            for ((index, spec) in specs.withIndex()) {
                folder = saveOne(post, spec, toast, index + 1, specs.size, false)
                if (folder == null) break
                delay(300)
            }
            if (folder != null) {
                downloads[key] = DlState.Done(folder)
                finishToast(toast, ToastPhase.Ok, "Saved ${specs.size} files to $folder")
                delay(1800)
                if (downloads[key] is DlState.Done) downloads.remove(key)
            } else {
                downloads.remove(key)
            }
        }
    }

    private suspend fun saveOne(
        post: Post,
        spec: DownloadSpec,
        toast: Long,
        position: Int,
        total: Int,
        track: Boolean,
    ): String? {
        return try {
            val saved = api.download(
                spec,
                saveTo,
                onFileName = { name -> if (total == 1) updateToast(toast) { it.copy(name = name) } },
                onProgress = { fraction, received ->
                    val overall = fraction?.let { (position - 1 + it) / total }
                    updateToast(toast) {
                        it.copy(progress = overall, status = progressText(position, total, fraction, received))
                    }
                },
            )
            val folder = saved.label
            if (track) downloads[spec.url] = DlState.Done(folder)
            recordHistory(post, saved)
            folder
        } catch (e: ApiException) {
            if (track) downloads.remove(spec.url)
            val info = if (e.offline) {
                online = false
                ErrorText.offline(prefs.builtIn)
            } else {
                ErrorText.explain(e.message, "Download failed", "Try again. If it keeps failing, paste the link again.")
            }
            error = info.copy(inline = false)
            finishToast(toast, ToastPhase.Bad, "Failed · " + info.title.take(80))
            null
        }
    }

    private suspend fun recordHistory(post: Post, saved: SavedFile) {
        val count = (savedCounts[post.link] ?: 0) + 1
        savedCounts[post.link] = count
        val sizes = savedSizes.getOrPut(post.link) { LongArray(3) }
        sizes[saved.kind] += saved.bytes
        runCatching {
            api.addHistory(post, saved.label, count, sizes)
            val updated = api.history()
            history.clear()
            history.addAll(updated)
        }
    }

    fun openFromHistory(entry: HistoryEntry) {
        tab = Tab.Home
        input = entry.link
        parse(entry.link)
    }

    fun deleteHistory(entry: HistoryEntry) {
        viewModelScope.launch {
            runCatching { api.removeHistory(entry.id) }
            history.removeAll { it.id == entry.id }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { api.clearHistory() }
            history.clear()
        }
    }
}
