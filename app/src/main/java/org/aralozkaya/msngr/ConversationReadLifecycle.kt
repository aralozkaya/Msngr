package org.aralozkaya.msngr

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.aralozkaya.msngr.data.MessengerRepository

/** A retained composition must not consume messages while its Activity is stopped. */
internal suspend fun observeConversationReadState(
    repository: MessengerRepository,
    contactId: String,
    lifecycle: Lifecycle,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.conversation(contactId).collect { repository.markRead(contactId) }
    }
}
