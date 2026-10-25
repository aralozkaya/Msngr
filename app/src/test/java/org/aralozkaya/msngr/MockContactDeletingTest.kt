package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockContactDeletingTest {
    private val peer = "peer@example.test"
    private val request = SignInRequest("owner@example.test", "mock-only",
        ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))

    @Test fun localDeleteWithoutBlockRetainsConversationAndCanBeReaddedOnce() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, addContactDelayMillis = 0, deleteContactDelayMillis = 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readNotifications = mutableListOf<String>()
        val repository = MessengerRepository(client, scope, onConversationRead = { readNotifications += it })
        try {
            repository.signIn(request).getOrThrow()
            repository.addContact(peer).getOrThrow()
            repository.sendMessageNow(peer, "Keep this saved conversation.").getOrThrow()
            val history = withTimeout(3000) { repository.conversation(peer).first { it.messages.isNotEmpty() } }.messages
            repository.deleteContact(peer).getOrThrow()
            assertFalse(repository.contacts.value.single { it.id == peer }.isForwardListContact)
            assertFalse(repository.contacts.value.single { it.id == peer }.isBlocked)
            assertEquals(history, repository.conversation(peer).first().messages)
            assertTrue(readNotifications.contains(peer))
            repository.addContact(peer).getOrThrow()
            assertEquals(1, repository.contacts.value.count { it.id == peer })
        } finally { repository.logout(); scope.cancel() }
    }

    @Test fun localDeleteAndBlockKeepsBlockedEntryAndUnblockDoesNotReadd() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, addContactDelayMillis = 0, deleteContactDelayMillis = 0)
        try {
            client.signIn(request).getOrThrow()
            client.addContact(peer)
            client.deleteContact(peer, true)
            val deleted = client.contacts.value.single { it.id == peer }
            assertTrue(deleted.isBlocked)
            assertFalse(deleted.isForwardListContact)
            assertTrue(runCatching { client.addContact(peer) }.isFailure)
            client.unblockContact(peer)
            assertFalse(client.contacts.value.single { it.id == peer }.isForwardListContact)
            client.addContact(peer)
            assertEquals(1, client.contacts.value.count { it.id == peer })
        } finally { client.close() }
    }

    @Test fun pendingMockDeleteCannotMutateANewerSession() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, addContactDelayMillis = 0, deleteContactDelayMillis = 100)
        try {
            client.signIn(request).getOrThrow()
            client.addContact(peer)
            val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.deleteContact(peer, true) } }
            assertTrue(client.contacts.value.single { it.id == peer }.isForwardListContact)
            client.close()
            client.signIn(request).getOrThrow()
            assertTrue(operation.await().isFailure)
            assertTrue(client.contacts.value.none { it.id == peer })
        } finally { client.close() }
    }
}
