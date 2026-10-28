package org.aralozkaya.msngr.protocol

import java.net.URI
import java.util.Locale

data class EmotionSound(val id: String, val name: String, val category: Int = 0) {
    val builtInCode: Int? get() = id.removePrefix("builtin-").toIntOrNull()?.takeIf { id.startsWith("builtin-") && it in 30..79 }
}

data class EmotionSoundMessage(val sound: EmotionSound, val displayText: String)

/** Plus! sounds travel inside ordinary, styled text/plain messages (including circle messages). */
object MsnEmotionSound {
    const val DEFAULT_SERVER = "https://bart.nina.chat/esnd/snd/"
    const val MAX_BYTES = 1024 * 1024
    const val CUSTOM_PREFIX = "[Messenger Plus! Sound] - Data{"
    private val hashPattern = Regex("[0-9A-Fa-f]{12}")
    private val tagHash = Regex("MsgPlus! - #([0-9A-Fa-f]{12})")

    // Codes and default captions from the native Plus! command dispatcher and English resources.
    private val defaults = listOf(
        Triple(35, "Hello!", "[c=7]Hello![/c]"),
        Triple(69, "Bye!", "[c=12]Bye![/c]"),
        Triple(33, "Be Right Back", "[c=10]Be right back[/c]"),
        Triple(36, "I'm So Stupid!", "[c=4]I'm so [b]stupid[/b]![/c]"),
        Triple(53, "Are You Mad?!?", "[c=14]Are you mad?!?[/c]"),
        Triple(57, "Oh My God!", "[c=4]Oh my [b]God[/b]![/c]"),
        Triple(37, "Kiss", "[c=13]XxxxXxxxX[/c]"),
        Triple(34, "Evil Laugh", "[c=4]Mua[/c][c=5]ha[/c][c=4]ha[/c][c=5]ha[/c][c=4]ha[/c]"),
        Triple(74, "Imperial March", ":-[ :-[ :-["),
        Triple(42, "Come on!", "[c=7]Come on![/c]"),
        Triple(64, "Dangerous", "[c=2]My [/c][c=55]patience[/c][c=2] has its limits...[/c]"),
        Triple(39, "Applause", "(h5)(h5)(h5)(h5)(h5)"),
        Triple(43, "Woow!", "[c=45][b]Wooooow![/b][/c]"),
        Triple(76, "Right...", "[c=6]Right...[/c]"),
        Triple(38, "Trekkie", "[c=12]Live long and prosper[/c]"),
        Triple(44, "Yawn", "[c=57]I'm tired |-)[/c]"),
        Triple(50, "You're Boring!", "[c=5]You're boring! :P[/c]"),
    )
    val builtIns: List<EmotionSound> = defaults.map { EmotionSound("builtin-${it.first}", it.second) }

    fun hash(value: String): String? = value.trim().removePrefix("#").takeIf(hashPattern::matches)?.uppercase(Locale.ROOT)

    fun parse(text: String): EmotionSoundMessage? {
        if (text.length > 2 && text[0] == '\u0004' && text[1].code in 30..79) {
            val id = "builtin-${text[1].code}"
            val sound = builtIns.firstOrNull { it.id == id } ?: EmotionSound(id, "Emotion sound ${text[1].code}")
            return EmotionSoundMessage(sound, text.substring(2))
        }
        if (!text.startsWith(CUSTOM_PREFIX) || text.length > 8192) return null
        val end = CUSTOM_PREFIX.length + 12
        if (text.getOrNull(end) != '}') return null
        val id = hash(text.substring(CUSTOM_PREFIX.length, end)) ?: return null
        val tail = text.substring(end + 1)
        if (!tail.startsWith("\u00a0-\u00a0") && !tail.startsWith(" - ")) return null
        val name = tail.substring(3).trimEnd { it == ' ' || it == '\u00a0' }.takeIf { it.isNotBlank() } ?: return null
        return EmotionSoundMessage(EmotionSound(id, name), "Sent a sound: $name")
    }

    fun message(sound: EmotionSound): String {
        sound.builtInCode?.let { code ->
            val caption = defaults.firstOrNull { it.first == code }?.third ?: sound.name
            return "\u0004${code.toChar()}$caption"
        }
        val id = requireNotNull(hash(sound.id)) { "Invalid sound ID." }
        val name = sound.name.replace(Regex("[\\r\\n\\u0000]"), " ").take(120).ifBlank { "Emotion sound" }
        return "$CUSTOM_PREFIX$id}\u00a0-\u00a0$name\u00a0" + " ".repeat(40)
    }

    fun server(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        uri.toASCIIString().trimEnd('/') + "/"
    }.getOrNull()

    /** Original custom downloads are MP3, with ID3v1 title/category/language and a Plus! ID comment. */
    fun downloaded(bytes: ByteArray, expected: EmotionSound? = null): EmotionSound {
        require(bytes.size in 128..MAX_BYTES) { "Invalid or oversized emotion sound." }
        val audioEnd = if (bytes.copyOfRange(bytes.size - 128, bytes.size - 125).contentEquals("TAG".toByteArray())) bytes.size - 128 else bytes.size
        // ID3v2 and zero padding are common. Require a real MPEG layer III frame header before playback.
        require((0 until minOf(audioEnd - 3, 65_536)).any { i ->
            val a = bytes[i].toInt() and 255; val b = bytes[i + 1].toInt() and 255; val c = bytes[i + 2].toInt() and 255
            a == 255 && b and 0xe0 == 0xe0 && b and 6 == 2 && b and 0x18 != 8 && c ushr 4 in 1..14 && c and 12 != 12
        }) { "The server did not return an MP3 sound." }
        if (expected?.builtInCode != null) return expected
        require(audioEnd < bytes.size) { "The sound's Plus! metadata is missing." }
        val tag = bytes.copyOfRange(audioEnd, bytes.size)
        val id = tagHash.find(tag.toString(Charsets.ISO_8859_1))?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        require(id != null && (expected == null || id == hash(expected.id))) { "The downloaded sound ID does not match." }
        // Native IDs combine the low 16 bits of the complete file size with CRC32 of everything before ID3v1.
        val crc = java.util.zip.CRC32().apply { update(bytes, 0, audioEnd) }.value
        require(id.take(4).toInt(16) == bytes.size.and(0xffff) && id.takeLast(8).toLong(16) == crc) {
            "The downloaded sound failed its integrity check."
        }
        val titleBytes = tag.copyOfRange(3, 33).takeWhile { it != 0.toByte() }.toByteArray()
        val utf8 = titleBytes.toString(Charsets.UTF_8)
        val title = (if ('\ufffd' in utf8) titleBytes.toString(charset("windows-1252")) else utf8).trim()
        val category = tag[93].toInt().and(255).takeIf { it in 1..5 } ?: 0
        return EmotionSound(id, title.ifBlank { expected?.name ?: "Emotion sound" }, category)
    }
}
