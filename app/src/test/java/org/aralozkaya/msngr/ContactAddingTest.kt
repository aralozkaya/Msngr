package org.aralozkaya.msngr

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory

class ContactAddingTest {
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val guid = "bea24e7f-ecc6-4aa6-8e12-1abc6439e423"
    private fun resource(name: String) = javaClass.getResource("/contact-add/$name")!!.readText()

    @Test fun requestMatchesCapturedContactSaveFieldsAndEscapesXml() {
        fun fields(xml: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.byteInputStream())
            return listOf("ApplicationId", "IsMigration", "PartnerScenario", "ManagedGroupRequest", "abId",
                "passportName", "isMessengerUser", "DisplayName", "EnableAllowListManagement")
                .associateWith { doc.getElementsByTagNameNS("*", it).item(0).textContent }
        }
        assertEquals(fields(resource("contact-save.xml")), fields(buildAddContactRequest("ticket", owner, "peer")))
        val escaped = buildAddContactRequest("ticket<&", "a&b@escargot.chat", "Name <&\"' Türkiye")
        assertTrue(escaped.contains("ticket&lt;&amp;"))
        assertEquals("Name <&\"' Türkiye", fields(escaped)["DisplayName"])
        assertFalse(escaped.contains("Mobile"))
        assertFalse(escaped.contains("Category"))
    }

    @Test fun responseRequiresSuccessfulResultAndMapsFaultsWithoutEchoingServerData() {
        assertEquals("11111111-2222-4333-8444-abc000000005", parseAddContactResponse(resource("contact-add-response.xml")))
        val fault = "<Envelope><Fault><faultstring>secret-ticket</faultstring><detail><errorcode>InvalidPassportUser</errorcode></detail></Fault></Envelope>"
        val error = runCatching { parseAddContactResponse(fault) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("No Messenger account"))
        assertFalse(error.message!!.contains("secret-ticket"))
        listOf("<Envelope/>", "<ABContactAddResult><guid>invalid</guid></ABContactAddResult>").forEach {
            assertTrue(runCatching { parseAddContactResponse(it) }.isFailure)
        }
    }

    @Test fun validatesNormalizedAddressSelfDuplicateAndBlockedWithoutNetwork() {
        assertEquals(peer, validateContactAddress("  PEER@Example.Test  ", owner, emptyList()))
        listOf("", "peer", "a@", "a@host", "a@@escargot.chat", "a b@escargot.chat", ".a@escargot.chat",
            "a..b@escargot.chat", owner.uppercase()).forEach {
            assertTrue(it, runCatching { validateContactAddress(it, owner, emptyList()) }.isFailure)
        }
        val contact = Contact(peer, null, peer, Presence.Offline)
        assertTrue(runCatching { validateContactAddress(peer, owner, listOf(contact)) }.isFailure)
        assertTrue(runCatching { validateContactAddress(peer, owner, listOf(contact.copy(isForwardListContact = false, isBlocked = true))) }.isFailure)
        assertEquals(peer, validateContactAddress(peer, owner, listOf(contact.copy(isForwardListContact = false))))
    }

    @Test fun addsSoapThenIncrementalAllowThenForwardAndPreservesEarlyPresence() = runBlocking {
        val transport = AddContactTransport(peer)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var snapshot = AddressBookSnapshot(emptyList(), "Aral")
        var adds = 0
        val client = client(scope, transport, { snapshot }) { host, ticket, email, name, message ->
            assertEquals("contacts-ticket", ticket)
            assertEquals(peer, email)
            assertEquals("Aral", name)
            assertEquals("Custom & <hello> ğüşi 🙂", message)
            assertTrue(host.isNotBlank())
            assertTrue(transport.sent.none { it.payload?.toString(Charsets.UTF_8)?.contains("peer") == true })
            adds++
            snapshot = snapshot.copy(contacts = listOf(Contact(peer, null, peer, Presence.Offline)))
            guid
        }
        try {
            signIn(client)
            client.addContact(" PEER@Example.Test ", "Custom & <hello> ğüşi 🙂")
            assertEquals(1, adds)
            val contact = client.contacts.value.single()
            assertTrue(contact.isForwardListContact)
            assertEquals(Presence.Busy, contact.presence)
            assertEquals("Listener", contact.nickname)
            val incremental = transport.sent.filter { it.verb == "ADL" && it.payload?.toString(Charsets.UTF_8)?.contains("peer") == true }
            assertEquals(listOf(2, 1), incremental.map { Regex("l=\"(\\d+)\"").find(it.payload!!.toString(Charsets.UTF_8))!!.groupValues[1].toInt() })
            assertTrue(incremental.none { it.payload!!.toString(Charsets.UTF_8).startsWith("<ml l=\"1\"") })
            delay(450) // The follow-up address-book refresh must not subscribe it twice.
            assertEquals(2, transport.sent.count { it.verb == "ADL" && it.payload?.toString(Charsets.UTF_8)?.contains("peer") == true })
            assertTrue(runCatching { client.addContact(peer) }.isFailure)
            assertEquals(1, adds)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun soapFailureLeavesRosterAndSubscriptionsUnchanged() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = AddContactTransport(peer)
        val client = client(scope, transport, { AddressBookSnapshot(emptyList()) }) { _, _, _, _, _ -> error("SOAP rejected") }
        try {
            signIn(client)
            val count = transport.sent.size
            assertTrue(runCatching { client.addContact(peer) }.isFailure)
            assertTrue(client.contacts.value.isEmpty())
            assertEquals(count, transport.sent.size)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun lateSoapResponseCannotPublishIntoAnEndedSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val transport = AddContactTransport(peer)
        val client = client(scope, transport, { AddressBookSnapshot(emptyList()) }) { _, _, _, _, _ ->
            entered.complete(Unit); response.await()
        }
        try {
            signIn(client)
            val operation = async { runCatching { client.addContact(peer) } }
            entered.await()
            client.close()
            response.complete(guid)
            assertTrue(operation.await().isFailure)
            assertTrue(client.contacts.value.isEmpty())
            assertTrue(transport.sent.none { it.payload?.toString(Charsets.UTF_8)?.contains("peer") == true })
        } finally { client.close(); scope.cancel() }
    }

    @Test fun rejectedSubscriptionReportsPersistedContactAndRefreshRecoversIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = AddContactTransport(peer).apply { rejectForward = true }
        var snapshot = AddressBookSnapshot(emptyList())
        val client = client(scope, transport, { snapshot }) { _, _, _, _, _ ->
            snapshot = snapshot.copy(contacts = listOf(Contact(peer, null, peer, Presence.Offline)))
            guid
        }
        try {
            signIn(client)
            val error = runCatching { client.addContact(peer) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("contact was saved"))
            transport.rejectForward = false
            withTimeout(3000) { client.contacts.first { it.any { contact -> contact.email == peer && contact.isForwardListContact } } }
            assertTrue(client.contacts.value.single().isForwardListContact)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun transactionTimeoutIsAnErrorAndNotCancellationOfTheForm() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = AddContactTransport(peer).apply { timeoutIncremental = true }
        val client = client(scope, transport, { AddressBookSnapshot(emptyList()) }) { _, _, _, _, _ -> guid }
        try {
            signIn(client)
            val error = runCatching { client.addContact(peer) }.exceptionOrNull()!!
            assertFalse(error is CancellationException)
            assertTrue(error.message!!.contains("contact was saved"))
            assertTrue(client.contacts.value.isEmpty())
        } finally { client.close(); scope.cancel() }
    }

    private fun client(scope: CoroutineScope, transport: MsnpTransport, snapshot: () -> AddressBookSnapshot,
        add: suspend (String, String, String, String, String) -> String) = LiveMsnp18Client(
        scope = scope, transport = transport,
        authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts-ticket", "storage") },
        fetchAddressBook = { _, _, _ -> snapshot() }, fetchStorageProfile = { _, _ -> StorageProfile() },
        addAddressBookContact = add,
        restoreAddressBookContact = { _, _, _ -> },
    )
    private suspend fun signIn(client: LiveMsnp18Client) {
        client.signIn(SignInRequest(owner, "test-only", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
    }
}

private class AddContactTransport(private val peer: String) : MsnpTransport {
    var rejectForward = false
    var timeoutIncremental = false
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    private var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val body = command.payload?.toString(Charsets.UTF_8).orEmpty()
        if (command.verb == "ADL" && body.contains("peer")) {
            if (timeoutIncremental) withTimeout(1) { delay(1000) }
            if (rejectForward && body.contains("l=\"1\"")) {
                incoming.send(MsnpCommand("201", command.transactionId))
                return
            }
        }
        val params = when (command.verb) {
            "VER" -> listOf("MSNP18")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if (command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "policy", "nonce") else listOf("OK")
            "ADL", "RML" -> listOf("OK")
            else -> null
        }
        if (command.verb == "ADL" && command.payload?.toString(Charsets.UTF_8)?.contains("l=\"2\"") == true) {
            incoming.send(MsnpCommand("NLN", parameters = listOf("BSY", peer, "Listener", "0", "0")))
        }
        if (params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
    }
    override suspend fun receive() = incoming.receive()
    override fun close() { incoming.close() }
}
