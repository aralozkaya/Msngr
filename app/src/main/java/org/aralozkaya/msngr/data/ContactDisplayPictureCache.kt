package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class CachedContactDisplayPicture(
    val picture: MsnDisplayPicture,
    val bytes: ByteArray,
)

internal interface ContactDisplayPictureCache {
    suspend fun load(
        accountEmail: String,
        contactEmails: Collection<String>,
    ): Map<String, CachedContactDisplayPicture>

    suspend fun store(
        accountEmail: String,
        contactEmail: String,
        picture: MsnDisplayPicture,
        bytes: ByteArray,
    )

    suspend fun remove(
        accountEmail: String,
        contactEmail: String,
    )
}

internal object EmptyContactDisplayPictureCache : ContactDisplayPictureCache {
    override suspend fun load(
        accountEmail: String,
        contactEmails: Collection<String>,
    ): Map<String, CachedContactDisplayPicture> = emptyMap()

    override suspend fun store(
        accountEmail: String,
        contactEmail: String,
        picture: MsnDisplayPicture,
        bytes: ByteArray,
    ) = Unit

    override suspend fun remove(
        accountEmail: String,
        contactEmail: String,
    ) = Unit
}

/** Account-scoped, hash-validated storage for contact display pictures. */
internal class FileContactDisplayPictureCache(
    private val rootDirectory: File,
) : ContactDisplayPictureCache {
    private val mutex = Mutex()

    override suspend fun load(
        accountEmail: String,
        contactEmails: Collection<String>,
    ): Map<String, CachedContactDisplayPicture> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val accountDirectory = accountDirectory(accountEmail)
            val contacts = contactEmails.asSequence()
                .map(String::normalizedCacheIdentity)
                .filter(String::isNotEmpty)
                .distinct()
                .map { contact -> contact to "${contact.sha256Hex()}.dp" }
                .toList()
            val retainedFiles = contacts.mapTo(HashSet(contacts.size)) { it.second }
            accountDirectory.listFiles()?.forEach { file ->
                if (file.name !in retainedFiles) file.delete()
            }
            contacts.mapNotNull { (normalizedContact, fileName) ->
                readRecord(File(accountDirectory, fileName), normalizedContact)
                    ?.let { normalizedContact to it }
            }.toMap()
        }
    }

    override suspend fun store(
        accountEmail: String,
        contactEmail: String,
        picture: MsnDisplayPicture,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val normalizedContact = contactEmail.normalizedCacheIdentity()
        if (!picture.creator.normalizedCacheIdentity().equals(normalizedContact, ignoreCase = true) ||
            bytes.size > MAX_PICTURE_BYTES ||
            !picture.matches(bytes)
        ) {
            return@withContext
        }
        mutex.withLock {
            val target = recordFile(accountEmail, normalizedContact)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            val metadata = picture.toXml().toByteArray(StandardCharsets.UTF_8)
            if (metadata.size > MAX_METADATA_BYTES) return@withLock
            runCatching {
                DataOutputStream(FileOutputStream(temporary)).use { output ->
                    output.writeInt(RECORD_MAGIC)
                    output.writeInt(RECORD_VERSION)
                    output.writeInt(metadata.size)
                    output.write(metadata)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }
                if (!temporary.renameTo(target)) {
                    FileInputStream(temporary).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    temporary.delete()
                }
            }.onFailure { temporary.delete() }
        }
    }

    override suspend fun remove(
        accountEmail: String,
        contactEmail: String,
    ) = withContext(Dispatchers.IO) {
        val normalizedContact = contactEmail.normalizedCacheIdentity()
        if (normalizedContact.isEmpty()) return@withContext
        mutex.withLock {
            val target = recordFile(accountEmail, normalizedContact)
            target.delete()
            File(target.parentFile, "${target.name}.tmp").delete()
            Unit
        }
    }

    private fun readRecord(
        file: File,
        normalizedContact: String,
    ): CachedContactDisplayPicture? {
        if (!file.isFile) return null
        if (file.length() !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            file.delete()
            return null
        }
        return runCatching {
            DataInputStream(FileInputStream(file)).use { input ->
                require(input.readInt() == RECORD_MAGIC)
                require(input.readInt() == RECORD_VERSION)
                val metadataSize = input.readInt()
                require(metadataSize in 1..MAX_METADATA_BYTES)
                val metadata = ByteArray(metadataSize).also(input::readFully)
                val pictureSize = input.readInt()
                require(pictureSize in 1..MAX_PICTURE_BYTES)
                val bytes = ByteArray(pictureSize).also(input::readFully)
                require(input.read() == -1)
                val picture = requireNotNull(
                    MsnDisplayPicture.parse(String(metadata, StandardCharsets.UTF_8)),
                )
                require(picture.creator.normalizedCacheIdentity().equals(normalizedContact, true))
                require(picture.matches(bytes))
                CachedContactDisplayPicture(picture, bytes)
            }
        }.getOrElse {
            file.delete()
            null
        }
    }

    private fun recordFile(accountEmail: String, contactEmail: String): File =
        File(accountDirectory(accountEmail), "${contactEmail.sha256Hex()}.dp")

    private fun accountDirectory(accountEmail: String): File = File(
        rootDirectory,
        accountEmail.normalizedCacheIdentity().sha256Hex(),
    )

    private companion object {
        const val RECORD_MAGIC = 0x4D534450 // MSDP
        const val RECORD_VERSION = 1
        const val MAX_PICTURE_BYTES = 1024 * 1024
        const val MAX_METADATA_BYTES = 16 * 1024
        const val MIN_RECORD_BYTES = 4L * 4L + 2L
        const val MAX_RECORD_BYTES = 4L * 4L + MAX_METADATA_BYTES + MAX_PICTURE_BYTES
    }
}

private fun String.normalizedCacheIdentity(): String {
    val withoutEndpoint = trim().substringBefore(';')
    val prefix = withoutEndpoint.substringBefore(':', missingDelimiterValue = "")
    return (if (prefix.toIntOrNull() != null) withoutEndpoint.substringAfter(':') else withoutEndpoint)
        .lowercase()
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
    val result = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        result[index * 2] = HEX_DIGITS[value ushr 4]
        result[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(result)
}

private const val HEX_DIGITS = "0123456789abcdef"
