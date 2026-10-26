package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WinkSettings(val playWinks: Boolean = true, val sounds: Boolean = true)
object WinkPrefs {
    private val current = MutableStateFlow(WinkSettings())
    fun state(context: Context): kotlinx.coroutines.flow.StateFlow<WinkSettings> { reload(context); return current.asStateFlow() }
    internal fun reload(context: Context) {
        val p = AccountPreferences.preferences(context, "msngr_winks")
        current.value = WinkSettings(p.getBoolean("play", true), p.getBoolean("sounds", true))
    }
    fun update(context: Context, settings: WinkSettings) {
        current.value = settings
        AccountPreferences.preferences(context, "msngr_winks").edit { putBoolean("play", settings.playWinks); putBoolean("sounds", settings.sounds) }
    }
}
