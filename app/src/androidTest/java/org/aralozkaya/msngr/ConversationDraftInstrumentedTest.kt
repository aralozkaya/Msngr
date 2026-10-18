package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.persistentConversationDraftStore
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.ContactRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationDraftInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun preferencesRestoreDraftAndPersistItsRemoval() {
        val account = "draft-test-${UUID.randomUUID()}@example.com"
        val text = "[b]Unsent[/b] :)\nStill here"
        val store = persistentConversationDraftStore(compose.activity)
        store.selectAccount(account)
        try {
            store.update("alice", text)
            val restored = persistentConversationDraftStore(compose.activity)
            restored.selectAccount(account)
            assertEquals(text, restored.drafts.value["alice"])
            restored.update("alice", "")
            val cleared = persistentConversationDraftStore(compose.activity)
            cleared.selectAccount(account)
            assertTrue(cleared.drafts.value.isEmpty())
        } finally {
            store.update("alice", "")
        }
    }

    @Test
    fun draftLabelAndUnreadBadgeAreBothVisible() {
        compose.setContent {
            MaterialTheme {
                ContactRow(
                    index = 0,
                    count = 1,
                    contact = Contact("alice", "Alice", "alice@example.com", Presence.Online, unreadCount = 3),
                    hasDraft = true,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithText("Draft").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("Alice").assertExists()
    }
}
