package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.PhotoShareStatus
import org.aralozkaya.msngr.data.PhotoToShare
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class MockMsnp18ClientTest {
    private val serverConfig = ServerPresets.resolveForProvider(
        provider = ProviderOptions.ESCARGOT,
        customServers = ServerOverrides(),
    )

    @Test
    fun successfulLoginUsesTheBundledMockServerContacts() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)

        val result = client.signIn(
            request(
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                initialPresence = Presence.Away,
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(Presence.Away, result.getOrThrow().presence)
        assertEquals(MockServerData.contacts(), client.contacts.value)
        client.close()
    }

    @Test
    fun mockLoginFailsWhenCredentialsAreMissing() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)

        val result = client.signIn(request(email = "", password = ""))

        assertTrue(result.isFailure)
        assertEquals("Email and password are required.", result.exceptionOrNull()?.message)
        assertTrue(client.contacts.value.isEmpty())
        client.close()
    }

    @Test
    fun mockLoginCanSimulateRejectedCredentials() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)

        val result = client.signIn(request(email = "fail@example.com", password = "secret"))

        assertTrue(result.isFailure)
        assertEquals(
            MockServerData.REJECTED_CREDENTIALS_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        assertTrue(client.contacts.value.isEmpty())
        client.close()
    }

    @Test
    fun blockingAndUnblockingUpdatesTheMsnpContactStream() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)
        client.signIn(request(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD))
        val contact = client.contacts.value.first()

        client.blockContact(contact.id)

        assertTrue(client.contacts.value.first { it.id == contact.id }.isBlocked)
        assertEquals(Presence.Offline, client.contacts.value.first { it.id == contact.id }.presence)

        client.unblockContact(contact.id)

        assertTrue(!client.contacts.value.first { it.id == contact.id }.isBlocked)
        client.close()
    }

    @Test
    fun readActionsToggleOnlyTheChosenConversationAndPreserveExistingUnreadCounts() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)
        client.signIn(request(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD))
        try {
            val contact = client.contacts.value.first { it.unreadCount > 1 }
            val others = client.contacts.value.filterNot { it.id == contact.id }
            val messages = client.messages.value
            client.markUnread(contact.id)
            assertEquals(contact.unreadCount, client.contacts.value.first { it.id == contact.id }.unreadCount)
            client.markRead(contact.id)
            assertEquals(0, client.contacts.value.first { it.id == contact.id }.unreadCount)
            repeat(2) { client.markUnread(contact.id) }
            assertEquals(0, client.contacts.value.first { it.id == contact.id }.unreadCount)
            assertTrue(client.contacts.value.first { it.id == contact.id }.isUnread)
            assertEquals(others, client.contacts.value.filterNot { it.id == contact.id })
            assertEquals(messages, client.messages.value)
            client.markRead(contact.id)
            assertEquals(0, client.contacts.value.first { it.id == contact.id }.unreadCount)
            assertTrue(!client.contacts.value.first { it.id == contact.id }.isUnread)
            client.markUnread(contact.id)
            client.sendMessage(contact.id, "Hello")
            val incoming = withTimeout(5_000) {
                client.contacts.first { contacts -> contacts.first { it.id == contact.id }.unreadCount > 0 }
            }
            assertEquals(1, incoming.first { it.id == contact.id }.unreadCount)
            client.clearMessages(contact.id)
            assertTrue(!client.contacts.value.first { it.id == contact.id }.isUnread)
            val beforeUnknownContact = client.contacts.value
            client.markUnread("missing-contact")
            client.markRead("missing-contact")
            assertEquals(beforeUnknownContact, client.contacts.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun outgoingMessagesKeepTheirLocalEmoticonRenderRanges() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)
        client.signIn(request(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD))
        val contact = client.contacts.value.first()
        val pickerRange = MsnEmoticonRange(6, 8)

        client.sendMessage(
            contactId = contact.id,
            text = "Hi :D :)",
            renderedEmoticonRanges = listOf(pickerRange),
        )

        val sent = client.messages.value.getValue(contact.id).single()
        assertEquals("Hi :D :)", sent.text)
        assertEquals(listOf(pickerRange), sent.renderedEmoticonRanges)
        client.close()
    }

    @Test
    fun outgoingMessagesKeepMessengerPlusWireFormatting() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)
        client.signIn(request(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD))
        val contact = client.contacts.value.first()

        client.sendMessage(contact.id, " [b][c=4]hello[/c][/b] ")

        val sent = client.messages.value.getValue(contact.id).single()
        assertEquals("[b][c=4]hello[/c][/b]", sent.text)
        assertEquals("hello", sent.text.messengerPlusPlainText())
        client.close()
    }

    @Test
    fun switchingAccountsAsTypingEndsDiscardsTheOldReply() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val client = MockMsnp18Client(scope = CoroutineScope(worker), signInDelayMillis = 0)
            try {
                client.signIn(request("first@example.test", "secret")).getOrThrow()
                val switched = async(Dispatchers.Unconfined) {
                    withTimeout(5_000) { client.typing.first { it["selin"] == false } }
                    client.signIn(request("second@example.test", "secret")).getOrThrow()
                }
                client.sendMessage("selin", "First account")
                switched.await()
                withContext(worker) {
                    assertTrue(client.messages.value.isEmpty())
                    assertTrue(client.typing.value.isEmpty())
                    assertEquals(MockServerData.contacts(), client.contacts.value)
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun staleStopAndNewPhotosPreserveProgressForTheActiveShare() = runBlocking {
        val directory = createTestTempDir("mock-photo-batches")
        val client = MockMsnp18Client(
            scope = this, signInDelayMillis = 0, photoCacheDirectory = directory, transferStepDelayMillis = 20,
        )
        fun photo(name: String) = PhotoToShare(name, "hash-$name", byteArrayOf(1), byteArrayOf(1, 2, 3))
        try {
            client.signIn(request(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD)).getOrThrow()
            client.sharePhotos("selin", listOf(photo("old"))).getOrThrow()
            val oldId = client.photoShares.value.getValue("selin").id
            client.stopPhotos("selin", oldId).getOrThrow()

            client.sharePhotos("selin", listOf(photo("first"))).getOrThrow()
            client.stopPhotos("selin", oldId).getOrThrow()
            withTimeout(3_000) {
                client.photoShares.first { it["selin"]?.status == PhotoShareStatus.Active }
            }
            assertTrue(!client.photoShares.value.getValue("selin").photos.single().downloadedByPeer)
            client.sharePhotos("selin", listOf(photo("second"))).getOrThrow()
            val completed = withTimeout(3_000) {
                client.photoShares.first { it.getValue("selin").photos.all { photo -> photo.downloadedByPeer } }
            }.getValue("selin")
            assertEquals(listOf("first", "second"), completed.photos.map { it.name })
            assertEquals(completed.totalOutgoingBytes, completed.sharedOutgoingBytes)
            client.close()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            client.close()
            directory.deleteRecursively()
        }
    }

    private fun request(
        email: String,
        password: String,
        initialPresence: Presence = Presence.Online,
    ) = SignInRequest(
        email = email,
        password = password,
        serverConfig = serverConfig,
        initialPresence = initialPresence,
    )
}
