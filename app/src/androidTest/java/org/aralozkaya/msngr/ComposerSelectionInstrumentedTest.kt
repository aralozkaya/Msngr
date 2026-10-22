package org.aralozkaya.msngr

import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerSelectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val draft = mutableStateOf("one two three")
    private val nativeStyle = mutableStateOf(NativeTextStyle())

    @Test fun firstLongPressShowsSelectionHandlesAndMenu() {
        showChat()
        selectWordAndAssertControls()
    }

    @Test fun selectionControlsSurviveComposerRefreshAfterFirstLongPress() {
        showChat()
        onView(withContentDescription("Message input")).perform(pressCharacter(1, Tap.SINGLE))
        compose.waitForIdle()
        selectWordAndAssertControls()

        compose.runOnIdle { nativeStyle.value = NativeTextStyle(colorRgb = 0x123456) }
        assertSelectionControls()
    }

    private fun showChat() {
        MessengerPlusPrefs.setEnabled(compose.activity, true)
        compose.setContent {
            MsngrTheme {
                ChatScreen(
                    draft = draft.value, onDraftChange = { draft.value = it },
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                    isTyping = false, onBack = {}, onSendMessage = { _, _ -> },
                    onRetryMessage = {}, onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {},
                    nativeStyle = nativeStyle.value,
                )
            }
        }
    }

    private fun selectWordAndAssertControls() {
        onView(withContentDescription("Message input")).perform(pressCharacter(5, Tap.LONG))
        compose.waitForIdle()
        onView(withContentDescription("Message input")).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            assertEquals("two", editor.text.substring(editor.selectionStart, editor.selectionEnd))
        }
        assertSelectionControls()
    }

    private fun assertSelectionControls() {
        // Newer Android toolbars host a remote SurfaceView; older releases expose the Copy view.
        val toolbarContent = anyOf(withText(android.R.string.copy), isAssignableFrom(SurfaceView::class.java))
        onView(toolbarContent).inRoot(allOf(isPlatformPopup(), withDecorView(hasDescendant(toolbarContent))))
            .check(matches(isDisplayed()))
        for (name in listOf("selection_start_handle", "selection_end_handle")) {
            val id = compose.activity.resources.getIdentifier(name, "id", "android")
            val handle = withId(id)
            onView(handle).inRoot(allOf(isPlatformPopup(), withDecorView(hasDescendant(handle))))
                .check(matches(isDisplayed()))
        }
    }

    private fun pressCharacter(offset: Int, tap: Tap) = GeneralClickAction(
        tap,
        { view ->
            val editor = view as EditText
            val layout = editor.layout
            val line = layout.getLineForOffset(offset)
            val location = IntArray(2).also(editor::getLocationOnScreen)
            floatArrayOf(
                location[0] + editor.totalPaddingLeft + layout.getPrimaryHorizontal(offset) - editor.scrollX,
                location[1] + editor.totalPaddingTop +
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - editor.scrollY,
            )
        },
        Press.FINGER,
        InputDevice.SOURCE_TOUCHSCREEN,
        MotionEvent.BUTTON_PRIMARY,
    )
}
