package org.aralozkaya.msngr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.data.MsnpSwitchboardSession
import org.aralozkaya.msngr.data.SwitchboardEvent
import org.aralozkaya.msngr.data.SwitchboardInvitation
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MsnpSwitchboardTest {
    @Test fun otherOwnEndpointsCanFetchPicturesAndPacketsForAnotherEndpointAreIgnored() = runBlocking {
        for (conversationContact in listOf(signedInEmail, "")) {
            val remoteId = "11111111-1111-1111-1111-111111111111"
            val bytes = mockDisplayPictureBytes()
            val picture = MsnDisplayPicture.create(signedInEmail, bytes)
            val received = CompletableDeferred<ByteArray>()
            val events = CopyOnWriteArrayList<SwitchboardEvent>()
            lateinit var peer: MsnpP2pSession
            val transport = ScriptedSwitchboardTransport(signedInEmail, signedInEmail, p2pSink = { peer.handle(it) })
            val session = MsnpSwitchboardSession(signedInEmail, machineGuid, conversationContact,
                SwitchboardInvitation("mock.example", 1864, "key", "42", signedInEmail), transport,
                CoroutineScope(SupervisorJob() + Dispatchers.Default), events::add,
                localPicture = { LocalDisplayPicture(picture, bytes) }, remoteEndpointId = remoteId,
                handshakeTimeoutMillis = 1_000)
            var destination = "$signedInEmail;{22222222-2222-2222-2222-222222222222}"
            peer = MsnpP2pSession(signedInEmail, signedInEmail,
                sendPacket = { transport.deliver(MsnpCommand("MSG", parameters = listOf("$signedInEmail;{$remoteId}"),
                    payload = Msnp18Protocol.p2pPayload(destination, it, "$signedInEmail;{$remoteId}"))) },
                localPicture = { null }, onDisplayPicture = { received.complete(it) },
                localEndpointId = remoteId, remoteEndpointId = machineGuid)
            try {
                session.connect()
                peer.requestDisplayPicture(picture)
                // Reaching the following command proves the preceding packet was handled.
                val barrier = MsnpCommand("SYN")
                transport.deliver(barrier)
                withTimeout(1_000) { while (barrier !in transport.received) kotlinx.coroutines.yield() }
                assertTrue(transport.sent.none { it.verb == "MSG" })
                destination = "$signedInEmail;{$machineGuid}"
                // A fresh request avoids the remote's pending-request deduplication.
                val source = "$signedInEmail;{$remoteId}"
                peer = MsnpP2pSession(signedInEmail, signedInEmail,
                    sendPacket = { transport.deliver(MsnpCommand("MSG", parameters = listOf(source),
                        payload = Msnp18Protocol.p2pPayload(destination, it, source))) },
                    localPicture = { null }, onDisplayPicture = { received.complete(it) },
                    localEndpointId = remoteId, remoteEndpointId = machineGuid)
                transport.deliver(MsnpCommand("MSG", parameters = listOf(source), payload = Msnp18Protocol.textPayload("own echo")))
                transport.deliver(MsnpCommand("MSG", parameters = listOf(source), payload = Msnp18Protocol.nudgePayload()))
                peer.requestDisplayPicture(picture)
                assertArrayEquals(bytes, withTimeout(2_000) { received.await() })
                assertTrue(events.none { it is SwitchboardEvent.Text || it is SwitchboardEvent.Nudge })
                if (conversationContact.isEmpty()) {
                    transport.deliver(MsnpCommand("JOI", parameters = listOf(contactEmail, "Contact")))
                    withTimeout(1_000) { while (events.none { it is SwitchboardEvent.Nudge }) kotlinx.coroutines.yield() }
                    assertTrue(events.any { it == SwitchboardEvent.ContactResolved })
                    assertEquals(listOf(SwitchboardEvent.Text(signedInEmail, "own echo")), events.filterIsInstance<SwitchboardEvent.Text>())
                }
            } finally { session.close() }
        }
    }

    private val signedInEmail = "mock-user@example.com"
    private val contactEmail = MockServerData.contacts().first().email
    private val machineGuid = "00000000-0000-0000-0000-000000000000"

    @Test
    fun outgoingSessionAuthenticatesInvitesAndSendsAllChatPayloads() = runBlocking {
        val transport = ScriptedSwitchboardTransport(signedInEmail, contactEmail)
        val session = outgoingSession(transport)

        session.connect()
        session.sendText("hello from mock transport")
        session.sendTyping()
        session.sendNudge()

        assertEquals("mock-switchboard.example", transport.connectedHost)
        assertEquals(1864, transport.connectedPort)
        assertEquals(0, transport.readTimeoutMillis)
        assertEquals(listOf("USR", "CAL", "CAL"), transport.sent.take(3).map { it.verb })
        assertEquals(
            "$signedInEmail;{$machineGuid}",
            transport.sent.first().parameters.first(),
        )
        assertEquals(
            listOf(signedInEmail, contactEmail),
            transport.sent.filter { it.verb == "CAL" }.map { it.parameters.first() },
        )

        val messages = transport.sent.filter { it.verb == "MSG" }
        assertEquals(3, messages.size)
        assertEquals(
            SwitchboardMimeMessage.Text("hello from mock transport"),
            parseSwitchboardMime(requireNotNull(messages[0].payload)),
        )
        assertEquals(
            SwitchboardMimeMessage.Typing,
            parseSwitchboardMime(requireNotNull(messages[1].payload)),
        )
        assertEquals(
            SwitchboardMimeMessage.Nudge,
            parseSwitchboardMime(requireNotNull(messages[2].payload)),
        )
        assertEquals("A", messages[0].parameters.first())
        assertEquals("U", messages[1].parameters.first())
        assertEquals("N", messages[2].parameters.first())
        session.close()
    }

    @Test
    fun incomingSessionAnswersAndDeliversTextTypingAndNudgeEvents() = runBlocking {
        val transport = ScriptedSwitchboardTransport(signedInEmail, contactEmail)
        val events = CopyOnWriteArrayList<SwitchboardEvent>()
        val receivedAll = CompletableDeferred<Unit>()
        val session = MsnpSwitchboardSession(
            signedInEmail = signedInEmail,
            machineGuid = machineGuid,
            contactEmail = contactEmail,
            invitation = SwitchboardInvitation(
                host = "mock-switchboard.example",
                port = 1864,
                authKey = "mock-incoming-key",
                sessionId = "4242",
                inviterEmail = contactEmail,
            ),
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            eventSink = { event ->
                events += event
                if (events.count { it !is SwitchboardEvent.Closed } == 3) receivedAll.complete(Unit)
            },
            handshakeTimeoutMillis = 1_000,
        )

        session.connect()
        transport.deliver(message(Msnp18Protocol.textPayload("mock incoming message")))
        transport.deliver(message(Msnp18Protocol.typingPayload(contactEmail)))
        transport.deliver(message(Msnp18Protocol.nudgePayload()))
        withTimeout(1_000) { receivedAll.await() }

        val answer = transport.sent.first()
        assertEquals("ANS", answer.verb)
        assertEquals("4242", answer.parameters.last())
        assertTrue(events.contains(SwitchboardEvent.Text(contactEmail, "mock incoming message")))
        assertTrue(events.contains(SwitchboardEvent.Typing(contactEmail)))
        assertTrue(events.contains(SwitchboardEvent.Nudge(contactEmail)))
        session.close()
    }

    @Test
    fun parsesMockMimeFixturesCaseInsensitivelyAndRejectsUnknownData() {
        val text = "mime-version: 1.0\r\ncontent-type: text/plain; charset=UTF-8\r\n\r\nmock body"
        val unsupported = "MIME-Version: 1.0\r\nContent-Type: application/mock\r\n\r\ndata"

        assertEquals(
            SwitchboardMimeMessage.Text("mock body"),
            parseSwitchboardMime(text.toByteArray()),
        )
        assertEquals(
            SwitchboardMimeMessage.Unsupported,
            parseSwitchboardMime(unsupported.toByteArray()),
        )
    }

    @Test
    fun plusFormattingIsNormalizedOutboundAndPreservedInboundByteForByte() = runBlocking {
        val transport = ScriptedSwitchboardTransport(signedInEmail, contactEmail)
        val session = outgoingSession(transport)
        session.connect()

        session.sendText(" [b][c=4]Hello[/c][/b] ")

        val sentText = transport.sent
            .last { it.verb == "MSG" }
            .payload
            ?.let(::parseSwitchboardMime)
        assertEquals(
            SwitchboardMimeMessage.Text("[b][c=4]Hello[/c][/b]"),
            sentText,
        )

        val incoming = "\u0002\u0005\u001F\u0006\u000304,01Styled\u0003\u0006\u001F\u0005\u0002"
        assertEquals(
            SwitchboardMimeMessage.Text(incoming),
            parseSwitchboardMime(Msnp18Protocol.textPayload(incoming)),
        )
        session.close()
    }

    @Test
    fun preservesBinaryP2pPacketsInSwitchboardMimePayloads() {
        val packet = byteArrayOf(0, 1, 2, 0, 0x7f)
        val message = parseSwitchboardMime(
            Msnp18Protocol.p2pPayload(signedInEmail, packet),
        ) as SwitchboardMimeMessage.P2p

        assertEquals(signedInEmail, message.destination)
        assertArrayEquals(packet, message.bytes)
    }

    @Test
    fun servesTheSignedInUsersDisplayPictureThroughTheSwitchboard() = runBlocking {
        val bytes = mockDisplayPictureBytes()
        val picture = MsnDisplayPicture.create(signedInEmail, bytes)
        val received = CompletableDeferred<ByteArray>()
        lateinit var remotePeer: MsnpP2pSession
        val transport = ScriptedSwitchboardTransport(
            signedInEmail = signedInEmail,
            contactEmail = contactEmail,
            p2pSink = { packet -> remotePeer.handle(packet) },
        )
        val session = MsnpSwitchboardSession(
            signedInEmail = signedInEmail,
            machineGuid = machineGuid,
            contactEmail = contactEmail,
            invitation = SwitchboardInvitation(
                host = "mock-switchboard.example",
                port = 1864,
                authKey = "mock-incoming-key",
                sessionId = "4242",
                inviterEmail = contactEmail,
            ),
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            eventSink = {},
            localPicture = { LocalDisplayPicture(picture, bytes) },
            handshakeTimeoutMillis = 1_000,
        )
        session.connect()
        remotePeer = MsnpP2pSession(
            localEmail = contactEmail,
            remoteEmail = signedInEmail,
            sendPacket = { packet ->
                transport.deliver(
                    message(Msnp18Protocol.p2pPayload(signedInEmail, packet)),
                )
            },
            localPicture = { null },
            onDisplayPicture = { received.complete(it) },
        )

        remotePeer.requestDisplayPicture(picture)

        assertArrayEquals(bytes, withTimeout(1_000) { received.await() })
        session.close()
    }

    private fun outgoingSession(transport: MsnpTransport) = MsnpSwitchboardSession(
        signedInEmail = signedInEmail,
        machineGuid = machineGuid,
        contactEmail = contactEmail,
        invitation = SwitchboardInvitation(
            host = "mock-switchboard.example",
            port = 1864,
            authKey = "mock-outgoing-key",
        ),
        transport = transport,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        eventSink = {},
        handshakeTimeoutMillis = 1_000,
    )

    private fun message(payload: ByteArray) = MsnpCommand(
        verb = "MSG",
        parameters = listOf(contactEmail, "NLN", payload.size.toString()),
        payload = payload,
    )
}

