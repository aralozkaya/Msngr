package org.aralozkaya.msngr

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.FileTransfer
import org.aralozkaya.msngr.data.SharedPhoto
import org.aralozkaya.msngr.protocol.MsnFileContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class TransferSaveState(val uri: String? = null, val error: String? = null)

/** Exports verified originals while keeping the private transfer available for Open / Save as. */
internal class ReceivedTransferStorage(private val context: Context) {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()

    fun state(account: AccountIdentity?, key: String): TransferSaveState {
        val prefs = AccountPreferences.forAccount(context, "msngr_transfer_saves", account)
        return TransferSaveState(prefs.getString("uri:$key", null), prefs.getString("error:$key", null))
    }

    private fun record(account: AccountIdentity?, key: String, state: TransferSaveState) {
        AccountPreferences.forAccount(context, "msngr_transfer_saves", account).edit {
            putString("uri:$key", state.uri)
            putString("error:$key", state.error)
        }
        changes.value += 1
    }

    suspend fun saveFile(account: AccountIdentity?, transfer: FileTransfer) = save(account, "file:${transfer.id}") {
        export(File(requireNotNull(transfer.localPath)), transfer.name, photo = false, TransferPrefs.read(context, account).fileTree)
    }

    suspend fun savePhoto(account: AccountIdentity?, shareId: String, photo: SharedPhoto) = save(account, "photo:$shareId:${photo.id}") {
        export(File(requireNotNull(photo.originalPath)), photo.name, photo = true, TransferPrefs.read(context, account).photoTree)
    }

    private suspend fun save(account: AccountIdentity?, key: String, write: suspend () -> Uri): Result<Uri> = withContext(Dispatchers.IO) {
        mutex.withLock {
            state(account, key).uri?.let {
                val uri = Uri.parse(it)
                val exists = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                if (exists) return@withLock Result.success(uri)
            }
            try {
                val uri = write()
                record(account, key, TransferSaveState(uri = uri.toString()))
                Result.success(uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                record(account, key, TransferSaveState(error = error.message ?: "The transfer could not be saved."))
                Result.failure(error)
            }
        }
    }

    private suspend fun export(file: File, name: String, photo: Boolean, tree: String?): Uri {
        require(file.isFile) { "The received original is no longer available." }
        require(MsnFileContext.validName(name)) { "The file name is unsupported." }
        val mime = if (photo) {
            // A peer's name may have no extension. Sniff the original instead of the JPEG preview.
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            requireNotNull(options.outMimeType?.takeIf { it.startsWith("image/") }) { "The original is not a supported photo." }
        } else FileSharingProvider.mime(name)
        val resolver = context.contentResolver
        if (tree != null) {
            val root = Uri.parse(tree)
            check(resolver.persistedUriPermissions.any { it.uri == root && it.isWritePermission }) {
                "Folder access was lost. Choose the ${if (photo) "photo" else "file"} folder again in File & photo transfers" +
                    if (photo) "." else ", or use Save as."
            }
            val parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
            val target = requireNotNull(DocumentsContract.createDocument(resolver, parent, mime, name)) { "The selected folder is unavailable." }
            try { copy(file, target); return target }
            catch (error: Exception) { runCatching { DocumentsContract.deleteDocument(resolver, target) }; throw error }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (photo) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (photo) "${Environment.DIRECTORY_DCIM}/Msngr" else Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = requireNotNull(resolver.insert(collection, values)) { "The default folder is unavailable." }
            try {
                copy(file, target)
                check(resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                return target
            } catch (error: Exception) { runCatching { resolver.delete(target, null, null) }; throw error }
        }
        check(hasDefaultTransferStorageAccess(context)) { "Allow storage access to save to the default folder on this Android version, or choose a custom folder." }
        @Suppress("DEPRECATION")
        val directory = Environment.getExternalStoragePublicDirectory(if (photo) Environment.DIRECTORY_DCIM else Environment.DIRECTORY_DOWNLOADS)
            .let { if (photo) File(it, "Msngr") else it }
        check(directory.isDirectory || directory.mkdirs()) { "The default folder is unavailable." }
        var target = File(directory, name)
        var suffix = 1
        while (!target.createNewFile()) {
            target = File(directory, "${name.substringBeforeLast('.', name)} (${suffix++})" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
        }
        try {
            file.inputStream().use { input -> target.outputStream().use { output -> copyCancellable(input, output) } }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            return Uri.fromFile(target)
        } catch (error: Exception) { target.delete(); throw error }
    }

    private suspend fun copy(file: File, target: Uri) {
        file.inputStream().use { input ->
            requireNotNull(context.contentResolver.openOutputStream(target, "wt")).use { output -> copyCancellable(input, output) }
        }
    }

    private suspend fun copyCancellable(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }
}

internal fun hasDefaultTransferStorageAccess(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

internal val Context.receivedTransferStorage: ReceivedTransferStorage
    get() = (applicationContext as MsngrApplication).receivedTransferStorage
