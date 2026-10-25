package org.aralozkaya.msngr.notifications

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.ChatActivity
import org.aralozkaya.msngr.ContactProfileActivity
import org.aralozkaya.msngr.GroupDetailsActivity
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.ServerPresets
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class InAppNotificationHostInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sharedOverlayLeavesChatInteractiveAndOpensAnotherConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.logout()
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
            false, ProviderOptions.ESCARGOT, ServerOverrides(), useMockServer = true)
        InAppNotificationPrefs.update(context) { InAppNotificationSettings(defaults = InAppNotificationOptions(vibrate = false)) }
        val contacts = repository.contacts.value.filterNot { it.isGroup }
        val first = contacts.first()
        val second = contacts[1]
        val controller = (context.applicationContext as MsngrApplication).inAppNotifications
        val scenario = ActivityScenario.launch<ChatActivity>(ChatActivity.createIntent(context, first.id))
        try {
            compose.onNodeWithContentDescription("Back").assertExists()
            compose.onNodeWithTag("other_chats_unread_badge", useUnmergedTree = true).assertExists()
            File(context.getExternalFilesDir(null), "chat-unread-back-badge.png").outputStream().use {
                compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            // A transparent idle overlay must not swallow typing or touches in the chat.
            compose.onNode(hasSetTextAction()).performTextInput("Unsent notification test")
            compose.onNode(hasSetTextAction()).assertTextContains("Unsent notification test")
            controller.show(InAppNotification(InAppNotificationType.Message, second.displayName, "A message from another chat", second.id))
            compose.waitUntil(3000) { compose.onAllNodesWithTag("in_app_notification_banner").fetchSemanticsNodes().isNotEmpty() }
            val file = File(context.getExternalFilesDir(null), "chat-in-app-notification.png")
            compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithTag("in_app_notification_banner").performClick()
            compose.waitUntil(3000) { compose.onAllNodesWithText(second.displayName).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("in_app_notification_banner").assertDoesNotExist()
            assertEquals("Unsent notification test", repository.drafts.value[first.id])
            // Detail pages expose the same conversation controls.
            scenario.onActivity { it.startActivity(ContactProfileActivity.createIntent(it, first.id)) }
            compose.onNodeWithTag("contact_notification_settings").performScrollTo().performClick()
            compose.onNodeWithText("Custom notifications").assertIsDisplayed()
            compose.onNodeWithTag("use_custom_notifications").assertIsOff().performClick().assertIsOn()
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey(first.id.notificationKey()))
            val groupId = repository.createGroup("Notification test group").getOrThrow()
            scenario.onActivity { it.startActivity(GroupDetailsActivity.createIntent(it, groupId)) }
            compose.onNodeWithTag("group_notification_settings").assertDoesNotExist()
            compose.onNodeWithContentDescription("Group options").performClick()
            compose.onNodeWithTag("group_notification_settings").performClick()
            compose.onNodeWithText("Custom notifications").assertIsDisplayed()
            compose.onNodeWithTag("use_custom_notifications").assertIsOff().performClick().assertIsOn()
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("in_app_type_GroupMessage"))
            compose.onNodeWithTag("in_app_type_GroupMessage").assertIsOn()
            assertTrue(InAppNotificationPrefs.read(context).conversations[groupId.notificationKey()]!!.isGroup)
        } finally {
            controller.dismiss()
            scenario.onActivity { it.finishAndRemoveTask() }
            scenario.close()
            InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
            repository.updateDraft(first.id, "")
            repository.logout()
            SessionPrefs.logout()
        }
    }
}
