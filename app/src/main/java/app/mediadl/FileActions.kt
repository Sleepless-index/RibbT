package app.mediadl

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens or shares files RibbT saved, through the apps already on the phone. Nothing is uploaded by RibbT itself. */
object FileActions {
    fun open(context: Context, file: SavedRef) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, intent, "No app on this phone can open that file.")
    }

    /** Opens Android's share sheet with the file (or all the files) attached, like sharing from a gallery. */
    fun share(context: Context, files: List<SavedRef>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { Uri.parse(it.uri) })
        val mimes = files.map { it.mime.ifBlank { "*/*" } }.distinct()
        val type = when {
            mimes.size == 1 -> mimes[0]
            mimes.all { it.startsWith("image/") } -> "image/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = type
        // The clip is what gives the app you pick permission to read the files.
        val clip = ClipData.newRawUri("RibbT", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, null), "Couldn’t open the share sheet.")
    }

    private fun start(context: Context, intent: Intent, failure: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        }
    }
}
