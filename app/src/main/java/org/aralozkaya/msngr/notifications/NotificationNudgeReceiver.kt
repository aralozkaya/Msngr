package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data

class NotificationNudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountKey = intent.getStringExtra(NOTIFICATION_ACCOUNT) ?: return
        if (context.notificationAccountKey() != accountKey) return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)?.takeIf(String::isNotBlank) ?: return
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?.takeIf(String::isNotBlank)
            ?: contactId.substringBefore('@')
        val notificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId),
        )
        val channelId = NotificationChannels.nudgeChannel(intent.getStringExtra(EXTRA_CHANNEL_ID))
        val notifications = MsngrNotificationManager(context)
        val input = try {
            Data.Builder()
                .putString(NOTIFICATION_ACCOUNT, accountKey)
                .putString(NOTIFICATION_CONTACT_ID, contactId)
                .putString(NOTIFICATION_CONTACT_NAME, contactName)
                .putInt(ACTION_NOTIFICATION_ID, notificationId)
                .putString(NudgeContactWorker.KEY_CHANNEL_ID, channelId)
                .build()
        } catch (_: IllegalStateException) {
            notifications.markNudgeFailed(contactId, contactName, notificationId, channelId)
            return
        }
        notifications.markNudging(
            contactId,
            contactName,
            notificationId,
            channelId,
        )
        context.enqueueNotificationAction<NudgeContactWorker>(input)
    }

    companion object {
        const val EXTRA_CONTACT_ID = "nudge_contact_id"
        const val EXTRA_CONTACT_NAME = "nudge_contact_name"
        const val EXTRA_NOTIFICATION_ID = "nudge_notification_id"
        const val EXTRA_CHANNEL_ID = "nudge_channel_id"
    }
}
