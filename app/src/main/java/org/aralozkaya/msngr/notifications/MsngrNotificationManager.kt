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
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.delay
import org.aralozkaya.msngr.LoginActivity
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.cachedAvatarBitmap
import org.aralozkaya.msngr.loadAvatarBitmap
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.shortcutAvatarBitmap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
            senderName = event.senderName?.messengerPlusPlainText(),
        )
        val contactAvatar = context.loadAvatarBitmap(displayEvent.contactAvatarUri)
            ?: context.shortcutAvatarBitmap(displayEvent.contactName, null)
        val contactIcon = IconCompat.createWithAdaptiveBitmap(contactAvatar)
        conversationIcons[displayEvent.contactId] = contactIcon
        val contact = contactPerson(displayEvent, contactIcon)
        val posted = when (displayEvent.type) {
            NotificationEventType.Message -> showMessage(displayEvent, contact, contactIcon)
            NotificationEventType.Nudge -> showNudge(displayEvent, contact, contactIcon)
            NotificationEventType.ContactSignedIn -> showContactSignedIn(displayEvent, contact, contactIcon)
        }
        if (posted) {
            val targetId = notificationId(displayEvent.type, displayEvent.contactId)
            repeat(20) {
                if (systemActiveNotification(targetId) != null) return@repeat
                delay(10)
            }
        }
    }

    fun appendReply(
        contactId: String,
        contactName: String,
        replyText: String,
        notificationId: Int = notificationId(NotificationEventType.Message, contactId),
        previousNotification: Notification? = activeNotification(notificationId),
        isGroup: Boolean = false,
        sentAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (!canPostNotifications()) return
        val cleanReply = replyText.messengerPlusPlainText().trim()
        if (cleanReply.isEmpty()) return
        val previousStyle = previousNotification?.let(
            NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification,
        )
        val resolvedIsGroup = previousStyle?.isGroupConversation ?: isGroup
        val resolvedName = (
            previousStyle?.conversationTitle?.toString()?.takeIf(String::isNotBlank)
                ?: contactName
            ).messengerPlusPlainText().ifBlank { contactId.substringBefore('@').ifBlank { contactId } }
        val contactIcon = resolveConversationIcon(
            contactId = contactId,
            contactName = resolvedName,
            previousNotification = previousNotification,
            previousStyle = previousStyle,
            isGroup = resolvedIsGroup,
        )
        val event = NotificationEvent(
            type = NotificationEventType.Message,
            contactId = contactId,
            contactName = resolvedName,
            messageText = cleanReply,
            sentAtMillis = sentAtMillis,
            isGroup = resolvedIsGroup,
        )
        val contact = contactPerson(event, contactIcon)
        val shortcut = publishConversationShortcut(event, contact, contactIcon)
        val keepExistingMessages = previousNotification?.extras?.getBoolean(EXTRA_IS_ACTION_STATUS) != true
        val style = messagingStyle().setGroupConversation(resolvedIsGroup).also { messagingStyle ->
            if (resolvedIsGroup) {
                messagingStyle.setConversationTitle(resolvedName)
            }
            if (keepExistingMessages) {
                previousStyle?.messages
                    ?.filterNot(::isActionStatusMessage)
                    ?.forEach(messagingStyle::addMessage)
            }
            messagingStyle.addMessage(cleanReply, sentAtMillis, null as Person?)
        }
        val notification = buildMessageNotification(
            event = event,
            sender = contact,
            shortcut = shortcut,
            style = style,
            notificationId = notificationId,
            sentAtMillis = sentAtMillis,
            onlyAlertOnce = true,
            forceSilent = true,
        )
        notify(notificationId, notification, allowWhenForeground = true)
    }

    fun ensureReplyShown(
        contactId: String,
        contactName: String,
        replyText: String,
        notificationId: Int = notificationId(NotificationEventType.Message, contactId),
        isGroup: Boolean = false,
    ) {
        val active = activeNotification(notificationId) ?: return
        val cleanReply = replyText.messengerPlusPlainText().trim()
        if (cleanReply.isEmpty()) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active)
        val alreadyShown = style?.messages?.any { message ->
            (message.person == null || message.person?.key == SELF_PERSON_KEY) &&
                message.text?.toString() == cleanReply
        } == true
        if (!alreadyShown) {
            appendReply(
                contactId = contactId,
                contactName = contactName,
                replyText = cleanReply,
                notificationId = notificationId,
                previousNotification = active,
                isGroup = isGroup,
            )
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
                NotificationChannels.MESSAGES, contactId, contactName, STATUS_SENDING_REPLY,
                previousNotification,
            )
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .build(),
            allowWhenForeground = true,
        )
    }

    fun markReplyFailed(contactId: String, contactName: String, notificationId: Int) {
        if (!canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                NotificationChannels.MESSAGES, contactId, contactName,
                STATUS_REPLY_FAILED,
                activeNotification(notificationId),
            )
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
            allowWhenForeground = true,
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
            allowWhenForeground = true,
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
            allowWhenForeground = true,
        )
    }

    fun dismiss(notificationId: Int) {
        recentNotifications.remove(notificationId)
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

    internal fun activeNotification(notificationId: Int): Notification? {
        val systemNotification = systemActiveNotification(notificationId)
        if (systemNotification == null) {
            recentNotifications.remove(notificationId)
            return null
        }
        val cached = recentNotifications[notificationId] ?: return systemNotification
        val systemSeq = systemNotification.extras.getLong(EXTRA_POST_SEQUENCE, 0L)
        val cachedSeq = cached.extras.getLong(EXTRA_POST_SEQUENCE, 0L)
        return if (cachedSeq > systemSeq) cached else systemNotification
    }

    private fun systemActiveNotification(notificationId: Int): Notification? =
        NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == notificationId }?.notification

    fun dismissConversation(contactId: String) {
        dismiss(notificationId(NotificationEventType.Message, contactId))
        dismiss(notificationId(NotificationEventType.Nudge, contactId))
    }

    private suspend fun showMessage(
        event: NotificationEvent,
        sender: Person,
        contactIcon: IconCompat,
    ): Boolean {
        val notificationId = notificationId(NotificationEventType.Message, event.contactId)
        val previousNotification = activeNotification(notificationId)
        val previousStyle = previousNotification?.let(
            NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification,
        )
        val keepExistingMessages = previousNotification?.extras?.getBoolean(EXTRA_IS_ACTION_STATUS) != true
        val shortcut = publishConversationShortcut(event, sender, contactIcon)
        val messageSender = if (event.isGroup) groupSenderPerson(event) else sender
        val style = messagingStyle().setGroupConversation(event.isGroup).also { messagingStyle ->
            if (event.isGroup) {
                messagingStyle.setConversationTitle(event.contactName)
            }
            if (keepExistingMessages) {
                previousStyle?.messages
                    ?.filterNot(::isActionStatusMessage)
                    ?.forEach(messagingStyle::addMessage)
            }
            messagingStyle.addMessage(event.messageText.orEmpty(), event.sentAtMillis, messageSender)
        }
        val notification = buildMessageNotification(
            event = event,
            sender = sender,
            shortcut = shortcut,
            style = style,
            notificationId = notificationId,
            sentAtMillis = event.sentAtMillis,
            onlyAlertOnce = false,
            forceSilent = false,
        )
        return notify(notificationId, notification)
    }

    private suspend fun groupSenderPerson(event: NotificationEvent): Person {
        val senderLabel = (event.senderName ?: event.senderEmail ?: "Member")
            .messengerPlusPlainText()
            .ifBlank { "Member" }
        val senderBitmap = context.loadAvatarBitmap(event.senderAvatarUri)
            ?: context.shortcutAvatarBitmap(senderLabel, null)
        return Person.Builder()
            .setName(senderLabel)
            .setKey(event.senderEmail ?: event.contactId)
            .setIcon(IconCompat.createWithAdaptiveBitmap(senderBitmap))
            .build()
    }

    private fun buildMessageNotification(
        event: NotificationEvent,
        sender: Person,
        shortcut: ShortcutInfoCompat,
        style: NotificationCompat.MessagingStyle,
        notificationId: Int,
        sentAtMillis: Long,
        onlyAlertOnce: Boolean,
        forceSilent: Boolean,
    ): Notification {
        val replyAction = replyAction(
            contactId = event.contactId,
            contactName = event.contactName,
            notificationId = notificationId,
            isGroup = event.isGroup,
        )
        val nudgeAction = nudgeAction(
            event = event,
            notificationId = notificationId,
            channelId = NotificationChannels.MESSAGES,
            label = context.getString(R.string.notification_nudge_label),
        )
        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("New message")
            .build()

        val conversationSilent = InAppNotificationPrefs.read(context)
            .conversations[event.contactId.notificationKey()]?.backgroundSilent == true
        return baseBuilder(
            NotificationChannels.MESSAGES,
            event.contactId,
            event.contactName,
            sender,
        )
            .setSilent(forceSilent || conversationSilent)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setShortcutInfo(shortcut)
            .setShortcutId(shortcut.id)
            .setLocusId(LocusIdCompat(shortcut.id))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(sentAtMillis)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(replyAction)
            .addAction(nudgeAction)
            .addAction(markReadAction(event.contactId))
            .setAutoCancel(true)
            .build()
    }

    private fun showNudge(event: NotificationEvent, contact: Person, contactIcon: IconCompat): Boolean {
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
            .setShortcutId(shortcut.id)
            .setLocusId(LocusIdCompat(shortcut.id))
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
        return notify(notificationId, notification)
    }

    private fun showContactSignedIn(event: NotificationEvent, contact: Person, contactIcon: IconCompat): Boolean {
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
            .setShortcutId(shortcut.id)
            .setLocusId(LocusIdCompat(shortcut.id))
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
        return notify(notificationId, notification)
    }

    private fun messagingStyle(): NotificationCompat.MessagingStyle = NotificationCompat.MessagingStyle(
        Person.Builder()
            .setName(context.getString(R.string.notification_self_name))
            .setKey(SELF_PERSON_KEY)
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
        val previousContact = previousStyle?.messages
            ?.lastOrNull { it.person != null && it.person?.key != SELF_PERSON_KEY }
            ?.person
            ?: previousNotification?.let(NotificationCompat::getPeople)
                ?.firstOrNull { it.key == contactId }
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
            val icon = resolveConversationIcon(
                contactId = contactId,
                contactName = name,
                previousNotification = previousNotification,
                previousStyle = previousStyle,
                isGroup = previousStyle?.isGroupConversation == true,
            )
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
            .addExtras(Bundle().apply { putBoolean(EXTRA_IS_ACTION_STATUS, true) })
            .setStyle(style.addMessage(text, System.currentTimeMillis(), contact))
            .setProgress(0, 0, false)
    }

    private fun resolveConversationIcon(
        contactId: String,
        contactName: String,
        previousNotification: Notification?,
        previousStyle: NotificationCompat.MessagingStyle?,
        isGroup: Boolean,
    ): IconCompat {
        previousNotification?.let(NotificationCompat::getPeople)
            ?.firstOrNull { it.key == contactId }
            ?.icon
            ?.let { return it }
        if (!isGroup) {
            previousStyle?.messages
                ?.lastOrNull { it.person != null && it.person?.key != SELF_PERSON_KEY }
                ?.person
                ?.icon
                ?.let { return it }
        }
        conversationIcons[contactId]?.let { return it }
        runCatching {
            context.appContainer.messengerRepository.contactSnapshot(contactId)?.avatarUri
        }.getOrNull()?.let { uri ->
            cachedAvatarBitmap(uri)?.let { return IconCompat.createWithAdaptiveBitmap(it) }
        }
        previousNotification?.getLargeIcon()?.let {
            return IconCompat.createFromIcon(context, it)
        }
        return IconCompat.createWithAdaptiveBitmap(context.shortcutAvatarBitmap(contactName, null))
    }

    private fun isActionStatusMessage(message: NotificationCompat.MessagingStyle.Message): Boolean {
        val text = message.text?.toString() ?: return false
        return text == STATUS_SENDING_REPLY ||
            text == STATUS_REPLY_FAILED ||
            text == context.getString(R.string.notification_sending_nudge) ||
            text.startsWith(context.getString(R.string.notification_nudge_failed_title))
    }

    private fun baseBuilder(
        channelId: String,
        contactId: String,
        contactName: String,
        contact: Person,
    ): NotificationCompat.Builder {
        val shortcutId = conversationShortcutId(contactId)
        return NotificationCompat.Builder(context, channelId)
            .setSilent(InAppNotificationPrefs.read(context).conversations[contactId.notificationKey()]?.backgroundSilent == true)
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentIntent(contentIntent(contactId, contactName))
            .setColorized(false)
            .setGroup("msngr_events")
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(contact)
    }

    private fun contactPerson(event: NotificationEvent, contactIcon: IconCompat): Person =
        Person.Builder()
            .setName(event.contactName)
            .setKey(event.contactId)
            .setIcon(contactIcon)
            .build()

    private fun replyAction(
        contactId: String,
        contactName: String,
        notificationId: Int,
        isGroup: Boolean,
    ): NotificationCompat.Action {
        val replyInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply_label))
            .build()
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra(NotificationReplyReceiver.EXTRA_CONTACT_ID, contactId)
            putExtra(NotificationReplyReceiver.EXTRA_CONTACT_NAME, contactName)
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationReplyReceiver.EXTRA_IS_GROUP, isGroup)
            data = android.net.Uri.Builder()
                .scheme("msngr")
                .authority("reply")
                .appendPath(contactId)
                .build()
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.notification_reply_label),
            replyPendingIntent,
        )
            .addRemoteInput(replyInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .extend(
                NotificationCompat.Action.WearableExtender()
                    .setHintDisplayActionInline(true)
                    .setHintLaunchesActivity(false),
            )
            .build()
    }

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
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .extend(
                NotificationCompat.Action.WearableExtender()
                    .setHintLaunchesActivity(false),
            )
            .build()
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
            data = android.net.Uri.Builder()
                .scheme("msngr")
                .authority("nudge")
                .appendPath(channelId)
                .appendPath(event.contactId)
                .build()
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + NUDGE_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pendingIntent)
            .setShowsUserInterface(false)
            .extend(
                NotificationCompat.Action.WearableExtender()
                    .setHintLaunchesActivity(false),
            )
            .build()
    }

    private fun publishConversationShortcut(
        event: NotificationEvent,
        contact: Person,
        contactIcon: IconCompat,
    ): ShortcutInfoCompat {
        val shortcutId = conversationShortcutId(event.contactId)
        return ShortcutInfoCompat.Builder(
            context,
            shortcutId,
        )
            .setShortLabel(event.contactName)
            .setLongLabel(event.contactName)
            .setIntent(
                LoginActivity.createNotificationIntent(
                    context,
                    event.contactId,
                    event.contactName,
                ).setAction(Intent.ACTION_VIEW),
            )
            .setPerson(contact)
            .setLongLived(true)
            .setIsConversation()
            .setLocusId(LocusIdCompat(shortcutId))
            .setIcon(contactIcon)
            .build()
            .also { shortcut ->
                runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
            }
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
    private fun notify(
        notificationId: Int,
        notification: Notification,
        allowWhenForeground: Boolean = false,
    ): Boolean {
        if (!allowWhenForeground && isForeground()) return false
        if (!canPostNotifications()) return false
        notification.extras.putLong(EXTRA_POST_SEQUENCE, nextPostSequence.incrementAndGet())
        recentNotifications[notificationId] = notification
        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            recentNotifications.remove(notificationId)
            // Permission can be revoked between the explicit check and the framework call.
            false
        }
    }

    companion object {
        const val OFFLINE_MESSAGES_NOTIFICATION_ID = 400_000
        const val CONTACT_REQUESTS_NOTIFICATION_ID = 500_000
        const val GROUP_INVITATIONS_NOTIFICATION_ID = 600_000
        private const val CONTACT_SIGNED_IN_ID_BASE = 300_000
        private const val EVENT_NOTIFICATION_ID_COUNT = 90_000
        private const val NUDGE_REQUEST_CODE_OFFSET = 400_000
        private const val SELF_PERSON_KEY = "me"
        private const val STATUS_SENDING_REPLY = "Sending replyâ€¦"
        private const val STATUS_REPLY_FAILED =
            "Reply not sent. Open Msngr to reconnect and try again."
        private const val EXTRA_IS_ACTION_STATUS = "msngr_is_action_status"
        private const val EXTRA_POST_SEQUENCE = "msngr_post_sequence"
        private val nextPostSequence = AtomicLong(0L)
        private val recentNotifications = ConcurrentHashMap<Int, Notification>()
        private val conversationIcons = ConcurrentHashMap<String, IconCompat>()

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
