package org.aralozkaya.msngr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.aralozkaya.msngr.data.AccountSession
import org.aralozkaya.msngr.pages.components.SessionsDialog
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionsDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val sessions = listOf(AccountSession("phone", "Phone", true),
        AccountSession("desktop", "Desktop"), AccountSession("laptop", "Laptop"))

    private fun checkAction(buttonTag: String, indicatorTag: String, label: String?, expected: Set<String>,
        closesCurrent: Boolean, fontScale: Float = 1f) {
        val pending = CompletableDeferred<Result<Unit>>()
        var requested: Set<String>? = null
        var closed = false
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { MsngrTheme {
            SessionsDialog(sessions, { ids -> requested = ids; pending.await() },
                { closed = true }, {})
        } } }
        compose.onNodeWithText("This device").assertExists()
        val button = if (label == null) compose.onNodeWithTag(buttonTag) else compose.onNodeWithText(label)
        button.performScrollTo().performClick()
        compose.onNodeWithText("Logging out…").assertDoesNotExist()
        compose.onNodeWithTag(buttonTag).assertIsEnabled()
        compose.onNodeWithTag(indicatorTag, useUnmergedTree = true).assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag(buttonTag)))
        compose.runOnIdle { pending.complete(Result.success(Unit)) }
        compose.waitForIdle()
        assertEquals(expected, requested)
        assertEquals(closesCurrent, closed)
    }

    @Test fun individualSessionTargetsOnlyItsOwnId() = checkAction("logout-session-desktop",
        "logout-session-loading-desktop", null, setOf("desktop"), false)
    @Test fun currentSessionCanBeEndedIndividually() = checkAction("logout-session-phone",
        "logout-session-loading-phone", null, setOf("phone"), true)
    @Test fun otherSessionsExcludeCurrent() = checkAction("logout-other-sessions",
        "logout-other-sessions-loading", "Log out other sessions", setOf("desktop", "laptop"), false)
    @Test fun allSessionsIncludeCurrent() = checkAction("logout-all-sessions",
        "logout-all-sessions-loading", "Log out all sessions", setOf("phone", "desktop", "laptop"), true)
    @Test fun largeTextKeepsBulkActionsReachable() = checkAction("logout-all-sessions",
        "logout-all-sessions-loading", "Log out all sessions", setOf("phone", "desktop", "laptop"), true, fontScale = 2f)

    @Test fun failureKeepsDialogOpenAndAllowsRetry() {
        val result = CompletableDeferred<Result<Unit>>()
        var active by mutableStateOf(sessions)
        compose.setContent { MsngrTheme {
            SessionsDialog(active, { result.await() }, {}, {})
        } }
        compose.onNodeWithTag("logout-session-phone").performScrollTo().performClick()
        compose.onNodeWithText("Logging out…").assertDoesNotExist()
        compose.onNodeWithTag("logout-session-phone").assertIsEnabled()
        compose.onNodeWithTag("logout-session-loading-phone", useUnmergedTree = true).assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("logout-session-phone")))
        compose.onNodeWithTag("logout-session-desktop").assertIsNotEnabled()
        compose.onNodeWithTag("logout-session-loading-desktop", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("logout-other-sessions").assertIsNotEnabled()
        compose.onNodeWithTag("logout-other-sessions-loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("logout-all-sessions").assertIsNotEnabled()
        compose.onNodeWithTag("logout-all-sessions-loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsNotEnabled()
        compose.runOnIdle { result.complete(Result.failure(IllegalStateException("Logout rejected"))) }
        compose.onNodeWithTag("logout-session-loading-phone", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Logout rejected").performScrollTo().assertExists()
        compose.onNodeWithText("Close").assertIsEnabled()
        compose.runOnIdle { active = listOf(sessions.first()) }
        compose.onNodeWithText("Log out all sessions").assertDoesNotExist()
        compose.onNodeWithTag("logout-session-phone").performScrollTo().assertIsEnabled()
    }
}
