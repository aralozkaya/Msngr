package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.MsnpCircles
import org.aralozkaya.msngr.data.MsnpOfflineMessages
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpFrameCodec
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTextStyleTest {
    private data class CapturedMessage(val name: String, val transaction: Int, val style: NativeTextStyle, val body: String)
    @Test fun originalMessengerFontStylesRoundTripWithoutLosingTheirFaceOrDecorations() {
        assertEquals(listOf("Light", "Light Italic", "Semilight", "Semilight Italic", "Regular", "Italic",
            "Semibold", "Semibold Italic", "Bold", "Bold Italic", "Black", "Black Italic"),
            NativeFontStyle.entries.map { it.label })
        for (option in NativeFontStyle.entries) {
            val style = NativeTextStyle(strikeout = true, underline = true).withFontStyle(option)
            assertEquals(option, style.fontStyle)
            assertEquals(option.weight, style.fontWeight)
            assertEquals(style, NativeTextStyle.fromFormatHeader(style.toFormatHeader()))
        }
    }
    @Test fun completeCapturedFramesDecodeAtEveryByteBoundaryAndMatchTheBuilder() {
        for ((name, transaction, style, body) in listOf(
            CapturedMessage("wlm-bold.txt", 7947, NativeTextStyle(bold = true), "NATIVE-STYLE dialog bold"),
            CapturedMessage("wlm-bisu-orange.txt", 8426, NativeTextStyle(true, true, true, true, 0xFF8000), "NATIVE-STYLE BIUS orange 18pt"),
            CapturedMessage("wlm-font-light.txt", 20430, NativeTextStyle().withFontStyle(NativeFontStyle.Light), "NATIVE-FONT Light"),
            CapturedMessage("wlm-font-semibold.txt", 21125, NativeTextStyle().withFontStyle(NativeFontStyle.Semibold), "NATIVE-FONT Semibold"),
            CapturedMessage("wlm-font-black-italic.txt", 22150, NativeTextStyle().withFontStyle(NativeFontStyle.BlackItalic), "NATIVE-FONT Black Italic"),
            CapturedMessage("wlm-font-semilight-italic.txt", 23801, NativeTextStyle().withFontStyle(NativeFontStyle.SemilightItalic), "NATIVE-FONT Semilight Italic"),
        )) {
            val frame = checkNotNull(javaClass.getResourceAsStream("/native-text-style/$name")).use { it.readBytes() }
            val expected = Msnp18Protocol.switchboardMessage(transaction,
                Msnp18Protocol.textPayload(body, style), acknowledgement = "N")
            assertArrayEquals(frame, MsnpFrameCodec.encode(expected))
            for (split in 0..frame.size) {
                val decoder = MsnpFrameDecoder()
                val parsed = decoder.feed(frame.copyOfRange(0, split)) + decoder.feed(frame.copyOfRange(split, frame.size))
                assertEquals(1, parsed.size)
                assertArrayEquals(expected.payload, parsed.single().payload)
            }
        }
        val received = checkNotNull(javaClass.getResourceAsStream("/native-text-style/wlm-bisu-orange-received.txt")).use { it.readBytes() }
        val command = MsnpFrameDecoder().feed(received).single()
        assertEquals(SwitchboardMimeMessage.Text("NATIVE-STYLE BIUS orange 18pt", NativeTextStyle(true, true, true, true, 0xFF8000)),
            parseSwitchboardMime(checkNotNull(command.payload)))
    }
    @Test fun reproducesCapturedWindowsHeaderAndPreservesUnicodeAndPlusBody() {
        val style = NativeTextStyle(true, true, true, true, 0xFF8000)
        val header = "FN=Segoe%20UI; EF=BISU; CO=80ff; CS=0; PF=22"
        assertEquals(header, style.toFormatHeader())
        assertEquals(style, NativeTextStyle.fromFormatHeader(header))
        val text = "[c=4]Türkçe[/c] :)"
        assertEquals(SwitchboardMimeMessage.Text(text, style), parseSwitchboardMime(Msnp18Protocol.textPayload(text, style)))
        for (payload in listOf(
            MsnpCircles.message(1, "circle@live.com", "sender@example.com", "endpoint", text, style = style).payload,
            MsnpOfflineMessages.send(1, "peer@example.com", text, style).payload,
        )) {
            val wire = checkNotNull(payload).toString(Charsets.UTF_8)
            assertTrue(wire.contains("X-MMS-IM-Format: $header\r\n"))
            assertTrue(wire.endsWith(text))
        }
    }

    @Test fun allEffectCombinationsAndRgbBytesRoundTripWithoutSizeOnTheWire() {
        for (mask in 0..15) for (color in listOf(0, 0xFF, 0xFF0000, 0x123456, 0xFFFFFF)) {
            val style = NativeTextStyle(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0, color)
            assertEquals(style, NativeTextStyle.fromFormatHeader(style.toFormatHeader()))
            assertEquals(style.toFormatHeader(), style.copy(size = 72).toFormatHeader())
        }
    }

    @Test fun missingOrMalformedFieldsFallBackSafely() {
        assertEquals(NativeTextStyle(), NativeTextStyle.fromFormatHeader(null))
        assertEquals(NativeTextStyle(), NativeTextStyle.fromFormatHeader("CO=-1; EF=; CS=bogus"))
        assertEquals(NativeTextStyle(), NativeTextStyle.fromFormatHeader("CO=1000000"))
        assertEquals(NativeTextStyle(bold = true, colorRgb = 0xFF0000),
            NativeTextStyle.fromFormatHeader("fn=x; ef=b; co=ff; unknown=value"))
    }
}
