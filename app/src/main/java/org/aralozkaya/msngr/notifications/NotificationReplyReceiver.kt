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
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.withoutUnicodeEmoji

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.withoutUnicodeEmoji()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_REPLY_LENGTH)
            ?: return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)?.takeIf(String::isNotBlank) ?: return
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?.takeIf(String::isNotBlank)
            ?: contactId.substringBefore('@')
        val notificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId),
        )
        val notifications = MsngrNotificationManager(context)
        val previousNotification = notifications.activeNotification(notificationId)
        // Mark the conversation read when the reply is submitted, before network
        // work can be delayed or retried and newer messages can arrive.
        runCatching { context.appContainer.messengerRepository }.getOrNull()?.markRead(contactId)
        notifications.apply {
            dismissConversation(contactId)
            markReplying(contactId, contactName, notificationId, previousNotification)
        }
        val input = Data.Builder()
            .putString(ReplyMessageWorker.KEY_CONTACT_ID, contactId)
            .putString(ReplyMessageWorker.KEY_CONTACT_NAME, contactName)
            .putString(ReplyMessageWorker.KEY_REPLY_TEXT, reply)
            .putInt(ReplyMessageWorker.KEY_NOTIFICATION_ID, notificationId)
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
        private const val MAX_REPLY_LENGTH = 4_000
    }
}
