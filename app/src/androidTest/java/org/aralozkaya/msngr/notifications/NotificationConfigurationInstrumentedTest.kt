package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.shortcutAvatarBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationConfigurationInstrumentedTest {

    @Test
    fun notificationTypesUseIndependentAndroidChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NotificationChannels.createAll(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channelIds = NotificationChannels.userConfigurable +
                NotificationChannels.BACKGROUND_CONNECTION

            assertEquals(8, channelIds.toSet().size)
            channelIds.forEach { channelId ->
                assertNotNull(
                    "Missing notification channel $channelId",
                    manager.getNotificationChannel(channelId),
                )
            }
        }
    }

    @Test
    fun eventNotificationsRouteToTheirChannelsAndOfferContactActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager.cancelAll()
        val renderer = MsngrNotificationManager(context)

        runBlocking {
            renderer.show(
                event(
                    NotificationEventType.Message,
                    "message-event",
                    "\u0002\u000304Message body\u0003\u0002",
                ),
            )
            renderer.show(event(NotificationEventType.Nudge, "nudge-event"))
            renderer.show(event(NotificationEventType.FriendOnline, "online-event"))
            renderer.showOfflineMessagesReceived()
            renderer.syncContactRequests(org.aralozkaya.msngr.data.ContactRequestInbox("test-account",
                listOf(org.aralozkaya.msngr.data.ContactRequest("peer@example.test", "Pending/test"))))
            renderer.syncGroupInvitations(org.aralozkaya.msngr.data.GroupInvitationInbox("test-account",
                listOf(org.aralozkaya.msngr.data.GroupInvitation("00000000-0000-0000-0009-000000000001@live.com", "Test invitation"))))
        }

        var notificationsByChannel = emptyMap<String, android.service.notification.StatusBarNotification>()
        val eventChannels = NotificationChannels.userConfigurable - NotificationChannels.APP_UPDATES
        for (attempt in 0 until 20) {
            notificationsByChannel = systemManager.activeNotifications.associateBy {
                it.notification.channelId
            }
            if (eventChannels.all(notificationsByChannel::containsKey)) {
                break
            }
            SystemClock.sleep(100)
        }
        assertEquals(
            eventChannels.toSet(),
            notificationsByChannel.keys.intersect(eventChannels.toSet()),
        )
        val messageNotification = notificationsByChannel.getValue(
            NotificationChannels.MESSAGES,
        ).notification
        val offlineNotice = notificationsByChannel.getValue(NotificationChannels.OFFLINE_MESSAGES).notification
        assertEquals("You received instant messages while you were offline.",
            offlineNotice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertTrue(offlineNotice.actions.isNullOrEmpty())
        val messageStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(messageNotification)
        assertEquals("Message body", messageStyle?.messages?.single()?.text?.toString())
        assertNotNull(messageStyle?.messages?.single()?.person?.icon)
        assertEquals(3, messageNotification.actions.orEmpty().size)
        assertTrue(
            messageNotification.actions.orEmpty().any { action ->
                action.remoteInputs?.any { input ->
                    input.resultKey == NotificationReplyReceiver.KEY_TEXT_REPLY
                } == true
            },
        )
        val nudgeAction = messageNotification.actions.orEmpty().single { action ->
            action.title.toString() == context.getString(R.string.notification_nudge_label)
        }
        assertTrue(nudgeAction.remoteInputs.isNullOrEmpty())
        val markReadAction = messageNotification.actions.orEmpty().single { action ->
            action.title.toString() == context.getString(R.string.notification_mark_read_label)
        }
        assertTrue(markReadAction.remoteInputs.isNullOrEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(
                NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
                markReadAction.semanticAction,
            )
        }
        val onlineNotification = notificationsByChannel.getValue(
            NotificationChannels.FRIENDS_ONLINE,
        ).notification
        assertNull(onlineNotification.getLargeIcon())
        val onlineStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(onlineNotification)
        assertEquals("is now online", onlineStyle?.messages?.single()?.text?.toString())
        assertEquals("Test contact", onlineStyle?.messages?.single()?.person?.name?.toString())
        assertNotNull(onlineStyle?.messages?.single()?.person?.icon)
        assertEquals(
            MsngrNotificationManager.conversationShortcutId("online-event@example.com"),
            NotificationCompat.getShortcutId(onlineNotification),
        )
        val onlineNudgeAction = onlineNotification.actions.orEmpty().single()
        assertEquals(
            context.getString(R.string.notification_nudge_label),
            onlineNudgeAction.title.toString(),
        )
        assertTrue(onlineNudgeAction.remoteInputs.isNullOrEmpty())
        val receivedNudgeNotification = notificationsByChannel.getValue(
            NotificationChannels.NUDGES,
        ).notification
        assertNotNull(receivedNudgeNotification.getLargeIcon())
        val nudgeBackAction = receivedNudgeNotification.actions.orEmpty().single()
        assertEquals(
            context.getString(R.string.notification_nudge_back_label),
            nudgeBackAction.title.toString(),
        )
        assertTrue(nudgeBackAction.remoteInputs.isNullOrEmpty())

        // Exercise the actual broadcast PendingIntent, including the cold-process path
        // where the application has no active repository to mark as read.
        markReadAction.actionIntent.send()
        val messageId = MsngrNotificationManager.notificationId(
            NotificationEventType.Message,
            "message-event@example.com",
        )
        for (attempt in 0 until 20) {
            if (systemManager.activeNotifications.none { it.id == messageId }) break
            SystemClock.sleep(100)
        }
        assertTrue(systemManager.activeNotifications.none { it.id == messageId })
        assertTrue(systemManager.activeNotifications.any {
            it.notification.channelId == NotificationChannels.FRIENDS_ONLINE
        })
        assertTrue(systemManager.activeNotifications.any {
            it.notification.channelId == NotificationChannels.NUDGES
        })
        systemManager.cancelAll()
    }

    @Test
    fun notificationActionReceiversAreNotExported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (receiver in listOf(
            NotificationNudgeReceiver::class.java,
            NotificationMarkReadReceiver::class.java,
        )) {
            val receiverInfo = context.packageManager.getReceiverInfo(
                ComponentName(context, receiver),
                PackageManager.GET_META_DATA,
            )
            assertFalse(receiverInfo.exported)
        }
    }

    @Test
    fun eventNotificationsUseContactPictureAndKeepFallbackWhenItIsMissing() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager.cancelAll()
        val renderer = MsngrNotificationManager(context)
        val contactPictureId = "contact-picture@example.com"
        val fallbackId = "fallback-picture@example.com"
        val onlineSentAtMillis = System.currentTimeMillis()

        renderer.show(
            NotificationEvent(
                type = NotificationEventType.Message,
                contactId = contactPictureId,
                contactName = "Contact with picture",
                contactAvatarUri = profilePictureDataUri(),
                messageText = "Message body",
            ),
        )
        renderer.show(
            NotificationEvent(
                type = NotificationEventType.Nudge,
                contactId = fallbackId,
                contactName = "Contact without picture",
            ),
        )
        renderer.show(
            NotificationEvent(
                type = NotificationEventType.FriendOnline,
                contactId = contactPictureId,
                contactName = "Contact with picture",
                contactAvatarUri = profilePictureDataUri(),
                sentAtMillis = onlineSentAtMillis,
            ),
        )

        val expectedIds = setOf(
            MsngrNotificationManager.notificationId(
                NotificationEventType.Message,
                contactPictureId,
            ),
            MsngrNotificationManager.notificationId(NotificationEventType.Nudge, fallbackId),
            MsngrNotificationManager.notificationId(NotificationEventType.FriendOnline, contactPictureId),
        )
        var notifications = emptyMap<Int, android.service.notification.StatusBarNotification>()
        for (attempt in 0 until 20) {
            notifications = systemManager.activeNotifications.associateBy { it.id }
            if (notifications.keys.containsAll(expectedIds)) break
            SystemClock.sleep(100)
        }
        val contactPictureNotification = notifications.getValue(
            MsngrNotificationManager.notificationId(
                NotificationEventType.Message,
                contactPictureId,
            ),
        ).notification
        val fallbackNotification = notifications.getValue(
            MsngrNotificationManager.notificationId(NotificationEventType.Nudge, fallbackId),
        ).notification
        val onlineNotification = notifications.getValue(
            MsngrNotificationManager.notificationId(NotificationEventType.FriendOnline, contactPictureId),
        ).notification

        assertNull(contactPictureNotification.getLargeIcon())
        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(contactPictureNotification)
        assertEquals(context.getString(R.string.notification_self_name), messagingStyle?.user?.name)
        val sender = messagingStyle
            ?.messages
            ?.single()
            ?.person
        assertNotNull(sender?.icon)
        val shortcutId = MsngrNotificationManager.conversationShortcutId(contactPictureId)
        assertEquals(shortcutId, NotificationCompat.getShortcutId(contactPictureNotification))
        assertTrue(
            ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == shortcutId },
        )
        assertNotNull(fallbackNotification.getLargeIcon())
        assertNull(onlineNotification.getLargeIcon())
        val onlineStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(onlineNotification)
        assertEquals(context.getString(R.string.notification_self_name), onlineStyle?.user?.name)
        assertEquals(false, onlineStyle?.isGroupConversation)
        val onlineMessage = onlineStyle?.messages?.single()
        assertEquals("is now online", onlineMessage?.text?.toString())
        assertEquals(onlineSentAtMillis, onlineMessage?.timestamp)
        assertEquals("Contact with picture", onlineMessage?.person?.name?.toString())
        assertEquals(contactPictureId, onlineMessage?.person?.key)
        val onlinePicture = onlineMessage?.person?.icon?.loadDrawable(context)?.toBitmap()
        assertNotNull(onlinePicture)
        requireNotNull(onlinePicture)
        assertEquals(Color.MAGENTA, onlinePicture.getPixel(onlinePicture.width / 2, onlinePicture.height / 2))
        assertEquals(shortcutId, NotificationCompat.getShortcutId(onlineNotification))
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId))
        systemManager.cancelAll()
    }

    @Test
    fun allContactNotificationsUsePhotoOrContactListInitial() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val systemManager = context.getSystemService(NotificationManager::class.java)
        val renderer = MsngrNotificationManager(context)
        val contactId = "avatar-fallback@example.com"
        val shortcutId = MsngrNotificationManager.conversationShortcutId(contactId)
        systemManager.cancelAll()
        try {
            for (uri in listOf(null, "file:///missing-contact-picture.png", profilePictureDataUri())) {
                for (type in NotificationEventType.entries) {
                    renderer.show(
                        NotificationEvent(
                            type = type,
                            contactId = contactId,
                            contactName = "[b]  alice example  [/b]",
                            contactAvatarUri = uri,
                            messageText = "Avatar test",
                        ),
                    )
                    val notificationId = MsngrNotificationManager.notificationId(type, contactId)
                    var notification: android.app.Notification? = null
                    for (attempt in 0 until 20) {
                        notification = systemManager.activeNotifications
                            .singleOrNull { it.id == notificationId }?.notification
                        if (notification != null) break
                        SystemClock.sleep(100)
                    }
                    val posted = requireNotNull(notification)
                    val actual = if (type == NotificationEventType.Nudge) {
                        posted.getLargeIcon()?.loadDrawable(context)?.toBitmap(128, 128)
                    } else {
                        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
                            ?.messages?.single()?.person?.icon?.loadDrawable(context)?.toBitmap(128, 128)
                    }
                    assertNotNull("Missing avatar for $type ($uri)", actual)
                    requireNotNull(actual)
                    if (uri?.startsWith("data:") == true) {
                        assertEquals(Color.MAGENTA, actual.getPixel(64, 64))
                    } else {
                        val initial = context.shortcutAvatarBitmap("Alice", null)
                        val expected = if (type == NotificationEventType.Nudge) initial else {
                            requireNotNull(IconCompat.createWithAdaptiveBitmap(initial)
                                .loadDrawable(context)).toBitmap(128, 128)
                        }
                        assertTrue("Wrong initial avatar for $type ($uri)", expected.sameAs(actual))
                        if (type != NotificationEventType.Nudge) {
                            val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context)
                                .single { it.id == shortcutId }
                            assertEquals("  alice example  ", shortcut.shortLabel.toString())
                            assertEquals(shortcutId, NotificationCompat.getShortcutId(posted))
                        }
                    }
                    systemManager.cancel(notificationId)
                    for (attempt in 0 until 20) {
                        if (systemManager.activeNotifications.none { it.id == notificationId }) break
                        SystemClock.sleep(100)
                    }
                    assertTrue(systemManager.activeNotifications.none { it.id == notificationId })
                }
            }
        } finally {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId))
            systemManager.cancelAll()
        }
    }

    @Test
    fun backgroundConnectionIsDeclaredAsRemoteMessagingService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, MessengerConnectionService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertTrue(
            serviceInfo.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING != 0,
        )
    }

    private fun event(
        type: NotificationEventType,
        eventId: String,
        messageText: String? = null,
    ) = NotificationEvent(
        type = type,
        contactId = "$eventId@example.com",
        contactName = "Test contact",
        messageText = messageText,
    )

    private fun profilePictureDataUri(): String {
        val bytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        return "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }
}
