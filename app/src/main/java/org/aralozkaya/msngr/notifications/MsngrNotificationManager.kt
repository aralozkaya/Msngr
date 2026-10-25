package org.aralozkaya.msngr.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.aralozkaya.msngr.LoginActivity
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.loadAvatarBitmap
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.shortcutAvatarBitmap
import java.util.UUID

internal class MsngrNotificationManager(
    private val context: Context,
    private val isForeground: () -> Boolean = {
        (context.applicationContext as? org.aralozkaya.msngr.MsngrApplication)?.visibilityTracker?.isForeground == true
    },
) {
    private var requestAccount: String? = null
    private var requestKeys = emptySet<String>()
    private var groupInvitationAccount: String? = null
    private var groupInvitationKeys = emptySet<String>()

    @Synchronized
    fun syncGroupInvitations(inbox: org.aralozkaya.msngr.data.GroupInvitationInbox) {
        if (isForeground()) {
            dismiss(GROUP_INVITATIONS_NOTIFICATION_ID)
            groupInvitationAccount = inbox.accountId
            groupInvitationKeys = inbox.invitations.map { it.id }.toSet()
            return
        }
        if (inbox.accountId == null || inbox.invitations.isEmpty()) {
            dismiss(GROUP_INVITATIONS_NOTIFICATION_ID)
            groupInvitationAccount = inbox.accountId
            groupInvitationKeys = emptySet()
            return
        }
        if (!canPostNotifications()) return
        val keys = inbox.invitations.map { it.id }.toSet()
        val isNew = groupInvitationAccount != inbox.accountId || (keys - groupInvitationKeys).isNotEmpty()
        if (groupInvitationAccount != inbox.accountId) dismiss(GROUP_INVITATIONS_NOTIFICATION_ID)
        val count = inbox.invitations.size
        val text = context.resources.getQuantityString(R.plurals.notification_group_invitations, count, count)
        val open = PendingIntent.getActivity(context, GROUP_INVITATIONS_NOTIFICATION_ID,
            LoginActivity.createGroupInvitationsNotificationIntent(context, inbox.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(GROUP_INVITATIONS_NOTIFICATION_ID, NotificationCompat.Builder(context, NotificationChannels.GROUP_INVITATIONS)
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentTitle(context.getString(R.string.group_invitations_title)).setContentText(text)
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                inbox.invitations.take(5).forEach { style.addLine(it.groupName.messengerPlusPlainText()) }
            })
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(!isNew).setOngoing(true).setAutoCancel(false).build())
        groupInvitationAccount = inbox.accountId
        groupInvitationKeys = keys
    }

    @Synchronized
    fun syncContactRequests(inbox: org.aralozkaya.msngr.data.ContactRequestInbox) {
        if (isForeground()) {
            dismiss(CONTACT_REQUESTS_NOTIFICATION_ID)
            requestAccount = inbox.accountId
            requestKeys = inbox.requests.map { it.membershipId }.toSet()
            return
        }
        if (inbox.accountId == null || inbox.requests.isEmpty()) {
            dismiss(CONTACT_REQUESTS_NOTIFICATION_ID)
            requestAccount = inbox.accountId
            requestKeys = emptySet()
            return
        }
        if (!canPostNotifications()) return
        val keys = inbox.requests.map { it.membershipId }.toSet()
        val newRequests = requestAccount != inbox.accountId || (keys - requestKeys).isNotEmpty()
        if (requestAccount != inbox.accountId) dismiss(CONTACT_REQUESTS_NOTIFICATION_ID)
        val count = inbox.requests.size
        val title = context.getString(R.string.contact_requests_title)
        val text = context.resources.getQuantityString(R.plurals.notification_contact_requests, count, count)
        val openRequests = PendingIntent.getActivity(context, CONTACT_REQUESTS_NOTIFICATION_ID,
            LoginActivity.createContactRequestsNotificationIntent(context, inbox.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(CONTACT_REQUESTS_NOTIFICATION_ID,
            NotificationCompat.Builder(context, NotificationChannels.CONTACT_REQUESTS)
                .setSmallIcon(R.drawable.ic_notification_msngr)
                .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openRequests)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setNumber(count).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setOnlyAlertOnce(!newRequests)
                // Opening the page does not resolve a request or clear its launcher badge.
                .setOngoing(true).setAutoCancel(false).build())
        requestAccount = inbox.accountId
        requestKeys = keys
    }

    fun showOfflineMessagesReceived() {
        if (!canPostNotifications()) return
        val text = context.getString(R.string.notification_offline_messages_received)
        val openApp = PendingIntent.getActivity(
            context,
            OFFLINE_MESSAGES_NOTIFICATION_ID,
            LoginActivity.createIntent(context).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            OFFLINE_MESSAGES_NOTIFICATION_ID,
            NotificationCompat.Builder(context, NotificationChannels.OFFLINE_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification_msngr)
                .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    suspend fun show(event: NotificationEvent) {
        if (isForeground()) return
        if (InAppNotificationPrefs.read(context).conversations[event.contactId.notificationKey()]?.backgroundEnabled == false) {
            dismiss(notificationId(event.type, event.contactId))
            return
        }
        if (!canPostNotifications()) return
        val displayEvent = event.copy(
            contactName = event.contactName.messengerPlusPlainText(),
            messageText = event.messageText?.messengerPlusPlainText(),
        )
        val contactAvatar = context.loadAvatarBitmap(displayEvent.contactAvatarUri)
            ?: context.shortcutAvatarBitmap(displayEvent.contactName, null)
        val contactIcon = IconCompat.createWithAdaptiveBitmap(contactAvatar)
        val contact = contactPerson(displayEvent, contactIcon)
        when (displayEvent.type) {
            NotificationEventType.Message -> showMessage(displayEvent, contact, contactIcon)
            NotificationEventType.Nudge -> showNudge(displayEvent, contact, contactIcon)
            NotificationEventType.ContactSignedIn -> showContactSignedIn(displayEvent, contact, contactIcon)
        }
    }

    fun markReplying(
        contactId: String,
        contactName: String,
        notificationId: Int,
        previousNotification: Notification? = activeNotification(notificationId),
    ) {
        if (!canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                NotificationChannels.MESSAGES, contactId, contactName, "Sending reply…",
                previousNotification,
            )
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun markReplyFailed(contactId: String, contactName: String, notificationId: Int) {
        if (!canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                NotificationChannels.MESSAGES, contactId, contactName,
                "Reply not sent. Open Msngr to reconnect and try again.",
                activeNotification(notificationId),
            )
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun markNudging(
        contactId: String,
        contactName: String,
        notificationId: Int,
        channelId: String,
    ) {
        if (!canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                channelId, contactId, contactName,
                context.getString(R.string.notification_sending_nudge),
                activeNotification(notificationId),
            )
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun markNudgeFailed(
        contactId: String,
        contactName: String,
        notificationId: Int,
        channelId: String,
    ) {
        if (!canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                channelId, contactId, contactName,
                "${context.getString(R.string.notification_nudge_failed_title)}. " +
                    "Open Msngr to reconnect and try again.",
                activeNotification(notificationId),
            )
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun dismiss(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun dismissContactSignedInNotifications() {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications.filter {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.notification.channelId == NotificationChannels.CONTACT_SIGNED_IN
            } else {
                it.id in CONTACT_SIGNED_IN_ID_BASE until CONTACT_SIGNED_IN_ID_BASE + EVENT_NOTIFICATION_ID_COUNT
            }
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    internal fun activeNotification(notificationId: Int): Notification? =
        NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == notificationId }?.notification

    fun dismissConversation(contactId: String) {
        dismiss(notificationId(NotificationEventType.Message, contactId))
        dismiss(notificationId(NotificationEventType.Nudge, contactId))
    }

    private fun showMessage(
        event: NotificationEvent,
        sender: Person,
        contactIcon: IconCompat,
    ) {
        val notificationId = notificationId(NotificationEventType.Message, event.contactId)
        val shortcut = publishConversationShortcut(event, sender, contactIcon)
        val replyInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply_label))
            .build()
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra(NotificationReplyReceiver.EXTRA_CONTACT_ID, event.contactId)
            putExtra(NotificationReplyReceiver.EXTRA_CONTACT_NAME, event.contactName)
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.notification_reply_label),
            replyPendingIntent,
        ).addRemoteInput(replyInput).setAllowGeneratedReplies(true).build()
        val nudgeAction = nudgeAction(
            event = event,
            notificationId = notificationId,
            channelId = NotificationChannels.MESSAGES,
            label = context.getString(R.string.notification_nudge_label),
        )
        val style = messagingStyle().setGroupConversation(event.isGroup).also { messagingStyle ->
            if (event.isGroup) {
                messagingStyle.setConversationTitle(event.contactName)
            }
            val messageSender = if (event.isGroup) Person.Builder()
                .setName(event.senderName ?: event.senderEmail ?: "Member")
                .setKey(event.senderEmail ?: event.contactId).build() else sender
            messagingStyle.addMessage(event.messageText.orEmpty(), event.sentAtMillis, messageSender)
        }
        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("New message")
            .build()

        val notification = baseBuilder(
            NotificationChannels.MESSAGES,
            event.contactId,
            event.contactName,
            sender,
        )
            .setShortcutInfo(shortcut)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(event.sentAtMillis)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(replyAction)
            .addAction(nudgeAction)
            .addAction(markReadAction(event.contactId))
            .setAutoCancel(true)
            .build()
        notify(notificationId, notification)
    }

    private fun showNudge(event: NotificationEvent, contact: Person, contactIcon: IconCompat) {
        val notificationId = notificationId(NotificationEventType.Nudge, event.contactId)
        val shortcut = publishConversationShortcut(event, contact, contactIcon)
        val style = messagingStyle()
            .setGroupConversation(event.isGroup)
            .also { if (event.isGroup) it.setConversationTitle(event.contactName) }
            .addMessage("You received a nudge!", event.sentAtMillis, contact)
        val notification = baseBuilder(
            NotificationChannels.NUDGES,
            event.contactId,
            event.contactName,
            contact,
        )
            .setShortcutInfo(shortcut)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(event.sentAtMillis)
            .addAction(
                nudgeAction(
                    event = event,
                    notificationId = notificationId,
                    channelId = NotificationChannels.NUDGES,
                    label = context.getString(R.string.notification_nudge_back_label),
                ),
            )
            .setAutoCancel(true)
            .build()
        notify(notificationId, notification)
    }

    private fun showContactSignedIn(event: NotificationEvent, contact: Person, contactIcon: IconCompat) {
        val notificationId = notificationId(NotificationEventType.ContactSignedIn, event.contactId)
        val shortcut = publishConversationShortcut(event, contact, contactIcon)
        val style = messagingStyle()
            .setGroupConversation(false)
            .addMessage(
                context.getString(R.string.notification_contact_signed_in, event.contactName),
                event.sentAtMillis,
                contact,
            )
        val notification = baseBuilder(
            NotificationChannels.CONTACT_SIGNED_IN,
            event.contactId,
            event.contactName,
            contact,
        )
            .setShortcutInfo(shortcut)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(event.sentAtMillis)
            .addAction(
                nudgeAction(
                    event = event,
                    notificationId = notificationId,
                    channelId = NotificationChannels.CONTACT_SIGNED_IN,
                    label = context.getString(R.string.notification_nudge_label),
                ),
            )
            .setAutoCancel(true)
            .build()
        notify(notificationId, notification)
    }

    private fun messagingStyle(): NotificationCompat.MessagingStyle = NotificationCompat.MessagingStyle(
        Person.Builder()
            .setName(context.getString(R.string.notification_self_name))
            .setKey("me")
            .build(),
    )

    private fun actionStatusBuilder(
        channelId: String,
        contactId: String,
        contactName: String,
        text: String,
        previousNotification: Notification?,
    ): NotificationCompat.Builder {
        val previousStyle = previousNotification?.let {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)
        }
        val previousContact = previousStyle?.messages?.lastOrNull()?.person
        val builder: NotificationCompat.Builder
        val style: NotificationCompat.MessagingStyle
        val contact: Person
        if (previousNotification != null && previousStyle != null && previousContact != null) {
            // Recover the system-held avatar and shortcut, including after process death.
            builder = NotificationCompat.Builder(context, previousNotification).clearActions()
            style = previousStyle
            contact = previousContact
            style.messages.clear()
            style.historicMessages.clear()
        } else {
            val name = contactName.messengerPlusPlainText()
            val event = NotificationEvent(NotificationEventType.Message, contactId, name)
            val icon = previousNotification?.getLargeIcon()?.let {
                IconCompat.createFromIcon(context, it)
            } ?: IconCompat.createWithAdaptiveBitmap(context.shortcutAvatarBitmap(name, null))
            contact = contactPerson(event, icon)
            builder = baseBuilder(channelId, contactId, name, contact)
                .setShortcutInfo(publishConversationShortcut(event, contact, icon))
            style = messagingStyle().setGroupConversation(false)
        }
        // MessagingStyle lets Android place the avatar with its app badge in the header.
        // A large icon on a plain notification is rendered as trailing content instead.
        return builder
            .setChannelId(channelId)
            .setLargeIcon(null as Bitmap?)
            .setStyle(style.addMessage(text, System.currentTimeMillis(), contact))
            .setProgress(0, 0, false)
    }

    private fun baseBuilder(
        channelId: String,
        contactId: String,
        contactName: String,
        contact: Person,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
        .setSilent(InAppNotificationPrefs.read(context).conversations[contactId.notificationKey()]?.backgroundSilent == true)
        .setSmallIcon(R.drawable.ic_notification_msngr)
        .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
        .setContentIntent(contentIntent(contactId, contactName))
        .setColorized(false)
        .setGroup("msngr_events")
        .addPerson(contact)

    private fun contactPerson(event: NotificationEvent, contactIcon: IconCompat): Person =
        Person.Builder()
            .setName(event.contactName)
            .setKey(event.contactId)
            .setIcon(contactIcon)
            .build()

    private fun markReadAction(contactId: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationMarkReadReceiver::class.java).apply {
            putExtra(NotificationMarkReadReceiver.EXTRA_CONTACT_ID, contactId)
            data = android.net.Uri.Builder().scheme("msngr").authority("mark-read")
                .appendPath(contactId).build()
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId(NotificationEventType.Message, contactId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.notification_mark_read_label),
            pendingIntent,
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
    }

    private fun nudgeAction(
        event: NotificationEvent,
        notificationId: Int,
        channelId: String,
        label: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationNudgeReceiver::class.java).apply {
            putExtra(NotificationNudgeReceiver.EXTRA_CONTACT_ID, event.contactId)
            putExtra(NotificationNudgeReceiver.EXTRA_CONTACT_NAME, event.contactName)
            putExtra(NotificationNudgeReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationNudgeReceiver.EXTRA_CHANNEL_ID, channelId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + NUDGE_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pendingIntent).build()
    }

    private fun publishConversationShortcut(
        event: NotificationEvent,
        contact: Person,
        contactIcon: IconCompat,
    ): ShortcutInfoCompat = ShortcutInfoCompat.Builder(
        context,
        conversationShortcutId(event.contactId),
    )
        .setShortLabel(event.contactName)
        .setIntent(
            LoginActivity.createNotificationIntent(
                context,
                event.contactId,
                event.contactName,
            ).setAction(Intent.ACTION_VIEW),
        )
        .setPerson(contact)
        .setIsConversation()
        .setIcon(contactIcon)
        .build()
        .also { shortcut ->
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        }

    private fun contentIntent(contactId: String, contactName: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(NotificationEventType.Message, contactId),
            LoginActivity.createNotificationIntent(context, contactId, contactName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notify(notificationId: Int, notification: Notification) {
        if (isForeground()) return
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and the framework call.
        }
    }

    companion object {
        const val OFFLINE_MESSAGES_NOTIFICATION_ID = 400_000
        const val CONTACT_REQUESTS_NOTIFICATION_ID = 500_000
        const val GROUP_INVITATIONS_NOTIFICATION_ID = 600_000
        private const val CONTACT_SIGNED_IN_ID_BASE = 300_000
        private const val EVENT_NOTIFICATION_ID_COUNT = 90_000
        private const val NUDGE_REQUEST_CODE_OFFSET = 400_000

        internal fun conversationShortcutId(contactId: String): String =
            "msngr:${UUID.nameUUIDFromBytes(contactId.lowercase().toByteArray())}"

        fun notificationId(type: NotificationEventType, contactId: String): Int {
            val typePrefix = when (type) {
                NotificationEventType.Message -> 100_000
                NotificationEventType.Nudge -> 200_000
                NotificationEventType.ContactSignedIn -> CONTACT_SIGNED_IN_ID_BASE
            }
            return typePrefix + (contactId.hashCode() and 0x7fffffff) % EVENT_NOTIFICATION_ID_COUNT
        }
    }
}
