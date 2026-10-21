package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class NotificationNudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)?.takeIf(String::isNotBlank) ?: return
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?.takeIf(String::isNotBlank)
            ?: contactId.substringBefore('@')
        val notificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId),
        )
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
            ?.takeIf(ALLOWED_CHANNEL_IDS::contains)
            ?: NotificationChannels.MESSAGES
        MsngrNotificationManager(context).markNudging(
            contactId,
            contactName,
            notificationId,
            channelId,
        )
        val input = Data.Builder()
            .putString(NudgeContactWorker.KEY_CONTACT_ID, contactId)
            .putString(NudgeContactWorker.KEY_CONTACT_NAME, contactName)
            .putInt(NudgeContactWorker.KEY_NOTIFICATION_ID, notificationId)
            .putString(NudgeContactWorker.KEY_CHANNEL_ID, channelId)
            .build()
        val request = OneTimeWorkRequestBuilder<NudgeContactWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "nudge:$contactId:${System.currentTimeMillis()}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val EXTRA_CONTACT_ID = "nudge_contact_id"
        const val EXTRA_CONTACT_NAME = "nudge_contact_name"
        const val EXTRA_NOTIFICATION_ID = "nudge_notification_id"
        const val EXTRA_CHANNEL_ID = "nudge_channel_id"
        private val ALLOWED_CHANNEL_IDS = setOf(
            NotificationChannels.MESSAGES,
            NotificationChannels.NUDGES,
            NotificationChannels.CONTACT_SIGNED_IN,
        )
    }
}
