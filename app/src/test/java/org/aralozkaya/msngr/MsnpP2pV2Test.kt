package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.data.P2pV2Packet
import org.aralozkaya.msngr.data.SlpMessage
import org.aralozkaya.msngr.data.field
import org.aralozkaya.msngr.data.sipMessage
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsnpP2pV2Test {
    @Test
    fun switchboardHandoverKeepsSequenceNumbersAndAnIncompletePicture() = runBlocking {
        val bytes = ByteArray(8_000) { (it * 17).toByte() }
        val picture = MsnDisplayPicture.create("sender@example.com", bytes)
        val originalBridge = mutableListOf<ByteArray>()
        val nextBridge = mutableListOf<ByteArray>()
        val response = mutableListOf<ByteArray>()
        var received: ByteArray? = null
        val receiver = MsnpP2pSession(
            localEmail = "receiver@example.com", remoteEmail = "sender@example.com",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = originalBridge::add, localPicture = { null },
            onDisplayPicture = { received = it },
        )
        val sender = MsnpP2pSession(
            localEmail = "sender@example.com", remoteEmail = "receiver@example.com",
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111",
            sendPacket = response::add, localPicture = { LocalDisplayPicture(picture, bytes) },
            onDisplayPicture = {},
        )
        receiver.requestDisplayPicture(picture)
        val invitation = requireNotNull(P2pV2Packet.parse(originalBridge.single()))
        sender.handle(originalBridge.single())
        val firstData = response.indexOfFirst { requireNotNull(P2pV2Packet.parse(it)).tfCombination == 5 }
        response.take(firstData + 1).forEach { receiver.handle(it) }
        receiver.bindPacketSender(nextBridge::add)
        response.drop(firstData + 1).forEach { receiver.handle(it) }
        assertArrayEquals(bytes, received)

        val lastSent = requireNotNull(P2pV2Packet.parse(originalBridge.last()))
        receiver.requestDisplayPicture(MsnDisplayPicture.create("sender@example.com", byteArrayOf(1, 2, 3)))
        val next = requireNotNull(P2pV2Packet.parse(nextBridge.last()))
        assertEquals(lastSent.identifier + lastSent.messageSize, next.identifier)
        assertTrue(next.identifier >= invitation.identifier + invitation.messageSize)
    }

    @Test
    fun v2PacketRoundTripsBigEndianHeadersAndDataRemaining() {
        val encoded = P2pV2Packet(
            operationCode = 3,
            identifier = 0xf102_0304L,
            sessionId = 0xa102_0304L,
            tfCombination = 5,
            packageNumber = 0xbeef,
            dataRemaining = 1234,
            body = byteArrayOf(1, 2, 3, 4),
            footer = 1,
            peerInfo = true,
        ).encode()

        assertTrue(P2pV2Packet.looksLike(encoded))
        val parsed = requireNotNull(P2pV2Packet.parse(encoded))
        assertEquals(3, parsed.operationCode)
        assertEquals(0xf102_0304L, parsed.identifier)
        assertEquals(0xa102_0304L, parsed.sessionId)
        assertEquals(5, parsed.tfCombination)
        assertEquals(0xbeef, parsed.packageNumber)
        assertEquals(1234, parsed.dataRemaining)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parsed.body)
        assertEquals(1, parsed.footer)
    }

    @Test
    fun endpointAwareSessionsTransferAndValidateAChunkedPicture() = runBlocking {
        val bytes = ByteArray(14_962) { ((it * 31) and 0xff).toByte() }
        val picture = MsnDisplayPicture.create("sender@example.com", bytes)
        var received: ByteArray? = null
        lateinit var receiver: MsnpP2pSession
        lateinit var sender: MsnpP2pSession
        receiver = MsnpP2pSession(
            localEmail = "receiver@example.com",
            remoteEmail = "sender@example.com",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = { sender.handle(it) },
            localPicture = { null },
            onDisplayPicture = { received = it },
        )
        sender = MsnpP2pSession(
            localEmail = "sender@example.com",
            remoteEmail = "receiver@example.com",
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111",
            sendPacket = {
                assertEquals(0, requireNotNull(P2pV2Packet.parse(it)).footer)
                receiver.handle(it)
            },
            localPicture = { LocalDisplayPicture(picture, bytes) },
            onDisplayPicture = {},
        )

        receiver.requestDisplayPicture(picture)

        assertArrayEquals(bytes, received)
    }

    @Test
    fun endpointAwareSessionDeduplicatesAnOutstandingPictureRequest() = runBlocking {
        val bytes = mockDisplayPictureBytes()
        val picture = MsnDisplayPicture.create("sender@example.com", bytes)
        var packetsSent = 0
        val receiver = MsnpP2pSession(
            localEmail = "receiver@example.com",
            remoteEmail = "sender@example.com",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = { packetsSent++ },
            localPicture = { null },
            onDisplayPicture = {},
        )

        receiver.requestDisplayPicture(picture)
        val packetsAfterFirstRequest = packetsSent
        receiver.requestDisplayPicture(picture)

        assertTrue(packetsAfterFirstRequest > 0)
        assertEquals(packetsAfterFirstRequest, packetsSent)
    }

    @Test
    fun displayPictureInvitationRequestsImmediateObjectTransfer() = runBlocking {
        val packets = mutableListOf<ByteArray>()
        val bytes = mockDisplayPictureBytes()
        val picture = MsnDisplayPicture.create("sender@example.com", bytes)
        val receiver = MsnpP2pSession(
            localEmail = "receiver@example.com",
            remoteEmail = "sender@example.com",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = packets::add,
            localPicture = { null },
            onDisplayPicture = {},
        )

        receiver.requestDisplayPicture(picture)

        val invitationPacket = requireNotNull(P2pV2Packet.parse(packets.single()))
        val invitation = requireNotNull(SlpMessage.parse(invitationPacket.body))
        assertEquals("18", invitation.body.field("RequestFlags"))
    }

    @Test
    fun endpointAwareSessionDeclinesDirectListeningAndKeepsTheBridgeAlive() = runBlocking {
        val responses = mutableListOf<ByteArray>()
        val receiver = MsnpP2pSession(
            localEmail = "receiver@example.com",
            remoteEmail = "sender@example.com",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = responses::add,
            localPicture = { null },
            onDisplayPicture = {},
        )
        val request = sipMessage(
            firstLine = "INVITE MSNMSGR:receiver@example.com MSNSLP/1.0",
            to = "receiver@example.com",
            from = "sender@example.com",
            branch = "33333333-3333-3333-3333-333333333333",
            callId = "44444444-4444-4444-4444-444444444444",
            cseq = 0,
            contentType = "application/x-msnmsgr-transreqbody",
            content = "Bridges: TCPv1 SBBridge\r\n" +
                "Hashed-Nonce: {55555555-5555-5555-5555-555555555555}\r\n\r\n",
        )
        receiver.handle(
            P2pV2Packet(
                identifier = 1,
                sessionId = 0,
                tfCombination = 1,
                body = request,
            ).encode(),
        )

        val responsePacket = requireNotNull(P2pV2Packet.parse(responses.single()))
        val response = requireNotNull(SlpMessage.parse(responsePacket.body))
        assertEquals("MSNSLP/1.0 200 OK", response.firstLine)
        assertEquals("application/x-msnmsgr-transrespbody", response.contentType)
        assertEquals("TCPv1", response.body.field("Bridge"))
        assertEquals("false", response.body.field("Listening"))
        assertEquals(
            "{00000000-0000-0000-0000-000000000000}",
            response.body.field("Hashed-Nonce"),
        )
    }
}
