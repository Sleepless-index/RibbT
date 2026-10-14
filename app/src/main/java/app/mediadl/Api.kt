package app.mediadl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class ApiException(message: String, val offline: Boolean = false, val status: Int = 0) : Exception(message)

/** A finished download: where it went, how big it is, and which kind (video, image or audio). */
class SavedFile(val label: String, val bytes: Long, val kind: Int)

const val KIND_VIDEO = 0
const val KIND_IMAGE = 1
const val KIND_AUDIO = 2

private const val MAX_RESUMES = 5
private const val MAX_POLL_FAILURES = 30
private const val DEVICE_RESERVE_BYTES = 100L * 1024 * 1024

class Api(private val context: Context, private val prefs: Prefs) {

    private fun connection(path: String, method: String = "GET", readTimeoutMs: Int = 20_000): HttpURLConnection {
        val target = if (path.startsWith("http")) path else prefs.serverUrl + path
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3_000
        connection.readTimeout = readTimeoutMs
        val settings = prefs.settingsJson
        if (!settings.isNullOrBlank()) {

            connection.setRequestProperty(
                "Cookie",
                "mdl_settings=" + URLEncoder.encode(settings, "UTF-8").replace("+", "%20"),
            )
        }
        return connection
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: ConnectException) {
        throw ApiException("Server offline", offline = true)
    } catch (e: UnknownHostException) {
        throw ApiException("Server offline", offline = true)
    } catch (e: SocketTimeoutException) {
        throw ApiException("The site timed out while responding.")
    } catch (e: IOException) {
        throw ApiException(e.message ?: "Network error", offline = true)
    }

