package org.aralozkaya.msngr

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NudgeCooldownInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingAndCoolingNudgesDisableTheButtonWhileTextRemainsAvailable() {
        val wait = mutableStateOf(Long.MAX_VALUE)
        val draft = mutableStateOf("")
        var nudges = 0
        compose.setContent {
            MsngrTheme {
                ChatScreen(draft = draft.value, onDraftChange = { draft.value = it },
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                    isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                    onSendTyping = {}, onSendNudge = { nudges++ }, onLoadOlderMessages = {},
                    nudgeCooldownMillis = wait.value)
            }
        }
        compose.onNodeWithText("Sending nudge…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.runOnIdle { wait.value = 11_000 }
        compose.onNodeWithText("You can send another nudge in 11 seconds.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.runOnIdle { draft.value = "Text during cooldown" }
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        compose.runOnIdle { draft.value = ""; wait.value = 0 }
        compose.onNodeWithText("You can send another nudge in 11 seconds.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, nudges) }
    }
}
