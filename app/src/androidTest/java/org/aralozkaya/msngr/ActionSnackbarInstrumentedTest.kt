package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.showActionSnackbar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ActionSnackbarInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun successNoticeHasCloseIconAndExpires() {
        val host = SnackbarHostState()
        compose.setContent {
            MaterialTheme {
                LaunchedEffect(Unit) { host.showActionSnackbar("Invitations sent.") }
                Scaffold(snackbarHost = { ActionSnackbarHost(host) }) { padding ->
                    Box(Modifier.padding(padding))
                }
            }
        }
        compose.onNodeWithText("Invitations sent.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
        }
        compose.waitUntil(10_000) { host.currentSnackbarData == null }
        compose.onNodeWithText("Invitations sent.").assertDoesNotExist()
    }

    @Test fun errorNoticeClosesWithItsTrailingIcon() {
        val host = SnackbarHostState()
        compose.setContent {
            MaterialTheme {
                LaunchedEffect(Unit) { host.showActionSnackbar("Could not send invitation.", isError = true) }
                Scaffold(snackbarHost = { ActionSnackbarHost(host) }) { padding ->
                    Box(Modifier.padding(padding))
                }
            }
        }
        compose.onNodeWithTag("action-error-snackbar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("Could not send invitation.").assertDoesNotExist()
        compose.runOnIdle { assertNull(host.currentSnackbarData) }
    }
}
