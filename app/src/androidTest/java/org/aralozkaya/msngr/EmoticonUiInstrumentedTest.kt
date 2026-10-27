package org.aralozkaya.msngr

import android.graphics.Typeface
import android.text.InputType
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.view.ActionMode
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmoticonUiInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val editorValue = mutableStateOf("")
    private val convertTypedShortcuts = mutableStateOf(true)
    private val useOriginalEmoticons = mutableStateOf(true)
    private val messengerPlusEnabled = mutableStateOf(true)
    private val hideControlCharacters = mutableStateOf(true)
    private lateinit var formattingController: MessengerPlusEditorController

    private fun showEditor(
        syntax: MessengerPlusEditorSyntax = MessengerPlusEditorSyntax.MESSAGE_CONTROLS,
    ) {
        formattingController = MessengerPlusEditorController(syntax)
        editorValue.value = ""
        convertTypedShortcuts.value = true
        useOriginalEmoticons.value = true
        messengerPlusEnabled.value = true
        hideControlCharacters.value = true
        compose.setContent {
            MaterialTheme {
                MsnEmoticonEditor(
                    value = editorValue.value,
                    onValueChange = { editorValue.value = it },
                    convertTypedShortcuts = convertTypedShortcuts.value,
                    useOriginalEmoticons = useOriginalEmoticons.value,
                    messengerPlusEnabled = messengerPlusEnabled.value,
                    hideControlCharacters = hideControlCharacters.value,
                    controller = formattingController,
                    singleLine = syntax == MessengerPlusEditorSyntax.PROFILE_MARKUP,
                )
            }
        }
    }

    @Test
    fun nativeSelectionActionsStyleOnlyTheSelectedTextAndKeepEditingActions() {
        showEditor()
        compose.onNode(hasSetTextAction()).performTextReplacement("one two three")
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editor = view as EditText
            editor.setSelection(4, 7)
            val callback = checkNotNull(editor.customSelectionActionModeCallback)
            val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
            mode.menu.add(0, android.R.id.copy, 0, "Copy")
            callback.onPrepareActionMode(mode, mode.menu)
            assertTrue(mode.menu.findItem(android.R.id.copy) != null)
            for (control in listOf(MessengerPlusTextCodec.BOLD_CONTROL, MessengerPlusTextCodec.ITALIC_CONTROL,
                MessengerPlusTextCodec.STRIKEOUT_CONTROL, MessengerPlusTextCodec.UNDERLINE_CONTROL)) {
                formattingController.toggle(control)
            }
            val result = formattingController.snapshot()
            val parsed = MessengerPlusTextCodec.parse(result.text)
            assertEquals("one two three", parsed.text)
            val styled = parsed.styleSpans.single { it.style.bold }
            assertEquals(MessengerPlusTextRange(4, 7), styled.range)
            assertTrue(styled.style.italic && styled.style.underline && styled.style.strikeout)
            assertEquals("two", MessengerPlusTextCodec.plainText(
                result.text.substring(result.selection.min, result.selection.max),
            ))
            formattingController.removeAllFormatting()
            assertEquals("one two three", formattingController.snapshot().text)
            assertTrue(mode.menu.findItem(android.R.id.copy) != null)
            mode.finish()
        }
    }

    @Test
    fun nativeSelectionAndInsertionMenusOmitColorAndEmoticonPickers() {
        showEditor()
        compose.onNode(hasSetTextAction()).performTextReplacement("one two three")
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editor = view as EditText
            for (selection in listOf(TextRange(4, 7), TextRange(4))) {
                editor.setSelection(selection.start, selection.end)
                val callback = checkNotNull(if (selection.collapsed) {
                    editor.customInsertionActionModeCallback
                } else {
                    editor.customSelectionActionModeCallback
                })
                val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
                assertTrue(mode.menu.findItem(R.id.format_colors) == null)
                assertTrue(mode.menu.findItem(R.id.insert_emoticon) == null)
                mode.finish()
            }
        }
    }

    @Test
    fun nativeProfileToolbarRendersStylesAndWritesProfileMarkup() {
        showEditor(syntax = MessengerPlusEditorSyntax.PROFILE_MARKUP)
        compose.onNode(hasSetTextAction()).performTextReplacement("one two three")
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editor = view as EditText
            editor.setSelection(4, 7)
            val callback = checkNotNull(editor.customSelectionActionModeCallback)
            val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
            formattingController.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
            assertEquals("one [b]two[/b] three", editor.text.toString())
            assertTrue(formattingController.supportsBackgroundFormatting)
            formattingController.applyForeground(checkNotNull(MessengerPlusTextCodec.paletteColor(4)))
            val snapshot = formattingController.snapshot()
            assertFalse(snapshot.text.any { it.isISOControl() })
            val parsed = MessengerPlusTextCodec.parse(snapshot.text)
            val styled = parsed.styleSpans.single { it.style.bold }
            assertEquals(MessengerPlusTextRange(4, 7), styled.range)
            assertEquals(0xFFFF0000.toInt(), styled.style.foreground?.argb)
            mode.finish()
        }
        assertEditorHasBoldStyle(expected = true)
    }

    @Test
    fun editorConstructionDoesNotRequireAnAttachedSelectionListener() {
        compose.runOnIdle {
            MsnEditText(compose.activity)
        }
    }

    @Test
    fun filteringEmojiKeepsTheCursorAndImeComposingWord() {
        compose.runOnIdle {
            val editor = MsnEditText(compose.activity)
            editor.setExternalValue("abcd")
            editor.setSelection(2)
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            connection.commitText("😀", 1)
            assertEquals("abcd", editor.text.toString())
            assertEquals(2, editor.selectionStart)

            connection.setComposingText("x😀y", 1)
            assertEquals("abxycd", editor.text.toString())
            assertEquals(4, editor.selectionStart)
            assertEquals(2, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(4, BaseInputConnection.getComposingSpanEnd(editor.text))
            connection.setComposingText("z", 1)
            assertEquals("abzcd", editor.text.toString())
            assertEquals(3, editor.selectionStart)

            connection.finishComposingText()
            editor.setExternalValue("a©b")
            editor.setSelection(2)
            connection.commitText("\uFE0F", 1)
            assertEquals("a©b", editor.text.toString())
            assertEquals(2, editor.selectionStart)
        }
    }

    @Test
    fun editorRequestsAutocorrectionForProseAndAllowsUnicodeSearchQueries() {
        compose.runOnIdle {
            val editor = MsnEditText(compose.activity)
            for (singleLine in listOf(false, true)) {
                editor.configureSingleLine(singleLine)
                val info = EditorInfo()
                checkNotNull(editor.onCreateInputConnection(info))
                assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0)
                assertEquals(singleLine, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0)
            }
            editor.configureSingleLine(true, autoCorrect = false)
            assertEquals(0, editor.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            editor.allowUnicodeEmoji = true
            editor.setExternalValue("😀 © क्\u200Dष")
            assertEquals("😀 © क्\u200Dष", editor.text.toString())
        }
    }

    @Test
    fun editorAppliesTypedPickerOriginalAndUnicodeRules() {
        showEditor()
        compose.onNode(hasSetTextAction()).performTextReplacement(":D")
        assertSingleSpanNamed("OriginalMsnGlyphSpan")

        compose.runOnIdle { convertTypedShortcuts.value = false }
        assertSpanCount(0)
        compose.onNode(hasSetTextAction()).assertTextEquals(":D")

        val smile = MSN_EMOTICON_SHORTCUTS.first { it.name == "Smile" }
        compose.runOnIdle { editorValue.value = smile.menuToken.toString() }
        assertSingleSpanNamed("OriginalMsnGlyphSpan")
        compose.onNode(hasSetTextAction()).assertTextEquals(":)")

        compose.runOnIdle { useOriginalEmoticons.value = false }
        assertSingleSpanNamed("UnicodeMsnGlyphSpan")
    }

    @Test
    fun messageTextUsesOriginalArtworkOrModernUnicode() {
        compose.setContent {
            MaterialTheme {
                Column {
                    MsnEmoticonText(
                        text = "Original :D",
                        useOriginalEmoticons = true,
                    )
                    MsnEmoticonText(
                        text = "Modern :D",
                        useOriginalEmoticons = false,
                    )
                }
            }
        }

        compose.onNodeWithText("Original :D").assertExists()
        compose.onNodeWithContentDescription("Open-mouthed").assertExists()
        compose.onNodeWithText("Modern 😀").assertExists()
    }

    @Test
    fun editorControllerAppliesInteroperableStylesToTheCurrentSelection() {
        showEditor()
        compose.onNode(hasSetTextAction()).performTextReplacement("Style")

        compose.runOnIdle {
            assertTrue(formattingController.selectAll())
            assertTrue(formattingController.hasSelection)
            formattingController.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
            formattingController.applyForeground(
                checkNotNull(MessengerPlusTextCodec.paletteColor(4)),
            )
            formattingController.applyBackground(MessengerPlusTextCodec.palette[1])
        }

        compose.runOnIdle {
            val parsed = MessengerPlusTextCodec.parse(editorValue.value)
            assertEquals("Style", parsed.text)
            assertTrue(parsed.styleSpans.single().style.bold)
            assertEquals(0xFFFF0000.toInt(), parsed.styleSpans.single().style.foreground?.argb)
            assertEquals(0xFF000000.toInt(), parsed.styleSpans.single().style.background?.argb)
        }
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editable = (view as EditText).text
            assertTrue(
                editable.getSpans(0, editable.length, StyleSpan::class.java)
                    .any { it.style == Typeface.BOLD },
            )
        }

        compose.runOnIdle {
            assertTrue(formattingController.hasFormatting)
            formattingController.removeAllFormatting()
            assertEquals("Style", editorValue.value)
            assertFalse(formattingController.hasFormatting)
        }
        assertEditorHasBoldStyle(expected = false)
    }

    @Test
    fun profileEditorControllerUsesProfileMarkupAndKeepsTheCursorInsideNewTags() {
        formattingController = MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
        var profileValue = TextFieldValue("")
        formattingController.attachProfileValue(profileValue) { profileValue = it }

        formattingController.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
        assertEquals("[b][/b]", profileValue.text)
        assertTrue(formattingController.insert("Style"))
        formattingController.selectAll()
        formattingController.applyForeground(
            checkNotNull(MessengerPlusTextCodec.paletteColor(4)),
        )

        assertFalse(profileValue.text.any { it.isISOControl() })
        val parsed = MessengerPlusTextCodec.parse(profileValue.text)
        assertEquals("Style", parsed.text)
        assertTrue(parsed.styleSpans.single().style.bold)
        assertEquals(0xFFFF0000.toInt(), parsed.styleSpans.single().style.foreground?.argb)

        assertTrue(formattingController.hasFormatting)
        formattingController.removeAllFormatting()
        assertEquals("Style", profileValue.text)
        assertFalse(formattingController.hasFormatting)
        assertEquals(MessengerPlusStyle(), MessengerPlusTextCodec.parse(profileValue.text).finalStyle)
    }

    @Test
    fun disablingMessengerPlusRemovesRenderedEditorStyles() {
        showEditor()
        compose.runOnIdle {
            editorValue.value = "\u0002Style\u0002"
        }

        assertEditorHasBoldStyle(expected = true)
        compose.runOnIdle { messengerPlusEnabled.value = false }

        compose.onNode(hasSetTextAction()).assertTextEquals("Style")
        assertEditorHasBoldStyle(expected = false)
    }

    @Test
    fun controlCharacterVisibilityUpdatesEditorAndAccessibilityText() {
        showEditor()
        compose.runOnIdle {
            editorValue.value = "[b]Style[/b]"
            messengerPlusEnabled.value = false
            hideControlCharacters.value = false
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("[b]Style[/b]")
        assertSpanCount(0)
        assertEditorHasBoldStyle(expected = false)
        compose.runOnIdle { hideControlCharacters.value = true }
        compose.onNode(hasSetTextAction()).assertTextEquals("Style")
        assertSpanCount(2)
        compose.runOnIdle {
            hideControlCharacters.value = false
            messengerPlusEnabled.value = true
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("Style")
        assertSpanCount(2)
        assertEditorHasBoldStyle(expected = true)
    }

    private fun assertEditorHasBoldStyle(expected: Boolean) {
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editable = (view as EditText).text
            val hasBoldStyle = editable.getSpans(0, editable.length, StyleSpan::class.java)
                .any { it.style == Typeface.BOLD }
            if (expected) assertTrue(hasBoldStyle) else assertFalse(hasBoldStyle)
        }
    }

    private fun assertSpanCount(expected: Int) {
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editable = (view as EditText).text
            assertEquals(
                expected,
                editable.getSpans(0, editable.length, ReplacementSpan::class.java).size,
            )
        }
    }

    private fun assertSingleSpanNamed(expectedSimpleName: String) {
        onView(withContentDescription("Message input")).check { view, noViewException ->
            if (noViewException != null) throw noViewException
            val editable = (view as EditText).text
            val spans = editable.getSpans(0, editable.length, ReplacementSpan::class.java)
            assertEquals(1, spans.size)
            assertEquals(expectedSimpleName, spans.single().javaClass.simpleName)
        }
    }
}
