package org.aralozkaya.msngr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessengerMemberships
import org.aralozkaya.msngr.data.MsnpCircles
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.parseMessengerMemberships
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class GroupBlockingTest {
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val id = "00000000-0000-0000-0009-123456789abc@live.com"
    private val group = Contact(id, "Test group", id, Presence.Online, isGroup = true,
        members = listOf(GroupMember(owner), GroupMember(peer, presence = Presence.Online)))
    private val login = SignInRequest(owner, "test-only",
        ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))

    @Test fun groupChangesUseIncrementalCirclePacketsAndRejectOrdinaryContacts() {
        for ((blocked, lists) in listOf(true to 4, false to 3)) {
            val command = MsnpCircles.changeBlocking(7, group, blocked)
            assertEquals("ADL", command.verb)
            assertEquals(7, command.transactionId)
            val bytes = command.payload!!
            val payload = bytes.toString(Charsets.UTF_8)
            assertEquals("<ml><d n=\"live.com\"><c n=\"${id.substringBefore('@')}\" t=\"9\" l=\"$lists\" /></d></ml>", payload)
            assertEquals(listOf(bytes.size.toString()), command.parameters)
        }
        assertTrue(runCatching { MsnpCircles.changeBlocking(8, group.copy(isGroup = false), true) }.isFailure)
    }

    @Test fun blockClearsUnreadAndTypingSuppressesTrafficAndSurvivesRefreshAndLogin() = runBlocking {
        val h = Harness()
        try {
            h.client.signIn(login).getOrThrow()
            h.transport.incoming.send(MsnpCircles.message(0, id, peer, "peer-endpoint", "Before block"))
            h.transport.incoming.send(MsnpCircles.message(0, id, peer, "peer-endpoint", type = "Control"))
            withTimeout(2000) { h.client.typing.first { it[id] == true } }
            h.client.markUnread(id)
            assertEquals(1, h.client.contacts.value.single().unreadCount)
            val beforeWrites = h.transport.sent.size
            h.client.blockContact(id)
            val blocked = h.client.contacts.value.single()
            assertTrue(blocked.isBlocked)
            assertEquals(Presence.Offline, blocked.presence)
            assertEquals(0, blocked.unreadCount)
            assertFalse(blocked.isMarkedUnread)
            assertFalse(h.client.typing.value[id] == true)
            assertTrue(h.persisted.get())
            assertEquals(listOf("ADL"), h.transport.sent.drop(beforeWrites).map { it.verb })
            h.client.blockContact(id)
            assertEquals(1, h.transport.sent.size - beforeWrites)
            h.client.refreshBlockedContacts()
            assertTrue(h.client.contacts.value.single().isBlocked)
            h.transport.incoming.send(MsnpCircles.message(0, id, peer, "peer-endpoint", "Ignored"))
            h.transport.incoming.send(MsnpCircles.message(0, id, peer, "peer-endpoint", type = "Control"))
            h.client.unblockContact(id) // Its acknowledgement follows the ignored frames on the same channel.
            assertEquals(listOf("Before block"), h.client.messages.value.getValue(id).map { it.text })
            assertFalse(h.client.typing.value[id] == true)
            assertFalse(h.client.contacts.value.single().isBlocked)
            assertEquals(Presence.Online, h.client.contacts.value.single().presence)
            h.client.blockContact(id)
            h.client.close()
            h.client.signIn(login).getOrThrow()
            assertTrue(h.client.contacts.value.single().isBlocked)
            assertTrue(h.persisted.get())
            h.client.unblockContact(id)
            h.client.close()
            h.client.signIn(login).getOrThrow()
            assertFalse(h.client.contacts.value.single().isBlocked)
            assertFalse(h.persisted.get())
        } finally { h.close() }
    }

    @Test fun rejectionAndAnEndedSessionCannotApplyLocalGroupBlocking() = runBlocking {
        val h = Harness()
        try {
            h.client.signIn(login).getOrThrow()
            h.client.markUnread(id)
            val before = h.client.contacts.value.single()
            h.transport.reject = true
            assertTrue(runCatching { h.client.blockContact(id) }.isFailure)
            assertEquals(before, h.client.contacts.value.single())
            assertFalse(h.persisted.get())
            h.transport.reject = false
            h.transport.gate = CompletableDeferred()
            val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.client.blockContact(id) } }
            h.client.close()
            h.transport.gate!!.complete(Unit)
            assertTrue(operation.await().isFailure)
            assertTrue(h.client.contacts.value.isEmpty())
        } finally { h.close() }
    }

    @Test fun serverCircleMembershipIsParsedAsAGroupBlock() {
        val xml = """<Envelope><FindMembershipResult><Services><Service><Memberships>
            <Membership><MemberRole>Block</MemberRole><Members><Member>
            <Type>Circle</Type><State>Accepted</State><CircleId>${id.substringBefore('@')}</CircleId>
            </Member></Members></Membership></Memberships></Service></Services></FindMembershipResult></Envelope>"""
        val contact = parseMessengerMemberships(xml, owner).blockedContacts.single()
        assertEquals(id, contact.id)
        assertTrue(contact.isGroup)
        assertTrue(contact.isBlocked)
    }

    private inner class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persisted = AtomicBoolean(false)
        val transport = BlockingCircleTransport { persisted.set(it) }
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Group blocking must never use a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group.copy(isBlocked = persisted.get())), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchMessengerMemberships = { _, _, _ -> MessengerMemberships(
                if (persisted.get()) listOf(group.copy(isBlocked = true)) else emptyList(), emptyList()) })
        fun close() { client.close(); scope.cancel() }
    }
}

private class BlockingCircleTransport(private val persist: (Boolean) -> Unit) : MsnpTransport {
    var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    var reject = false
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val body = command.payload?.toString(Charsets.UTF_8).orEmpty()
        if (command.verb == "ADL" && body.startsWith("<ml>") && body.contains("t=\"9\"")) {
            gate?.await()
            if (reject) { incoming.send(MsnpCommand("913", command.transactionId)); return }
            if (!body.contains("l=\"5\"")) persist(body.contains("l=\"4\""))
        }
        val params = when (command.verb) {
            "VER" -> listOf("MSNP18")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if (command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "policy", "nonce") else listOf("OK")
            "ADL", "RML" -> listOf("OK")
            else -> null
        }
        if (params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
    }
    override suspend fun receive() = incoming.receive()
    override fun close() { incoming.close() }
}
