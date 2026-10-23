package org.aralozkaya.msngr.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.LoginActivity
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.restoreSignedInProfile
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.SignInRequest

class MessengerConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionJob: Job? = null
    private var repository: MessengerRepository? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground("Preparing the Messenger connection…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BackgroundDeliveryPrefs.keepsConnectionAlive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // MainActivity may start us again while a package-replacement restore is still running.
        if (connectionJob?.isActive == true) return START_STICKY
        connectionJob = scope.launch {
            val connectedRepository = findOrRestoreRepository()
            if (connectedRepository == null) {
                stopSelf()
                return@launch
            }
            repository = connectedRepository
            connectedRepository.setPersistentBackgroundConnection(true)
            combine(
                connectedRepository.profile,
                connectedRepository.connectionIssue,
            ) { profile, issue ->
                when {
                    issue != null -> "Reconnecting automatically…"
                    profile.email.isNotBlank() -> "Connected as ${profile.email}"
                    else -> "Connected to Messenger"
                }
            }.collect(::updateForegroundNotification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repository?.setPersistentBackgroundConnection(false)
        connectionJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun findOrRestoreRepository(): MessengerRepository? {
        val app = application as MsngrApplication
        val existing = runCatching { app.container.messengerRepository }.getOrNull()
        if (existing?.hasSignedInSession == true) return existing

        val snapshot = SessionPrefs.readLoginSnapshot(this)
        if (!canRestoreBackgroundConnection(enabled = true, snapshot = snapshot)) {
            return null
        }
        val restored = app.container.repositoryFor(snapshot.useMockServer)
        val result = restored.signIn(
            SignInRequest(
                email = snapshot.email,
                password = snapshot.password,
                serverConfig = snapshot.resolvedServers,
                initialPresence = snapshot.loginPresence,
            ),
        )
        val serverProfile = result.getOrNull() ?: return null
        SessionPrefs.activateRememberedSession(this)
        restoreSignedInProfile(restored, serverProfile, serverProfile.presence)
        return restored
    }

    private fun promoteToForeground(status: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            connectionNotification(status),
            type,
        )
    }

    private fun updateForegroundNotification(status: String) {
        promoteToForeground(status)
    }

    private fun connectionNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            LoginActivity.createIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.BACKGROUND_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_msngr)
            .setColor(ContextCompat.getColor(this, R.color.msngr_brand_primary))
            .setContentTitle(getString(R.string.background_connection_title))
            .setContentText(status)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 400_001
    }
}
