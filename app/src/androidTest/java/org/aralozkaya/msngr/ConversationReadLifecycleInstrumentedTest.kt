package org.aralozkaya.msngr

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationReadLifecycleInstrumentedTest {
    @Test fun backgroundMessagesStayUnreadUntilConversationIsVisibleAgain() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)
        val repository = MessengerRepository(client)
        repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        val contactId = repository.contacts.value.first().id
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val observer = launch(Dispatchers.Main.immediate) {
            observeConversationReadState(repository, contactId, owner.lifecycle)
        }
        try {
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
            withTimeout(2000) { repository.contacts.first { !it.first().isUnread } }
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.CREATED }
            repository.sendMessage(contactId, "Background test")
            withTimeout(4000) { repository.contacts.first { it.first().isUnread } }
            delay(300)
            assertTrue(repository.contacts.value.first().isUnread)
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
            withTimeout(2000) { repository.contacts.first { !it.first().isUnread } }
            Unit
        } finally {
            observer.cancelAndJoin()
            repository.logout()
        }
    }

    @Test fun multipleNudgesReceivedOutsideChatShakeOnlyOnceWhenOpenedAndShakeImmediatelyWhileVisible() = runBlocking {
        val client = LifecycleTestNudgeClient()
        val repository = MessengerRepository(client)
        val contactId = "selin@msngr.dev"
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var shakeCount = 0
        val observer = launch(Dispatchers.Main.immediate) {
            observeConversationNudgeShakes(repository, contactId, owner.lifecycle) {
                shakeCount++
            }
        }
        try {
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.CREATED }

            // Multiple nudges received while outside the conversation must not shake yet and must coalesce.
            client.events.tryEmit(MessengerEvent.IncomingNudge(contactId))
            client.events.tryEmit(MessengerEvent.IncomingNudge(contactId))
            client.events.tryEmit(MessengerEvent.IncomingNudge(contactId))
            delay(100)
            assertEquals(0, shakeCount)

            // Opening/resuming the conversation shakes once for all queued nudges.
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
            withTimeout(2000) {
                while (shakeCount < 1) delay(10)
            }
            delay(100)
            assertEquals(1, shakeCount)

            // A new nudge arriving while the conversation is visible shakes immediately.
            client.events.tryEmit(MessengerEvent.IncomingNudge(contactId))
            withTimeout(2000) {
                while (shakeCount < 2) delay(10)
            }
            assertEquals(2, shakeCount)
        } finally {
            observer.cancelAndJoin()
            repository.logout()
        }
    }
}

private class LifecycleTestNudgeClient : MsnpClient {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    override val typing = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val events = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 16)

    override suspend fun signIn(request: SignInRequest) =
        Result.success(UserProfileSnapshot("You", request.email, Presence.Online))
    override suspend fun changePresence(presence: Presence) = Unit
    override suspend fun changeNickname(nickname: String) = Unit
    override suspend fun changePersonalMessage(statusMessage: String) = Unit
    override suspend fun changeDisplayPicture(image: ByteArray?) = Unit
    override suspend fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ) = Unit
    override suspend fun sendTyping(contactId: String) = Unit
    override suspend fun sendNudge(contactId: String) = Unit
    override suspend fun blockContact(contactId: String) = Unit
    override suspend fun unblockContact(contactId: String) = Unit
    override fun markRead(contactId: String) = Unit
    override fun markUnread(contactId: String) = Unit
    override fun clearMessages() = Unit
    override fun clearMessages(contactId: String) = Unit
    override fun close() = Unit
}
