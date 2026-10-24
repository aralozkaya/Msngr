package org.aralozkaya.msngr.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class InAppNotification(
    val type: InAppNotificationType,
    val title: String,
    val text: String,
    val contactId: String? = null,
    val avatarUri: String? = null,
    val id: Long = 0,
)

internal class InAppNotificationController(
    private val context: Context,
    private val visibility: AppVisibilityTracker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val vibrate: () -> Unit = { vibrateForInAppNotification(context) },
) {
    private val mutableBanner = MutableStateFlow<InAppNotification?>(null)
    val banner = mutableBanner.asStateFlow()
    private var expiry: Job? = null
    private var sequence = 0L
    private var lastVibrationAt = -1_000L

    init {
        scope.launch {
            InAppNotificationPrefs.state(context).collect { settings ->
                val current = mutableBanner.value ?: return@collect
                if (!settings.showsBanner(current.type, current.contactId)) dismiss(current.id)
            }
        }
    }

    fun show(notification: InAppNotification) {
        scope.launch {
            if (!visibility.isForeground || isOpenConversation(notification.contactId)) return@launch
            val settings = InAppNotificationPrefs.read(context)
            if (!settings.allows(notification.type, notification.contactId)) return@launch
            val now = SystemClock.elapsedRealtime()
            if (settings.vibrates(notification.type, notification.contactId) && now - lastVibrationAt >= 750) {
                vibrate()
                lastVibrationAt = now
            }
            if (!settings.showsBanner(notification.type, notification.contactId)) return@launch
            expiry?.cancel()
            val current = notification.copy(id = ++sequence)
            mutableBanner.value = current
            expiry = scope.launch { delay(5_000); dismiss(current.id) }
        }
    }

    fun dismiss(id: Long? = null) {
        scope.launch {
            if (id == null || mutableBanner.value?.id == id) {
                mutableBanner.value = null
                expiry?.cancel()
                expiry = null
            }
        }
    }

    fun dismissConversation(contactId: String) {
        scope.launch {
            if (mutableBanner.value?.contactId?.notificationKey() == contactId.notificationKey()) dismiss()
        }
    }

    private fun isOpenConversation(contactId: String?) = contactId != null &&
        visibility.activeConversationId?.notificationKey() == contactId.notificationKey()
}

/** Notification usage lets Android apply its vibration intensity and DND policy without a channel. */
@Suppress("DEPRECATION")
internal fun vibrateForInAppNotification(context: Context) {
    val audio = context.getSystemService(AudioManager::class.java)
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT ||
        notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    if (Build.VERSION.SDK_INT >= 33) {
        vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build())
    } else {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE), attributes)
        } else if (audio.shouldVibrate(AudioManager.VIBRATE_TYPE_NOTIFICATION)) {
            vibrator.vibrate(120, attributes)
        }
    }
}
