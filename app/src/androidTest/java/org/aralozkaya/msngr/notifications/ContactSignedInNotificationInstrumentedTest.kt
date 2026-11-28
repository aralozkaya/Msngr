package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactSignedInNotificationInstrumentedTest {
    @Test
    fun foregroundEntryDismissesEveryContactSignInAndPreservesOtherAlerts() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val signIns = listOf("first", "second", "third").map {
            NotificationEvent(NotificationEventType.ContactSignedIn, "$it@example.test", it)
        }
        val otherEvents = listOf(
            NotificationEvent(NotificationEventType.Message, "first@example.test", "first", messageText = "Hello"),
            NotificationEvent(NotificationEventType.Nudge, "second@example.test", "second"),
        )
        val retainedIds = otherEvents.map { MsngrNotificationManager.notificationId(it.type, it.contactId) }.toSet() +
            MsngrNotificationManager.OFFLINE_MESSAGES_NOTIFICATION_ID
        val signInIds = signIns.map { MsngrNotificationManager.notificationId(it.type, it.contactId) }.toSet()
        manager.cancelAll()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                repeat(2) {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    // A new renderer posts alerts; foreground cleanup must discover them through Android.
                    val renderer = MsngrNotificationManager(context)
                    (signIns + otherEvents).forEach { renderer.show(it) }
                    renderer.showOfflineMessagesReceived()
                    withTimeout(3000) {
                        while (!manager.activeNotifications.map { it.id }.containsAll(signInIds + retainedIds)) delay(50)
                    }

                    scenario.moveToState(Lifecycle.State.RESUMED)

                    withTimeout(3000) {
                        while (manager.activeNotifications.any {
                            androidx.core.app.NotificationCompat.getChannelId(it.notification) == NotificationChannels.CONTACT_SIGNED_IN
                        }) delay(50)
                    }
                    assertTrue(manager.activeNotifications.map { it.id }.containsAll(retainedIds))
                }
            }
        } finally {
            manager.cancelAll()
        }
    }

    @Test
    fun delayedForegroundCleanupPreservesAReplacementWithTheSameId() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val poster = MsngrNotificationManager(context) { false }
        val event = NotificationEvent(NotificationEventType.ContactSignedIn, "replacement@example.test", "First sign-in")
        val id = MsngrNotificationManager.notificationId(event.type, event.contactId)
        val marker = NotificationEvent(NotificationEventType.Nudge, "replacement-marker", "Marker")
        val markerId = MsngrNotificationManager.notificationId(marker.type, marker.contactId)
        fun active() = manager.activeNotifications.singleOrNull { it.id == id }
        try {
            poster.show(event)
            val first = withTimeout(3000) {
                while (active() == null) delay(25)
                requireNotNull(active())
            }
            val recheckFinished = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync {
                MsngrNotificationManager(context).dismissContactSignedInNotifications()
                Handler(Looper.getMainLooper()).postDelayed(
                    { recheckFinished.complete(Unit) }, MsngrNotificationManager.SIGN_IN_CANCEL_RECHECK_MILLIS,
                )
            }
            poster.show(event.copy(contactName = "Second sign-in"))
            val replacement = withTimeout(3000) {
                while (active()?.postTime == null || active()?.postTime == first.postTime) delay(25)
                requireNotNull(active())
            }
            withTimeout(3000) { recheckFinished.await() }
            // Wait for Android to process a subsequent notification before observing the replacement.
            poster.show(marker)
            withTimeout(3000) { while (manager.activeNotifications.none { it.id == markerId }) delay(25) }
            assertEquals(replacement.postTime, active()?.postTime)
        } finally {
            manager.cancel(id)
            manager.cancel(markerId)
        }
    }

    @Test
    fun onlyOfflineToNonOfflineTransitionsNotify() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val transitions = Presence.entries.flatMap { previous ->
            Presence.entries.map { next ->
                MessengerEvent.PresenceChanged("$previous-$next@example.test", next, previous)
            }
        }
        val contacts = MutableStateFlow(transitions.map {
            Contact(it.contactId, "\u0002Alex\u0002", it.contactId, it.presence)
        })
        val events = MutableSharedFlow<MessengerEvent>()
        val client = object : MsnpClient by MockMsnp18Client() {
            override val contacts = contacts
            override val events = events
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = MessengerRepository(client, scope)
        val bridge = RealtimeNotificationBridge(
            AppVisibilityTracker(), MsngrNotificationManager(context), scope,
        )
        val markerId = MsngrNotificationManager.notificationId(NotificationEventType.Nudge, "marker")
        manager.cancelAll()
        try {
            bridge.attach(repository)
            withTimeout(3000) { events.subscriptionCount.first { it >= 2 } }
            transitions.forEach { events.emit(it) }
            // This notification is processed after every presence event, including ignored ones.
            events.emit(MessengerEvent.IncomingNudge("marker"))
            withTimeout(3000) {
                while (manager.activeNotifications.none { it.id == markerId }) delay(50)
            }

            val signedInNotifications = manager.activeNotifications.filter {
                androidx.core.app.NotificationCompat.getChannelId(it.notification) == NotificationChannels.CONTACT_SIGNED_IN
            }
            val expectedIds = Presence.entries.filter { it != Presence.Offline }.map {
                MsngrNotificationManager.notificationId(
                    NotificationEventType.ContactSignedIn, "Offline-$it@example.test",
                )
            }.toSet()
            assertEquals(expectedIds, signedInNotifications.map { it.id }.toSet())
            signedInNotifications.forEach {
                val message = NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(it.notification)?.messages?.single()
                assertEquals("Alex has just signed in.", message?.text?.toString())
                assertEquals("Alex", message?.person?.name?.toString())
            }
        } finally {
            scope.cancel()
            client.close()
            manager.cancelAll()
        }
    }
}
