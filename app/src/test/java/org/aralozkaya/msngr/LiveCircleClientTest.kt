package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.NudgeCooldownException
import org.aralozkaya.msngr.data.NudgeRateLimiter
import org.aralozkaya.msngr.data.MsnpCircles
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.parseCircleEnvelope
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LiveCircleClientTest {
    private val me = "me@example.com"
    private val groupId = "00000000-0000-0000-0009-123456789abc@live.com"
    private val otherGroupId = "00000000-0000-0000-0009-123456789abd@live.com"
    private val group = Contact(groupId, "Test", groupId, Presence.Online, isGroup = true)

    @Test fun groupNudgeFloodIsSilentPerSenderAndKeepsItsHistory() = runBlocking {
        var time = 0L
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Group nudges must not open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            nudgeLimiter = NudgeRateLimiter { time })
        val events = CopyOnWriteArrayList<MessengerEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me, "alice@example.com", "bob@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().presence.isOnline } }
            client.sendNudgeWithResult(groupId).getOrThrow()
            assertTrue(client.sendNudgeWithResult(groupId).exceptionOrNull() is NudgeCooldownException)
            assertEquals(1, transport.sent.count { it.verb == "SDG" })
            var size = 1
            suspend fun receive(sender: String, type: String = "Nudge") {
                transport.incoming.send(MsnpCircles.message(0, groupId, sender, "endpoint", "Marker", type))
                size++
                withTimeout(2_000) { client.messages.first { it[groupId]?.size == size } }
            }
            receive("alice@example.com")
            receive("ALICE@example.com")
            time = 10_999
            receive("alice@example.com")
            receive("bob@example.com") // Alice's stream must not silence Bob.
            time += 11_000
            receive("alice@example.com")
            receive(me)
            receive("bob@example.com", "Text")
            val marker = client.messages.value.getValue(groupId).last().id
            withTimeout(2_000) { while (events.none { it == MessengerEvent.IncomingMessage(groupId, marker) }) yield() }
            assertEquals(7, client.messages.value.getValue(groupId).count { it.isNudge })
            assertEquals(3, events.count { it is MessengerEvent.IncomingNudge })
            assertEquals(6, client.contacts.value.single().unreadCount)
            assertTrue(client.sendNudgeWithResult(groupId).exceptionOrNull() is NudgeCooldownException)
        } finally { collector.cancel(); client.close(); scope.cancel() }
    }

    @Test fun groupOnlyMemberPicturesRefreshInEveryGroupWithoutAddingAContact() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val storageId = "00000000-0000-0000-0000-123456789abc"
        val member = GroupMember("member@example.com", "Member", storageId = storageId)
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Stored member pictures do not need a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(
                group.copy(members = listOf(member)),
                group.copy(id = otherGroupId, email = otherGroupId, members = listOf(member)),
            ), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchDisplayPicture = { url, _ -> requestedUrls += url; byteArrayOf(1, 2, 3) })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            client.refreshContactPicture(member.email)
            assertTrue(requestedUrls.any { it.endsWith("/storage/usertile/$storageId/static") })
            assertEquals(2, client.contacts.value.size)
            assertTrue(client.contacts.value.all { it.isGroup && it.members.single().avatarUri != null })
        } finally { client.close(); scope.cancel() }
    }

    @Test fun groupsAuthenticateAndRouteWithoutSwitchboards() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("A circle must not open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group, group.copy(id = otherGroupId, email = otherGroupId)), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            assertTrue(transport.sent.any { it.verb == "USR" && it.parameters.take(2) == listOf("SHA", "A") })
            val subscriptions = transport.sent.filter { it.verb == "ADL" }
            assertFalse(subscriptions.first().payload!!.toString(Charsets.UTF_8).contains(groupId.substringBefore('@')))
            assertTrue(subscriptions.last().payload!!.toString(Charsets.UTF_8).contains("t=\"9\""))
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2000) { client.contacts.first { it.first().presence == Presence.Online } }
            client.sendMessageWithResult(groupId, "Hello TÃ¼rkiye", null).getOrThrow()
            assertEquals("Hello TÃ¼rkiye", parseCircleEnvelope(transport.sent.last { it.verb == "SDG" }.payload!!)!!.body.toString(Charsets.UTF_8))
            transport.incoming.send(MsnpCircles.message(0, groupId, "alice@example.com", "remote", "Alice's message"))
            withTimeout(2000) { client.messages.first { it[groupId]?.size == 2 } }
            val incoming = client.messages.value[groupId]!!.last()
            assertEquals("alice@example.com", incoming.senderEmail)
            assertEquals(groupId, incoming.contactId)
            assertNull(client.messages.value[otherGroupId])
            transport.incoming.send(MsnpCircles.message(0, otherGroupId, "bob@example.com", "remote", "Other conversation"))
            withTimeout(2000) { client.messages.first { it[otherGroupId]?.size == 1 } }
            assertEquals(2, client.messages.value[groupId]!!.size)
            transport.incoming.send(MsnpCommand("NLN", parameters = listOf("BSY", "1:alice@example.com;via=9:$groupId", "Alice", "0:0", "0")))
            withTimeout(2000) { client.contacts.first { it.first().members.any { member -> member.displayName == "Alice" } } }
            assertEquals(2, client.contacts.value.size)
            client.sendTyping(groupId)
            assertEquals("Typing", parseCircleEnvelope(transport.sent.last().payload!!)!!.headers["message-subtype"])
            client.blockContact(groupId)
            assertTrue(client.contacts.value.first().isBlocked)
            assertTrue(client.sendMessageWithResult(groupId, "Blocked", null).isFailure)
            client.unblockContact(groupId)
            assertFalse(client.contacts.value.first().isBlocked)
            transport.rejectSdg = true
            client.sendMessageWithResult(groupId, "Rejected", null)
            withTimeout(2000) { client.messages.first { it[groupId]!!.last().deliveryError != null } }
            Unit
        } finally { client.close(); scope.cancel() }
    }

    @Test fun availabilityExcludesSelfAndMembershipRefreshesOnlyAfterProtocolEvents() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetches = AtomicInteger()
        val snapshot = AtomicReference(AddressBookSnapshot(listOf(group.copy(members = listOf(
            GroupMember(me), GroupMember("alice@example.com"),
        ))), circleTicket = "signed"))
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("A removed circle must never open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> fetches.incrementAndGet(); snapshot.get() },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me))
            withTimeout(2000) { client.contacts.first { it.single().members.first().presence.isOnline } }
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
            assertTrue(client.sendMessageWithResult(groupId, "Nobody else here", null).isFailure)
            assertTrue(client.sendNudgeWithResult(groupId).isFailure)
            client.sendTyping(groupId)
            assertTrue(transport.sent.none { it.verb == "SDG" })
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2000) { client.contacts.first { it.single().presence.isOnline } }
            transport.incoming.send(MsnpCommand("FLN", parameters = listOf("1:alice@example.com;via=9:$groupId")))
            withTimeout(2000) { client.contacts.first { !it.single().presence.isOnline } }
            transport.incoming.send(MsnpCommand("NLN", parameters = listOf("NLN", "1:$me;via=9:$groupId", "Me", "0", "0")))
            withTimeout(2000) { client.contacts.first { it.single().members.first().displayName == "Me" } }
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
            assertEquals(1, fetches.get())

            val notice = MsnpCommand("NOT", payload = "<NOTIFICATION siteid=\"45705\"><MSG><BODY>&lt;NotificationData&gt;&lt;CircleId&gt;${groupId.substringBefore('@')}&lt;/CircleId&gt;&lt;/NotificationData&gt;</BODY></MSG></NOTIFICATION>".toByteArray())
            snapshot.set(snapshot.get().copy(contacts = listOf(group.copy(nickname = "Renamed", members = listOf(GroupMember(me))))))
            repeat(5) { transport.incoming.send(notice) }
            withTimeout(3000) { client.contacts.first { it.single().displayName == "Renamed" } }
            assertEquals(2, fetches.get())
            assertEquals(listOf(me), client.contacts.value.single().members.map(GroupMember::email))

            // Another session leaves/deletes the group: NOT invalidates our address book.
            snapshot.set(snapshot.get().copy(contacts = emptyList()))
            transport.incoming.send(notice)
            withTimeout(3000) { client.contacts.first(List<Contact>::isEmpty) }
            assertTrue(client.sendMessageWithResult(groupId, "Removed", null).isFailure)
            assertTrue(transport.sent.none { it.verb == "XFR" })

            // A newly accepted group is fetched and subscribed without another login.
            snapshot.set(snapshot.get().copy(contacts = listOf(group.copy(id = otherGroupId, email = otherGroupId))))
            transport.incoming.send(notice)
            withTimeout(3000) { client.contacts.first { it.singleOrNull()?.id == otherGroupId } }
            withTimeout(3000) { while (transport.sent.none { it.verb == "ADL" && it.payload?.toString(Charsets.UTF_8)?.contains(otherGroupId.substringBefore('@')) == true }) delay(10) }
            snapshot.set(snapshot.get().copy(contacts = emptyList()))
            transport.incoming.send(MsnpCommand("RML", 0, payload = "<ml/>".toByteArray()))
            withTimeout(3000) { client.contacts.first(List<Contact>::isEmpty) }
            Unit
        } finally { client.close(); scope.cancel() }
    }

    private fun roster(id: String, vararg emails: String): MsnpCommand {
        val body = "<circle><roster><id>IM</id>" + emails.joinToString("") { "<user><id>1:$it</id></user>" } + "</roster></circle>"
        return MsnpCommand("NFY", parameters = listOf("PUT"), payload =
            "Routing: 1.0\r\nFrom: 9:$id\r\n\r\nReliability: 1.0\r\n\r\nPublication: 1.0\r\nUri: /circle\r\n\r\n$body".toByteArray())
    }
}

private class CircleTransport : MsnpTransport {
    var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    var rejectSdg = false
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val params = when (command.verb) {
            "VER" -> listOf("MSNP18")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if (command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "policy", "nonce") else listOf("OK")
            "ADL", "RML" -> listOf("OK")
            else -> null
        }
        if (params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
        if (command.verb == "SDG" && rejectSdg) incoming.send(MsnpCommand("933", command.transactionId))
    }
    override suspend fun receive() = incoming.receive()
    override fun close() { incoming.close() }
}
