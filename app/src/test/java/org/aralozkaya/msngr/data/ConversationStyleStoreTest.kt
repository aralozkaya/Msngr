package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.NativeTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStyleStoreTest {
    @Test fun sizeNoticeIsOncePerAccountAndChatAndSurvivesResetAndRecreation() {
        val notices = mutableMapOf<String, Set<String>>()
        fun store() = ConversationStyleStore(
            loadSizeNotices = { notices[it].orEmpty() },
            writeSizeNotice = { account, contact ->
                notices[account] = notices[account].orEmpty() + contact
            },
        )
        val first = store()
        assertFalse(first.markSizeNoticeShown("peer@example.com"))
        first.selectAccount(" ACCOUNT ")
        assertTrue(first.markSizeNoticeShown("PEER@example.com"))
        assertFalse(first.markSizeNoticeShown("peer@example.com"))
        first.update("peer@example.com", NativeTextStyle(size = 24))
        first.update("peer@example.com", NativeTextStyle())
        assertFalse(first.markSizeNoticeShown("peer@example.com"))
        assertTrue(first.markSizeNoticeShown("circle@live.com"))
        first.selectAccount("other")
        assertTrue(first.markSizeNoticeShown("peer@example.com"))
        first.deselectAccount()
        assertFalse(first.markSizeNoticeShown("new@example.com"))
        first.selectAccount("account")
        assertFalse(first.markSizeNoticeShown("peer@example.com"))
        val restored = store()
        restored.selectAccount("account")
        assertFalse(restored.markSizeNoticeShown("peer@example.com"))
        assertFalse(restored.markSizeNoticeShown("circle@live.com"))
        assertTrue(restored.markSizeNoticeShown("new@example.com"))
    }

    @Test fun isolatesAccountsContactsAndGroupsAndPersistsReset() {
        val disk = mutableMapOf<String, Map<String, NativeTextStyle>>()
        fun store() = ConversationStyleStore({ disk[it].orEmpty() }, { account, contact, style ->
            disk[account] = disk[account].orEmpty() + (contact to style)
        })
        val style = NativeTextStyle(bold = true, colorRgb = 0x123456, size = 22)
        val first = store()
        first.selectAccount(" ACCOUNT ")
        first.update("PEER@example.com", style)
        first.update("circle@live.com", style.copy(italic = true))
        first.selectAccount("other")
        assertTrue(first.styles.value.isEmpty())
        first.update("peer@example.com", style.copy(size = 12))
        first.deselectAccount()
        assertTrue(first.styles.value.isEmpty())
        val restored = store()
        restored.selectAccount("account")
        assertEquals(style, restored.styles.value["peer@example.com"])
        assertEquals(style.copy(italic = true), restored.styles.value["circle@live.com"])
        restored.update("peer@example.com", NativeTextStyle())
        assertNull(restored.styles.value["peer@example.com"])
        val reset = store()
        reset.selectAccount("account")
        assertEquals(NativeTextStyle(), reset.styles.value["peer@example.com"])
    }
}
