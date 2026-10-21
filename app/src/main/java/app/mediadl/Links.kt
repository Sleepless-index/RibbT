package app.mediadl

import java.net.URI

object Links {
    private val absolute = Regex("""https?://[^\s<>"'）)】\]]+""", RegexOption.IGNORE_CASE)
    private val trailing = Regex("""[.,;:!?，。；：！？、"'）)】\]]+$""")

    fun extract(text: String, hosts: List<String>): String {
        absolute.find(text)?.let { return it.value.replace(trailing, "") }
        if (hosts.isEmpty()) return ""
        val alternation = hosts.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val bare = Regex(
            "(?<![\\w.-])(?:[\\w-]+\\.)*(?:" + alternation + ")(?![\\w-]|\\.[\\w-])/[^\\s<>\"'）)】\\]]*",
            RegexOption.IGNORE_CASE,
        )
        return bare.find(text)?.value?.replace(trailing, "") ?: ""
    }

    fun hostOf(link: String): String = try {
        val absoluteLink = if (link.startsWith("http", ignoreCase = true)) link else "https://$link"
        (URI(absoluteLink).host ?: "").lowercase().removePrefix("www.")
    } catch (e: Exception) {
        ""
    }

    fun supported(host: String, hosts: List<String>): Boolean =
        host.isNotEmpty() && hosts.any { host == it || host.endsWith(".$it") }
}
