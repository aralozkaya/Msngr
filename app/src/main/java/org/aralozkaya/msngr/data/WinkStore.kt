package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import org.aralozkaya.msngr.sha256Hex
import java.util.Properties

/** CABs are content addressed, account scoped, and decompressed only when needed. */
internal class WinkStore(
    private val directory: File,
    private val bundled: () -> List<Pair<WinkReference, ByteArray>> = { emptyList() },
) {
    private val mutableLibraryChanges = MutableStateFlow(0L)
    val libraryChanges = mutableLibraryChanges.asStateFlow()
    private val defaults by lazy { bundled().associateBy { it.first.metadata.sha1d } }

    @Synchronized fun catalog(account: String): List<WinkReference> {
        val library = area(account, "library")
        val custom = library.mediaFiles("properties").mapNotNull { file ->
            readReference(file)?.takeIf { reference ->
                val cab = File(library, file.nameWithoutExtension + ".cab").recoverMediaFile()
                cab.isFile && cab.length() == reference.metadata.size.toLong()
            }
        }.sortedBy { it.name.lowercase() }
        return (defaults.values.map { it.first } + custom).distinctBy { it.metadata.sha1d }
    }

    @Synchronized fun isSaved(account: String, metadata: MsnDisplayPicture): Boolean {
        if (defaults[metadata.sha1d]?.first?.metadata?.size == metadata.size) return true
        val area = area(account, "library")
        val stem = metadata.sha1d.sha256Hex()
        val reference = readReference(File(area, "$stem.properties")) ?: return false
        val cab = File(area, "$stem.cab").recoverMediaFile()
        return reference.metadata.sha1d == metadata.sha1d && cab.isFile && cab.length() == metadata.size.toLong()
    }

    @Synchronized fun isDefault(metadata: MsnDisplayPicture): Boolean = defaults.containsKey(metadata.sha1d)

    @Synchronized fun knownName(account: String, metadata: MsnDisplayPicture): String? {
        defaults[metadata.sha1d]?.first?.name?.takeIf(String::isNotBlank)?.let { return it }
        if (account.isBlank()) return null
        val saved = readReference(File(area(account, "library"), metadata.sha1d.sha256Hex() + ".properties"))
        return saved?.name?.takeIf { it.isNotBlank() && !it.equals("Unused", true) && !it.equals("Wink", true) }
    }

    @Synchronized fun remove(account: String, metadata: MsnDisplayPicture) {
        require(!isDefault(metadata)) { "Default winks cannot be deleted." }
        val library = area(account, "library")
        val stem = metadata.sha1d.sha256Hex()
        deletePackage(File(library, "$stem.cab"))
        mutableLibraryChanges.value += 1
    }

    @Synchronized fun bytes(account: String, metadata: MsnDisplayPicture): ByteArray? {
        defaults[metadata.sha1d]?.second?.takeIf(metadata::matches)?.let { return it.copyOf() }
        for (kind in listOf("library", "received")) {
            val f = File(area(account, kind), metadata.sha1d.sha256Hex() + ".cab").recoverMediaFile()
            if (f.isFile && f.length() == metadata.size.toLong()) {
                val bytes = f.readBytes()
                if (metadata.matches(bytes)) { f.setLastModified(System.currentTimeMillis()); return bytes }
            }
        }
        return null
    }

    @Synchronized fun install(account: String, reference: WinkReference, bytes: ByteArray) {
        save(account, "library", reference, bytes, null)
        mutableLibraryChanges.value += 1
    }

    @Synchronized fun receive(account: String, contact: String, reference: WinkReference, bytes: ByteArray) {
        if (defaults.containsKey(reference.metadata.sha1d)) return
        save(account, "received", reference, bytes, contact)
    }

    @Synchronized fun clearReceived(account: String, contact: String? = null) {
        val area = area(account, "received")
        if (contact == null) {
            check(!area.exists() || area.deleteRecursively()) { "Could not clear received winks." }
            return
        }
        val owner = contact.lowercase().sha256Hex()
        for (f in area.mediaFiles("properties")) {
            val properties = Properties().apply { f.inputStream().use(::load) }
            val owners = properties.getProperty("contacts", "").split(',').filter(String::isNotEmpty).toMutableSet()
            if (!owners.remove(owner)) continue
            if (owners.isEmpty()) deletePackage(File(area, f.nameWithoutExtension + ".cab"))
            else {
                properties.setProperty("contacts", owners.joinToString(","))
                f.replaceMediaFile { properties.store(it, null) }
            }
        }
    }

    private fun save(account: String, kind: String, reference: WinkReference, bytes: ByteArray, contact: String?) {
        require(account.isNotBlank() && reference.metadata.matches(bytes))
        requireNotNull(MsnWinkPackage.parse(bytes)) { "Invalid wink package." }
        val area = area(account, kind).apply { mkdirs() }
        val stem = reference.metadata.sha1d.sha256Hex()
        val data = File(area, "$stem.cab").recoverMediaFile()
        val old = area.mediaFiles("cab").filter { it != data }.sortedBy { it.lastModified() }
        var used = old.sumOf { it.length() } + bytes.size
        val limit = if (kind == "library") 64L * 1024 * 1024 else 32L * 1024 * 1024
        if (kind == "library") require(used <= limit && old.size < 512) { "Your wink library is full." }
        var evictedCount = 0
        while (used > limit || (kind == "received" && old.size - evictedCount >= 256)) {
            val evicted = old[evictedCount++]
            used -= evicted.length()
            deletePackage(evicted)
        }
        if (!data.isFile || data.length() != bytes.size.toLong() || !data.readBytes().contentEquals(bytes))
            data.replaceMediaFile { it.write(bytes) }
        val meta = File(area, "$stem.properties").recoverMediaFile()
        val properties = Properties().apply { if (meta.exists()) meta.inputStream().use(::load) }
        properties.setProperty("xml", reference.metadata.toXml())
        properties.setProperty("name", reference.name)
        if (contact != null) {
            val owners = properties.getProperty("contacts", "").split(',').filter(String::isNotEmpty).toSet() + contact.lowercase().sha256Hex()
            properties.setProperty("contacts", owners.joinToString(","))
        }
        meta.replaceMediaFile { properties.store(it, null) }
    }
    private fun deletePackage(cab: File) {
        for (file in listOf(cab, File(cab.parentFile, cab.nameWithoutExtension + ".properties"))) {
            file.recoverMediaFile()
            check(!file.exists() || file.delete()) { "Could not delete wink." }
        }
    }
    private fun readReference(file: File): WinkReference? = runCatching {
        val p = Properties().apply { file.recoverMediaFile().inputStream().use(::load) }
        WinkReference(MsnWink.parseOffer(p.getProperty("xml")) ?: return null, p.getProperty("name", "Wink"))
    }.getOrNull()
    private fun area(account: String, kind: String) = File(File(directory, account.sha256Hex()), kind)
}
