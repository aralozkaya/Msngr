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
import org.aralozkaya.msngr.data.ContactRequest
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessengerMemberships
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.buildAddContactRequest
import org.aralozkaya.msngr.data.buildMembershipChangeRequest
import org.aralozkaya.msngr.data.mergeMessengerMemberships
import org.aralozkaya.msngr.data.parseMembershipChangeResponse
import org.aralozkaya.msngr.data.parseMessengerMemberships
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory

class ContactRequestsTest {
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val request = ContactRequest(peer, "Pending/11111111-2222-4333-8444-abc000000016")
    private val login = SignInRequest(owner, "test-only",
        ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
    private fun fixture(name: String) = javaClass.getResource("/contact-requests/$name")!!.readText()

    @Test fun offlineMembershipsLoadTheCapturedPendingRoleRatherThanTreatingReverseAsARequest() {
        val membership = parseMessengerMemberships(fixture("offline-memberships.xml"), owner.uppercase())
        assertEquals(listOf(request), membership.requests)
        assertTrue(membership.blockedContacts.isEmpty())
        assertTrue(parseMessengerMemberships(fixture("offline-memberships.xml").replace("Pending</", "Reverse</"), owner).requests.isEmpty())
    }

    @Test fun ignoresDeletedSelfBlockedCircleAndOtherServiceRequestsAndDeduplicatesEmails() {
        fun member(email: String, extra: String = "", type: String = "Passport") =
            "<Member><Type>$type</Type><State>Accepted</State><MembershipId>Pending/id</MembershipId><PassportName>$email</PassportName>$extra</Member>"
        val pending = member(peer.uppercase()) + member(peer) + member(owner) + member(peer, "<Deleted>true</Deleted>") + member(peer, type = "Circle")
        val xml = """<Envelope><FindMembershipResult><Services>
            <Service><Info><Handle><Type>Messenger</Type></Handle></Info><Memberships>
              <Membership><MemberRole>Pending</MemberRole><Members>$pending</Members></Membership>
            </Memberships></Service>
            <Service><Info><Handle><Type>SocialNetwork</Type></Handle></Info><Memberships>
              <Membership><MemberRole>Pending</MemberRole><Members>${member(owner)}</Members></Membership>
            </Memberships></Service>
            </Services></FindMembershipResult></Envelope>"""
        assertEquals(1, parseMessengerMemberships(xml, owner).requests.size)
        val blocked = xml.replace("<Services>", "<Membership><MemberRole>Block</MemberRole><Members>${member(peer)}</Members></Membership><Services>")
        assertTrue(parseMessengerMemberships(blocked, owner).requests.isEmpty())
    }

    @Test fun membershipWritesMatchTheNativeContactMsgrApiFieldsAndEscapeOpaqueIdentifiers() {
        fun fields(xml: String, tags: List<String>): List<String> {
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.byteInputStream())
            return tags.map { document.getElementsByTagNameNS("*", it).item(0)?.textContent.orEmpty() }
        }
        val tags = listOf("ApplicationId", "IsMigration", "PartnerScenario", "ManagedGroupRequest", "MemberRole", "Type", "State", "MembershipId")
        assertEquals(fields(fixture("delete-pending.xml"), tags), fields(buildMembershipChangeRequest("ticket", peer,
            "Pending", true, request.membershipId), tags))
        val reverseTags = tags - "MembershipId" + "PassportName"
        // The older native capture is the opposite direction between the same two accounts.
        assertEquals(fields(fixture("acknowledge-reverse.xml"), reverseTags),
            fields(buildMembershipChangeRequest("ticket", owner, "Reverse", false), reverseTags))
        val escaped = buildMembershipChangeRequest("<&", peer, "Pending", true, "id<&\"'")
        assertEquals(listOf("<&", "id<&\"'"), fields(escaped, listOf("TicketToken", "MembershipId")))
        assertEquals(listOf("ContactMsgrAPI"), fields(buildAddContactRequest("ticket", peer, "Aral", "ContactMsgrAPI"), listOf("PartnerScenario")))
        val fault = "<Envelope><Fault><faultstring>secret-ticket</faultstring></Fault></Envelope>"
        val error = runCatching { parseMembershipChangeResponse(fault, "DeleteMember") }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret-ticket"))
        assertTrue(runCatching { parseMembershipChangeResponse("<Envelope/>", "AddMember") }.isFailure)
    }

