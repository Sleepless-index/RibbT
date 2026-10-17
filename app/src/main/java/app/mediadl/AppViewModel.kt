package app.mediadl

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val LINK_HINT = Regex("https?://|\\w\\.\\w{2,}/", RegexOption.IGNORE_CASE)

private const val COOKIE_MAX_BYTES = 2 * 1024 * 1024
private const val QUEUE_LIMIT = 40

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
    var playlist by mutableStateOf<Playlist?>(null)
    var pasteReady by mutableStateOf(false)
    val downloads = mutableStateMapOf<String, DlState>()
    val toasts = mutableStateListOf<DlToast>()

    /** Every download of this session, newest last: what the queue button lists after the toasts are gone. */
    val queue = mutableStateListOf<DlToast>()
    var idea by mutableStateOf(false)
    var hopSignal by mutableIntStateOf(0)
    var savedPulse by mutableIntStateOf(0)

    var platforms by mutableStateOf<List<Platform>>(emptyList())
    var history = mutableStateListOf<HistoryEntry>()
    var server by mutableStateOf<ServerSettings?>(null)

    var theme by mutableStateOf(prefs.theme)
    var accent by mutableStateOf(Color(prefs.accent))
    var playlistAudio by mutableStateOf(prefs.playlistAudio)
    var saveTo by mutableStateOf(prefs.saveTo)
    var sortByType by mutableStateOf(prefs.sortByType)

    /** An SD card is inserted right now (checked when the Downloading page opens and when one is plugged or pulled). */
    var sdAvailable by mutableStateOf(Storage.sdAvailable(application))
    var pasteAndGo by mutableStateOf(prefs.pasteAndGo)
    var reduceMotion by mutableStateOf(prefs.reduceMotion)

    /** Bumps on every edit of the link field so the mascot can watch it being typed. */
    var typingSignal by mutableIntStateOf(0)
    var layout by mutableStateOf(prefs.layout)
    var folderName by mutableStateOf(prefs.folderName)

    /** null = still checking, "" = yt-dlp isn't installed on the server. */
    var ytdlpVersion by mutableStateOf<String?>(null)
    var ytdlpStatus by mutableStateOf<String?>(null)
    var ytdlpBusy by mutableStateOf(false)
    var ytdlpChecking by mutableStateOf(false)

    var debugInfo by mutableStateOf(prefs.debugInfo)
    val appVersion: String = AppUpdate.installedVersion(application)
    var appChecking by mutableStateOf(false)
    var appRelease by mutableStateOf<AppRelease?>(null)

    /** The newest yt-dlp on PyPI for the chosen channel; [ytdlpAvailable] is null until we've looked. */
    var ytdlpLatest by mutableStateOf<String?>(null)
    var ytdlpAvailable by mutableStateOf<Boolean?>(null)
    var ytdlpChannel by mutableStateOf(prefs.ytdlpChannel)
    var baseUrl by mutableStateOf(prefs.baseUrl)
    var serverMode by mutableStateOf(prefs.serverMode)

    /** Sign-in cookies stored on the server (null = not loaded yet). */
    var cookies by mutableStateOf<CookieInfo?>(null)
    var cookiesBusy by mutableStateOf(false)

    /** True while the built-in server is still booting (a few seconds after launch). */
    var starting by mutableStateOf(false)

    private var parseJob: Job? = null
    private var ideaJob: Job? = null
    private var parseToken = 0
    private var toastCounter = 0L
    private val savedCounts = HashMap<String, Int>()
    private val savedSizes = HashMap<String, LongArray>()

    /** Server jobs this app is collecting right now, so a restart-recovery never saves the same file twice. */
    private val liveJobs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Per download toast: the running coroutine, the server job behind it, and what Retry should do.
    private val toastJobs = HashMap<Long, Job>()
    private val toastServerJobs = HashMap<Long, String>()
    private val retryActions = HashMap<Long, () -> Unit>()

    /** A link shared into the app while the built-in server was still starting; parsed as soon as it is up. */
    private var pendingShare: String? = null

    init {
        applyAccent(accent)
        AppInstaller.cleanup(application)
        AppInstaller.onResult = { ok, message ->
            appUpdateBusy = false
            if (!ok) {
                val toast = addToast("RibbT update", "")
                finishToast(toast, ToastPhase.Bad, "Failed · $message", notify = false)
            }
        }
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
            if (!reachable && pendingShare != null) {
                pendingShare = null
                loading = false
            }
            if (reachable) {
                everOnline = true
                runCatching { platforms = api.platforms() }
                pendingShare?.let { shared ->
                    pendingShare = null
                    loading = false
                    parse(shared)
                }
                runCatching {
                    val loaded = api.serverSettings()
                    prefs.settingsJson = loaded.settings.toString()
                    server = loaded
                }
                loadHistory()
                loadYtdlp()
                loadCookies()
                recoverJobs()
            }
        }
    }

    // History filters: "" means all.
    var historyPlatform by mutableStateOf("")
    var historyType by mutableStateOf("")
    var historySort by mutableStateOf("newest")
    var historyQuery by mutableStateOf("")

    /** History after the platform / type filters and the chosen order. */
    val visibleHistory: List<HistoryEntry>
        get() {
            val query = historyQuery.trim()
            val filtered = history.filter { entry ->
                (historyPlatform.isEmpty() || entry.platform == historyPlatform) &&
                    (historyType.isEmpty() || entry.hasType(historyType)) &&
                    (query.isEmpty() || entry.title.contains(query, ignoreCase = true) ||
                        entry.author.contains(query, ignoreCase = true))
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
        cookies = null
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

    /** Applies the accent colour at once; [save] is false while a finger is still dragging in the picker. */
    fun updateAccent(color: Color, save: Boolean = true) {
        accent = color
        applyAccent(color)
        if (save) {
            prefs.accent = color.toArgb()
            savedPulse++
        }
    }

    fun updatePlaylistAudio(value: Boolean) { prefs.playlistAudio = value; playlistAudio = value }
    fun updateSaveTo(value: String) { prefs.saveTo = value; saveTo = value; savedPulse++ }
    fun updateSortByType(value: Boolean) { prefs.sortByType = value; sortByType = value; savedPulse++ }

    /** The custom folder can still be written to (false when none is picked, or its card or permission is gone). */
    var folderOk by mutableStateOf(prefs.folderUri.isNotBlank())

    fun refreshStorage() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (sd, folder) = withContext(Dispatchers.IO) {
                Storage.sdAvailable(app) to Storage.folderUsable(app, prefs.folderUri)
            }
            sdAvailable = sd
            folderOk = folder
        }
    }

    /**
     * Where [chosen] really goes right now. An SD card that was pulled out, or a folder that is no longer
     * reachable, falls back to the phone's Downloads, with a short note for the toast. Checked before the
     * download starts so nobody waits for a file that then has nowhere to go.
     */
    private fun resolveSaveTarget(chosen: String): Pair<String, String?> {
        val app = getApplication<Application>()
        return when (chosen) {
            "sdcard" -> if (Storage.sdAvailable(app)) chosen to null else "downloads" to "SD card not found"
            "folder" -> if (Storage.folderUsable(app, prefs.folderUri)) chosen to null else "downloads" to "Folder unavailable"
            else -> chosen to null
        }
    }

    private fun savedText(prefix: String, folder: String, note: String?): String =
        if (note == null) "$prefix $folder" else "$note · $prefix $folder"
    fun updateReduceMotion(value: String) { prefs.reduceMotion = value; reduceMotion = value; savedPulse++ }
    fun updatePasteAndGo(value: Boolean) { prefs.pasteAndGo = value; pasteAndGo = value; savedPulse++ }
    fun updateDebugInfo(value: Boolean) { prefs.debugInfo = value; debugInfo = value; savedPulse++ }
    fun updateLayout(value: String) { prefs.layout = value; layout = value; savedPulse++ }
    fun updateYtdlpChannel(value: String) {
        prefs.ytdlpChannel = value
        ytdlpChannel = value
        ytdlpAvailable = null
        savedPulse++
        checkYtdlp()
    }

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
        folderOk = true
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
            checkYtdlp()
        }
    }

    /** Looks for a newer yt-dlp. Quiet when it runs by itself; [manual] reports the answer as a toast. */
    fun checkYtdlp(manual: Boolean = false) {
        if (ytdlpBusy || ytdlpChecking) return
        ytdlpChecking = true
        val toast = if (manual) addToast("yt-dlp", "Checking for updates…") else null
        viewModelScope.launch {
            try {
                val result = api.ytdlpLatest(ytdlpChannel)
                ytdlpLatest = result.latest
                ytdlpAvailable = result.available
                toast?.let {
                    finishToast(it, ToastPhase.Ok, if (result.available) "Update available · ${result.latest}" else "yt-dlp is up to date")
                }
            } catch (e: ApiException) {
                toast?.let { finishToast(it, ToastPhase.Bad, "Couldn’t check · ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(60)}") }
            } finally {
                ytdlpChecking = false
            }
        }
    }

    /** [silent] checks in the background (when Settings opens) without a toast, and stays quiet on errors. */
    fun checkAppUpdate(silent: Boolean = false) {
        if (appChecking) return
        appChecking = true
        val toast = if (silent) null else addToast("RibbT", "Checking for updates…")
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { AppUpdate.latest(appVersion) }
                appRelease = release
                val text = if (release.available) "Update available · ${release.version}" else "RibbT is up to date"
                if (toast != null) finishToast(toast, ToastPhase.Ok, text, notify = false)
            } catch (e: ApiException) {
                if (toast != null) finishToast(toast, ToastPhase.Bad, "Couldn’t check · ${e.message.orEmpty().take(70)}", notify = false)
            } finally {
                appChecking = false
            }
        }
    }

    /** True while the new RibbT is being downloaded or handed to Android's installer. */
    var appUpdateBusy by mutableStateOf(false)

    /** "Downloading · 42%" style line shown under the RibbT row while an update is in progress. */
    var appUpdateStatus by mutableStateOf<String?>(null)

    /**
     * Downloads the new APK inside the app (progress shows in the toast, the queue and the Settings row)
     * and then asks Android to install it. Android shows its own confirmation on top of RibbT.
     */
    fun installAppUpdate() {
        val release = appRelease ?: return
        if (appUpdateBusy) return
        val app = getApplication<Application>()
        if (!AppInstaller.canInstall(app)) {
            AppInstaller.openInstallSettings(app)
            val toast = addToast("RibbT update", "")
            finishToast(toast, ToastPhase.Bad, "Allow RibbT to install updates, then tap Update again", notify = false)
            return
        }
        appUpdateBusy = true
        appUpdateStatus = "Starting…"
        val toast = addToast("RibbT ${release.version.orEmpty()}".trim(), "Downloading update…", cancellable = true)
        val job = viewModelScope.launch {
            try {
                val apk = AppInstaller.download(app, release.apkUrl) { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    val line = if (fraction != null) "Downloading · ${(fraction * 100).toInt()}%" else "Downloading · ${formatBytes(done)}"
                    appUpdateStatus = line
                    updateToast(toast) { it.copy(progress = fraction, status = line) }
                }
                appUpdateStatus = "Installing…"
                updateToast(toast) { it.copy(progress = null, status = "Confirm the update on the next screen") }
                AppInstaller.install(app, apk)
                // Android now shows its own confirmation. The app is replaced and restarted when it finishes.
                finishToast(toast, ToastPhase.Ok, "Ready to install", notify = false)
                appUpdateStatus = null
                appUpdateBusy = false
            } catch (e: ApiException) {
                appUpdateBusy = false
                appUpdateStatus = null
                finishToast(toast, ToastPhase.Bad, "Failed · ${e.message.orEmpty().take(90)}", notify = false)
            }
        }
        trackDownload(toast, job) {
            appUpdateBusy = false
            appUpdateStatus = null
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
                ytdlpAvailable = false
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
        playlist = null
        downloads.clear()
        savedCounts.remove(link)
        savedSizes.remove(link)
        parseJob = viewModelScope.launch {
            try {
                when (val result = api.parseAny(link)) {
                    is ParseResult.Single -> post = result.post
                    is ParseResult.Listing -> playlist = result.playlist
                }
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
                        debugText("Reading the link", link, e.message, e.status),
                    )
                }
            } finally {
                if (token == parseToken) loading = false
            }
        }
    }

    fun openPlaylist(link: String) {
        if (loading) return
        error = null
        parseJob?.cancel()
        ideaJob?.cancel()
        idea = false
        val token = ++parseToken
        loading = true
        parseJob = viewModelScope.launch {
            try {
                playlist = api.playlist(link)
                post = null
                online = true
                pulseIdea()
            } catch (e: ApiException) {
                if (e.offline) {
                    markOffline()
                } else {
                    report(
                        ErrorText.explain(
                            e.message,
                            "Couldn’t read that playlist",
                            "Check that the playlist is public, then try again.",
                        ),
                        debugText("Reading the playlist", link, e.message, e.status),
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
        playlist = null
        error = null
        downloads.clear()
        pasteReady = false
    }

    fun receiveShared(text: String) {
        tab = Tab.Home
        input = text
        if (online == true) {
            parse(text)
            return
        }
        // Opened by a share while the built-in server is still starting: wait for it, then parse.
        pendingShare = text
        error = null
        loading = true
        if (online == false && !starting) refresh()
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

    private fun addToast(name: String, status: String, cancellable: Boolean = false): Long {
        val id = ++toastCounter
        val toast = DlToast(id, name, status, null, ToastPhase.Active, canCancel = cancellable)
        toasts.add(toast)
        if (cancellable) {
            queue.add(toast)
            while (queue.size > QUEUE_LIMIT) {
                val oldest = queue.indexOfFirst { it.phase != ToastPhase.Active }.takeIf { it >= 0 } ?: 0
                retryActions.remove(queue[oldest].id)
                queue.removeAt(oldest)
            }
        }
        return id
    }

    private fun updateToast(id: Long, transform: (DlToast) -> DlToast) {
        val index = toasts.indexOfFirst { it.id == id }
        if (index >= 0) toasts[index] = transform(toasts[index])
        val inQueue = queue.indexOfFirst { it.id == id }
        if (inQueue >= 0) queue[inQueue] = if (index >= 0) toasts[index] else transform(queue[inQueue])
    }

    /** Drops finished, failed and cancelled entries from the queue list. */
    fun clearFinishedQueue() {
        queue.filter { it.phase != ToastPhase.Active }.forEach { retryActions.remove(it.id) }
        queue.removeAll { it.phase != ToastPhase.Active }
    }

    /** Runs a failed download again from the queue list, replacing its old entry. */
    fun retryFromQueue(id: Long) {
        val action = retryActions.remove(id) ?: return
        queue.removeAll { it.id == id }
        dismissToast(id)
        action()
    }

    /** Shows a failure as a toast (and flags the mascot) instead of a box in the page. */
    fun report(info: ErrorInfo, debug: String? = null) {
        error = info.copy(inline = false)
        if (toasts.any { it.phase == ToastPhase.Bad && it.name == info.title }) return
        val id = ++toastCounter
        toasts.add(DlToast(id, info.title, info.hint.orEmpty(), null, ToastPhase.Bad, debug = debug))
        viewModelScope.launch {
            delay(if (debug != null) 20000 else 7000)
            dismissToast(id)
        }
    }

    private fun debugText(stage: String, link: String?, message: String?, status: Int): String? {
        if (!debugInfo) return null
        val ytdlp = when (val version = ytdlpVersion) {
            null -> "unknown"
            "" -> "not installed"
            else -> version
        }
        return buildString {
            appendLine("RibbT debug info")
            appendLine("App: $appVersion · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Server: ${if (prefs.builtIn) "built-in" else "custom"} · yt-dlp $ytdlp")
            appendLine("Stage: $stage")
            if (!link.isNullOrBlank()) appendLine("Link: $link")
            if (status != 0) appendLine("Status: $status")
            appendLine("Time: ${java.util.Date()}")
            append("Error: ${message.orEmpty().trim().take(1500)}")
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
        // The retry action stays so the queue list can still offer Retry after the toast is gone.
        toasts.removeAll { it.id == id }
    }

    private fun finishToast(id: Long, phase: ToastPhase, status: String, notify: Boolean = true) {
        val name = (toasts.firstOrNull { it.id == id } ?: queue.firstOrNull { it.id == id })?.name.orEmpty()
        if (notify) Notifier.download(getApplication(), id, name, status, ok = phase == ToastPhase.Ok)
        updateToast(id) { it.copy(status = status, progress = null, phase = phase, canCancel = false) }
        if (phase == ToastPhase.Ok) hopSignal++
        val lingers = phase == ToastPhase.Bad && toasts.firstOrNull { it.id == id }?.debug != null
        viewModelScope.launch {
            delay(if (phase == ToastPhase.Ok) 3500 else if (lingers) 20000 else 9000)
            dismissToast(id)
        }
    }

    /** One bar for the whole download: fetching on the server first, then copying the file onto the phone. */
    private fun mergedFraction(progress: DlProgress): Float? = when (progress.phase) {
        "downloading" -> progress.fraction?.let { it * 0.85f }
        "saving" -> 0.85f + (progress.fraction ?: 0f) * 0.15f
        else -> null // waiting or converting: nothing to measure, the bar sweeps instead
    }

    private fun progressText(position: Int, total: Int, progress: DlProgress): String {
        val verb = when (progress.phase) {
            "waiting" -> "Waiting"
            "converting" -> "Converting"
            else -> "Downloading" // fetching on the server and copying to the phone read as one download
        }
        val measurable = progress.phase == "downloading" || progress.phase == "saving"
        val percent = if (measurable) progress.fraction?.let { (it * 100).toInt().coerceIn(0, 99) } else null
        val bytes = if (measurable) formatBytes(progress.bytes) else ""
        return buildString {
            append(if (total > 1) "$verb $position of $total" else verb)
            if (percent != null) append(" · $percent%")
            if (total == 1 && bytes.isNotEmpty()) append(" · $bytes")
        }
    }

    fun download(post: Post, spec: DownloadSpec, pending: String = "Preparing…", record: Boolean = true) {
        if (downloads[spec.url] is DlState.Busy) return
        downloads[spec.url] = DlState.Busy(null)
        val toast = addToast(spec.name, pending, cancellable = true)
        val job = viewModelScope.launch {
            val (target, note) = resolveSaveTarget(saveTo)
            val folder = saveOne(post, spec, toast, 1, 1, true, target, record = record)
            if (folder != null) {
                finishToast(toast, ToastPhase.Ok, savedText("Saved to", folder, note))
                delay(1800)
                if (downloads[spec.url] is DlState.Done) downloads.remove(spec.url)
            } else {
                offerRetry(toast) { download(post, spec, pending, record) }
            }
        }
        trackDownload(toast, job) { downloads.remove(spec.url) }
    }

    fun downloadSubtitle(post: Post, track: SubtitleTrack) {
        download(post, track.spec, "Fetching subtitles…", record = false)
    }

    fun downloadPlaylist(list: Playlist, chosen: List<PlaylistEntry>, audioOnly: Boolean = false) {
        val key = "playlist:" + list.link
        if (chosen.isEmpty() || downloads[key] is DlState.Busy) return
        downloads[key] = DlState.Busy(null)
        val toast = addToast(list.title.ifBlank { "Playlist" }, "Preparing…", cancellable = true)
        val job = viewModelScope.launch {
            val (target, note) = resolveSaveTarget(saveTo)
            var saved = 0
            var folder = ""
            var lastReason: String? = null
            var lastStatus = 0
            for ((index, entry) in chosen.withIndex()) {
                updateToast(toast) {
                    it.copy(name = entry.title.ifBlank { it.name }, status = "Reading ${index + 1} of ${chosen.size}…", progress = null)
                }
                try {
                    val entryPost = api.parse(entry.url)
                    val downloads = entryPost.items.firstOrNull { it.type == "video" }?.downloads.orEmpty()
                    val spec = if (audioOnly) {
                        downloads.firstOrNull { it.label.contains("audio", ignoreCase = true) || it.label.contains("sound", ignoreCase = true) }
                            ?: throw ApiException("No audio found for ${entry.title}.")
                    } else {
                        downloads.firstOrNull() ?: throw ApiException("No video found for ${entry.title}.")
                    }
                    folder = saveOne(entryPost, spec, toast, index + 1, chosen.size, false, target, reportFailure = false).orEmpty()
                    saved++
                } catch (e: ApiException) {
                    lastReason = e.message
                    lastStatus = e.status
                    if (e.offline) {
                        online = false
                        break
                    }
                }
                delay(300)
            }
            val skipped = chosen.size - saved
            if (saved > 0) {
                downloads[key] = DlState.Done(folder)
                val text = if (skipped == 0) {
                    savedText("Saved ${chosen.size} ${if (audioOnly) "tracks" else "videos"} to", folder, note)
                } else {
                    "Saved $saved of ${chosen.size} · $skipped failed"
                }
                finishToast(toast, ToastPhase.Ok, text)
                delay(1800)
                if (downloads[key] is DlState.Done) downloads.remove(key)
            } else {
                downloads.remove(key)
                val info = ErrorText.explain(lastReason, "Download failed", "Try again. If it keeps failing, open the playlist again.")
                error = info.copy(inline = false)
                updateToast(toast) { it.copy(debug = debugText("Playlist download", list.link, lastReason, lastStatus)) }
                finishToast(toast, ToastPhase.Bad, "Failed · " + ErrorText.toastLine(info))
                offerRetry(toast) { downloadPlaylist(list, chosen, audioOnly) }
            }
        }
        trackDownload(toast, job) { downloads.remove(key) }
    }

    /** Remembers the coroutine behind a download toast so Cancel can stop it. */
    private fun trackDownload(toast: Long, job: Job, onCancelled: () -> Unit) {
        toastJobs[toast] = job
        job.invokeOnCompletion { cause ->
            toastJobs.remove(toast)
            toastServerJobs.remove(toast)
            if (cause is CancellationException) onCancelled()
        }
    }

    private fun offerRetry(toast: Long, action: () -> Unit) {
        if (toasts.none { it.id == toast } && queue.none { it.id == toast }) return
        retryActions[toast] = action
        updateToast(toast) { it.copy(canRetry = true) }
    }

    /** Stops a running download: the app stops waiting, the server drops the job, and a half-written file is removed. */
    fun cancelDownload(id: Long) {
        val serverJob = toastServerJobs.remove(id)
        toastJobs[id]?.cancel()
        if (serverJob != null) {
            prefs.removePendingJob(serverJob)
            viewModelScope.launch { runCatching { api.cancelJob(serverJob) } }
        }
        // Say it was cancelled for a moment, so it is clear the press worked, then clear the toast.
        retryActions.remove(id)
        updateToast(id) { it.copy(status = "Cancelled", progress = null, phase = ToastPhase.Cancelled, canCancel = false, canRetry = false) }
        viewModelScope.launch {
            delay(1800)
            dismissToast(id)
        }
    }

    fun retryDownload(id: Long) {
        val action = retryActions.remove(id) ?: return
        queue.removeAll { it.id == id }
        dismissToast(id)
        action()
    }

    fun downloadAll(post: Post, items: List<MediaItem>) {
        downloadSpecs(post, items.mapNotNull { it.downloads.firstOrNull() })
    }

    private fun downloadSpecs(post: Post, specs: List<DownloadSpec>) {
        val key = "all:" + post.link
        if (specs.isEmpty() || downloads[key] is DlState.Busy) return
        downloads[key] = DlState.Busy(null)
        val toast = addToast(if (specs.size == 1) "1 file" else "${specs.size} files", "Preparing…", cancellable = true)
        val job = viewModelScope.launch {
            var folder: String? = null
            var saved = 0
            val (target, note) = resolveSaveTarget(saveTo)
            for ((index, spec) in specs.withIndex()) {
                folder = saveOne(post, spec, toast, index + 1, specs.size, false, target)
                if (folder == null) break
                saved++
                delay(300)
            }
            if (folder != null) {
                downloads[key] = DlState.Done(folder)
                finishToast(toast, ToastPhase.Ok, savedText("Saved ${specs.size} files to", folder, note))
                delay(1800)
                if (downloads[key] is DlState.Done) downloads.remove(key)
            } else {
                downloads.remove(key)
                // Files already saved stay saved; Retry only does the ones that are left.
                offerRetry(toast) { downloadSpecs(post, specs.drop(saved)) }
            }
        }
        trackDownload(toast, job) { downloads.remove(key) }
    }

    private suspend fun saveOne(
        post: Post,
        spec: DownloadSpec,
        toast: Long,
        position: Int,
        total: Int,
        track: Boolean,
        target: String,
        record: Boolean = true,
        reportFailure: Boolean = true,
    ): String? {
        var jobId: String? = null
        return try {
            val saved = api.download(
                spec,
                target,
                onJob = { id ->
                    jobId = id
                    toastServerJobs[toast] = id
                    liveJobs.add(id)
                    // Remembered until the file is saved, so the app can finish it after a restart.
                    prefs.addPendingJob(
                        PendingJob(
                            id, spec.name, target, post.link, post.title, post.author, post.kind,
                            post.thumb.orEmpty(), System.currentTimeMillis(),
                        ),
                    )
                },
                onFileName = { name -> if (total == 1) updateToast(toast) { it.copy(name = name) } },
                onProgress = { progress ->
                    val overall = mergedFraction(progress)?.let { (position - 1 + it) / total }
                    updateToast(toast) {
                        it.copy(progress = overall, status = progressText(position, total, progress))
                    }
                },
            )
            jobId?.let { prefs.removePendingJob(it) }
            val folder = saved.label
            if (track) downloads[spec.url] = DlState.Done(folder)
            rememberFile(post.link, saved)
            if (record) recordHistory(post, saved)
            folder
        } catch (e: ApiException) {
            jobId?.let { prefs.removePendingJob(it) }
            if (track) downloads.remove(spec.url)
            if (!reportFailure) throw e
            val info = if (e.offline) {
                online = false
                ErrorText.offline(prefs.builtIn)
            } else {
                ErrorText.explain(e.message, "Download failed", "Try again. If it keeps failing, paste the link again.")
            }
            error = info.copy(inline = false)
            updateToast(toast) { it.copy(debug = debugText("Download", post.link, e.message, e.status)) }
            finishToast(toast, ToastPhase.Bad, "Failed · " + ErrorText.toastLine(info))
            null
        } finally {
            jobId?.let { liveJobs.remove(it) }
        }
    }

    /**
     * Finishes downloads the server was still working on, or had finished, when the app last closed
     * (an Android kill, or the app swiped away and reopened with a server that kept running).
     */
    private fun recoverJobs() {
        for (job in prefs.pendingJobs()) {
            if (!liveJobs.add(job.id)) continue
            val toast = addToast(job.name.ifBlank { job.title.ifBlank { "Download" } }, "Resuming…", cancellable = true)
            toastServerJobs[toast] = job.id
            val (target, note) = resolveSaveTarget(job.saveTo)
            val resumed = viewModelScope.launch {
                try {
                    val saved = api.resumeJob(
                        job.id, job.name, target,
                        onFileName = { name -> updateToast(toast) { it.copy(name = name) } },
                        onProgress = { progress ->
                            updateToast(toast) {
                                it.copy(progress = mergedFraction(progress), status = progressText(1, 1, progress))
                            }
                        },
                    )
                    prefs.removePendingJob(job.id)
                    val post = Post(
                        id = "", platform = "", link = job.link, title = job.title, author = job.author,
                        avatar = null, kind = job.kind, thumb = job.thumb.ifBlank { null }, items = emptyList(),
                    )
                    rememberFile(job.link, saved)
                    recordHistory(post, saved)
                    finishToast(toast, ToastPhase.Ok, savedText("Saved to", saved.label, note))
                } catch (e: ApiException) {
                    prefs.removePendingJob(job.id)
                    val info = if (e.offline) {
                        online = false
                        ErrorText.offline(prefs.builtIn)
                    } else {
                        ErrorText.explain(e.message, "Download failed", "Start it again from the link.")
                    }
                    finishToast(toast, ToastPhase.Bad, "Failed · " + ErrorText.toastLine(info))
                } finally {
                    liveJobs.remove(job.id)
                }
            }
            trackDownload(toast, resumed) {}
        }
    }

    // --- Sign-in cookies ---------------------------------------------------------------------------

    fun loadCookies() {
        viewModelScope.launch { runCatching { api.cookies() }.onSuccess { cookies = it } }
    }

    private fun readCookieFile(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        try {
            val input = resolver.openInputStream(uri) ?: throw ApiException("Couldn’t open that file.")
            return input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    if (out.size() > COOKIE_MAX_BYTES) throw ApiException("That file is too big to be a cookies.txt.")
                }
                out.toString("UTF-8")
            }
        } catch (e: IOException) {
            throw ApiException("Couldn’t read that file.")
        }
    }

    private fun shortReason(e: ApiException): String =
        if (e.offline) "Server offline" else e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "Something went wrong" }

    /** The file picker returned a cookies.txt: send it to the server, which keeps only what yt-dlp needs. */
    fun importCookies(uri: Uri) {
        if (cookiesBusy) return
        cookiesBusy = true
        val toast = addToast("Cookies", "Importing…")
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readCookieFile(uri) }
                val info = api.importCookies(text)
                cookies = info
                savedPulse++
                finishToast(toast, ToastPhase.Ok, "Imported ${info.count} cookies")
            } catch (e: ApiException) {
                finishToast(toast, ToastPhase.Bad, "Failed · " + shortReason(e))
            } finally {
                cookiesBusy = false
            }
        }
    }

    fun removeCookies() {
        if (cookiesBusy) return
        cookiesBusy = true
        val toast = addToast("Cookies", "Removing…")
        viewModelScope.launch {
            try {
                cookies = api.removeCookies()
                savedPulse++
                finishToast(toast, ToastPhase.Ok, "Cookies removed")
            } catch (e: ApiException) {
                finishToast(toast, ToastPhase.Bad, "Failed · " + shortReason(e))
            } finally {
                cookiesBusy = false
            }
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

    private fun rememberFile(link: String, saved: SavedFile) {
        val uri = saved.uri ?: return
        prefs.addSavedFile(link, SavedRef(uri, saved.mime, saved.name))
    }

    /** The files saved from [link] that are still on the phone (deleted ones, or a pulled SD card, are left out). */
    suspend fun existingFiles(link: String): List<SavedRef> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        prefs.savedFiles(link).filter { Storage.exists(app, Uri.parse(it.uri)) }
    }

    fun openFromHistory(entry: HistoryEntry) {
        tab = Tab.Home
        input = entry.link
        parse(entry.link)
    }

    fun deleteHistory(entry: HistoryEntry) {
        prefs.removeSavedFiles(entry.link)
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
