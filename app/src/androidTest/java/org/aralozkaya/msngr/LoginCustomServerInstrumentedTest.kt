package org.aralozkaya.msngr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginCustomServerInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun loginScreenOpensFullScreenCustomServiceModalFromServiceMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.logout()
        val account = AccountIdentity.forLogin("custom-modal-test@example.test", useMockServer = true)
        SessionPrefs.disableRememberedCredentials(context, account = account)
        SessionPrefs.selectLoginAccount(context, account)

        val scenario = ActivityScenario.launch<LoginActivity>(
            LoginActivity.createIntent(context, allowAutomaticSignIn = false),
        )
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Welcome to Msngr")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(ProviderOptions.ESCARGOT).performScrollTo().performClick()
            compose.onNodeWithText(ProviderOptions.CUSTOM).performClick()

            compose.onNodeWithTag("custom-server-modal").assertIsDisplayed()
            compose.onNodeWithText("Custom MSNP service").assertIsDisplayed()
            scenario.onActivity { activity ->
                val modal = compose.onNodeWithTag("custom-server-modal").fetchSemanticsNode().boundsInRoot
                assertEquals(activity.window.decorView.width.toFloat(), modal.width, 1f)
                assertEquals(activity.window.decorView.height.toFloat(), modal.height, 1f)
            }

            compose.onNodeWithTag("custom-server-main-host").performTextReplacement("custom.msnp.test")
            compose.onNodeWithTag("custom-server-config-host").performTextReplacement("conf.msnp.test")
            compose.onNodeWithTag("custom-server-save").performClick()

            compose.onNodeWithTag("custom-server-modal").assertDoesNotExist()
            compose.onNodeWithText(ProviderOptions.CUSTOM).assertIsDisplayed()
            compose.onNodeWithText("custom.msnp.test:1863 \u00B7 conf.msnp.test")
                .performScrollTo()
                .assertIsDisplayed()
        } finally {
            scenario.close()
            SessionPrefs.disableRememberedCredentials(context, account = account)
        }
    }
}
