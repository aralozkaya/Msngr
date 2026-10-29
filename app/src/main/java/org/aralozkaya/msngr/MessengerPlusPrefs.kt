package org.aralozkaya.msngr

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessengerPlusSettings(
    val enabled: Boolean = true,
    val hideControlCharacters: Boolean = true,
    val playEmotionSounds: Boolean = true,
) {
    val shouldHideControlCharacters: Boolean get() = enabled || hideControlCharacters
}

object MessengerPlusPrefs {
    internal const val PREFS_NAME = "msngr_messenger_plus"
    internal const val KEY_ENABLED = "enabled"
    internal const val KEY_HIDE_CONTROL_CHARACTERS = "hide_control_characters"

    private val mutableSettings = MutableStateFlow(MessengerPlusSettings())

    fun state(context: Context): StateFlow<MessengerPlusSettings> {
        reload(context)
        return mutableSettings.asStateFlow()
    }

    internal fun reload(context: Context) {
        mutableSettings.value = read(context)
    }

    fun isEnabled(context: Context): Boolean = read(context).enabled

    fun setEnabled(context: Context, enabled: Boolean) {
        mutableSettings.value = read(context).copy(enabled = enabled)
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putBoolean(KEY_ENABLED, enabled)
        }
    }

    fun setHideControlCharacters(context: Context, hide: Boolean) {
        val settings = read(context)
        if (settings.enabled) return
        mutableSettings.value = settings.copy(hideControlCharacters = hide)
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putBoolean(KEY_HIDE_CONTROL_CHARACTERS, hide)
        }
    }

    fun setPlayEmotionSounds(context: Context, enabled: Boolean) {
        AccountPreferences.preferences(context, PREFS_NAME).edit { putBoolean("play_emotion_sounds", enabled) }
        mutableSettings.value = read(context)
    }

    private fun read(context: Context): MessengerPlusSettings = MessengerPlusSettings(
        enabled = AccountPreferences.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLED, true),
        playEmotionSounds = AccountPreferences.preferences(context, PREFS_NAME).getBoolean("play_emotion_sounds", true),
        hideControlCharacters = AccountPreferences.preferences(context, PREFS_NAME)
            .getBoolean(KEY_HIDE_CONTROL_CHARACTERS, true),
    )
}

internal val LocalMessengerPlusEnabled = staticCompositionLocalOf { true }
internal val LocalMessengerPlusHideControlCharacters = staticCompositionLocalOf { true }

internal const val MESSENGER_PLUS_DISCLAIMER =
    "Recipients without Messenger Plus! installed will not see the rendered result and may see " +
        "formatting codes instead."
