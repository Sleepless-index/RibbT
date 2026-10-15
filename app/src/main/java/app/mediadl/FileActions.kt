package app.mediadl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens a file RibbT saved in whatever app on the phone handles that type. */
object FileActions {
    fun open(context: Context, file: SavedRef) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, intent, "No app on this phone can open that file.")
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
