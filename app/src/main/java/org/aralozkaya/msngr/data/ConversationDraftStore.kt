package org.aralozkaya.msngr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Keeps the exact composer source, including formatting and emoticon tokens. */
class ConversationDraftStore(
    private val loadAccount: (String) -> Map<String, String> = { emptyMap() },
    private val writeDraft: (accountId: String, contactId: String, text: String) -> Unit = { _, _, _ -> },
) {
    private val accounts = mutableMapOf<String, Map<String, String>>()
    private var selectedAccount: String? = null
    private val mutableDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()

    @Synchronized
    fun selectAccount(accountId: String) {
        val account = accountId.trim().lowercase(Locale.ROOT)
        if (selectedAccount == account) return
        val drafts = accounts.getOrPut(account) { loadAccount(account) }
        selectedAccount = account
        mutableDrafts.value = drafts
    }

    @Synchronized
    fun update(contactId: String, text: String) {
        val account = selectedAccount ?: return
        if (mutableDrafts.value[contactId].orEmpty() == text) return
        // Persist every edit, including clearing, rather than relying on screen disposal.
        writeDraft(account, contactId, text)
        val updated = if (text.isEmpty()) mutableDrafts.value - contactId
        else mutableDrafts.value + (contactId to text)
        accounts[account] = updated
        mutableDrafts.value = updated
    }

    @Synchronized
    fun deselectAccount() {
        selectedAccount = null
        mutableDrafts.value = emptyMap()
    }
}
