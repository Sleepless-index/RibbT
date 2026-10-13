package app.mediadl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.yausername.youtubedl_common.utils.ZipUtils
import java.io.File
import kotlin.concurrent.thread

/** State of the server that runs inside the app (the same Python package that runs on a PC or in Termux). */
object BuiltInServer {
    const val PORT = 8765
    const val URL = "http://127.0.0.1:$PORT"

    @Volatile var ready = false
        internal set

    /** Set when starting failed, so Settings can say why instead of just "offline". */
    @Volatile var failure: String? = null
        internal set

    fun start(context: Context) {
        val intent = Intent(context, ServerService::class.java)
        runCatching { context.startForegroundService(intent) }
            .onFailure { failure = it.message ?: "Couldn’t start the built-in server." }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ServerService::class.java))
        ready = false
    }
}

/** Paths of the ffmpeg build that ships inside the APK. */
class BundledTools(val ffmpeg: String, val ffprobe: String, val libs: String, val readyMarker: String)

object BundledToolsSetup {
    /**
     * ffmpeg and ffprobe are real executables packaged as libffmpeg.so / libffprobe.so, so they sit
     * in the native library directory. The shared libraries they need ship in one zip that is
     * unpacked once (again after an app update that changes it).
     */
    fun locate(context: Context): BundledTools {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val root = File(context.noBackupFilesDir, "ribbt-ffmpeg")
        return BundledTools(
            ffmpeg = File(nativeDir, "libffmpeg.so").absolutePath,
            ffprobe = File(nativeDir, "libffprobe.so").absolutePath,
            libs = File(root, "usr/lib").absolutePath,
            readyMarker = File(root, "ready").absolutePath,
        )
    }

    fun unpackIfNeeded(context: Context) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val zip = File(nativeDir, "libffmpeg.zip.so")
        val tools = locate(context)
        val marker = File(tools.readyMarker)
        val root = marker.parentFile ?: return
        val version = zip.length().toString()
        if (marker.isFile && marker.readText() == version && File(tools.libs).isDirectory) return
        root.deleteRecursively()
        root.mkdirs()
        ZipUtils.unzip(zip, root)
        marker.writeText(version)
    }
}

/**
 * Keeps the built-in server alive. It is a foreground service so Android doesn't stop the server
 * (and a download in progress) when the app is in the background.
 */
class ServerService : Service() {
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(Notifier.SERVER_CHANNEL, "Built-in server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps RibbT’s downloader running while you use it."
                    setShowBadge(false)
                },
            )
        }
        val open = Notifier.openApp(this)
        val notification: Notification = NotificationCompat.Builder(this, Notifier.SERVER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ribbt)
            .setLargeIcon(Notifier.largeIcon(this))
            .setColor(0xFFFF2442.toInt())
            .setContentTitle("RibbT is ready")
            .setContentText("The built-in downloader is running.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!starting) {
            starting = true
            BuiltInServer.failure = null
            thread(name = "ribbt-start") { boot() }
        }
        return START_STICKY
    }

    private fun boot() {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val tools = BundledToolsSetup.locate(this)
            val data = File(filesDir, "server").absolutePath
            // ffmpeg's libraries are unpacked on the first run; the server answers meanwhile and
            // asks for a retry if a conversion is requested before they are ready.
            Python.getInstance().getModule("ribbt_server")
                .callAttr("start", data, tools.ffmpeg, tools.ffprobe, tools.libs, tools.readyMarker, BuiltInServer.PORT)
            BuiltInServer.ready = true
            BundledToolsSetup.unpackIfNeeded(this)
        } catch (error: Throwable) {
            BuiltInServer.failure = error.message ?: error.javaClass.simpleName
            starting = false
        }
    }

    // Swiping the app away should stop the server too, instead of leaving a notification behind.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { Python.getInstance().getModule("ribbt_server").callAttr("stop") }
        BuiltInServer.ready = false
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}
