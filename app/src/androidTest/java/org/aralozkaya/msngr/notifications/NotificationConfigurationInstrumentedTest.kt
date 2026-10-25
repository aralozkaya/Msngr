package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
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
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.shortcutAvatarBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

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
            val signedInChannel = manager.getNotificationChannel(NotificationChannels.CONTACT_SIGNED_IN)
            assertEquals("Contact signed in", signedInChannel.name.toString())
            assertEquals("Contacts who have just signed in", signedInChannel.description)
        }
    }

    @Test
    fun replacedFriendsOnlineChannelIsRemovedFromExistingInstallations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(
            "friends_online", "Friends online", NotificationManager.IMPORTANCE_DEFAULT,
        ))

        NotificationChannels.createAll(context)

        assertNull(manager.getNotificationChannel("friends_online"))
        assertNotNull(manager.getNotificationChannel(NotificationChannels.CONTACT_SIGNED_IN))
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
            renderer.show(event(NotificationEventType.ContactSignedIn, "signed-in-event"))
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
        val compatActions = (0 until NotificationCompat.getActionCount(messageNotification))
            .mapNotNull { NotificationCompat.getAction(messageNotification, it) }
        val replyAction = compatActions.single { action ->
            action.remoteInputs?.any { input ->
                input.resultKey == NotificationReplyReceiver.KEY_TEXT_REPLY
            } == true
        }
        assertFalse(replyAction.showsUserInterface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(
                NotificationCompat.Action.SEMANTIC_ACTION_REPLY,
                replyAction.semanticAction,
            )
        }
        val wearableReply = NotificationCompat.Action.WearableExtender(replyAction)
        assertTrue(wearableReply.hintDisplayActionInline)
        assertFalse(wearableReply.hintLaunchesActivity)
        val nudgeAction = compatActions.single { action ->
            action.title.toString() == context.getString(R.string.notification_nudge_label)
        }
        assertTrue(nudgeAction.remoteInputs.isNullOrEmpty())
        assertFalse(nudgeAction.showsUserInterface)
        val markReadAction = compatActions.single { action ->
            action.title.toString() == context.getString(R.string.notification_mark_read_label)
        }
        assertTrue(markReadAction.remoteInputs.isNullOrEmpty())
        assertFalse(markReadAction.showsUserInterface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(
                NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
                markReadAction.semanticAction,
            )
        }
        val signedInNotification = notificationsByChannel.getValue(
            NotificationChannels.CONTACT_SIGNED_IN,
        ).notification
        assertNull(signedInNotification.getLargeIcon())
        val signedInStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(signedInNotification)
        assertEquals("Test contact has just signed in.", signedInStyle?.messages?.single()?.text?.toString())
        assertEquals("Test contact", signedInStyle?.messages?.single()?.person?.name?.toString())
        assertNotNull(signedInStyle?.messages?.single()?.person?.icon)
        assertEquals(
            MsngrNotificationManager.conversationShortcutId("signed-in-event@example.com"),
            NotificationCompat.getShortcutId(signedInNotification),
        )
        val signedInNudgeAction = signedInNotification.actions.orEmpty().single()
        assertEquals(
            context.getString(R.string.notification_nudge_label),
            signedInNudgeAction.title.toString(),
        )
        assertTrue(signedInNudgeAction.remoteInputs.isNullOrEmpty())
        val receivedNudgeNotification = notificationsByChannel.getValue(
            NotificationChannels.NUDGES,
        ).notification
        assertNull(receivedNudgeNotification.getLargeIcon())
        val receivedNudgeStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(receivedNudgeNotification)
        assertEquals("You received a nudge!", receivedNudgeStyle?.messages?.single()?.text?.toString())
        assertNotNull(receivedNudgeStyle?.messages?.single()?.person?.icon)
        assertEquals(
            MsngrNotificationManager.conversationShortcutId("nudge-event@example.com"),
            NotificationCompat.getShortcutId(receivedNudgeNotification),
        )
        val nudgeBackAction = receivedNudgeNotification.actions.orEmpty().single()
        assertEquals(
            context.getString(R.string.notification_nudge_back_label),
            nudgeBackAction.title.toString(),
        )
        assertTrue(nudgeBackAction.remoteInputs.isNullOrEmpty())

        // Exercise the actual broadcast PendingIntent, including the cold-process path
        // where the application has no active repository to mark as read.
        requireNotNull(markReadAction.actionIntent).send()
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
            it.notification.channelId == NotificationChannels.CONTACT_SIGNED_IN
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
        val signedInSentAtMillis = System.currentTimeMillis()

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
                type = NotificationEventType.ContactSignedIn,
                contactId = contactPictureId,
                contactName = "Contact with picture",
                contactAvatarUri = profilePictureDataUri(),
                sentAtMillis = signedInSentAtMillis,
            ),
        )

        val expectedIds = setOf(
            MsngrNotificationManager.notificationId(
                NotificationEventType.Message,
                contactPictureId,
            ),
            MsngrNotificationManager.notificationId(NotificationEventType.Nudge, fallbackId),
            MsngrNotificationManager.notificationId(NotificationEventType.ContactSignedIn, contactPictureId),
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
        val signedInNotification = notifications.getValue(
            MsngrNotificationManager.notificationId(NotificationEventType.ContactSignedIn, contactPictureId),
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
        assertEquals(shortcutId, NotificationCompat.getLocusId(contactPictureNotification)?.id)
        val publishedShortcut = ShortcutManagerCompat.getDynamicShortcuts(context)
            .single { it.id == shortcutId }
        assertEquals(shortcutId, publishedShortcut.locusId?.id)
        assertNull(fallbackNotification.getLargeIcon())
        assertNotNull(NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(fallbackNotification)?.messages?.single()?.person?.icon)
        assertNull(signedInNotification.getLargeIcon())
        val signedInStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(signedInNotification)
        assertEquals(context.getString(R.string.notification_self_name), signedInStyle?.user?.name)
        assertEquals(false, signedInStyle?.isGroupConversation)
        val signedInMessage = signedInStyle?.messages?.single()
        assertEquals("Contact with picture has just signed in.", signedInMessage?.text?.toString())
        assertEquals(signedInSentAtMillis, signedInMessage?.timestamp)
        assertEquals("Contact with picture", signedInMessage?.person?.name?.toString())
        assertEquals(contactPictureId, signedInMessage?.person?.key)
        val signedInPicture = signedInMessage?.person?.icon?.loadDrawable(context)?.toBitmap()
        assertNotNull(signedInPicture)
        requireNotNull(signedInPicture)
        assertEquals(Color.MAGENTA, signedInPicture.getPixel(signedInPicture.width / 2, signedInPicture.height / 2))
        assertEquals(shortcutId, NotificationCompat.getShortcutId(signedInNotification))
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
                    assertConversationIcon(posted, contactId)
                    val actual = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
                        ?.messages?.single()?.person?.icon?.loadDrawable(context)?.toBitmap(128, 128)
                    assertNotNull("Missing avatar for $type ($uri)", actual)
                    requireNotNull(actual)
                    if (uri?.startsWith("data:") == true) {
                        assertEquals(Color.MAGENTA, actual.getPixel(64, 64))
                    } else {
                        val initial = context.shortcutAvatarBitmap("Alice", null)
                        val expected = requireNotNull(IconCompat.createWithAdaptiveBitmap(initial)
                            .loadDrawable(context)).toBitmap(128, 128)
                        assertTrue("Wrong initial avatar for $type ($uri)", expected.sameAs(actual))
                    }
                    val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context)
                        .single { it.id == shortcutId }
                    assertEquals("  alice example  ", shortcut.shortLabel.toString())
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
    fun actionStatusUpdatesKeepConversationPhotoAfterRendererRecreation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val systemManager = context.getSystemService(NotificationManager::class.java)
        val contactId = "action-avatar@example.test"
        val contactName = "Action contact"
        systemManager.cancelAll()
        try {
            for (type in NotificationEventType.entries) {
                val id = MsngrNotificationManager.notificationId(type, contactId)
                val channelId = when (type) {
                    NotificationEventType.Message -> NotificationChannels.MESSAGES
                    NotificationEventType.Nudge -> NotificationChannels.NUDGES
                    NotificationEventType.ContactSignedIn -> NotificationChannels.CONTACT_SIGNED_IN
                }
                MsngrNotificationManager(context).show(NotificationEvent(
                    type, contactId, contactName, profilePictureDataUri(), messageText = "Hello",
                ))
                val previous = awaitNotification(id) { true }
                if (type == NotificationEventType.Message) {
                    // Reply submission marks the chat read and cancels its original notification.
                    systemManager.cancel(id)
                    MsngrNotificationManager(context).markReplying(contactId, contactName, id, previous)
                } else {
                    MsngrNotificationManager(context).markNudging(contactId, contactName, id, channelId)
                }
                val sendingText = if (type == NotificationEventType.Message) "Sending reply…"
                    else context.getString(R.string.notification_sending_nudge)
                val sending = awaitNotification(id) { messageText(it) == sendingText }
                assertConversationPhoto(sending, contactId)
                assertTrue(sending.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
                assertTrue(sending.actions.isNullOrEmpty())

                if (type == NotificationEventType.Message) {
                    MsngrNotificationManager(context).markReplyFailed(contactId, contactName, id)
                } else {
                    MsngrNotificationManager(context).markNudgeFailed(contactId, contactName, id, channelId)
                }
                val failed = awaitNotification(id) { messageText(it)?.contains("reconnect") == true }
                assertConversationPhoto(failed, contactId)
                assertEquals(channelId, failed.channelId)
                assertFalse(failed.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
            }

            // A restored worker can still post an initial avatar if the original was dismissed.
            val fallbackId = MsngrNotificationManager.notificationId(NotificationEventType.Message, "missing")
            MsngrNotificationManager(context).markReplyFailed("missing", "Missing contact", fallbackId)
            assertConversationIcon(awaitNotification(fallbackId) { true }, "missing")
        } finally {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(
                MsngrNotificationManager.conversationShortcutId(contactId),
                MsngrNotificationManager.conversationShortcutId("missing"),
            ))
            systemManager.cancelAll()
        }
    }

    @Test
    fun groupMessagesKeepGroupConversationIconAndMemberAttribution() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val contactId = "group-avatar@live.com"
        val id = MsngrNotificationManager.notificationId(NotificationEventType.Message, contactId)
        try {
            MsngrNotificationManager(context).show(NotificationEvent(
                NotificationEventType.Message, contactId, "Group with picture", profilePictureDataUri(),
                messageText = "From a member", isGroup = true, senderName = "Alice", senderEmail = "alice@example.test",
                senderAvatarUri = profilePictureDataUri(),
            ))
            val notification = awaitNotification(id) { messageText(it) == "From a member" }
            assertConversationIcon(notification, contactId)
            val style = requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
            assertTrue(style.isGroupConversation)
            assertEquals("Group with picture", style.conversationTitle.toString())
            assertEquals("Alice", style.messages.single().person?.name.toString())
            assertNotNull(style.messages.single().person?.icon)
            val shortcutId = MsngrNotificationManager.conversationShortcutId(contactId)
            assertEquals(shortcutId, NotificationCompat.getLocusId(notification)?.id)
            val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == shortcutId }
            assertEquals(shortcutId, shortcut.locusId?.id)
        } finally {
            manager.cancel(id)
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(MsngrNotificationManager.conversationShortcutId(contactId)))
        }
    }

    private fun awaitNotification(
        id: Int,
        matches: (android.app.Notification) -> Boolean,
    ): android.app.Notification {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(NotificationManager::class.java)
        repeat(30) {
            manager.activeNotifications.firstOrNull { it.id == id }?.notification
                ?.takeIf(matches)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Notification $id did not reach the expected state")
    }

    private fun messageText(notification: android.app.Notification): String? =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.messages?.singleOrNull()?.text?.toString()

    private fun assertConversationIcon(notification: android.app.Notification, contactId: String) {
        assertEquals(R.drawable.ic_notification_msngr, notification.smallIcon.resId)
        assertNull(notification.getLargeIcon())
        assertFalse(notification.extras.containsKey(android.app.Notification.EXTRA_PICTURE))
        assertEquals(MsngrNotificationManager.conversationShortcutId(contactId), NotificationCompat.getShortcutId(notification))
        val style = requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        assertNull(style.messages.single().dataUri)
    }

    private fun assertConversationPhoto(notification: android.app.Notification, contactId: String) {
        assertConversationIcon(notification, contactId)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.messages?.single()?.person?.icon
        val bitmap = requireNotNull(icon?.loadDrawable(context)).toBitmap(128, 128)
        assertEquals(Color.MAGENTA, bitmap.getPixel(64, 64))
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
