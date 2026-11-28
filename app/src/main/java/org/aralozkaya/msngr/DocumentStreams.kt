package org.aralozkaya.msngr

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal fun Context.isSafeExternalFileUri(uri: Uri): Boolean = when (uri.scheme) {
    ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE -> !uri.authority.isNullOrBlank()
    ContentResolver.SCHEME_FILE -> runCatching {
        val file = uri.path?.let(::File)?.canonicalFile ?: return@runCatching false
        // Legacy file managers send raw paths on shared storage. Private providers use content URIs.
        getSystemService(StorageManager::class.java)?.getStorageVolume(file) != null &&
            getExternalFilesDirs(null).filterNotNull().none { dir ->
                val appDirectory = dir.canonicalFile.parentFile
                appDirectory != null && (file == appDirectory || file.path.startsWith(appDirectory.path + File.separator))
            }
    }.getOrDefault(false)
    else -> false
}

internal fun Context.queryDocumentDisplayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (name >= 0 && cursor.moveToFirst()) cursor.getString(name) else null
    }
}.getOrNull() ?: uri.lastPathSegment

internal inline fun InputStream.readBytesBounded(limit: Int, tooLarge: () -> String): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count == -1) return result.toByteArray()
        require(count <= limit - result.size(), tooLarge)
        result.write(buffer, 0, count)
    }
}
