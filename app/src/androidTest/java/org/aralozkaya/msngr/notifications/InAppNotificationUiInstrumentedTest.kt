package org.aralozkaya.msngr.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.SettingsRoute
import org.aralozkaya.msngr.pages.ConversationNotificationSettingsScreen
import org.aralozkaya.msngr.pages.CustomizedNotificationsScreen
import org.aralozkaya.msngr.pages.InAppNotificationSettingsScreen
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.test.ExperimentalTestApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
class InAppNotificationUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val haptic = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }

    private fun isolatedContext(): Context = object : ContextWrapper(compose.activity) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("in_app_ui_test_$name", mode)
    }

    @Test fun killSwitchDisablesOtherControlsAndPreservesSelections() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
                    MsngrTheme { InAppNotificationSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) }
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
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
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
            compose.onNodeWithTag("delete_notification_custom_peer").assertDoesNotExist()
            compose.onNodeWithTag("notification_custom_peer").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("no_notification_customizations").assertExists()
            screenshot("customized-notifications-empty")
            assertTrue(InAppNotificationPrefs.read(context).conversations.isEmpty())
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun customizationsOpenFromAppBarMenuAndBackReturnsToGeneralSettings() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
                    MsngrTheme { SettingsRoute(onBack = {}, onDeleteAllChatHistories = {}) }
                }
            }
            compose.onNodeWithTag("in_app_notification_options").assertDoesNotExist()
            compose.onNodeWithTag("in_app_notification_settings").performScrollTo().performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(
                hasTestTag("in_app_type_vibration_${InAppNotificationType.entries.last().name}"))
            compose.onNodeWithTag("customized_notification_settings").assertDoesNotExist()
            compose.onNodeWithTag("in_app_notification_options").performClick()
            screenshot("notification-options-menu", popup = true)
            compose.onNodeWithTag("customized_notification_settings").performClick()
            compose.onNodeWithTag("no_notification_customizations").assertExists()
            compose.onNodeWithTag("in_app_notification_options").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("in_app_enabled").assertExists()
            compose.onNodeWithTag("customized_notification_settings").assertDoesNotExist()
            compose.onNodeWithTag("in_app_notification_options").performClick()
            compose.onNodeWithTag("customized_notification_settings").assertExists()
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun swipeOnlyResetsChosenCustomizationAndShortDragCancels() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        InAppNotificationPrefs.customize(context, "peer", "Sample contact", false) { it.copy(vibrate = false) }
        InAppNotificationPrefs.customize(context, "group", "Sample group", true) { it.copy(banners = false) }
        val originalDefaults = InAppNotificationPrefs.read(context).defaults
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
                    MsngrTheme { CustomizedNotificationsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) }
                }
            }
            val row = compose.onNodeWithTag("notification_custom_peer")
            val shortDistance = with(compose.density) { 24.dp.toPx() }
            row.performTouchInput { swipe(center, center - Offset(shortDistance, 0f), durationMillis = 1000) }
            compose.runOnIdle { assertEquals(setOf("peer", "group"), InAppNotificationPrefs.read(context).conversations.keys) }
            row.performTouchInput {
                down(Offset(width * .9f, centerY))
                moveTo(Offset(width * .25f, centerY), delayMillis = 300)
            }
            compose.runOnIdle { assertEquals(2, InAppNotificationPrefs.read(context).conversations.size) }
            compose.onNodeWithTag("reset_notification_custom_icon_peer", useUnmergedTree = true).assertExists()
            val action = compose.onNodeWithTag("reset_notification_custom_peer").fetchSemanticsNode().boundsInRoot
            val narrowWidth = with(compose.density) {
                IconButtonDefaults.mediumContainerSize().width.toPx()
            }
            assertTrue("The reset button must grow beyond its narrow revealed size", action.width > narrowWidth)
            val wrapperBounds = compose.onNodeWithTag("swipe_notification_custom_peer").fetchSemanticsNode().boundsInRoot
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val gap = with(compose.density) { 4.dp.toPx() }
            assertEquals(wrapperBounds.right - rowBounds.right - gap, action.width, 1f)
            assertEquals(rowBounds.height, action.height, 1f)
            compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics) }
            screenshot("customized-notification-swipe")
            row.performTouchInput { up() }
            row.assertDoesNotExist()
            val remaining = compose.onNodeWithTag("notification_custom_group").assertExists()
            remaining.performTouchInput { swipeRight() }
            compose.runOnIdle {
                val settings = InAppNotificationPrefs.read(context)
                assertEquals(setOf("group"), settings.conversations.keys)
                assertFalse(settings.conversations.getValue("group").options.banners)
                assertEquals(originalDefaults, settings.defaults)
            }
            remaining.performCustomAccessibilityActionWithLabel("Reset notifications for Sample group")
            compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics) }
            compose.onNodeWithTag("no_notification_customizations").assertExists()
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun partialSwipeSticksAtNarrowButtonAndSupportsTapResetOrClose() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        InAppNotificationPrefs.customize(context, "peer", "Sample contact", false) { it.copy(vibrate = false) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
                    MsngrTheme { CustomizedNotificationsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) }
                }
            }
            val wrapper = compose.onNodeWithTag("swipe_notification_custom_peer")
            val action = compose.onNodeWithTag("reset_notification_custom_peer")
            val revealDistance = with(compose.density) { 72.dp.toPx() }
            fun reveal() {
                wrapper.performTouchInput { swipe(center, center - Offset(revealDistance, 0f), durationMillis = 1000) }
            }
            action.assertDoesNotExist()
            reveal()
            action.assertIsDisplayed().assertIsEnabled()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val size = IconButtonDefaults.mediumContainerSize()
            assertEquals(with(compose.density) { size.width.toPx() }, bounds.width, 1f)
            assertEquals(wrapper.fetchSemanticsNode().boundsInRoot.height, bounds.height, 1f)
            compose.runOnIdle { assertTrue(haptics.isEmpty()) }
            screenshot("customized-notification-narrow-action")
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            compose.onNodeWithTag("notification_custom_peer").performClick()
            action.assertDoesNotExist()
            reveal()
            wrapper.performTouchInput { swipeRight() }
            action.assertDoesNotExist()
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            wrapper.performTouchInput { swipe(center, center - Offset(revealDistance, 0f), durationMillis = 80) }
            action.assertIsDisplayed()
            assertEquals(with(compose.density) { size.width.toPx() }, action.fetchSemanticsNode().boundsInRoot.width, 1f)
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            action.performClick()
            compose.onNodeWithTag("no_notification_customizations").assertExists()
            compose.runOnIdle { assertTrue(haptics.isEmpty()) }
            assertTrue(InAppNotificationPrefs.read(context).conversations.isEmpty())
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun releasingBelowThresholdOrRetreatingKeepsTheRevealedState() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        InAppNotificationPrefs.customize(context, "peer", "Sample contact", false) { it.copy(vibrate = false) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
                    MsngrTheme { CustomizedNotificationsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) }
                }
            }
            val wrapper = compose.onNodeWithTag("swipe_notification_custom_peer")
            val action = compose.onNodeWithTag("reset_notification_custom_peer")
            val narrowWidth = with(compose.density) {
                IconButtonDefaults.mediumContainerSize().width.toPx()
            }
            wrapper.performTouchInput {
                down(Offset(width * .9f, centerY))
                moveTo(Offset(width * .6f, centerY), delayMillis = 300)
            }
            assertTrue(action.fetchSemanticsNode().boundsInRoot.width > narrowWidth)
            wrapper.performTouchInput {
                moveTo(Offset(width * .6f, centerY), delayMillis = 1000)
                up()
            }
            assertEquals(narrowWidth, action.fetchSemanticsNode().boundsInRoot.width, 1f)
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            wrapper.performTouchInput {
                down(Offset(width * .9f, centerY))
                moveTo(Offset(width * .2f, centerY), delayMillis = 300)
            }
            assertTrue(action.fetchSemanticsNode().boundsInRoot.width > narrowWidth * 2)
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            wrapper.performTouchInput {
                moveTo(Offset(width * .85f, centerY), delayMillis = 1000)
                moveTo(Offset(width * .85f, centerY), delayMillis = 500)
                up()
            }
            assertEquals(narrowWidth, action.fetchSemanticsNode().boundsInRoot.width, 1f)
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            wrapper.performTouchInput { swipeLeft() }
            compose.onNodeWithTag("no_notification_customizations").assertExists()
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun revealedStateSurvivesSavedStateRestorationWithoutResettingCustomization() {
        val context = isolatedContext()
        val restoration = StateRestorationTester(compose)
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        InAppNotificationPrefs.customize(context, "peer", "Sample contact", false) { it.copy(vibrate = false) }
        try {
            restoration.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalHapticFeedback provides haptic) {
                    MsngrTheme { CustomizedNotificationsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) }
                }
            }
            val revealDistance = with(compose.density) { 72.dp.toPx() }
            compose.onNodeWithTag("swipe_notification_custom_peer").performTouchInput {
                swipe(center, center - Offset(revealDistance, 0f), durationMillis = 1000)
            }
            compose.onNodeWithTag("reset_notification_custom_peer").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            val action = compose.onNodeWithTag("reset_notification_custom_peer").assertIsDisplayed().assertIsEnabled()
            val size = IconButtonDefaults.mediumContainerSize()
            assertEquals(with(compose.density) { size.width.toPx() }, action.fetchSemanticsNode().boundsInRoot.width, 1f)
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            action.performClick()
            compose.onNodeWithTag("no_notification_customizations").assertExists()
            compose.runOnIdle { assertTrue(haptics.isEmpty()) }
        } finally { InAppNotificationPrefs.update(context) { InAppNotificationSettings() } }
    }

    @Test fun rtlSwipesResetTowardTheLogicalStart() {
        val context = isolatedContext()
        InAppNotificationPrefs.update(context) { InAppNotificationSettings() }
        InAppNotificationPrefs.customize(context, "peer", "Sample contact", false) { it.copy(vibrate = false) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalLayoutDirection provides LayoutDirection.Rtl, LocalHapticFeedback provides haptic) {
                    MsngrTheme { CustomizedNotificationsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) }
                }
            }
            val row = compose.onNodeWithTag("notification_custom_peer")
            row.performTouchInput { swipeLeft() }
            row.assertExists()
            val revealDistance = with(compose.density) { 72.dp.toPx() }
            val wrapper = compose.onNodeWithTag("swipe_notification_custom_peer")
            wrapper.performTouchInput { swipe(center, center + Offset(revealDistance, 0f), durationMillis = 1000) }
            val actionBounds = compose.onNodeWithTag("reset_notification_custom_peer").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertEquals(wrapper.fetchSemanticsNode().boundsInRoot.left, actionBounds.left, 1f)
            assertTrue(InAppNotificationPrefs.read(context).conversations.containsKey("peer"))
            wrapper.performTouchInput { swipeRight() }
            compose.onNodeWithTag("no_notification_customizations").assertExists()
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

    private fun screenshot(name: String, popup: Boolean = false) {
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            (if (popup) compose.onNode(isPopup()) else compose.onRoot()).captureToImage()
                .asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
