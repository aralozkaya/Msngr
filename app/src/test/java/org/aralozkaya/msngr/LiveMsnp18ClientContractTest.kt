package org.aralozkaya.msngr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.CachedContactDisplayPicture
import org.aralozkaya.msngr.data.ContactDisplayPictureCache
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LiveMsnp18ClientContractTest {
    @Test fun ownPictureRefreshFetchesCurrentStorageBytesWithoutUploadingAndCanAdoptTheIncomingObject() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val original = mockDisplayPictureBytes()
        val incoming = requireNotNull(javaClass.getResourceAsStream("/animated-profile/two-frame.gif")).use { it.readBytes() }
        val remote = AtomicReference(original)
        var downloads = 0
        var uploads = 0
        val client = LiveMsnp18Client(transport = notification,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(emptyList()) },
            fetchStorageProfile = { host, ticket ->
                assertEquals(serverConfig.storageServer, host)
                assertEquals("storage", ticket)
                StorageProfile(profileResourceId = "profile", expressionProfileResourceId = "expression",
                    photoResourceId = "photo", displayPictureUrl = "https://example.test/current.gif")
            },
            fetchDisplayPicture = { _, _ -> downloads++; remote.get().copyOf() },
            updateStorageDisplayPicture = { _, _, _, _ -> uploads++ })
        try {
            val signedIn = client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            assertTrue(signedIn.displayPictureFetched)
            remote.set(incoming)
            val fetched = client.fetchOwnDisplayPicture()
            assertTrue(fetched.fetched)
            assertArrayEquals(incoming, fetched.bytes)
            assertEquals(2, downloads)
            assertEquals(0, uploads)
            client.adoptIncomingDisplayPicture(fetched.bytes)
            assertEquals(0, uploads)
            assertTrue(requireNotNull(MsnDisplayPicture.parse(notification.sent.last { it.verb == "CHG" }.parameters[2])).matches(incoming))
        } finally { client.close() }
    }

    @Test fun keepingLocalAfterRemoteChangeUploadsEvenWhenTheAdvertisedLocalObjectHasNotChanged() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val original = mockDisplayPictureBytes()
        val remote = AtomicReference(original)
        var uploads = 0
        val client = LiveMsnp18Client(transport = notification,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(emptyList()) },
            fetchStorageProfile = { _, _ -> StorageProfile(profileResourceId = "profile", photoResourceId = "photo",
                displayPictureUrl = "https://example.test/current.png") },
            fetchDisplayPicture = { _, _ -> remote.get().copyOf() },
            updateStorageDisplayPicture = { _, _, _, bytes -> uploads++; remote.set(bytes.copyOf()) })
        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            remote.set(original + byteArrayOf(1, 2, 3))
            assertTrue(client.fetchOwnDisplayPicture().fetched)
            client.changeDisplayPicture(original)
            assertEquals(1, uploads)
            assertArrayEquals(original, remote.get())
        } finally { client.close() }
    }

    @Test fun ownPictureFetchDistinguishesProfileAndDownloadFailuresFromServerRemoval() = runBlocking {
        val notification = ContractNotificationTransport(email)
        var profileFails = true
        var imageFails = true
        var hasImage = true
        val client = LiveMsnp18Client(transport = notification,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(emptyList()) },
            fetchStorageProfile = { _, _ ->
                if (profileFails) throw IOException("storage offline")
                StorageProfile(displayPictureUrl = if (hasImage) "https://example.test/current.png" else null)
            },
            fetchDisplayPicture = { _, _ -> if (imageFails) null else mockDisplayPictureBytes() })
        try {
            assertFalse(client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow().displayPictureFetched)
            assertFalse(client.fetchOwnDisplayPicture().fetched)
            profileFails = false
            assertFalse(client.fetchOwnDisplayPicture().fetched)
            imageFails = false
            assertTrue(client.fetchOwnDisplayPicture().fetched)
            hasImage = false
            val removed = client.fetchOwnDisplayPicture()
            assertTrue(removed.fetched)
            assertNull(removed.bytes)
        } finally { client.close() }
    }

    private fun sessionClient(notification: ContractNotificationTransport) = LiveMsnp18Client(
        transport = notification,
        endpointName = { "Phone" },
        authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
        fetchAddressBook = { _, _, _ -> AddressBookSnapshot(emptyList()) },
        fetchStorageProfile = { _, _ -> StorageProfile() },
    )

    @Test fun automaticLogoutWaitsForAccountPublicationAndEndsEveryOtherSession() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val client = sessionClient(notification)
        val repository = MessengerRepository(client, signOutOtherSessionsOnSignIn = { true })
        val otherIds = listOf("12345678-90ab-cdef-0123-456789abcdef", "abcdef12-3456-7890-abcd-ef1234567890")
        try {
            val login = async { repository.signIn(SignInRequest(email, password, serverConfig)) }
            withTimeout(1_000) { client.sessions.first { it.any { session -> session.isCurrent } } }
            withTimeout(1_000) { while (notification.sent.none { it.verb == "CHG" }) yield() }
            val own = client.sessions.value.single().id
            fun publish(owner: String, ids: List<String>) = notification.deliver(MsnpCommand("UBX",
                parameters = listOf("1:$owner"), payload = ("<Data>" +
                    (listOf(own) + ids).joinToString("") { "<EndpointData id=\"{$it}\"/>" } + "</Data>").toByteArray()))
            publish("someone@example.com", otherIds)
            delay(50)
            assertFalse(login.isCompleted)
            assertTrue(notification.sent.none { it.verb == "UUN" })
            publish(email, otherIds)
            for ((index, id) in otherIds.withIndex()) {
                withTimeout(1_000) { while (notification.sent.count { it.verb == "UUN" } <= index) yield() }
                val command = notification.sent.last { it.verb == "UUN" }
                assertEquals(listOf("$email;{$id}", "4", "14"), command.parameters)
                notification.deliver(MsnpCommand("UUN", command.transactionId, listOf("OK")))
                assertFalse(login.isCompleted)
                publish(email, otherIds.drop(index + 1))
            }
            withTimeout(1_000) { login.await().getOrThrow() }
            assertTrue(repository.hasSignedInSession)
            assertTrue(client.sessions.value.single().isCurrent)
        } finally { repository.logout() }
    }

    @Test fun automaticLogoutRejectionClosesTheNewConnection() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val client = sessionClient(notification)
        val repository = MessengerRepository(client, signOutOtherSessionsOnSignIn = { true })
        try {
            val login = async { repository.signIn(SignInRequest(email, password, serverConfig)) }
            withTimeout(1_000) { client.sessions.first { it.isNotEmpty() } }
            withTimeout(1_000) { while (notification.sent.none { it.verb == "CHG" }) yield() }
            notification.deliver(MsnpCommand("UBX", parameters = listOf(email),
                payload = """<Data><EndpointData id="{12345678-90ab-cdef-0123-456789abcdef}"/></Data>""".toByteArray()))
            withTimeout(1_000) { while (notification.sent.none { it.verb == "UUN" }) yield() }
            notification.deliver(MsnpCommand("500", notification.sent.last { it.verb == "UUN" }.transactionId))
            assertTrue(withTimeout(1_000) { login.await() }.isFailure)
            assertFalse(repository.hasSignedInSession)
            assertTrue(client.sessions.value.isEmpty())
        } finally { repository.logout() }
    }

    @Test fun targetedLogoutWaitsForDepartureAndKeepsCurrentSession() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val client = sessionClient(notification)
        val other = "12345678-90ab-cdef-0123-456789abcdef"
        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val own = client.sessions.value.single().id
            fun publish(xml: String) = notification.deliver(MsnpCommand("UBX", parameters = listOf("1:$email"),
                payload = xml.toByteArray()))
            publish("""<Data><EndpointData id="{$own}"/><PrivateEndpointData id="{$other}"><EpName>Laptop</EpName></PrivateEndpointData></Data>""")
            withTimeout(1_000) { client.sessions.first { it.size == 2 } }
            val action = async { client.logOutSession(other) }
            withTimeout(1_000) { while (notification.sent.none { it.verb == "UUN" }) yield() }
            val sent = notification.sent.last { it.verb == "UUN" }
            assertEquals(listOf("$email;{$other}", "4", "14"), sent.parameters)
            notification.deliver(MsnpCommand("UUN", sent.transactionId, listOf("OK")))
            delay(30)
            assertFalse(action.isCompleted)
            publish("""<Data><EndpointData id="{$own}"/></Data>""")
            withTimeout(1_000) { action.await() }
            assertTrue(client.sessions.value.single().isCurrent)
        } finally { client.close() }
    }

    @Test fun invisibleLogoutRejectionExplainsWorkaroundAndKeepsBothSessions() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val client = sessionClient(notification)
        val other = "12345678-90ab-cdef-0123-456789abcdef"
        try {
            client.signIn(SignInRequest(email, password, serverConfig, Presence.Offline)).getOrThrow()
            val own = client.sessions.value.single().id
            fun publish(ids: List<String>) = notification.deliver(MsnpCommand("UBX",
                parameters = listOf("1:$email"), payload = ("<Data>" + ids.joinToString("") {
                    "<PrivateEndpointData id=\"{$it}\"><State>HDN</State></PrivateEndpointData>"
                } + "</Data>").toByteArray()))
            publish(listOf(own, other))
            withTimeout(1_000) { client.sessions.first { it.size == 2 } }
            val before = client.sessions.value
            val action = async { runCatching { client.logOutSession(other) } }
            withTimeout(1_000) { while (notification.sent.none { it.verb == "UUN" }) yield() }
            notification.deliver(MsnpCommand("217", notification.sent.last { it.verb == "UUN" }.transactionId))
            val failure = withTimeout(1_000) { action.await() }.exceptionOrNull()
            assertTrue(requireNotNull(failure).message.orEmpty().contains("MSNP 217"))
            assertTrue(failure.message.orEmpty().contains("all sessions appear offline"))
            assertTrue(failure.message.orEmpty().contains("Online and try again"))
            assertEquals(before, client.sessions.value)
            // A rejected logout must not expose an invisible account or silently remove a device.
            assertEquals(listOf("HDN"), notification.sent.filter { it.verb == "CHG" }.map { it.parameters.first() })
            assertEquals(1, notification.sent.count { it.verb == "UUN" })

            client.changePresence(Presence.Online)
            val retry = async { client.logOutSession(other) }
            withTimeout(1_000) { while (notification.sent.count { it.verb == "UUN" } < 2) yield() }
            notification.deliver(MsnpCommand("UUN", notification.sent.last { it.verb == "UUN" }.transactionId, listOf("OK")))
            publish(listOf(own))
            withTimeout(1_000) { retry.await() }
            assertTrue(client.sessions.value.single().isCurrent)
        } finally { client.close() }
    }

    @Test fun remoteLogoutClosesConnectionWithoutReportingConnectionLoss() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val client = sessionClient(notification)
        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first() }
            notification.deliver(MsnpCommand("UBN", parameters = listOf("$email;{12345678-90ab-cdef-0123-456789abcdef}", "8", "4"),
                payload = "gtfo".toByteArray()))
            assertTrue(withTimeout(1_000) { event.await() } is MessengerEvent.SignedOut)
            assertTrue(client.sessions.value.isEmpty())
        } finally { client.close() }
    }

    @Test fun serverLogoutIsTerminalButMaintenanceCanReconnect() = runBlocking {
        for (reason in listOf("OTH", "SSD")) {
            val notification = ContractNotificationTransport(email)
            val client = sessionClient(notification)
            try {
                client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
                val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first() }
                notification.deliver(MsnpCommand("OUT", parameters = listOf(reason)))
                val received = withTimeout(1_000) { event.await() }
                assertTrue(if (reason == "OTH") received is MessengerEvent.SignedOut
                    else received is MessengerEvent.ConnectionLost)
            } finally { client.close() }
        }
    }

    private val email = MockServerData.ACCOUNT_EMAIL
    private val password = MockServerData.ACCOUNT_PASSWORD
    private val contact = MockServerData.contacts().first().let {
        it.copy(id = it.email.lowercase(), unreadCount = 0, presence = Presence.Offline)
    }
    private val serverConfig = ServerPresets.resolveForProvider(
        ProviderOptions.ESCARGOT,
        ServerOverrides(),
    )

    @Test fun initialPresenceIsSilentForBothIlnAndNlnButLaterSignInsAreReported() = runBlocking {
        val h = PresenceHarness()
        try {
            h.signIn()
            h.announce("ILN", Presence.Busy)
            h.announce("NLN", Presence.Online)
            h.completeSync()
            assertTrue(h.presenceEvents.isEmpty())

            h.offline()
            h.announce("NLN", Presence.Online)
            h.announce("NLN", Presence.Online)
            assertEquals(listOf(Presence.Offline, Presence.Online), h.presenceEvents.map { it.presence })
        } finally { h.close() }
    }

    @Test fun reconnectDoesNotRepeatUnchangedOnlineAnnouncements() = runBlocking {
        val h = PresenceHarness()
        try {
            h.signIn()
            h.announce("ILN", Presence.Online)
            h.completeSync()
            for (verb in listOf("NLN", "ILN")) {
                h.disconnect()
                h.signIn()
                h.announce(verb, Presence.Online)
                h.announce(verb, Presence.Online)
                h.completeSync()
                h.announce("NLN", Presence.Online)
            }
            assertTrue(h.presenceEvents.isEmpty())
        } finally { h.close() }
    }

    @Test fun presenceEventsKeepBothStatusesForEveryTransition() = runBlocking {
        for (previous in Presence.entries) {
            for (next in Presence.entries) {
                val h = PresenceHarness()
                try {
                    h.signIn()
                    h.announce("ILN", previous)
                    h.completeSync()
                    if (next == Presence.Offline) h.offline() else h.announce("NLN", next)
                    val expected = if (previous == next) emptyList() else listOf(
                        MessengerEvent.PresenceChanged(contact.id, next, previous),
                    )
                    assertEquals("$previous -> $next", expected, h.presenceEvents)
                } finally { h.close() }
            }
        }
    }

    @Test fun reconnectPresenceEventsKeepTheLastObservedStatus() = runBlocking {
        for (verb in listOf("ILN", "NLN")) {
            for (previous in Presence.entries) {
                val next = if (previous == Presence.Busy) Presence.Away else Presence.Busy
                val h = PresenceHarness()
                try {
                    h.signIn()
                    h.announce("ILN", previous)
                    h.completeSync()
                    h.disconnect()
                    h.signIn()
                    h.announce(verb, next)
                    h.completeSync()
                    assertEquals(
                        listOf(MessengerEvent.PresenceChanged(contact.id, next, previous)),
                        h.presenceEvents,
                    )
                } finally { h.close() }
            }
        }
    }

    @Test fun reconnectReportsOnlyContactsWhoChangedFromOfflineToOnlineDuringTheOutage() = runBlocking {
        for (verb in listOf("ILN", "NLN")) {
            val h = PresenceHarness()
            try {
                h.signIn()
                h.announce("ILN", Presence.Online)
                h.completeSync()
                h.disconnect()
                h.signIn()
                h.announce(verb, Presence.Online)
                h.announce(verb, Presence.Online, h.otherContact.id)
                h.announce("NLN", Presence.Online, h.otherContact.id)
                h.completeSync()
                assertEquals(
                    listOf(MessengerEvent.PresenceChanged(h.otherContact.id, Presence.Online, Presence.Offline)),
                    h.presenceEvents,
                )
            } finally { h.close() }
        }
    }

    @Test fun failedReconnectKeepsPresenceForTheNextAttempt() = runBlocking {
        val h = PresenceHarness()
        try {
            h.signIn()
            h.announce("ILN", Presence.Online)
            h.completeSync()
            h.disconnect()
            h.notification.failNextChg()
            assertTrue(h.client.signIn(SignInRequest(email, password, serverConfig)).isFailure)
            assertTrue(h.client.contacts.value.isEmpty())
            h.signIn()
            h.announce("NLN", Presence.Online)
            h.announce("ILN", Presence.Online, h.otherContact.id)
            h.completeSync()
            assertEquals(
                listOf(MessengerEvent.PresenceChanged(h.otherContact.id, Presence.Online, Presence.Offline)),
                h.presenceEvents,
            )
        } finally { h.close() }
    }

    @Test fun contactAbsentFromRestoredPresenceCanNotifyWhenTheyLaterComeOnline() = runBlocking {
        val h = PresenceHarness()
        try {
            h.signIn()
            h.announce("ILN", Presence.Online)
            h.completeSync()
            h.disconnect()
            h.signIn()
            h.completeSync() // Offline contacts need not receive an explicit FLN.
            h.announce("NLN", Presence.Online)
            assertEquals(listOf(MessengerEvent.PresenceChanged(contact.id, Presence.Online, Presence.Offline)), h.presenceEvents)

            // Keep that observation through another reconnect too.
            h.disconnect()
            h.signIn()
            h.announce("NLN", Presence.Online)
            h.completeSync()
            assertEquals(1, h.presenceEvents.size)
        } finally { h.close() }
    }

    @Test fun reconnectDoesNotAssumeNewRosterContactsWerePreviouslyOffline() = runBlocking {
        val h = PresenceHarness()
        try {
            h.signIn()
            h.completeSync()
            h.disconnect()
            val newContact = contact.copy(id = "new@example.com", email = "new@example.com")
            h.roster = h.roster + newContact
            h.signIn()
            h.announce("NLN", Presence.Online, newContact.id)
            h.completeSync()
            assertTrue(h.presenceEvents.isEmpty())
            h.offline(newContact.id)
            h.announce("NLN", Presence.Online, newContact.id)
            assertEquals(listOf(Presence.Offline, Presence.Online), h.presenceEvents.map { it.presence })
        } finally { h.close() }
    }

    @Test fun logoutRemoteSignOutAndAccountChangesStartWithSilentPresenceAgain() = runBlocking {
        for (reset in listOf("logout", "remote", "account", "server")) {
            val h = PresenceHarness()
            try {
                h.signIn()
                h.completeSync()
                val request = when (reset) {
                    "account" -> SignInRequest("other@example.com", password, serverConfig)
                    "server" -> SignInRequest(email, password, serverConfig.copy(mainServer = "other.example.com"))
                    else -> {
                        if (reset == "remote") h.notification.deliver(MsnpCommand("OUT", parameters = listOf("OTH")))
                        else h.client.close()
                        SignInRequest(email, password, serverConfig)
                    }
                }
                h.client.signIn(request).getOrThrow()
                h.announce("NLN", Presence.Online)
                h.completeSync()
                assertTrue("Initial presence after $reset must be silent", h.presenceEvents.isEmpty())
            } finally { h.close() }
        }
    }

    private inner class PresenceHarness {
        private val job = SupervisorJob()
        // Run received commands and event collection to completion without timing-based waits.
        private val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val notification = ContractNotificationTransport(email)
        val otherContact = contact.copy(id = "other-contact@example.com", email = "other-contact@example.com")
        var roster = listOf(contact, otherContact)
        val client = LiveMsnp18Client(
            scope = scope,
            transport = notification,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(roster) },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            initialContactPictureRefreshDelayMillis = 60_000,
        )
        val presenceEvents = mutableListOf<MessengerEvent.PresenceChanged>()

        init {
            scope.launch { client.events.collect { if (it is MessengerEvent.PresenceChanged) presenceEvents += it } }
        }

        suspend fun signIn() { client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow() }
        fun announce(verb: String, presence: Presence, id: String = contact.id) {
            val parameters = listOf(presence.msnpCode, "1:$id") +
                (if (verb == "ILN") listOf("1") else emptyList()) + listOf("Contact", "0:0", "0")
            notification.deliver(MsnpCommand(verb, parameters = parameters))
        }
        fun offline(id: String = contact.id) {
            notification.deliver(MsnpCommand("FLN", parameters = listOf("1:$id")))
        }
        fun completeSync() {
            notification.deliver(MsnpCommand("MSG", parameters = listOf("Hotmail", "Hotmail"),
                payload = "Content-Type: text/x-msmsgsinitialmdatanotification\r\n\r\nMail-Data: <MD/>".toByteArray()))
        }
        fun disconnect() { notification.disconnect(IOException("Connection interrupted")) }
        fun close() { client.close(); job.cancel() }
    }

    @Test
    fun eachSignInPublishesTheCurrentDeviceName() = runBlocking {
        val notification = ContractNotificationTransport(email)
        var deviceName = "Aral's phone & tablet"
        val client = LiveMsnp18Client(
            transport = notification,
            endpointName = { deviceName },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(emptyList()) },
            fetchStorageProfile = { _, _ -> StorageProfile() },
        )
        fun endpointAnnouncements() = notification.sent
            .mapNotNull { it.payload?.toString(Charsets.UTF_8) }
            .filter { it.contains("<PrivateEndpointData>") }
        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            assertTrue(endpointAnnouncements().single()
                .contains("<EpName>Aral&apos;s phone &amp; tablet</EpName>"))

            client.signIn(SignInRequest("another@example.com", password, serverConfig)).getOrThrow()
            assertEquals(endpointAnnouncements().first(), endpointAnnouncements().last())

            deviceName = "Renamed téléphone"
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val announcements = endpointAnnouncements()
            assertEquals(3, announcements.size)
            assertTrue(announcements.last().contains("<EpName>Renamed téléphone</EpName>"))
        } finally {
            client.close()
        }
    }

    @Test
    fun notificationDisconnectPublishesConnectionLost() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "messenger-ticket",
                    response = "rps-proof",
                    contactsTicket = "t=address-book-ticket&p=",
                    storageTicket = "t=storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(emptyList(), ownerDisplayName = "Server User")
            },
            fetchStorageProfile = { _, _ -> StorageProfile() },
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val lostEvent = async(start = CoroutineStart.UNDISPATCHED) {
                client.events.first { it is MessengerEvent.ConnectionLost }
            }

            notification.disconnect(IOException("Broken pipe"))

            val event = withTimeout(1_000) { lostEvent.await() }
            assertTrue(event is MessengerEvent.ConnectionLost)
            assertEquals("Broken pipe", (event as MessengerEvent.ConnectionLost).reason)
        } finally {
            client.close()
        }
    }

    @Test
    fun failedPictureAdvertisementRetriesWithoutUploadingStorageTwice() = runBlocking {
        val notification = ContractNotificationTransport(email)
        var uploads = 0
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(emptyList(), ownerDisplayName = "Mock Server User")
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            updateStorageDisplayPicture = { _, _, _, _ -> uploads++ },
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val bytes = mockDisplayPictureBytes()
            notification.failNextChg()

            assertTrue(runCatching { client.changeDisplayPicture(bytes) }.isFailure)
            assertEquals(1, uploads)

            client.changeDisplayPicture(bytes)
            assertEquals(1, uploads)
            val advertised = MsnDisplayPicture.parse(
                notification.sent.last { it.verb == "CHG" }.parameters[2],
            )
            assertTrue(requireNotNull(advertised).matches(bytes))
        } finally {
            client.close()
        }
    }

    @Test
    fun removingPictureUpdatesStorageBeforePresenceAndAllowsCreatingANewPicture() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val bytes = mockDisplayPictureBytes()
        var removalFailure: Exception? = IOException("storage rejected removal")
        var removals = 0
        var creations = 0
        var updates = 0
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(emptyList(), ownerDisplayName = "Mock Server User")
            },
            fetchDisplayPicture = { _, _ -> bytes },
            fetchStorageProfile = { _, _ ->
                StorageProfile(
                    profileResourceId = "mock!106",
                    expressionProfileResourceId = "mock!118",
                    photoResourceId = "mock!205",
                    displayPictureUrl = "https://mock-images.example/profile.png",
                )
            },
            updateStorageDisplayPicture = { _, _, _, _ -> updates++ },
            createStorageDisplayPicture = { host, ticket, profileResourceId, image ->
                assertEquals(serverConfig.storageServer, host)
                assertEquals("t=mock-storage-ticket&p=", ticket)
                assertEquals("mock!106", profileResourceId)
                assertArrayEquals(bytes, image)
                creations++
                "mock!206"
            },
            removeStorageDisplayPicture = {
                    host,
                    ticket,
                    expressionProfileResourceId,
                    photoResourceId,
                ->
                assertEquals(serverConfig.storageServer, host)
                assertEquals("t=mock-storage-ticket&p=", ticket)
                assertEquals("mock!118", expressionProfileResourceId)
                assertEquals("mock!205", photoResourceId)
                removals++
                removalFailure?.let { throw it }
            },
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val commandCountBeforeRemoval = notification.sent.size

            assertTrue(runCatching { client.changeDisplayPicture(null) }.isFailure)
            assertEquals(1, removals)
            assertEquals(commandCountBeforeRemoval, notification.sent.size)

            removalFailure = null
            client.changeDisplayPicture(null)
            assertEquals(2, removals)
            assertEquals("0", notification.sent.last { it.verb == "CHG" }.parameters[2])

            client.changeDisplayPicture(bytes)
            assertEquals(1, creations)
            assertEquals(0, updates)
            val advertised = MsnDisplayPicture.parse(
                notification.sent.last { it.verb == "CHG" }.parameters[2],
            )
            assertTrue(requireNotNull(advertised).matches(bytes))
        } finally {
            client.close()
        }
    }

    @Test
    fun staticStorageCannotOverwriteAnAnimatedObjectThatArrivesDuringRefresh() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val gif = requireNotNull(javaClass.getResourceAsStream("/animated-profile/two-frame.gif"))
            .use { it.readBytes() }
        val stalePng = mockDisplayPictureBytes()
        val newPng = stalePng + byteArrayOf(1, 2, 3)
        val storageStarted = CompletableDeferred<Unit>()
        val releaseStorage = CompletableDeferred<Unit>()
        var storageReads = 0
        val client = LiveMsnp18Client(
            transport = notification,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(contact),
                contactStorageIds = mapOf(contact.id to "stored-contact")) },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchDisplayPicture = { url, _ -> when {
                url.endsWith("/small") -> byteArrayOf(1)
                url.endsWith("/static") -> {
                    storageReads++
                    storageStarted.complete(Unit)
                    releaseStorage.await()
                    stalePng
                }
                url.endsWith("animated.gif") -> gif
                url.endsWith("new.png") -> newPng
                else -> null
            } },
            initialContactPictureRefreshDelayMillis = 60_000,
        )
        fun announce(bytes: ByteArray, url: String) = notification.deliver(MsnpCommand("NLN",
            parameters = listOf("NLN", "1:${contact.email}", "Contact", Msnp18Protocol.CLIENT_IDENTITY,
                MsnDisplayPicture.create(contact.email, bytes).copy(url1 = url).toEncodedValue())))
        try {
            client.setAppInForeground(false)
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val refresh = async { client.refreshContactPicture(contact.id) }
            withTimeout(1_000) { storageStarted.await() }
            announce(gif, "https://images.example/animated.gif")
            val animatedUri = withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri?.startsWith("data:image/gif;") == true }
                    .single().avatarUri
            }
            releaseStorage.complete(Unit)
            refresh.await()
            assertEquals(animatedUri, client.contacts.value.single().avatarUri)
            client.refreshContactPicture(contact.id)
            assertEquals(animatedUri, client.contacts.value.single().avatarUri)
            assertEquals(1, storageReads)

            // A newly advertised picture must still replace the GIF normally.
            announce(newPng, "https://images.example/new.png")
            withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri?.startsWith("data:image/png;") == true }
            }
            Unit
        } finally {
            releaseStorage.complete(Unit)
            client.close()
        }
    }

    @Test
    fun staticStorageCannotOverwriteTheVerifiedOriginalPngDuringRefresh() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val original = mockDisplayPictureBytes() + byteArrayOf(1, 2, 3)
        val storageStarted = CompletableDeferred<Unit>()
        val releaseStorage = CompletableDeferred<Unit>()
        val client = LiveMsnp18Client(
            transport = notification,
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "proof", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(contact),
                contactStorageIds = mapOf(contact.id to "stored-contact")) },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchDisplayPicture = { url, _ -> when {
                url.endsWith("/small") -> byteArrayOf(1)
                url.endsWith("/static") -> { storageStarted.complete(Unit); releaseStorage.await(); mockDisplayPictureBytes() }
                url.endsWith("original.png") -> original
                else -> null
            } },
            initialContactPictureRefreshDelayMillis = 60_000,
        )
        try {
            client.setAppInForeground(false)
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val refresh = async { client.refreshContactPicture(contact.id) }
            withTimeout(1_000) { storageStarted.await() }
            notification.deliver(MsnpCommand("NLN", parameters = listOf("NLN", "1:${contact.email}", "Contact",
                Msnp18Protocol.CLIENT_IDENTITY, MsnDisplayPicture.create(contact.email, original)
                    .copy(url1 = "https://images.example/original.png").toEncodedValue())))
            val uri = withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri?.endsWith(MsnBase64.encode(original)) == true }.single().avatarUri
            }
            releaseStorage.complete(Unit); refresh.await()
            assertEquals(uri, client.contacts.value.single().avatarUri)
            client.refreshContactPicture(contact.id)
            assertEquals(uri, client.contacts.value.single().avatarUri)
        } finally { releaseStorage.complete(Unit); client.close() }
    }

    @Test
    fun cachedPictureIsImmediateAndRemoteRelogRefreshStartsImmediately() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val cachedBytes = mockDisplayPictureBytes()
        val updatedBytes = cachedBytes + byteArrayOf(9, 8, 7)
        val cachedPicture = MsnDisplayPicture.create(contact.email, cachedBytes)
        val updatedPicture = MsnDisplayPicture.create(contact.email, updatedBytes)
            .copy(url1 = "https://mock-images.example/updated.png")
        val cache = RecordingContactDisplayPictureCache(
            cached = CachedContactDisplayPicture(cachedPicture, cachedBytes),
        )
        var fetchedUrl: String? = null
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(listOf(contact), ownerDisplayName = "Mock Server User")
            },
            fetchDisplayPicture = { url, _ ->
                fetchedUrl = url
                updatedBytes
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            contactDisplayPictureCache = cache,
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val cachedUri = requireNotNull(client.contacts.value.single().avatarUri)
            assertTrue(cachedUri.startsWith("data:image/png;base64,"))

            notification.deliver(MsnpCommand("FLN", parameters = listOf(contact.email)))
            withTimeout(1_000) {
                client.contacts.first { it.single().presence == Presence.Offline }
            }
            notification.deliver(
                MsnpCommand(
                    "NLN",
                    parameters = listOf(
                        "NLN",
                        "1:${contact.email}",
                        "Mock%20Contact",
                        Msnp18Protocol.CLIENT_IDENTITY,
                        updatedPicture.toEncodedValue(),
                    ),
                ),
            )

            assertEquals(cachedUri, client.contacts.value.single().avatarUri)
            val refreshed = withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri != cachedUri }.single()
            }
            assertEquals("https://mock-images.example/updated.png", fetchedUrl)
            assertTrue(refreshed.avatarUri.orEmpty().startsWith("data:image/png;base64,"))
            withTimeout(1_000) {
                while (cache.stored?.picture?.sha1d != updatedPicture.sha1d) {
                    kotlinx.coroutines.yield()
                }
            }
            assertArrayEquals(updatedBytes, cache.stored?.bytes)
        } finally {
            client.close()
        }
    }

    @Test
    fun explicitPictureRemovalClearsUiAndCacheWhileMissingMetadataDoesNot() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val cachedBytes = mockDisplayPictureBytes()
        val replacementBytes = cachedBytes + byteArrayOf(3, 2, 1)
        val cachedPicture = MsnDisplayPicture.create(contact.email, cachedBytes)
        val cache = RecordingContactDisplayPictureCache(
            cached = CachedContactDisplayPicture(cachedPicture, cachedBytes),
        )
        val storageId = "11111111-2222-3333-4444-555555555555"
        val storedTile = AtomicReference(cachedBytes)
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(
                    contacts = listOf(contact),
                    ownerDisplayName = "Mock Server User",
                    contactStorageIds = mapOf(contact.id to storageId),
                )
            },
            fetchDisplayPicture = { url, _ ->
                storedTile.get().takeIf { "/storage/usertile/$storageId/" in url }
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            contactDisplayPictureCache = cache,
            contactTilePollIntervalMillis = 20,
            initialContactPictureRefreshDelayMillis = 60_000,
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val cachedUri = requireNotNull(client.contacts.value.single().avatarUri)

            notification.deliver(
                MsnpCommand(
                    "NLN",
                    parameters = listOf(
                        "NLN",
                        "1:${contact.email}",
                        "Mock%20Contact",
                        Msnp18Protocol.CLIENT_IDENTITY,
                    ),
                ),
            )
            withTimeout(1_000) {
                client.contacts.first { it.single().presence == Presence.Online }
            }
            assertEquals(cachedUri, client.contacts.value.single().avatarUri)
            assertEquals(null, cache.removedContactEmail)

            notification.deliver(
                MsnpCommand(
                    "NLN",
                    parameters = listOf(
                        "NLN",
                        "1:${contact.email}",
                        "Mock%20Contact",
                        Msnp18Protocol.CLIENT_IDENTITY,
                        "0",
                    ),
                ),
            )

            withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri == null }
                while (cache.removedContactEmail == null) yield()
            }
            assertEquals(contact.email, cache.removedContactEmail)

            // A stale user-tile response must not resurrect the just-removed cached picture.
            delay(100)
            assertEquals(null, client.contacts.value.single().avatarUri)

            // Polling must still discover a genuinely new picture if its bytes later change.
            storedTile.set(replacementBytes)
            val replacementUri = withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri != null }.single().avatarUri
            }
            assertTrue(replacementUri.orEmpty().startsWith("data:image/png;base64,"))
            assertTrue(replacementUri != cachedUri)
        } finally {
            client.close()
        }
    }

    @Test
    fun initialPictureDownloadsStartTogetherAsPresenceArrives() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val contacts = (1..4).map { index ->
            val contactEmail = "contact$index@example.com"
            contact.copy(
                id = contactEmail,
                email = contactEmail,
                nickname = "Contact $index",
            )
        }
        val pictureBytes = mockDisplayPictureBytes()
        val startedDownloads = AtomicInteger()
        val releaseDownloads = CompletableDeferred<Unit>()
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(contacts, ownerDisplayName = "Mock Server User")
            },
            fetchDisplayPicture = { _, _ ->
                startedDownloads.incrementAndGet()
                releaseDownloads.await()
                pictureBytes
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            contacts.forEach { initialContact ->
                val picture = MsnDisplayPicture.create(initialContact.email, pictureBytes)
                    .copy(url1 = "https://mock-images.example/${initialContact.id}.png")
                notification.deliver(
                    MsnpCommand(
                        "NLN",
                        parameters = listOf(
                            "NLN",
                            "1:${initialContact.email}",
                            initialContact.nickname.orEmpty().replace(" ", "%20"),
                            Msnp18Protocol.CLIENT_IDENTITY,
                            picture.toEncodedValue(),
                        ),
                    ),
                )
            }

            withTimeout(1_000) {
                while (startedDownloads.get() != contacts.size) yield()
            }
            releaseDownloads.complete(Unit)
            val loadedContacts = withTimeout(1_000) {
                client.contacts.first { current ->
                    current.size == contacts.size && current.all { it.avatarUri != null }
                }
            }
            assertEquals(contacts.size, loadedContacts.size)
        } finally {
            releaseDownloads.complete(Unit)
            client.close()
        }
    }

    @Test
    fun postLoginSweepForceFetchesEveryOnlineContactPicture() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val contacts = (1..3).map { index ->
            val contactEmail = "contact$index@example.com"
            contact.copy(
                id = contactEmail,
                email = contactEmail,
                nickname = "Contact $index",
            )
        }
        val storageIds = contacts.mapIndexed { index, current ->
            current.id to "11111111-2222-3333-4444-55555555555${index + 1}"
        }.toMap()
        val pictureBytes = mockDisplayPictureBytes()
        val fetchedUrls = CopyOnWriteArrayList<String>()
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(
                    contacts = contacts,
                    ownerDisplayName = "Mock Server User",
                    contactStorageIds = storageIds,
                )
            },
            fetchDisplayPicture = { url, _ ->
                fetchedUrls += url
                when {
                    storageIds.values.none { storageId -> "/$storageId/" in url } -> null
                    url.endsWith("/small") -> byteArrayOf(1, 2, 3)
                    url.endsWith("/static") -> pictureBytes
                    else -> null
                }
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            initialContactPictureRefreshDelayMillis = 60_000,
        )

        try {
            // Keep normal foreground polling out of this test so only the post-login sweep can
            // retrieve these pictures.
            client.setAppInForeground(false)
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            contacts.take(2).forEach { onlineContact ->
                notification.deliver(
                    MsnpCommand(
                        "NLN",
                        parameters = listOf(
                            "NLN",
                            "1:${onlineContact.email}",
                            onlineContact.nickname.orEmpty().replace(" ", "%20"),
                            Msnp18Protocol.CLIENT_IDENTITY,
                        ),
                    ),
                )
            }
            notification.deliver(
                MsnpCommand(
                    "MSG",
                    parameters = listOf("Hotmail", "Hotmail"),
                    payload = (
                        "MIME-Version: 1.0\r\n" +
                            "Content-Type: text/x-msmsgsinitialmdatanotification; charset=UTF-8\r\n" +
                            "\r\nMail-Data: <MD/>"
                        ).toByteArray(),
                ),
            )

            val refreshedContacts = withTimeout(1_000) {
                client.contacts.first { current ->
                    current.count { it.avatarUri != null } == 2
                }
            }

            assertTrue(refreshedContacts.take(2).all { it.avatarUri != null })
            assertEquals(null, refreshedContacts.last().avatarUri)
            assertEquals(2, fetchedUrls.count { it.endsWith("/small") })
            assertEquals(2, fetchedUrls.count { it.endsWith("/static") })
            assertTrue(fetchedUrls.none { "/${storageIds.getValue(contacts.last().id)}/" in it })
        } finally {
            client.close()
        }
    }

    @Test
    fun offlineProfileRefreshCachesOriginalAndReusesItAfterFailedFetchAndNextSignIn() = runBlocking {
        val cachedBytes = mockDisplayPictureBytes()
        val originalBytes = cachedBytes + ByteArray(240 * 1024) { 7 }
        val cache = RecordingContactDisplayPictureCache(
            CachedContactDisplayPicture(MsnDisplayPicture.create(contact.email, cachedBytes), cachedBytes),
        )
        val fetchedUrls = CopyOnWriteArrayList<String>()
        var storageAvailable = true
        fun createClient() = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = ContractNotificationTransport(email),
            authenticateSso = { _, _, _, _, _ ->
                SsoToken("mock-ticket", "mock-proof", "mock-contacts", "mock-storage")
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(
                    contacts = listOf(contact),
                    ownerDisplayName = "Owner",
                    contactStorageIds = mapOf(contact.id to "stored-contact"),
                )
            },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchDisplayPicture = { url, maxBytes ->
                fetchedUrls += url
                assertTrue(maxBytes >= originalBytes.size)
                if (!storageAvailable) null
                else if (url.endsWith("/static")) originalBytes else cachedBytes
            },
            contactDisplayPictureCache = cache,
            initialContactPictureRefreshDelayMillis = 60_000,
        )
        val client = createClient()
        val originalUri: String
        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
            client.refreshContactPicture(contact.id)
            originalUri = requireNotNull(client.contacts.value.single().avatarUri)
            assertArrayEquals(originalBytes, cache.stored?.bytes)
            assertTrue(fetchedUrls.any { it.endsWith("/static") })
            assertEquals(Presence.Offline, client.contacts.value.single().presence)

            storageAvailable = false
            client.refreshContactPicture(contact.id)
            assertEquals(originalUri, client.contacts.value.single().avatarUri)
            assertArrayEquals(originalBytes, cache.stored?.bytes)
        } finally {
            client.close()
        }
        val restored = createClient()
        try {
            restored.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            assertEquals(originalUri, restored.contacts.value.single().avatarUri)
            assertEquals(Presence.Offline, restored.contacts.value.single().presence)
        } finally {
            restored.close()
        }
    }

    @Test
    fun storedContactTileRefreshesWithoutAnotherPresenceEvent() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val firstBytes = mockDisplayPictureBytes()
        val updatedBytes = firstBytes + byteArrayOf(7, 7, 7)
        val storedTile = AtomicReference(firstBytes)
        val fetchedUrls = CopyOnWriteArrayList<String>()
        val storageId = "11111111-2222-3333-4444-555555555555"
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(
                    contacts = listOf(contact),
                    ownerDisplayName = "Mock Server User",
                    contactStorageIds = mapOf(contact.id to storageId),
                )
            },
            fetchDisplayPicture = { url, _ ->
                fetchedUrls += url
                storedTile.get().takeIf { "/storage/usertile/$storageId/" in url }
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            contactTilePollIntervalMillis = 20,
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            notification.deliver(
                MsnpCommand(
                    "NLN",
                    parameters = listOf(
                        "NLN",
                        "1:${contact.email}",
                        "Mock%20Contact",
                        Msnp18Protocol.CLIENT_IDENTITY,
                    ),
                ),
            )
            val initialUri = withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri != null }.single().avatarUri
            }

            storedTile.set(updatedBytes)
            val updatedUri = withTimeout(1_000) {
                client.contacts.first { it.single().avatarUri != initialUri }.single().avatarUri
            }

            assertTrue(updatedUri.orEmpty().startsWith("data:image/png;base64,"))
            assertTrue(fetchedUrls.any { it.endsWith("/small") })
            assertTrue(fetchedUrls.any { it.endsWith("/static") })
        } finally {
            client.close()
        }
    }

    @Test
    fun staleCachedPictureDoesNotSuppressRetryAfterAFailedTransfer() = runBlocking {
        val notification = ContractNotificationTransport(email)
        val switchboard = ContractSwitchboardTransport(email, contact.email)
        val cachedBytes = mockDisplayPictureBytes()
        val updatedBytes = cachedBytes + byteArrayOf(4, 5, 6)
        val cachedPicture = MsnDisplayPicture.create(contact.email, cachedBytes)
        val updatedPicture = MsnDisplayPicture.create(contact.email, updatedBytes)
            .copy(url1 = "https://mock-images.example/retry.png")
        val cache = RecordingContactDisplayPictureCache(
            cached = CachedContactDisplayPicture(cachedPicture, cachedBytes),
        )
        var fetchAttempts = 0
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            switchboardTransportFactory = { switchboard },
            authenticateSso = { _, _, _, _, _ ->
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(listOf(contact), ownerDisplayName = "Mock Server User")
            },
            fetchDisplayPicture = { _, _ ->
                fetchAttempts++
                updatedBytes.takeIf { fetchAttempts > 1 }
            },
            fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            contactDisplayPictureCache = cache,
        )

        try {
            client.signIn(SignInRequest(email, password, serverConfig)).getOrThrow()
            val cachedUri = requireNotNull(client.contacts.value.single().avatarUri)
            val announcement = MsnpCommand(
                "NLN",
                parameters = listOf(
                    "NLN",
                    "1:${contact.email}",
                    "Mock%20Contact",
                    Msnp18Protocol.CLIENT_IDENTITY,
                    updatedPicture.toEncodedValue(),
                ),
            )

            notification.deliver(announcement)
            withTimeout(1_000) {
                while (fetchAttempts < 1) yield()
            }
            delay(700)
            assertEquals(cachedUri, client.contacts.value.single().avatarUri)

            notification.deliver(announcement)
            val refreshed = withTimeout(2_000) {
                client.contacts.first { it.single().avatarUri != cachedUri }.single()
            }
            assertEquals(2, fetchAttempts)
            assertTrue(refreshed.avatarUri.orEmpty().startsWith("data:image/png;base64,"))
        } finally {
            client.close()
        }
    }

    @Test
    fun crosstalkAndCustomProvidersRouteEveryLiveServiceToTheirSelectedEndpoints() = runBlocking {
        val customOverrides = ServerOverrides(
            mainServer = "notification.custom.mock",
            mainPort = 4242,
            switchboardPort = 4343,
            configServer = "config.custom.mock",
            authServerEnabled = true,
            authServer = "auth.custom.mock",
            addressBookServerEnabled = true,
            addressBookServer = "address-book.custom.mock",
        )
        val configurations = listOf(
            ServerPresets.resolveForProvider(ProviderOptions.CROSSTALK, ServerOverrides()),
            ServerPresets.resolveForProvider(ProviderOptions.CUSTOM, customOverrides),
        )

        configurations.forEach { configuration ->
            val notification = ContractNotificationTransport(email)
            var authenticationHost = ""
            var addressBookHost = ""
            val client = LiveMsnp18Client(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                transport = notification,
                authenticateSso = { host, _, _, _, _ ->
                    authenticationHost = host
                    SsoToken(
                        ticket = "mock-messenger-ticket",
                        response = "mock-rps-proof",
                        contactsTicket = "t=mock-address-book-ticket&p=",
                        storageTicket = "t=mock-storage-ticket&p=",
                    )
                },
                fetchAddressBook = { host, _, _ ->
                    addressBookHost = host
                    AddressBookSnapshot(emptyList(), ownerDisplayName = "Mock Server User")
                },
                fetchStorageProfile = { _, _ -> StorageProfile(photoResourceId = "mock!205") },
            )

            try {
                client.signIn(SignInRequest(email, password, configuration)).getOrThrow()

                assertEquals(configuration.mainServer, notification.connectedHost)
                assertEquals(configuration.mainPort, notification.connectedPort)
                assertEquals(configuration.authServer, authenticationHost)
                assertEquals(configuration.addressBookServer, addressBookHost)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun completeLivePipelineUsesOnlyInjectedMockServicesAndTransports() = runBlocking {
        val notification = ContractNotificationTransport(email)
        var switchboard = ContractSwitchboardTransport(email, contact.email)
        var ssoCalled = false
        var addressBookCalled = false
        var displayPictureUrl = ""
        val displayPictureBytes = mockDisplayPictureBytes()
        val client = LiveMsnp18Client(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = notification,
            switchboardTransportFactory = { switchboard },
            authenticateSso = { host, requestedEmail, requestedPassword, policy, nonce ->
                ssoCalled = true
                assertEquals(serverConfig.authServer, host)
                assertEquals(email, requestedEmail)
                assertEquals(password, requestedPassword)
                assertEquals("MBI_KEY_OLD", policy)
                assertEquals("mock-nonce", nonce)
                SsoToken(
                    ticket = "mock-messenger-ticket",
                    response = "mock-rps-proof",
                    contactsTicket = "t=mock-address-book-ticket&p=",
                    storageTicket = "t=mock-storage-ticket&p=",
                )
            },
            fetchAddressBook = { host, ticket, signedInEmail ->
                addressBookCalled = true
                assertEquals(serverConfig.addressBookServer, host)
                assertEquals("t=mock-address-book-ticket&p=", ticket)
                assertEquals(email, signedInEmail)
                AddressBookSnapshot(
                    contacts = listOf(contact),
                    ownerDisplayName = "Mock Server User",
                )
            },
            fetchDisplayPicture = { url, maxBytes ->
                displayPictureUrl = url
                assertTrue(displayPictureBytes.size <= maxBytes)
                displayPictureBytes
            },
            fetchStorageProfile = { host, ticket ->
                assertEquals(serverConfig.storageServer, host)
                assertEquals("t=mock-storage-ticket&p=", ticket)
                StorageProfile(photoResourceId = "mock!205")
            },
            updateStorageDisplayPicture = { host, ticket, resourceId, image ->
                assertEquals(serverConfig.storageServer, host)
                assertEquals("t=mock-storage-ticket&p=", ticket)
                assertEquals("mock!205", resourceId)
                assertArrayEquals(displayPictureBytes, image)
            },
        )

        val profile = client.signIn(
            SignInRequest(
                email = email,
                password = password,
                serverConfig = serverConfig,
                initialPresence = Presence.Busy,
            ),
        ).getOrThrow()

        assertTrue(ssoCalled)
        assertTrue(addressBookCalled)
        assertEquals("Mock Server User", profile.username)
        assertEquals(Presence.Busy, profile.presence)
        assertEquals(listOf(contact), client.contacts.value)
        assertEquals(
            listOf("VER", "CVR", "USR", "USR", "ADL", "UUX", "UUX", "CHG"),
            notification.sent.take(8).map(MsnpCommand::verb),
        )
        assertEquals(
            Presence.Busy.msnpCode,
            notification.sent.single { it.verb == "CHG" }.parameters.first(),
        )
        assertTrue(
            notification.sent.single { it.verb == "ADL" }
                .payload
                ?.toString(Charsets.UTF_8)
                .orEmpty()
                .contains("<c n=\"${contact.email.substringBefore('@')}\" l=\"3\" t=\"1\" />"),
        )
        assertEquals(0, notification.readTimeoutMillis)
        assertTrue(
            notification.sent
                .filter { it.verb == "UUX" }
                .any {
                    it.payload?.toString(Charsets.UTF_8).orEmpty().contains(
                        "<Capabilities>${Msnp18Protocol.ENDPOINT_CAPABILITIES}</Capabilities>",
                    )
                },
        )

        client.blockContact(contact.id)
        assertTrue(client.contacts.value.single().isBlocked)
        assertEquals(
            listOf("RML", "ADL"),
            notification.sent.takeLast(2).map(MsnpCommand::verb),
        )
        assertTrue(
            notification.sent.takeLast(2)[0].payload
                ?.toString(Charsets.UTF_8).orEmpty().contains("l=\"2\""),
        )
        assertTrue(
            notification.sent.takeLast(2)[1].payload
                ?.toString(Charsets.UTF_8).orEmpty().contains("l=\"4\""),
        )

        client.unblockContact(contact.id)
        assertTrue(!client.contacts.value.single().isBlocked)
        assertEquals(
            listOf("RML", "ADL"),
            notification.sent.takeLast(2).map(MsnpCommand::verb),
        )

        client.changeNickname(MockServerData.ACCOUNT_NICKNAME)
        val nickname = notification.sent.single { it.verb == "PRP" }
        assertEquals(
            listOf("MFN", MockServerData.ACCOUNT_NICKNAME.replace(" ", "%20")),
            nickname.parameters,
        )

        client.changePersonalMessage(MockServerData.ACCOUNT_STATUS_MESSAGE)
        val personalMessage = notification.sent
            .filter { it.verb == "UUX" }
            .single { it.payload?.toString(Charsets.UTF_8).orEmpty().contains("<Data>") }
            .payload
            ?.toString(Charsets.UTF_8)
            .orEmpty()
        assertTrue(
            personalMessage.contains("<PSM>${MockServerData.ACCOUNT_STATUS_MESSAGE}</PSM>"),
        )
        assertTrue(!personalMessage.contains("<MachineGuid></MachineGuid>"))

        client.changeDisplayPicture(displayPictureBytes)
        val publishedPictureCommand = notification.sent.last { it.verb == "CHG" }
        val publishedPicture = MsnDisplayPicture.parse(publishedPictureCommand.parameters[2])
        assertEquals(Msnp18Protocol.CLIENT_IDENTITY, publishedPictureCommand.parameters[1])
        assertEquals(email, publishedPicture?.creator)
        assertTrue(requireNotNull(publishedPicture).matches(displayPictureBytes))
        assertEquals(
            listOf("CHG", "CHG", "UUX"),
            notification.sent.takeLast(3).map(MsnpCommand::verb),
        )
        assertEquals(Presence.Online.msnpCode, notification.sent.takeLast(3)[0].parameters[0])
        assertEquals(Presence.Busy.msnpCode, notification.sent.takeLast(3)[1].parameters[0])
        assertTrue(
            notification.sent.last().payload?.toString(Charsets.UTF_8).orEmpty()
                .contains("<PSM>${MockServerData.ACCOUNT_STATUS_MESSAGE}</PSM>"),
        )
        val commandCountAfterRefresh = notification.sent.size
        client.changeDisplayPicture(displayPictureBytes)
        assertEquals(commandCountAfterRefresh, notification.sent.size)

        notification.deliver(
            MsnpCommand(
                verb = "NLN",
                parameters = listOf("BSY", "1:${contact.email}", "Mock%20Contact", "0:0"),
            ),
        )
        val presentContact = withTimeout(1_000) {
            client.contacts.first { contacts -> contacts.single().presence == Presence.Busy }.single()
        }
        assertEquals("Mock Contact", presentContact.nickname)

        val contactPictureUrl = "https://mock-images.example/contact.png"
        val contactPicture = MsnDisplayPicture.create(contact.email, displayPictureBytes)
            .copy(url1 = contactPictureUrl)
        notification.deliver(
            MsnpCommand(
                verb = "NLN",
                parameters = listOf(
                    "BSY",
                    "1:${contact.email}",
                    "Mock%20Contact",
                    Msnp18Protocol.CLIENT_IDENTITY,
                    contactPicture.toEncodedValue(),
                ),
            ),
        )
        val contactWithPicture = withTimeout(1_000) {
            client.contacts.first { contacts -> contacts.single().avatarUri != null }.single()
        }
        assertEquals(contactPictureUrl, displayPictureUrl)
        assertTrue(contactWithPicture.avatarUri.orEmpty().startsWith("data:image/png;base64,"))

        val directPictureUri = requireNotNull(contactWithPicture.avatarUri)
        val p2pPictureBytes = displayPictureBytes + byteArrayOf(0x50, 0x32, 0x50)
        val p2pPicture = MsnDisplayPicture.create(contact.email, p2pPictureBytes)
        switchboard.remotePicture = LocalDisplayPicture(p2pPicture, p2pPictureBytes)
        notification.deliver(
            MsnpCommand(
                verb = "NLN",
                parameters = listOf(
                    "BSY",
                    "1:${contact.email}",
                    "Mock%20Contact",
                    Msnp18Protocol.CLIENT_IDENTITY,
                    p2pPicture.toEncodedValue(),
                ),
            ),
        )
        val contactWithP2pPicture = withTimeout(2_000) {
            client.contacts.first { contacts ->
                contacts.single().avatarUri?.let { it != directPictureUri } == true
            }.single()
        }
        assertTrue(contactWithP2pPicture.avatarUri.orEmpty().startsWith("data:image/png;base64,"))
        assertArrayEquals(
            displayPictureBytes,
            withTimeout(2_000) { switchboard.requestSignedInPicture(publishedPicture) },
        )

        val deliveryGate = CompletableDeferred<Unit>()
        switchboard.textDeliveryGate = deliveryGate
        val sending = async { client.sendMessageWithResult(contact.id, "hello😀 from the integrated mock (y)") }
        val pending = withTimeout(1_000) {
            client.messages.first { it[contact.id]?.singleOrNull()?.isSending == true }
                .getValue(contact.id).single()
        }
        assertNull(pending.deliveryError)
        deliveryGate.complete(Unit)
        withTimeout(1_000) { sending.await().getOrThrow() }
        val sent = client.messages.value.getValue(contact.id).single()
        assertEquals(pending.id, sent.id)
        assertFalse(sent.isSending)
        assertNull(sent.deliveryError)
        assertEquals(
            "hello from the integrated mock (y)",
            client.messages.value.getValue(contact.id).single().text,
        )
        assertTrue(switchboard.sent.any { it.verb == "USR" })
        assertEquals(
            listOf(email, contact.email),
            switchboard.sent.filter { it.verb == "CAL" }.map { it.parameters.first() },
        )

        switchboard.deliver(
            MsnpCommand(
                verb = "MSG",
                parameters = listOf(contact.email, "BSY", "0"),
                payload = Msnp18Protocol.textPayload("mock incoming text"),
            ),
        )
        val conversation = withTimeout(1_000) {
            client.messages.first { it[contact.id].orEmpty().size == 2 }.getValue(contact.id)
        }
        assertEquals(listOf(MessageAuthor.Me, MessageAuthor.Contact), conversation.map { it.author })
        assertEquals("mock incoming text", conversation.last().text)

        client.sendTyping(contact.id)
        client.sendNudge(contact.id)
        val payloadKinds = switchboard.sent
            .filter { it.verb == "MSG" }
            .mapNotNull { it.payload }
            .map { String(it) }
        assertTrue(payloadKinds.any { it.contains(Msnp18Protocol.TYPING_CONTENT_TYPE) })
        assertTrue(payloadKinds.any { it.contains(Msnp18Protocol.NUDGE_CONTENT_TYPE) })
        assertEquals(
            "N",
            switchboard.sent.last { command ->
                command.verb == "MSG" && command.payload?.toString(Charsets.UTF_8)
                    .orEmpty().contains(Msnp18Protocol.NUDGE_CONTENT_TYPE)
            }.parameters.first(),
        )
        assertTrue(client.messages.value.getValue(contact.id).last().isNudge)

        switchboard.rejectText = true
        val nativeStyle = NativeTextStyle(true, true, true, true, 0xFF8000, 18)
        val failedSend = client.sendStyledMessage(contact.id, "Keep this failed message", null, nativeStyle)
        assertTrue(failedSend.isFailure)
        val messages = client.messages.value.getValue(contact.id)
        assertEquals(4, messages.size)
        val failed = messages.last()
        assertEquals(MessageAuthor.Me, failed.author)
        assertEquals("Keep this failed message", failed.text)
        assertEquals(nativeStyle, failed.nativeStyle)
        assertFalse(failed.isSending)
        assertTrue(failed.deliveryError.orEmpty().isNotBlank())

        switchboard = ContractSwitchboardTransport(email, contact.email).apply { rejectText = true }
        assertTrue(client.retryMessage(failed).isFailure)
        assertEquals(4, client.messages.value.getValue(contact.id).size)
        assertEquals(failed, client.messages.value.getValue(contact.id).last())

        val retryGate = CompletableDeferred<Unit>()
        switchboard = ContractSwitchboardTransport(email, contact.email).apply {
            textDeliveryGate = retryGate
        }
        val retrying = async { client.retryMessage(failed) }
        val retryPending = withTimeout(1_000) {
            client.messages.first { it[contact.id]?.lastOrNull()?.isSending == true }
                .getValue(contact.id).last()
        }
        assertEquals(failed.copy(isSending = true, deliveryError = null), retryPending)
        client.retryMessage(failed).getOrThrow()
        retryGate.complete(Unit)
        withTimeout(1_000) { retrying.await().getOrThrow() }
        val retried = client.messages.value.getValue(contact.id).last()
        assertEquals(failed.copy(deliveryError = null), retried)
        assertTrue(switchboard.sent.any { it.payload?.toString(Charsets.UTF_8)?.contains(nativeStyle.toFormatHeader()) == true })
        assertEquals(4, client.messages.value.getValue(contact.id).size)
        client.retryMessage(failed).getOrThrow()
        assertEquals(1, switchboard.sent.count { it.verb == "MSG" && it.parameters.firstOrNull() == "A" })

        // A history-restored failure is absent from the live client's message map.
        client.clearMessages(contact.id)
        val restored = failed.copy(renderedEmoticonRanges = emptyList())
        client.retryMessage(restored).getOrThrow()
        assertEquals(restored.copy(deliveryError = null), client.messages.value.getValue(contact.id).single())

        client.close()
    }
}

private class RecordingContactDisplayPictureCache(
    private val cached: CachedContactDisplayPicture,
) : ContactDisplayPictureCache {
    @Volatile
    private var current: CachedContactDisplayPicture? = cached

    @Volatile
    var stored: CachedContactDisplayPicture? = null

    @Volatile
    var removedContactEmail: String? = null

    override suspend fun load(
        accountEmail: String,
        contactEmails: Collection<String>,
    ): Map<String, CachedContactDisplayPicture> = current?.let { currentPicture ->
        contactEmails.associate { it.lowercase() to currentPicture }
    }.orEmpty()

    override suspend fun store(
        accountEmail: String,
        contactEmail: String,
        picture: MsnDisplayPicture,
        bytes: ByteArray,
    ) {
        val cachedPicture = CachedContactDisplayPicture(picture, bytes.copyOf())
        current = cachedPicture
        stored = cachedPicture
    }

    override suspend fun remove(
        accountEmail: String,
        contactEmail: String,
    ) {
        current = null
        removedContactEmail = contactEmail
    }
}

private class ContractNotificationTransport(
    private val signedInEmail: String,
) : MsnpTransport {
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    private var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    var readTimeoutMillis: Int? = null
        private set
    var connectedHost: String? = null
        private set
    var connectedPort: Int? = null
        private set
    private var failNextChg = false

    fun failNextChg() {
        failNextChg = true
    }

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) {
        connectedHost = host
        connectedPort = port
        incoming = Channel(Channel.UNLIMITED)
    }

    override suspend fun send(command: MsnpCommand) {
        if (command.verb == "CHG" && failNextChg) {
            failNextChg = false
            throw IOException("mock notification write failure")
        }
        sent += command
        when (command.verb) {
            "VER" -> deliver(
                MsnpCommand("VER", command.transactionId, listOf(Msnp18Protocol.VERSION, "CVR0")),
            )
            "CVR" -> deliver(MsnpCommand("CVR", command.transactionId, listOf("14.0.8117.0416")))
            "USR" -> if (command.parameters.getOrNull(1) == "I") {
                deliver(
                    MsnpCommand(
                        "USR",
                        command.transactionId,
                        listOf("SSO", "S", "MBI_KEY_OLD", "mock-nonce"),
                    ),
                )
            } else {
                deliver(
                    MsnpCommand(
                        "USR",
                        command.transactionId,
                        listOf("OK", signedInEmail, "1", "0"),
                    ),
                )
            }
            "XFR" -> deliver(
                MsnpCommand(
                    "XFR",
                    command.transactionId,
                    listOf("SB", "mock-switchboard.example:1864", "CKI", "mock-key"),
                ),
            )
            "ADL", "RML" -> deliver(MsnpCommand(command.verb, command.transactionId, listOf("OK")))
        }
    }

    override suspend fun receive(): MsnpCommand = incoming.receive()

    override fun setReadTimeout(timeoutMillis: Int) {
        readTimeoutMillis = timeoutMillis
    }

    fun deliver(command: MsnpCommand) {
        check(incoming.trySend(command).isSuccess)
    }

    fun disconnect(error: Throwable) {
        incoming.close(error)
    }

    override fun close() {
        incoming.close()
    }
}

private class ContractSwitchboardTransport(
    private val signedInEmail: String,
    private val contactEmail: String,
) : MsnpTransport {
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    private val incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    var remotePicture: LocalDisplayPicture? = null
    var textDeliveryGate: CompletableDeferred<Unit>? = null
    var rejectText: Boolean = false
    private var remotePeer: MsnpP2pSession? = null
    private val receivedPictures = Channel<ByteArray>(Channel.UNLIMITED)

    suspend fun requestSignedInPicture(picture: MsnDisplayPicture): ByteArray {
        requireNotNull(remotePeer).requestDisplayPicture(picture)
        return receivedPictures.receive()
    }

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) = Unit

    override suspend fun send(command: MsnpCommand) {
        sent += command
        when (command.verb) {
            "USR" -> deliver(
                MsnpCommand("USR", command.transactionId, listOf("OK", signedInEmail, "Mock User")),
            )
            "CAL" -> {
                val invitee = command.parameters.first()
                deliver(MsnpCommand("CAL", command.transactionId, listOf("RINGING", "4242")))
                deliver(MsnpCommand("JOI", parameters = listOf(invitee, "NLN", "0:0")))
            }
            "MSG" -> {
                if (command.parameters.firstOrNull() == "A") {
                    textDeliveryGate?.await()
                    if (rejectText) throw IOException("Message send failed")
                }
                val message = parseSwitchboardMime(requireNotNull(command.payload))
                if (message is SwitchboardMimeMessage.P2p) {
                    val peer = remotePeer ?: MsnpP2pSession(
                        localEmail = contactEmail,
                        remoteEmail = signedInEmail,
                        sendPacket = { packet ->
                            val payload = Msnp18Protocol.p2pPayload(signedInEmail, packet)
                            deliver(
                                MsnpCommand(
                                    verb = "MSG",
                                    parameters = listOf(
                                        contactEmail,
                                        "Mock Contact",
                                        payload.size.toString(),
                                    ),
                                    payload = payload,
                                ),
                            )
                        },
                        localPicture = { remotePicture },
                        onDisplayPicture = { receivedPictures.trySend(it) },
                    ).also { remotePeer = it }
                    peer.handle(message.bytes)
                }
                if (command.parameters.firstOrNull() == "D") {
                    deliver(MsnpCommand("ACK", command.transactionId))
                }
            }
        }
    }

    override suspend fun receive(): MsnpCommand = incoming.receive()

    fun deliver(command: MsnpCommand) {
        check(incoming.trySend(command).isSuccess)
    }

    override fun close() {
        incoming.close()
    }
}
