package org.aralozkaya.msngr

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineContactComposerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun offlinePrivateChatAllowsTextAndEmoticonsButDisablesNudges() {
        val draft = mutableStateOf("")
        val contact = mutableStateOf(Contact("peer", "Peer", "peer@example.com", Presence.Offline))
        compose.setContent {
            MsngrTheme {
                ChatScreen(draft = draft.value, onDraftChange = { draft.value = it }, contact = contact.value,
                    messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                    isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                    onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {})
            }
        }
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.onNodeWithContentDescription("Show emoticons").assertIsEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.runOnIdle { draft.value = "Offline text" }
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        compose.onNodeWithText("Peer is offline. Messages you send will be delivered when they sign in.").assertIsDisplayed()
        compose.runOnIdle { draft.value = ""; contact.value = contact.value.copy(presence = Presence.Online) }
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()
    }
}
