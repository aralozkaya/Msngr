package org.aralozkaya.msngr

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Outputs captured from MsgPlus.RemoveFormatCodes in the installed Plus! 5.02.712. */
@RunWith(Parameterized::class)
class MessengerPlusNativeCompatibilityTest(
    private val name: String,
    private val source: String,
    private val expected: String,
) {
    @Test
    fun matchesNativePlainTextAndSourceMapping() {
        val parsed = MessengerPlusTextCodec.parse(source)
        assertEquals(name, expected, parsed.text)
        assertEquals(expected.length, parsed.textOffsetForSource(source.length))
        assertEquals(expected, parsed.glyphs.joinToString("") { it.value })
        parsed.glyphs.forEach { glyph ->
            assertEquals(glyph.value, source.substring(glyph.sourceStart, glyph.sourceEndExclusive))
        }
        // Normalization runs at several layers of the send pipeline.
        val wire = MessengerPlusTextCodec.normalizeProfileMarkupForChat(source)
        assertEquals(wire, MessengerPlusTextCodec.normalizeProfileMarkupForChat(wire))
        assertEquals(expected, MessengerPlusTextCodec.plainText(wire))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> = checkNotNull(
            MessengerPlusNativeCompatibilityTest::class.java.getResourceAsStream(
                "/messenger-plus/native-5.02.712-plain.tsv",
            ),
        ).bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith('#') }.map { line ->
                val columns = line.split('\t')
                arrayOf(columns[0], decode(columns[1]), decode(columns[2]))
            }.toList()
        }

        private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }
}
