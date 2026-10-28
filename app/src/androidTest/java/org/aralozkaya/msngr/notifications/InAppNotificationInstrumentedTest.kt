package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.ChatActivity
import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.ServerPresets
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactRequest
import org.aralozkaya.msngr.data.ContactRequestInbox
import org.aralozkaya.msngr.data.GroupInvitation
import org.aralozkaya.msngr.data.GroupInvitationInbox
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InAppNotificationInstrumentedTest {
    private fun hasEventNotifications(manager: NotificationManager): Boolean = manager.activeNotifications.any {
        it.notification.channelId in NotificationChannels.userConfigurable &&
            it.notification.channelId != NotificationChannels.APP_UPDATES
    }

    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("in_app_notification_test_$name", mode)
        }
    }

    @Test fun foregroundMessagesNotifyButContactSignInsAndOfflineReceiptsStaySilent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = isolatedContext()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val visibility = AppVisibilityTracker()
        val foreground = withContext(Dispatchers.Main) { Activity().also(visibility::onActivityStarted) }
        val vibrations = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = InAppNotificationController(context, visibility, scope) { vibrations.incrementAndGet() }
        val events = MutableSharedFlow<MessengerEvent>()
        val peer = "in-app-peer@example.test"
        val group = "in-app-group@example.test"
        val messages = MutableStateFlow(mapOf(peer to listOf(ChatMessage(1, peer, MessageAuthor.Contact, "Hello in app", 1)),
            group to listOf(ChatMessage(2, group, MessageAuthor.Contact, "Group hello", 2, senderName = "Member"))))
        val mock = MockMsnp18Client(signInDelayMillis = 0)
        val requests = MutableStateFlow(emptyList<ContactRequest>())
        val invitations = MutableStateFlow(emptyList<GroupInvitation>())
        val client = object : MsnpClient by mock {
            override val events = events
            override val messages = messages
            override val contactRequests = requests
            override val groupInvitations = invitations
            override val contacts = MutableStateFlow(listOf(Contact(peer, "Peer", peer, Presence.Online),
                Contact(group, "Group", group, Presence.Online, isGroup = true)))
        }
        val repository = MessengerRepository(client, scope)
        val renderer = MsngrNotificationManager(context) { visibility.isForeground }
        val bridge = RealtimeNotificationBridge(visibility, renderer, scope, controller)
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        manager.cancelAll()
        try {
            bridge.attach(repository)
            repository.signIn(SignInRequest("owner@example.test", "mock-only",
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
                initialPresence = Presence.Offline)).getOrThrow()
            withTimeout(3000) { events.subscriptionCount.first { it >= 2 } }
            Presence.entries.filter { it != Presence.Offline }.forEach { presence ->
                events.emit(MessengerEvent.PresenceChanged(peer, presence, Presence.Offline))
            }
            events.emit(MessengerEvent.OfflineMessagesReceived)
            delay(150)
            assertNull(controller.banner.value)
            assertEquals(0, vibrations.get())
            assertFalse(hasEventNotifications(manager))
            events.emit(MessengerEvent.IncomingMessage(peer, 1, isOffline = true))
            withTimeout(3000) { controller.banner.first { it?.text == "Hello in app" } }
            assertEquals(1, vibrations.get())
            assertFalse(hasEventNotifications(manager))
            events.emit(MessengerEvent.IncomingMessage(group, 2))
            withTimeout(3000) { controller.banner.first { it?.type == InAppNotificationType.GroupMessage } }
            assertEquals("Member: Group hello", controller.banner.value?.text)
            val messageBanner = controller.banner.value
            val messageVibrations = vibrations.get()
            events.emit(MessengerEvent.OfflineMessagesReceived)
            delay(150)
            assertEquals(messageBanner, controller.banner.value)
            assertEquals(messageVibrations, vibrations.get())
            assertFalse(hasEventNotifications(manager))
            renderer.syncContactRequests(ContactRequestInbox("owner", listOf(ContactRequest(peer, "request"))))
            renderer.syncGroupInvitations(GroupInvitationInbox("owner", listOf(GroupInvitation(group, "Group"))))
            renderer.showOfflineMessagesReceived()
            assertFalse(hasEventNotifications(manager))
            requests.value = listOf(ContactRequest(peer, "request"))
            withTimeout(3000) { controller.banner.first { it?.type == InAppNotificationType.ContactRequest } }
            invitations.value = listOf(GroupInvitation(group, "Invited group"))
            withTimeout(3000) { controller.banner.first { it?.type == InAppNotificationType.GroupInvitation } }
            assertFalse(hasEventNotifications(manager))
            // Nudges in the open conversation have no banner; another conversation still gets one.
            withContext(Dispatchers.Main) {
                val chat = ChatActivity().apply { intent = ChatActivity.createIntent(context, peer) }
                visibility.onActivityResumed(chat)
                controller.dismiss()
            }
            events.emit(MessengerEvent.IncomingNudge(peer))
            delay(150)
            assertNull(controller.banner.value)
            events.emit(MessengerEvent.IncomingNudge(group))
            withTimeout(3000) { controller.banner.first { it?.contactId == group } }
            withContext(Dispatchers.Main) {
                visibility.onActivityResumed(foreground)
                visibility.onActivityStopped(foreground)
                controller.dismiss()
            }
            events.emit(MessengerEvent.IncomingNudge(peer))
            withTimeout(3000) { while (manager.activeNotifications.none {
                it.id == MsngrNotificationManager.notificationId(NotificationEventType.Nudge, peer)
            }) delay(30) }
            withTimeout(3000) { while (manager.activeNotifications.none {
                it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID
            } || manager.activeNotifications.none { it.id == MsngrNotificationManager.GROUP_INVITATIONS_NOTIFICATION_ID }) delay(30) }
            assertNull(controller.banner.value)
        } finally {
            scope.cancel()
            client.close()
            manager.cancelAll()
            InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
            InAppNotificationPrefs.reload(instrumentation.targetContext)
        }
    }

    @Test fun openConversationNudgesVibrateWithoutBannersAndRespectSettings() = runBlocking {
        val context = isolatedContext()
        val visibility = AppVisibilityTracker()
        val peer = "open-peer"
        withContext(Dispatchers.Main) {
            visibility.onActivityStarted(Activity())
            visibility.onActivityResumed(ChatActivity().apply { intent = ChatActivity.createIntent(context, peer) })
        }
        val defaults = InAppNotificationOptions()
        val custom = ConversationNotificationOverride(peer, "Peer", false, defaults)
        val cases = listOf(
            InAppNotificationSettings() to 1,
            InAppNotificationSettings(defaults.copy(banners = false)) to 1,
            InAppNotificationSettings(defaults.copy(enabled = false)) to 0,
            InAppNotificationSettings(defaults.copy(vibrate = false)) to 0,
            InAppNotificationSettings(defaults.copy(rules = mapOf(
                InAppNotificationType.Nudge to InAppNotificationRule(vibrate = false)))) to 0,
            InAppNotificationSettings(defaults.copy(rules = mapOf(
                InAppNotificationType.Nudge to InAppNotificationRule(enabled = false)))) to 0,
            InAppNotificationSettings(defaults.copy(enabled = false, vibrate = false), mapOf(peer to custom)) to 1,
            InAppNotificationSettings(conversations = mapOf(peer to custom.copy(options = defaults.copy(vibrate = false)))) to 0,
        )
        try {
            cases.forEach { (settings, expectedVibrations) ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val vibrations = AtomicInteger()
                InAppNotificationPrefs.update(context) { settings }
                val controller = InAppNotificationController(context, visibility, scope) { vibrations.incrementAndGet() }
                try {
                    withContext(Dispatchers.Main) {
                        controller.show(InAppNotification(InAppNotificationType.Message, "Peer", "Hello", peer))
                        assertEquals(0, vibrations.get())
                        controller.show(InAppNotification(InAppNotificationType.Nudge, "Peer", "Sent you a nudge.", peer))
                        assertEquals(expectedVibrations, vibrations.get())
                        assertNull(controller.banner.value)
                        // Consecutive nudges still respect the vibration burst limit.
                        controller.show(InAppNotification(InAppNotificationType.Nudge, "Peer", "Sent you another nudge.", peer))
                        assertEquals(expectedVibrations, vibrations.get())
                    }
                } finally { scope.cancel() }
            }
        } finally {
            InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
            InAppNotificationPrefs.reload(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Test fun backgroundConversationCanBeMutedOrMadeSilentAndReset() = runBlocking {
        val context = isolatedContext()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val renderer = MsngrNotificationManager(context)
        val peer = "custom-background@example.test"
        val event = NotificationEvent(NotificationEventType.Nudge, peer, "Custom contact")
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        manager.cancelAll()
        try {
            InAppNotificationPrefs.customizeBackground(context, peer, "Custom contact", false) { it.copy(backgroundEnabled = false) }
            renderer.show(event)
            assertFalse(hasEventNotifications(manager))
            InAppNotificationPrefs.customizeBackground(context, peer, "Custom contact", false) {
                it.copy(backgroundEnabled = true, backgroundSilent = true)
            }
            // Editing the in-app side preserves background choices.
            InAppNotificationPrefs.customize(context, peer, "Custom contact", false) { it.copy(vibrate = false) }
            InAppNotificationPrefs.reload(context)
            assertTrue(InAppNotificationPrefs.read(context).conversations[peer]!!.backgroundSilent)
            renderer.show(event)
            val id = MsngrNotificationManager.notificationId(event.type, peer)
            withTimeout(3000) { while (manager.activeNotifications.none { it.id == id }) delay(30) }
            assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY,
                manager.activeNotifications.first { it.id == id }.notification.groupAlertBehavior)
            InAppNotificationPrefs.reset(context, peer)
            assertTrue(InAppNotificationPrefs.read(context).conversations.isEmpty())
        } finally {
            manager.cancelAll()
            InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
            InAppNotificationPrefs.reload(instrumentation.targetContext)
        }
    }

    @Test fun preferencesPersistAndCustomAlertsOverrideGeneralSettings() = runBlocking {
        val context = isolatedContext()
        val visibility = AppVisibilityTracker()
        withContext(Dispatchers.Main) { visibility.onActivityStarted(Activity()) }
        val vibrations = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        val controller = InAppNotificationController(context, visibility, scope) { vibrations.incrementAndGet() }
        try {
            InAppNotificationPrefs.customize(context, "PEER", "Peer", false) { it.copy(vibrate = false) }
            InAppNotificationPrefs.reload(context)
            assertFalse(InAppNotificationPrefs.read(context).options("peer").vibrate)
            controller.show(InAppNotification(InAppNotificationType.Message, "Peer", "Custom", "peer"))
            withTimeout(3000) { controller.banner.first { it != null } }
            assertEquals(0, vibrations.get())
            InAppNotificationPrefs.update(context) {
                it.copy(defaults = it.defaults.copy(enabled = false, vibrate = false, banners = false))
            }
            controller.show(InAppNotification(InAppNotificationType.Nudge, "Peer", "Custom priority", "peer"))
            withTimeout(3000) { controller.banner.first { it?.text == "Custom priority" } }
            assertEquals(0, vibrations.get())
            controller.show(InAppNotification(InAppNotificationType.Nudge, "Other", "General muted", "other"))
            delay(150)
            assertEquals("Custom priority", controller.banner.value?.text)
            // The custom profile's own switch still silences that conversation.
            InAppNotificationPrefs.customize(context, "peer", "Peer", false) { it.copy(enabled = false) }
            withTimeout(3000) { controller.banner.first { it == null } }
            controller.show(InAppNotification(InAppNotificationType.Nudge, "Peer", "Muted", "peer"))
            delay(150)
            assertNull(controller.banner.value)
            assertEquals(0, vibrations.get())
            InAppNotificationPrefs.setCustomizationEnabled(context, "peer", "Peer", false, false)
            InAppNotificationPrefs.reload(context)
            assertTrue(InAppNotificationPrefs.read(context).conversations.isEmpty())
        } finally {
            scope.cancel()
            InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
            InAppNotificationPrefs.reload(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }
}
