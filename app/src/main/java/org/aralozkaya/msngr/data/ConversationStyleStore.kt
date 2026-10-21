package org.aralozkaya.msngr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aralozkaya.msngr.NativeTextStyle
import java.util.Locale

/** Account- and conversation-scoped styles survive clearing a draft or message history. */
class ConversationStyleStore(
    private val loadAccount: (String) -> Map<String, NativeTextStyle> = { emptyMap() },
    private val writeStyle: (String, String, NativeTextStyle) -> Unit = { _, _, _ -> },
    private val loadSizeNotices: (String) -> Set<String> = { emptySet() },
    private val writeSizeNotice: (String, String) -> Unit = { _, _ -> },
) {
    private val accounts = mutableMapOf<String, Map<String, NativeTextStyle>>()
    private val sizeNotices = mutableMapOf<String, Set<String>>()
    private var account: String? = null
    private val mutableStyles = MutableStateFlow<Map<String, NativeTextStyle>>(emptyMap())
    val styles = mutableStyles.asStateFlow()

    @Synchronized fun selectAccount(accountId: String) {
        val key = accountId.trim().lowercase(Locale.ROOT)
        if (account == key) return
        account = key
        mutableStyles.value = accounts.getOrPut(key) { loadAccount(key) }
    }

    /** Returns true only for the first size notice in this account and chat, even after a style reset. */
    @Synchronized fun markSizeNoticeShown(contactId: String): Boolean {
        val key = account ?: return false
        val contact = contactId.lowercase(Locale.ROOT)
        val shown = sizeNotices.getOrPut(key) { loadSizeNotices(key) }
        if (contact in shown) return false
        writeSizeNotice(key, contact)
        sizeNotices[key] = shown + contact
        return true
    }

    @Synchronized fun update(contactId: String, style: NativeTextStyle) {
        val key = account ?: return
        val contact = contactId.lowercase(Locale.ROOT)
        if ((mutableStyles.value[contact] ?: NativeTextStyle()) == style) return
        writeStyle(key, contact, style)
        val updated = if (style == NativeTextStyle()) mutableStyles.value - contact
            else mutableStyles.value + (contact to style)
        accounts[key] = updated
        mutableStyles.value = updated
    }

    @Synchronized fun deselectAccount() {
        account = null
        mutableStyles.value = emptyMap()
    }
}
