package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.ServerPresets
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertEquals
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
        try {
            val events = listOf(
                NotificationEvent(NotificationEventType.Message, contact.id, contact.displayName, messageText = "Hello"),
                NotificationEvent(NotificationEventType.Nudge, contact.id, contact.displayName),
                NotificationEvent(NotificationEventType.FriendOnline, contact.id, contact.displayName),
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
    fun notificationReplyMarksOnlyRepliedConversationReadAtSubmission() = runBlocking {
        val repository = signedInRepository()
        val contact = repository.contacts.value.first()
        val other = repository.contacts.value.last()
        repository.markRead(contact.id)
        repository.markUnread(contact.id)
        repository.markUnread(other.id)
        val otherUnread = repository.contactSnapshot(other.id)!!.isUnread
        try {
            val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
                putExtra(NotificationReplyReceiver.EXTRA_CONTACT_ID, contact.id)
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
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            context.getSystemService(NotificationManager::class.java).cancelAll()
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
}
