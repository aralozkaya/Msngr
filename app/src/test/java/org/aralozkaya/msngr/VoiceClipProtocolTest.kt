package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.FileConversationHistoryStore
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.data.P2pV2Packet
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.MsnVoiceClip
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class VoiceClipProtocolTest {
    private fun wave() = javaClass.getResourceAsStream("/voice-clips/native-siren.wav")!!.use { it.readBytes() }
    private fun offer(): ByteArray = ("MIME-Version: 1.0\r\nContent-Type: text/x-msnmsgr-datacast\r\n\r\n" +
        javaClass.getResourceAsStream("/voice-clips/native-offer.txt")!!.bufferedReader().readText()).toByteArray()

    @Test fun decodesCapturedNativeOfferAndEntireWaveHeader() {
        val metadata = (parseSwitchboardMime(offer()) as SwitchboardMimeMessage.VoiceClipOffer).metadata
        assertEquals(11, metadata.objectType)
        assertEquals(5_460, metadata.size)
        assertTrue(metadata.matches(wave()))
        assertEquals(2_700, MsnVoiceClip.parse(wave())!!.durationMillis)
        assertEquals(43_200, MsnVoiceClip.inspect(wave())!!.samples)
        assertEquals(5_400, MsnVoiceClip.inspect(wave())!!.frames.size)
        assertNull(MsnDisplayPicture.parse(metadata.toXml()))
        assertEquals(metadata, MsnDisplayPicture.parseObject(metadata.toXml(), 11))
    }

    @Test fun rejectsTruncationInvalidCodecAndUnboundedRiffChunks() {
        val source = wave()
        for (length in source.indices) assertNull("length=$length", MsnVoiceClip.inspect(source.copyOf(length)))
        for (offset in listOf(4, 16, 20, 22, 24, 28, 32, 34, 36, 38, 44, 48, 56)) {
            val invalid = source.copyOf().also { it[offset] = 0xff.toByte(); it[offset + 1] = 0xff.toByte() }
            assertNull("offset=$offset", MsnVoiceClip.inspect(invalid))
        }
        assertNull(MsnVoiceClip.inspect(ByteArray(MsnVoiceClip.MAX_WAV_BYTES + 1)))
    }

    @Test fun acceptsUnknownPaddedRiffChunkWithoutChangingAudio() {
        val source = wave()
        val extra = "JUNK".toByteArray() + byteArrayOf(1, 0, 0, 0, 42, 0)
        val edited = source.copyOfRange(0, 12) + extra + source.copyOfRange(12, source.size)
        ByteBuffer.wrap(edited).order(ByteOrder.LITTLE_ENDIAN).putInt(4, edited.size - 8)
        assertArrayEquals(MsnVoiceClip.inspect(source)!!.frames, MsnVoiceClip.inspect(edited)!!.frames)
    }

    @Test fun rejectsInvalidAndOversizedVoiceAdvertisementsAndPreservesNudges() {
        val text = offer().toString(Charsets.UTF_8)
        for (invalid in listOf(text.replace("Type=\"11\"", "Type=\"3\""),
            text.replace("Size=\"5460\"", "Size=\"2147483647\""), text.replace("SHA1D=\"", "SHA1D=\"bad")))
            assertEquals(SwitchboardMimeMessage.Unsupported, parseSwitchboardMime(invalid.toByteArray()))
        assertEquals(SwitchboardMimeMessage.Nudge, parseSwitchboardMime(Msnp18Protocol.nudgePayload()))
    }

    @Test fun transfersCapturedNativeAudioOverBothP2pVersionsWithoutChangingProfilePictures() = runBlocking {
        for (v2 in listOf(false, true)) {
            val bytes = wave()
            val metadata = (parseSwitchboardMime(offer()) as SwitchboardMimeMessage.VoiceClipOffer).metadata
            var received: ByteArray? = null
            var pictures = 0
            lateinit var owner: MsnpP2pSession
            lateinit var receiver: MsnpP2pSession
            val requesterPackets = mutableListOf<ByteArray>()
            receiver = MsnpP2pSession("receiver@example.test", metadata.creator,
                sendPacket = { requesterPackets += it; owner.handle(it) }, localPicture = { null },
                onDisplayPicture = { pictures++ }, localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                onVoiceClip = { requested, result -> assertEquals(metadata, requested); received = result })
            owner = MsnpP2pSession(metadata.creator, "receiver@example.test", sendPacket = { receiver.handle(it) },
                localPicture = { null }, onDisplayPicture = { pictures++ },
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                localVoiceClip = { hash -> if (hash == metadata.sha1d) LocalDisplayPicture(metadata, bytes) else null })
            receiver.requestVoiceClip(metadata)
            assertArrayEquals("v2=$v2", bytes, received)
            assertEquals(0, pictures)
            val first = requesterPackets.first()
            val slp = if (v2) P2pV2Packet.parse(first)!!.body else first.copyOfRange(48, first.size - 4)
            assertTrue(slp.toString(Charsets.UTF_8).contains("AppID: 20\r\n"))
            assertTrue(requesterPackets.any { it.toString(Charsets.UTF_8).contains("BYE MSNMSGR:") })
        }
    }

    @Test fun doesNotServeAnObjectThatWasNotSharedWithTheRequester() = runBlocking {
        val metadata = (parseSwitchboardMime(offer()) as SwitchboardMimeMessage.VoiceClipOffer).metadata
        val response = mutableListOf<ByteArray>()
        val owner = MsnpP2pSession(metadata.creator, "stranger@example.test", response::add, { null }, {},
            localVoiceClip = { null })
        val requester = MsnpP2pSession("stranger@example.test", metadata.creator, { owner.handle(it) }, { null }, {})
        requester.requestVoiceClip(metadata)
        assertTrue(response.any { it.toString(Charsets.UTF_8).contains("603 Decline") })
        assertFalse(response.any { it.toString(Charsets.US_ASCII).contains("RIFF") })
    }

    @Test fun keepsVoiceHistoryAndTurnsInterruptedDownloadsIntoAnError() {
        val directory = Files.createTempDirectory("msngr-voice-history").toFile()
        try {
            val store = FileConversationHistoryStore(directory)
            store.selectAccount("account@example.test")
            val clip = MsnVoiceClip.parse(wave())!!
            store.merge(mapOf("peer" to listOf(
                ChatMessage(1, "peer", MessageAuthor.Contact, "Voice clip", 1, voiceClip = clip),
                ChatMessage(2, "peer", MessageAuthor.Contact, "Voice clip", 2, isVoiceClipPending = true))))
            val restored = FileConversationHistoryStore(directory)
            restored.selectAccount("account@example.test"); restored.loadLatest("peer")
            val messages = restored.messages.value.getValue("peer")
            assertEquals(clip, messages.first().voiceClip)
            assertTrue(messages.last().isVoiceClip)
            assertFalse(messages.last().isVoiceClipPending)
            assertNotNull(messages.last().voiceClipTransferError)
            restored.clearContact("peer")
            assertTrue(restored.messages.value["peer"].isNullOrEmpty())
        } finally { directory.deleteRecursively() }
    }
}
