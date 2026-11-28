package org.aralozkaya.msngr.data

import android.content.Context
import android.content.SharedPreferences
import org.aralozkaya.msngr.sha256Hex

/** SharedPreferences.apply queues disk writes immediately and flushes at lifecycle transitions. */
internal fun persistentConversationDraftStore(context: Context): ConversationDraftStore {
    val appContext = context.applicationContext
    val preferences = mutableMapOf<String, SharedPreferences>()
    fun preferencesFor(account: String): SharedPreferences = preferences.getOrPut(account) {
        val key = account.sha256Hex()
        appContext.getSharedPreferences("conversation_drafts_$key", Context.MODE_PRIVATE)
    }
    return ConversationDraftStore(
        loadAccount = { account ->
            preferencesFor(account).all.mapNotNull { (contact, value) ->
                (value as? String)?.takeIf { it.isNotEmpty() }?.let { contact to it }
            }.toMap()
        },
        writeDraft = { account, contact, text ->
            preferencesFor(account).edit().apply {
                if (text.isEmpty()) remove(contact) else putString(contact, text)
            }.apply()
        },
    )
}
