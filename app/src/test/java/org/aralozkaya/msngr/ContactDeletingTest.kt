package org.aralozkaya.msngr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.buildContactMessengerUpdateRequest
import org.aralozkaya.msngr.data.parseContactUpdateResponse
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory

class ContactDeletingTest {
    @Test fun unknownPresenceDoesNotCreateForwardMembership() = runBlocking {
        val h = DeleteHarness()
        h.book.set(AddressBookSnapshot(emptyList()))
        try {
            h.signIn()
            h.transport.push(MsnpCommand("NLN", parameters = listOf("NLN", DELETE_PEER, "Listener", "0", "0")))
            withTimeout(3000) { h.client.contacts.first { it.any { c -> c.presence.isOnline } } }
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            h.client.addContact(DELETE_PEER)
            assertEquals(1, h.restoreCalls)
            assertTrue(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun addingADeletedContactAbsentFromSnapshotRestoresMessengerFlag() = runBlocking {
        val h = DeleteHarness()
        h.book.set(AddressBookSnapshot(emptyList()))
        try {
            h.signIn()
            h.client.addContact(DELETE_PEER)
            assertEquals(1, h.restoreCalls)
            assertTrue(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun removalMatchesCapturedNativeSoapAndRejectsInvalidOrFaultResponses() {
        fun fields(xml: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.byteInputStream())
            return listOf("ApplicationId", "IsMigration", "PartnerScenario", "ManagedGroupRequest", "abId",
                "contactId", "isMessengerUser", "propertiesChanged")
                .associateWith { doc.getElementsByTagNameNS("*", it).item(0).textContent }
        }
        val captured = javaClass.getResource("/contact-delete/remove-messenger-contact.xml")!!.readText()
        assertEquals(fields(captured), fields(buildContactMessengerUpdateRequest("ticket<&", DELETE_GUID, false)))
        assertTrue(buildContactMessengerUpdateRequest("ticket<&", DELETE_GUID, false).contains("ticket&lt;&amp;"))
        assertTrue(runCatching { buildContactMessengerUpdateRequest("ticket", "<injected>", false) }.isFailure)
        assertEquals("true", fields(buildContactMessengerUpdateRequest("ticket", DELETE_GUID, true))["isMessengerUser"])
        parseContactUpdateResponse("<ABContactUpdateResponse xmlns=\"http://www.msn.com/webservices/AddressBook\"/>")
        listOf("<Envelope/>", "<Fault><faultstring>secret-ticket</faultstring></Fault>").forEach {
            val error = runCatching { parseContactUpdateResponse(it) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("secret-ticket"))
        }
    }

    @Test fun deleteWithoutBlockRemovesOnlyForwardAndLatePresenceCannotRestoreIt() = runBlocking {
        val h = DeleteHarness()
        try {
            h.signIn()
            h.client.deleteContact(DELETE_PEER, false)
            assertEquals(listOf("RML:1"), h.transport.mutations())
            assertEquals(1, h.soapCalls)
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            assertFalse(h.client.contacts.value.single().isBlocked)
            withTimeout(3000) { h.client.contacts.first { it.isEmpty() } }
            h.transport.push(MsnpCommand("NLN", parameters = listOf("NLN", DELETE_PEER, "Listener", "0", "0")))
            withTimeout(3000) { h.client.contacts.first { it.any { c -> c.presence.isOnline } } }
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            assertTrue(h.transport.mutations().none { it.startsWith("ADL:1") })
            h.client.addContact(DELETE_PEER)
            assertEquals(1, h.restoreCalls)
            assertEquals(1, h.client.contacts.value.size)
            assertTrue(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun deleteAndBlockMatchesNativeListOrderAndUnblockingDoesNotReadd() = runBlocking {
        val h = DeleteHarness()
        try {
            h.signIn()
            h.client.deleteContact(DELETE_PEER, true)
            assertEquals(listOf("RML:2", "ADL:4", "RML:1"), h.transport.mutations())
            assertTrue(h.client.contacts.value.single().isBlocked)
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            delay(400) // Include the membership refresh triggered by the successful deletion.
            assertTrue(h.client.contacts.value.single().isBlocked)
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            assertTrue(runCatching { h.client.addContact(DELETE_PEER) }.isFailure)
            h.client.unblockContact(DELETE_PEER)
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            h.client.addContact(DELETE_PEER)
            assertEquals(1, h.client.contacts.value.size)
            assertFalse(h.client.contacts.value.single().isBlocked)
            assertTrue(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun deletingAnAlreadyBlockedContactKeepsItsBlockWithoutDuplicateChanges() = runBlocking {
        val h = DeleteHarness(initiallyBlocked = true)
        try {
            h.signIn()
            h.client.deleteContact(DELETE_PEER, false)
            assertEquals(listOf("RML:1"), h.transport.mutations())
            assertTrue(h.client.contacts.value.single().isBlocked)
            assertFalse(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun soapFailureDoesNotRemoveTheContactAndPartialBlockingIsReported() = runBlocking {
        val h = DeleteHarness().apply { failSoap = true }
        try {
            h.signIn()
            assertTrue(runCatching { h.client.deleteContact(DELETE_PEER, false) }.isFailure)
            assertTrue(h.client.contacts.value.single().isForwardListContact)
            assertTrue(h.transport.mutations().isEmpty())
            val error = runCatching { h.client.deleteContact(DELETE_PEER, true) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("was blocked, but could not be deleted"))
            assertTrue(h.client.contacts.value.single().isBlocked)
            assertTrue(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun rejectedForwardRemovalReportsPersistedDeletionAndRetryIsIdempotent() = runBlocking<Unit> {
        val h = DeleteHarness()
        try {
            h.signIn()
            h.transport.rejectRemoval = true
            val error = runCatching { h.client.deleteContact(DELETE_PEER, false) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("contact was deleted"))
            assertFalse(h.client.contacts.value.single().isForwardListContact)
            h.transport.rejectRemoval = false
            h.client.deleteContact(DELETE_PEER, false)
            assertEquals(1, h.soapCalls)
            assertEquals(listOf("RML:1", "RML:1"), h.transport.mutations())
            withTimeout(3000) { h.client.contacts.first { it.isEmpty() } }
        } finally { h.close() }
    }

    @Test fun transactionTimeoutIsVisibleFailureRatherThanFormCancellation() = runBlocking {
        val h = DeleteHarness()
        try {
            h.signIn()
            h.transport.timeoutRemoval = true
            val error = runCatching { h.client.deleteContact(DELETE_PEER, false) }.exceptionOrNull()!!
            assertFalse(error is CancellationException)
            assertTrue(error.message!!.contains("contact was deleted"))
            assertFalse(h.client.contacts.value.single().isForwardListContact)
        } finally { h.close() }
    }

    @Test fun lateSoapResponseCannotMutateAnEndedSession() = runBlocking {
        val h = DeleteHarness().apply { soapGate = CompletableDeferred() }
        try {
            h.signIn()
            val operation = async { runCatching { h.client.deleteContact(DELETE_PEER, false) } }
            h.soapStarted.await()
            h.client.close()
            h.soapGate!!.complete(Unit)
            assertTrue(operation.await().isFailure)
            assertTrue(h.client.contacts.value.isEmpty())
            assertTrue(h.transport.mutations().isEmpty())
        } finally { h.close() }
    }

    @Test fun groupsAndNonContactsCannotBeDeleted() = runBlocking {
        val h = DeleteHarness()
        h.book.set(h.book.get().copy(contacts = listOf(
            Contact(DELETE_PEER, null, DELETE_PEER, Presence.Offline, isForwardListContact = false))))
        try {
            h.signIn()
            assertTrue(runCatching { h.client.deleteContact(DELETE_PEER, false) }.isFailure)
            assertTrue(runCatching { h.client.deleteContact(DELETE_OWNER, false) }.isFailure)
            assertEquals(0, h.soapCalls)
        } finally { h.close() }
        val group = DeleteHarness()
        group.book.set(group.book.get().copy(contacts = listOf(
            Contact(DELETE_PEER, null, DELETE_PEER, Presence.Offline, isGroup = true)), circleTicket = "circle-ticket"))
        try {
            group.signIn()
            assertTrue(runCatching { group.client.deleteContact(DELETE_PEER, false) }.isFailure)
            assertEquals(0, group.soapCalls)
        } finally { group.close() }
    }
}

private const val DELETE_OWNER = "owner@example.test"
private const val DELETE_PEER = "peer@example.test"
private const val DELETE_GUID = "11111111-2222-4333-8444-abc000000005"

private class DeleteHarness(initiallyBlocked: Boolean = false) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val book = AtomicReference(AddressBookSnapshot(
        listOf(Contact(DELETE_PEER, "Listener", DELETE_PEER, Presence.Offline, isBlocked = initiallyBlocked)),
        contactStorageIds = mapOf(DELETE_PEER to DELETE_GUID)))
    var failSoap = false
    var soapCalls = 0
    var restoreCalls = 0
    val soapStarted = CompletableDeferred<Unit>()
    var soapGate: CompletableDeferred<Unit>? = null
    val transport = DeleteContactTransport { blocked ->
        book.updateAndGet { it.copy(contacts = it.contacts.map { c -> c.copy(isBlocked = blocked) }) }
    }
    val client = LiveMsnp18Client(scope = scope, transport = transport,
        authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts-ticket", "storage") },
        fetchAddressBook = { _, _, _ -> book.get() }, fetchStorageProfile = { _, _ -> StorageProfile() },
        removeAddressBookContact = { _, ticket, guid ->
            assertEquals("contacts-ticket", ticket)
            assertEquals(DELETE_GUID, guid)
            soapCalls++
            soapStarted.complete(Unit)
            soapGate?.await()
            if (failSoap) error("SOAP rejected")
            book.updateAndGet { it.copy(contacts = it.contacts.filter(Contact::isBlocked).map { c -> c.copy(isForwardListContact = false) }) }
        },
        addAddressBookContact = { _, _, _, _, _ ->
            book.updateAndGet { it.copy(contacts = listOf(Contact(DELETE_PEER, "Listener", DELETE_PEER, Presence.Offline))) }
            DELETE_GUID
        },
        restoreAddressBookContact = { _, ticket, guid ->
            assertEquals("contacts-ticket", ticket)
            assertEquals(DELETE_GUID, guid)
            restoreCalls++
        },
    )
    suspend fun signIn() {
        client.signIn(SignInRequest(DELETE_OWNER, "test-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        transport.sent.clear()
    }
    fun close() { client.close(); scope.cancel() }
}

private class DeleteContactTransport(private val updateBlock: (Boolean) -> Unit) : MsnpTransport {
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    var rejectRemoval = false
    var timeoutRemoval = false
    private var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val body = command.payload?.toString(Charsets.UTF_8).orEmpty()
        if (command.verb == "RML" && body.contains("l=\"1\"")) {
            if (timeoutRemoval) withTimeout(1) { delay(1000) }
            if (rejectRemoval) { incoming.send(MsnpCommand("201", command.transactionId)); return }
        }
        if (body.contains("l=\"4\"")) updateBlock(command.verb == "ADL")
        val params = when (command.verb) {
            "VER" -> listOf("MSNP18")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if (command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "policy", "nonce") else listOf("OK")
            "ADL", "RML" -> listOf("OK")
            else -> null
        }
        if (params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
    }
    suspend fun push(command: MsnpCommand) { incoming.send(command) }
    fun mutations() = sent.filter { it.verb in listOf("ADL", "RML") }.map {
        it.verb + ":" + Regex("l=\"(\\d+)\"").find(it.payload!!.toString(Charsets.UTF_8))!!.groupValues[1]
    }
    override suspend fun receive() = incoming.receive()
    override fun close() { incoming.close() }
}
