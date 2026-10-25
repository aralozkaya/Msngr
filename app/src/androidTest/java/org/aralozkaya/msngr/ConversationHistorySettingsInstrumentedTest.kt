package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.aralozkaya.msngr.pages.MessengerPlusSettingsScreen
import org.aralozkaya.msngr.pages.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationHistorySettingsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun deleteAllRequiresConfirmationAndCancelKeepsHistory() {
        var deleteRequests = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    innerPadding = PaddingValues(),
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                    onDestinationSelected = {},
                    onDeleteAllChatHistories = { deleteRequests += 1 },
                )
            }
        }
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("delete_all_chat_histories"))

        compose.onNodeWithTag("delete_all_chat_histories").performClick()
        compose.onNodeWithText("Delete all chat histories?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete all chat histories?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deleteRequests) }

        compose.onNodeWithTag("delete_all_chat_histories").performClick()
        compose.onNodeWithText("Delete all").performClick()

        compose.onNodeWithText("Delete all chat histories?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, deleteRequests) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun messengerPlusDefaultsOnAndShowsRecipientDisclaimer() {
        val context = compose.activity
        val preferences = AccountPreferences.preferences(context, MessengerPlusPrefs.PREFS_NAME)
        val hadStoredValue = preferences.contains(MessengerPlusPrefs.KEY_ENABLED)
        val previousValue = preferences.getBoolean(MessengerPlusPrefs.KEY_ENABLED, true)
        val hideKey = MessengerPlusPrefs.KEY_HIDE_CONTROL_CHARACTERS
        val hadHideValue = preferences.contains(hideKey)
        val previousHideValue = preferences.getBoolean(hideKey, true)
        preferences.edit().remove(MessengerPlusPrefs.KEY_ENABLED).remove(hideKey).commit()

        try {
            assertTrue(MessengerPlusPrefs.state(context).value.enabled)
            compose.setContent {
                MaterialTheme {
                    MessengerPlusSettingsScreen(
                        innerPadding = PaddingValues(),
                        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                    )
                }
            }
            compose.onNode(hasScrollAction())
                .performScrollToNode(hasTestTag("messenger_plus_enabled"))
            val toggle = compose.onNodeWithTag("messenger_plus_enabled")
            toggle.assertIsOn()
            compose.onNodeWithText(MESSENGER_PLUS_DISCLAIMER).assertExists()

            val hideToggle = compose.onNodeWithTag("messenger_plus_hide_control_characters")
            hideToggle.assertIsOn().assertIsNotEnabled()
            toggle.performClick().assertIsOff()
            hideToggle.assertIsOn().assertIsEnabled().performClick().assertIsOff()
            compose.runOnIdle {
                assertFalse(MessengerPlusPrefs.state(context).value.hideControlCharacters)
                assertFalse(preferences.getBoolean(hideKey, true))
            }
            toggle.performClick().assertIsOn()
            hideToggle.assertIsOn().assertIsNotEnabled()
            compose.runOnIdle { MessengerPlusPrefs.setHideControlCharacters(context, true) }
            toggle.performClick().assertIsOff()
            hideToggle.assertIsOff().assertIsEnabled()

            compose.runOnIdle {
                assertFalse(MessengerPlusPrefs.isEnabled(context))
                assertFalse(
                    preferences.getBoolean(MessengerPlusPrefs.KEY_ENABLED, true),
                )
            }
        } finally {
            preferences.edit().apply {
                if (hadHideValue) putBoolean(hideKey, previousHideValue) else remove(hideKey)
                if (hadStoredValue) {
                    putBoolean(MessengerPlusPrefs.KEY_ENABLED, previousValue)
                } else {
                    remove(MessengerPlusPrefs.KEY_ENABLED)
                }
            }.commit()
            MessengerPlusPrefs.state(context)
        }
    }
}
