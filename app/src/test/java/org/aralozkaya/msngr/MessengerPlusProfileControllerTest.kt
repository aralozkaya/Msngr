package org.aralozkaya.msngr

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerPlusProfileControllerTest {
    @Test
    fun basicStylesUseProfileTagsAndPreserveTheSelectedText() {
        val profile = ProfileEditor()
        listOf(
            MessengerPlusTextCodec.BOLD_CONTROL, MessengerPlusTextCodec.ITALIC_CONTROL,
            MessengerPlusTextCodec.UNDERLINE_CONTROL, MessengerPlusTextCodec.STRIKEOUT_CONTROL,
        ).forEach(profile.controller::toggle)

        val parsed = MessengerPlusTextCodec.parse(profile.value.text)
        assertEquals("one two three", parsed.text)
        assertFalse(profile.value.text.any { it.isISOControl() })
        val selected = parsed.styleSpans.single { it.style.bold }
        assertEquals(MessengerPlusTextRange(4, 7), selected.range)
        assertTrue(selected.style.italic && selected.style.underline && selected.style.strikeout)
        assertEquals("two", profile.selectedText())
    }

    @Test
    fun profileColorsAndGradientsStayInMarkupAndDoNotColorAdjacentText() {
        val profile = ProfileEditor()
        val red = checkNotNull(MessengerPlusTextCodec.paletteColor(4))
        val blue = checkNotNull(MessengerPlusTextCodec.paletteColor(12))
        profile.controller.applyForeground(red)
        assertTrue(profile.controller.applyGradient(red, blue))

        val parsed = MessengerPlusTextCodec.parse(profile.value.text)
        assertEquals("one two three", parsed.text)
        assertTrue(profile.controller.supportsBackgroundFormatting)
        assertFalse(profile.value.text.any { it.isISOControl() })
        assertTrue(profile.value.text.contains("[/c=12]"))
        assertEquals("two", profile.selectedText())
        parsed.styleSpans.filter { it.style.foreground != null }.forEach {
            assertTrue(it.range.start >= 4 && it.range.endExclusive <= 7)
        }
    }

    @Test
    fun removingMarkupRestoresPlainTextSelectionOffsets() {
        val profile = ProfileEditor(TextFieldValue("[b]one two three[/b]", TextRange(7, 10)))
        profile.controller.removeAllFormatting()
        assertEquals(TextFieldValue("one two three", TextRange(4, 7)), profile.value)
        assertFalse(profile.controller.hasFormatting)
    }

    @Test
    fun removingStylesDoesNotActivateQuotedMarkup() {
        val source = "[b]A[n][i]literal[/i][/n]Z[/b]"
        val profile = ProfileEditor(TextFieldValue(source, TextRange(3, source.length - 4)))
        profile.controller.removeAllFormatting()
        val parsed = MessengerPlusTextCodec.parse(profile.value.text)
        assertEquals("A[i]literal[/i]Z", parsed.text)
        assertTrue(parsed.glyphs.all { it.style == MessengerPlusStyle() })
        assertFalse(profile.controller.hasFormatting)
        assertEquals("A[i]literal[/i]Z", profile.selectedText())
    }

    @Test
    fun togglingOffSplitsEnclosingStylesWithoutChangingAdjacentText() {
        val profile = ProfileEditor(TextFieldValue("[b]one two three[/b]", TextRange(7, 10)))
        profile.controller.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
        val parsed = MessengerPlusTextCodec.parse(profile.value.text)
        assertEquals("one two three", parsed.text)
        assertEquals("two", profile.selectedText())
        parsed.glyphs.forEachIndexed { index, glyph ->
            assertEquals(index !in 4..6, glyph.style.bold)
        }
        profile.controller.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
        assertTrue(MessengerPlusTextCodec.parse(profile.value.text).glyphs.all { it.style.bold })
    }

    @Test
    fun turningOffNestedAndBinaryStylesPreservesOtherAttributes() {
        val source = "·#[b]one [i]two[/i] three[/b]·#"
        val start = source.indexOf("two")
        val profile = ProfileEditor(TextFieldValue(source, TextRange(start, start + 3)))
        profile.controller.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
        val parsed = MessengerPlusTextCodec.parse(profile.value.text)
        assertEquals("one two three", parsed.text)
        parsed.glyphs.forEachIndexed { index, glyph ->
            assertEquals(index !in 4..6, glyph.style.bold)
            assertEquals(index in 4..6, glyph.style.italic)
        }
        assertEquals("two", profile.selectedText())
        assertFalse(profile.value.text.any { it.isISOControl() })
    }

    @Test
    fun customBackgroundAndAdaptiveColorsUseNativeMarkup() {
        val profile = ProfileEditor()
        profile.controller.applyBackground(checkNotNull(MessengerPlusTextCodec.colorValue("#123456")))
        profile.controller.applyForeground(checkNotNull(MessengerPlusTextCodec.colorValue("adapt")))
        val parsed = MessengerPlusTextCodec.parse(profile.value.text, adaptiveColor = 0xFF789ABC.toInt())
        assertEquals("one two three", parsed.text)
        parsed.glyphs.forEachIndexed { index, glyph ->
            assertEquals(if (index in 4..6) 0xFF123456.toInt() else null, glyph.style.background?.argb)
            assertEquals(if (index in 4..6) 0xFF789ABC.toInt() else null, glyph.style.foreground?.argb)
        }
    }

    @Test
    fun backgroundGradientKeepsSelectionAndAdjacentTextUnstyled() {
        val profile = ProfileEditor()
        assertTrue(profile.controller.applyGradient(
            checkNotNull(MessengerPlusTextCodec.colorValue("4")),
            checkNotNull(MessengerPlusTextCodec.colorValue("12")),
            background = true,
        ))
        assertEquals("one [a=04]two[/a=12] three", profile.value.text)
        assertEquals("two", profile.selectedText())
        val parsed = MessengerPlusTextCodec.parse(profile.value.text)
        assertEquals(0xFFFF0000.toInt(), parsed.glyphs[4].style.background?.argb)
        assertEquals(0xFF0000FC.toInt(), parsed.glyphs[6].style.background?.argb)
        assertTrue(parsed.glyphs.filterIndexed { index, _ -> index !in 4..6 }.all { it.style.background == null })
    }

    @Test
    fun previewEditsAreIsolatedUntilAppliedToTheTargetProfileField() {
        val name = ProfileEditor()
        val personalMessage = ProfileEditor(TextFieldValue("Available"))
        val original = name.controller.snapshot()
        val preview = ProfileEditor(original)
        preview.controller.toggle(MessengerPlusTextCodec.ITALIC_CONTROL)
        assertEquals(original, name.controller.snapshot())
        assertEquals("Available", personalMessage.value.text)

        name.controller.replaceValue(preview.controller.snapshot())
        assertEquals(preview.value, name.value)
        assertEquals("one [i]two[/i] three", name.value.text)
        assertEquals("Available", personalMessage.value.text)
    }

    private class ProfileEditor(initial: TextFieldValue = TextFieldValue("one two three", TextRange(4, 7))) {
        var value = initial
        val controller = MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)

        init {
            controller.attachProfileValue(initial) { value = it }
        }

        fun selectedText(): String = MessengerPlusTextCodec.plainText(
            value.text.substring(value.selection.min, value.selection.max),
        )
    }
}
