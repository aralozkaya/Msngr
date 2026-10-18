package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.Notification
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.aralozkaya.msngr.*
import org.aralozkaya.msngr.data.*
import org.junit.Assert.*
import org.junit.Test

class GroupInvitationNotificationInstrumentedTest {
    @Test fun offlineGroupAndContactInvitationsBothNotifyAfterSignInAndClearOnLogout() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val offlineInvitation = GroupInvitation("00000000-0000-0000-0009-123456789abc@live.com", "Offline invitation")
        val invitations = MutableStateFlow(emptyList<GroupInvitation>())
        val requests = MutableStateFlow(emptyList<ContactRequest>())
        val mock = MockMsnp18Client(signInDelayMillis = 0)
        val client = object : MsnpClient by mock {
            override val groupInvitations = invitations
            override val contactRequests = requests
            override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> =
                mock.signIn(request).onSuccess {
                    // The live client publishes the address book before repository sign-in completes.
                    invitations.value = listOf(offlineInvitation)
                    requests.value = listOf(ContactRequest("offline-contact@example.com", "Pending/offline"))
                }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = MessengerRepository(client, scope, useMockServer = true)
        val renderer = MsngrNotificationManager(context)
        val bridge = RealtimeNotificationBridge(AppVisibilityTracker(), renderer, scope)
        val notificationId = MsngrNotificationManager.GROUP_INVITATIONS_NOTIFICATION_ID
        try {
            bridge.attach(repository)
            repository.signIn(SignInRequest("peer@example.test", "mock-only",
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            val contactId = MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID
            withTimeout(3000) { while (!manager.activeNotifications.map { it.id }.containsAll(listOf(notificationId, contactId))) delay(20) }
            val notification = manager.activeNotifications.first { it.id == notificationId }.notification
            assertEquals(NotificationChannels.GROUP_INVITATIONS, notification.channelId)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(NotificationChannels.CONTACT_REQUESTS, manager.activeNotifications.first { it.id == contactId }.notification.channelId)
            invitations.value = invitations.value + GroupInvitation("00000000-0000-0000-0009-123456789abd@live.com", "Online invitation")
            withTimeout(3000) { while (manager.activeNotifications.firstOrNull { it.id == notificationId }?.notification?.number != 2) delay(20) }
            assertEquals(1, manager.activeNotifications.count { it.id == notificationId })
            assertTrue(manager.activeNotifications.any { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID })
            repository.logout()
            withTimeout(3000) { while (manager.activeNotifications.any { it.id == notificationId || it.id == contactId }) delay(20) }
        } finally { repository.logout(); scope.cancel(); renderer.dismiss(notificationId); renderer.dismiss(MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID) }
    }
}
