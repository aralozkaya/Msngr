package org.aralozkaya.msngr.protocol

import org.aralozkaya.msngr.protocol.cab.DecompressLzx
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory

/** Single-cabinet reader. Never writes archive-controlled paths to the filesystem. */
object MsnWinkPackage {
    private const val MAX_FILES = 32
    private const val MAX_BLOCKS = 512

    fun parse(bytes: ByteArray): WinkPackage? = runCatching { read(bytes) }.getOrNull()

    fun read(bytes: ByteArray): WinkPackage {
        val files = cabinet(bytes)
        val manifest = xml(files["content.xml"] ?: error("Missing wink manifest."))
        require(manifest.getAttribute("type").equals("wink", true)) { "This package is not a wink." }
        val items = manifest.getElementsByTagNameNS("*", "item")
        fun item(type: String): Element? = (0 until items.length).map { items.item(it) as Element }
            .firstOrNull { it.getAttribute("type").equals(type, true) }
        val animation = item("animation") ?: error("Missing wink animation.")
        val swf = files[safeName(animation.getAttribute("file"))] ?: error("Missing SWF.")
        require(swf.size >= 8 && String(swf, 0, 3, Charsets.US_ASCII) in listOf("FWS", "CWS", "ZWS"))
        val expanded = ByteBuffer.wrap(swf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(expanded in 8..MsnWink.MAX_EXPANDED_BYTES) { "SWF is too large." }
        val thumb = item("thumbnail")?.let { files[safeName(it.getAttribute("file"))] }
        // Bound image dimensions before Android decodes an untrusted thumbnail.
        return WinkPackage(bytes.copyOf(), swf, thumb,
            animation.getAttribute("wink:sizex").toIntOrNull()?.coerceIn(1, 2048) ?: 640,
            animation.getAttribute("wink:sizey").toIntOrNull()?.coerceIn(1, 2048) ?: 480)
    }

    /** MCO installers wrap one or more signed wink CABs in a theme CAB. */
    fun install(bytes: ByteArray, fallbackName: String = "Wink"): List<Pair<WinkReference, ByteArray>> {
        val files = cabinet(bytes)
        val manifest = xml(files["content.xml"] ?: error("Missing package manifest."))
        if (manifest.getAttribute("type").equals("wink", true)) {
            read(bytes)
            return listOf(WinkReference(MsnDisplayPicture.create("msngr", bytes, 8), fallbackName.take(120)) to bytes)
        }
        require(manifest.getAttribute("type").equals("theme", true)) { "Unsupported content package." }
        val items = manifest.getElementsByTagNameNS("*", "item")
        val result = (0 until items.length).mapNotNull { index ->
            val item = items.item(index) as Element
            if (!item.getAttribute("type").equals("wink", true)) return@mapNotNull null
            val file = safeName(item.getAttribute("file"))
            val cab = files[file] ?: error("Missing wink in installer.")
            read(cab)
            val name = item.getAttribute("name").ifBlank { if (items.length == 1) fallbackName else file.substringBeforeLast('.').replace('_', ' ') }.take(120)
            val metadata = MsnDisplayPicture.create("msngr", cab, 8).copy(
                extraAttributes = mapOf("stamp" to item.getAttribute("stamp")))
            WinkReference(metadata, name) to cab
        }
        require(result.isNotEmpty()) { "This installer contains no winks." }
        return result
    }

    internal fun xml(bytes: ByteArray): Element {
        require(bytes.size <= 64 * 1024)
        require(!bytes.toString(Charsets.UTF_8).replace("\u0000", "").contains("<!DOCTYPE", ignoreCase = true)) { "DTD is not allowed." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are not allowed.") }
        val document = builder.parse(bytes.inputStream())
        require(document.doctype == null)
        return document.documentElement
    }

    private fun safeName(name: String): String {
        val path = name.replace('\\', '/').lowercase(Locale.ROOT)
        require(path.isNotBlank() && path.length <= 240 && !path.startsWith('/') && ':' !in path &&
            path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe package path." }
        return path
    }

    internal fun cabinet(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size in 36..MsnWink.MAX_CAB_BYTES) { "Wink package is too large or incomplete." }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16() = input.short.toInt() and 65535
        fun u32() = (input.int.toLong() and 0xffffffffL).also { require(it <= Int.MAX_VALUE) }.toInt()
        require(input.int == 0x4643534d) { "Expected a CAB/MCO package." }
        input.int
        require(u32() == bytes.size)
        input.int
        val filesOffset = u32()
        input.int
        require(input.get().toInt() == 3 && input.get().toInt() == 1)
        val folderCount = u16(); val fileCount = u16(); val flags = u16()
        u16(); u16()
        require(folderCount in 1..MAX_FILES && fileCount in 1..MAX_FILES && flags and 3 == 0 && flags and 7 == flags)
        var folderReserve = 0; var dataReserve = 0
        if (flags and 4 != 0) {
            val headerReserve = u16(); folderReserve = input.get().toInt() and 255; dataReserve = input.get().toInt() and 255
            input.position(input.position() + headerReserve)
        }
        data class Folder(val offset: Int, val blocks: Int, val compression: Int)
        val folders = List(folderCount) {
            val offset = u32(); val blocks = u16(); val compression = u16()
            require(blocks in 1..MAX_BLOCKS && compression and 15 in listOf(0, 1, 3)) { "Unsupported CAB compression." }
            if (compression and 15 == 3) require(compression shr 8 in 15..21)
            input.position(input.position() + folderReserve)
            Folder(offset, blocks, compression)
        }
        require(filesOffset >= input.position())
        input.position(filesOffset)
        data class Entry(val name: String, val size: Int, val offset: Int, val folder: Int)
        val names = hashSetOf<String>()
        var fileTotal = 0L
        val entries = List(fileCount) {
            val size = u32(); val offset = u32(); val folder = u16()
            u16(); u16(); val attributes = u16()
            val nameBytes = ByteArrayOutputStream()
            while (true) {
                val b = input.get(); if (b == 0.toByte()) break
                require(nameBytes.size() < 240); nameBytes.write(b.toInt())
            }
            val name = safeName(nameBytes.toByteArray().toString(if (attributes and 128 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1))
            fileTotal += size
            require(names.add(name) && folder in folders.indices && fileTotal <= MsnWink.MAX_EXPANDED_BYTES &&
                offset.toLong() + size <= MsnWink.MAX_EXPANDED_BYTES)
            Entry(name, size, offset, folder)
        }
        val tableEnd = input.position()
        var totalExpanded = 0
        val decoded = folders.map { folder ->
            require(folder.offset >= tableEnd)
            input.position(folder.offset)
            val output = ByteArrayOutputStream()
            var dictionary = byteArrayOf()
            val lzx = if (folder.compression and 15 == 3) DecompressLzx().apply { init(folder.compression shr 8) } else null
            repeat(folder.blocks) {
                val checksum = input.int; val packedSize = u16(); val unpackedSize = u16()
                require(packedSize in 1..38912 && unpackedSize in 1..32768)
                totalExpanded += unpackedSize
                require(totalExpanded <= MsnWink.MAX_EXPANDED_BYTES)
                input.position(input.position() + dataReserve)
                val packed = ByteArray(packedSize).also(input::get)
                if (checksum != 0) {
                    val sizes = byteArrayOf(packedSize.toByte(), (packedSize shr 8).toByte(), unpackedSize.toByte(), (unpackedSize shr 8).toByte())
                    require(checksum == (cabChecksum(packed) xor cabChecksum(sizes))) { "CAB checksum mismatch." }
                }
                val decoded = when (folder.compression and 15) {
                    0 -> packed.also { require(it.size == unpackedSize) }
                    1 -> {
                        require(packed.size > 2 && packed[0] == 67.toByte() && packed[1] == 75.toByte())
                        val inflater = Inflater(true)
                        try {
                            if (dictionary.isNotEmpty()) inflater.setDictionary(dictionary)
                            inflater.setInput(packed, 2, packed.size - 2)
                            val block = ByteArray(unpackedSize)
                            var read = 0
                            while (read < block.size) {
                                val count = inflater.inflate(block, read, block.size - read)
                                require(count > 0); read += count
                            }
                            require(inflater.finished() && inflater.remaining == 0)
                            block
                        } finally { inflater.end() }
                    }
                    else -> ByteArray(unpackedSize).also { lzx!!.decompress(packed.copyOf(packed.size + 4), it, packed.size, unpackedSize) }
                }
                output.write(decoded)
                dictionary = (dictionary + decoded).takeLast(32768).toByteArray()
            }
            output.toByteArray()
        }
        return entries.associate { entry ->
            val folder = decoded[entry.folder]
            require(entry.offset.toLong() + entry.size <= folder.size)
            entry.name to folder.copyOfRange(entry.offset, entry.offset + entry.size)
        }
    }

    private fun cabChecksum(bytes: ByteArray): Int {
        var result = 0; var i = 0
        while (i + 4 <= bytes.size) {
            result = result xor ByteBuffer.wrap(bytes, i, 4).order(ByteOrder.LITTLE_ENDIAN).int; i += 4
        }
        var tail = 0
        while (i < bytes.size) { tail = (tail shl 8) or (bytes[i++].toInt() and 255) }
        return result xor tail
    }
}