    @Test fun explicitPendingMembershipOverridesARetainedMessengerAddressBookRow() {
        val stored = Contact(peer, "Listener", peer, Presence.Offline)
        val book = AddressBookSnapshot(listOf(stored), contactStorageIds = mapOf(peer to GUID))
        val memberships = parseMessengerMemberships(fixture("offline-memberships.xml"), owner)
        val merged = mergeMessengerMemberships(book, memberships)
        assertEquals(listOf(request), merged.contactRequests)
        assertFalse(merged.contacts.single().isForwardListContact)
        assertEquals("Listener", merged.contacts.single().nickname)
        assertEquals(GUID, merged.contactStorageIds[peer])
        assertTrue(mergeMessengerMemberships(book, MessengerMemberships(emptyList(), emptyList()))
            .contacts.single().isForwardListContact)
    }

    @Test fun offlineSignInPublishesRequestAndAcceptanceAddsContactBeforeClearingPending() = runBlocking {
        val h = Harness()
        try {
            h.signIn()
            assertEquals(listOf(request), h.client.contactRequests.value)
            h.client.respondToContactRequest(peer, true)
            assertEquals(listOf("add", "restore", "Allow", "Reverse", "remove:Pending"), h.calls)
            assertEquals(listOf("ADL:2", "ADL:1"), h.transport.membershipWrites())
            assertTrue(h.client.contactRequests.value.isEmpty())
            assertTrue(h.client.contacts.value.single().isForwardListContact)
            assertEquals(Presence.Busy, h.client.contacts.value.single().presence)
        } finally { h.close() }
    }

    @Test fun declineWithoutBlockingDoesNotAddAContactOrSendAllowBlockOrForwardUpdates() = runBlocking {
        val h = Harness()
        try {
            h.signIn(); h.client.respondToContactRequest(peer, false)
            assertEquals(listOf("Reverse", "remove:Pending"), h.calls)
            assertTrue(h.transport.membershipWrites().isEmpty())
            assertTrue(h.client.contacts.value.isEmpty())
            assertTrue(h.client.contactRequests.value.isEmpty())
        } finally { h.close() }
    }

    @Test fun declineAndBlockPersistsBlockWithoutForwardMembership() = runBlocking {
        val h = Harness()
        try {
            h.signIn(); h.client.respondToContactRequest(peer, false, true)
            assertEquals(listOf("remove:Allow", "Block", "Reverse", "remove:Pending"), h.calls)
            assertEquals(listOf("RML:2", "ADL:4"), h.transport.membershipWrites())
            assertTrue(h.client.contacts.value.single().isBlocked)
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            assertTrue(h.client.contactRequests.value.isEmpty())
        } finally { h.close() }
    }

    @Test fun failedMembershipLookupRetainsRequestsAndDoesNotMutateServer() = runBlocking {
        val h = Harness()
        try {
            h.signIn(); h.failLookup = true
            assertTrue(runCatching { h.client.refreshContactRequests() }.isFailure)
            assertTrue(runCatching { h.client.respondToContactRequest(peer, true) }.isFailure)
            assertEquals(listOf(request), h.client.contactRequests.value)
            assertTrue(h.calls.isEmpty())
        } finally { h.close() }
    }

