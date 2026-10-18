package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.aralozkaya.msngr.*
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.*
import org.junit.Test

class ContactRequestNotificationInstrumentedTest {
    @Test fun pendingAndDeclinedPeopleAreNotAnnouncedAsFriendsOnline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val peer = "peer@example.test"
        val contact = Contact(peer, "peer", peer, Presence.Online, isForwardListContact = false)
        val contacts = MutableStateFlow(listOf(contact))
        val requests = MutableStateFlow(listOf(ContactRequest(peer, "Pending/mock-listener")))
        val events = MutableSharedFlow<MessengerEvent>()
        val mock = MockMsnp18Client(signInDelayMillis = 0)
        val client = object : MsnpClient by mock {
            override val contacts = contacts
            override val contactRequests = requests
            override val events = events
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = MessengerRepository(client, scope, useMockServer = true)
        val bridge = RealtimeNotificationBridge(AppVisibilityTracker(), MsngrNotificationManager(context), scope)
        val friendId = MsngrNotificationManager.notificationId(NotificationEventType.FriendOnline, peer)
        try {
            bridge.attach(repository)
            repository.signIn(SignInRequest("owner@example.test", "mock-only",
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            withTimeout(3000) { events.subscriptionCount.first { it >= 2 } }
            withTimeout(3000) {
                while (manager.activeNotifications.none { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID }) delay(50)
            }
            events.emit(MessengerEvent.PresenceChanged(peer, Presence.Online))
            delay(200)
            assertTrue(manager.activeNotifications.any { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID })
            assertTrue(manager.activeNotifications.none { it.id == friendId })
            requests.value = emptyList() // Declining leaves the person outside the forward list.
            events.emit(MessengerEvent.PresenceChanged(peer, Presence.Online))
            delay(200)
            assertTrue(manager.activeNotifications.none { it.id == friendId })
            contacts.value = listOf(contact.copy(isForwardListContact = true))
            events.emit(MessengerEvent.PresenceChanged(peer, Presence.Online))
            withTimeout(3000) { while (manager.activeNotifications.none { it.id == friendId }) delay(50) }
        } finally { repository.logout(); scope.cancel(); manager.cancelAll() }
    }
}
