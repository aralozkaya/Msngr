package org.aralozkaya.msngr

import kotlinx.coroutines.*
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.*
import org.junit.Test

/** These clients have no transport or SOAP service; dummy credentials never leave memory. */
class MockContactAddingTest {
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val request = SignInRequest(owner, "mock-only", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))

    @Test fun mockAdditionShowsPendingThenPublishesOneVisibleLocalContact() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, addContactDelayMillis = 100)
        try {
            client.signIn(request).getOrThrow()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { client.addContact(peer.uppercase()) }
            assertFalse(pending.isCompleted)
            assertTrue(client.contacts.value.none { it.email == peer })
            pending.await()
            val contact = client.contacts.value.single { it.email == peer }
            assertEquals("peer", contact.nickname)
            assertEquals(Presence.Online, contact.presence)
            assertTrue(contact.statusMessage.contains("Mock contact"))
            assertTrue(runCatching { client.addContact(peer) }.isFailure)
            assertEquals(1, client.contacts.value.count { it.email == peer })
        } finally { client.close() }
    }

    @Test fun abandonedMockAdditionCannotChangeANewerSession() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0, addContactDelayMillis = 100)
        try {
            client.signIn(request).getOrThrow()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.addContact(peer) } }
            client.close()
            client.signIn(request).getOrThrow()
            assertTrue(pending.await().isFailure)
            assertTrue(client.contacts.value.none { it.email == peer })
        } finally { client.close() }
    }
}
