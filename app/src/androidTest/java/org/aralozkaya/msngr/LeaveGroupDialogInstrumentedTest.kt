package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.CompletableDeferred
import org.aralozkaya.msngr.pages.components.LeaveGroupDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class LeaveGroupDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pendingLeaveReplacesItsActionAndBlocksDismissalUntilFailure() {
        val response = CompletableDeferred<Result<Unit>>()
        var calls = 0
        var left = false
        var dismissed = false
        var error: String? = null
        compose.setContent {
            MaterialTheme {
                LeaveGroupDialog("Test group", onLeave = { calls++; response.await() },
                    onLeft = { left = true }, onError = { error = it }, onDismiss = { dismissed = true })
            }
        }
        val actionBefore = compose.onNodeWithTag("group-leave-submit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("group-leave-submit").performClick()
        compose.waitUntil(5000) { calls == 1 }
        compose.onNodeWithTag("group-leave-submit").assertIsEnabled()
        compose.onNodeWithTag("group-leave-dismiss").assertIsNotEnabled()
        compose.onNodeWithTag("group-leave-loading-overlay").assertDoesNotExist()
        val indicator = compose.onNodeWithTag("group-leave-spinner", useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(actionBefore.center.x, indicator.center.x, 1f)
        assertEquals(actionBefore.center.y, indicator.center.y, 1f)
        compose.onNodeWithText("Leaving group…").assertDoesNotExist()
        pressBack()
        compose.runOnIdle { assertFalse(dismissed) }
        response.complete(Result.failure(IllegalStateException("Could not leave group.")))
        compose.waitUntil(5000) { error != null }
        compose.onNodeWithTag("group-leave-spinner", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("group-leave-submit").assertIsEnabled().assertTextEquals("Leave group")
        compose.onNodeWithText("Could not leave group.").assertDoesNotExist()
        compose.runOnIdle { assertFalse(left); assertEquals("Could not leave group.", error) }
    }

    @Test fun successCallsOnLeftWithoutReportingAnError() {
        var left = false
        compose.setContent {
            MaterialTheme {
                LeaveGroupDialog("Test group", onLeave = { Result.success(Unit) },
                    onLeft = { left = true }, onError = { fail(it) }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("group-leave-submit").performClick()
        compose.waitUntil(5000) { left }
    }
}
