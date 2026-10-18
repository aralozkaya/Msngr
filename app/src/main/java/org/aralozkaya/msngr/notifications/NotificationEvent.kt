package org.aralozkaya.msngr.notifications

internal enum class NotificationEventType {
    Message,
    Nudge,
    FriendOnline,
}

internal data class NotificationEvent(
    val type: NotificationEventType,
    val contactId: String,
    val contactName: String,
    val contactAvatarUri: String? = null,
    val messageText: String? = null,
    val sentAtMillis: Long = System.currentTimeMillis(),
    val isGroup: Boolean = false,
    val senderName: String? = null,
    val senderEmail: String? = null,
)
