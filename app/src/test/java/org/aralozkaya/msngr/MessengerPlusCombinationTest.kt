package org.aralozkaya.msngr

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerPlusCombinationTest {
    @Test fun everySubsetRestoresPlainTextOutsideItsScopes() {
        MessengerPlusCombinationCases.all.forEach { case ->
            listOf(case.features, case.features.reversed()).distinct().forEach { order ->
                val parsed = MessengerPlusTextCodec.parse("plain " + case.source(order = order) + " tail")
                assertEquals(case.id, "plain AbçZ tail", parsed.text)
                assertTrue(case.id, (parsed.glyphs.take(6) + parsed.glyphs.takeLast(5))
                    .all { it.style == MessengerPlusStyle() })
                assertEquals(case.id, MessengerPlusStyle(), parsed.finalStyle)
            }
        }
    }

    @Test fun quotedClosersMatchNativeScopePairing() {
        listOf("b", "i", "u", "s", "c", "a").forEach { tag ->
            val open = if (tag in listOf("c", "a")) "[$tag=4]" else "[$tag]"
            val source = "${open}A[n][/$tag][/n]B[/$tag]"
            val parsed = MessengerPlusTextCodec.parse(source)
            // Native Plus! pairs the closer inside [n] with the outer opener.
            // Its final unmatched closer remains visible (also checked in WLM chat).
            assertEquals(tag, "AB[/$tag]", parsed.text)
            assertEquals(MessengerPlusStyle(), parsed.glyphs.last().style)
        }
    }

    @Test fun togglingAMixedSelectionAppliesTheDecorationThroughout() {
        listOf('b', 'i', 'u', 's').zip(listOf('\u0002', '\u0005', '\u001F', '\u0006')).forEach { (tag, control) ->
            val source = "[$tag]A[/$tag]B"
            var value = TextFieldValue(source, TextRange(3, source.length))
            val controller = MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
            controller.attachProfileValue(value) { value = it }
            controller.toggle(control)
            val parsed = MessengerPlusTextCodec.parse(value.text)
            assertEquals("AB", parsed.text)
            assertTrue(parsed.glyphs.all { when (tag) {
                'b' -> it.style.bold; 'i' -> it.style.italic; 'u' -> it.style.underline; else -> it.style.strikeout
            } })
        }
    }

    @Test fun selectingAllFormattedTextCanToggleEveryDecorationOff() {
        MessengerPlusCombinationCases.all.forEach { case ->
            listOf('\u0002', '\u0005', '\u001F', '\u0006').forEachIndexed { bit, control ->
                if (case.mask and (1 shl bit) == 0) return@forEachIndexed
                var value = TextFieldValue(case.source(), TextRange(0, case.source().length))
                val controller = MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
                controller.attachProfileValue(value) { value = it }
                controller.toggle(control)
                val styles = MessengerPlusTextCodec.parse(value.text).glyphs.map { it.style }
                assertTrue("${case.id} select-all toggle $bit", styles.all {
                    when (bit) { 0 -> !it.bold; 1 -> !it.italic; 2 -> !it.underline; else -> !it.strikeout }
                })
            }
        }
    }

    @Test fun everySubsetAndNestingOrderSurvivesOutgoingEncoding() {
        assertEquals(256, MessengerPlusCombinationCases.all.size)
        MessengerPlusCombinationCases.all.forEach { case ->
            MessengerPlusCombinationCases.permutations(case.features).forEach { order ->
                val source = case.source(order = order)
                case.assertStyles(source)
                val outgoing = source.toMsnOutgoingMessage(convertTypedShortcuts = false)
                assertEquals("${case.id} $order", source, outgoing.text)
                repeat(4) {
                    assertEquals(source, MessengerPlusTextCodec.normalizeProfileMarkupForChat(outgoing.text))
                    assertEquals(source, outgoing.text.trimMessengerPlusWhitespace().text)
                }
                case.assertStyles(outgoing.text)
                assertEquals("AbçZ", source.toMsnOutgoingMessage(false, messengerPlusEnabled = false).text)
            }
        }
    }

    @Test fun everySubsetSupportsEveryMixtureOfDecorationSyntaxes() {
        var checked = 0
        MessengerPlusCombinationCases.all.forEach { case ->
            val active = listOf("b", "i", "u", "s").filterIndexed { bit, _ -> case.mask and (1 shl bit) != 0 }
            val variants = active.fold(listOf(case.source())) { sources, tag ->
                val binary = when (tag) { "b" -> "\u0002"; "i" -> "\u0005"; "u" -> "\u001F"; else -> "\u0006" }
                val readable = when (tag) { "b" -> "·#"; "i" -> "·&"; "u" -> "·@"; else -> "·'" }
                sources.flatMap { source -> listOf(source,
                    source.replace("[$tag]", binary).replace("[/$tag]", binary),
                    source.replace("[$tag]", readable).replace("[/$tag]", readable)) }
            }
            variants.forEach { source ->
                case.assertStyles(source)
                assertEquals(source, source.toMsnOutgoingMessage(false).text)
                checked++
            }
        }
        assertEquals(4096, checked)
    }

    @Test fun everySubsetCanToggleEachDecorationOffAndBackOn() {
        MessengerPlusCombinationCases.all.forEach { case ->
            listOf('\u0002', '\u0005', '\u001F', '\u0006').forEachIndexed { bit, control ->
                val source = case.source()
                val start = source.indexOf("AbçZ")
                var value = TextFieldValue(source, TextRange(start, start + 4))
                val controller = MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
                controller.attachProfileValue(value) { value = it }
                controller.toggle(control)
                val parsed = MessengerPlusTextCodec.parse(value.text)
                assertEquals(case.id, "AbçZ", parsed.text)
                val expected = case.mask xor (1 shl bit)
                parsed.glyphs.forEach { glyph ->
                    assertEquals(case.id, expected and 1 != 0, glyph.style.bold)
                    assertEquals(case.id, expected and 2 != 0, glyph.style.italic)
                    assertEquals(case.id, expected and 4 != 0, glyph.style.underline)
                    assertEquals(case.id, expected and 8 != 0, glyph.style.strikeout)
                    assertEquals(case.id, case.foreground != 0, glyph.style.foreground != null)
                    assertEquals(case.id, case.background != 0, glyph.style.background != null)
                }
                controller.toggle(control)
                assertEquals("AbçZ", MessengerPlusTextCodec.plainText(value.text))
                assertTrue(case.id, MessengerPlusTextCodec.parse(value.text).glyphs.all {
                    it.style.bold == (case.mask and 1 != 0) && it.style.italic == (case.mask and 2 != 0) &&
                        it.style.underline == (case.mask and 4 != 0) && it.style.strikeout == (case.mask and 8 != 0)
                })
            }
        }
    }
}
