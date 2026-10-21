package org.aralozkaya.msngr.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal class NudgeContactWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val contactId = inputData.getString(KEY_CONTACT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val contactName = inputData.getString(KEY_CONTACT_NAME)?.takeIf(String::isNotBlank)
            ?: contactId.substringBefore('@')
        val notificationId = inputData.getInt(
            KEY_NOTIFICATION_ID,
            MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId),
        )
        val channelId = inputData.getString(KEY_CHANNEL_ID)
            ?.takeIf(ALLOWED_CHANNEL_IDS::contains)
            ?: NotificationChannels.MESSAGES
        val notifications = MsngrNotificationManager(applicationContext)
        val repository = applicationContext.connectedRepositoryForNotificationAction()
            ?: return finalFailure(
                notifications,
                contactId,
                contactName,
                notificationId,
                channelId,
            )
        val contact = repository.contactSnapshot(contactId)
        if (contact == null || contact.isBlocked) {
            return finalFailure(
                notifications,
                contactId,
                contactName,
                notificationId,
                channelId,
            )
        }
        val sent = repository.sendNudgeNow(contactId)
        if (sent.isSuccess) {
            notifications.dismiss(notificationId)
            return Result.success()
        }
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        return finalFailure(notifications, contactId, contactName, notificationId, channelId)
    }

    private fun finalFailure(
        notifications: MsngrNotificationManager,
        contactId: String,
        contactName: String,
        notificationId: Int,
        channelId: String,
    ): Result {
        notifications.markNudgeFailed(contactId, contactName, notificationId, channelId)
        return Result.failure()
    }

    companion object {
        const val KEY_CONTACT_ID = "contact_id"
        const val KEY_CONTACT_NAME = "contact_name"
        const val KEY_NOTIFICATION_ID = "notification_id"
        const val KEY_CHANNEL_ID = "channel_id"
        private const val MAX_RETRIES = 2
        private val ALLOWED_CHANNEL_IDS = setOf(
            NotificationChannels.MESSAGES,
            NotificationChannels.NUDGES,
            NotificationChannels.CONTACT_SIGNED_IN,
        )
    }
}
