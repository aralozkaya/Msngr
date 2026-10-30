package org.aralozkaya.msngr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Read-only grants for completed files, confined to the app's private transfer directory. */
class FileSharingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = mime(uri.getQueryParameter("name").orEmpty())
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(resolve(requireNotNull(context), uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(requireNotNull(context), uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> {
            when (it) { OpenableColumns.DISPLAY_NAME -> uri.getQueryParameter("name") ?: file.name; OpenableColumns.SIZE -> file.length(); else -> null }
        }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private fun area(context: Context) = File(context.noBackupFilesDir, "file_transfers").canonicalFile
        private fun resolve(context: Context, uri: Uri): File {
            val root = area(context)
            val file = File(root, uri.pathSegments.joinToString(File.separator)).canonicalFile
            require(file.path.startsWith(root.path + File.separator) && file.isFile) { "This file is unavailable." }
            return file
        }
        internal fun uri(context: Context, file: File, name: String): Uri {
            val root = area(context)
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(root.path + File.separator) && canonical.isFile)
            return Uri.Builder().scheme("content").authority(context.packageName + ".files")
                .apply { canonical.relativeTo(root).invariantSeparatorsPath.split('/').forEach(::appendPath) }
                .appendQueryParameter("name", name).build()
        }
        internal fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
    }
}
