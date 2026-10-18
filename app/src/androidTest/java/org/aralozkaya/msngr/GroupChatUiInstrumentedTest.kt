package org.aralozkaya.msngr

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupChatUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consecutiveMessagesFromDifferentMembersShowBothNames() {
        compose.setContent {
            MaterialTheme {
                ChatMessageList(
                    messages = listOf(
                        ChatMessage(1, "group", MessageAuthor.Contact, "First message", 1000, senderEmail = "alice@example.com", senderName = "Alice"),
                        ChatMessage(2, "group", MessageAuthor.Contact, "Second message", 2000, senderEmail = "bob@example.com", senderName = "Bob"),
                    ),
                    listState = rememberLazyListState(), emoticonSettings = EmoticonSettings(), innerPadding = PaddingValues(),
                    isGroup = true,
                )
            }
        }
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("Bob").assertIsDisplayed()
        compose.onNodeWithText("First message").assertIsDisplayed()
        compose.onNodeWithText("Second message").assertIsDisplayed()
    }
}