    @Test fun blockedRefreshReadsServerBlocksAndUnblocksWithoutWritingMemberships() = runBlocking {
        val h = Harness()
        try {
            h.pending = emptyList()
            h.saved = Contact(peer, "Saved name", peer, Presence.Offline, avatarUri = "data:picture")
            h.signIn()
            val outsider = Contact("outside@example.test", "Outside", "outside@example.test", Presence.Offline,
                isBlocked = true, isForwardListContact = false)
            h.blocked = listOf(h.saved!!.copy(isBlocked = true, isForwardListContact = false), outsider)
            val writesBefore = h.transport.sent.size
            h.client.refreshBlockedContacts()
            assertEquals(setOf(peer, outsider.id), h.client.contacts.value.filter(Contact::isBlocked).map(Contact::id).toSet())
            assertTrue(h.client.contacts.value.single { it.id == peer }.isForwardListContact)
            assertEquals("data:picture", h.client.contacts.value.single { it.id == peer }.avatarUri)
            h.blocked = emptyList()
            h.client.refreshBlockedContacts()
            assertEquals(listOf(peer), h.client.contacts.value.map(Contact::id))
            assertFalse(h.client.contacts.value.single().isBlocked)
            assertTrue(h.calls.isEmpty())
            assertTrue(h.transport.sent.drop(writesBefore).none { it.verb in setOf("ADL", "RML") })
        } finally { h.close() }
    }

