package app.mediadl

import org.json.JSONArray
import org.json.JSONObject

data class DownloadSpec(val label: String, val url: String, val name: String)

data class MediaItem(
    val type: String,
    val label: String,
    val preview: String?,
    val downloads: List<DownloadSpec>,
    val play: String? = null,
    val playAlt: String? = null,
    /** Separate audio track to play together with [play] (YouTube serves video and sound apart). */
    val playAudio: String? = null,
)

data class Post(
    val id: String,
    val platform: String,
    val link: String,
    val title: String,
    val author: String,
    val avatar: String?,
    val kind: String,
    val thumb: String?,
    val items: List<MediaItem>,
)

data class Platform(
    val name: String,
    val label: String,
    val hosts: List<String>,
    val display: List<String>,
)

data class HistoryEntry(
    val id: String,
    val link: String,
    val title: String,
    val author: String,
    val platform: String,
    val kind: String,
    val thumb: String,
    val savedTo: String,
    val files: Int,
    val time: Long,
    /** Bytes saved for this post, by kind. All zero for entries saved before sizes were recorded. */
    val videoBytes: Long = 0,
    val imageBytes: Long = 0,
    val audioBytes: Long = 0,
) {
    val totalBytes: Long get() = videoBytes + imageBytes + audioBytes

    /** Which type filters this entry belongs to. */
    fun hasType(type: String): Boolean = when (type) {
        "video" -> videoBytes > 0 || (totalBytes == 0L && kind == "video")
        "image" -> imageBytes > 0 || (totalBytes == 0L && kind != "video")
        "audio" -> audioBytes > 0
        else -> true
    }
}

data class SettingRow(
    val section: String,
    val path: String,
    val label: String,
    val hint: String,
    val type: String,
)

/** One option in a dropdown. A disabled one stays visible, greyed out, with [note] saying why. */
data class Choice(val value: String, val label: String, val enabled: Boolean = true, val note: String? = null)

data class ServerSettings(
    val settings: JSONObject,
    val choices: Map<String, List<Choice>>,
    val schema: List<SettingRow>,
)

data class YtdlpResult(val before: String?, val after: String?, val updated: Boolean)

/** What a running download is doing. [phase] is waiting, downloading (on the server), converting or saving. */
data class DlProgress(val phase: String, val fraction: Float?, val bytes: Long)

/** A background download on the server. [state] is queued, running, ready, error or cancelled. */
data class JobInfo(
    val state: String,
    val phase: String,
    val fraction: Float?,
    val bytes: Long,
    val size: Long,
    val mime: String?,
    val error: String?,
    val status: Int,
)

/** A download the server is working on that hasn't been saved yet; kept so a restart of the app can finish it. */
data class PendingJob(
    val id: String,
    val name: String,
    val saveTo: String,
    val link: String,
    val title: String,
    val author: String,
    val kind: String,
    val thumb: String,
    val started: Long,
)

/** The sign-in cookies stored on the server: a summary only, the cookies themselves never leave it. */
data class CookieInfo(val present: Boolean, val count: Int, val domains: List<String>)

/** Result of looking for a newer yt-dlp without installing it. */
data class YtdlpLatest(val latest: String?, val available: Boolean)

/** [inline] errors show in the box under the field; the others were already shown as a toast. */
data class ErrorInfo(val title: String, val hint: String?, val inline: Boolean = true)

sealed interface DlState {
    data object Idle : DlState
    data class Busy(val progress: Float?) : DlState
    data class Done(val where: String) : DlState
}

enum class ToastPhase { Active, Ok, Bad }

data class DlToast(
    val id: Long,
    val name: String,
    val status: String,
    val progress: Float?,
    val phase: ToastPhase,
    /** Shows a Cancel button while the download runs. */
    val canCancel: Boolean = false,
    /** Shows a Retry button once it has failed. */
    val canRetry: Boolean = false,
)

/** A file RibbT saved on this phone, remembered so History can open or share it later. */
class SavedRef(val uri: String, val mime: String, val name: String)

enum class MediaLayout(val key: String, val label: String) {
    Carousel("carousel", "Carousel"),
    Stacked("stacked", "Stacked"),
    Masonry("masonry", "Masonry");

    companion object {
        fun from(key: String): MediaLayout = entries.firstOrNull { it.key == key } ?: Carousel
    }
}

enum class Tab(val label: String) { Home("home"), History("history"), Settings("settings") }

inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        out.add(transform(obj))
    }
    return out
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }

fun JSONObject.toPost(): Post = Post(
    id = optString("id"),
    platform = optString("platform"),
    link = optString("link"),
    title = optString("title"),
    author = optString("author"),
    avatar = optStringOrNull("avatar"),
    kind = optString("kind"),
    thumb = optStringOrNull("thumb"),
    items = (optJSONArray("items") ?: JSONArray()).mapObjects { item ->
        MediaItem(
            type = item.optString("type"),
            label = item.optString("label"),
            preview = item.optStringOrNull("preview"),
            play = item.optStringOrNull("play"),
            playAlt = item.optStringOrNull("play_alt"),
            playAudio = item.optStringOrNull("play_audio"),
            downloads = (item.optJSONArray("downloads") ?: JSONArray()).mapObjects {
                DownloadSpec(it.optString("label"), it.optString("url"), it.optString("name"))
            },
        )
    },
)

fun JSONObject.toJobInfo(): JobInfo = JobInfo(
    state = optString("state"),
    phase = optString("phase"),
    fraction = if (isNull("progress")) null else optDouble("progress").toFloat(),
    bytes = optLong("bytes", 0L),
    size = optLong("size", 0L),
    mime = optStringOrNull("mime"),
    error = optStringOrNull("error"),
    status = optInt("status", 0),
)

fun JSONObject.toCookieInfo(): CookieInfo = CookieInfo(
    present = optBoolean("present", false),
    count = optInt("count", 0),
    domains = optJSONArray("domains").strings(),
)

fun PendingJob.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("saveTo", saveTo).put("link", link).put("title", title)
    .put("author", author).put("kind", kind).put("thumb", thumb).put("started", started)

fun JSONObject.toPendingJob(): PendingJob = PendingJob(
    id = optString("id"),
    name = optString("name"),
    saveTo = optString("saveTo", "downloads"),
    link = optString("link"),
    title = optString("title"),
    author = optString("author"),
    kind = optString("kind"),
    thumb = optString("thumb"),
    started = optLong("started", 0L),
)

fun JSONObject.toPlatform(): Platform = Platform(
    name = optString("name"),
    label = optString("label"),
    hosts = optJSONArray("hosts").strings(),
    display = optJSONArray("display").strings(),
)

fun JSONObject.toHistoryEntry(): HistoryEntry = HistoryEntry(
    id = optString("id"),
    link = optString("link"),
    title = optString("title"),
    author = optString("author"),
    platform = optString("platform"),
    kind = optString("kind"),
    thumb = optString("thumb"),
    savedTo = optString("saved_to"),
    files = optInt("files", 1),
    time = optLong("time", 0L),
    videoBytes = optJSONObject("sizes")?.optLong("video", 0L) ?: 0L,
    imageBytes = optJSONObject("sizes")?.optLong("image", 0L) ?: 0L,
    audioBytes = optJSONObject("sizes")?.optLong("audio", 0L) ?: 0L,
)
