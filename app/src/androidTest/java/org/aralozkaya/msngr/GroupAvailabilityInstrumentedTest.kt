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
class GroupAvailabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineGroupDisablesComposerAndRestoresDraftWhenAnotherMemberReturns() {
        val contact = mutableStateOf(Contact("circle", "Test", "circle@live.com", Presence.Offline, isGroup = true))
        val available = mutableStateOf(true)
        val draft = mutableStateOf("Saved draft")
        compose.setContent {
            MsngrTheme {
                ChatScreen(draft = draft.value, onDraftChange = { draft.value = it }, contact = contact.value,
                    isConversationAvailable = available.value, messages = emptyList(), isHistoryLoaded = true,
                    canLoadOlderMessages = false, isTyping = false, onBack = {}, onSendMessage = { _, _ -> },
                    onRetryMessage = {}, onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {})
            }
        }
        val notice = "Test is currently offline. You can send a message when more group members are online."
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsNotEnabled().assertTextContains("Saved draft")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show emoticons").assertDoesNotExist()
        compose.runOnIdle { contact.value = contact.value.copy(presence = Presence.Online) }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsEnabled().assertTextContains("Saved draft")
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        compose.runOnIdle { draft.value = ""; contact.value = contact.value.copy(presence = Presence.Offline) }
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsNotEnabled()
        compose.runOnIdle { available.value = false }
        compose.onNodeWithText("Test is no longer available. You may have left or been removed from the group.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsNotEnabled()
    }
}
