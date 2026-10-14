package app.mediadl

/** Turns raw server text into a short title plus one concrete next step. */
object ErrorText {
    private const val CONVERT_TITLE = "Couldn’t convert the file"
    private val LINKER_PATH = Regex("CANNOT LINK EXECUTABLE \"[^\"]*\": ?")
    private class Rule(val pattern: Regex, val title: String, val hint: String?)

    private fun rule(pattern: String, title: String, hint: String?) =
        Rule(Regex(pattern, RegexOption.IGNORE_CASE), title, hint)

    private val rules = listOf(
        rule("not enough (free )?(storage|space)", "Not enough storage", "Free up some space on your phone, then try again."),
        rule("yt-dlp", "yt-dlp is missing", "Use Settings → Server → Update (or pip install -U yt-dlp in Termux)"),
        rule("ffmpeg is not installed|could not start ffmpeg", "ffmpeg is missing", "In Termux run: pkg install ffmpeg"),
        rule(
            "sign in|log in|login|\\bbot\\b|cookies|confirm you", "The site wants a sign-in",
            "Import your cookies.txt in Settings → Server → Sign-in cookies, then try again. On your own server, set YTDLP_COOKIES instead.",
        ),
        rule(
            "private|removed|deleted|not available|unavailable|blocked the request|age.restricted", "Can’t access that post",
            "It may be private, deleted or age-restricted. Open the link in your browser to check.",
        ),
        rule("no downloadable|no media|nothing to download", "No photo or video found", "That post doesn’t seem to contain downloadable media."),
        rule(
            "doesn.t look like|not a .*link|isn.t a", "That isn’t a post link",
            "Open the post itself and copy its link. Profile and home page links don’t work.",
        ),
        rule("timed out|timeout", "The site took too long", "It was slow to respond. Try again in a moment."),
        rule(
            "resolver|could not reach|unreadable|unexpected response|redirect", "The site didn’t answer properly",
            "Try again in a moment. If it keeps happening, the site may have changed.",
        ),
        rule("server responded \\d{3}", "The server hit a problem", "Try again. If it keeps happening, restart the server in Termux."),
        rule("busy", "Server is busy", "Wait a few seconds and try again."),
        rule("transcode|convert|encode", "Couldn’t convert the file", "Set the format or bitrate to Auto in Settings and try again."),
        rule("blocked or unreachable media|expired|forbidden|\\b(?:403|410)\\b", "That download link expired", "Paste the post link again to get a fresh one."),
    )

    fun explain(message: String?, fallbackTitle: String, fallbackHint: String?): ErrorInfo {
        val text = message.orEmpty()
        // A failed ffmpeg run carries ffmpeg's own explanation below the first line. Show it,
        // because "ffmpeg exited 1" alone says nothing about what went wrong.
        if (text.contains("(ffmpeg exited") || text.startsWith("Transcode failed")) {
            // Android's linker names the whole install path before saying what is missing, which
            // pushes the useful part (the library name) off a one-line toast, so drop the path.
            val detail = text.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.replace(LINKER_PATH, "ffmpeg can’t start: ") }
                .toList().takeLast(3).joinToString("\n").take(300)
            return ErrorInfo(CONVERT_TITLE, detail.ifBlank { fallbackHint })
        }
        for (rule in rules) {
            if (rule.pattern.containsMatchIn(text)) return ErrorInfo(rule.title, rule.hint)
        }
        val first = text.lineSequence().firstOrNull().orEmpty().trim()
        return if (first.isNotEmpty() && first.length <= 90) {
            ErrorInfo(first, fallbackHint)
        } else {
            ErrorInfo(fallbackTitle, fallbackHint ?: first.take(200).ifBlank { null })
        }
    }

    /** One line for the download toast. For a failed conversion that is ffmpeg's own last line. */
    fun toastLine(info: ErrorInfo): String {
        val detail = if (info.title == CONVERT_TITLE) info.hint?.lineSequence()?.lastOrNull().orEmpty().trim() else ""
        return (detail.ifEmpty { info.title }).take(110)
    }

    fun offline(builtIn: Boolean) = if (builtIn) {
        ErrorInfo("Built-in server isn’t running", "Open Settings → Server and switch to Custom address, or restart the app.")
    } else {
        ErrorInfo("Server offline", "Start the server on that device, then tap Retry.")
    }
}
