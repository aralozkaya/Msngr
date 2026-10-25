package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMessageNotificationInstrumentedTest {
    @Test fun backgroundReceiptShowsOneIndependentNoticeAndReadingChatKeepsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val renderer = MsngrNotificationManager(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = MutableSharedFlow<MessengerEvent>()
        val client = object : MsnpClient by MockMsnp18Client() {
            override val events = events
        }
        val repository = MessengerRepository(client, scope = scope)
        val visibility = AppVisibilityTracker()
        val bridge = RealtimeNotificationBridge(visibility, renderer, scope)
        manager.cancelAll()
        try {
            bridge.attach(repository)
            withTimeout(2000) { events.subscriptionCount.first { it >= 2 } }
            events.emit(MessengerEvent.OfflineMessagesReceived)
            withTimeout(2000) { while (manager.activeNotifications.none {
                it.id == MsngrNotificationManager.OFFLINE_MESSAGES_NOTIFICATION_ID
            }) delay(50) }
            // Repeated arrivals update the same general notice, and cannot alert it repeatedly.
            events.emit(MessengerEvent.OfflineMessagesReceived)
            renderer.dismissConversation("offline-peer@example.com")
            val notices = manager.activeNotifications.filter {
                it.notification.channelId == NotificationChannels.OFFLINE_MESSAGES
            }
            assertEquals(1, notices.size)
            val notification = notices.single().notification
            assertEquals("You received instant messages while you were offline.",
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertNotNull(notification.contentIntent)
            assertTrue(notification.actions.isNullOrEmpty())
        } finally {
            scope.cancel()
            client.close()
            manager.cancelAll()
        }
    }
}
