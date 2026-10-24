package app.mediadl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/** True while RibbT's screen is showing; set by MainActivity. */
object Foreground {
    @Volatile var visible = false
}

object Notifier {
    const val SERVER_CHANNEL = "builtin-server"
    const val DOWNLOAD_CHANNEL = "downloads"

    /** The full-colour app icon, shown large inside the notification (the small status-bar icon must be flat white). */
    fun largeIcon(context: Context): Bitmap? =
        runCatching { ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(128, 128) }.getOrNull()

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(DOWNLOAD_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a download finishes or fails while RibbT is in the background."
            },
        )
    }

    /** A finished or failed download. Skipped while the app is on screen, since the in-app toast covers that. */
    fun download(context: Context, id: Long, name: String, status: String, ok: Boolean) {
        if (Foreground.visible) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureDownloadChannel(context)
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ribbt)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFFFF2442.toInt())
            .setContentTitle(if (ok) "Download complete" else "Download failed")
            .setContentText(if (name.isBlank()) status else "$name · $status")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n$status"))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1000 + (id % 1000).toInt(), notification)
    }
}
