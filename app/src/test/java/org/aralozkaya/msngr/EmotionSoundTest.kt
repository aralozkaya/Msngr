package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EmotionSoundTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/emotion-sounds/$name")!!.use { it.readBytes() }
    private fun customId(): String {
        val audio = fixture("hello.mp3")
        val crc = java.util.zip.CRC32().apply { update(audio) }.value
        return "%04X%08X".format(java.util.Locale.ROOT, (audio.size + 128).and(0xffff), crc)
    }
    private fun custom(id: String = customId(), name: String = "Test sound"): ByteArray {
        val tag = ByteArray(128)
        "TAG".toByteArray().copyInto(tag)
        name.toByteArray().copyInto(tag, 3)
        "Created in Messenger Plus!".toByteArray().copyInto(tag, 33)
        tag[93] = 5
        "MsgPlus! - #$id".toByteArray().copyInto(tag, 97)
        return fixture("hello.mp3") + tag
    }
    @Test fun nativeMessagesDecodeAtEverySplitPreservingSoundTextAndFontHeaders() {
        for (name in listOf("hello-in", "hello-out", "custom-in", "custom-out", "styled-in")) {
            val bytes = fixture("wlm-$name.txt")
            val original = MsnpFrameDecoder().feed(bytes).single()
            val text = parseSwitchboardMime(original.payload!!) as SwitchboardMimeMessage.Text
            assertNotNull(MsnEmotionSound.parse(text.value))
            for (split in 0..bytes.size) {
                val decoder = MsnpFrameDecoder()
                val frames = decoder.feed(bytes.copyOfRange(0, split)) + decoder.feed(bytes.copyOfRange(split, bytes.size))
                assertEquals(text, parseSwitchboardMime(frames.single().payload!!))
            }
            assertArrayEquals(original.payload, Msnp18Protocol.textPayload(text.value, text.style))
        }
    }
    @Test fun capturedBuiltInColorsAndCustomStylingUseTheExistingPlusRenderer() {
        val hello = MsnEmotionSound.parse("\u0004#\u00037Hello!\u0003")!!
        assertEquals("builtin-35", hello.sound.id)
        assertEquals("Hello!", hello.displayText.messengerPlusPlainText())
        assertEquals(7, MessengerPlusTextCodec.parse(hello.displayText).styleSpans.single().style.foreground?.wireToken?.toInt())
        val styled = MsnEmotionSound.parse("\u0004#[b][c=#FF6600]Styled sound[/c][/b]")!!
        val spans = MessengerPlusTextCodec.parse(styled.displayText)
        assertEquals("Styled sound", spans.text)
        assertTrue(spans.styleSpans.all { it.style.bold && it.style.foreground?.argb == 0xffff6600.toInt() })
    }
    @Test fun allNativeBuiltInCommandsRetainTheirOwnCaptionAndSoundCode() {
        assertEquals(17, MsnEmotionSound.builtIns.size)
        assertEquals(17, MsnEmotionSound.builtIns.map { it.id }.distinct().size)
        for (sound in MsnEmotionSound.builtIns) {
            val source = MsnEmotionSound.message(sound)
            val parsed = MsnEmotionSound.parse(source)!!
            assertEquals(sound, parsed.sound)
            assertEquals(sound.builtInCode, source[1].code)
            assertTrue(parsed.displayText.messengerPlusPlainText().isNotBlank())
        }
    }
    @Test fun customAnnouncementsRetainNativeMinimumPaddingAndAllowStyledNames() {
        val sound = EmotionSound("012345abcdef", "[b]Test[/b]")
        val source = MsnEmotionSound.message(sound)
        assertTrue(source.length >= 64)
        assertTrue(source.endsWith(" ".repeat(40)))
        val parsed = MsnEmotionSound.parse(source)!!
        assertEquals("012345ABCDEF", parsed.sound.id)
        assertEquals("Sent a sound: Test", parsed.displayText.messengerPlusPlainText())
        assertTrue(MessengerPlusTextCodec.parse(parsed.displayText).styleSpans.any { it.style.bold })
        assertNull(MsnEmotionSound.parse(source.replace("012345ABCDEF", "../bad-IDxxx")))
        assertNull(MsnEmotionSound.parse("a $source"))
        assertNull(MsnEmotionSound.parse("\u0004\u001dtext"))
        assertNull(MsnEmotionSound.parse("\u0004Ptext"))
        assertNull(MsnEmotionSound.parse("[Messenger Plus! Sound] - Data{012345ABCDEF}"))
    }
    @Test fun groupAndOfflineTextFramingKeepSoundAndNativeEffects() {
        for (source in listOf(MsnEmotionSound.message(MsnEmotionSound.builtIns.first()), MsnEmotionSound.message(EmotionSound("012345ABCDEF", "Custom")))) {
            val style = NativeTextStyle(bold = true, italic = true, colorRgb = 0x778899)
            val circle = parseCircleEnvelope(MsnpCircles.message(1, "group@live.com", "me@example.test", "endpoint", source, style = style).payload!!)!!
            assertEquals(source, circle.body.toString(Charsets.UTF_8))
            assertEquals(style, NativeTextStyle.fromFormatHeader(circle.headers["x-mms-im-format"]))
            assertTrue(MsnpOfflineMessages.send(1, "peer@example.test", source, style).payload!!.toString(Charsets.UTF_8).endsWith(source))
        }
    }
    @Test fun downloadedMp3MetadataIsBoundedAndMatchedToTheRequestedId() {
        assertEquals(MsnEmotionSound.builtIns.first(), MsnEmotionSound.downloaded(fixture("hello.mp3"), MsnEmotionSound.builtIns.first()))
        assertEquals(EmotionSound(customId(), "Test sound", 5), MsnEmotionSound.downloaded(custom()))
        assertTrue(runCatching { MsnEmotionSound.downloaded(custom(), EmotionSound("FFFFFFFFFFFF", "Other")) }.isFailure)
        assertTrue(runCatching { MsnEmotionSound.downloaded(custom().apply { this[2000] = (this[2000].toInt() xor 1).toByte() }) }.isFailure)
        assertTrue(runCatching { MsnEmotionSound.downloaded(custom(id = "012345ABCDEF")) }.isFailure)
        for (invalid in listOf(byteArrayOf(48), "<html>Error</html>".toByteArray(), ByteArray(MsnEmotionSound.MAX_BYTES + 1), ByteArray(512)))
            assertTrue(runCatching { MsnEmotionSound.downloaded(invalid) }.isFailure)
    }
    @Test fun serviceUsesOnlyConfiguredBaseAndTheCapturedDownloadAndRandomEndpoints() = runBlocking {
        val requests = mutableListOf<String>()
        val id = customId()
        val service = EmotionSoundService("https://sounds.example.test:8443/custom/", EmotionSoundHttp { url ->
            requests += url; if ("builtin?" in url) fixture("hello.mp3") else custom()
        })
        service.download(MsnEmotionSound.builtIns.first())
        service.download(EmotionSound(id, "Test"))
        service.random(5, 7)
        assertEquals(listOf("https://sounds.example.test:8443/custom/builtin?code=35", "https://sounds.example.test:8443/custom/get?hash=$id", "https://sounds.example.test:8443/custom/random?catId=5&lngId=7"), requests)
        assertTrue(service.popular().isEmpty())
        assertEquals(3, requests.size)
        assertTrue(runCatching { service.random(0) }.isFailure)
        assertTrue(runCatching { service.download(EmotionSound("../../evil", "Bad")) }.isFailure)
    }
    @Test fun publicCatalogSearchUsesPublishedIdsAndDecodesNames() = runBlocking {
        val service = EmotionSoundService(MsnEmotionSound.DEFAULT_SERVER, EmotionSoundHttp { url ->
            assertEquals("https://escargot.chat/sounds/", url)
            "<i onclick=\"playAudio('012345abcdef');\" class=\"volume\"></i> It&#039;s &amp; sound</th>".toByteArray()
        })
        assertEquals(listOf(EmotionSound("012345ABCDEF", "It's & sound")), service.popular())
    }
    @Test fun soundUrlsValidateBeforeTheyAreStoredOrUsed() {
        assertEquals("http://localhost:8080/esnd/snd/", MsnEmotionSound.server(" http://localhost:8080/esnd/snd "))
        for (bad in listOf("", "sounds.example.test", "file:///tmp/", "https://user:pass@example.test/", "https://example.test/?token=a", "https://example.test/#x", "https://example.test:0/"))
            assertNull(bad, MsnEmotionSound.server(bad))
        assertTrue(ServerPresets.presetFor(ProviderOptions.ESCARGOT).isValid())
        assertEquals(MsnEmotionSound.DEFAULT_SERVER, ServerPresets.resolveForProvider(ProviderOptions.CROSSTALK, ServerOverrides()).emotionSoundServer)
    }
    @Test fun libraryPersistsByAccountAndServiceAndRemovalKeepsReplayCache() {
        val directory = Files.createTempDirectory("msngr-emotion-library").toFile()
        try {
            val store = EmotionSoundStore(directory); val bytes = custom(); val sound = MsnEmotionSound.downloaded(bytes)
            store.put("account-a", MsnEmotionSound.DEFAULT_SERVER, sound, bytes, true)
            assertTrue(store.isSaved("account-a", MsnEmotionSound.DEFAULT_SERVER, sound))
            assertNull(store.bytes("account-b", MsnEmotionSound.DEFAULT_SERVER, sound))
            assertNull(store.bytes("account-a", "https://other.example.test/", sound))
            val restart = EmotionSoundStore(directory)
            assertEquals(sound, restart.catalog("account-a", MsnEmotionSound.DEFAULT_SERVER).last())
            restart.remove("account-a", MsnEmotionSound.DEFAULT_SERVER, sound)
            assertFalse(restart.isSaved("account-a", MsnEmotionSound.DEFAULT_SERVER, sound))
            assertArrayEquals(bytes, restart.bytes("account-a", MsnEmotionSound.DEFAULT_SERVER, sound))
        } finally { directory.deleteRecursively() }
    }
    @Test fun historyRetainsOriginalStyledTextButNeverRestoresAutoplay() {
        val directory = Files.createTempDirectory("msngr-emotion-history").toFile()
        try {
            val source = MsnEmotionSound.message(EmotionSound("012345ABCDEF", "[b]Sound[/b]"))
            val message = ChatMessage(12, "group@live.com", MessageAuthor.Contact, source, 15, senderEmail = "peer@example.test", nativeStyle = NativeTextStyle(italic = true), autoPlayEmotionSound = true)
            FileConversationHistoryStore(directory).apply { selectAccount("account"); merge(mapOf(message.contactId to listOf(message))) }
            val restart = FileConversationHistoryStore(directory).apply { selectAccount("account"); loadLatest(message.contactId) }
            val restored = restart.messages.value.getValue(message.contactId).single()
            assertEquals(message.copy(autoPlayEmotionSound = false), restored)
            assertEquals(message.emotionSound, restored.emotionSound)
        } finally { directory.deleteRecursively() }
    }
    @Test fun mockTextSendPreservesCustomSoundPadding() = runBlocking {
        val client = MockMsnp18Client()
        try {
            val source = MsnEmotionSound.message(EmotionSound("012345ABCDEF", "X"))
            client.sendStyledMessage("peer@example.test", source, null, NativeTextStyle(bold = true)).getOrThrow()
            assertEquals(source, client.messages.value.getValue("peer@example.test").first().text)
        } finally { client.close() }
    }
}
