package org.aralozkaya.msngr

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAvailabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enhancedFeaturesTrackOnlyNonOfflineRecipientsAndCloseUnavailableHandwriting() {
        val me = "me@example.com"
        val supported = GroupMember("supported@example.com", presence = Presence.Away, clientCapabilities = 0x800c)
        val legacy = GroupMember("legacy@example.com")
        val contact = mutableStateOf(Contact("circle", "Test", "circle@live.com", Presence.Online, isGroup = true,
            members = listOf(GroupMember(me.uppercase(), presence = Presence.Online), supported, legacy)))
        compose.setContent {
            MsngrTheme {
                ChatScreen(draft = "Saved draft", onDraftChange = {}, contact = contact.value, ownEmail = me,
                    messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                    isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                    onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {})
            }
        }
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsEnabled()
        compose.onNodeWithText("Winks").assertIsEnabled()
        compose.runOnIdle {
            contact.value = contact.value.copy(members = contact.value.members.map {
                if (it.email == legacy.email) it.copy(presence = Presence.Busy) else it
            })
        }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Winks").assertIsNotEnabled()
        compose.runOnIdle {
            contact.value = contact.value.copy(members = contact.value.members.filter { it.email != legacy.email })
        }
        compose.onNodeWithText("Winks").assertIsEnabled()
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertIsDisplayed()
        compose.runOnIdle {
            contact.value = contact.value.copy(members = contact.value.members.map { it.copy(clientCapabilities = 0) })
        }
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsEnabled().assertTextContains("Saved draft")
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Winks").assertIsNotEnabled()
        compose.runOnIdle {
            contact.value = contact.value.copy(members = listOf(supported.copy(clientCapabilities = 0x04)))
        }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsEnabled()
        compose.onNodeWithText("Winks").assertIsNotEnabled()
    }

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
        compose.onNodeWithContentDescription("Show emoticons").assertIsNotEnabled()
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
