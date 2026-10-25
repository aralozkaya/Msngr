package org.aralozkaya.msngr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Both real entry points, backed entirely by the packet-free mock client. */
class MockDeleteContactInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mainMenuDeletesUnblockedAndProfileDeletesWithBlock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "owner@example.test"
        val peer = "peer@example.test"
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        val profile = repository.signIn(SignInRequest(owner, "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, owner, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = true, automaticSignIn = false)
        repository.setLocalProfile(SessionPrefs.mergeServerProfile(context, profile))
        repository.addContact(peer).getOrThrow()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("peer").performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("Delete contact").performClick()
                compose.onNodeWithTag("delete-contact-block").assertIsOff()
                compose.onNodeWithText("Test data only. No connection is made and no packets are sent.").assertIsDisplayed()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("delete-contact-submit").performClick()
                compose.mainClock.advanceTimeBy(100)
                compose.onNodeWithTag("delete-contact-spinner", useUnmergedTree = true).assertIsDisplayed()
                compose.mainClock.autoAdvance = true
                compose.waitUntil(5000) { repository.contacts.value.none { c -> c.email == peer && c.isForwardListContact } }
                compose.onNodeWithText("Mock contact deleted.").assertIsDisplayed()
                compose.onNodeWithText("peer").assertDoesNotExist()
                assertFalse(repository.contacts.value.single { c -> c.email == peer }.isBlocked)
                repository.addContact(peer).getOrThrow()
                compose.onNodeWithContentDescription("View peer's profile").performScrollTo().performClick()
                compose.onNodeWithText("Delete contact").performScrollTo().performClick()
                compose.onNodeWithTag("delete-contact-block").assertIsOff().performClick()
                compose.onNodeWithTag("delete-contact-submit").performClick()
                compose.waitUntil(5000) { repository.contacts.value.any { c -> c.email == peer && c.isBlocked && !c.isForwardListContact } }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("delete-contact-modal").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Mock server active · Test data only").assertIsDisplayed()
                compose.onNodeWithText("peer").assertDoesNotExist()
            }
        } finally {
            compose.mainClock.autoAdvance = true
            repository.logout()
            SessionPrefs.logout()
        }
    }
}
