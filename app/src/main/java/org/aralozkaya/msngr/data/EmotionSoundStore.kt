package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.EmotionSound
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        check(!bytes.contentEquals(byteArrayOf('0'.code.toByte()))) { "No sounds were found in this category." }
        MsnEmotionSound.downloaded(bytes) to bytes
    }
    suspend fun popular(): List<EmotionSound> = withContext(Dispatchers.IO) {
        // The native protocol has local name search and ID downloads, not an invented remote search API.
        // Escargot publishes these discoverable IDs on its public catalog, using the same sound service.
        if (base != MsnEmotionSound.DEFAULT_SERVER && base != "http://msn.bart.escargot.chat/esnd/snd/") return@withContext emptyList()
        val html = http.get("https://escargot.chat/sounds/").toString(Charsets.UTF_8)
        var category = 0
        Regex("<h5>([^<]+)</h5>|playAudio\\('([0-9a-fA-F]{12})'\\);[\\s\\S]*?</i>\\s*([^<]{1,500})</th>").findAll(html).take(110).mapNotNull { match ->
            if (match.groupValues[1].isNotEmpty()) {
                category = listOf("Cartoons / Games", "TV / Movies", "Music", "Vocals", "Sound Effects").indexOf(match.groupValues[1].trim()) + 1
                return@mapNotNull null
            }
            val name = match.groupValues[3].replace("&#039;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            EmotionSound(match.groupValues[2].uppercase(java.util.Locale.ROOT), name, category)
        }.distinctBy { it.id }.toList()
    }
}

private fun getSoundResponse(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000; connection.readTimeout = 15_000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "MessengerPlusLive")
    return try {
        check(connection.responseCode == 200) { "The emotion sounds service returned HTTP ${connection.responseCode}." }
        require(connection.contentLengthLong <= MsnEmotionSound.MAX_BYTES) { "The sound download is too large." }
        connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MsnEmotionSound.MAX_BYTES) { "The sound download is too large." }
                output.write(buffer, 0, count)
            }; output.toByteArray()
        }
    } finally { connection.disconnect() }
}

/** Account/service-isolated MP3 library and bounded replay cache; history retains only the original text. */
internal class EmotionSoundStore(private val root: File) {
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private fun folder(account: String, server: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest((account + "\u0000" + MsnEmotionSound.server(server)).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, key)
    }
    private fun file(account: String, server: String, sound: EmotionSound, saved: Boolean): File {
        require(sound.builtInCode != null || MsnEmotionSound.hash(sound.id) != null)
        val id = sound.builtInCode?.let { "builtin-$it" } ?: MsnEmotionSound.hash(sound.id)!!
        return File(folder(account, server), "${if (saved) "saved" else "cache"}/$id.mp3")
    }
    @Synchronized fun bytes(account: String, server: String, sound: EmotionSound): ByteArray? {
        for (saved in listOf(true, false)) {
            val path = file(account, server, sound, saved)
            if (path.isFile && path.length() in 128..MsnEmotionSound.MAX_BYTES.toLong()) {
                val bytes = path.readBytes()
                if (runCatching { MsnEmotionSound.downloaded(bytes, sound) }.isSuccess) { path.setLastModified(System.currentTimeMillis()); return bytes }
            }
        }
        return null
    }
    @Synchronized fun isSaved(account: String, server: String, sound: EmotionSound): Boolean = file(account, server, sound, true).isFile
    @Synchronized fun catalog(account: String, server: String): List<EmotionSound> =
        MsnEmotionSound.builtIns + File(folder(account, server), "saved").listFiles().orEmpty().filter { it.isFile && it.length() in 128..MsnEmotionSound.MAX_BYTES.toLong() }
            .mapNotNull { path -> runCatching { MsnEmotionSound.downloaded(path.readBytes()) }.getOrNull() }.sortedBy { it.name.lowercase() }

    @Synchronized fun put(account: String, server: String, sound: EmotionSound, bytes: ByteArray, save: Boolean = false) {
        MsnEmotionSound.downloaded(bytes, sound)
        val destination = file(account, server, sound, save)
        destination.parentFile!!.mkdirs()
        if (save) {
            val files = destination.parentFile!!.listFiles().orEmpty()
            check(destination.exists() || (files.size < 256 && files.sumOf { it.length() } + bytes.size <= 32L * 1024 * 1024)) { "Your saved sounds library is full." }
        }
        val temp = File(destination.parentFile, destination.name + ".tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(destination) || runCatching { destination.writeBytes(bytes); temp.delete() }.isSuccess) { "The sound could not be saved." }
        if (!save) {
            val files = destination.parentFile!!.listFiles().orEmpty().filter { it.extension == "mp3" }.sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }; var count = files.size
            for (path in files) { if (total <= 32L * 1024 * 1024 && count <= 256) break
                val size = path.length(); if (path.delete()) { total -= size; count-- }
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
}
