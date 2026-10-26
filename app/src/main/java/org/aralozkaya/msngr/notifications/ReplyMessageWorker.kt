package org.aralozkaya.msngr.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal class ReplyMessageWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val contactId = inputData.getString(KEY_CONTACT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val contactName = inputData.getString(KEY_CONTACT_NAME)?.takeIf(String::isNotBlank)
            ?: contactId.substringBefore('@')
        val text = inputData.getString(KEY_REPLY_TEXT)?.trim()?.takeIf(String::isNotEmpty)
            ?: return Result.failure()
        val notificationId = inputData.getInt(
            KEY_NOTIFICATION_ID,
            MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId),
        )
        val isGroupInput = inputData.getBoolean(KEY_IS_GROUP, false)
        val notifications = MsngrNotificationManager(applicationContext)
        val repository = try {
            applicationContext.connectedRepositoryForNotificationAction()
        } catch (_: NotificationActionSessionRestoreException) {
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
            null
        }
            ?: return finalFailure(
                notifications,
                contactId,
                contactName,
                notificationId,
            )
        val contact = repository.contactSnapshot(contactId)
        if (contact == null || contact.isBlocked) {
            return finalFailure(notifications, contactId, contactName, notificationId)
        }
        repository.markRead(contactId, dismissNotification = false)
        val sent = repository.sendMessageNow(contactId, text)
        if (sent.isSuccess) {
            notifications.ensureReplyShown(
                contactId = contactId,
                contactName = contact.displayName.ifBlank { contactName },
                replyText = text,
                notificationId = notificationId,
                isGroup = contact.isGroup || isGroupInput,
            )
            return Result.success()
        }
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        return finalFailure(notifications, contactId, contactName, notificationId)
    }

    private fun finalFailure(
        notifications: MsngrNotificationManager,
        contactId: String,
        contactName: String,
        notificationId: Int,
    ): Result {
        notifications.markReplyFailed(contactId, contactName, notificationId)
        return Result.failure()
    }

    companion object {
        const val KEY_CONTACT_ID = "contact_id"
        const val KEY_CONTACT_NAME = "contact_name"
        const val KEY_REPLY_TEXT = "reply_text"
        const val KEY_NOTIFICATION_ID = "notification_id"
        const val KEY_IS_GROUP = "is_group"
        private const val MAX_RETRIES = 2
    }
}
