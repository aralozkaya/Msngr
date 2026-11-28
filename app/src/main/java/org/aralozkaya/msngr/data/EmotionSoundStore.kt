package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.readBytesBounded
import org.aralozkaya.msngr.protocol.EmotionSound
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.aralozkaya.msngr.sha256Hex

fun interface EmotionSoundHttp { fun get(url: String): ByteArray }

internal class EmotionSoundService(private val server: String, private val http: EmotionSoundHttp = EmotionSoundHttp(::getSoundResponse)) {
    private val base = requireNotNull(MsnEmotionSound.server(server)) { "Invalid emotion sounds service URL." }
    suspend fun download(sound: EmotionSound): Pair<EmotionSound, ByteArray> = withContext(Dispatchers.IO) {
        val path = sound.builtInCode?.let { "builtin?code=$it" }
            ?: "get?hash=${requireNotNull(MsnEmotionSound.hash(sound.id))}"
        val bytes = http.get(base + path)
        check(!bytes.contentEquals(byteArrayOf('0'.code.toByte()))) { "This sound is no longer available on the server." }
        MsnEmotionSound.downloaded(bytes, sound) to bytes
    }
    suspend fun random(category: Int, language: Int = 0): Pair<EmotionSound, ByteArray> = withContext(Dispatchers.IO) {
        require(category in 1..5 && language in 0..255)
        val bytes = http.get(base + "random?catId=$category" + if (language > 0) "&lngId=$language" else "")
        check(!bytes.contentEquals(byteArrayOf('0'.code.toByte()))) { "No sounds were found for this category and language." }
        MsnEmotionSound.downloaded(bytes) to bytes
    }
    suspend fun popular(): List<EmotionSound> = withContext(Dispatchers.IO) {
        // Each provider publishes its catalog as HTML.
        when (base) {
            MsnEmotionSound.DEFAULT_SERVER, "http://msn.bart.escargot.chat/esnd/snd/" -> {
                parsePopularCatalog(
                    html = http.get(MsnEmotionSound.ESCARGOT_CATALOG_URL).toString(Charsets.UTF_8),
                    pattern = Regex("<h5>([^<]+)</h5>|playAudio\\('([0-9a-fA-F]{12})'\\);[\\s\\S]*?</i>\\s*([^<]{1,500})</th>"),
                    categoryGroup = 1,
                    idGroup = 2,
                    nameGroup = 3,
                )
            }
            MsnEmotionSound.CROSSTALK_SERVER -> {
                parsePopularCatalog(
                    html = http.get(MsnEmotionSound.CROSSTALK_CATALOG_URL).toString(Charsets.UTF_8),
                    pattern = Regex("<h3 class=\"snd-chart-title\"[^>]*>([^<]+)</h3>|<span class=\"snd-title\"[^>]*>([^<]{1,500})</span>[\\s\\S]*?data-ct-copy=\"/sound #([0-9a-fA-F]{12})\""),
                    categoryGroup = 1,
                    idGroup = 3,
                    nameGroup = 2,
                )
            }
            else -> emptyList()
        }
    }

    private fun parsePopularCatalog(
        html: String,
        pattern: Regex,
        categoryGroup: Int,
        idGroup: Int,
        nameGroup: Int,
    ): List<EmotionSound> {
        var category = 0
        return pattern.findAll(html).take(110).mapNotNull { match ->
            val heading = match.groupValues[categoryGroup]
            if (heading.isNotEmpty()) {
                category = popularCategoryIndex(heading)
                return@mapNotNull null
            }
            val id = match.groupValues[idGroup].uppercase(java.util.Locale.ROOT)
            val name = decodeHtmlEntities(match.groupValues[nameGroup])
            EmotionSound(id, name, category)
        }.distinctBy { it.id }.toList()
    }

    private fun popularCategoryIndex(heading: String): Int {
        val normalized = heading.trim().replace(" / ", "/")
        return listOf("Cartoons/Games", "TV/Movies", "Music", "Vocals", "Sound Effects").indexOf(normalized) + 1
    }

