package org.aralozkaya.msngr

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String, @param:StringRes val labelRes: Int) {
    System("System", R.string.theme_mode_system),
    Light("Light", R.string.theme_mode_light),
    Dark("Dark", R.string.theme_mode_dark);

    @Composable
    fun displayLabel(): String = stringResource(labelRes)
}

enum class ColorMode(val label: String, @param:StringRes val labelRes: Int) {
    Default("Default", R.string.color_mode_default),
    Dynamic("Dynamic", R.string.color_mode_dynamic),
    Scenes("Scenes", R.string.color_mode_scenes);

    @Composable
    fun displayLabel(): String = stringResource(labelRes)
}

enum class UiDensity(val label: String, @param:StringRes val labelRes: Int) {
    Minimal("Minimal", R.string.ui_density_minimal),
    Small("Small", R.string.ui_density_small),
    Medium("Medium", R.string.ui_density_medium),
    Regular("Regular", R.string.ui_density_regular);

    val isCompact: Boolean
        get() = this != Regular

    @Composable
    fun displayLabel(): String = stringResource(labelRes)
}

data class LayoutSettings(
    val density: UiDensity = UiDensity.Regular,
    val compactInlineStatusMessage: Boolean = false,
    val compactHidePresenceText: Boolean = false,
) {
    val isCompact: Boolean
        get() = density.isCompact
}

val LocalLayoutSettings = staticCompositionLocalOf { LayoutSettings() }
val LocalFavoritesLayoutSettings = staticCompositionLocalOf { LayoutSettings() }

data class AppAppearance(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorMode: ColorMode = ColorMode.Dynamic,
    val uiDensity: UiDensity = UiDensity.Regular,
    val compactInlineStatusMessage: Boolean = false,
    val compactHidePresenceText: Boolean = false,
    val useSeparateFavoritesLayout: Boolean = false,
    val favoritesUiDensity: UiDensity = UiDensity.Regular,
    val favoritesCompactInlineStatusMessage: Boolean = false,
    val favoritesCompactHidePresenceText: Boolean = false,
    val sceneId: String = "daisyhill",
    val customColorArgb: Int? = null,
    val sceneColorArgb: Int? = null,
    val showSceneInConversations: Boolean = true,
    val darkenScenesInDarkMode: Boolean = true,
    val showAds: Boolean = true,
) {
    val layoutSettings: LayoutSettings
        get() = LayoutSettings(
            density = uiDensity,
            compactInlineStatusMessage = compactInlineStatusMessage,
            compactHidePresenceText = compactHidePresenceText,
        )

    val favoritesLayoutSettings: LayoutSettings
        get() = if (useSeparateFavoritesLayout) {
            LayoutSettings(
                density = favoritesUiDensity,
                compactInlineStatusMessage = favoritesCompactInlineStatusMessage,
                compactHidePresenceText = favoritesCompactHidePresenceText,
            )
        } else {
            layoutSettings
        }
}

object AppearancePrefs {
    private const val PREFS_NAME = "msngr_appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_COLOR = "color"
    private const val KEY_UI_DENSITY = "ui_density"
    private const val KEY_COMPACT_INLINE_STATUS = "compact_inline_status"
    private const val KEY_COMPACT_HIDE_PRESENCE_TEXT = "compact_hide_presence_text"
    private const val KEY_FAVORITES_SEPARATE_LAYOUT = "favorites_separate_layout"
    private const val KEY_FAVORITES_UI_DENSITY = "favorites_ui_density"
    private const val KEY_FAVORITES_COMPACT_INLINE_STATUS = "favorites_compact_inline_status"
    private const val KEY_FAVORITES_COMPACT_HIDE_PRESENCE_TEXT = "favorites_compact_hide_presence_text"
    private const val KEY_SCENE = "scene"
    private const val KEY_CUSTOM_COLOR = "custom_color"
    private const val KEY_SCENE_COLOR = "scene_color"
    private const val KEY_SCENE_IN_CONVERSATIONS = "scene_in_conversations"
    private const val KEY_DARKEN_SCENES = "darken_scenes"
    private const val KEY_SHOW_ADS = "show_ads"
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

