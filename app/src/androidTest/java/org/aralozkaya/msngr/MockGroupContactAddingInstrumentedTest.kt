package org.aralozkaya.msngr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises both real activities with group-only members and the packet-free mock client. */
class MockGroupContactAddingInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val owner = "group-owner@example.test"
    private val alice = "alice@example.test"
    private val bob = "bob@example.test"

    @Test fun memberTapOpensPrefilledFullScreenFormAndSavedMemberOpensChat() = withMockGroup { repository, groupId ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<GroupDetailsActivity>(GroupDetailsActivity.createIntent(context, groupId)).use { scenario ->
            compose.onNodeWithText(alice).performClick()
            compose.onNodeWithTag("add-contact-email").assertTextContains(alice)
            compose.onNodeWithTag("add-contact-submit").assertIsEnabled()
            val modal = compose.onNodeWithTag("add-contact-modal").fetchSemanticsNode().boundsInRoot
            scenario.onActivity {
                assertEquals(it.window.decorView.width.toFloat(), modal.width, 1f)
                assertEquals(it.window.decorView.height.toFloat(), modal.height, 1f)
            }
            compose.onNodeWithTag("add-contact-dismiss").performClick()
            compose.onNodeWithText("Group details").assertIsDisplayed()
            assertFalse(repository.contactSnapshot(alice)!!.isForwardListContact)

            compose.onNodeWithText(bob).performClick()
            compose.onNodeWithTag("add-contact-email").assertTextContains(bob)
            compose.onNodeWithTag("add-contact-submit").performClick()
            compose.waitUntil(5_000) { repository.contactSnapshot(bob)?.isForwardListContact == true }
            compose.onNodeWithTag("add-contact-modal").assertDoesNotExist()
            compose.onNodeWithText("Mock contact added.").assertIsDisplayed()

            val chatMonitor = instrumentation.addMonitor(ChatActivity::class.java.name, null, false)
            try {
                compose.onNodeWithText(bob).performClick()
                val chat = instrumentation.waitForMonitorWithTimeout(chatMonitor, 5_000)
                assertTrue("Saved members open their private chat", chat is ChatActivity)
                instrumentation.runOnMainSync { chat?.finish() }
            } finally {
                instrumentation.removeMonitor(chatMonitor)
            }
        }
    }

    @Test fun groupOnlyProfileAddsContactAndThenOffersDeletion() = withMockGroup { repository, groupId ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<GroupDetailsActivity>(GroupDetailsActivity.createIntent(context, groupId)).use {
            compose.onNodeWithContentDescription("View $alice's profile").performClick()
            compose.onNodeWithText("Delete contact").assertDoesNotExist()
            compose.onNodeWithText("Add a contact").performScrollTo().performClick()
            compose.onNodeWithTag("add-contact-email").assertTextContains(alice)
            compose.onNodeWithTag("add-contact-submit").performClick()
            compose.waitUntil(5_000) { repository.contactSnapshot(alice)?.isForwardListContact == true }
            compose.onNodeWithTag("add-contact-modal").assertDoesNotExist()
            compose.onNodeWithText("Mock contact added.").assertIsDisplayed()
            compose.onNodeWithText("Add a contact").assertDoesNotExist()
            compose.onNodeWithText("Delete contact").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Group details").assertIsDisplayed()
        }
    }

    private fun withMockGroup(test: (MessengerRepository, String) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        val profile = repository.signIn(SignInRequest(owner, "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, owner, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = true, automaticSignIn = false)
        repository.setLocalProfile(SessionPrefs.mergeServerProfile(context, profile))
        try {
            val groupId = repository.createGroup("Contact adding test").getOrThrow()
            repository.inviteGroupMembers(groupId, listOf(alice, bob), "").getOrThrow()
            test(repository, groupId)
        } finally {
            repository.logout()
            SessionPrefs.logout()
        }
    }
}