    private fun decodeHtmlEntities(text: String): String =
        text.replace("&#039;", "'").replace("&#39;", "'")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .trim()
}

private fun getSoundResponse(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "MessengerPlusLive")
    return try {
        check(connection.responseCode == 200) { "The emotion sounds service returned HTTP ${connection.responseCode}." }
        require(connection.contentLengthLong <= MsnEmotionSound.MAX_BYTES) { "The sound download is too large." }
        connection.inputStream.use { input ->
            input.readBytesBounded(MsnEmotionSound.MAX_BYTES) { "The sound download is too large." }
        }
    } finally { connection.disconnect() }
}

/** Account/service-isolated MP3 library and bounded replay cache; history retains only the original text. */
internal class EmotionSoundStore(private val root: File) {
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private fun folder(account: String, server: String): File {
        val normalizedServer = MsnEmotionSound.server(server).orEmpty()
        val key = "$account\u0000$normalizedServer".sha256Hex()
        return File(root, key)
    }
    private fun file(account: String, server: String, sound: EmotionSound, saved: Boolean): File {
        require(sound.builtInCode != null || MsnEmotionSound.hash(sound.id) != null)
        val id = sound.builtInCode?.let { "builtin-$it" } ?: MsnEmotionSound.hash(sound.id)!!
        return File(folder(account, server), "${if (saved) "saved" else "cache"}/$id.mp3")
    }
    @Synchronized fun bytes(account: String, server: String, sound: EmotionSound): ByteArray? {
        for (saved in listOf(true, false)) {
            val path = file(account, server, sound, saved).recoverMediaFile()
            if (path.isFile && path.length() in 128..MsnEmotionSound.MAX_BYTES.toLong()) {
                val bytes = path.readBytes()
                if (runCatching { MsnEmotionSound.downloaded(bytes, sound) }.isSuccess) { path.setLastModified(System.currentTimeMillis()); return bytes }
            }
        }
        return null
    }
    @Synchronized fun isSaved(account: String, server: String, sound: EmotionSound): Boolean =
        file(account, server, sound, true).recoverMediaFile().isFile
    @Synchronized fun catalog(account: String, server: String): List<EmotionSound> =
        MsnEmotionSound.builtIns + File(folder(account, server), "saved").mediaFiles("mp3").filter { it.length() in 128..MsnEmotionSound.MAX_BYTES.toLong() }
            .mapNotNull { path -> runCatching { MsnEmotionSound.downloaded(path.readBytes()) }.getOrNull() }.sortedBy { it.name.lowercase() }

    @Synchronized fun put(account: String, server: String, sound: EmotionSound, bytes: ByteArray, save: Boolean = false) {
        MsnEmotionSound.downloaded(bytes, sound)
        val destination = file(account, server, sound, save)
        val directory = requireNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the sounds directory." }
        val files = directory.mediaFiles("mp3").filter { it != destination }
        if (save) {
            check(files.size < MAX_FILES && files.sumOf { it.length() } + bytes.size <= MAX_LIBRARY_BYTES) {
                "Your saved sounds library is full."
            }
        }
        destination.replaceMediaFile { it.write(bytes) }
        if (!save) {
            var total = files.sumOf { it.length() } + bytes.size
            var count = files.size + 1
            for (path in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_LIBRARY_BYTES && count <= MAX_FILES) break
                val size = path.length()
                if (path.delete()) {
                    total -= size
                    count--
                }
            }
        }
        if (save) mutableChanges.value++
    }
    @Synchronized fun remove(account: String, server: String, sound: EmotionSound) {
        bytes(account, server, sound)?.let { put(account, server, sound, it) }
        val saved = file(account, server, sound, true)
        check(!saved.exists() || saved.delete()) { "The sound could not be removed." }
        mutableChanges.value++
    }

    private companion object {
        const val MAX_FILES = 256
        const val MAX_LIBRARY_BYTES = 32L * 1024 * 1024
    }
}
