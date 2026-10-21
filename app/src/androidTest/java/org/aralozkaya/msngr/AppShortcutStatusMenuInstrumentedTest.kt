package org.aralozkaya.msngr

import android.view.ActionMode
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AppShortcutStatusMenuInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun statusShortcutDialogSavesPresenceWithoutChangingFormattedMessage() {
        var savedPresence: Presence? = null
        var savedMessage: String? = "not saved"
        compose.setContent {
            MsngrTheme {
                StatusShortcutWindow(
                    initialPresence = Presence.Online,
                    initialStatusMessage = "[b]Hello[/b]",
                    applying = false,
                    errorMessage = null,
                    onSave = { presence, message ->
                        savedPresence = presence
                        savedMessage = message
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("shortcut_status_save").assertIsNotEnabled()
        compose.onNodeWithTag("shortcut_status_dropdown").performClick()
        compose.onNodeWithText("Appear offline").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shortcut_status_save").performClick()
        compose.runOnIdle {
            assertEquals(Presence.Offline, savedPresence)
            assertNull(savedMessage)
        }
    }

    @Test
    fun statusShortcutOpensPlusFormattingAndSavesTheStyledSelection() {
        var savedMessage: String? = null
        compose.setContent {
            MsngrTheme {
                CompositionLocalProvider(LocalMessengerPlusEnabled provides true) {
                    StatusShortcutWindow(
                        initialPresence = Presence.Online,
                        initialStatusMessage = "one two three",
                        applying = false,
                        errorMessage = null,
                        onSave = { _, message -> savedMessage = message },
                        onDismiss = {},
                    )
                }
            }
        }
        onView(withContentDescription("Status message")).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            editor.requestFocus()
            editor.setSelection(4, 7)
            val callback = checkNotNull(editor.customSelectionActionModeCallback)
            val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
            assertNull(mode.menu.findItem(R.id.format_colors))
            assertNull(mode.menu.findItem(R.id.insert_emoticon))
            assertNull(mode.menu.findItem(R.id.format_native))
            callback.onActionItemClicked(mode, checkNotNull(mode.menu.findItem(R.id.format_plus)))
        }
        compose.onNodeWithText("Bold").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("shortcut_status_save").performClick()
        compose.runOnIdle { assertEquals("one [b]two[/b] three", savedMessage) }
    }

    @Test
    fun statusShortcutDialogSavesEditedPersonalMessage() {
        var savedMessage: String? = null
        compose.setContent {
            MsngrTheme {
                StatusShortcutWindow(
                    initialPresence = Presence.Online,
                    initialStatusMessage = "Old message",
                    applying = false,
                    errorMessage = null,
                    onSave = { _, message -> savedMessage = message },
                    onDismiss = {},
                )
            }
        }

        compose.onNode(hasSetTextAction())
            .performTextReplacement("New message")
        compose.onNodeWithTag("shortcut_status_save").performClick()
        compose.runOnIdle { assertEquals("New message", savedMessage) }
    }

    @Test
    fun statusShortcutKeepsMarkupWhenFormattingIsEnabled() {
        var savedMessage: String? = null
        compose.setContent {
            MsngrTheme {
                CompositionLocalProvider(LocalMessengerPlusEnabled provides true) {
                    StatusShortcutWindow(
                        initialPresence = Presence.Online,
                        initialStatusMessage = "[b]Old[/b]",
                        applying = false,
                        errorMessage = null,
                        onSave = { _, message -> savedMessage = message },
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextReplacement("[i]New[/i]")
        compose.onNodeWithTag("shortcut_status_save").performClick()
        compose.runOnIdle { assertEquals("[i]New[/i]", savedMessage) }
    }

    @Test
    fun statusShortcutSavesPlainTextWhenFormattingIsDisabled() {
        var savedMessage: String? = null
        compose.setContent {
            MsngrTheme {
                CompositionLocalProvider(LocalMessengerPlusEnabled provides false) {
                    StatusShortcutWindow(
                        initialPresence = Presence.Online,
                        initialStatusMessage = "[b]Old[/b]",
                        applying = false,
                        errorMessage = null,
                        onSave = { _, message -> savedMessage = message },
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextReplacement("[i]New[/i]")
        compose.onNodeWithTag("shortcut_status_save").performClick()
        compose.runOnIdle { assertEquals("New", savedMessage) }
    }
}
