package org.aralozkaya.msngr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MockGroupBlockingInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun groupDetailsBlockAndBlockedContactsUnblockUseTheRepositoryAndKeepMembership() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        val owner = "group-blocking@example.test"
        repository.signIn(SignInRequest(owner, "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, owner, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = true, automaticSignIn = false)
        try {
            val id = repository.createGroup("Blocking test group").getOrThrow()
            val members = repository.contacts.value.single { it.id == id }.members
            ActivityScenario.launch<GroupDetailsActivity>(GroupDetailsActivity.createIntent(context, id)).use {
                compose.onNodeWithContentDescription("Group options").performClick()
                compose.onNodeWithText("Block group").performClick()
                assertFalse(repository.contacts.value.single { it.id == id }.isBlocked)
                compose.onNodeWithTag("group-block-submit").performClick()
                compose.waitUntil(5000) { repository.contacts.value.single { it.id == id }.isBlocked }
                compose.onNodeWithTag("group-block-modal").assertDoesNotExist()
                compose.onNodeWithContentDescription("Group options").performClick()
                compose.onNodeWithText("Unblock group").assertIsDisplayed()
            }
            ActivityScenario.launch(BlockedContactsActivity::class.java).use {
                compose.onNodeWithText("Blocking test group").assertIsDisplayed()
                compose.onNodeWithTag("contact-row-$id").performTouchInput { longClick() }
                compose.onNodeWithText("Unblock").performClick()
                compose.onNodeWithText("Unblock group?").assertIsDisplayed()
                compose.onNodeWithTag("group-block-submit").performClick()
                compose.waitUntil(5000) { !repository.contacts.value.single { it.id == id }.isBlocked }
                compose.onNodeWithText("Blocking test group").assertDoesNotExist()
                assertEquals(members, repository.contacts.value.single { it.id == id }.members)
            }
        } finally { repository.logout(); SessionPrefs.logout() }
    }
}
