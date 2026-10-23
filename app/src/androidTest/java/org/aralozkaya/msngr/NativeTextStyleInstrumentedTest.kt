package org.aralozkaya.msngr

import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.view.ActionMode
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.persistentConversationStyleStore
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NativeTextStyleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val style = mutableStateOf(NativeTextStyle())
    private val draft = mutableStateOf("one two three")

    private fun showChat(plusEnabled: Boolean, onSizeNoticeRequested: () -> Boolean = { false }) {
        MessengerPlusPrefs.setEnabled(compose.activity, plusEnabled)
        compose.setContent {
            MsngrTheme {
                ChatScreen(draft = draft.value, onDraftChange = { draft.value = it },
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                    isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                    onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {},
                    nativeStyle = style.value, onNativeStyleChange = { style.value = it },
                    onSizeNoticeRequested = onSizeNoticeRequested)
            }
        }
    }

    private fun openMenu(native: Boolean, collapsed: Boolean = false) {
        onView(withContentDescription("Message input")).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            editor.requestFocus()
            editor.setSelection(4, if (collapsed) 4 else 7)
            val callback = checkNotNull(if (collapsed) editor.customInsertionActionModeCallback else editor.customSelectionActionModeCallback)
            val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
            callback.onPrepareActionMode(mode, mode.menu)
            assertNotNull(mode.menu.findItem(R.id.format_native))
            assertNull(mode.menu.findItem(R.id.format_bold))
            assertNull(mode.menu.findItem(R.id.format_colors))
            assertTrue(callback.onActionItemClicked(mode, checkNotNull(mode.menu.findItem(
                if (native) R.id.format_native else R.id.format_plus))))
        }
        compose.waitForIdle()
        if (InstrumentationRegistry.getArguments().getString("styleScreenshots") == "true") {
            val target = java.io.File(compose.activity.getExternalFilesDir(null),
                if (native) "native-style-sheet.png" else "plus-formatting-sheet.png")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun nativeSheetWorksWithoutPlusAndUpdatesEditorColorSizeAndResetWithoutEditingTheDraft() {
        showChat(false)
        openMenu(native = true, collapsed = true)
        compose.onNodeWithTag("native_text_style_sheet").assertExists()
        chooseFontStyle(NativeFontStyle.BoldItalic)
        for (label in listOf("Strikeout", "Underline")) compose.onNodeWithText(label).performClick()
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("24")
        compose.runOnIdle { assertEquals(NativeTextStyle(true, true, true, true, size = 24), style.value) }
        compose.onNodeWithContentDescription("Text size").performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        compose.onNodeWithTag("native_text_size_input").assertTextContains("36")
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("999")
        compose.runOnIdle { assertEquals(36, style.value.size) }
        compose.onNodeWithText("Enter a size from 8 to 72.").assertExists()
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("18")
        compose.onNodeWithText("Color").performClick()
        compose.onNodeWithContentDescription("Color wheel").assertExists()
        compose.onNodeWithText("Color code").performTextReplacement("#123456")
        compose.onNodeWithText("Apply color").performClick()
        compose.runOnIdle { assertEquals(0x123456, style.value.colorRgb) }
        closeNativeSheet()
        onView(withContentDescription("Message input")).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            val text = editor.text
            val spans = text.getSpans(0, text.length, StyleSpan::class.java)
            assertTrue(spans.any { it.style and Typeface.BOLD != 0 })
            assertTrue(spans.any { it.style and Typeface.ITALIC != 0 })
            assertTrue(text.getSpans(0, text.length, UnderlineSpan::class.java).isNotEmpty())
            assertTrue(text.getSpans(0, text.length, StrikethroughSpan::class.java).isNotEmpty())
            assertEquals(0xFF123456.toInt(), editor.currentTextColor)
            assertEquals("one two three", text.toString())
        }
        openMenu(native = true)
        compose.onNodeWithText("Reset styling").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(NativeTextStyle(), style.value)
            assertEquals("one two three", draft.value)
        }
    }

    @Test fun nativeColorAndSwitchRowsHaveMatchingBounds() {
        showChat(false)
        openMenu(native = true)
        val strikeoutBounds = compose.onNodeWithText("Strikeout").fetchSemanticsNode().boundsInRoot
        val underlineBounds = compose.onNodeWithText("Underline").fetchSemanticsNode().boundsInRoot
        val colorBounds = compose.onNodeWithText("Color").fetchSemanticsNode().boundsInRoot
        assertEquals(strikeoutBounds.left, colorBounds.left, 0.5f)
        assertEquals(strikeoutBounds.right, colorBounds.right, 0.5f)
        assertEquals(strikeoutBounds.height, colorBounds.height, 0.5f)
        assertEquals(strikeoutBounds.width, underlineBounds.width, 0.5f)

    }

    @Test fun nativeSizeSliderStopsEveryTwelvePointsAndNumberInputKeepsExactSizes() {
        showChat(false)
        openMenu(native = true)
        val slider = compose.onNodeWithContentDescription("Text size").performScrollTo().assertIsDisplayed()
        val stops = listOf(8, 12, 24, 36, 48, 60, 72)
        stops.forEachIndexed { index, size ->
            slider.performTouchInput {
                swipe(center, Offset((width * index.toFloat() / stops.lastIndex).coerceIn(1f, width - 1f), centerY))
            }
            val actualSize = compose.runOnIdle { style.value.size }
            assertEquals("Stop $index: ${slider.fetchSemanticsNode().boundsInRoot}", size, actualSize)
            compose.onNodeWithTag("native_text_size_input").assertTextContains(size.toString())
        }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(31f) }
        compose.runOnIdle { assertEquals(36, style.value.size) }
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("19")
        compose.runOnIdle { assertEquals(19, style.value.size) }
        val progress = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(19f, progress.current)
        assertEquals(8f..72f, progress.range)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        compose.runOnIdle { assertEquals(24, style.value.size) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(21f) }
        compose.runOnIdle { assertEquals(12, style.value.size) }
    }

    @Test fun plusDecorationsMovedIntoTheSheetAndStillAffectOnlyTheSelectedText() {
        showChat(true)
        openMenu(native = false)
        compose.onNodeWithText("Plus! Formatting").assertDoesNotExist()
        compose.onNodeWithText("Clear formatting").assertDoesNotExist()
        for (label in listOf("Bold", "Italic", "Strikeout", "Underline")) {
            compose.onNodeWithText(label).assertDoesNotExist()
            compose.onNodeWithContentDescription(label).assertIsOff().performClick().assertIsOn()
        }
        compose.runOnIdle {
            val parsed = MessengerPlusTextCodec.parse(draft.value)
            assertEquals("one two three", parsed.text)
            val decorated = parsed.styleSpans.single { it.style.bold }
            assertEquals(MessengerPlusTextRange(4, 7), decorated.range)
            assertTrue(decorated.style.italic && decorated.style.strikeout && decorated.style.underline)
            assertEquals(NativeTextStyle(), style.value)
        }
        compose.onNodeWithContentDescription("Clear formatting").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("one two three", draft.value) }
        for (label in listOf("Bold", "Italic", "Strikeout", "Underline")) {
            compose.onNodeWithContentDescription(label).assertIsOff()
        }
    }

    @Test fun preferencesRestorePerContactAndPerGroupStylesAfterRecreation() {
        val account = "native-style-${UUID.randomUUID()}"
        val original = persistentConversationStyleStore(compose.activity)
        original.selectAccount(account)
        val chosen = NativeTextStyle(underline = true, colorRgb = 0x123456, size = 24)
            .withFontStyle(NativeFontStyle.SemilightItalic)
        original.update("peer", chosen)
        original.update("group@live.com", chosen.copy(size = 18))
        assertTrue(original.markSizeNoticeShown("PEER"))
        val restored = persistentConversationStyleStore(compose.activity)
        restored.selectAccount(account)
        assertEquals(chosen, restored.styles.value["peer"])
        assertEquals(18, restored.styles.value.getValue("group@live.com").size)
        assertFalse(restored.markSizeNoticeShown("peer"))
        assertTrue(restored.markSizeNoticeShown("group@live.com"))
        restored.update("peer", NativeTextStyle())
        val reset = persistentConversationStyleStore(compose.activity)
        reset.selectAccount(account)
        assertNull(reset.styles.value["peer"])
        assertFalse(reset.markSizeNoticeShown("peer"))
        assertFalse(reset.markSizeNoticeShown("group@live.com"))
        restored.update("group@live.com", NativeTextStyle())
    }

    @Test fun firstResizeNoticeIsVisibleInSheetAndDoesNotRepeatAfterReopeningOrReset() {
        val store = persistentConversationStyleStore(compose.activity)
        store.selectAccount("size-notice-${UUID.randomUUID()}")
        showChat(false) { store.markSizeNoticeShown("peer") }
        val notice = compose.activity.getString(R.string.chat_text_size_local_notice)
        openMenu(native = true)
        chooseFontStyle(NativeFontStyle.Bold)
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("999")
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("10")
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("24")
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithContentDescription("Text size")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        compose.onNodeWithText(notice).assertDoesNotExist()
        closeNativeSheet()
        openMenu(native = true)
        compose.onNodeWithText("Reset styling").performScrollTo().performClick()
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithTag("native_text_size_input").performTextReplacement("18")
        compose.onNodeWithText(notice).assertDoesNotExist()
    }

    @Test fun firstSliderResizeShowsLocalOnlyNotice() {
        val store = persistentConversationStyleStore(compose.activity)
        store.selectAccount("slider-notice-${UUID.randomUUID()}")
        showChat(false) { store.markSizeNoticeShown("peer") }
        openMenu(native = true)
        compose.onNodeWithContentDescription("Text size")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_text_size_local_notice)).assertIsDisplayed()
    }

    @Test fun currentChatSizeResizesBothDirectionsOfHistoryWithoutChangingTheirOtherStyles() {
        val size = mutableStateOf(20)
        val messages = listOf(
            ChatMessage(1, "peer", MessageAuthor.Me, "Outgoing", 1,
                nativeStyle = NativeTextStyle(bold = true, colorRgb = 0x123456, size = 32)),
            ChatMessage(2, "peer", MessageAuthor.Contact, "Incoming", 2,
                nativeStyle = NativeTextStyle(italic = true, underline = true, colorRgb = 0x654321)),
            ChatMessage(3, "peer", MessageAuthor.System, "System", 3),
        )
        compose.setContent {
            MsngrTheme {
                ChatMessageList(
                    messages = messages,
                    listState = rememberLazyListState(),
                    emoticonSettings = EmoticonSettings(),
                    innerPadding = PaddingValues(),
                    textSize = size.value,
                )
            }
        }
        fun assertSize(label: String, expected: Int) {
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(results))
                assertEquals(expected.sp, results.single().layoutInput.style.fontSize)
                assertEquals((expected * 1.5f).sp, results.single().layoutInput.style.lineHeight)
            }
        }
        assertSize("Outgoing", 32)
        assertSize("Incoming", 32)
        assertSize("System", 16)
        val outgoing = compose.onNodeWithText("Outgoing").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().spanStyles.first().item
        val incoming = compose.onNodeWithText("Incoming").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().spanStyles.first().item
        assertEquals(FontWeight.Bold, outgoing.fontWeight)
        assertEquals(Color(0xFF123456), outgoing.color)
        assertEquals(FontStyle.Italic, incoming.fontStyle)
        assertTrue(incoming.textDecoration?.contains(TextDecoration.Underline) == true)
        assertEquals(Color(0xFF654321), incoming.color)
        compose.runOnIdle { size.value = NativeTextStyle.DEFAULT_SIZE }
        assertSize("Outgoing", 16)
        assertSize("Incoming", 16)
    }

    private fun chooseFontStyle(option: NativeFontStyle) {
        compose.onNodeWithTag("native_font_style_dropdown").performClick()
        compose.onNodeWithTag("native_font_style_${option.name}").performScrollTo().performClick()
    }

    private fun closeNativeSheet() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("native_text_style_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun dropdownOffersEveryOriginalMessengerFontStyleAndRendersItsWeight() {
        showChat(false)
        lateinit var editor: EditText
        onView(withContentDescription("Message input")).check { view, error ->
            if (error != null) throw error
            editor = view as EditText
        }
        openMenu(native = true)
        for (option in NativeFontStyle.entries) {
            compose.onNodeWithTag("native_font_style_dropdown").performClick()
            val item = compose.onNodeWithTag("native_font_style_${option.name}").performScrollTo()
            if (option.italic) {
                item.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(action(results))
                    assertRenderedItalic(results.single(), option.weight, option.label)
                }
            }
            item.performClick()
            if (option.italic) {
                compose.onNodeWithTag("native_font_style_dropdown")
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        assertTrue(action(results))
                        assertRenderedItalic(results.single(), option.weight, "Selected ${option.label}")
                    }
            }
            compose.runOnIdle {
                assertEquals(option, style.value.fontStyle)
                val paint = android.text.TextPaint(editor.paint)
                editor.text.getSpans(0, 1, android.text.style.MetricAffectingSpan::class.java)
                    .forEach { it.updateDrawState(paint) }
                assertEquals(option.weight, paint.typeface.weight)
                assertEquals(option.italic, paint.typeface.isItalic || paint.textSkewX < 0f)
            }
        }
        compose.onNodeWithText("Strikeout").performClick()
        compose.onNodeWithText("Underline").performClick()
        chooseFontStyle(NativeFontStyle.Light)
        compose.runOnIdle { assertTrue(style.value.strikeout && style.value.underline) }
    }

    @Test fun nativeBubbleEffectsCombineWithPlusOverridesAndScaleLineHeight() {
        compose.setContent {
            MsngrTheme {
                MsnEmoticonText(
                    text = "[c=#FF0000][u]Native[/u][/c]\nsecond",
                    useOriginalEmoticons = false,
                    messengerPlusEnabled = true,
                    style = MaterialTheme.typography.bodyLarge,
                    nativeStyle = NativeTextStyle(bold = true, italic = true, strikeout = true,
                        colorRgb = 0x123456, size = 20),
                    modifier = Modifier.testTag("styled_bubble"),
                )
            }
        }
        val node = compose.onNodeWithTag("styled_bubble")
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("Native\nsecond", text.text)
        val first = text.spanStyles.single { it.start == 0 }.item
        assertEquals(FontWeight.Bold, first.fontWeight)
        assertEquals(FontStyle.Italic, first.fontStyle)
        assertTrue(first.textDecoration?.contains(TextDecoration.Underline) == true)
        assertTrue(first.textDecoration?.contains(TextDecoration.LineThrough) == true)
        assertEquals(Color.Red, first.color)
        assertEquals(Color(0xFF123456), text.spanStyles.single { it.end == text.length }.item.color)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action(results)
            assertEquals(32.sp, results.single().layoutInput.style.fontSize)
            assertEquals(48.sp, results.single().layoutInput.style.lineHeight)
            assertEquals(2, results.single().lineCount)
        }
    }
}
