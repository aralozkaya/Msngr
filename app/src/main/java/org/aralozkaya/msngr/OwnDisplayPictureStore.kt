package org.aralozkaya.msngr

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.aralozkaya.msngr.protocol.DisplayPictureFormat
import org.aralozkaya.msngr.protocol.DisplayPictureLimits
import java.io.File
import java.security.MessageDigest

internal data class SavedDisplayPicture(val initialized: Boolean, val bytes: ByteArray? = null, val uri: String? = null)

/** Original image bytes, isolated by email AND service. A saved removal is a real snapshot. */
internal object OwnDisplayPictureStore {
    private fun directory(context: Context, account: AccountIdentity) =
        File(context.noBackupFilesDir, "own_display_pictures/${account.storageKey}")

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun selected(context: Context, account: AccountIdentity): String? = runCatching {
        AtomicFile(File(directory(context, account), "selected")).openRead().use {
            // Bound the metadata read, too. The only valid values are 'none' and a SHA-256.
            val buffer = ByteArray(65)
            val length = it.read(buffer)
            if (length <= 0 || length > 64 || it.read() != -1) null else String(buffer, 0, length, Charsets.US_ASCII)
        }
    }.getOrNull()

    @Synchronized fun reference(context: Context, account: AccountIdentity): SavedDisplayPicture {
        val selected = selected(context, account) ?: return SavedDisplayPicture(false)
        if (selected == "none") return SavedDisplayPicture(true)
        if (!selected.matches(Regex("[a-f0-9]{64}"))) return SavedDisplayPicture(false)
        val file = File(directory(context, account), "$selected.image")
        if (!file.isFile || file.length() !in 1..DisplayPictureLimits.MAX_BYTES.toLong()) return SavedDisplayPicture(false)
        return SavedDisplayPicture(true, uri = Uri.fromFile(file).toString())
    }

    @Synchronized fun read(context: Context, account: AccountIdentity): SavedDisplayPicture {
        val reference = reference(context, account)
        if (!reference.initialized || reference.uri == null) return reference
        return runCatching {
            val file = File(requireNotNull(Uri.parse(reference.uri).path))
            val bytes = file.readBytes()
            require(bytes.size <= DisplayPictureLimits.MAX_BYTES && DisplayPictureFormat.detect(bytes) != null)
            require(file.nameWithoutExtension == hash(bytes))
            reference.copy(bytes = bytes)
        }.getOrElse { SavedDisplayPicture(false) }
    }

    @Synchronized fun write(context: Context, account: AccountIdentity, bytes: ByteArray?): SavedDisplayPicture {
        require(bytes == null || bytes.size <= DisplayPictureLimits.MAX_BYTES && DisplayPictureFormat.detect(bytes) != null)
        val directory = directory(context, account).apply { mkdirs() }
        val selected = bytes?.let(::hash) ?: "none"
        val file = bytes?.let { File(directory, "$selected.image") }
        if (file != null) atomicWrite(file, requireNotNull(bytes))
        atomicWrite(File(directory, "selected"), selected.toByteArray(Charsets.US_ASCII))
        // The new reference is committed before retiring superseded originals.
        directory.listFiles()?.filter { it.extension == "image" && it != file }?.forEach { it.delete() }
        return SavedDisplayPicture(true, bytes, file?.let { Uri.fromFile(it).toString() })
    }

    @Synchronized fun clear(context: Context, account: AccountIdentity) {
        val directory = directory(context, account)
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
