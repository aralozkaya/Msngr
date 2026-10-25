package org.aralozkaya.msngr

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.notifications.MsngrNotificationManager
import org.aralozkaya.msngr.notifications.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MockContactRequestsInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val peer = "peer@example.test"

    @Test fun loginRequestDrivesNotificationMenuBadgesAndDeclineWithOptionalBlock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val repository = context.appContainer.repositoryFor(true)
        repository.logout()
        val profile = repository.signIn(SignInRequest("owner@example.test", "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, profile.email, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), true, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("requests-icon-badge", useUnmergedTree = true).assertIsDisplayed()
                saveScreen("main")
                compose.onNodeWithContentDescription("Open menu").performClick()
                compose.onNodeWithTag("requests-menu-badge", useUnmergedTree = true).assertIsDisplayed()
                saveScreen("menu")
                compose.onNodeWithText("Requests & invitations").performClick()
                compose.onNodeWithTag("contact-request-$peer").assertIsDisplayed()
                saveScreen("requests")
                compose.waitUntil(5000) { manager.activeNotifications.any {
                    it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID } }
                val notice = manager.activeNotifications.single { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID }.notification
                assertEquals(NotificationChannels.CONTACT_REQUESTS, notice.channelId)
                assertEquals(1, notice.number)
                assertTrue(manager.getNotificationChannel(NotificationChannels.CONTACT_REQUESTS).canShowBadge())
                compose.onNodeWithTag("decline-request-$peer").performClick()
                compose.onNodeWithTag("contact-request-block").assertIsOff()
                saveScreen("decision")
                compose.onNodeWithText("Decide later").performClick()
                assertEquals(1, repository.contactRequests.value.size)
                compose.onNodeWithTag("decline-request-$peer").performClick()
                compose.onNodeWithTag("contact-request-block").performClick()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("contact-request-submit").performClick()
                compose.mainClock.advanceTimeBy(100)
                compose.onNodeWithTag("contact-request-spinner", useUnmergedTree = true).assertIsDisplayed()
                saveScreen("loading")
                compose.mainClock.autoAdvance = true
                compose.waitUntil(5000) { repository.contactRequests.value.isEmpty() }
                compose.onNodeWithText("No pending requests or invitations").assertIsDisplayed()
                saveScreen("empty")
                assertTrue(repository.contacts.value.single { contact -> contact.id == peer }.isBlocked)
                assertFalse(repository.contacts.value.single { contact -> contact.id == peer }.isForwardListContact)
                compose.waitUntil(5000) { manager.activeNotifications.none { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID } }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("requests-icon-badge", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithContentDescription("Open menu").performClick()
                compose.onNodeWithTag("requests-menu-badge", useUnmergedTree = true).assertDoesNotExist()
            }
        } finally { compose.mainClock.autoAdvance = true; repository.logout(); SessionPrefs.logout() }
    }

    @Test fun notificationOpensRequestsAndAcceptanceClearsTheNotification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val repository = context.appContainer.repositoryFor(true)
        repository.logout()
        val profile = repository.signIn(SignInRequest("owner@example.test", "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, profile.email, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), true, false)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5000) { manager.activeNotifications.any { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID } }
                manager.activeNotifications.single { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID }
                    .notification.contentIntent.send()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("contact-request-$peer").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(manager.activeNotifications.any { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID })
                compose.onNodeWithTag("accept-request-$peer").performClick()
                compose.onNodeWithTag("contact-request-block").assertIsNotEnabled()
                compose.onNodeWithTag("contact-request-submit").performClick()
                compose.waitUntil(5000) { repository.contactRequests.value.isEmpty() }
                compose.onNodeWithText("No pending requests or invitations").assertIsDisplayed()
                assertTrue(repository.contacts.value.single { contact -> contact.id == peer }.isForwardListContact)
                assertFalse(repository.contacts.value.single { contact -> contact.id == peer }.isBlocked)
                compose.waitUntil(5000) { manager.activeNotifications.none { it.id == MsngrNotificationManager.CONTACT_REQUESTS_NOTIFICATION_ID } }
            }
        } finally { repository.logout(); SessionPrefs.logout() }
    }

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(150)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "contact-requests-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
