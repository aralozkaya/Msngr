package org.aralozkaya.msngr

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Runs the actual main-screen flow using only MockMsnp18Client and dummy credentials. */
class MockAddContactInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mockFabAdditionShowsOverlayThenSuccessAndVisibleContact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "owner@example.test"
        val peer = "peer@example.test"
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        val profile = repository.signIn(SignInRequest(owner, "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        // Mock and real accounts have distinct storage identities. The real credentials stay intact.
        SessionPrefs.completeLogin(context, owner, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = true, automaticSignIn = false)
        repository.setLocalProfile(SessionPrefs.mergeServerProfile(context, profile))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Mock server active · Test data only").assertIsDisplayed()
                compose.onNodeWithContentDescription("Open contact actions").performClick()
                compose.onNodeWithText("Add a contact").performClick()
                compose.onNodeWithText("Try adding a contact with mock data. No connection is made and no invitation is sent.")
                    .assertIsDisplayed()
                compose.onNodeWithTag("add-contact-email").performTextInput(peer)
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("add-contact-submit").performClick()
                compose.mainClock.advanceTimeBy(100)
                compose.onNodeWithTag("add-contact-spinner", useUnmergedTree = true).assertIsDisplayed()
                assertTrue(repository.contacts.value.none { it.email == peer })
                compose.mainClock.autoAdvance = true
                compose.waitUntil(5000) { repository.contacts.value.any { contact -> contact.email == peer } }
                compose.onNodeWithTag("add-contact-email").assertDoesNotExist()
                compose.onNodeWithText("Mock contact added.").assertIsDisplayed()
                compose.onNodeWithContentDescription("Dismiss").performClick()
                compose.onNodeWithText("Mock contact added.").assertDoesNotExist()
                compose.onNodeWithText("peer").assertIsDisplayed()
                assertEquals(1, repository.contacts.value.count { it.email == peer })
            }
        } finally {
            compose.mainClock.autoAdvance = true
            repository.logout()
            SessionPrefs.logout()
        }
    }
}
