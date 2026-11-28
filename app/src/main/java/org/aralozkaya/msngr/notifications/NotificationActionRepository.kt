package org.aralozkaya.msngr.notifications

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.accountIdentity
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.protocol.MessageNotSubmittedException
import org.aralozkaya.msngr.data.SessionUnavailableException
import org.aralozkaya.msngr.restoreRememberedRepository
import org.aralozkaya.msngr.restoreSignedInProfile
import java.util.UUID

internal const val NOTIFICATION_ACCOUNT = "notification_account"
internal const val NOTIFICATION_CONTACT_ID = "contact_id"
internal const val NOTIFICATION_CONTACT_NAME = "contact_name"
internal const val ACTION_NOTIFICATION_ID = "notification_id"
private const val MAX_ACTION_RETRIES = 2

internal fun Context.notificationAccountKey(): String? =
    (appContainer.currentRepository?.signedInAccountIdentity ?: SessionPrefs.preferenceAccount(this))?.storageKey

internal fun Intent.bindNotificationAccount(accountKey: String?) {
    putExtra(NOTIFICATION_ACCOUNT, accountKey)
    // PendingIntent identity ignores extras. Include the owner in the URI as well.
    if (accountKey != null) data = requireNotNull(data).buildUpon()
        .appendQueryParameter("account", accountKey).build()
}

internal inline fun <reified W : ListenableWorker> Context.enqueueNotificationAction(input: Data, actionId: UUID) {
    val request = OneTimeWorkRequestBuilder<W>()
        .setId(actionId)
        .setInputData(input)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    // Earlier Android versions implement expedited work as a foreground service.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }
    WorkManager.getInstance(this).enqueue(request.build())
}

internal suspend fun CoroutineWorker.deliverNudgeOrReply(reply: String?): WorkResult {
    val account = inputData.getString(NOTIFICATION_ACCOUNT) ?: return WorkResult.failure()
    val contactId = inputData.getString(NOTIFICATION_CONTACT_ID)?.takeIf(String::isNotBlank)
        ?: return WorkResult.failure()
    fun sameAccount() = applicationContext.notificationAccountKey() == account
    val contactName = inputData.getString(NOTIFICATION_CONTACT_NAME)?.takeIf(String::isNotBlank)
        ?: contactId.substringBefore('@')
    val notificationId = inputData.getInt(ACTION_NOTIFICATION_ID,
        MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId))
    val notifications = MsngrNotificationManager(applicationContext)
    fun abandon(): WorkResult {
        notifications.dismissAction(notificationId, account, id)
        return WorkResult.failure()
    }
    if (!sameAccount()) return abandon()
    fun deliveryFailed(): WorkResult {
        if (reply == null) {
            val channel = NotificationChannels.nudgeChannel(inputData.getString(NudgeContactWorker.KEY_CHANNEL_ID))
            notifications.markNudgeFailed(contactId, contactName, notificationId, channel, account)
        } else {
            notifications.markReplyFailed(contactId, contactName, notificationId, account)
        }
        return WorkResult.failure()
    }

    val repository = try {
        applicationContext.connectedRepositoryForNotificationAction(account)
    } catch (_: NotificationActionSessionRestoreException) {
        if (runAttemptCount < MAX_ACTION_RETRIES && sameAccount()) return WorkResult.retry()
        null
    }
    if (!sameAccount()) return abandon()
    if (repository == null) return deliveryFailed()
    val sent = repository.sendNotificationAction(account, contactId, reply)
    val failure = sent.exceptionOrNull()
    if (failure is CancellationException) throw failure
    if (failure is SessionUnavailableException || !sameAccount()) return abandon()
    if (failure != null) {
        if (failure is MessageNotSubmittedException && runAttemptCount < MAX_ACTION_RETRIES) return WorkResult.retry()
        return deliveryFailed()
    }
    val contact = sent.getOrNull() ?: return deliveryFailed()
    if (reply == null) notifications.dismissAction(notificationId, account, id) else notifications.ensureReplyShown(
        contactId, contact.displayName.ifBlank { contactName }, reply, notificationId,
        isGroup = contact.isGroup || inputData.getBoolean(ReplyMessageWorker.KEY_IS_GROUP, false),
        accountKey = account,
    )
    return WorkResult.success()
}

internal class NotificationActionSessionRestoreException(cause: Throwable) :
    Exception("Could not restore the notification action session", cause)

/**
 * Returns an active repository, reconnecting or restoring the remembered session if necessary.
 * A null result requires sign-in; a failed connection attempt throws so workers can retry it.
 */
internal suspend fun Context.connectedRepositoryForNotificationAction(
    accountKey: String? = notificationAccountKey(),
): MessengerRepository? {
    val appContext = applicationContext
    if (accountKey == null || accountKey != notificationAccountKey() || SessionPrefs.wasRemotelySignedOut(appContext)) return null
    val app = appContext as MsngrApplication
    val existing = app.container.currentRepository
    if (
        existing != null &&
        existing.hasSignedInSession &&
        existing.connectionIssue.value == null
    ) {
        return existing
    }
    var reconnectFailure: Throwable? = null
    if (existing?.hasSignedInSession == true) {
        val owner = existing.signedInAccountIdentity?.takeIf { it.storageKey == accountKey } ?: return null
        val reconnect = existing.reconnect(owner)
        reconnectFailure = reconnect.exceptionOrNull()
        if (reconnectFailure is CancellationException) throw reconnectFailure
        val refreshed = reconnect.getOrNull()
        if (refreshed != null) {
            appContext.restoreSignedInProfile(existing, refreshed, refreshed.presence, owner)
            return existing
        }
    }

    val snapshot = SessionPrefs.readLoginSnapshot(appContext)
    if (snapshot.accountIdentity.storageKey != accountKey) return null
    if (!snapshot.savePassword || snapshot.email.isBlank() || snapshot.password.isEmpty()) {
        // The in-memory session can still reconnect even when its credentials were not saved.
        reconnectFailure?.let { throw NotificationActionSessionRestoreException(it) }
        return null
    }
    return appContext.restoreRememberedRepository(snapshot).getOrElse { failure ->
        if (failure is CancellationException) throw failure
        if (failure is SessionUnavailableException) return null
        throw NotificationActionSessionRestoreException(failure)
    }
}
