package org.aralozkaya.msngr.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.messengerPlusPlainText

internal class RealtimeNotificationBridge(
    private val visibilityTracker: AppVisibilityTracker,
    private val notificationManager: MsngrNotificationManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var eventJob: Job? = null
    private var requestsJob: Job? = null
    private var groupInvitationsJob: Job? = null

    fun attach(repository: MessengerRepository) {
        eventJob?.cancel()
        requestsJob?.cancel()
        groupInvitationsJob?.cancel()
        groupInvitationsJob = scope.launch {
            repository.groupInvitationInbox.collect(notificationManager::syncGroupInvitations)
        }
        requestsJob = scope.launch {
            // StateFlow also delivers invitations discovered during offline sign-in.
            repository.contactRequestInbox.collect(notificationManager::syncContactRequests)
        }
        eventJob = scope.launch {
            repository.events.collect { event ->
                if (visibilityTracker.isForeground &&
                    event !is MessengerEvent.OfflineMessagesReceived &&
                    (event !is MessengerEvent.IncomingMessage || !event.isOffline)
                ) return@collect
                val notificationEvent = when (event) {
                    is MessengerEvent.OwnDisplayPictureChanged -> return@collect
                    MessengerEvent.OfflineMessagesReceived -> {
                        notificationManager.showOfflineMessagesReceived()
                        return@collect
                    }
                    is MessengerEvent.IncomingMessage -> {
                        val message = repository.messageSnapshot(event.contactId, event.messageId)
                            ?: return@collect
                        val contact = repository.contactSnapshot(event.contactId)
                        NotificationEvent(
                            type = NotificationEventType.Message,
                            contactId = event.contactId,
                            contactName = contact?.displayName?.messengerPlusPlainText()
                                ?: event.contactId.substringBefore('@'),
                            contactAvatarUri = contact?.avatarUri,
                            messageText = message.text.messengerPlusPlainText(),
                            sentAtMillis = message.timestampMillis,
                            isGroup = contact?.isGroup == true,
                            senderName = message.senderName?.messengerPlusPlainText(),
                            senderEmail = message.senderEmail,
                        )
                    }
                    is MessengerEvent.IncomingNudge -> {
                        val contact = repository.contactSnapshot(event.contactId)
                        NotificationEvent(
                            type = NotificationEventType.Nudge,
                            contactId = event.contactId,
                            contactName = contact?.displayName?.messengerPlusPlainText()
                                ?: event.contactId.substringBefore('@'),
                            contactAvatarUri = contact?.avatarUri,
                        )
                    }
                    is MessengerEvent.PresenceChanged -> {
                        if (event.previousPresence != Presence.Offline ||
                            event.presence == Presence.Offline) return@collect
                        val contact = repository.contactSnapshot(event.contactId) ?: return@collect
                        if (contact.isBlocked || !contact.isForwardListContact ||
                            repository.contactRequests.value.any { it.id == contact.id }) return@collect
                        NotificationEvent(
                            type = NotificationEventType.ContactSignedIn,
                            contactId = event.contactId,
                            contactName = contact.displayName.messengerPlusPlainText(),
                            contactAvatarUri = contact.avatarUri,
                        )
                    }
                    is MessengerEvent.ConnectionLost, is MessengerEvent.SignedOut -> return@collect
                }
                notificationManager.show(notificationEvent)
            }
        }
    }
}
