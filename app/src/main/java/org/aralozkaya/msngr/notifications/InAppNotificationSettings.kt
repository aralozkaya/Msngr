package org.aralozkaya.msngr.notifications

/** These categories belong to Msngr; none creates an Android notification channel. */
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.R

enum class InAppNotificationType(val label: String, @param:StringRes val labelRes: Int) {
    Message("Messages", R.string.in_app_notification_type_messages_plural),
    GroupMessage("Group messages", R.string.in_app_notification_type_group_messages_plural),
    Nudge("Nudges", R.string.in_app_notification_type_nudges_plural),
    ContactRequest("Contact requests", R.string.in_app_notification_type_contact_requests_plural),
    GroupInvitation("Group invitations", R.string.in_app_notification_type_group_invitations_plural);

    @Composable
    fun displayLabel(): String = stringResource(labelRes)
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

internal data class PushNotificationOptions(
    val silenceWhenNotOnline: Boolean = true,
    val includeChatsWhenNotOnline: Boolean = false,
)

internal data class InAppNotificationSettings(
    val defaults: InAppNotificationOptions = InAppNotificationOptions(),
    val conversations: Map<String, ConversationNotificationOverride> = emptyMap(),
    val push: PushNotificationOptions = PushNotificationOptions(),
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

    fun silencesPush(presence: org.aralozkaya.msngr.enums.Presence, isChat: Boolean): Boolean {
        if (presence == org.aralozkaya.msngr.enums.Presence.Online || !push.silenceWhenNotOnline) return false
        return !isChat || push.includeChatsWhenNotOnline
    }
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
