package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
