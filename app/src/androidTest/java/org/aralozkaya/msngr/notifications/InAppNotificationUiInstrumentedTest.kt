package org.aralozkaya.msngr.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.aralozkaya.msngr.pages.ConversationNotificationSettingsScreen
import org.aralozkaya.msngr.pages.CustomizedNotificationsScreen
import org.aralozkaya.msngr.pages.InAppNotificationSettingsScreen
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class InAppNotificationUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun isolatedContext(): Context = object : ContextWrapper(compose.activity) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("in_app_ui_test_$name", mode)
    }

    @Test fun killSwitchDisablesOtherControlsAndPreservesSelections() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MsngrTheme { InAppNotificationSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior(), {}) }
                }
            }
            compose.onNodeWithTag("in_app_enabled").assertIsOn()
            compose.onNodeWithTag("in_app_vibration").assertIsOn()
            compose.onNodeWithTag("in_app_vibration").performClick().assertIsOff()
            compose.onNodeWithTag("in_app_enabled").performClick().assertIsOff()
            compose.onNodeWithTag("in_app_vibration").assertIsNotEnabled()
            compose.onNodeWithTag("in_app_banners").assertIsNotEnabled()
            compose.onNodeWithTag("in_app_badges").assertDoesNotExist()
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("in_app_type_Message"))
            compose.onNodeWithTag("in_app_type_Message").assertIsNotEnabled()
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("in_app_enabled"))
            compose.onNodeWithTag("in_app_enabled").performClick().assertIsOn()
            compose.onNodeWithTag("in_app_vibration").assertIsOff().assertIsEnabled()
            screenshot("in-app-settings")
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun conversationCustomizationIsListedEditableAndRemovable() {
        val context = isolatedContext()
        val page = mutableIntStateOf(0)
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MsngrTheme {
                        if (page.intValue == 0) ConversationNotificationSettingsScreen("peer", "Sample contact", false,
                            PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior())
                        else CustomizedNotificationsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior())
                    }
                }
            }
            compose.onNodeWithTag("use_custom_notifications").assertIsOff()
            compose.onNodeWithTag("conversation_notifications_enabled").assertIsNotEnabled()
            compose.onNodeWithTag("use_custom_notifications").performClick().assertIsOn()
            compose.onNodeWithTag("in_app_vibration").performClick().assertIsOff()
            compose.runOnIdle { page.intValue = 1 }
            compose.onNodeWithTag("notification_custom_peer").assertExists().assertHasClickAction()
            screenshot("customized-notifications")
            compose.runOnIdle { page.intValue = 0 }
            compose.onNodeWithTag("in_app_vibration").assertIsOff()
            // Global switches do not disable an enabled custom profile's controls.
            compose.runOnIdle {
                InAppNotificationPrefs.update(context) { it.copy(defaults = InAppNotificationOptions(
                    enabled = false, vibrate = false, banners = false,
                    rules = mapOf(InAppNotificationType.Message to InAppNotificationRule(enabled = false, vibrate = false)))) }
                InAppNotificationPrefs.customizeBackground(context, "peer", "Sample contact", false) {
                    it.copy(backgroundEnabled = false, backgroundSilent = true)
                }
            }
            compose.onNodeWithTag("conversation_notifications_enabled").assertIsOn().assertIsEnabled()
            compose.onNodeWithTag("in_app_vibration").assertIsEnabled()
            compose.onNodeWithTag("in_app_banners").assertIsOn().assertIsEnabled()
            compose.onNodeWithTag("use_custom_notifications").performClick().assertIsOff()
            compose.runOnIdle {
                InAppNotificationPrefs.reload(context)
                assertTrue(InAppNotificationPrefs.read(context).conversations.isEmpty())
            }
            compose.onNodeWithTag("use_custom_notifications").performClick().assertIsOn()
            compose.runOnIdle {
                val saved = InAppNotificationPrefs.read(context)
                assertEquals(saved.defaults, saved.conversations["peer"]!!.options)
                assertTrue(saved.conversations["peer"]!!.backgroundEnabled)
                assertFalse(saved.conversations["peer"]!!.backgroundSilent)
            }
            compose.onNodeWithTag("conversation_notifications_enabled").assertIsOff().assertIsEnabled().performClick().assertIsOn()
            compose.onNodeWithTag("in_app_banners").assertIsOff().assertIsEnabled()
            compose.onNodeWithTag("in_app_vibration").assertIsOff().assertIsEnabled().performClick().assertIsOn()
            compose.runOnIdle {
                assertFalse(InAppNotificationPrefs.read(context).defaults.enabled)
                assertTrue(InAppNotificationPrefs.read(context).allows(InAppNotificationType.Nudge, "peer"))
            }
            compose.runOnIdle { page.intValue = 1 }
            compose.onNodeWithTag("delete_notification_custom_peer").performClick()
            compose.onNodeWithTag("no_notification_customizations").assertExists()
            screenshot("customized-notifications-empty")
            assertTrue(InAppNotificationPrefs.read(context).conversations.isEmpty())
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun bannerSupportsOpeningAndDismissal() {
        var opened = false
        var dismissed = false
        compose.setContent {
            MsngrTheme {
                InAppNotificationBanner(InAppNotification(InAppNotificationType.Message, "Sample contact", "Hello from another chat", "peer"),
                    onDismiss = { dismissed = true }, onOpen = { opened = true })
            }
        }
        compose.onNodeWithTag("in_app_notification_banner").performClick()
        assertTrue(opened)
        compose.onNodeWithTag("dismiss_in_app_notification").performClick()
        assertTrue(dismissed)
        screenshot("in-app-banner")
    }

    private fun screenshot(name: String) {
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
