package com.nova.keyboard.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Read-only provider used to hand cached clipboard media to apps accepting IME rich content. */
class ClipboardContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val file = resolveFile(uri) ?: return null
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolveFile(uri) ?: return null
        val columns = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Clipboard content is read-only")
        val file = resolveFile(uri) ?: throw FileNotFoundException("Clipboard item not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Clipboard provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Clipboard provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Clipboard provider is read-only")

    private fun resolveFile(uri: Uri): File? {
        val ctx = context ?: return null
        if (uri.authority != "${ctx.packageName}.nova-clipboard") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "item") return null
        val name = segments[1]
        if (!name.matches(Regex("clip_[0-9]+\\.[A-Za-z0-9]{1,10}"))) return null

        val root = File(ctx.cacheDir, "nova_clipboard").canonicalFile
        val file = File(root, name).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }
}
