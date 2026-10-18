package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

enum class ColorMode(val label: String) {
    Default("Default"),
    Dynamic("Dynamic"),
}

data class AppAppearance(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorMode: ColorMode = ColorMode.Dynamic,
)

object AppearancePrefs {
    private const val PREFS_NAME = "msngr_appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_COLOR = "color"
    private val mutableAppearance = MutableStateFlow(AppAppearance())

    fun state(context: Context): StateFlow<AppAppearance> {
        reload(context)
        return mutableAppearance.asStateFlow()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        reload(context)
        val updated = mutableAppearance.value.copy(themeMode = mode)
        mutableAppearance.value = updated
        persist(context, updated)
        AppShortcuts.refresh(context)
    }

    fun setColorMode(context: Context, mode: ColorMode) {
        reload(context)
        val updated = mutableAppearance.value.copy(colorMode = mode)
        mutableAppearance.value = updated
        persist(context, updated)
        AppShortcuts.refresh(context)
    }

    internal fun reload(context: Context) {
        val prefs = AccountPreferences.preferences(context, PREFS_NAME)
        mutableAppearance.value = AppAppearance(
            themeMode = enumValueOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.System),
            colorMode = enumValueOrDefault(prefs.getString(KEY_COLOR, null), ColorMode.Dynamic),
        )
    }

    private fun persist(context: Context, appearance: AppAppearance) {
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putString(KEY_THEME, appearance.themeMode.name)
            putString(KEY_COLOR, appearance.colorMode.name)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
