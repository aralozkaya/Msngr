package org.aralozkaya.msngr

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Rule
import org.junit.Test

class MockGroupFeedbackInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun groupLeftNoticeWorksForNewAndExistingMainActivitiesWithoutReplaying() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        repository.logout()
        val profile = repository.signIn(SignInRequest("mock-user@example.com", "mock-only",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        SessionPrefs.completeLogin(context, profile.email, "mock-only", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = true, automaticSignIn = false)
        try {
            ActivityScenario.launch<MainActivity>(MainActivity.groupLeftIntent(context, "First group")).use { main ->
                compose.onNodeWithText("You left First group.").assertIsDisplayed()
                compose.onNodeWithContentDescription("Dismiss").performClick()
                main.onActivity { it.startActivity(ChatActivity.createIntent(it, "selin")) }
                compose.onNodeWithContentDescription("Send nudge").assertIsDisplayed()
                instrumentation.runOnMainSync {
                    context.startActivity(MainActivity.groupLeftIntent(context, "Second group")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                compose.onNodeWithText("You left Second group.").assertIsDisplayed()
                compose.onNodeWithContentDescription("Open contact actions").assertIsDisplayed()
                compose.onNodeWithContentDescription("Send nudge").assertDoesNotExist()
                compose.onNodeWithContentDescription("Dismiss").performClick()
                main.recreate()
                compose.onNodeWithText("You left First group.").assertDoesNotExist()
                compose.onNodeWithText("You left Second group.").assertDoesNotExist()
            }
        } finally {
            repository.logout()
            SessionPrefs.logout()
        }
    }
}
