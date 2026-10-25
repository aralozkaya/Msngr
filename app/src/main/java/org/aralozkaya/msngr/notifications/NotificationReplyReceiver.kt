package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.withoutUnicodeEmoji

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.withoutUnicodeEmoji()
            ?.trim()
            ?.takeIf { it.messengerPlusPlainText().isNotBlank() }
            ?.take(MAX_REPLY_LENGTH)
            ?: return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)?.takeIf(String::isNotBlank) ?: return
        val repository = runCatching { context.appContainer.messengerRepository }.getOrNull()
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
        val previousNotification = notifications.activeNotification(notificationId)
        // Mark the conversation read when the reply is submitted without cancelling
        // the active conversation notification, so the expanded shade and connected
        // smart watches continue showing the incoming message, our reply, and follow-ups.
        repository?.markRead(contactId, dismissNotification = false)
        notifications.dismiss(MsngrNotificationManager.notificationId(NotificationEventType.Nudge, contactId))
        (context.applicationContext as? MsngrApplication)?.inAppNotifications?.dismissConversation(contactId)
        notifications.appendReply(
            contactId = contactId,
            contactName = contactName,
            replyText = reply,
            notificationId = notificationId,
            previousNotification = previousNotification,
            isGroup = isGroup,
        )
        val input = Data.Builder()
            .putString(ReplyMessageWorker.KEY_CONTACT_ID, contactId)
            .putString(ReplyMessageWorker.KEY_CONTACT_NAME, contactName)
            .putString(ReplyMessageWorker.KEY_REPLY_TEXT, reply)
            .putInt(ReplyMessageWorker.KEY_NOTIFICATION_ID, notificationId)
            .putBoolean(ReplyMessageWorker.KEY_IS_GROUP, isGroup)
            .build()
        val request = OneTimeWorkRequestBuilder<ReplyMessageWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reply:$contactId:${System.currentTimeMillis()}",
            ExistingWorkPolicy.KEEP,
            request,
        )
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
