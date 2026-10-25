package org.aralozkaya.msngr

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessengerPlusCombinationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeComposerSelectAllCanRemoveEveryCombinedDecoration() {
        compose.runOnUiThread {
            val editor = MsnEditText(compose.activity)
            val controller = MessengerPlusEditorController()
            controller.attach(editor)
            MessengerPlusCombinationCases.all.forEach { case ->
                listOf('\u0002', '\u0005', '\u001F', '\u0006').forEachIndexed { bit, control ->
                    if (case.mask and (1 shl bit) == 0) return@forEachIndexed
                    editor.setExternalValue(case.source())
                    controller.selectAll()
                    controller.toggle(control)
                    val parsed = MessengerPlusTextCodec.parse(editor.text.toString())
                    assertEquals(case.id, "AbçZ", parsed.text)
                    parsed.glyphs.forEach { glyph ->
                        val expected = case.mask xor (1 shl bit)
                        assertEquals(case.id, expected and 1 != 0, glyph.style.bold)
                        assertEquals(case.id, expected and 2 != 0, glyph.style.italic)
                        assertEquals(case.id, expected and 4 != 0, glyph.style.underline)
                        assertEquals(case.id, expected and 8 != 0, glyph.style.strikeout)
                    }
                }
            }
        }
    }

    @Test fun everySubsetRendersInTheComposerAndBothBubbleDirections() {
        val source = mutableStateOf("")
        val author = mutableStateOf(MessageAuthor.Contact)
        compose.setContent {
            MaterialTheme {
                Column {
                    MessageBubble(ChatMessage(1, "test", author.value, source.value, 0), false)
                    MsnEmoticonEditor(source.value, { source.value = it }, false, false)
                }
            }
        }
        MessengerPlusCombinationCases.all.forEach { case ->
            listOf(case.features, case.features.reversed()).distinct().forEach { order ->
                val raw = case.source(order = order)
                compose.runOnIdle { source.value = raw }
                onView(withContentDescription("Message input")).check { view, error ->
                    if (error != null) throw error
                    assertPlusEditorStyles(case, raw, (view as MsnEditText).text)
                }
                listOf(MessageAuthor.Contact, MessageAuthor.Me).forEach { direction ->
                    compose.runOnIdle { author.value = direction }
                    val annotated = compose.onNode(hasText("AbçZ") and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                        .fetchSemanticsNode().config[SemanticsProperties.Text].single()
                    assertPlusBubbleStyles(case, raw, annotated)
                }
            }
        }
    }
}

internal fun assertPlusEditorStyles(case: MessengerPlusCombinationCases.Case, source: String, text: Spanned) {
    assertEquals(case.id, source, text.toString())
    val start = source.indexOf("AbçZ")
    for (offset in start until start + 4) {
        val typeface = text.getSpans(offset, offset + 1, StyleSpan::class.java)
            .fold(0) { style, span -> style or span.style }
        assertEquals("${case.id} bold", case.mask and 1 != 0, typeface and Typeface.BOLD != 0)
        assertEquals("${case.id} italic", case.mask and 2 != 0, typeface and Typeface.ITALIC != 0)
        assertEquals("${case.id} underline", case.mask and 4 != 0,
            text.getSpans(offset, offset + 1, UnderlineSpan::class.java).isNotEmpty())
        assertEquals("${case.id} strike", case.mask and 8 != 0,
            text.getSpans(offset, offset + 1, StrikethroughSpan::class.java).isNotEmpty())
        assertEquals("${case.id} foreground", case.expectedColor(source, offset, false),
            text.getSpans(offset, offset + 1, ForegroundColorSpan::class.java).singleOrNull()?.foregroundColor)
        assertEquals("${case.id} background", case.expectedColor(source, offset, true),
            text.getSpans(offset, offset + 1, BackgroundColorSpan::class.java).singleOrNull()?.backgroundColor)
    }
}

internal fun assertPlusBubbleStyles(
    case: MessengerPlusCombinationCases.Case,
    source: String,
    text: androidx.compose.ui.text.AnnotatedString,
) {
    val start = text.text.indexOf("AbçZ")
    for (offset in start until start + 4) {
        val span = text.spanStyles.singleOrNull { offset in it.start until it.end }?.item
        assertEquals("${case.id} bold", case.mask and 1 != 0,
            span?.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold)
        assertEquals("${case.id} italic", case.mask and 2 != 0,
            span?.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic)
        assertEquals("${case.id} underline", case.mask and 4 != 0,
            span?.textDecoration?.contains(androidx.compose.ui.text.style.TextDecoration.Underline) == true)
        assertEquals("${case.id} strike", case.mask and 8 != 0,
            span?.textDecoration?.contains(androidx.compose.ui.text.style.TextDecoration.LineThrough) == true)
        val sourceOffset = source.indexOf("AbçZ") + offset - start
        val expectedForeground = case.expectedColor(source, sourceOffset, false)
        val expectedBackground = case.expectedColor(source, sourceOffset, true)
        assertEquals("${case.id} foreground", expectedForeground?.let { androidx.compose.ui.graphics.Color(it) }
            ?: androidx.compose.ui.graphics.Color.Unspecified, span?.color ?: androidx.compose.ui.graphics.Color.Unspecified)
        assertEquals("${case.id} background", expectedBackground?.let { androidx.compose.ui.graphics.Color(it) }
            ?: androidx.compose.ui.graphics.Color.Unspecified, span?.background ?: androidx.compose.ui.graphics.Color.Unspecified)
    }
}
