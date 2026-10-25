@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.AppearanceSettingsScreen
import org.aralozkaya.msngr.pages.components.LoggedInUserProfileCard
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SceneAppearanceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun isolatedContext() = object : ContextWrapper(compose.activity) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("scene_test_$name", mode)
    }.also {
        AccountPreferences.preferences(it, "msngr_appearance").edit().clear().commit()
        AppearancePrefs.reload(it)
    }

    @Test fun scenePickerFillsSystemBarAreasAndInsetsItsControls() {
        val context = isolatedContext()
        AppearancePrefs.setThemeMode(context, ThemeMode.Light)
        val showPicker = mutableStateOf(true)
        try {
            compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MsngrTheme {
                        if (showPicker.value) ScenePickerDialog("daisyhill", {}, { showPicker.value = false })
                    }
                }
            }
            compose.waitForIdle()
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val picker = compose.onNodeWithTag("scene_picker_surface", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            assertEquals("The picker fills the display width", screenshot.width.toFloat(), picker.width, 1f)
            assertEquals("The picker extends through both system bars", screenshot.height.toFloat(), picker.height, 1f)
            val bars = checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            assertTrue(compose.onNodeWithContentDescription("Close scenes").fetchSemanticsNode().boundsInWindow.top >= bars.top)
            assertTrue(compose.onNodeWithTag("scene_gallery").fetchSemanticsNode().boundsInWindow.bottom <= picker.bottom - bars.bottom + 1f)
            screenshot.save("scene-picker-fullscreen-light.png")
            screenshot.recycle()
            compose.runOnIdle { AppearancePrefs.setThemeMode(context, ThemeMode.Dark) }
            compose.waitForIdle()
            compose.onNodeWithTag("scene_picker_surface", useUnmergedTree = true).captureToImage()
            Thread.sleep(500) // System bar icon updates are rendered outside Compose's test clock.
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).also {
                it.save("scene-picker-fullscreen-dark.png")
                it.recycle()
            }
            val galleryHeight = compose.onNodeWithTag("scene_gallery").fetchSemanticsNode().boundsInWindow.height
            compose.onNodeWithText("Search scenes").performClick().performTextInput("Dahlias")
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("scene_gallery").fetchSemanticsNode().boundsInWindow.height < galleryHeight
            }
            compose.onNodeWithTag("scene_wlm_2011_dahlias").assertIsDisplayed()
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).also {
                it.save("scene-picker-search-keyboard.png")
                it.recycle()
            }
            compose.onNodeWithContentDescription("Close scenes").performClick()
            compose.onNodeWithTag("scene_picker_surface", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            AppearancePrefs.reload(compose.activity)
        }
    }

    @Test fun scenesCustomPaletteAndResetSurviveReopeningAppearance() {
        val context = isolatedContext()
        AppearancePrefs.setColorMode(context, ColorMode.Default)
        AppearancePrefs.setMainColor(context, null)
        val reopening = mutableIntStateOf(0)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MsngrTheme {
                        androidx.compose.runtime.key(reopening.intValue) {
                            AppearanceSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior())
                        }
                    }
                }
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Scenes", substring = false))
            compose.onNodeWithText("Scenes").performClick()
            compose.runOnIdle { assertEquals(ColorMode.Scenes, AppearancePrefs.state(context).value.colorMode) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("choose_scene"))
            compose.onNodeWithTag("choose_scene").performClick()
            compose.onNodeWithText("Search scenes").performTextInput("Dahlias")
            compose.onNodeWithTag("scene_wlm_2011_dahlias").assertExists()
            compose.onNodeWithTag("scene_gallery").captureToImage().asAndroidBitmap().save("scene-gallery.png")
            compose.onNodeWithTag("scene_wlm_2011_dahlias").performClick()
            compose.runOnIdle { assertEquals("wlm_2011_dahlias", AppearancePrefs.state(context).value.sceneId) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("custom_main_color"))
            compose.onNodeWithTag("custom_main_color").performClick()
            compose.onNodeWithTag("main_color_wheel").performTouchInput { click(Offset(width * 0.8f, height * 0.5f)) }
            compose.onNodeWithTag("main_color_hex").performTextReplacement("#A02790")
            compose.onNodeWithTag("main_color_dialog").captureToImage().asAndroidBitmap().save("scene-color-wheel.png")
            compose.onNodeWithText("Apply").performClick()
            compose.runOnIdle {
                assertEquals(0xFFA02790.toInt(), AppearancePrefs.state(context).value.sceneColorArgb)
                AppearancePrefs.reload(context)
                reopening.intValue++
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Use scene colors"))
            compose.onNodeWithText("Use scene colors").performClick()
            compose.runOnIdle {
                assertNull(AppearancePrefs.state(context).value.sceneColorArgb)
                assertEquals(ColorMode.Scenes, AppearancePrefs.state(context).value.colorMode)
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("custom_main_color"))
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-appearance.png")
        } finally {
            AppearancePrefs.reload(compose.activity)
        }
    }

    @Test fun imageBackedCardAndChatRenderInBothBrightnessModes() {
        val context = isolatedContext()
        AppearancePrefs.setScene(context, "daisyhill")
        AppearancePrefs.setThemeMode(context, ThemeMode.Light)
        AppearancePrefs.setDarkenScenesInDarkMode(context, true)
        val screen = mutableIntStateOf(0)
        var primary: Color = Color.Unspecified
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MsngrTheme {
                        primary = MaterialTheme.colorScheme.primary
                        if (screen.intValue == 0) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 64.dp)) {
                                LoggedInUserProfileCard(UserProfileSnapshot("[b]Aral[/b]", "aral@example.com", Presence.Online,
                                    statusMessage = "Enjoying the view :)"), {}, {})
                            }
                        } else {
                            ChatScreen("", {}, Contact("friend", "Friend", "friend@example.com", Presence.Online),
                                messages = listOf(ChatMessage(1, "friend", MessageAuthor.Contact, "Hello from Messenger!", 0)),
                                isHistoryLoaded = true, canLoadOlderMessages = false, isTyping = false,
                                onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                                onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Wait for asynchronous artwork decoding before capturing either header.
            Thread.sleep(500)
            compose.onNodeWithTag("scene_header_artwork", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("scene_dark_tint", useUnmergedTree = true).assertDoesNotExist()
            val lightScene = compose.onNodeWithTag("scene_backdrop", useUnmergedTree = true).captureToImage().toPixelMap()
            val untintedLuminance = lightScene[(lightScene.width * 0.85f).toInt(), (lightScene.height * 0.15f).toInt()].luminance()
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-profile-light.png")
            compose.runOnIdle { screen.intValue = 1 }
            compose.onNodeWithText("Friend").assertExists()
            compose.waitForIdle()
            Thread.sleep(500)
            compose.onNodeWithTag("scene_dark_tint", useUnmergedTree = true).assertDoesNotExist()
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-chat-light.png")
            compose.runOnIdle { AppearancePrefs.setThemeMode(context, ThemeMode.Dark) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse(androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,
                    compose.activity.window.decorView).isAppearanceLightStatusBars)
            }
            compose.onNodeWithTag("scene_dark_tint", useUnmergedTree = true).assertExists()
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-chat-dark-tint.png")
            val scenePrimary = primary
            compose.runOnIdle { AppearancePrefs.setShowSceneInConversations(context, false) }
            compose.onNodeWithTag("scene_header_artwork", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("scene_dark_tint", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Friend").assertExists()
            compose.runOnIdle { assertEquals(scenePrimary, primary) }
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-chat-no-image.png")
            compose.runOnIdle { screen.intValue = 0 }
            compose.waitForIdle()
            Thread.sleep(500)
            compose.onNodeWithTag("scene_header_artwork", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("scene_dark_tint", useUnmergedTree = true).assertExists()
            val tintedScene = compose.onNodeWithTag("scene_backdrop", useUnmergedTree = true).captureToImage().toPixelMap()
            val tintedLuminance = tintedScene[(tintedScene.width * 0.85f).toInt(), (tintedScene.height * 0.15f).toInt()].luminance()
            assertTrue("The actual artwork is darkened", tintedLuminance < untintedLuminance * 0.5f)
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-profile-dark-tint.png")
            compose.runOnIdle { AppearancePrefs.setDarkenScenesInDarkMode(context, false) }
            compose.onNodeWithTag("scene_dark_tint", useUnmergedTree = true).assertDoesNotExist()
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-profile-dark.png")
        } finally {
            AppearancePrefs.reload(compose.activity)
        }
    }

    @Test fun sceneOptionsPersistAndExpandUnderTheirSelectedColorMode() {
        val context = isolatedContext()
        AppearancePrefs.setThemeMode(context, ThemeMode.Light)
        AppearancePrefs.setColorMode(context, ColorMode.Dynamic)
        val reopening = mutableIntStateOf(0)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MsngrTheme {
                        androidx.compose.runtime.key(reopening.intValue) {
                            AppearanceSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior())
                        }
                    }
                }
            }
            val list = compose.onNode(hasScrollAction())
            list.performScrollToNode(hasTestTag("color_mode_Scenes"))
            compose.onNodeWithTag("color_controls_Scenes").assertDoesNotExist()
            compose.onNodeWithTag("color_mode_Scenes").performClick()
            list.performScrollToNode(hasTestTag("scene_in_conversations"))
            compose.onNodeWithTag("scene_in_conversations").assertIsOn().performClick().assertIsOff()
            list.performScrollToNode(hasTestTag("darken_scenes"))
            compose.onNodeWithTag("darken_scenes").assertIsOn().performClick().assertIsOff()
            compose.runOnIdle {
                AppearancePrefs.reload(context)
                assertFalse(AppearancePrefs.state(context).value.showSceneInConversations)
                assertFalse(AppearancePrefs.state(context).value.darkenScenesInDarkMode)
                reopening.intValue++
            }
            list.performScrollToNode(hasTestTag("scene_in_conversations"))
            compose.onNodeWithTag("scene_in_conversations").assertIsOff()
            compose.onNodeWithTag("darken_scenes").assertIsOff()
            compose.onNodeWithTag("darken_scenes").performScrollTo().assertIsDisplayed()
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-options-light-off.png")
            compose.onNodeWithTag("scene_in_conversations").performClick().assertIsOn()
            compose.onNodeWithTag("darken_scenes").performClick().assertIsOn()
            compose.mainClock.advanceTimeBy(500)
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-options-light-on.png")
            compose.runOnIdle { AppearancePrefs.setThemeMode(context, ThemeMode.Dark) }
            compose.onRoot().captureToImage().asAndroidBitmap().save("scene-options-dark-on.png")
            list.performScrollToNode(hasTestTag("color_mode_Dynamic"))
            compose.onNodeWithTag("color_mode_Dynamic").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("color_controls_Scenes").assertDoesNotExist()
            compose.onNodeWithTag("scene_in_conversations").assertDoesNotExist()
            list.performScrollToNode(hasTestTag("color_mode_Default"))
            compose.onNodeWithTag("color_mode_Default").performClick()
            list.performScrollToNode(hasTestTag("custom_main_color"))
            compose.onNodeWithTag("color_controls_Default").assertExists()
            compose.onNodeWithTag("darken_scenes").assertDoesNotExist()
        } finally {
            AppearancePrefs.reload(compose.activity)
        }
    }

    @Test fun removedConversationBackgroundSelectionsFallBackToAnActualScene() {
        val context = isolatedContext()
        try {
            AccountPreferences.preferences(context, "msngr_appearance").edit()
                .putString("color", "Scenes").putString("scene", "car").commit()
            val appearance = AppearancePrefs.state(context).value
            assertEquals("daisyhill", appearance.sceneId)
            assertEquals(ColorMode.Scenes, appearance.colorMode)
            assertTrue(appearance.showSceneInConversations)
            assertTrue(appearance.darkenScenesInDarkMode)
            assertTrue(AppAppearance().darkenScenesInDarkMode)
        } finally {
            AppearancePrefs.reload(compose.activity)
        }
    }

    @Test fun gifAndApngArtworkActuallyAdvanceFrames() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val decoded = decodeSceneFrames(testContext.assets.open("scenes/animation.png").use { it.readBytes() })
        assertEquals(2, decoded.bitmaps.size)
        assertNotEquals(decoded.bitmaps[0].getPixel(0, 0), decoded.bitmaps[1].getPixel(0, 0))
        val context = object : ContextWrapper(compose.activity) {
            override fun getAssets(): AssetManager = testContext.assets
        }
        val format = mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                SceneArtwork(MessengerScene("animation-${format.intValue}", "Test", "Test", if (format.intValue == 0) "animation.gif" else "animation.png",
                    0xFF0000FF.toInt(), true), Modifier.size(80.dp).testTag("animation"))
            }
        }
        fun verifyFrames() {
            val samples = mutableSetOf<androidx.compose.ui.graphics.Color>()
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeBy(57)
                samples += compose.onNodeWithTag("animation").captureToImage().toPixelMap()[20, 20]
                samples.size >= 2
            }
        }
        verifyFrames()
        compose.runOnIdle { format.intValue = 1 }
        verifyFrames()
    }

    @Test fun customColorValidationAndActionsWorkAtDoubleTextSize() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { MainColorDialog(0xFF74B4EF.toInt(), {}, {}) }
            }
        }
        compose.onNodeWithText("Apply").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("main_color_hex").performScrollTo().performTextReplacement("#GGGGGG")
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithTag("main_color_hex").performTextReplacement("#123456")
        compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun Bitmap.save(name: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        output.mkdirs()
        File(output, name).outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
