package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Data
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.withoutUnicodeEmoji
import java.util.UUID

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountKey = intent.getStringExtra(NOTIFICATION_ACCOUNT) ?: return
        if (context.notificationAccountKey() != accountKey) return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.withoutUnicodeEmoji()
            ?.trim()
            ?.takeIf { it.messengerPlusPlainText().isNotBlank() }
            ?.take(MAX_REPLY_LENGTH)
            ?: return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)?.takeIf(String::isNotBlank) ?: return
        val repository = context.appContainer.currentRepository
        val contactSnapshot = repository?.contactSnapshot(contactId)
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?.takeIf(String::isNotBlank)
            ?: contactSnapshot?.displayName
            ?: contactId.substringBefore('@')
        val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, contactSnapshot?.isGroup == true)
        val notificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId),
        )
        val notifications = MsngrNotificationManager(context)
        val input = try {
            Data.Builder()
                .putString(NOTIFICATION_ACCOUNT, accountKey)
                .putString(NOTIFICATION_CONTACT_ID, contactId)
                .putString(NOTIFICATION_CONTACT_NAME, contactName)
                .putString(ReplyMessageWorker.KEY_REPLY_TEXT, reply)
                .putInt(ACTION_NOTIFICATION_ID, notificationId)
                .putBoolean(ReplyMessageWorker.KEY_IS_GROUP, isGroup)
                .build()
        } catch (_: IllegalStateException) {
            // WorkManager limits serialized bytes. A multibyte reply may exceed its budget.
            notifications.markReplyFailed(contactId, contactName, notificationId, accountKey)
            return
        }
        repository?.markRead(contactId, dismissNotification = false)
        notifications.dismiss(MsngrNotificationManager.notificationId(NotificationEventType.Nudge, contactId))
        (context.applicationContext as? MsngrApplication)?.inAppNotifications?.dismissConversation(contactId)
        val actionId = UUID.randomUUID()
        notifications.appendReply(
            contactId = contactId,
            contactName = contactName,
            replyText = reply,
            notificationId = notificationId,
            isGroup = isGroup,
            accountKey = accountKey,
            actionId = actionId,
        )
        context.enqueueNotificationAction<ReplyMessageWorker>(input, actionId)
    }

    companion object {
        const val KEY_TEXT_REPLY = "msngr_notification_reply"
        const val EXTRA_CONTACT_ID = "notification_contact_id"
        const val EXTRA_CONTACT_NAME = "notification_contact_name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_IS_GROUP = "notification_is_group"
        private const val MAX_REPLY_LENGTH = 4_000
    }
}
