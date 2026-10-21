package app.mediadl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/** What storage the phone has right now. An SD card can be pulled out at any time, so ask again before using it. */
object Storage {
    /** Volume name of an inserted SD card, or null when there isn't one. */
    fun sdVolume(context: Context): String? = runCatching {
        MediaStore.getExternalVolumeNames(context).firstOrNull {
            it != MediaStore.VOLUME_EXTERNAL_PRIMARY && it != MediaStore.VOLUME_EXTERNAL && it != MediaStore.VOLUME_INTERNAL
        }
    }.getOrNull()

    fun sdAvailable(context: Context): Boolean = sdVolume(context) != null

    /** True while the file behind [uri] can still be opened (it hasn't been deleted, the card is still in). */
    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** True while a folder picked with the system picker can still be written to (permission kept, card still there). */
    fun folderUsable(context: Context, folderUri: String): Boolean {
        if (folderUri.isBlank()) return false
        return runCatching {
            val resolver = context.contentResolver
            val tree = Uri.parse(folderUri)
            val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
            if (!granted) return@runCatching false
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }
}
