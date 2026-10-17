package app.mediadl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AppUpdate {
    private const val REPO = "Sleepless-index/RibbT"
    private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private const val FALLBACK_APK = "https://github.com/$REPO/releases/download/latest/RibbT.apk"
    private const val FALLBACK_PAGE = "https://github.com/$REPO/releases/tag/latest"
    private val versionPattern = Regex("""(\d+)\.(\d+)\.(\d+)""")

    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun versionParts(text: String?): List<Int>? {
        val match = text?.let { versionPattern.find(it) } ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
    }

    fun isNewer(latest: String?, current: String): Boolean {
        val a = versionParts(latest) ?: return false
        val b = versionParts(current) ?: return false
        for (index in 0 until 3) {
            if (a[index] != b[index]) return a[index] > b[index]
        }
        return false
    }

    fun latest(current: String): AppRelease {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RibbT")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 403 || code == 429) throw ApiException("GitHub is limiting checks right now. Try again later.")
            if (code == 404) throw ApiException("No release has been published yet.")
            if (code !in 200..299) throw ApiException("GitHub answered $code.")
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException("GitHub sent something unexpected.")
            }
            val name = json.optString("name")
            val version = versionParts(name)?.joinToString(".") ?: versionParts(json.optString("body"))?.joinToString(".")
            var apk = FALLBACK_APK
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = asset.optString("browser_download_url").ifBlank { FALLBACK_APK }
                        break
                    }
                }
            }
            return AppRelease(
                version = version,
                available = isNewer(version, current),
                apkUrl = apk,
                pageUrl = json.optString("html_url").ifBlank { FALLBACK_PAGE },
            )
        } catch (e: IOException) {
            throw ApiException("Couldn’t reach GitHub. Check your connection.")
        } finally {
            connection.disconnect()
        }
    }

    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone can open that link.", Toast.LENGTH_SHORT).show()
        }
    }
}
