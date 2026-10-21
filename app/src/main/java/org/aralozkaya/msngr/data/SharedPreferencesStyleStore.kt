package org.aralozkaya.msngr.data

import android.content.Context
import android.content.SharedPreferences
import org.aralozkaya.msngr.NativeTextStyle
import java.security.MessageDigest

private const val SIZE_NOTICE_PREFIX = "size_notice_shown:"

internal fun persistentConversationStyleStore(context: Context): ConversationStyleStore {
    val appContext = context.applicationContext
    val preferences = mutableMapOf<String, SharedPreferences>()
    fun preferencesFor(account: String) = preferences.getOrPut(account) {
        val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        appContext.getSharedPreferences("conversation_styles_$key", Context.MODE_PRIVATE)
    }
    return ConversationStyleStore(
        loadAccount = { account ->
            preferencesFor(account).all.mapNotNull { (contact, value) ->
                (value as? String)?.let { source ->
                    runCatching {
                        val header = source.substringBefore('\n')
                        val size = source.substringAfter('\n').toInt()
                        contact to NativeTextStyle.fromFormatHeader(header).copy(size = size)
                    }.getOrNull()
                }
            }.toMap()
        },
        writeStyle = { account, contact, style ->
            preferencesFor(account).edit().apply {
                if (style == NativeTextStyle()) remove(contact)
                else putString(contact, style.toFormatHeader() + "\n" + style.size)
            }.apply()
        },
        loadSizeNotices = { account ->
            preferencesFor(account).all.entries.mapNotNull { (key, value) ->
                key.takeIf { it.startsWith(SIZE_NOTICE_PREFIX) && value == true }
                    ?.removePrefix(SIZE_NOTICE_PREFIX)
            }.toSet()
        },
        writeSizeNotice = { account, contact ->
            preferencesFor(account).edit().putBoolean(SIZE_NOTICE_PREFIX + contact, true).apply()
        },
    )
}
