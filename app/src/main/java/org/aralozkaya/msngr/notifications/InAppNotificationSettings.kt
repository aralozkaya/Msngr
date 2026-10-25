package org.aralozkaya.msngr.notifications

/** These categories belong to Msngr; none creates an Android notification channel. */
internal enum class InAppNotificationType(val label: String) {
    Message("Messages"),
    GroupMessage("Group messages"),
    Nudge("Nudges"),
    ContactRequest("Contact requests"),
    GroupInvitation("Group invitations"),
}

internal data class InAppNotificationRule(val enabled: Boolean = true, val vibrate: Boolean = true)

internal data class InAppNotificationOptions(
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val banners: Boolean = true,
    val rules: Map<InAppNotificationType, InAppNotificationRule> = emptyMap(),
) {
    fun rule(type: InAppNotificationType) = rules[type] ?: InAppNotificationRule()
}

internal data class ConversationNotificationOverride(
    val contactId: String,
    val displayName: String,
    val isGroup: Boolean,
    val options: InAppNotificationOptions,
    val backgroundEnabled: Boolean = true,
    val backgroundSilent: Boolean = false,
)

internal data class InAppNotificationSettings(
    val defaults: InAppNotificationOptions = InAppNotificationOptions(),
    val conversations: Map<String, ConversationNotificationOverride> = emptyMap(),
) {
    fun options(contactId: String?) = conversations[contactId?.notificationKey()]?.options ?: defaults

    fun allows(type: InAppNotificationType, contactId: String? = null): Boolean {
        val options = options(contactId)
        return options.enabled && options.rule(type).enabled
    }

    fun vibrates(type: InAppNotificationType, contactId: String? = null): Boolean =
        allows(type, contactId) && options(contactId).vibrate &&
            options(contactId).rule(type).vibrate

    fun showsBanner(type: InAppNotificationType, contactId: String? = null): Boolean =
        allows(type, contactId) && options(contactId).banners
}

internal fun String.notificationKey() = trim().lowercase(java.util.Locale.ROOT)

internal fun otherUnreadCount(
    contacts: List<org.aralozkaya.msngr.data.Contact>, currentContactId: String,
): Int {
    return contacts.sumOf { contact ->
        if (contact.id.notificationKey() != currentContactId.notificationKey() && !contact.isBlocked) {
            contact.unreadCount.coerceAtLeast(if (contact.isMarkedUnread) 1 else 0)
        } else 0
    }
}
