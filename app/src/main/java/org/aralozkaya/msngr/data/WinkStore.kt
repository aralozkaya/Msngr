package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.*
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** CABs are content addressed, account scoped, and decompressed only when needed. */
internal class WinkStore(
    private val directory: File,
    private val bundled: () -> List<Pair<WinkReference, ByteArray>> = { emptyList() },
) {
    private val defaults by lazy { bundled().associateBy { it.first.metadata.sha1d } }

    @Synchronized fun catalog(account: String): List<WinkReference> =
        (defaults.values.map { it.first } + area(account, "library").listFiles().orEmpty()
            .filter { it.extension == "properties" }.mapNotNull { readReference(it) }).distinctBy { it.metadata.sha1d }

    @Synchronized fun bytes(account: String, metadata: MsnDisplayPicture): ByteArray? {
        defaults[metadata.sha1d]?.second?.takeIf(metadata::matches)?.let { return it.copyOf() }
        for (kind in listOf("library", "received")) {
            val f = File(area(account, kind), metadata.sha1d.key() + ".cab")
            if (f.exists() && f.length() == metadata.size.toLong()) {
                val bytes = f.readBytes()
                if (metadata.matches(bytes)) { f.setLastModified(System.currentTimeMillis()); return bytes }
            }
        }
        return null
    }

    @Synchronized fun install(account: String, reference: WinkReference, bytes: ByteArray) {
        save(account, "library", reference, bytes, null)
    }

    @Synchronized fun receive(account: String, contact: String, reference: WinkReference, bytes: ByteArray) {
        if (defaults.containsKey(reference.metadata.sha1d)) return
        save(account, "received", reference, bytes, contact)
    }

    @Synchronized fun clearReceived(account: String, contact: String? = null) {
        val area = area(account, "received")
        for (f in area.listFiles().orEmpty().filter { it.extension == "properties" }) {
            val properties = Properties().apply { f.inputStream().use(::load) }
            val owners = properties.getProperty("contacts", "").split(',').filter(String::isNotEmpty).toMutableSet()
            if (contact != null) owners.remove(contact.lowercase().key()) else owners.clear()
            if (owners.isEmpty()) { File(area, f.nameWithoutExtension + ".cab").delete(); f.delete() }
            else { properties.setProperty("contacts", owners.joinToString(",")); atomic(f, properties) }
        }
    }

    private fun save(account: String, kind: String, reference: WinkReference, bytes: ByteArray, contact: String?) {
        require(account.isNotBlank() && reference.metadata.matches(bytes))
        requireNotNull(MsnWinkPackage.parse(bytes)) { "Invalid wink package." }
        val area = area(account, kind).apply { mkdirs() }
        val stem = reference.metadata.sha1d.key()
        val data = File(area, "$stem.cab")
        val old = area.listFiles().orEmpty().filter { it.extension == "cab" && it != data }.sortedBy { it.lastModified() }.toMutableList()
        var used = old.sumOf { it.length() } + bytes.size
        val limit = if (kind == "library") 64L * 1024 * 1024 else 32L * 1024 * 1024
        if (kind == "library") require(used <= limit && old.size < 512) { "Your wink library is full." }
        while (used > limit || (kind == "received" && old.size >= 256)) {
            val evicted = old.removeAt(0); used -= evicted.length(); evicted.delete()
            File(area, evicted.nameWithoutExtension + ".properties").delete()
        }
        if (!data.exists()) {
            val temporary = File(area, "$stem.tmp")
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.renameTo(data)) { "Could not save wink." }
        }
        val meta = File(area, "$stem.properties")
        val properties = Properties().apply { if (meta.exists()) meta.inputStream().use(::load) }
        properties.setProperty("xml", reference.metadata.toXml())
        properties.setProperty("name", reference.name)
        if (contact != null) {
            val owners = properties.getProperty("contacts", "").split(',').filter(String::isNotEmpty).toSet() + contact.lowercase().key()
            properties.setProperty("contacts", owners.joinToString(","))
        }
        atomic(meta, properties)
    }
    private fun atomic(file: File, properties: Properties) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { properties.store(it, null) }
        val backup = File(file.parentFile, file.name + ".bak")
        backup.delete()
        if (file.exists()) check(file.renameTo(backup)) { "Could not replace wink metadata." }
        if (!temporary.renameTo(file)) { backup.renameTo(file); error("Could not save wink metadata.") }
        backup.delete()
    }
    private fun readReference(file: File): WinkReference? = runCatching {
        val p = Properties().apply { file.inputStream().use(::load) }
        WinkReference(MsnWink.parseOffer(p.getProperty("xml")) ?: return null, p.getProperty("name", "Wink"))
    }.getOrNull()
    private fun area(account: String, kind: String) = File(File(directory, account.key()), kind)
    private fun String.key() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}
