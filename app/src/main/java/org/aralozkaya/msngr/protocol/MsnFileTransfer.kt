package org.aralozkaya.msngr.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fixed-width, UTF-16LE context carried by a Messenger file invitation. */
data class MsnFileContext(val name: String, val size: Long) {
    fun encode(): ByteArray {
        require(validName(name) && size in 0..MAX_FILE_BYTES) { "Invalid file name or size." }
        return ByteBuffer.allocate(638).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(638); putInt(3); putLong(size); putInt(1)
            put(name.toByteArray(Charsets.UTF_16LE))
            position(570); putInt(-1)
        }.array()
    }

    companion object {
        const val GUID = "5D3E02AB-6190-11D3-BBBB-00C04F795683"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

        fun parse(bytes: ByteArray): MsnFileContext? = runCatching {
            if (bytes.size !in 574..65536) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val length = buffer.int
            val version = buffer.int
            val size = buffer.long
            if (length !in 574..bytes.size || version !in 2..3 || size !in 0..MAX_FILE_BYTES) return null
            buffer.int // Preview flags; image previews follow the fixed header.
            val filename = ByteArray(520).also(buffer::get).toString(Charsets.UTF_16LE)
            val terminator = filename.indexOf('\u0000')
            if (terminator < 0) return null
            val name = filename.substring(0, terminator)
            if (!validName(name)) return null
            MsnFileContext(name, size)
        }.getOrNull()

        fun validName(name: String): Boolean = name.isNotBlank() && name.length <= 259 &&
            name !in listOf(".", "..") && name.none { it < ' ' || it in "/\\:*?\"<>|" } &&
            !name.endsWith('.') && !name.endsWith(' ')

        /** Native Messenger blocks these offers when no compatible scanner is installed. */
        fun isExecutable(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
            "exe", "com", "scr", "pif", "bat", "cmd", "msi", "msp", "cpl", "dll", "hta",
            "js", "jse", "vbs", "vbe", "wsf", "wsh", "ps1", "lnk", "reg", "apk",
        )
    }
}