    private fun readText(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun requestJson(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        readTimeoutMs: Int = 20_000,
        text: String? = null,
    ): JSONObject {
        val connection = connection(path, method, readTimeoutMs)
        try {
            if (body != null || text != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (text != null) "text/plain; charset=utf-8" else "application/json")
                connection.outputStream.use { it.write((text ?: body.toString()).toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = readText(connection)
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (code !in 200..299) {
                val message = json.optString("error").ifBlank { text.take(200).ifBlank { "Server responded $code" } }
                throw ApiException(message, status = code)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = connection("/api/platforms", readTimeoutMs = 3_000)
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            false
        }
    }

    suspend fun platforms(): List<Platform> = withContext(Dispatchers.IO) {
        guard { requestJson("/api/platforms").optJSONArray("platforms")?.mapObjects { it.toPlatform() } ?: emptyList() }
    }

    suspend fun serverSettings(): ServerSettings = withContext(Dispatchers.IO) {
        guard {
            val json = requestJson("/api/settings")
            val choices = HashMap<String, List<Choice>>()
            val raw = json.optJSONObject("choices") ?: JSONObject()
            for (path in raw.keys()) {
                choices[path] = (raw.optJSONArray(path) ?: JSONArray()).mapObjects {
                    Choice(it.optString("value"), it.optString("label"))
                }
            }
            val schema = (json.optJSONArray("schema") ?: JSONArray()).mapObjects {
                SettingRow(
                    section = it.optString("section"),
                    path = it.optString("path"),
                    label = it.optString("label"),
                    hint = it.optString("hint"),
                    type = it.optString("type"),
                )
            }
            ServerSettings(json.optJSONObject("settings") ?: JSONObject(), choices, schema)
        }
    }

    suspend fun parse(link: String): Post = withContext(Dispatchers.IO) {
        guard { requestJson("/api/app/parse", "POST", JSONObject().put("url", link), readTimeoutMs = 75_000).toPost() }
    }

    suspend fun history(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        guard { requestJson("/api/history").optJSONArray("items")?.mapObjects { it.toHistoryEntry() } ?: emptyList() }
    }

    suspend fun addHistory(post: Post, savedTo: String, files: Int, sizes: LongArray) = withContext(Dispatchers.IO) {
        guard {
            requestJson(
                "/api/history", "POST",
                JSONObject()
                    .put("link", post.link)
                    .put("title", post.title)
                    .put("author", post.author)
                    .put("kind", post.kind)
                    .put("thumb", post.thumb ?: "")
                    .put("saved_to", savedTo)
                    .put("files", files)
                    .put(
                        "sizes",
                        JSONObject().put("video", sizes[0]).put("image", sizes[1]).put("audio", sizes[2]),
                    ),
            )
        }
        Unit
    }

    suspend fun removeHistory(id: String) = withContext(Dispatchers.IO) {
        guard { requestJson("/api/history/$id", "DELETE") }
        Unit
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        guard { requestJson("/api/history", "DELETE") }
        Unit
    }

    suspend fun ytdlpVersion(): String? = withContext(Dispatchers.IO) {
        guard {
            val json = requestJson("/api/ytdlp")
            if (json.isNull("version")) null else json.optString("version").ifBlank { null }
        }
    }

    suspend fun ytdlpLatest(channel: String): YtdlpLatest = withContext(Dispatchers.IO) {
        guard {
            val json = requestJson("/api/ytdlp/latest?channel=" + URLEncoder.encode(channel, "UTF-8"), readTimeoutMs = 30_000)
            YtdlpLatest(json.optStringOrNull("latest"), json.optBoolean("available", false))
        }
    }

    suspend fun ytdlpUpdate(channel: String): YtdlpResult = withContext(Dispatchers.IO) {
        guard {
            val json = requestJson(
                "/api/ytdlp/update", "POST", JSONObject().put("channel", channel), readTimeoutMs = 330_000,
            )
            YtdlpResult(
                before = json.optStringOrNull("before"),
                after = json.optStringOrNull("after"),
                updated = json.optBoolean("updated", false),
            )
        }
    }

    /**
     * Downloads [spec] in two steps: the server works on it as a background job (fetching, joining,
     * converting) while this polls for progress, then the finished file is copied into [saveTo].
     * [onJob] is called as soon as the job exists, so the caller can remember it.
     */
    suspend fun download(
        spec: DownloadSpec,
        saveTo: String,
        onJob: (String) -> Unit,
        onFileName: (String) -> Unit,
        onProgress: (DlProgress) -> Unit,
    ): SavedFile = withContext(Dispatchers.IO) {
        val id = guard { startJob(spec) }
        onJob(id)
        collect(id, spec.name, saveTo, onFileName, onProgress)
    }

    /** Picks up a job that was started earlier (before the app was closed or killed). */
    suspend fun resumeJob(
        id: String,
        name: String,
        saveTo: String,
        onFileName: (String) -> Unit,
        onProgress: (DlProgress) -> Unit,
    ): SavedFile = withContext(Dispatchers.IO) { collect(id, name, saveTo, onFileName, onProgress) }

    private suspend fun collect(
        id: String,
        fallbackName: String,
        saveTo: String,
        onFileName: (String) -> Unit,
        onProgress: (DlProgress) -> Unit,
    ): SavedFile {
        val coroutine = currentCoroutineContext()
        try {
            val ready = awaitJob(id, onProgress)
            val saved = guard {
                transferJobFile(id, ready, saveTo, fallbackName, onFileName, onProgress) { coroutine.ensureActive() }
            }
            deleteJobQuietly(id)
            return saved
        } catch (e: CancellationException) {
            // The app was closed mid-download. The job stays on the server and the app finishes it next time.
            throw e
        } catch (e: Exception) {
            deleteJobQuietly(id)
            throw e
        }
    }

    private fun startJob(spec: DownloadSpec): String {
        val json = requestJson("/api/jobs", "POST", JSONObject().put("path", spec.url).put("name", spec.name))
        return json.optString("id").ifBlank { throw ApiException("The server didn’t start the download.") }
    }

    private fun jobInfo(id: String): JobInfo = guard { requestJson("/api/jobs/$id").toJobInfo() }

    private fun deleteJobQuietly(id: String) {
        runCatching {
            val connection = connection("/api/jobs/$id", "DELETE", readTimeoutMs = 5_000)
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Polls until the server has the file ready, passing progress along. Throws if the job failed. */
    private suspend fun awaitJob(id: String, onProgress: (DlProgress) -> Unit): JobInfo {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val info = try {
                jobInfo(id).also { failures = 0 }
            } catch (e: ApiException) {
                // A brief drop (the phone dozing, a PC server restarting) shouldn't lose a download
                // the server is still working on.
                if (!e.offline || ++failures > MAX_POLL_FAILURES) throw e
                delay(1_000)
                continue
            }
            when (info.state) {
                "ready" -> return info
                "error" -> throw ApiException(info.error ?: "The download failed on the server.", status = info.status)
                "cancelled" -> throw ApiException("The download was cancelled.")
                else -> {
                    val phase = when {
                        info.state == "queued" -> "waiting"
                        info.phase == "converting" -> "converting"
                        else -> "downloading"
                    }
                    onProgress(DlProgress(phase, info.fraction, info.bytes))
                }
            }
            delay(500)
        }
    }

    /** Opens the finished file at [offset]; null when there is nothing left to read. */
    private fun openJobFile(id: String, offset: Long): HttpURLConnection? {
        val connection = connection("/api/jobs/$id/file", readTimeoutMs = 60_000)
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val code = connection.responseCode
            if (code == 416) {
                connection.disconnect()
                return null
            }
            if (code !in 200..299) {
                val message = readText(connection).trim().ifBlank { "Server responded $code" }.take(1200)
                throw ApiException(message, status = code)
            }
            if (code == 200 && offset > 0) {
                // The server ignored the Range header and is sending everything again: skip what we already have.
                val input = connection.inputStream
                var left = offset
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped > 0) {
                        left -= skipped
                    } else if (input.read() == -1) {
                        break
                    } else {
                        left--
                    }
                }
            }
            return connection
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    /** Free space on the phone's own storage; folders on an SD card are checked by the system when writing. */
    private fun requireDeviceSpace(saveTo: String, bytes: Long) {
        if (bytes <= 0 || saveTo == "sdcard" || saveTo == "folder") return
        val free = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull() ?: return
        if (free < bytes + DEVICE_RESERVE_BYTES) {
            throw ApiException(
                "Not enough free storage on your phone to save this. It needs about ${formatBytes(bytes)}, " +
                    "but only ${formatBytes(free)} is free. Free up some space and try again.",
                status = 507,
            )
        }
    }

    /**
     * Copies the finished file into its destination. If the connection drops half way, the transfer
     * carries on from the byte it reached (HTTP Range) instead of starting over.
     */
    private fun transferJobFile(
        id: String,
        ready: JobInfo,
        saveTo: String,
        fallbackName: String,
        onFileName: (String) -> Unit,
        onProgress: (DlProgress) -> Unit,
        checkActive: () -> Unit,
    ): SavedFile {
        val first = openJobFile(id, 0L) ?: throw ApiException("The server sent an empty file.")
        var connection: HttpURLConnection? = first
        try {
            val mime = (first.contentType ?: ready.mime ?: "application/octet-stream")
                .substringBefore(";").trim().lowercase()
            val fileName = fileNameFrom(first.getHeaderField("Content-Disposition"))
                ?: (fallbackName + "." + extensionFor(mime))
            onFileName(fileName)
            val total = if (ready.size > 0) ready.size else first.contentLengthLong
            requireDeviceSpace(saveTo, total)
            val sink = openSink(fileName, mime, saveTo)
            var written = 0L
            try {
                sink.out.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var retries = 0
                    var lastPercent = -1
                    while (true) {
                        try {
                            val active = connection ?: openJobFile(id, written) ?: break
                            connection = active
                            val input = active.inputStream
                            while (true) {
                                checkActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                try {
                                    out.write(buffer, 0, read)
                                } catch (e: IOException) {
                                    // Not a network problem, so don't retry: the destination can't take the file.
                                    throw ApiException("Couldn’t write the file. ${e.message.orEmpty()}".trim())
                                }
                                written += read
                                if (total > 0) {
                                    val percent = (written * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(DlProgress("saving", (written.toFloat() / total).coerceAtMost(1f), written))
                                    }
                                } else {
                                    onProgress(DlProgress("saving", null, written))
                                }
                            }
                            if (total > 0 && written < total) throw IOException("The transfer ended early.")
                            break
                        } catch (e: IOException) {
                            connection?.disconnect()
                            connection = null
                            if (++retries > MAX_RESUMES) throw e
                            Thread.sleep(1_000L * retries)
                        }
                    }
                }
                sink.commit()
            } catch (e: Exception) {
                sink.abort()
                throw e
            }
            return SavedFile(
                sink.label,
                written,
                when {
                    mime.startsWith("video/") -> KIND_VIDEO
                    mime.startsWith("audio/") -> KIND_AUDIO
                    else -> KIND_IMAGE
                },
            )
        } finally {
            connection?.disconnect()
            first.disconnect()
        }
    }

    // --- Sign-in cookies (for YouTube's age and bot checks) ---------------------------------------

    suspend fun cookies(): CookieInfo = withContext(Dispatchers.IO) {
        guard { requestJson("/api/cookies").toCookieInfo() }
    }

    suspend fun importCookies(text: String): CookieInfo = withContext(Dispatchers.IO) {
        guard { requestJson("/api/cookies", "POST", text = text, readTimeoutMs = 30_000).toCookieInfo() }
    }

    suspend fun removeCookies(): CookieInfo = withContext(Dispatchers.IO) {
        guard { requestJson("/api/cookies", "DELETE").toCookieInfo() }
    }

    private class Destination(val collection: Uri, val folder: String, val label: String)

    /** Where a finished file is written, plus how to finish or throw it away. */
    private class Sink(val out: OutputStream, val label: String, val commit: () -> Unit, val abort: () -> Unit)

    private fun sdVolume(): String? = MediaStore.getExternalVolumeNames(context).firstOrNull {
        it != MediaStore.VOLUME_EXTERNAL_PRIMARY && it != MediaStore.VOLUME_EXTERNAL && it != MediaStore.VOLUME_INTERNAL
    }

    /** Sorting folder inside RibbT's own folder, by kind of file. */
    private fun kindFolder(mime: String): String = when {
        mime.startsWith("video/") -> "Videos"
        mime.startsWith("image/") -> "Images"
        mime.startsWith("audio/") -> "Audio"
        else -> "Other"
    }

    private fun destination(mime: String, saveTo: String): Destination {
        val sub = kindFolder(mime)
        if (saveTo == "sdcard") {
            val volume = sdVolume()
                ?: throw ApiException("No SD card found. Insert one, or use “Choose folder…” in Settings.")
            return Destination(MediaStore.Downloads.getContentUri(volume), "Download/RibbT/$sub", "SD card/Download/RibbT/$sub")
        }
        if (saveTo == "gallery") {
            when {
                mime.startsWith("image/") -> return Destination(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/RibbT", "Pictures/RibbT",
                )
                mime.startsWith("video/") -> return Destination(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/RibbT", "Movies/RibbT",
                )
                mime.startsWith("audio/") -> return Destination(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/RibbT", "Music/RibbT",
                )
            }
        }
        return Destination(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/RibbT/$sub", "Downloads/RibbT/$sub")
    }

    private fun openSink(fileName: String, mime: String, saveTo: String): Sink {
        if (saveTo == "folder") return openFolderSink(fileName, mime)
        val target = destination(mime, saveTo)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = try {
            resolver.insert(target.collection, values)
        } catch (e: Exception) {
            null
        } ?: throw ApiException("Couldn’t create the file. Check that the app can use storage.")
        val output = try {
            resolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }
        if (output == null) {
            resolver.delete(uri, null, null)
            throw ApiException("Couldn’t write the file.")
        }
        return Sink(
            output,
            target.label,
            commit = {
                val finished = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, finished, null, null)
                if (mime.startsWith("video/")) {
                    // Asking for a thumbnail makes the system build and cache one now, so the file
                    // shows a picture in file managers and galleries instead of a blank icon.
                    runCatching { resolver.loadThumbnail(uri, android.util.Size(512, 512), null).recycle() }
                }
            },
            abort = { resolver.delete(uri, null, null) },
        )
    }

    /** Writes into the folder chosen with the system folder picker (an SD card or any other folder). */
    private fun openFolderSink(fileName: String, mime: String): Sink {
        val chosen = prefs.folderUri
        if (chosen.isBlank()) throw ApiException("No folder chosen. Pick one in Settings under Save to.")
        val resolver = context.contentResolver
        val tree = Uri.parse(chosen)
        val failure = "Couldn’t write to the chosen folder. Pick it again in Settings."
        val document = try {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val parent = childFolder(resolver, tree, root, kindFolder(mime)) ?: root
            DocumentsContract.createDocument(resolver, parent, mime, fileName)
        } catch (e: Exception) {
            null
        } ?: throw ApiException(failure)
        val output = try {
            resolver.openOutputStream(document)
        } catch (e: Exception) {
            null
        }
        if (output == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw ApiException(failure)
        }
        return Sink(
            output,
            prefs.folderName.ifBlank { "Chosen folder" },
            commit = {},
            abort = { runCatching { DocumentsContract.deleteDocument(resolver, document) } },
        )
    }

    /** The sub-folder called [name] inside [parent] in a picked folder; created when it isn't there yet. */
    private fun childFolder(resolver: android.content.ContentResolver, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name && cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun fileNameFrom(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return Uri.decode(it.groupValues[1].trim()).ifBlank { null }
        }
        Regex("filename=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return it.groupValues[1].trim().ifBlank { null }
        }
        return null
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/ogg", "audio/opus" -> "opus"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> if (mime.startsWith("video/")) "mp4" else if (mime.startsWith("audio/")) "mp3" else "jpg"
    }
}
