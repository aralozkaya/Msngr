package org.aralozkaya.msngr.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.AccountIdentity
import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.accountIdentity
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

/** Exercise the posted PendingIntents, receivers, WorkManager and backend together. */
class NotificationActionDeliveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val work = WorkManager.getInstance(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = MsngrNotificationManager(context) { false }
    private val requests = mutableSetOf<UUID>()

    @Test fun replyAndAllNudgeActionsDeliverThroughTheirPostedPendingIntents() = runBlocking {
        withAccount(useMockServer = true) {
            val repository = context.appContainer.repositoryFor(true)
            val login = SessionPrefs.readLoginSnapshot(context)
            repository.signIn(SignInRequest(login.email, login.password, login.resolvedServers)).getOrThrow()
            repository.clearConversationHistory()
            val contacts = repository.contacts.value.filter { !it.isGroup && !it.isBlocked }
            val contact = contacts.first()
            val reply = "PendingIntent reply"
            assertEquals(WorkInfo.State.SUCCEEDED, submit(NotificationEventType.Message, contact.id, reply).state)
            withTimeout(5_000) {
                repository.conversation(contact.id).first { conversation -> conversation.messages.any {
                    it.author == MessageAuthor.Me && it.text == reply && !it.isSending && it.deliveryError == null
                } }
            }

            for ((index, type) in NotificationEventType.entries.withIndex()) {
                val peer = contacts[index]
                assertEquals(WorkInfo.State.SUCCEEDED, submit(type, peer.id).state)
                withTimeout(5_000) {
                    repository.conversation(peer.id).first { conversation ->
                        conversation.messages.any { it.isNudge && it.text == "You sent a nudge." }
                    }
                }
            }
            // An accidental second tap must still fail immediately instead of sending later.
            val coolingDown = submit(NotificationEventType.Nudge, contact.id)
            assertEquals(WorkInfo.State.FAILED, coolingDown.state)
            assertEquals(1, coolingDown.runAttemptCount)
            assertEquals(1, repository.conversation(contact.id).first().messages.count { it.text == "You sent a nudge." })
        }
    }

    @Test fun revokedSessionsRequireSignInInsteadOfRetryingSavedCredentials() = runBlocking {
        withAccount(useMockServer = true) {
            val repository = context.appContainer.repositoryFor(true)
            SessionPrefs.recordRemoteSignOut(context)
            val peer = MockServerData.contacts().first().id
            for (reply in listOf("Reply after sign-out", null)) {
                val result = submit(NotificationEventType.Message, peer, reply)
                assertEquals(WorkInfo.State.FAILED, result.state)
                assertEquals(1, result.runAttemptCount)
                assertFalse(repository.hasSignedInSession)
            }
        }
    }

    @Test fun replyRetriesWhenTheRememberedSessionCannotConnect() = runBlocking {
        checkRestoreRetry(reply = "Reply waiting for reconnection")
    }

    @Test fun nudgeRetriesWhenTheRememberedSessionCannotConnect() = runBlocking {
        checkRestoreRetry(reply = null)
    }

    private suspend fun checkRestoreRetry(reply: String?) {
        // Reject a real MSNP connection locally, without relying on a public server or changing
        // the device's network. No message or nudge can have been submitted at this point.
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val rejector = thread(isDaemon = true, name = "notification-restore-rejection") {
                while (!server.isClosed) {
                    runCatching { server.accept().use { } }
                }
            }
            try {
                withAccount(useMockServer = false, servers = ServerOverrides(
                    mainServer = "127.0.0.1", mainPort = server.localPort, configServer = "127.0.0.1",
                )) {
                    val result = submit(NotificationEventType.Message, "restore-peer@example.test", reply)
                    assertEquals("Session restoration failures must use the delivery retry budget", WorkInfo.State.ENQUEUED, result.state)
                    assertTrue("A restore attempt must have run before retrying", result.runAttemptCount > 0)
                }
            } finally {
                server.close()
                rejector.join(1_000)
            }
        }
    }

    private suspend fun submit(type: NotificationEventType, contactId: String, reply: String? = null): WorkInfo {
        val tag = if (reply == null) NudgeContactWorker::class.java.name else ReplyMessageWorker::class.java.name
        val before = work.getWorkInfosByTag(tag).get().map { it.id }.toSet()
        notifications.show(NotificationEvent(type, contactId, "Action test contact", messageText = "Incoming message"))
        val notificationId = MsngrNotificationManager.notificationId(type, contactId)
        val notification: Notification = withTimeout(10_000) {
            while (true) {
                manager.activeNotifications.firstOrNull { it.id == notificationId }?.notification?.let { return@withTimeout it }
                delay(50)
            }
            error("Unreachable")
        }
        val action = notification.actions.single {
            if (reply == null) it.title.toString().startsWith("Nudge") else !it.remoteInputs.isNullOrEmpty()
        }
        if (reply == null) {
            action.actionIntent.send()
        } else {
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY).build()),
                fillIn, Bundle().apply { putCharSequence(NotificationReplyReceiver.KEY_TEXT_REPLY, reply) })
            action.actionIntent.send(context, 0, fillIn)
        }
        return withTimeout(15_000) {
            while (true) {
                val info = work.getWorkInfosByTag(tag).get().singleOrNull { it.id !in before }
                if (info != null) {
                    requests += info.id
                    if (info.state.isFinished || info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0) {
                        return@withTimeout info
                    }
                }
                delay(50)
            }
            error("Unreachable")
        }
    }

    private suspend fun withAccount(
        useMockServer: Boolean,
        servers: ServerOverrides = ServerOverrides(),
        block: suspend () -> Unit,
    ) {
        val previous = SessionPrefs.readLoginSnapshot(context)
        val provider = if (useMockServer) ProviderOptions.ESCARGOT else ProviderOptions.CUSTOM
        val fixture = AccountIdentity.forLogin("notification-actions@example.test", provider, servers, useMockServer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.logout()
        SessionPrefs.completeLogin(context, fixture.email, "fixture-password", true, provider, servers,
            useMockServer = useMockServer, automaticSignIn = true)
        try {
            block()
        } finally {
            requests.forEach { work.cancelWorkById(it).result.get() }
            requests.clear()
            runCatching {
                context.appContainer.messengerRepository.takeIf { it.signedInAccountIdentity == fixture }
                    ?.clearConversationHistory()
            }
            runCatching { context.appContainer.messengerRepository.logout() }
            manager.cancelAll()
            SessionPrefs.logout()
            SessionPrefs.disableRememberedCredentials(context, account = fixture)
            SessionPrefs.selectLoginAccount(context, previous.accountIdentity.takeIf { previous.email.isNotBlank() })
        }
    }
}
