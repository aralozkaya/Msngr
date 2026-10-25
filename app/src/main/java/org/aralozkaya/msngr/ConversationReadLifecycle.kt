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

/**
 * Queued incoming nudges stay pending while the conversation is stopped and coalesce into a
 * single shake when the conversation becomes visible again.
 */
internal suspend fun observeConversationNudgeShakes(
    repository: MessengerRepository,
    contactId: String,
    lifecycle: Lifecycle,
    onShake: () -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.pendingNudgeShakes.collect {
            if (repository.consumePendingNudgeShake(contactId)) {
                onShake()
            }
        }
    }
}
