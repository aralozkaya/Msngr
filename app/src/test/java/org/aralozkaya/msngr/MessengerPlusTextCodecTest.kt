package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerPlusTextCodecTest {
    @Test
    fun featureDefaultsToEnabled() {
        assertTrue(MessengerPlusSettings().enabled)
    }

    @Test
    fun disabledPresentationKeepsVisibleTextWithoutFormattingStyles() {
        val parsed = MessengerPlusTextCodec.parseForPresentation(
            "[b][c=4]Plain[/c][/b]",
            formattingEnabled = false,
        )

        assertEquals("Plain", parsed.text)
        assertTrue(parsed.styleSpans.isEmpty())
        assertTrue(parsed.sourceStyleSpans.isEmpty())
        assertEquals(MessengerPlusStyle(), parsed.finalStyle)
    }

    @Test
    fun rawPresentationPreservesCodesAndSourceOffsets() {
        val source = "[b]Hi[/b] \u00034red\u0003 🙂 :)"
        val parsed = MessengerPlusTextCodec.parseForPresentation(
            source, formattingEnabled = false, hideControlCharacters = false,
        )
        assertEquals(source, parsed.text)
        assertTrue(parsed.styleSpans.isEmpty())
        assertTrue(parsed.sourceStyleSpans.isEmpty())
        assertTrue(parsed.controlRanges.isEmpty())
        val shortcut = MsnEmoticonRange(source.length - 2, source.length)
        assertEquals(shortcut, parsed.textRangeForSource(shortcut))
        assertEquals(source, parsed.glyphs.joinToString("") { it.value })
    }

    @Test
    fun renderingAlwaysHidesCodesEvenWhenHidingPreferenceIsOff() {
        val parsed = MessengerPlusTextCodec.parseForPresentation(
            "[b]Bold[/b]", formattingEnabled = true, hideControlCharacters = false,
        )
        assertEquals("Bold", parsed.text)
        assertTrue(parsed.styleSpans.single().style.bold)
        assertTrue(MessengerPlusSettings(hideControlCharacters = false).shouldHideControlCharacters)
        assertFalse(MessengerPlusSettings(enabled = false, hideControlCharacters = false).shouldHideControlCharacters)
        assertTrue(MessengerPlusSettings(enabled = false).shouldHideControlCharacters)
    }

    @Test
    fun classicPaletteMatchesMessengerPlusIndicesExactly() {
        val native = checkNotNull(javaClass.getResourceAsStream("/messenger-plus/native-5.02.712-palette.json"))
            .bufferedReader().use { it.readText() }
        val expected = Regex("[0-9A-F]{6}").findAll(native).map {
            (0xFF000000L or it.value.toLong(16)).toInt()
        }.toList()
        assertEquals(expected, MessengerPlusTextCodec.palette.map(MessengerPlusPaletteColor::argb))
        assertEquals((0..67).map { it.toString().padStart(2, '0') },
            MessengerPlusTextCodec.palette.map(MessengerPlusPaletteColor::wireToken))
    }

    @Test
    fun binaryStyleControlsToggleIndependently() {
        val parsed = MessengerPlusTextCodec.parse(
            "\u0002Bold \u0005and italic\u0005\u0002 plain " +
                "\u001Funder\u001F \u0006strike\u0006",
        )

        assertEquals("Bold and italic plain under strike", parsed.text)
        assertStyle(parsed, offset = 0, bold = true)
        assertStyle(parsed, offset = 5, bold = true, italic = true)
        assertStyle(parsed, offset = 16)
        assertStyle(parsed, offset = 22, underline = true)
        assertStyle(parsed, offset = 28, strikeout = true)
    }

    @Test
    fun indexedHexAndBackgroundColorsDecodeWithoutChangingGeometry() {
        val parsed = MessengerPlusTextCodec.parse(
            "\u0002\u000304,01Red\u0003#00FF00Green\u0003Bold\u0002",
        )

        assertEquals("RedGreenBold", parsed.text)
        assertStyle(
            parsed,
            offset = 0,
            bold = true,
            foreground = 0xFFFF0000.toInt(),
            background = 0xFF000000.toInt(),
        )
        assertStyle(
            parsed,
            offset = 3,
            bold = true,
            foreground = 0xFF00FF00.toInt(),
            background = 0xFF000000.toInt(),
        )
        assertStyle(parsed, offset = 8, bold = true)
    }

    @Test
    fun isolatedColorControlResetsOnlyForegroundAndBackground() {
        val parsed = MessengerPlusTextCodec.parse("\u0002\u000304,01A\u0003B\u0002")

        assertStyle(
            parsed,
            offset = 0,
            bold = true,
            foreground = 0xFFFF0000.toInt(),
            background = 0xFF000000.toInt(),
        )
        assertStyle(parsed, offset = 1, bold = true)
    }

    @Test
    fun customForegroundCanCoexistWithAndRestoreAnIndexedBackground() {
        val customForeground = checkNotNull(MessengerPlusColorValue.fromHex("#123456"))
        val style = MessengerPlusStyle(
            bold = true,
            foreground = customForeground,
            background = checkNotNull(MessengerPlusTextCodec.paletteColor(4)),
        )
        val wire = MessengerPlusTextCodec.resetColorsControl() +
            MessengerPlusTextCodec.colorsFromReset(style) + "A"
        val parsed = MessengerPlusTextCodec.parse(wire)

        assertEquals("\u0003\u0003#123456,04A", wire)
        assertStyle(
            parsed,
            offset = 0,
            foreground = 0xFF123456.toInt(),
            background = 0xFFFF0000.toInt(),
        )
    }

    @Test
    fun profileTagsAreCaseInsensitiveAndPreservedOnTheWire() {
        val source = "[B][i][u][s][c=4]Plus[/c][/s][/u][/i][/b]"
        val parsed = MessengerPlusTextCodec.parse(source)

        assertEquals("Plus", parsed.text)
        assertStyle(
            parsed,
            offset = 0,
            bold = true,
            italic = true,
            underline = true,
            strikeout = true,
            foreground = 0xFFFF0000.toInt(),
        )
        assertEquals(
            source, MessengerPlusTextCodec.normalizeProfileMarkupForChat(source),
        )
    }

    @Test
    fun invalidColorArgumentsAreHiddenAndOutOfRangePaletteIndicesAreBlack() {
        val parsed = MessengerPlusTextCodec.parse("[c=#12]literal[/c] \u000399Default")

        assertEquals("literal Default", parsed.text)
        assertEquals(0xFF000000.toInt(), styleAt(parsed, parsed.text.lastIndex).foreground?.argb)
    }

    @Test
    fun gradientsUseNativeClosingColorAndSourcePositions() {
        val profile = MessengerPlusTextCodec.parse("[c=#000000]AA[b]BB[/b]CC[/c=#FFFFFF]")
        assertEquals("AABBCC", profile.text)
        // Native counts the three characters in [b] and four in [/b] in the ramp.
        assertEquals(listOf(0, 21, 106, 127, 233, 255), profile.glyphs.map { it.style.foreground!!.argb and 255 })
        assertEquals(listOf(false, false, true, true, false, false), profile.glyphs.map { it.style.bold })
        val wire = MessengerPlusTextCodec.gradient("ABC", color("4"), color("12"))
        assertEquals("[c=04]ABC[/c=12]", wire)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF7F007E.toInt(), 0xFF0000FC.toInt()),
            MessengerPlusTextCodec.parse(wire).glyphs.map { it.style.foreground?.argb })
    }

    @Test
    fun oneCharacterGradientUsesStartAndUnicodeUsesUtf16Positions() {
        assertEquals(color("4").argb,
            MessengerPlusTextCodec.parse("[c=4]X[/c=12]").glyphs.single().style.foreground?.argb)
        val parsed = MessengerPlusTextCodec.parse("[c=#000000]A😀B[/c=#FFFFFF]")
        assertEquals(3, parsed.glyphs.size)
        assertEquals(listOf(0, 85, 255), parsed.glyphs.map { it.style.foreground!!.argb and 255 })
    }

    @Test
    fun namedAndBackgroundOnlyControlsUseNativeColors() {
        val expected = mapOf(
            "white" to "FFFFFF", "black" to "000000", "red" to "FF0000", "yellow" to "FFFF00",
            "pink" to "FFC0CB", "green" to "008000", "orange" to "FFA500", "purple" to "800080",
            "blue" to "0000FF", "beige" to "F5F5DC", "brown" to "A52A2A", "teal" to "008080",
            "navy" to "000080", "maroon" to "800000", "limegreen" to "32CD32", "gray" to "808080",
        )
        expected.forEach { (name, rgb) ->
            val parsed = MessengerPlusTextCodec.parse("[c=$name]A[/c]")
            assertEquals(name, color("#$rgb").argb, parsed.glyphs.single().style.foreground?.argb)
        }
        val parsed = MessengerPlusTextCodec.parse("\u00034A\u0003,#123456B\u0003C")
        assertEquals(color("4").argb, parsed.glyphs[1].style.foreground?.argb)
        assertEquals(0xFF123456.toInt(), parsed.glyphs[1].style.background?.argb)
        assertNull(parsed.glyphs[2].style.background)
        assertEquals("\u0003#008000", MessengerPlusTextCodec.colorControl(color("green")))
    }

    @Test
    fun nestedColorsRestoreOuterColorsAndBackgroundIndependently() {
        val parsed = MessengerPlusTextCodec.parse("[a=8][c=4]A[c=12]B[/c]C[/c]D[/a]E")
        assertEquals(listOf(color("4").argb, color("12").argb, color("4").argb, null, null),
            parsed.glyphs.map { it.style.foreground?.argb })
        assertEquals(listOf(color("8").argb, color("8").argb, color("8").argb, color("8").argb, null),
            parsed.glyphs.map { it.style.background?.argb })
    }

    @Test
    fun binaryAndBbcodeStyleLayersHaveIndependentResets() {
        val parsed = MessengerPlusTextCodec.parse("[b]A\u0002B\u0002C\u000fD[/b]E")
        assertEquals(listOf(true, true, true, true, false), parsed.glyphs.map { it.style.bold })
        val gradient = MessengerPlusTextCodec.parse("[c=4]AB\u00039CD\u0003EF[/c=12]")
        assertEquals(color("9").argb, gradient.glyphs[2].style.foreground?.argb)
        assertEquals(color("9").argb, gradient.glyphs[3].style.foreground?.argb)
        assertEquals(color("12").argb, gradient.glyphs.last().style.foreground?.argb)
    }

    @Test
    fun binaryColorsOverrideLaterTagsUntilResetAndSurviveClosingTags() {
        val parsed = MessengerPlusTextCodec.parse("\u00034A[c=12]B\u0003C[/c]D")
        assertEquals(listOf(color("4").argb, color("4").argb, color("12").argb, null),
            parsed.glyphs.map { it.style.foreground?.argb })
        val persistent = MessengerPlusTextCodec.parse("[c=4]A\u000312B[/c]C\u0003D")
        assertEquals(listOf(color("4").argb, color("12").argb, color("12").argb, null),
            persistent.glyphs.map { it.style.foreground?.argb })
        val bold = MessengerPlusTextCodec.parse("[b]A\u0002B[/b]C\u0002D")
        assertEquals(listOf(true, true, true, false), bold.glyphs.map { it.style.bold })
    }

    @Test
    fun foregroundAndBackgroundGradientsCoexist() {
        val parsed = MessengerPlusTextCodec.parse("[a=#000000][c=4]ABC[/c=12][/a=#FFFFFF]")
        assertEquals("ABC", parsed.text)
        assertEquals(color("4").argb, parsed.glyphs.first().style.foreground?.argb)
        assertEquals(color("12").argb, parsed.glyphs.last().style.foreground?.argb)
        assertTrue(parsed.glyphs.all { it.style.background != null })
        assertTrue(parsed.glyphs.zipWithNext().all { (a, b) ->
            (a.style.background!!.argb and 255) < (b.style.background!!.argb and 255)
        })
    }

    @Test
    fun adaptiveColorAndGradientsFollowTheSuppliedTheme() {
        val parsed = MessengerPlusTextCodec.parse("[c=adapt]ABC[/c=#FFFFFF]", adaptiveColor = 0xFF123456.toInt())
        assertEquals(0xFF123456.toInt(), parsed.glyphs.first().style.foreground?.argb)
        assertEquals(0xFFFFFFFF.toInt(), parsed.glyphs.last().style.foreground?.argb)
    }

    @Test
    fun literalsAndUnmatchedTagsRemainVisible() {
        val source = "[g=1,5]text[/g](!s12) [b]orphan"
        assertEquals(source, MessengerPlusTextCodec.plainText(source))
        val quoted = MessengerPlusTextCodec.parse("[n][b]literal[/b][/n]")
        assertEquals("[b]literal[/b]", quoted.text)
        assertTrue(quoted.glyphs.none { it.style.bold })
    }

    @Test
    fun sourceMappingAndCaretStylesUseCompletePairs() {
        val source = "[b]Hi😀[/b]X"
        val parsed = MessengerPlusTextCodec.parse(source)
        assertEquals(0, parsed.textOffsetForSource(3))
        assertEquals(4, parsed.textOffsetForSource(7))
        assertTrue(MessengerPlusTextCodec.styleAt(source, 3).bold)
        assertTrue(MessengerPlusTextCodec.styleAt(source, 7).bold)
        assertFalse(MessengerPlusTextCodec.styleAt(source, 11).bold)
    }

    private fun color(token: String) = checkNotNull(MessengerPlusTextCodec.colorValue(token))

    @Test
    fun visibleWhitespaceTrimKeepsFormattingControlsAndMapsEmoticonRanges() {
        val source = "  \u0002Hi :)\u0002  "
        val emoticonStart = source.indexOf(":)")
        val trimmed = MessengerPlusTextCodec.trimVisibleWhitespace(source)

        assertEquals("\u0002Hi :)\u0002", trimmed.text)
        assertEquals(
            MsnEmoticonRange(4, 6),
            trimmed.map(MsnEmoticonRange(emoticonStart, emoticonStart + 2)),
        )
        assertTrue(MessengerPlusTextCodec.hasVisibleContent(source))
        assertFalse(MessengerPlusTextCodec.hasVisibleContent(" \u0002\u0003 "))
    }

    private fun assertStyle(
        parsed: MessengerPlusParsedText,
        offset: Int,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikeout: Boolean = false,
        foreground: Int? = null,
        background: Int? = null,
    ) {
        val actual = styleAt(parsed, offset)
        assertEquals(bold, actual.bold)
        assertEquals(italic, actual.italic)
        assertEquals(underline, actual.underline)
        assertEquals(strikeout, actual.strikeout)
        assertEquals(foreground, actual.foreground?.argb)
        assertEquals(background, actual.background?.argb)
    }

    private fun styleAt(parsed: MessengerPlusParsedText, offset: Int): MessengerPlusStyle =
        parsed.styleSpans.single { offset in it.range.start until it.range.endExclusive }.style
}
