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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.LruCache
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
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.getQuantityNumberSentence
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.cachedAvatarBitmap
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.loadAvatarBitmap
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.shortcutAvatarBitmap
import java.util.UUID

internal class MsngrNotificationManager(
    internal val context: Context,
    private val isForeground: () -> Boolean = {
        (context.applicationContext as? MsngrApplication)?.visibilityTracker?.isForeground == true
    },
) {
    private val systemNotifications = NotificationManagerCompat.from(context.applicationContext)
    @Volatile private var attachedPresence: (() -> Presence)? = null
    private var requestAccount: String? = null
    private var requestKeys = emptySet<String>()
    private var groupInvitationAccount: String? = null
    private var groupInvitationKeys = emptySet<String>()

    internal fun bindPresence(presenceProvider: (() -> Presence)?) {
        attachedPresence = presenceProvider
    }

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
        val text = context.getQuantityNumberSentence(
            count = count,
            oneBeforeRes = R.string.notification_group_invitations_singular_you_singular_before_number,
            oneAfterRes = R.string.notification_group_invitations_singular_you_singular_after_number,
            otherBeforeRes = R.string.notification_group_invitations_plural_you_singular_before_number,
            otherAfterRes = R.string.notification_group_invitations_plural_you_singular_after_number,
        )
        val open = PendingIntent.getActivity(context, GROUP_INVITATIONS_NOTIFICATION_ID,
            LoginActivity.createGroupInvitationsNotificationIntent(context, inbox.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(GROUP_INVITATIONS_NOTIFICATION_ID, NotificationCompat.Builder(context, NotificationChannels.GROUP_INVITATIONS)
            .setSilent(isSilentForGeneralNonChat())
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentTitle(context.getString(R.string.group_invitations_title_plural)).setContentText(text)
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
        val title = context.getString(R.string.contact_requests_title_plural)
        val text = context.getQuantityNumberSentence(
            count = count,
            oneBeforeRes = R.string.notification_contact_requests_singular_you_singular_before_number,
            oneAfterRes = R.string.notification_contact_requests_singular_you_singular_after_number,
            otherBeforeRes = R.string.notification_contact_requests_plural_you_singular_before_number,
            otherAfterRes = R.string.notification_contact_requests_plural_you_singular_after_number,
        )
        val openRequests = PendingIntent.getActivity(context, CONTACT_REQUESTS_NOTIFICATION_ID,
            LoginActivity.createContactRequestsNotificationIntent(context, inbox.accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(CONTACT_REQUESTS_NOTIFICATION_ID,
            NotificationCompat.Builder(context, NotificationChannels.CONTACT_REQUESTS)
                .setSilent(isSilentForGeneralNonChat())
                .setSmallIcon(R.drawable.ic_notification_msngr)
                .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openRequests)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setNumber(count).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setOnlyAlertOnce(!newRequests)
                .setOngoing(true).setAutoCancel(false).build())
        requestAccount = inbox.accountId
        requestKeys = keys
    }

    fun showOfflineMessagesReceived() {
        if (!canPostNotifications()) return
        val text = context.getString(R.string.notification_offline_messages_received_you_singular)
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
                .setSilent(isSilentForGeneralNonChat())
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
        val currentAccount = context.notificationAccountKey()
        if (event.accountKey != null && event.accountKey != currentAccount) return
        if (InAppNotificationPrefs.read(context).conversations[event.contactId.notificationKey()]?.backgroundEnabled == false) {
            dismiss(notificationId(event.type, event.contactId))
            return
        }
        if (!canPostNotifications()) return
        val displayEvent = event.copy(
            accountKey = event.accountKey ?: currentAccount,
            contactName = event.contactName.messengerPlusPlainText(),
            messageText = event.messageText?.messengerPlusPlainText(),
            senderName = event.senderName?.messengerPlusPlainText(),
        )
        val contactAvatar = context.loadAvatarBitmap(displayEvent.contactAvatarUri)
            ?: context.shortcutAvatarBitmap(displayEvent.contactName, null)
        if (context.notificationAccountKey() != displayEvent.accountKey) return
        val contactIcon = IconCompat.createWithAdaptiveBitmap(contactAvatar)
        val contact = contactPerson(displayEvent, contactIcon)
        val posted = when (displayEvent.type) {
            NotificationEventType.Message -> showMessage(displayEvent, contact, contactIcon)
            NotificationEventType.Nudge -> showNudge(displayEvent, contact, contactIcon)
            NotificationEventType.ContactSignedIn -> showContactSignedIn(displayEvent, contact, contactIcon)
        }
        if (posted) {
            val targetId = notificationId(displayEvent.type, displayEvent.contactId)
            repeat(20) {
                if (systemActiveNotification(targetId) != null) return
                delay(10)
            }
        }
    }

    fun appendReply(
        contactId: String,
        contactName: String,
        replyText: String,
        notificationId: Int = notificationId(NotificationEventType.Message, contactId),
        previousNotification: Notification? = activeNotification(notificationId, contactId),
        isGroup: Boolean = false,
        sentAtMillis: Long = System.currentTimeMillis(),
        accountKey: String? = context.notificationAccountKey(),
        actionId: UUID? = null,
    ) {
        if (context.notificationAccountKey() != accountKey || !canPostNotifications()) return
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
            accountKey = accountKey,
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
        if (actionId != null) notification.extras.putString(EXTRA_ACTION_ID, actionId.toString())
        notify(notificationId, notification, allowWhenForeground = true)
    }

    fun ensureReplyShown(
        contactId: String,
        contactName: String,
        replyText: String,
        notificationId: Int = notificationId(NotificationEventType.Message, contactId),
        isGroup: Boolean = false,
        accountKey: String? = context.notificationAccountKey(),
    ) {
        val active = activeNotification(notificationId, contactId, accountKey) ?: return
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
                accountKey = accountKey,
            )
        }
    }

    fun markReplying(
        contactId: String,
        contactName: String,
        notificationId: Int,
        previousNotification: Notification? = activeNotification(notificationId, contactId),
    ) {
        if (!canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                NotificationChannels.MESSAGES, contactId, contactName, context.getString(R.string.notification_sending_reply),
                previousNotification,
            )
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .build(),
            allowWhenForeground = true,
        )
    }

    fun markReplyFailed(
        contactId: String, contactName: String, notificationId: Int, accountKey: String? = context.notificationAccountKey(),
    ) {
        if (context.notificationAccountKey() != accountKey || !canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                NotificationChannels.MESSAGES, contactId, contactName,
                context.getString(R.string.notification_reply_failed),
                activeNotification(notificationId, contactId, accountKey), accountKey,
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
        accountKey: String? = context.notificationAccountKey(),
        actionId: UUID? = null,
    ) {
        if (context.notificationAccountKey() != accountKey || !canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                channelId, contactId, contactName,
                context.getString(R.string.notification_sending_nudge),
                activeNotification(notificationId, contactId, accountKey),
                accountKey,
            )
                .addExtras(Bundle().apply { putString(EXTRA_ACTION_ID, actionId?.toString()) })
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
        accountKey: String? = context.notificationAccountKey(),
    ) {
        if (context.notificationAccountKey() != accountKey || !canPostNotifications()) return
        notify(
            notificationId,
            actionStatusBuilder(
                channelId, contactId, contactName,
                context.getString(
                    R.string.notification_nudge_failed_text,
                    context.getString(R.string.notification_nudge_failed_title),
                ),
                activeNotification(notificationId, contactId, accountKey), accountKey,
            )
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
            allowWhenForeground = true,
        )
    }

    fun dismiss(notificationId: Int) = synchronized(recentNotifications) {
        recentNotifications.remove(notificationId)
        systemNotifications.cancel(notificationId)
    }

    fun dismissAction(notificationId: Int, accountKey: String, actionId: UUID) = synchronized(recentNotifications) {
        val latest = latestNotification(notificationId, systemActiveNotification(notificationId))
        if (latest?.extras?.getString(EXTRA_ACTION_ID) == actionId.toString() &&
            latest.extras.getString(NOTIFICATION_ACCOUNT) == accountKey
        ) dismiss(notificationId)
    }

    fun dismissContactSignedInNotifications(
        scheduleRecheck: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).postDelayed(it, SIGN_IN_CANCEL_RECHECK_MILLIS) },
    ) {
        val signIns = systemNotifications.activeNotifications.filter {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.notification.channelId == NotificationChannels.CONTACT_SIGNED_IN
            } else {
                it.id in CONTACT_SIGNED_IN_ID_BASE until CONTACT_SIGNED_IN_ID_BASE + EVENT_NOTIFICATION_ID_COUNT
            }
        }
        if (signIns.isEmpty()) return
        val posted = signIns.associate { it.key to it.postIdentity() }
        signIns.forEach(::cancelPostedSignIn)

        // Android can silently drop rapid cancellations of the same key.
        scheduleRecheck {
            systemNotifications.activeNotifications.forEach {
                if (posted[it.key] == it.postIdentity()) cancelPostedSignIn(it)
            }
        }
    }

    private fun StatusBarNotification.postIdentity() =
        postTime to notification.extras.getLong(EXTRA_POST_SEQUENCE)

    private fun cancelPostedSignIn(posted: StatusBarNotification) = synchronized(recentNotifications) {
        val latest = recentNotifications[posted.id]
        if (latest == null || latest.extras.getLong(EXTRA_POST_SEQUENCE) <= posted.notification.extras.getLong(EXTRA_POST_SEQUENCE)) {
            recentNotifications.remove(posted.id)
            systemNotifications.cancel(posted.tag, posted.id)
        }
    }

    private fun activeNotification(
        notificationId: Int,
        contactId: String,
        accountKey: String? = context.notificationAccountKey(),
    ): Notification? {
        val systemNotification = systemActiveNotification(notificationId)
        if (systemNotification == null) {
            recentNotifications.remove(notificationId)
            return null
        }
        return latestNotification(notificationId, systemNotification)?.takeIf {
            it.extras.getString(NOTIFICATION_ACCOUNT) == accountKey && it.extras.getString(EXTRA_CONVERSATION) == contactId
        }
    }

    private fun latestNotification(notificationId: Int, posted: Notification?): Notification? {
        val cached = recentNotifications[notificationId]
        if (posted == null) return cached
        val postedSequence = posted.extras.getLong(EXTRA_POST_SEQUENCE)
        return cached?.takeIf { it.extras.getLong(EXTRA_POST_SEQUENCE) > postedSequence } ?: posted
    }

    private fun systemActiveNotification(notificationId: Int): Notification? =
        systemNotifications.activeNotifications
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
        val previousNotification = activeNotification(notificationId, event.contactId, event.accountKey)
        val previousStyle = previousNotification?.let(
            NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification,
        )
        val keepExistingMessages = previousNotification?.extras?.getBoolean(EXTRA_IS_ACTION_STATUS) != true
        val messageSender = if (event.isGroup) groupSenderPerson(event) else sender
        if (context.notificationAccountKey() != event.accountKey) return false
        val shortcut = publishConversationShortcut(event, sender, contactIcon)
        val style = messagingStyle().setGroupConversation(event.isGroup).also { messagingStyle ->
            if (event.isGroup) {
                messagingStyle.setConversationTitle(event.contactName)
            }
            if (keepExistingMessages) {
                previousStyle?.messages
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
        val memberFallback = context.getString(R.string.common_member_fallback_singular)
        val senderLabel = (event.senderName ?: event.senderEmail ?: memberFallback)
            .messengerPlusPlainText()
            .ifBlank { memberFallback }
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
            accountKey = event.accountKey,
        )
        val nudgeAction = nudgeAction(
            event = event,
            notificationId = notificationId,
            channelId = NotificationChannels.MESSAGES,
            label = context.getString(R.string.notification_nudge_label_singular),
        )
        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_public_new_message))
            .build()

        return baseBuilder(
            NotificationChannels.MESSAGES,
            event.contactId,
            event.contactName,
            sender,
            isChat = true,
            forceSilent = forceSilent,
            accountKey = event.accountKey,
        )
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
            .addAction(markReadAction(event.contactId, event.accountKey))
            .setAutoCancel(true)
            .build()
    }

    private fun showNudge(event: NotificationEvent, contact: Person, contactIcon: IconCompat): Boolean {
        val notificationId = notificationId(NotificationEventType.Nudge, event.contactId)
        val shortcut = publishConversationShortcut(event, contact, contactIcon)
        val style = messagingStyle()
            .setGroupConversation(event.isGroup)
            .also { if (event.isGroup) it.setConversationTitle(event.contactName) }
            .addMessage(context.getString(R.string.chat_nudge_received_you_singular), event.sentAtMillis, contact)
        val notification = baseBuilder(
            NotificationChannels.NUDGES,
            event.contactId,
            event.contactName,
            contact,
            accountKey = event.accountKey,
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
            accountKey = event.accountKey,
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
                    label = context.getString(R.string.notification_nudge_label_singular),
                ),
            )
            .setAutoCancel(true)
            .build()
        return notify(notificationId, notification)
    }

    private fun messagingStyle(): NotificationCompat.MessagingStyle = NotificationCompat.MessagingStyle(
        Person.Builder()
            .setName(context.getString(R.string.notification_self_name_you_singular))
            .setKey(SELF_PERSON_KEY)
            .build(),
    )

    private fun actionStatusBuilder(
        channelId: String,
        contactId: String,
        contactName: String,
        text: String,
        previousNotification: Notification?,
        accountKey: String? = context.notificationAccountKey(),
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
            val event = NotificationEvent(
                NotificationEventType.Message, contactId, name, accountKey = accountKey,
            )
            val icon = resolveConversationIcon(
                contactId = contactId,
                contactName = name,
                previousNotification = previousNotification,
                previousStyle = previousStyle,
                isGroup = previousStyle?.isGroupConversation == true,
            )
            contact = contactPerson(event, icon)
            builder = baseBuilder(channelId, contactId, name, contact, accountKey = accountKey)
                .setShortcutInfo(publishConversationShortcut(event, contact, icon))
            style = messagingStyle().setGroupConversation(false)
        }
        // MessagingStyle lets Android place the avatar with its app badge in the header.
        // A large icon on a plain notification is rendered as trailing content instead.
        val isChat = channelId == NotificationChannels.MESSAGES
        return builder
            .setChannelId(channelId)
            .setSilent(isSilentForConversation(contactId, isChat = isChat))
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
        context.appContainer.currentRepository?.contactSnapshot(contactId)?.avatarUri?.let { uri ->
            cachedAvatarBitmap(uri)?.let { return IconCompat.createWithAdaptiveBitmap(it) }
        }
        previousNotification?.getLargeIcon()?.let {
            return IconCompat.createFromIcon(context, it)
        }
        return IconCompat.createWithAdaptiveBitmap(context.shortcutAvatarBitmap(contactName, null))
    }

    private fun baseBuilder(
        channelId: String,
        contactId: String,
        contactName: String,
        contact: Person,
        isChat: Boolean = false,
        forceSilent: Boolean = false,
        accountKey: String? = context.notificationAccountKey(),
    ): NotificationCompat.Builder {
        val shortcutId = conversationShortcutId(accountKey, contactId)
        return NotificationCompat.Builder(context, channelId)
            .setSilent(isSilentForConversation(contactId, isChat = isChat, forceSilent = forceSilent))
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentIntent(contentIntent(accountKey, contactId, contactName))
            .addExtras(Bundle().apply {
                putString(NOTIFICATION_ACCOUNT, accountKey)
                putString(EXTRA_CONVERSATION, contactId)
            })
            .setColorized(false)
            .setGroup("msngr_events")
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(contact)
    }

    private fun currentPresence(): Presence =
        attachedPresence?.invoke() ?: resolveOwnPresence(context)

    private fun isSilentForGeneralNonChat(): Boolean =
        InAppNotificationPrefs.read(context).silencesPush(currentPresence(), isChat = false)

    private fun isSilentForConversation(
        contactId: String,
        isChat: Boolean,
        forceSilent: Boolean = false,
    ): Boolean {
        if (forceSilent) return true
        val settings = InAppNotificationPrefs.read(context)
        val conversationSilent = settings.conversations[contactId.notificationKey()]?.backgroundSilent == true
        return conversationSilent || settings.silencesPush(currentPresence(), isChat = isChat)
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
        accountKey: String?,
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
            bindNotificationAccount(accountKey)
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

    private fun markReadAction(contactId: String, accountKey: String?): NotificationCompat.Action {
        val intent = Intent(context, NotificationMarkReadReceiver::class.java).apply {
            putExtra(NotificationMarkReadReceiver.EXTRA_CONTACT_ID, contactId)
            data = android.net.Uri.Builder().scheme("msngr").authority("mark-read")
                .appendPath(contactId).build()
            bindNotificationAccount(accountKey)
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
            bindNotificationAccount(event.accountKey)
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
        val shortcutId = conversationShortcutId(event.accountKey, event.contactId)
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
                    event.accountKey,
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

    private fun contentIntent(accountKey: String?, contactId: String, contactName: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(NotificationEventType.Message, contactId),
            LoginActivity.createNotificationIntent(context, contactId, contactName, accountKey),
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
        if (notification.extras.containsKey(NOTIFICATION_ACCOUNT) &&
            notification.extras.getString(NOTIFICATION_ACCOUNT) != context.notificationAccountKey()
        ) return false
        return synchronized(recentNotifications) {
            if (!allowWhenForeground && isForeground()) return@synchronized false
            if (!canPostNotifications()) return@synchronized false
            notification.extras.putLong(EXTRA_POST_SEQUENCE, SystemClock.elapsedRealtimeNanos())
            recentNotifications.put(notificationId, notification)
            try {
                systemNotifications.notify(notificationId, notification)
                true
            } catch (_: SecurityException) {
                recentNotifications.remove(notificationId)
                false
            }
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
        private const val EXTRA_ACTION_ID = "msngr_action_id"
        private const val EXTRA_IS_ACTION_STATUS = "msngr_is_action_status"
        private const val EXTRA_CONVERSATION = "msngr_conversation"
        private const val SIGN_IN_CANCEL_RECHECK_MILLIS = 1_000L
        private const val EXTRA_POST_SEQUENCE = "msngr_post_sequence"
        private val recentNotifications = LruCache<Int, Notification>(32)

        internal fun resolveOwnPresence(context: Context): Presence {
            val livePresence = (context.applicationContext as? MsngrApplication)
                ?.container
                ?.currentRepository
                ?.takeIf { it.hasSignedInSession }
                ?.profile
                ?.value
                ?.presence
            if (livePresence != null) return livePresence
            return if (SessionPrefs.preferenceAccount(context) != null) {
                SessionPrefs.readUserProfile(context).presence
            } else {
                Presence.Online
            }
        }

        internal fun shouldSilenceNonChatNotification(context: Context): Boolean =
            InAppNotificationPrefs.read(context).silencesPush(resolveOwnPresence(context), isChat = false)

        internal fun conversationShortcutId(accountKey: String?, contactId: String): String {
            val contact = contactId.lowercase()
            val identity = if (accountKey == null) contact else "$accountKey\u0000$contact"
            return "msngr:${UUID.nameUUIDFromBytes(identity.toByteArray())}"
        }

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
