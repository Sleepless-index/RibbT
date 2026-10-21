package app.mediadl

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Updates RibbT without leaving the app: the APK is downloaded here (with progress), then handed to
 * Android's package installer, which only shows its own "Update" confirmation on top of RibbT.
 */
object AppInstaller {
    private const val ACTION = "app.mediadl.INSTALL_RESULT"
    private const val DIR = "update"
    private const val FILE = "RibbT.apk"

    /** Told how the install ended. Called on the main thread; only failures are reported (success restarts the app). */
    @Volatile
    var onResult: ((ok: Boolean, message: String) -> Unit)? = null

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for RibbT; Android asks for this once. */
    fun openInstallSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Removes an APK left over from an earlier update. */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }

    /** Downloads the APK into the app's cache. Cancelling the coroutine stops it and removes the partial file. */
    suspend fun download(context: Context, url: String, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val target = File(dir, FILE)
            target.delete()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "RibbT")
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                if (code !in 200..299) throw ApiException("GitHub answered $code for the update file.")
                val total = connection.contentLengthLong
                var done = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
                if (total > 0 && done != total) throw ApiException("The update download was cut short. Try again.")
                if (done < 1_000_000) throw ApiException("The update file looks wrong. Try again later.")
                target
            } catch (e: IOException) {
                target.delete()
                throw ApiException("Couldn’t download the update. Check your connection.")
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }

    /** Hands the APK to the system installer. The result arrives in [InstallResultReceiver]. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        try {
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(FILE, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION)
                val pending = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: IOException) {
            throw ApiException("Couldn’t start the install: ${e.message.orEmpty().take(80)}")
        } catch (e: SecurityException) {
            throw ApiException("Android won’t let RibbT install updates. Allow it in Settings, then try again.")
        }
    }

    internal fun report(ok: Boolean, message: String) {
        val callback = onResult ?: return
        Handler(Looper.getMainLooper()).post { callback(ok, message) }
    }
}

/** Receives the installer's answers: asks Android to show its confirmation, or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppInstaller.report(true, "")
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppInstaller.report(false, "Update cancelled")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                AppInstaller.report(false, "This update can’t replace the installed RibbT (different signature).")
            else -> AppInstaller.report(
                false,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(100) ?: "The install failed",
            )
        }
    }
}
