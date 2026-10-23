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
    Scenes("Scenes"),
}

data class AppAppearance(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorMode: ColorMode = ColorMode.Dynamic,
    val sceneId: String = "daisyhill",
    val customColorArgb: Int? = null,
    val sceneColorArgb: Int? = null,
    val showSceneInConversations: Boolean = true,
    val darkenScenesInDarkMode: Boolean = true,
)

object AppearancePrefs {
    private const val PREFS_NAME = "msngr_appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_COLOR = "color"
    private const val KEY_SCENE = "scene"
    private const val KEY_CUSTOM_COLOR = "custom_color"
    private const val KEY_SCENE_COLOR = "scene_color"
    private const val KEY_SCENE_IN_CONVERSATIONS = "scene_in_conversations"
    private const val KEY_DARKEN_SCENES = "darken_scenes"
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

    fun setScene(context: Context, sceneId: String) {
        reload(context)
        update(context, mutableAppearance.value.copy(colorMode = ColorMode.Scenes,
            sceneId = sceneId, sceneColorArgb = null))
    }

    /** In scene mode a manual seed keeps the artwork; reset restores its own palette. */
    fun setMainColor(context: Context, argb: Int?) {
        reload(context)
        val current = mutableAppearance.value
        update(context, if (current.colorMode == ColorMode.Scenes) current.copy(sceneColorArgb = argb?.or(0xFF000000.toInt()))
            else current.copy(colorMode = ColorMode.Default, customColorArgb = argb?.or(0xFF000000.toInt())))
    }

    fun setShowSceneInConversations(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(showSceneInConversations = enabled))
    }

    fun setDarkenScenesInDarkMode(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(darkenScenesInDarkMode = enabled))
    }

    private fun update(context: Context, appearance: AppAppearance) {
        mutableAppearance.value = appearance
        persist(context, appearance)
        AppShortcuts.refresh(context)
    }

    internal fun reload(context: Context) {
        val prefs = AccountPreferences.preferences(context, PREFS_NAME)
        mutableAppearance.value = AppAppearance(
            themeMode = enumValueOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.System),
            colorMode = enumValueOrDefault(prefs.getString(KEY_COLOR, null), ColorMode.Dynamic),
            sceneId = messengerScene(prefs.getString(KEY_SCENE, null) ?: "daisyhill").id,
            customColorArgb = if (prefs.contains(KEY_CUSTOM_COLOR)) prefs.getInt(KEY_CUSTOM_COLOR, 0) else null,
            sceneColorArgb = if (prefs.contains(KEY_SCENE_COLOR)) prefs.getInt(KEY_SCENE_COLOR, 0) else null,
            showSceneInConversations = prefs.getBoolean(KEY_SCENE_IN_CONVERSATIONS, true),
            darkenScenesInDarkMode = prefs.getBoolean(KEY_DARKEN_SCENES, true),
        )
    }

    private fun persist(context: Context, appearance: AppAppearance) {
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putString(KEY_THEME, appearance.themeMode.name)
            putString(KEY_COLOR, appearance.colorMode.name)
            putString(KEY_SCENE, appearance.sceneId)
            putBoolean(KEY_SCENE_IN_CONVERSATIONS, appearance.showSceneInConversations)
            putBoolean(KEY_DARKEN_SCENES, appearance.darkenScenesInDarkMode)
            appearance.customColorArgb?.let { putInt(KEY_CUSTOM_COLOR, it) } ?: remove(KEY_CUSTOM_COLOR)
            appearance.sceneColorArgb?.let { putInt(KEY_SCENE_COLOR, it) } ?: remove(KEY_SCENE_COLOR)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
