package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EmoticonSettings(
    val convertTypedShortcuts: Boolean = true,
    val useOriginalEmoticons: Boolean = true,
)

object EmoticonPrefs {
    private const val PREFS_NAME = "msngr_emoticons"
    private const val KEY_CONVERT_TYPED_SHORTCUTS = "convert_typed_shortcuts"
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

    fun setUseOriginalEmoticons(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableSettings.value.copy(useOriginalEmoticons = enabled))
    }

    internal fun reload(context: Context) {
        val preferences = AccountPreferences.preferences(context, PREFS_NAME)
        mutableSettings.value = EmoticonSettings(
            convertTypedShortcuts = preferences.getBoolean(KEY_CONVERT_TYPED_SHORTCUTS, true),
            useOriginalEmoticons = preferences.getBoolean(KEY_USE_ORIGINAL_EMOTICONS, true),
        )
    }

    private fun update(context: Context, settings: EmoticonSettings) {
        mutableSettings.value = settings
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putBoolean(KEY_CONVERT_TYPED_SHORTCUTS, settings.convertTypedShortcuts)
            putBoolean(KEY_USE_ORIGINAL_EMOTICONS, settings.useOriginalEmoticons)
        }
    }
}