    @Test fun blockedRefreshFailureAndAnEndedSessionCannotReplaceTheDisplayedList() = runBlocking {
        val h = Harness()
        try {
            h.signIn()
            h.blocked = listOf(Contact(peer, "Blocked", peer, Presence.Offline,
                isBlocked = true, isForwardListContact = false))
            h.client.refreshBlockedContacts()
            val before = h.client.contacts.value
            h.failLookup = true
            assertTrue(runCatching { h.client.refreshBlockedContacts() }.isFailure)
            assertEquals(before, h.client.contacts.value)
            h.failLookup = false
            h.lookupGate = CompletableDeferred()
            val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.client.refreshBlockedContacts() } }
            h.client.close()
            h.lookupGate!!.complete(Unit)
            assertTrue(operation.await().isFailure)
            assertTrue(h.client.contacts.value.isEmpty())
            assertTrue(h.calls.isEmpty())
        } finally { h.close() }
    }

    @Test fun withdrawnRequestIsRemovedLocallyWithoutAnyMutation() = runBlocking {
        val h = Harness()
        try {
            h.signIn(); h.pending = emptyList()
            assertTrue(runCatching { h.client.respondToContactRequest(peer, true) }.isFailure)
            assertTrue(h.client.contactRequests.value.isEmpty())
            assertTrue(h.calls.isEmpty())
        } finally { h.close() }
    }

    @Test fun failedSaveKeepsRequestAnswerableAndRetryDoesNotDuplicateSuccessfulContactSave() = runBlocking {
        val h = Harness()
        try {
            h.signIn(); h.failSave = true
            assertTrue(runCatching { h.client.respondToContactRequest(peer, true) }.isFailure)
            assertEquals(listOf(request), h.client.contactRequests.value)
            assertTrue(h.calls.none { it == "remove:Pending" })
            h.failSave = false
            h.client.respondToContactRequest(peer, true)
            assertTrue(h.client.contactRequests.value.isEmpty())
        } finally { h.close() }
    }

    @Test fun anotherSessionCannotReceiveAnOldMembershipResponse() = runBlocking {
        val h = Harness()
        try {
            h.signIn()
            h.lookupGate = CompletableDeferred()
            val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.client.respondToContactRequest(peer, false, true) } }
            h.client.close(); h.lookupGate!!.complete(Unit)
            assertTrue(operation.await().isFailure)
            assertTrue(h.calls.isEmpty())
            assertTrue(h.client.contactRequests.value.isEmpty())
            assertTrue(h.client.contacts.value.isEmpty())
        } finally { h.close() }
    }

    @Test fun mockRequestsArePacketFreeAndRepositoryInboxIsScopedToSuccessfulLogin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = MockMsnp18Client(signInDelayMillis = 0, contactRequestDelayMillis = 0)
        val repository = MessengerRepository(client, scope)
        try {
            assertNull(repository.contactRequestInbox.value.accountId)
            repository.signIn(login).getOrThrow()
            val inbox = withTimeout(3000) { repository.contactRequestInbox.first { it.requests.isNotEmpty() } }
            assertEquals(AccountIdentity.forServer(owner, login.serverConfig, true).storageId, inbox.accountId)
            repository.respondToContactRequest(peer, false, true).getOrThrow()
            withTimeout(3000) { repository.contactRequestInbox.first { it.requests.isEmpty() } }
            assertTrue(repository.contacts.value.single { it.id == peer }.isBlocked)
            repository.reconnect().getOrThrow()
            assertTrue(repository.contactRequests.value.isEmpty())
            repository.logout()
            withTimeout(3000) { repository.contactRequestInbox.first { it.accountId == null } }
            Unit
        } finally { repository.logout(); scope.cancel() }
    }

    @Test fun delayedMockDecisionCannotModifyAReplacementAccount() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, contactRequestDelayMillis = 100)
        try {
            client.signIn(login).getOrThrow()
            val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.respondToContactRequest(peer, true) } }
            client.close(); client.signIn(login.copy(email = peer)).getOrThrow()
            assertTrue(operation.await().isFailure)
            assertTrue(client.contactRequests.value.isEmpty())
            assertTrue(client.contacts.value.none { it.id == peer })
        } finally { client.close() }
    }

    private inner class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RequestTransport(peer)
        val calls = CopyOnWriteArrayList<String>()
        var pending = listOf(request)
        var blocked = emptyList<Contact>()
        var saved: Contact? = null
        var failLookup = false
        var failSave = false
        var lookupGate: CompletableDeferred<Unit>? = null
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts-ticket", "storage") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOfNotNull(saved), "Aral",
                contactStorageIds = if (saved == null) emptyMap() else mapOf(peer to GUID), contactRequests = pending) },
            fetchMessengerMemberships = { _, _, email ->
                assertEquals(owner, email); lookupGate?.await()
                if (failLookup) error("lookup failed")
                MessengerMemberships(blocked, pending)
            },
            acceptAddressBookContact = { _, _, email, _ ->
                assertEquals(peer, email); calls += "add"
                if (failSave) error("save failed")
                saved = Contact(peer, null, peer, Presence.Offline); GUID
            },
            restoreAddressBookContact = { _, _, guid -> assertEquals(GUID, guid); calls += "restore" },
            changeMembership = { _, _, email, role, remove, id ->
                assertEquals(peer, email); calls += (if (remove) "remove:" else "") + role
                if (role == "Pending") { assertEquals(request.membershipId, id); pending = emptyList() }
            })
        suspend fun signIn() { client.signIn(login).getOrThrow() }
        fun close() { client.close(); scope.cancel() }
    }

    companion object { private const val GUID = "11111111-2222-4333-8444-abc000000016" }
}

private class RequestTransport(private val peer: String) : MsnpTransport {
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    private var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
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
        if (command.verb == "ADL" && command.payload?.toString(Charsets.UTF_8)?.contains("l=\"2\"") == true)
            incoming.send(MsnpCommand("NLN", parameters = listOf("BSY", peer, "Listener", "0", "0")))
        if (params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
    }
    fun membershipWrites() = sent.filter { it.verb in setOf("ADL", "RML") &&
        it.payload?.toString(Charsets.UTF_8)?.contains("peer") == true }.map {
        it.verb + ":" + Regex("l=\"(\\d+)\"").find(it.payload!!.toString(Charsets.UTF_8))!!.groupValues[1]
    }
    override suspend fun receive() = incoming.receive()
    override fun close() { incoming.close() }
}
