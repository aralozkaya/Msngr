package org.aralozkaya.msngr

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LiveOfflineMessagesTest {
    private val owner = "peer@example.test"
    private val peer = "owner@example.test"
    private val config = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/offline/$name")).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun client(scope: CoroutineScope, transport: OfflineNotificationTransport, presence: Presence = Presence.Offline,
        service: OfflineMessageService = object : OfflineMessageService {
            override suspend fun metadata(host: String, ticket: String) = emptyList<OfflineMessageMetadata>()
            override suspend fun message(host: String, ticket: String, id: String): String = error("No message")
            override suspend fun delete(host: String, ticket: String, id: String) = Unit
        }, factory: () -> MsnpTransport = { error("Offline messages must not open a switchboard") },
    ) = LiveMsnp18Client(scope = scope, transport = transport, switchboardTransportFactory = factory,
        authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage", "t=offline&p=") },
        fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(Contact(peer, "Sender", peer, presence)), "Recipient") },
        fetchStorageProfile = { _, _ -> StorageProfile(null, null, null, null, null, null) },
        offlineMessageService = service, offlineRetryDelayMillis = 10L,
        initialContactPictureRefreshDelayMillis = 60_000L)

    @Test fun offlineTextUsesUumAndServerRejectionsStayOnTheSameBubble() = runBlocking {
        val job = SupervisorJob()
        val transport = OfflineNotificationTransport(owner)
        val client = client(CoroutineScope(job + Dispatchers.Default), transport)
        try {
            client.signIn(SignInRequest(owner, "test", config)).getOrThrow()
            client.sendMessageWithResult(peer, "Offline çığ (y)").getOrThrow()
            val command = transport.sent.single { it.verb == "UUM" }
            assertEquals(listOf(peer, "1", "1"), command.parameters.take(3))
            assertEquals(command.payload!!.size.toString(), command.parameters.last())
            assertFalse(client.messages.value.getValue(peer).single().isSending)
            client.sendTyping(peer)
            assertTrue(client.sendNudgeWithResult(peer).isFailure)
            assertTrue(client.sendMessageWithResult(peer, "ç".repeat(601)).isFailure)
            assertEquals(1, transport.sent.count { it.verb == "UUM" })
            transport.deliver(MsnpCommand("500", command.transactionId))
            withTimeout(2000) { client.messages.first { it[peer]?.first()?.deliveryError != null } }
            assertEquals(2, client.messages.value.getValue(peer).count { it.author == MessageAuthor.Me })
        } finally { client.close(); job.cancel() }
    }

    @Test fun failedPersistenceAndDeletionRetryWithoutDuplicateUnreadOrEvents() = runBlocking {
        val job = SupervisorJob()
        val transport = OfflineNotificationTransport(owner)
        val metadata = MsnpOfflineMessages.parseMetadata(fixture("wlm-metadata.xml"))
        val operations = CopyOnWriteArrayList<String>()
        val deleteAttempts = AtomicInteger()
        val saved = mutableSetOf<Long>()
        val events = CopyOnWriteArrayList<MessengerEvent.IncomingMessage>()
        val notices = AtomicInteger()
        val service = object : OfflineMessageService {
            @Volatile var deleted = false
            override suspend fun metadata(host: String, ticket: String): List<OfflineMessageMetadata> {
                assertEquals(config.mainServer, host)
                assertEquals("t=offline&p=", ticket)
                return if (deleted) emptyList() else metadata
            }
            override suspend fun message(host: String, ticket: String, id: String): String {
                operations += "fetch"
                return fixture("wlm-get-message.xml")
            }
            override suspend fun delete(host: String, ticket: String, id: String) {
                assertTrue(saved.isNotEmpty())
                operations += "delete"
                if (deleteAttempts.incrementAndGet() == 1) throw IOException("delete interrupted")
                deleted = true
            }
        }
        val client = client(CoroutineScope(job + Dispatchers.Default), transport, service = service)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect {
                if (it is MessengerEvent.IncomingMessage) events += it
                if (it is MessengerEvent.OfflineMessagesReceived) notices.incrementAndGet()
            }
        }
        try {
            client.signIn(SignInRequest(owner, "test", config)).getOrThrow()
            var writes = 0
            client.startOfflineMessageDelivery { message ->
                operations += "persist"
                if (++writes == 1) throw IOException("disk unavailable")
                saved.add(message.id)
            }
            withTimeout(2000) { while (!service.deleted || events.size != 1) delay(10) }
            assertEquals(listOf("fetch", "persist", "fetch", "persist", "delete", "fetch", "persist", "delete"), operations.toList())
            assertEquals(1, client.contacts.value.single().unreadCount)
            assertEquals(1, client.messages.value.getValue(peer).size)
            assertTrue(events.single().isOffline)
            assertEquals(1, notices.get())
            // A failed acknowledgement can redeliver on the next login. Persisted IDs suppress alerts.
            client.close()
            service.deleted = false
            client.signIn(SignInRequest(owner, "test", config)).getOrThrow()
            client.startOfflineMessageDelivery { saved.add(it.id) }
            withTimeout(2000) { while (!service.deleted) delay(10) }
            assertEquals(1, events.size)
            assertEquals(1, notices.get())
            assertEquals(0, client.contacts.value.single().unreadCount)
        } finally { collector.cancel(); client.close(); job.cancel() }
    }

    @Test fun closingDuringHttpFetchNeverPersistsOrAcknowledgesTheOldSession() = runBlocking {
        val job = SupervisorJob()
        val gate = CompletableDeferred<Unit>()
        val fetching = CompletableDeferred<Unit>()
        val operations = CopyOnWriteArrayList<String>()
        val service = object : OfflineMessageService {
            override suspend fun metadata(host: String, ticket: String) = MsnpOfflineMessages.parseMetadata(fixture("wlm-metadata.xml"))
            override suspend fun message(host: String, ticket: String, id: String) = withContext(NonCancellable) {
                fetching.complete(Unit)
                gate.await()
                fixture("wlm-get-message.xml")
            }
            override suspend fun delete(host: String, ticket: String, id: String) { operations += "delete" }
        }
        val client = client(CoroutineScope(job + Dispatchers.Default), OfflineNotificationTransport(owner), service = service)
        try {
            client.signIn(SignInRequest(owner, "test", config)).getOrThrow()
            client.startOfflineMessageDelivery { operations += "persist"; true }
            withTimeout(2000) { fetching.await() }
            client.close()
            gate.complete(Unit)
            job.cancelAndJoin()
            assertTrue(operations.isEmpty())
            assertTrue(client.messages.value.isEmpty())
        } finally { gate.complete(Unit); client.close(); job.cancel() }
    }

    @Test fun multipleNewOfflineMessagesAnnounceTheReceivedBatchOnce() = runBlocking {
        val job = SupervisorJob()
        val first = MsnpOfflineMessages.parseMetadata(fixture("wlm-metadata.xml")).single()
        val metadata = listOf(first, first.copy(id = "2390D51C-619F-4729-814C-F85FCB88203B"))
        val deleted = CopyOnWriteArrayList<String>()
        val notices = AtomicInteger()
        val received = AtomicInteger()
        val service = object : OfflineMessageService {
            override suspend fun metadata(host: String, ticket: String) = metadata.filter { it.id !in deleted }
            override suspend fun message(host: String, ticket: String, id: String) = fixture("wlm-get-message.xml")
            override suspend fun delete(host: String, ticket: String, id: String) { deleted += id }
        }
        val client = client(CoroutineScope(job + Dispatchers.Default), OfflineNotificationTransport(owner), service = service)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect {
                if (it is MessengerEvent.OfflineMessagesReceived) notices.incrementAndGet()
                if (it is MessengerEvent.IncomingMessage) received.incrementAndGet()
            }
        }
        try {
            client.signIn(SignInRequest(owner, "test", config, initialPresence = Presence.Offline)).getOrThrow()
            client.startOfflineMessageDelivery { true }
            withTimeout(2000) { while (deleted.size != 2 || received.get() != 2) delay(10) }
            assertEquals(1, notices.get())
            assertEquals(2, client.contacts.value.single().unreadCount)
            assertEquals(2, client.messages.value.getValue(peer).size)
        } finally { collector.cancel(); client.close(); job.cancel() }
    }

    @Test fun signingInInvisibleDoesNotAnnounceLiveQueuedMessagesAsOffline() = runBlocking {
        assertInvisibleLiveMessagesDoNotAnnounceOffline(Presence.Offline)
    }

    @Test fun switchingToInvisibleDoesNotAnnounceLiveQueuedMessagesAsOffline() = runBlocking {
        assertInvisibleLiveMessagesDoNotAnnounceOffline(Presence.Online)
    }

    private suspend fun assertInvisibleLiveMessagesDoNotAnnounceOffline(initialPresence: Presence) = coroutineScope {
        val job = SupervisorJob()
        val transport = OfflineNotificationTransport(owner)
        val first = MsnpOfflineMessages.parseMetadata(fixture("wlm-metadata.xml")).single()
        val queued = CopyOnWriteArrayList<OfflineMessageMetadata>()
        val deleted = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<MessengerEvent.IncomingMessage>()
        val notices = AtomicInteger()
        val service = object : OfflineMessageService {
            override suspend fun metadata(host: String, ticket: String) = queued.filter { it.id !in deleted }
            override suspend fun message(host: String, ticket: String, id: String) = fixture("wlm-get-message.xml")
            override suspend fun delete(host: String, ticket: String, id: String) { deleted += id }
        }
        val client = client(CoroutineScope(job + Dispatchers.Default), transport, service = service)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect {
                if (it is MessengerEvent.OfflineMessagesReceived) notices.incrementAndGet()
                if (it is MessengerEvent.IncomingMessage) events += it
            }
        }
        try {
            client.signIn(SignInRequest(owner, "test", config, initialPresence = initialPresence)).getOrThrow()
            if (initialPresence != Presence.Offline) client.changePresence(Presence.Offline)
            // OIM metadata rounds send times to seconds, including messages sent just after login.
            val sentAt = System.currentTimeMillis() / 1000 * 1000
            queued += first.copy(sentAtMillis = sentAt)
            client.startOfflineMessageDelivery { true }
            withTimeout(2000) { while (deleted.size != 1 || events.size != 1) delay(10) }
            assertEquals(0, notices.get())

            // Later queue notifications must keep delivering messages without another offline notice.
            queued += first.copy(id = "2390D51C-619F-4729-814C-F85FCB88203B", sentAtMillis = sentAt)
            transport.deliver(MsnpCommand("MSG", payload = (
                "Content-Type: text/x-msmsgsoimnotification\r\n\r\nMail-Data: too-large\r\n"
            ).toByteArray()))
            withTimeout(2000) { while (deleted.size != 2 || events.size != 2) delay(10) }
            assertEquals(0, notices.get())
            assertEquals(2, client.contacts.value.single().unreadCount)
            assertEquals(2, client.messages.value.getValue(peer).size)
            assertTrue(events.all { it.isOffline })
        } finally { collector.cancel(); client.close(); job.cancel() }
    }

    @Test fun aConfirmedOfflineSwitchboardInviteFallsBackBeforeAnyTextIsSubmitted() = runBlocking {
        val job = SupervisorJob()
        val notification = OfflineNotificationTransport(owner)
        val board = OfflineNotificationTransport(owner, offlineInvite = peer)
        val client = client(CoroutineScope(job + Dispatchers.Default), notification, Presence.Online, factory = { board })
        try {
            client.signIn(SignInRequest(owner, "test", config)).getOrThrow()
            client.sendMessageWithResult(peer, "Presence race").getOrThrow()
            assertEquals(1, notification.sent.count { it.verb == "UUM" })
            assertFalse(board.sent.any { it.verb == "MSG" })
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
        } finally { client.close(); job.cancel() }
    }
}

private class OfflineNotificationTransport(private val owner: String, private val offlineInvite: String? = null) : MsnpTransport {
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    private var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val parameters = when (command.verb) {
            "VER" -> listOf("MSNP18", "CVR0")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if (command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "MBI", "nonce") else listOf("OK", owner, "1", "0")
            "ADL", "RML" -> listOf("OK")
            "XFR" -> listOf("SB", "board.example:1863", "CKI", "key")
            "CAL" -> listOf("RINGING", "123")
            else -> return
        }
        if (command.verb == "CAL" && command.parameters.first() == offlineInvite) deliver(MsnpCommand("217", command.transactionId))
        else deliver(MsnpCommand(command.verb, command.transactionId, parameters))
    }
    fun deliver(command: MsnpCommand) { incoming.trySend(command).getOrThrow() }
    override suspend fun receive() = incoming.receive()
    override fun setReadTimeout(timeoutMillis: Int) = Unit
    override fun close() { incoming.close() }
}
