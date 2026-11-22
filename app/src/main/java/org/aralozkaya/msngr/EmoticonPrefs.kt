package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EmoticonStyle(val storageValue: String) {
    Wlm2009("wlm_2009"),
    Wlm2012("wlm_2012"),
    AndroidEmoji("android_emoji");

    val usesArtwork: Boolean
        get() = this != AndroidEmoji

    companion object {
        fun fromStorage(value: String?): EmoticonStyle? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class EmoticonSettings(
    val convertTypedShortcuts: Boolean = true,
    val style: EmoticonStyle = EmoticonStyle.Wlm2009,
) {
    val useOriginalEmoticons: Boolean
        get() = style.usesArtwork
}

object EmoticonPrefs {
    private const val PREFS_NAME = "msngr_emoticons"
    private const val KEY_CONVERT_TYPED_SHORTCUTS = "convert_typed_shortcuts"
    private const val KEY_EMOTICON_STYLE = "emoticon_style"
    private const val KEY_USE_ORIGINAL_EMOTICONS = "use_original_emoticons"

    private val mutableSettings = MutableStateFlow(EmoticonSettings())

    fun state(context: Context): StateFlow<EmoticonSettings> {
        reload(context)
        return mutableSettings.asStateFlow()
    }

    fun setConvertTypedShortcuts(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableSettings.value.copy(convertTypedShortcuts = enabled))
    }

    fun setEmoticonStyle(context: Context, style: EmoticonStyle) {
        reload(context)
        update(context, mutableSettings.value.copy(style = style))
    }

    fun setUseOriginalEmoticons(context: Context, enabled: Boolean) {
        setEmoticonStyle(
            context,
            if (enabled) EmoticonStyle.Wlm2009 else EmoticonStyle.AndroidEmoji,
        )
    }

    internal fun reload(context: Context) {
        val preferences = AccountPreferences.preferences(context, PREFS_NAME)
        val style = EmoticonStyle.fromStorage(preferences.getString(KEY_EMOTICON_STYLE, null))
            ?: if (preferences.getBoolean(KEY_USE_ORIGINAL_EMOTICONS, true)) {
                EmoticonStyle.Wlm2009
            } else {
                EmoticonStyle.AndroidEmoji
            }
        mutableSettings.value = EmoticonSettings(
            convertTypedShortcuts = preferences.getBoolean(KEY_CONVERT_TYPED_SHORTCUTS, true),
            style = style,
        )
    }

    private fun update(context: Context, settings: EmoticonSettings) {
        mutableSettings.value = settings
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putBoolean(KEY_CONVERT_TYPED_SHORTCUTS, settings.convertTypedShortcuts)
            putString(KEY_EMOTICON_STYLE, settings.style.storageValue)
            putBoolean(KEY_USE_ORIGINAL_EMOTICONS, settings.style.usesArtwork)
        }
    }
}