    fun setShowAds(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(showAds = enabled))
    }

    fun setUiDensity(context: Context, density: UiDensity) {
        reload(context)
        update(context, mutableAppearance.value.copy(uiDensity = density))
    }

    fun setCompactInlineStatusMessage(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(compactInlineStatusMessage = enabled))
    }

    fun setCompactHidePresenceText(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(compactHidePresenceText = enabled))
    }

    fun setUseSeparateFavoritesLayout(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(useSeparateFavoritesLayout = enabled))
    }

    fun setFavoritesUiDensity(context: Context, density: UiDensity) {
        reload(context)
        update(context, mutableAppearance.value.copy(favoritesUiDensity = density))
    }

    fun setFavoritesCompactInlineStatusMessage(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(favoritesCompactInlineStatusMessage = enabled))
    }

    fun setFavoritesCompactHidePresenceText(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableAppearance.value.copy(favoritesCompactHidePresenceText = enabled))
    }

    private fun update(context: Context, appearance: AppAppearance) {
        mutableAppearance.value = appearance
        persist(context, appearance)
        AppShortcuts.refresh(context)
    }

    internal fun reload(context: Context) {
        val prefs = AccountPreferences.preferences(context, PREFS_NAME)
        val rawDensity = prefs.getString(KEY_UI_DENSITY, null)
        val uiDensity = when (rawDensity) {
            "Compact" -> UiDensity.Small
            else -> enumValueOrDefault(rawDensity, UiDensity.Regular)
        }
        mutableAppearance.value = AppAppearance(
            themeMode = enumValueOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.System),
            colorMode = enumValueOrDefault(prefs.getString(KEY_COLOR, null), ColorMode.Dynamic),
            uiDensity = uiDensity,
            compactInlineStatusMessage = prefs.getBoolean(KEY_COMPACT_INLINE_STATUS, false),
            compactHidePresenceText = prefs.getBoolean(KEY_COMPACT_HIDE_PRESENCE_TEXT, false),
            useSeparateFavoritesLayout = prefs.getBoolean(KEY_FAVORITES_SEPARATE_LAYOUT, false),
            favoritesUiDensity = enumValueOrDefault(prefs.getString(KEY_FAVORITES_UI_DENSITY, null), UiDensity.Regular),
            favoritesCompactInlineStatusMessage = prefs.getBoolean(KEY_FAVORITES_COMPACT_INLINE_STATUS, false),
            favoritesCompactHidePresenceText = prefs.getBoolean(KEY_FAVORITES_COMPACT_HIDE_PRESENCE_TEXT, false),
            sceneId = messengerScene(prefs.getString(KEY_SCENE, null) ?: "daisyhill").id,
            customColorArgb = if (prefs.contains(KEY_CUSTOM_COLOR)) prefs.getInt(KEY_CUSTOM_COLOR, 0) else null,
            sceneColorArgb = if (prefs.contains(KEY_SCENE_COLOR)) prefs.getInt(KEY_SCENE_COLOR, 0) else null,
            showSceneInConversations = prefs.getBoolean(KEY_SCENE_IN_CONVERSATIONS, true),
            darkenScenesInDarkMode = prefs.getBoolean(KEY_DARKEN_SCENES, true),
            showAds = prefs.getBoolean(KEY_SHOW_ADS, true),
        )
    }

    private fun persist(context: Context, appearance: AppAppearance) {
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putString(KEY_THEME, appearance.themeMode.name)
            putString(KEY_COLOR, appearance.colorMode.name)
            putString(KEY_UI_DENSITY, appearance.uiDensity.name)
            putBoolean(KEY_COMPACT_INLINE_STATUS, appearance.compactInlineStatusMessage)
            putBoolean(KEY_COMPACT_HIDE_PRESENCE_TEXT, appearance.compactHidePresenceText)
            putBoolean(KEY_FAVORITES_SEPARATE_LAYOUT, appearance.useSeparateFavoritesLayout)
            putString(KEY_FAVORITES_UI_DENSITY, appearance.favoritesUiDensity.name)
            putBoolean(KEY_FAVORITES_COMPACT_INLINE_STATUS, appearance.favoritesCompactInlineStatusMessage)
            putBoolean(KEY_FAVORITES_COMPACT_HIDE_PRESENCE_TEXT, appearance.favoritesCompactHidePresenceText)
            putString(KEY_SCENE, appearance.sceneId)
            putBoolean(KEY_SCENE_IN_CONVERSATIONS, appearance.showSceneInConversations)
            putBoolean(KEY_DARKEN_SCENES, appearance.darkenScenesInDarkMode)
            putBoolean(KEY_SHOW_ADS, appearance.showAds)
            appearance.customColorArgb?.let { putInt(KEY_CUSTOM_COLOR, it) } ?: remove(KEY_CUSTOM_COLOR)
            appearance.sceneColorArgb?.let { putInt(KEY_SCENE_COLOR, it) } ?: remove(KEY_SCENE_COLOR)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
