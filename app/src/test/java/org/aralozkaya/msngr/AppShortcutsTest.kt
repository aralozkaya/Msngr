package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Test

class AppShortcutsTest {
    @Test
    fun recentChatsArePromotedDeduplicatedAndLimitedToThree() {
        val alice = RecentChatShortcut("alice@example.com", "Alice")
        val bob = RecentChatShortcut("bob@example.com", "Bob")
        val carol = RecentChatShortcut("carol@example.com", "Carol")
        val david = RecentChatShortcut("david@example.com", "David")

        val first = updatedRecentChats(emptyList(), alice)
        val promoted = updatedRecentChats(listOf(alice, bob, carol), bob)
        val capped = updatedRecentChats(promoted, david)

        assertEquals(listOf(alice), first)
        assertEquals(listOf(bob, alice, carol), promoted)
        assertEquals(listOf(david, bob, alice), capped)
    }

    @Test
    fun reopeningFirstChatKeepsOrderButAChangedLabelUpdatesIt() {
        val alice = RecentChatShortcut("alice@example.com", "Alice")
        val bob = RecentChatShortcut("bob@example.com", "Bob")
        val current = listOf(alice, bob)

        assertEquals(current, updatedRecentChats(current, alice))
        assertEquals(
            listOf(alice.copy(label = "Alice Cooper"), bob),
            updatedRecentChats(current, alice.copy(label = "Alice Cooper")),
        )
    }
}
