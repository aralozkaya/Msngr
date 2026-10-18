package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.data.MsnpStorageService
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.Assert.*
import org.junit.Test

class AnimatedDisplayPictureTest {
    private fun gif() = requireNotNull(javaClass.getResourceAsStream("/animated-profile/two-frame.gif"))
        .use { it.readBytes() }

    @Test fun readsGifCanvasAndFrameTiming() {
        val info = requireNotNull(GifDisplayPicture.parse(gif()))
        assertEquals(2, info.width)
        assertEquals(2, info.height)
        assertEquals(listOf(100, 200), info.frameDelaysMillis)
        assertEquals(300, info.durationMillis)
    }

    @Test fun rejectsTruncatedAndOversizedAnimations() {
        val bytes = gif()
        for (length in bytes.indices) assertNull("length=$length", GifDisplayPicture.parse(bytes.copyOf(length)))
        assertNull(GifDisplayPicture.parse(bytes.copyOf(GifDisplayPicture.MAX_BYTES + 1)))
        assertNull(GifDisplayPicture.parse(bytes.copyOf().also {
            it[6] = 0xff.toByte(); it[7] = 0xff.toByte()
            it[8] = 0xff.toByte(); it[9] = 0xff.toByte()
        }))
    }

    @Test fun roamingStorageKeepsGifMimeTypeAndExactBytes() {
        val bytes = gif()
        val requests = listOf(
            MsnpStorageService.buildCreateDocumentRequest("ticket", "123!106", bytes),
            MsnpStorageService.buildUpdateDocumentRequest("ticket", "123!205", bytes),
        )
        for (xml in requests) {
            assertTrue(xml.contains("<MimeType>image/gif</MimeType>"))
            assertTrue(xml.contains("<Data>${MsnBase64.encode(bytes)}</Data>"))
        }
    }

    @Test fun gifTransfersUnchangedThroughBothP2pVersions() = runBlocking {
        for (endpointAware in listOf(false, true)) {
            val bytes = gif()
            val picture = MsnDisplayPicture.create("sender@example.com", bytes)
            assertEquals(picture, MsnDisplayPicture.parse(picture.toEncodedValue()))
            var received: ByteArray? = null
            lateinit var sender: MsnpP2pSession
            lateinit var receiver: MsnpP2pSession
            val senderEndpoint = if (endpointAware) "11111111-1111-1111-1111-111111111111" else null
            val receiverEndpoint = if (endpointAware) "22222222-2222-2222-2222-222222222222" else null
            sender = MsnpP2pSession("sender@example.com", "receiver@example.com",
                sendPacket = { receiver.handle(it) },
                localPicture = { LocalDisplayPicture(picture, bytes) }, onDisplayPicture = {},
                localEndpointId = senderEndpoint.orEmpty(), remoteEndpointId = receiverEndpoint)
            receiver = MsnpP2pSession("receiver@example.com", "sender@example.com",
                sendPacket = { sender.handle(it) }, localPicture = { null },
                onDisplayPicture = { received = it },
                localEndpointId = receiverEndpoint.orEmpty(), remoteEndpointId = senderEndpoint)
            receiver.requestDisplayPicture(picture)
            assertArrayEquals(bytes, received)
        }
    }
}
