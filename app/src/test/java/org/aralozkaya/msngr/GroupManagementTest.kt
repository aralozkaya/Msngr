package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupInvitation
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.PERSONAL_ADDRESS_BOOK
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.buildCreateGroupRequest
import org.aralozkaya.msngr.data.buildGroupConnectionRequest
import org.aralozkaya.msngr.data.buildLeaveGroupRequest
import org.aralozkaya.msngr.data.ownGroupRole
import org.aralozkaya.msngr.data.parseAddressBook
import org.aralozkaya.msngr.data.parseCreatedGroupContactId
import org.aralozkaya.msngr.data.parseCreatedGroupId
import org.aralozkaya.msngr.data.parseGroupOperationResponse
import org.aralozkaya.msngr.data.validateGroupInvitees
import org.aralozkaya.msngr.data.validateGroupName
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class GroupManagementTest {
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val guid = "00000000-0000-0000-0009-abc000000001"
    private val id = "$guid@live.com"
    private val peerGuid = "11111111-2222-4333-8444-abc000000017"
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("group-management/$name.xml")!!.readText()
    private fun request(email: String = owner) = SignInRequest(email, "mock-only",
        ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))

    @Test fun offlineInvitationsLoadedDuringSignInPublishAnAccountScopedNotificationInbox() = runBlocking<Unit> {
        val invitation = GroupInvitation(id, "Offline group", owner)
        val invitations = MutableStateFlow(emptyList<GroupInvitation>())
        val mock = MockMsnp18Client(signInDelayMillis = 0)
        val client = object : MsnpClient by mock {
            override val groupInvitations = invitations
            override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> =
                mock.signIn(request).onSuccess { invitations.value = listOf(invitation) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = MessengerRepository(client, scope, useMockServer = true)
        try {
            assertNull(repository.groupInvitationInbox.value.accountId)
            val signIn = request(peer)
            repository.signIn(signIn).getOrThrow()
            val inbox = withTimeout(3000) { repository.groupInvitationInbox.first { it.invitations.isNotEmpty() } }
            assertEquals(AccountIdentity.forServer(peer, signIn.serverConfig, true).storageId, inbox.accountId)
            assertEquals(listOf(invitation), inbox.invitations)
            repository.logout()
            withTimeout(3000) { repository.groupInvitationInbox.first { it.accountId == null && it.invitations.isEmpty() } }
        } finally { repository.logout(); scope.cancel() }
    }

    @Test fun capturedCreateAndInviteResponsesRetainServerIdentifiers() {
        assertEquals(guid, parseCreatedGroupId(fixture("CreateCircleResponse-1")))
        assertEquals(peerGuid, parseCreatedGroupContactId(fixture("CreateContactResponse-1")))
        parseGroupOperationResponse(fixture("ManageWLConnectionResponse-1"), "ManageWLConnection")
        parseGroupOperationResponse(fixture("BreakConnectionResponse-1"), "BreakConnection")
    }

    @Test fun onlineAndOfflineCapturedInvitationsStayOutsideAcceptedRoster() {
        for (name in listOf("pending-1", "pending-2")) {
            val snapshot = parseAddressBook(fixture(name), peer)
            val invitation = snapshot.groupInvitations.single()
            assertTrue(invitation.groupName.startsWith("Fixture"))
            assertEquals(owner, invitation.inviterEmail)
            assertEquals("owner", invitation.inviterName)
            assertTrue(snapshot.contacts.none(Contact::isGroup))
            assertFalse(invitation.id.contains("hotmail.com"))
        }
    }

    @Test fun escapedCreateInviteAndLeaveRequestsMatchCapturedOperations() {
        val create = buildCreateGroupRequest("<&ticket>", "Friends & <family>", "Owner & friends")
        assertTrue(create.contains("<PartnerScenario>CircleSave</PartnerScenario>"))
        assertTrue(create.contains("<MembershipAccess>0</MembershipAccess>"))
        assertTrue(create.contains("Friends &amp; &lt;family&gt;"))
        assertTrue(create.contains("&lt;&amp;ticket&gt;"))
        val invite = buildGroupConnectionRequest("ticket", guid, peerGuid, 3)
        assertTrue(invite.contains("<relationshipRole>3</relationshipRole>"))
        val accept = buildGroupConnectionRequest("ticket", PERSONAL_ADDRESS_BOOK, guid, 0)
        val decline = buildGroupConnectionRequest("ticket", PERSONAL_ADDRESS_BOOK, guid, 0, accept = false)
        assertTrue(accept.contains("<action>1</action>"))
        assertTrue(decline.contains("<action>2</action>"))
        assertTrue(buildLeaveGroupRequest("ticket", guid, peerGuid).contains("<deleteContact>true</deleteContact><blockContact>false</blockContact>"))
    }

    @Test fun invalidAndErrorResponsesCannotBecomeSuccessfulGroupMutations() {
        for (xml in listOf("<Envelope/>", "<Envelope><CreateCircleResponse><Id>bad</Id></CreateCircleResponse></Envelope>",
            "<Envelope><Fault>denied</Fault><CreateCircleResponse><Id>$guid</Id></CreateCircleResponse></Envelope>")) {
            assertTrue(runCatching { parseCreatedGroupId(xml) }.isFailure)
        }
        assertTrue(runCatching { parseGroupOperationResponse("<Envelope><ManageWLConnectionResponse><clientErrorData>denied</clientErrorData></ManageWLConnectionResponse></Envelope>", "ManageWLConnection") }.isFailure)
    }

    @Test fun validationDeduplicatesAndRejectsSelfInvalidAddressesAndOverCapacity() {
        assertEquals(listOf(peer), validateGroupInvitees(listOf(" $peer ", peer.uppercase()), owner))
        assertTrue(runCatching { validateGroupInvitees(listOf(owner), owner) }.isFailure)
        assertTrue(runCatching { validateGroupInvitees(listOf("invalid"), owner) }.isFailure)
        assertTrue(runCatching { validateGroupInvitees(listOf(peer), owner, List(20) { GroupMember("$it@example.com") }) }.isFailure)
        assertTrue(runCatching { validateGroupName(" \n ") }.isFailure)
        assertTrue(runCatching { validateGroupName("bad\u0000name") }.isFailure)
    }

    @Test fun createdGroupSurvivesLaggingRosterAndRepeatedInvitesKeepTheSameGroup() = runBlocking {
        val transport = GroupTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = CopyOnWriteArrayList<String>()
        val members = listOf(GroupMember(owner, role = GroupMemberRole.Owner))
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(emptyList(), circleTicket = "signed") },
            createAddressBookGroup = { _, _, _, _ -> calls += "create"; guid },
            fetchAddressBookGroupMembers = { _, _, _ -> members },
            inviteAddressBookGroupMember = { _, _, group, email, message -> calls += "invite:$group:$email:$message" })
        try {
            client.signIn(request()).getOrThrow()
            assertEquals(id, client.createGroup("Empty"))
            client.inviteGroupMembers(id, listOf(peer), "Initial custom message")
            client.inviteGroupMembers(id, listOf(peer), "Initial custom message")
            withTimeout(3000) { while (transport.sent.none { it.verb == "ADL" && it.payload?.toString(Charsets.UTF_8)?.contains(guid) == true }) delay(20) }
            assertEquals(listOf("create", "invite:$guid:$peer:Initial custom message", "invite:$guid:$peer:Initial custom message"), calls.toList())
            assertEquals(GroupMemberRole.Owner, client.contacts.value.single().ownGroupRole(owner))
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
            assertTrue(client.groupInvitations.value.isEmpty())
        } finally { client.close(); scope.cancel() }
    }

    @Test fun ownerGuardUsesCurrentServerRoleAndNonOwnerLeaveRetainsMessages() = runBlocking {
        val transport = GroupTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val group = Contact(id, "Test", id, Presence.Offline, isGroup = true,
            members = listOf(GroupMember(peer, storageId = peerGuid, role = GroupMemberRole.User)))
        val currentRole = AtomicReference(GroupMemberRole.Owner)
        val calls = CopyOnWriteArrayList<String>()
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group), circleTicket = "signed") },
            fetchAddressBookGroupMembers = { _, _, _ -> listOf(GroupMember(peer, storageId = peerGuid, role = currentRole.get())) },
            leaveAddressBookGroup = { _, _, groupGuid, memberGuid -> calls += "$groupGuid:$memberGuid" })
        try {
            client.signIn(request(peer)).getOrThrow()
            assertTrue(runCatching { client.leaveGroup(id) }.isFailure)
            assertTrue(calls.isEmpty())
            assertEquals(1, client.contacts.value.size)
            currentRole.set(GroupMemberRole.User)
            client.leaveGroup(id)
            assertEquals(listOf("$guid:$peerGuid"), calls.toList())
            assertTrue(client.contacts.value.isEmpty())
        } finally { client.close(); scope.cancel() }
    }

    @Test fun mockGroupOperationsNeverAcceptInvitedPeopleOrAllowOwnerToLeave() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, addContactDelayMillis = 0)
        try {
            client.signIn(request()).getOrThrow()
            val groupId = client.createGroup("Local group")
            client.inviteGroupMembers(groupId, listOf(peer))
            assertEquals(listOf(owner), client.contacts.value.first { it.id == groupId }.members.map(GroupMember::email))
            assertTrue(runCatching { client.leaveGroup(groupId) }.isFailure)
        } finally { client.close() }
    }

    @Test fun loginAndDelayedOnlineInvitationsAuthenticateWithoutAcceptedGroupsAndRemainAnswerableAfterFailure() = runBlocking {
        val transport = GroupTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val invitation = GroupInvitation(id, "Pending group", owner)
        val snapshot = AtomicReference(AddressBookSnapshot(emptyList(), circleTicket = "signed", groupInvitations = listOf(invitation)))
        var fail = true
        val responses = CopyOnWriteArrayList<Boolean>()
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchAddressBook = { _, _, _ -> snapshot.get() },
            respondAddressBookGroupInvitation = { _, _, groupGuid, accept ->
                assertEquals(guid, groupGuid)
                if (fail) error("Temporary failure")
                responses += accept
            })
        try {
            client.signIn(request(peer)).getOrThrow()
            assertEquals(listOf(invitation), client.groupInvitations.value)
            assertTrue(client.contacts.value.isEmpty())
            assertTrue(transport.sent.any { it.verb == "USR" && it.parameters.take(2) == listOf("SHA", "A") })
            assertTrue(runCatching { client.respondToGroupInvitation(id, false) }.isFailure)
            assertEquals(listOf(invitation), client.groupInvitations.value)
            fail = false
            client.respondToGroupInvitation(id, false)
            client.refreshGroupInvitations() // A stale server snapshot must not resurrect a handled invitation.
            assertTrue(client.groupInvitations.value.isEmpty())
            assertEquals(listOf(false), responses.toList())
            snapshot.set(snapshot.get().copy(groupInvitations = emptyList()))
            client.refreshGroupInvitations()
            transport.publish(MsnpCommand("NOT", payload = "<NOTIFICATION siteid=\"45705\"/>".toByteArray()))
            // The server publishes NOT before its membership database flush.
            delay(700)
            snapshot.set(snapshot.get().copy(groupInvitations = listOf(invitation)))
            withTimeout(3000) { client.groupInvitations.first { it == listOf(invitation) } }
            assertTrue(client.contacts.value.isEmpty())
        } finally { client.close(); scope.cancel() }
    }
}

private class GroupTransport : MsnpTransport {
    private var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val params = when(command.verb) {
            "VER" -> listOf("MSNP18")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if(command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "policy", "nonce") else listOf("OK")
            "ADL", "RML" -> listOf("OK")
            else -> null
        }
        if(params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
    }
    override suspend fun receive(): MsnpCommand = incoming.receive()
    suspend fun publish(command: MsnpCommand) { incoming.send(command) }
    override fun close() { incoming.close() }
}
