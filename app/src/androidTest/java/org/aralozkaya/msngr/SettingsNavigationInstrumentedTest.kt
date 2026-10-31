package org.aralozkaya.msngr

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var restoration: StateRestorationTester
    private var toolbarExitRequested = false
    private var compactViewport by mutableStateOf(false)

    @Before fun showSettings() {
        context = compose.activity
        SessionPrefs.logout()
        SessionPrefs.completeLogin(context, "settings-navigation@example.com", "test-password", true,
            ProviderOptions.ESCARGOT, ServerOverrides(), useMockServer = true)
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            Box(if (compactViewport) Modifier.height(480.dp) else Modifier.fillMaxSize()) {
                MsngrTheme {
                    SettingsRoute(onBack = { toolbarExitRequested = true }, onDeleteAllChatHistories = {})
                }
            }
        }
    }

    @After fun clearAccount() {
        compose.mainClock.autoAdvance = true
        SessionPrefs.disableRememberedCredentials(context)
        SessionPrefs.logout()
        AccountPreferences.reload(compose.activity)
    }

    @Test fun cancelledPredictiveBackPreviewsParentAndRestoresChildFromEitherEdge() {
        openCategory("Appearance")
        val original = compose.onNodeWithText("Theme").fetchSemanticsNode().boundsInRoot
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            startGesture(edge, 0.7f)
            compose.onNodeWithText("Settings").assertExists()
            compose.onAllNodesWithContentDescription("Back").assertCountEquals(2)
            val preview = compose.onNodeWithText("Theme").fetchSemanticsNode().boundsInRoot
            assertTrue("The child must shrink as the gesture progresses", preview.height < original.height)

            progressGesture(edge, 0.2f)
            val reversed = compose.onNodeWithText("Theme").fetchSemanticsNode().boundsInRoot
            assertTrue("Reversing the gesture must reverse its animation", reversed.height > preview.height)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            settle()

            compose.onNodeWithText("Settings").assertDoesNotExist()
            compose.onNodeWithText("Theme").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Back").assertCountEquals(1)
            assertEquals(original, compose.onNodeWithText("Theme").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun committedPredictiveBackPopsOneNestedPageAndToolbarPopsItsParent() {
        openCategory("In-app notifications")
        compose.onNodeWithTag("in_app_notification_options").performClick()
        compose.onNodeWithTag("customized_notification_settings").performClick()
        compose.onNodeWithTag("no_notification_customizations").assertIsDisplayed()

        startGesture(BackEventCompat.EDGE_LEFT, 0.6f)
        compose.onNodeWithTag("in_app_notification_options").assertExists()
        compose.onNodeWithTag("no_notification_customizations").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()

        compose.onNodeWithTag("no_notification_customizations").assertDoesNotExist()
        compose.onNodeWithTag("in_app_notification_options").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        assertFalse(toolbarExitRequested)
    }

    @Test fun predictiveBackDuringForwardAnimationCompletesAndAllowsNavigationAgain() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Appearance").performClick()
        compose.mainClock.advanceTimeBy(32)
        startGesture(BackEventCompat.EDGE_RIGHT, 0.6f)
        compose.onNodeWithText("Settings").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        openCategory("Appearance")
        compose.onNodeWithText("Theme").assertIsDisplayed()
    }

    @Test fun connectionInformationRestoresItsParentStackAfterSavedStateRestoration() {
        openCategory("Connection")
        openCategory("Connection information")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Remembered credentials").assertIsDisplayed()
        back()
        compose.onNodeWithTag("sign_out_other_sessions_on_sign_in").assertExists()
        back()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun everyCategorySupportsSystemBackAndRootLeavesSystemBackUnclaimed() {
        for (title in listOf("Appearance", "Emoticons & Winks", "Messenger Plus!", "Contact Invitations",
            "File & photo transfers", "In-app notifications", "Connection", "About", "App updates")) {
            openCategory(title)
            compose.onNodeWithText("Settings").assertDoesNotExist()
            compose.runOnIdle { assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
            back()
            compose.onNodeWithText("Settings").assertIsDisplayed()
        }
        compose.runOnIdle { assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(toolbarExitRequested)
    }

    @Test fun returningFromLicensesPreservesParentAndRootScrollState() {
        compose.runOnIdle { compactViewport = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("About"))
        val rootScroll = scrollOffset()
        assertTrue("The small viewport must require scrolling", rootScroll > 0f)
        compose.onNodeWithText("About").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open-source licenses"))
        val aboutScroll = scrollOffset()
        compose.onNodeWithText("Open-source licenses").performClick()
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.onNodeWithText("Open-source licenses").assertIsDisplayed()
        assertEquals(aboutScroll, scrollOffset(), 0f)
        back()
        compose.onNodeWithText("About").assertIsDisplayed()
        assertEquals(rootScroll, scrollOffset(), 0f)
    }

    private fun scrollOffset(): Float = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun openCategory(title: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun startGesture(edge: Int, progress: Float) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, edge))
        }
        compose.mainClock.advanceTimeByFrame()
        progressGesture(edge, progress)
    }

    private fun progressGesture(edge: Int, progress: Float) {
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 500f, progress, edge))
        }
        compose.mainClock.advanceTimeBy(64)
    }

    private fun settle() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }
}
