package org.aralozkaya.msngr.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.localizeChatMessageText
import org.aralozkaya.msngr.localizeEmotionSoundName

internal class RealtimeNotificationBridge(
    private val visibilityTracker: AppVisibilityTracker,
    private val notificationManager: MsngrNotificationManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val inAppNotifications: InAppNotificationController? = null,
) {
    private var eventJob: Job? = null
    private var requestsJob: Job? = null
    private var groupInvitationsJob: Job? = null
    private val appContext get() = notificationManager.context

    fun attach(repository: MessengerRepository) {
        eventJob?.cancel()
        requestsJob?.cancel()
        groupInvitationsJob?.cancel()
        inAppNotifications?.dismiss()
        notificationManager.bindPresence { repository.profile.value.presence }
        groupInvitationsJob = scope.launch {
            var previousAccount: String? = null
            var previousKeys = emptySet<String>()
            combine(repository.groupInvitationInbox, visibilityTracker.foreground) { inbox, _ -> inbox }
                .collect { inbox ->
                    val newInvitations = inbox.invitations.filter { previousAccount != inbox.accountId || it.id !in previousKeys }
                    if (visibilityTracker.isForeground) {
                        newInvitations.lastOrNull()?.let { invitation ->
                            inAppNotifications?.show(InAppNotification(InAppNotificationType.GroupInvitation,
                                invitation.groupName.messengerPlusPlainText(), appContext.getString(R.string.in_app_notification_group_invite_text_you_singular), invitation.id))
                        }
                    }
                    previousAccount = inbox.accountId
                    previousKeys = inbox.invitations.map { it.id }.toSet()
                    notificationManager.syncGroupInvitations(inbox)
                }
        }
        requestsJob = scope.launch {
            // StateFlow also delivers invitations discovered during offline sign-in.
            var previousAccount: String? = null
            var previousKeys = emptySet<String>()
            combine(repository.contactRequestInbox, visibilityTracker.foreground) { inbox, _ -> inbox }
                .collect { inbox ->
                    val newRequests = inbox.requests.filter { previousAccount != inbox.accountId || it.membershipId !in previousKeys }
                    if (visibilityTracker.isForeground) {
                        newRequests.lastOrNull()?.let { request ->
                            inAppNotifications?.show(InAppNotification(InAppNotificationType.ContactRequest,
                                (request.displayName ?: request.email).messengerPlusPlainText(),
                                appContext.getString(R.string.in_app_notification_contact_request_text_you_singular), request.id))
                        }
                    }
                    previousAccount = inbox.accountId
                    previousKeys = inbox.requests.map { it.membershipId }.toSet()
                    notificationManager.syncContactRequests(inbox)
                }
        }
        eventJob = scope.launch {
            repository.events.collect { event ->
                val notificationEvent = when (event) {
                    is MessengerEvent.OwnDisplayPictureChanged,
                    is MessengerEvent.OwnPersonalStatusChanged,
                    is MessengerEvent.OwnCurrentMediaChanged,
                    is MessengerEvent.OwnPresenceChanged -> return@collect
                    is MessengerEvent.ConversationRepliedElsewhere -> {
                        notificationManager.dismissConversation(event.contactId)
                        inAppNotifications?.dismissConversation(event.contactId)
                        return@collect
                    }
                    MessengerEvent.OfflineMessagesReceived -> {
                        if (!visibilityTracker.isForeground) notificationManager.showOfflineMessagesReceived()
                        return@collect
                    }
                    is MessengerEvent.IncomingMessage -> {
                        val message = repository.messageSnapshot(event.contactId, event.messageId)
                            ?: return@collect
                        val contact = repository.contactSnapshot(event.contactId)
                        val senderContact = message.senderEmail
                            ?.takeIf { contact?.isGroup == true }
                            ?.let(repository::contactSnapshot)
                        NotificationEvent(
                            type = NotificationEventType.Message,
                            contactId = event.contactId,
                            contactName = contact?.displayName?.messengerPlusPlainText()
                                ?: event.contactId.substringBefore('@'),
                            contactAvatarUri = contact?.avatarUri,
                            messageText = (message.emotionSound?.let {
                                appContext.getString(R.string.chat_emotion_sound_message, appContext.localizeEmotionSoundName(it.sound.name))
                            } ?: appContext.localizeChatMessageText(message)).messengerPlusPlainText(),
                            sentAtMillis = message.timestampMillis,
                            isGroup = contact?.isGroup == true,
                            senderName = (message.senderName ?: senderContact?.displayName)
                                ?.messengerPlusPlainText(),
                            senderEmail = message.senderEmail,
                            senderAvatarUri = senderContact?.avatarUri,
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
                            isGroup = contact?.isGroup == true,
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
                    is MessengerEvent.ConnectionLost, is MessengerEvent.SignedOut -> {
                        inAppNotifications?.dismiss()
                        return@collect
                    }
                }
                if (visibilityTracker.isForeground) {
                    val type = when (notificationEvent.type) {
                        NotificationEventType.Message -> if (notificationEvent.isGroup) InAppNotificationType.GroupMessage else InAppNotificationType.Message
                        NotificationEventType.Nudge -> InAppNotificationType.Nudge
                        NotificationEventType.ContactSignedIn -> return@collect
                    }
                    val text = when (type) {
                        InAppNotificationType.GroupMessage -> appContext.getString(
                            R.string.in_app_notification_group_message_format,
                            notificationEvent.senderName ?: notificationEvent.senderEmail ?: appContext.getString(R.string.common_member_fallback_singular),
                            notificationEvent.messageText.orEmpty(),
                        )
                        InAppNotificationType.Nudge -> appContext.getString(R.string.in_app_notification_nudge_text_you_singular)
                        else -> notificationEvent.messageText.orEmpty()
                    }
                    inAppNotifications?.show(InAppNotification(type, notificationEvent.contactName, text,
                        notificationEvent.contactId, notificationEvent.contactAvatarUri))
                } else notificationManager.show(notificationEvent)
            }
        }
    }
}
