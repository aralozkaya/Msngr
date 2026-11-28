package org.aralozkaya.msngr.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.ChatActivity
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.FileTransferStatus
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.PhotoShareStatus
import org.aralozkaya.msngr.data.resolveContact
import org.aralozkaya.msngr.messengerPlusPlainText
import java.util.concurrent.ConcurrentHashMap

internal class SharingNotificationController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var attachJob: Job? = null
    private var attachedRepository: MessengerRepository? = null
    private val activeNotifications = ConcurrentHashMap<String, PostedState>()
    private val assignedIds = ConcurrentHashMap<String, Int>()

    private data class ShareNotificationSpec(
        val key: String,
        val kind: String,
        val contactId: String,
        val shareId: String,
        val statusKey: String,
        val title: String,
        val contentText: String,
        val actionLabel: String,
        val sharedBytes: Long,
        val totalBytes: Long,
        val indeterminate: Boolean,
        val isPhotoShare: Boolean,
    )

    private data class PostedState(
        val notificationId: Int,
        val statusKey: String,
        val title: String,
        val contentText: String,
        val actionLabel: String,
        val indeterminate: Boolean,
        val lastPostedAtNanos: Long,
    )

    @Synchronized
    fun attach(repository: MessengerRepository) {
        attachJob?.cancel()
        attachedRepository = repository
        dismissAll()
        attachJob = scope.launch {
            combine(
                repository.liveTransferMessages,
                repository.photoShares,
                repository.contacts,
                repository.profile,
            ) { _, _, _, _ -> Unit }.collect {
                val owner = repository.signedInAccountIdentity
                val specs = if (owner == null) emptyList() else buildSpecs(
                    owner.storageKey, repository.liveTransferMessages.value, repository.photoShares.value, repository.contacts.value,
                )
                if (owner != null && context.notificationAccountKey() != owner.storageKey) return@collect
                syncNotifications(repository, owner?.storageKey, specs)
            }
        }
    }

    @Synchronized
    fun dismissAll() {
        val manager = NotificationManagerCompat.from(context)
        activeNotifications.values.forEach { manager.cancel(it.notificationId) }
        activeNotifications.clear()
        assignedIds.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sysManager = context.getSystemService(NotificationManager::class.java)
            sysManager?.activeNotifications
                ?.filter { it.notification.channelId == NotificationChannels.SHARING }
                ?.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    private fun buildSpecs(
        accountKey: String,
        messages: Map<String, List<org.aralozkaya.msngr.data.ChatMessage>>,
        photoShares: Map<String, org.aralozkaya.msngr.data.PhotoShare>,
        contacts: List<Contact>,
    ): List<ShareNotificationSpec> {
        val result = mutableListOf<ShareNotificationSpec>()
        fun contactLabel(contactId: String): String =
            contacts.resolveContact(contactId)?.displayName?.messengerPlusPlainText()
                ?.takeIf(String::isNotBlank)
                ?: contactId

        messages.forEach { (contactId, list) ->
            val recipientName = contactLabel(contactId)
            list.forEach { msg ->
                val transfer = msg.fileTransfer ?: return@forEach
                if (!transfer.outgoing || transfer.photoId != null || !transfer.active) return@forEach
                val shared = if (transfer.status == FileTransferStatus.Offered) 0L
                else transfer.transferred.coerceIn(0L, transfer.size)
                val remaining = (transfer.size - shared).coerceAtLeast(0L)
                val title = if (transfer.status == FileTransferStatus.Offered) {
                    context.getString(
                        R.string.notification_sharing_file_waiting,
                        recipientName,
                        transfer.name,
                    )
                } else {
                    context.getString(
                        R.string.notification_sharing_file_sending,
                        transfer.name,
                        recipientName,
                    )
                }
                val contentText = context.getString(
                    R.string.notification_sharing_sizes,
                    Formatter.formatFileSize(context, shared),
                    Formatter.formatFileSize(context, remaining),
                )
                result += ShareNotificationSpec(
                    key = "$accountKey:${NotificationShareCancelReceiver.KIND_FILE}:$contactId:${transfer.id}",
                    kind = NotificationShareCancelReceiver.KIND_FILE,
                    contactId = contactId,
                    shareId = transfer.id,
                    statusKey = transfer.status.name,
                    title = title,
                    contentText = contentText,
                    actionLabel = context.getString(R.string.common_cancel),
                    sharedBytes = shared,
                    totalBytes = transfer.size,
                    indeterminate = transfer.status == FileTransferStatus.Offered,
                    isPhotoShare = false,
                )
            }
        }

        photoShares.forEach { (contactId, share) ->
            val ongoing = share.status in listOf(PhotoShareStatus.Invited, PhotoShareStatus.Active)
            val hasOutgoing = share.outgoing || share.photos.any { it.outgoing }
            if (!ongoing || !hasOutgoing) return@forEach
            val recipientName = contactLabel(contactId)
            val total = share.totalOutgoingBytes
            val shared = share.sharedOutgoingBytes.coerceIn(0L, total)
            val remaining = (total - shared).coerceAtLeast(0L)
            val isWaiting = share.status == PhotoShareStatus.Invited
            val title = if (isWaiting) {
                context.getString(R.string.notification_sharing_photos_waiting, recipientName)
            } else {
                context.getString(R.string.notification_sharing_photos_uploading, recipientName)
            }
            val contentText = context.getString(
                R.string.notification_sharing_sizes,
                Formatter.formatFileSize(context, shared),
                Formatter.formatFileSize(context, remaining),
            )
            result += ShareNotificationSpec(
                key = "$accountKey:${NotificationShareCancelReceiver.KIND_PHOTO}:$contactId:${share.id}",
                kind = NotificationShareCancelReceiver.KIND_PHOTO,
                contactId = contactId,
                shareId = share.id,
                statusKey = share.status.name,
                title = title,
                contentText = contentText,
                actionLabel = context.getString(
                    if (isWaiting) R.string.common_cancel else R.string.photo_sharing_stop_sharing,
                ),
                sharedBytes = shared,
                totalBytes = total,
                indeterminate = isWaiting || total <= 0L,
                isPhotoShare = true,
            )
        }
        return result
    }

    @Synchronized
    private fun syncNotifications(repository: MessengerRepository, accountKey: String?, specs: List<ShareNotificationSpec>) {
        if (attachedRepository !== repository || repository.signedInAccountIdentity?.storageKey != accountKey) return
        val manager = NotificationManagerCompat.from(context)
        val activeKeys = specs.mapTo(hashSetOf()) { it.key }
        val removed = activeNotifications.keys.filter { it !in activeKeys }
        removed.forEach { key ->
            activeNotifications.remove(key)?.let { manager.cancel(it.notificationId) }
            assignedIds.remove(key)
        }
        if (accountKey == null || !canPostNotifications()) return
        val now = System.nanoTime()
        for (spec in specs) {
            val id = allocateNotificationId(spec.key)
            val previous = activeNotifications[spec.key]
            if (previous != null) {
                val contentIdentical = previous.statusKey == spec.statusKey &&
                    previous.title == spec.title &&
                    previous.contentText == spec.contentText &&
                    previous.actionLabel == spec.actionLabel &&
                    previous.indeterminate == spec.indeterminate
                if (contentIdentical) continue
                val pureByteProgress = previous.statusKey == spec.statusKey &&
                    previous.title == spec.title &&
                    previous.actionLabel == spec.actionLabel &&
                    previous.indeterminate == spec.indeterminate &&
                    spec.sharedBytes > 0L &&
                    spec.sharedBytes < spec.totalBytes
                if (pureByteProgress && now - previous.lastPostedAtNanos < THROTTLE_NANOS) {
                    continue
                }
            }
            if (postNotification(manager, id, spec, accountKey)) {
                activeNotifications[spec.key] = PostedState(
                    notificationId = id,
                    statusKey = spec.statusKey,
                    title = spec.title,
                    contentText = spec.contentText,
                    actionLabel = spec.actionLabel,
                    indeterminate = spec.indeterminate,
                    lastPostedAtNanos = now,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(
        manager: NotificationManagerCompat,
        notificationId: Int,
        spec: ShareNotificationSpec,
        accountKey: String,
    ): Boolean {
        val openIntent = ChatActivity.createIntent(
            context = context,
            contactId = spec.contactId,
            openPhotoShare = spec.isPhotoShare,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data = Uri.Builder()
                .scheme("msngr")
                .authority("share-open")
                .appendPath(spec.kind)
                .appendPath(spec.contactId)
                .appendPath(spec.shareId)
                .build()
            bindNotificationAccount(accountKey)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = Intent(context, NotificationShareCancelReceiver::class.java).apply {
            putExtra(NotificationShareCancelReceiver.EXTRA_KIND, spec.kind)
            putExtra(NotificationShareCancelReceiver.EXTRA_CONTACT_ID, spec.contactId)
            putExtra(NotificationShareCancelReceiver.EXTRA_SHARE_ID, spec.shareId)
            putExtra(NotificationShareCancelReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            data = Uri.Builder()
                .scheme("msngr")
                .authority("share-cancel")
                .appendPath(spec.kind)
                .appendPath(spec.contactId)
                .appendPath(spec.shareId)
                .build()
            bindNotificationAccount(accountKey)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + CANCEL_REQUEST_CODE_OFFSET,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelAction = NotificationCompat.Action.Builder(0, spec.actionLabel, cancelPendingIntent)
            .setShowsUserInterface(false)
            .build()
        val progressMax = 1000
        val progress = if (spec.indeterminate || spec.totalBytes <= 0L) 0
        else ((spec.sharedBytes * progressMax) / spec.totalBytes).toInt().coerceIn(0, progressMax)
        val notification = NotificationCompat.Builder(context, NotificationChannels.SHARING)
            .setSmallIcon(R.drawable.ic_notification_sharing_upload)
            .setColor(ContextCompat.getColor(context, R.color.msngr_brand_primary))
            .setContentTitle(spec.title)
            .setContentText(spec.contentText)
            .setProgress(progressMax, progress, spec.indeterminate)
            .setContentIntent(contentIntent)
            .addAction(cancelAction)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(SHARING_NOTIFICATION_GROUP)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return try {
            manager.notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun allocateNotificationId(key: String): Int {
        assignedIds[key]?.let { return it }
        val start = (key.hashCode() and 0x7fffffff) % SHARING_ID_COUNT
        val used = assignedIds.values.toHashSet()
        for (offset in 0 until SHARING_ID_COUNT) {
            val candidate = SHARING_ID_BASE + ((start + offset) % SHARING_ID_COUNT)
            if (candidate !in used) {
                assignedIds[key] = candidate
                return candidate
            }
        }
        return SHARING_ID_BASE + start
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val SHARING_ID_BASE = 800_000
        const val SHARING_ID_COUNT = 90_000
        private const val CANCEL_REQUEST_CODE_OFFSET = 100_000
        private const val THROTTLE_NANOS = 250_000_000L
        private const val SHARING_NOTIFICATION_GROUP = "msngr_sharing"
    }
}
