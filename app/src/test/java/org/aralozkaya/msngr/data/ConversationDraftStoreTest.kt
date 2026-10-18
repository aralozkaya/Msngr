package org.aralozkaya.msngr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDraftStoreTest {
    @Test
    fun restoresExactTextAcrossStoreRecreationAndKeepsContactsSeparate() {
        val saved = mutableMapOf<String, Map<String, String>>()
        fun newStore() = ConversationDraftStore(
            loadAccount = { saved[it].orEmpty() },
            writeDraft = { account, contact, text ->
                saved[account] = if (text.isEmpty()) saved[account].orEmpty() - contact
                else saved[account].orEmpty() + (contact to text)
            },
        )
        val original = newStore()
        original.selectAccount(" Me@Example.com ")
        val text = "  [b]Hello[/b] :)\nNext line  "
        original.update("alice", text)
        original.update("bob", "Another draft")
        val restored = newStore()
        restored.selectAccount("me@example.com")
        assertEquals(mapOf("alice" to text, "bob" to "Another draft"), restored.drafts.value)
        restored.update("alice", "")
        val afterClear = newStore()
        afterClear.selectAccount("me@example.com")
        assertEquals(mapOf("bob" to "Another draft"), afterClear.drafts.value)
    }

    @Test
    fun accountSwitchAndLogoutNeverExposeOrOverwriteAnotherAccountsDraft() {
        val store = ConversationDraftStore()
        store.selectAccount("one@example.com")
        store.update("alice", "First account")
        store.selectAccount("two@example.com")
        assertTrue(store.drafts.value.isEmpty())
        store.update("alice", "Second account")
        store.deselectAccount()
        assertTrue(store.drafts.value.isEmpty())
        store.update("alice", "Signed out edit")
        store.selectAccount("one@example.com")
        assertEquals("First account", store.drafts.value["alice"])
        store.selectAccount("two@example.com")
        assertEquals("Second account", store.drafts.value["alice"])
    }

    @Test
    fun everyEditIsSavedWithoutWaitingForConversationToClose() {
        val writes = mutableListOf<String>()
        val store = ConversationDraftStore(writeDraft = { _, _, text -> writes += text })
        store.selectAccount("me@example.com")
        listOf("H", "He", "Hello", "Hello", "", " ").forEach { store.update("alice", it) }
        assertEquals(listOf("H", "He", "Hello", "", " "), writes)
        assertEquals(" ", store.drafts.value["alice"])
    }
}
