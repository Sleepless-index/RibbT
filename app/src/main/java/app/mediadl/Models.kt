package app.mediadl

import org.json.JSONArray
import org.json.JSONObject

data class DownloadSpec(val label: String, val url: String, val name: String)

/** One resolution a video can be saved in. [size] is in bytes, 0 when the site doesn't say. */
data class QualityOption(val height: Int, val label: String, val size: Long, val spec: DownloadSpec)

data class MediaItem(
    val type: String,
    val label: String,
    val preview: String?,
    val downloads: List<DownloadSpec>,
    /** Pixel height of the stream the server picked, 0 when unknown. */
    val height: Int = 0,
    val size: Long = 0L,
    val duration: String? = null,
    val variants: List<QualityOption> = emptyList(),
    val play: String? = null,
    val playAlt: String? = null,
    /** Separate audio track to play together with [play] (YouTube serves video and sound apart). */
    val playAudio: String? = null,
)

data class SubtitleTrack(val lang: String, val label: String, val auto: Boolean, val spec: DownloadSpec)

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
    val subtitles: List<SubtitleTrack> = emptyList(),
    val playlist: String? = null,
)

data class PlaylistEntry(
    val id: String,
    val title: String,
    val author: String,
    val duration: String,
    val url: String,
    val thumb: String?,
)

data class Playlist(
    val link: String,
    val platform: String,
    val title: String,
    val author: String,
    val total: Int,
    val entries: List<PlaylistEntry>,
)

sealed interface ParseResult {
    data class Single(val post: Post) : ParseResult
    data class Listing(val playlist: Playlist) : ParseResult
}

data class AppRelease(val version: String?, val available: Boolean, val apkUrl: String, val pageUrl: String)

data class Platform(
    val name: String,
    val label: String,
    val hosts: List<String>,
    val display: List<String>,
    /** True when the service can offer subtitle files. */
    val subtitles: Boolean = false,
    /** What a link can give you: "Video", "Photos", "Audio", "Subtitles", "Playlists"… */
    val features: List<String> = emptyList(),
    /** Anything worth knowing before pasting a link (a sign-in is needed, tracks only…). */
    val note: String = "",
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
data class DlProgress(val phase: String, val fraction: Float?, val bytes: Long, val total: Long = 0L, val detail: String = "")

/** A background download on the server. [state] is queued, running, ready, error or cancelled. */
data class JobInfo(
    val state: String,
    val phase: String,
    /** What a conversion is doing, in the server's words (empty otherwise). */
    val detail: String = "",
    val fraction: Float?,
    val bytes: Long,
    /** Bytes the server expects to fetch in all; 0 while that is not known. */
    val total: Long,
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

enum class ToastPhase { Active, Ok, Bad, Cancelled }

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
    val debug: String? = null,
    val parent: Long? = null,
    /** Preview image and service name, for the thumbnail and logo badge in the download list. */
    val thumb: String? = null,
    val platform: String? = null,
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

enum class Tab(val label: String) { Home("Home"), History("History"), Settings("Settings") }

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

fun JSONObject.toSubtitleTrack(): SubtitleTrack = SubtitleTrack(
    lang = optString("lang"),
    label = optString("label"),
    auto = optBoolean("auto", false),
    spec = DownloadSpec(optString("label"), optString("url"), optString("name")),
)

fun JSONObject.toPlaylist(): Playlist {
    val entries = (optJSONArray("entries") ?: JSONArray()).mapObjects {
        PlaylistEntry(
            id = it.optString("id"),
            title = it.optString("title"),
            author = it.optString("author"),
            duration = it.optString("duration"),
            url = it.optString("url"),
            thumb = it.optStringOrNull("thumb"),
        )
    }
    return Playlist(
        link = optString("link"),
        platform = optString("platform"),
        title = optString("title"),
        author = optString("author"),
        total = optInt("total", entries.size),
        entries = entries.filter { it.url.isNotBlank() },
    )
}

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
            height = item.optInt("height", 0),
            size = item.optLong("size", 0L),
            duration = item.optStringOrNull("duration"),
            variants = (item.optJSONArray("variants") ?: JSONArray()).mapObjects { v ->
                QualityOption(
                    height = v.optInt("height", 0),
                    label = v.optString("label"),
                    size = v.optLong("size", 0L),
                    spec = DownloadSpec("Download", v.optString("url"), v.optString("name")),
                )
            }.filter { it.height > 0 && it.spec.url.isNotBlank() },
            downloads = (item.optJSONArray("downloads") ?: JSONArray()).mapObjects {
                DownloadSpec(it.optString("label"), it.optString("url"), it.optString("name"))
            },
        )
    },
    subtitles = (optJSONArray("subtitles") ?: JSONArray()).mapObjects { it.toSubtitleTrack() }
        .filter { it.lang.isNotBlank() && it.spec.url.isNotBlank() },
    playlist = optStringOrNull("playlist"),
)

fun JSONObject.toJobInfo(): JobInfo = JobInfo(
    state = optString("state"),
    phase = optString("phase"),
    detail = optString("detail"),
    fraction = if (isNull("progress")) null else optDouble("progress").toFloat(),
    bytes = optLong("bytes", 0L),
    total = if (isNull("total")) 0L else optLong("total", 0L),
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
    subtitles = optBoolean("subtitles", false),
    features = optJSONArray("features").strings(),
    note = optString("note"),
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
