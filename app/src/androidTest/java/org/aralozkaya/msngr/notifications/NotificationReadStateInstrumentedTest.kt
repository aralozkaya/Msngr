package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.ServerPresets
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationReadStateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun readingConversationClearsItsMessagesAndNudgesOnly() = runBlocking {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val systemManager = context.getSystemService(NotificationManager::class.java)
        val notifications = MsngrNotificationManager(context)
        val repository = signedInRepository()
        val contact = repository.contacts.value.first()
        val other = repository.contacts.value.last()
        systemManager.cancelAll()
        delay(600)
        try {
            val events = listOf(
                NotificationEvent(NotificationEventType.Message, contact.id, contact.displayName, messageText = "Hello"),
                NotificationEvent(NotificationEventType.Nudge, contact.id, contact.displayName),
                NotificationEvent(NotificationEventType.ContactSignedIn, contact.id, contact.displayName),
                NotificationEvent(NotificationEventType.Message, other.id, other.displayName, messageText = "Still unread"),
            )
            events.forEach { notifications.show(it) }
            val allIds = events.map {
                MsngrNotificationManager.notificationId(it.type, it.contactId)
            }.toSet()
            awaitNotificationIds(systemManager, allIds)
            repository.markUnread(contact.id)
            repository.markUnread(other.id)

            repository.markRead(contact.id)

            assertTrue(!repository.contactSnapshot(contact.id)!!.isUnread)
            assertTrue(repository.contactSnapshot(other.id)!!.isUnread)
            awaitNotificationIds(systemManager, allIds - setOf(
                MsngrNotificationManager.notificationId(NotificationEventType.Message, contact.id),
                MsngrNotificationManager.notificationId(NotificationEventType.Nudge, contact.id),
            ))
        } finally {
            systemManager.cancelAll()
            repository.logout()
        }
    }

    @Test
    fun notificationReplyMarksOnlyRepliedConversationReadAndKeepsConversationNotification() = runBlocking {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        val systemManager = context.getSystemService(NotificationManager::class.java)
        val notifications = MsngrNotificationManager(context)
        val repository = signedInRepository()
        val contact = repository.contacts.value.first()
        val other = repository.contacts.value.last()
        val messageNotificationId = MsngrNotificationManager.notificationId(
            NotificationEventType.Message,
            contact.id,
        )
        val groupId = "group-conv@live.com"
        val groupNotificationId = MsngrNotificationManager.notificationId(
            NotificationEventType.Message,
            groupId,
        )
        systemManager.cancelAll()
        delay(800)
        repository.markRead(contact.id)
        repository.markUnread(contact.id)
        repository.markUnread(other.id)
        val otherUnread = repository.contactSnapshot(other.id)!!.isUnread
        try {
            // 1. Private chat: incoming message -> our reply -> new incoming MSNP message while expanded
            notifications.show(
                NotificationEvent(
                    type = NotificationEventType.Message,
                    contactId = contact.id,
                    contactName = contact.displayName,
                    messageText = "Incoming hello",
                ),
            )
            awaitNotificationIds(systemManager, setOf(messageNotificationId))

            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                putExtra(NotificationReplyReceiver.EXTRA_CONTACT_ID, contact.id)
                putExtra(NotificationReplyReceiver.EXTRA_CONTACT_NAME, contact.displayName)
                putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, messageNotificationId)
            }
            val input = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY).build()
            fun reply(text: String) {
                RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply {
                    putCharSequence(NotificationReplyReceiver.KEY_TEXT_REPLY, text)
                })
                NotificationReplyReceiver().onReceive(context, intent)
            }
            reply("   ")
            assertTrue(repository.contactSnapshot(contact.id)!!.isUnread)

            reply("Thanks!")

            assertTrue(!repository.contactSnapshot(contact.id)!!.isUnread)
            assertEquals(otherUnread, repository.contactSnapshot(other.id)?.isUnread)

            // The notification remains active after replying and appends the incoming reply
            // delivered through the MSNP event stream while expanded.
            val updatedNotification = awaitConversationMessages(
                systemManager,
                messageNotificationId,
                listOf(
                    "Incoming hello",
                    "Thanks!",
                    MockServerData.replyTo("Thanks!"),
                ),
            )
            val updatedStyle = requireNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(updatedNotification),
            )
            assertFalse(updatedStyle.isGroupConversation)
            assertEquals(contact.displayName, updatedStyle.messages[0].person?.name?.toString())
            assertNull(updatedStyle.messages[1].person)
            assertEquals(contact.displayName, updatedStyle.messages[2].person?.name?.toString())

            // 2. Group chat: incoming message -> our reply -> new message from another member while expanded
            delay(500)
            notifications.show(
                NotificationEvent(
                    type = NotificationEventType.Message,
                    contactId = groupId,
                    contactName = "Design Group",
                    messageText = "Hello team",
                    isGroup = true,
                    senderName = "Alice",
                    senderEmail = "alice@example.test",
                ),
            )
            awaitConversationMessages(systemManager, groupNotificationId, listOf("Hello team"))

            delay(250)
            notifications.appendReply(
                contactId = groupId,
                contactName = "Design Group",
                replyText = "Our group answer",
                notificationId = groupNotificationId,
                isGroup = true,
            )
            val repliedGroupNotification = awaitConversationMessages(
                systemManager,
                groupNotificationId,
                listOf("Hello team", "Our group answer"),
            )
            val repliedGroupStyle = requireNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(repliedGroupNotification),
            )
            assertTrue(repliedGroupStyle.isGroupConversation)
            assertEquals("Design Group", repliedGroupStyle.conversationTitle?.toString())
            assertEquals("Alice", repliedGroupStyle.messages[0].person?.name?.toString())
            assertNull(repliedGroupStyle.messages[1].person)

            delay(250)
            notifications.show(
                NotificationEvent(
                    type = NotificationEventType.Message,
                    contactId = groupId,
                    contactName = "Design Group",
                    messageText = "Follow-up from Bob",
                    isGroup = true,
                    senderName = "Bob",
                    senderEmail = "bob@example.test",
                ),
            )
            val updatedGroupNotification = awaitConversationMessages(
                systemManager,
                groupNotificationId,
                listOf("Hello team", "Our group answer", "Follow-up from Bob"),
            )
            val updatedGroupStyle = requireNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(updatedGroupNotification),
            )
            assertTrue(updatedGroupStyle.isGroupConversation)
            assertEquals("Design Group", updatedGroupStyle.conversationTitle?.toString())
            assertEquals("Alice", updatedGroupStyle.messages[0].person?.name?.toString())
            assertNull(updatedGroupStyle.messages[1].person)
            assertEquals("Bob", updatedGroupStyle.messages[2].person?.name?.toString())
            assertNotNull(updatedGroupStyle.messages[2].person?.icon)
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            systemManager.cancelAll()
            repository.logout()
        }
    }

    private suspend fun signedInRepository(): MessengerRepository =
        context.appContainer.repositoryFor(useMockServer = true).also { repository ->
            repository.signIn(SignInRequest(
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
            )).getOrThrow()
        }

    private fun awaitNotificationIds(manager: NotificationManager, expected: Set<Int>) {
        fun eventIds(): Set<Int> = manager.activeNotifications
            .filterNot { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
            .map { it.id }.toSet()
        repeat(30) {
            if (eventIds() == expected) return
            SystemClock.sleep(100)
        }
        assertEquals(expected, eventIds())
    }

    private fun awaitConversationMessages(
        manager: NotificationManager,
        notificationId: Int,
        expected: List<String>,
    ): Notification {
        repeat(40) {
            val notification = manager.activeNotifications
                .firstOrNull { it.id == notificationId }
                ?.notification
            val messages = notification?.let(
                NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification,
            )?.messages?.mapNotNull { it.text?.toString() }
            if (notification != null && messages == expected) {
                return notification
            }
            SystemClock.sleep(100)
        }
        val actual = manager.activeNotifications
            .firstOrNull { it.id == notificationId }
            ?.notification
            ?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)
            ?.messages?.mapNotNull { it.text?.toString() }
        assertEquals(expected, actual)
        error("Unreachable")
    }
}
