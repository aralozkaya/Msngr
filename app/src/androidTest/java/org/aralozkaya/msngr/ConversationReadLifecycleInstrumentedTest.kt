package org.aralozkaya.msngr

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.*
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
}
