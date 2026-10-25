package org.aralozkaya.msngr

import android.view.ActionMode
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.ProfilePage
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The selection toolbar's Colors action opens the color sheet through the keyboard sheet scaffold. */
@RunWith(AndroidJUnit4::class)
class MessengerPlusColorSheetInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = compose.activity.applicationContext
    private val red = MessengerPlusTextCodec.palette.first { it.index == 4 }

    @Before
    fun enableMessengerPlus() {
        MessengerPlusPrefs.setEnabled(context, true)
        context.getSharedPreferences("msngr_keyboard", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Match MsngrActivity: edge-to-edge, with the keyboard delivered as insets.
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After
    fun releaseRepository() {
        runCatching { context.appContainer.messengerRepository.logout() }
    }

    @Test
    fun chatComposerColorsActionOpensTheSheetAndColorsTheSelection() {
        val draft = showChat()
        openColorsFromSelection("Message input")
        pickRed()
        assertDraftColored(draft)
    }

    @Test
    fun openingTheSheetKeepsTheTextHighlighted() {
        showChat()
        openColorsFromSelection("Message input")
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editor = view as EditText
            assertEquals(4 to 7, editor.selectionStart to editor.selectionEnd)
        }
    }

    @Test
    fun tappingTheFieldWhileTheColorSheetIsOpenKeepsItOpenWithoutTheKeyboard() {
        showChat()
        openColorsFromSelection("Message input")
        onView(withContentDescription("Message input")).perform(tapCharacter(1))
        compose.waitForIdle()
        Thread.sleep(500) // Leave room for a keyboard that should not appear.
        compose.waitForIdle()

        assertTrue(
            "The color sheet should stay open",
            compose.onAllNodes(hasTestTag(MESSENGER_PLUS_COLOR_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty(),
        )
        assertFalse("The keyboard should stay closed", imeVisible())
    }

    @Test
    fun tappingTheFieldWhileTheEmoticonSheetIsOpenOpensTheKeyboardAndClosesTheSheet() {
        showChat()
        compose.onNodeWithContentDescription("Show emoticons").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(EMOTICON_PICKER_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        onView(withContentDescription("Message input")).perform(tapCharacter(1))
        compose.waitUntil(5_000) { imeVisible() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(EMOTICON_PICKER_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun tapCharacter(offset: Int) = GeneralClickAction(
        Tap.SINGLE,
        { view ->
            val editor = view as EditText
            val layout = editor.layout
            val line = layout.getLineForOffset(offset)
            val location = IntArray(2).also(editor::getLocationOnScreen)
            floatArrayOf(
                location[0] + editor.totalPaddingLeft + layout.getPrimaryHorizontal(offset),
                location[1] + editor.totalPaddingTop +
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f,
            )
        },
        Press.FINGER,
        InputDevice.SOURCE_UNKNOWN,
        MotionEvent.BUTTON_PRIMARY,
    )

    @Test
    fun chatComposerColorsActionReplacesTheOpenKeyboard() {
        val draft = showChat()
        showKeyboardFor("Message input")
        openColorsFromSelection("Message input")
        compose.waitUntil(5_000) { !imeVisible() }
        pickRed()
        assertDraftColored(draft)
    }

    @Test
    fun expandingTheColorSheetKeepsTheMessageFieldAboveIt() {
        showChat()
        openColorsFromSelection("Message input")
        val handle = compose.onNodeWithContentDescription("Drag handle")
        val collapsedTop = handle.getBoundsInRoot().top
        assertTrue(messageFieldBottom() <= collapsedTop)

        handle.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expandedTop = handle.getBoundsInRoot().top
        assertTrue("The sheet should have expanded", expandedTop < collapsedTop)
        // The extra height pads the page too, so the composer moves up with the sheet.
        assertTrue(messageFieldBottom() <= expandedTop)
    }

    private fun messageFieldBottom() =
        compose.onNodeWithContentDescription("Message input").getBoundsInRoot().bottom

    private fun showChat(): androidx.compose.runtime.MutableState<String> {
        val draft = mutableStateOf("one two three")
        compose.setContent {
            MsngrTheme {
                ChatScreen(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = emptyList(),
                    isHistoryLoaded = true,
                    canLoadOlderMessages = false,
                    isTyping = false,
                    onBack = {},
                    onSendMessage = { _, _ -> },
                    onRetryMessage = {},
                    onSendTyping = {},
                    onSendNudge = {},
                    onLoadOlderMessages = {},
                )
            }
        }
        return draft
    }

    private fun assertDraftColored(draft: androidx.compose.runtime.MutableState<String>) {
        compose.runOnIdle {
            val parsed = MessengerPlusTextCodec.parse(draft.value)
            assertEquals("one two three", parsed.text)
            val colored = parsed.styleSpans.single { it.style.foreground != null }
            assertEquals(MessengerPlusTextRange(4, 7), colored.range)
            assertEquals(red.argb, colored.style.foreground?.argb)
        }
    }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.runOnIdle {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun showKeyboardFor(editorDescription: String) {
        onView(withContentDescription(editorDescription)).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            view.requestFocus()
            WindowCompat.getInsetsController(compose.activity.window, view)
                .show(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) { imeVisible() }
        compose.waitForIdle()
    }

    @Test
    fun profileDisplayNameColorsActionOpensTheSheetAndColorsTheSelection() {
        showProfilePage()
        openColorsFromSelection("Display name", replaceTextWith = "one two three")
        pickRed()
        assertEditorText("Display name", "one [c=${red.wireToken}]two[/c] three")
    }

    @Test
    fun profileStatusColorsActionOpensTheSheetAndColorsTheSelection() {
        showProfilePage()
        openColorsFromSelection("Personal message", replaceTextWith = "one two three")
        pickRed()
        assertEditorText("Personal message", "one [c=${red.wireToken}]two[/c] three")
    }

    @Test
    fun profileFieldsTrailingEmoticonButtonsOpenThePanelAndInsertEmoticons() {
        showProfilePage()
        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Show emoticons"))[0]
            .performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(EMOTICON_PICKER_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Smile").performClick()
        compose.waitForIdle()
        onView(withContentDescription("Display name")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            assertTrue((view as EditText).text.toString().endsWith(smile.menuToken))
        }

        // Display name's button is now "Show keyboard", so the remaining one is Personal message's.
        compose.onNodeWithContentDescription("Show emoticons").performClick()
        compose.onNodeWithContentDescription("Smile").performClick()
        compose.waitForIdle()
        onView(withContentDescription("Personal message")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            assertTrue((view as EditText).text.toString().endsWith(smile.menuToken))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showProfilePage() {
        context.appContainer.repositoryFor(useMockServer = true)
        compose.setContent {
            MsngrTheme {
                ProfilePage(
                    innerPadding = PaddingValues(),
                    scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    onProfileSaved = {},
                )
            }
        }
    }

    /** Highlights "two" and chooses Colors from the editor's own selection toolbar. */
    private fun openColorsFromSelection(editorDescription: String, replaceTextWith: String? = null) {
        onView(withContentDescription(editorDescription)).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editor = view as EditText
            replaceTextWith?.let(editor::setText)
            editor.setSelection(4, 7)
            val callback = checkNotNull(editor.customSelectionActionModeCallback)
            val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
            callback.onPrepareActionMode(mode, mode.menu)
            val colors = checkNotNull(mode.menu.findItem(R.id.format_plus)) {
                "The selection toolbar should offer Plus! Formatting"
            }
            assertTrue(callback.onActionItemClicked(mode, colors))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(MESSENGER_PLUS_COLOR_SHEET_TAG))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun pickRed() {
        // The options scroll within the keyboard-height sheet.
        compose.onNodeWithContentDescription("${red.wireToken} ${red.label}")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
    }

    private fun assertEditorText(editorDescription: String, expected: String) {
        onView(withContentDescription(editorDescription)).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            assertEquals(expected, (view as EditText).text.toString())
        }
    }
}