private class ScriptedSwitchboardTransport(
    private val signedInEmail: String,
    private val contactEmail: String,
    private val p2pSink: suspend (ByteArray) -> Unit = {},
) : MsnpTransport {
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    val received = CopyOnWriteArrayList<MsnpCommand>()
    private val incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    var connectedHost: String? = null
        private set
    var connectedPort: Int? = null
        private set
    var readTimeoutMillis: Int? = null
        private set

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) {
        connectedHost = host
        connectedPort = port
    }

    override suspend fun send(command: MsnpCommand) {
        sent += command
        when (command.verb) {
            "USR" -> deliver(
                MsnpCommand("USR", command.transactionId, listOf("OK", signedInEmail, "Mock User")),
            )
            "ANS" -> {
                deliver(
                    MsnpCommand(
                        "IRO",
                        command.transactionId,
                        listOf("1", "1", contactEmail, "NLN", "0:0"),
                    ),
                )
                deliver(MsnpCommand("ANS", command.transactionId, listOf("OK")))
            }
            "CAL" -> {
                val invitee = command.parameters.first()
                deliver(MsnpCommand("CAL", command.transactionId, listOf("RINGING", "4242")))
                deliver(MsnpCommand("JOI", parameters = listOf(invitee, "NLN", "0:0")))
            }
            "MSG" -> {
                val message = parseSwitchboardMime(requireNotNull(command.payload))
                if (message is SwitchboardMimeMessage.P2p) p2pSink(message.bytes)
                if (command.parameters.firstOrNull() == "D") {
                    deliver(MsnpCommand("ACK", command.transactionId))
                }
            }
        }
    }

    override suspend fun receive(): MsnpCommand = incoming.receive().also { received += it }

    override fun setReadTimeout(timeoutMillis: Int) {
        readTimeoutMillis = timeoutMillis
    }

    fun deliver(command: MsnpCommand) {
        check(incoming.trySend(command).isSuccess)
    }

    override fun close() {
        incoming.close()
    }
}
