package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.FileConversationHistoryStore
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.SwitchboardChunkAssembler
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnInkCodec
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs

class MsnInkCodecTest {

    @Test
    fun decodesCapturedWlmThreeStrokeFineNormalBoldIsfPacket() {
        val bytes = requireNotNull(MsnBase64.decode(CAPTURED_MSG1_3STROKES_ISF))
        val drawing = requireNotNull(MsnInkCodec.decodeIsf(bytes))

        assertEquals(3, drawing.strokes.size)
        // Stroke 0: Blue (0x0000FF), Fine (10)
        assertEquals(0x0000FF, drawing.strokes[0].colorRgb)
        assertEquals(10, drawing.strokes[0].widthHimetric)
        assertEquals(11, drawing.strokes[0].points.size)
        // Stroke 1: Red (0xFF0000), Normal (100)
        assertEquals(0xFF0000, drawing.strokes[1].colorRgb)
        assertEquals(100, drawing.strokes[1].widthHimetric)
        assertEquals(11, drawing.strokes[1].points.size)
        // Stroke 2: Blue (0x0000FF), Bold (200)
        assertEquals(0x0000FF, drawing.strokes[2].colorRgb)
        assertEquals(200, drawing.strokes[2].widthHimetric)
        assertEquals(11, drawing.strokes[2].points.size)
    }

    @Test
    fun decodesCapturedWlmAllSixteenPaletteColorsIsfPacket() {
        val bytes = requireNotNull(MsnBase64.decode(CAPTURED_MSG2_16COLORS_ISF))
        val drawing = requireNotNull(MsnInkCodec.decodeIsf(bytes))

        assertEquals(16, drawing.strokes.size)
        val decodedColors = drawing.strokes.map(InkStroke::colorRgb).toSet()
        val expectedWlmColors = WLM_INK_COLORS.map(WlmInkColorOption::rgb).toSet()
        assertEquals(expectedWlmColors, decodedColors)
    }

    @Test
    fun decodesCapturedWlmGorillaPackedAndSingleBlockIsfPackets() {
        val msg4Bytes = requireNotNull(MsnBase64.decode(CAPTURED_MSG4_PURPLE_TEAL_ISF))
        val msg4 = requireNotNull(MsnInkCodec.decodeIsf(msg4Bytes))
        assertEquals(2, msg4.strokes.size)
        assertEquals(0x800080, msg4.strokes[0].colorRgb)
        assertEquals(100, msg4.strokes[0].widthHimetric)
        assertEquals(0x008080, msg4.strokes[1].colorRgb)
        assertEquals(200, msg4.strokes[1].widthHimetric)

        val msg5Bytes = requireNotNull(MsnBase64.decode(CAPTURED_MSG5_REVERSE_GREEN_ISF))
        val msg5 = requireNotNull(MsnInkCodec.decodeIsf(msg5Bytes))
        assertEquals(1, msg5.strokes.size)
        assertEquals(0x008000, msg5.strokes[0].colorRgb)
        assertEquals(200, msg5.strokes[0].widthHimetric)
        assertEquals(13, msg5.strokes[0].points.size)
    }

    @Test
    fun decodesCapturedWlmFortifiedGifPacket() {
        val gifBytes = requireNotNull(MsnBase64.decode(CAPTURED_MSG6_FORTIFIED_GIF))
        val drawing = requireNotNull(MsnInkCodec.decodeGif(gifBytes))

        assertEquals(1, drawing.strokes.size)
        assertEquals(0xFF0000, drawing.strokes[0].colorRgb)
        assertEquals(100, drawing.strokes[0].widthHimetric)
        assertEquals(11, drawing.strokes[0].points.size)
    }

    @Test
    fun roundTripsIsfAndFortifiedGifPreservingStrokesColorsWidthsAndGeometry() {
        val original = InkDrawing(
            canvasWidthPx = 411f,
            canvasHeightPx = 180f,
            strokes = listOf(
                InkStroke(
                    points = listOf(
                        InkPoint(30f, 40f),
                        InkPoint(65f, 42f),
                        InkPoint(110f, 55f),
                        InkPoint(165f, 80f),
                        InkPoint(220f, 60f),
                    ),
                    colorRgb = 0x000000,
                    widthHimetric = InkStroke.FINE_PEN_WIDTH_HIMETRIC,
                ),
                InkStroke(
                    points = listOf(
                        InkPoint(45f, 95f),
                        InkPoint(90f, 120f),
                        InkPoint(150f, 110f),
                        InkPoint(210f, 135f),
                    ),
                    colorRgb = 0xFF00FF,
                    widthHimetric = InkStroke.NORMAL_PEN_WIDTH_HIMETRIC,
                ),
                InkStroke(
                    points = (0..30).map { i ->
                        InkPoint(20f + i * 9f, 50f + (i % 5) * 14f)
                    },
                    colorRgb = 0x008080,
                    widthHimetric = InkStroke.BOLD_PEN_WIDTH_HIMETRIC,
                ),
            ),
        )

        val isfBytes = MsnInkCodec.encodeIsf(original)
        val decodedIsf = requireNotNull(MsnInkCodec.decodeIsf(isfBytes))
        assertEquals(original.strokes.size, decodedIsf.strokes.size)
        for (i in original.strokes.indices) {
            val expected = original.strokes[i]
            val actual = decodedIsf.strokes[i]
            assertEquals(expected.colorRgb, actual.colorRgb)
            assertEquals(expected.widthHimetric, actual.widthHimetric)
            assertEquals(expected.points.size, actual.points.size)
            for (j in 1 until expected.points.size) {
                val expectedDx = expected.points[j].x - expected.points[0].x
                val expectedDy = expected.points[j].y - expected.points[0].y
                val actualDx = actual.points[j].x - actual.points[0].x
                val actualDy = actual.points[j].y - actual.points[0].y
                assertTrue(abs(expectedDx - actualDx) <= 1.5f)
                assertTrue(abs(expectedDy - actualDy) <= 1.5f)
            }
        }

        val gifBytes = MsnInkCodec.encodeFortifiedGif(original)
        assertEquals("GIF89a", String(gifBytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), gifBytes.last())
        val decodedGif = requireNotNull(MsnInkCodec.decodeGif(gifBytes))
        assertEquals(original.strokes.size, decodedGif.strokes.size)
        for (i in original.strokes.indices) {
            assertEquals(original.strokes[i].colorRgb, decodedGif.strokes[i].colorRgb)
            assertEquals(original.strokes[i].widthHimetric, decodedGif.strokes[i].widthHimetric)
            assertEquals(original.strokes[i].points.size, decodedGif.strokes[i].points.size)
        }
    }

    @Test
    fun splitsAndReassemblesMultiChunkSwitchboardInkMessages() {
        val denseStrokes = (0 until 18).map { s ->
            InkStroke(
                points = (0..45).map { p ->
                    InkPoint(
                        x = 15f + p * 7f + (s * 3f),
                        y = 20f + ((p * 17 + s * 13) % 110).toFloat(),
                    )
                },
                colorRgb = WLM_INK_COLORS[s % WLM_INK_COLORS.size].rgb,
                widthHimetric = when (s % 3) {
                    0 -> InkStroke.FINE_PEN_WIDTH_HIMETRIC
                    1 -> InkStroke.NORMAL_PEN_WIDTH_HIMETRIC
                    else -> InkStroke.BOLD_PEN_WIDTH_HIMETRIC
                },
            )
        }
        val drawing = InkDrawing(
            canvasWidthPx = 411f,
            canvasHeightPx = 180f,
            strokes = denseStrokes,
        )
        val payloads = Msnp18Protocol.inkPayloads(
            drawing = drawing,
            messageId = "B402CDCC-71C9-4CA6-8C9E-F9E4A0B0D5C8",
        )
        assertTrue("Expected multi-chunk ink message", payloads.size >= 2)
        val firstChunkText = payloads.first().toString(Charsets.UTF_8)
        assertTrue(firstChunkText.contains("Content-Type: application/x-ms-ink\r\n"))
        assertTrue(firstChunkText.contains("Message-ID: {B402CDCC-71C9-4CA6-8C9E-F9E4A0B0D5C8}\r\n"))
        assertTrue(firstChunkText.contains("Chunks: ${payloads.size}\r\n"))
        val secondChunkText = payloads[1].toString(Charsets.UTF_8)
        assertTrue(secondChunkText.startsWith("Message-ID: {B402CDCC-71C9-4CA6-8C9E-F9E4A0B0D5C8}\r\nChunk: 1\r\n\r\n"))

        val assembler = SwitchboardChunkAssembler()
        for (i in 0 until payloads.lastIndex) {
            assertNull(assembler.feed("peer@example.com", payloads[i]))
        }
        val reassembled = requireNotNull(assembler.feed("peer@example.com", payloads.last()))
        val parsed = parseSwitchboardMime(reassembled)
        assertTrue(parsed is SwitchboardMimeMessage.Ink)
        val decoded = (parsed as SwitchboardMimeMessage.Ink).drawing
        assertEquals(drawing.strokes.size, decoded.strokes.size)
    }

    @Test
    fun handwritingCanvasStateSupportsCustomizationsEraserModesAndUndoRedo() {
        val state = HandwritingCanvasState()
        state.penSize = WlmPenSize.Bold
        state.selectedColorRgb = 0x800080 // Purple
        state.gridStyle = WlmGridStyle.LargeGrid

        state.commitStroke(
            listOf(
                InkPoint(10f, 50f),
                InkPoint(50f, 50f),
                InkPoint(90f, 50f),
                InkPoint(130f, 50f),
            ),
        )
        assertEquals(1, state.strokes.size)
        assertTrue(state.canUndo)

        // Point eraser (Small) splits the horizontal stroke in the middle
        state.activeTool = WlmHandwritingTool.Eraser
        state.eraserMode = WlmEraserMode.Small
        state.applyEraserPath(listOf(InkPoint(70f, 50f)))
        assertEquals(2, state.strokes.size)

        // Whole-stroke eraser removes the touched stroke segment only
        state.eraserMode = WlmEraserMode.Stroke
        state.applyEraserPath(listOf(InkPoint(15f, 50f)))
        assertEquals(1, state.strokes.size)

        // Clear all clears remaining strokes and resets tool to Pen
        state.clearAll()
        assertEquals(0, state.strokes.size)
        assertEquals(WlmHandwritingTool.Pen, state.activeTool)

        // Undo restores step-by-step; Redo re-applies
        state.undo()
        assertEquals(1, state.strokes.size)
        state.undo()
        assertEquals(2, state.strokes.size)
        state.redo()
        assertEquals(1, state.strokes.size)
    }

    @Test
    fun conversationHistoryStorePersistsAndRestoresInkMessages() {
        val directory = Files.createTempDirectory("msngr-ink-history").toFile()
        try {
            val drawing = requireNotNull(
                MsnInkCodec.decodeIsf(requireNotNull(MsnBase64.decode(CAPTURED_MSG1_3STROKES_ISF))),
            )
            val message = ChatMessage(
                id = 42L,
                contactId = "selin",
                author = MessageAuthor.Me,
                text = "Handwriting",
                timestampMillis = 1_700_000_000_000L,
                inkDrawing = drawing,
            )

            val writer = FileConversationHistoryStore(directory)
            writer.selectAccount("owner@example.com")
            writer.merge(mapOf("selin" to listOf(message)))

            val reader = FileConversationHistoryStore(directory)
            reader.selectAccount("owner@example.com")
            reader.loadLatest("selin")
            val restored = reader.messages.value["selin"].orEmpty().single()
            assertTrue(restored.isInk)
            val restoredDrawing = requireNotNull(restored.inkDrawing)
            assertEquals(drawing.strokes.size, restoredDrawing.strokes.size)
            assertEquals(drawing.strokes.map(InkStroke::colorRgb), restoredDrawing.strokes.map(InkStroke::colorRgb))
            assertEquals(drawing.strokes.map(InkStroke::widthHimetric), restoredDrawing.strokes.map(InkStroke::widthHimetric))
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val CAPTURED_MSG1_3STROKES_ISF =
            "AKQBHAOAgAQdA5oFSAIdCUgRRICA/AdFCgdIEUT/AUVkCkgRRICA/AdFyAEZFDIIAIAoAjiGK0IzCADAFgK3bStCFauq00GrqtNBAAAAPgAAAL8KGQuC/JH4r93ZNzcAh5hDa3crvdrvdrvdruAJAQoYC4P8Ad939ZE1IIeXRGk1ar1ar1ar1argCQIKGQuD/CJfg8fDqRNSh5dEaTVqvVqvVqvVquA="

        const val CAPTURED_MSG2_16COLORS_ISF =
            "AJIFHAOAgAQdA+oHeAKYAQdIEUT/AUVkBEgRRQoJSBFEwICBAkVkCkgRRICBggRFyAEJSBFE////B0UKCUgRRICAgARFZAlIEUSAgAJFyAEJSBFEgICCBEUKB0gRRIABRWQKSBFEgIGABEXIAQhIEUSAgQJFCglIEUSAgPwHRWQJSBFEgP4DRcgBCUgRRID+/wdFCgpIEUT/gfwHRcgBCEgRRP//A0UKGRQyCACAKAI4hitCMwgAwBYCt20rQhWrqtNBq6rTQQAAAD4AAAC/HgcQgvwB94AACQEKFQmD9bzc1vhupIeNQaUyaVyqTySUgAkCChcJg/jb4r58OPDeNoeNQiWyqYzKXyyYgAkDChcJg/nb5r58N43w4oeNQqYyqVyqTySUgAkEChcJg/rb6r443w48NoeNQiWyqYzKXyyYgAkFChcJg/vb7r58OPDeNoeNQqYyyYzKXyqVgAkGChkJg/wG34Cvnw3jfDiAh41CJXJJTKpTKpiACQcKGQmD/A7fga+ON8OPDYCHjUKmMsmMyl8smIAJCAoZCYP8Ft+Cr58OPDeNgIeNQqYyqVyqTySUgAkJChkJg/we34Ovnw3jfDiAh41CJbKpjMpfLJiACQoKGQmD/CbfhK+ON8OPDYCHjUKmMqlcqk8klIAJCwoXCYP8Lt+FtzW+G6mHjUIlsqmMyl8smIAJDAoYCYP8Nt+Gr58N1NbAh41CpjLJjMpfKpWACQ0KGAmD/D7fh6+NTW+GwIeNQiVySUyqUyqYgAkAChcJg/xG34i3Nb4bqYeNQqYyyYzKXyyYgAkOChkJg/xO34mvnw48N42Ah41CJXJJTKpPJJSACQ8KGAmD/Fbfiq+NTW+HEIeNQiWyqYzKXyyYgA=="

        const val CAPTURED_MSG4_PURPLE_TEAL_ISF =
            "AL4BHAOAgAQdBJAFyAMCFQlIEUSAgYAERWQKSBFEgICCBEXIARkUMggAgCgCOIYrQjMIAMAWArdtK0IVq6rTQauq00G8NanIaLdfyR4EAgdo1Ao0EIenSKR33D07s+JqHX8PTuz4modfh5dING8BdwL/AycDHwLnAz8DNwL/AycDDwK3Aw8DIAkBCjUQh6dII/gHE1JrOHqDYcTUms4eoNhAh7VM898BZwLvAzcDPwL3Ay8DFwLvAzcDPwL3Ay8DEA=="

        const val CAPTURED_MSG5_REVERSE_GREEN_ISF =
            "AFscA4CABB0DmgJSAwlIEUSAgAJFyAEZFDIIAIAoAjiGK0IzCADAFgK3bStCFauq00GrqtNBAAAAPgAAAD4KHQ2D+HvT8WSakmiHmEFt+BsD4GwPgbA+BsD4GwOA"

        const val CAPTURED_MSG6_FORTIFIED_GIF =
            "R0lGODlhdQAjAPcAAAAAAAAAMwAAZgAAmQAAzAAA/wArAAArMwArZgArmQArzAAr/wBVAABVMwBVZgBVmQBVzABV/wCAAACAMwCAZgCAmQCAzACA/wCqAACqMwCqZgCqmQCqzACq/wDVAADVMwDVZgDVmQDVzADV/wD/AAD/MwD/ZgD/mQD/zAD//zMAADMAMzMAZjMAmTMAzDMA/zMrADMrMzMrZjMrmTMrzDMr/zNVADNVMzNVZjNVmTNVzDNV/zOAADOAMzOAZjOAmTOAzDOA/zOqADOqMzOqZjOqmTOqzDOq/zPVADPVMzPVZjPVmTPVzDPV/zP/ADP/MzP/ZjP/mTP/zDP//2YAAGYAM2YAZmYAmWYAzGYA/2YrAGYrM2YrZmYrmWYrzGYr/2ZVAGZVM2ZVZmZVmWZVzGZV/2aAAGaAM2aAZmaAmWaAzGaA/2aqAGaqM2aqZmaqmWaqzGaq/2bVAGbVM2bVZmbVmWbVzGbV/2b/AGb/M2b/Zmb/mWb/zGb//5kAAJkAM5kAZpkAmZkAzJkA/5krAJkrM5krZpkrmZkrzJkr/5lVAJlVM5lVZplVmZlVzJlV/5mAAJmAM5mAZpmAmZmAzJmA/5mqAJmqM5mqZpmqmZmqzJmq/5nVAJnVM5nVZpnVmZnVzJnV/5n/AJn/M5n/Zpn/mZn/zJn//8wAAMwAM8wAZswAmcwAzMwA/8wrAMwrM8wrZswrmcwrzMwr/8xVAMxVM8xVZsxVmcxVzMxV/8yAAMyAM8yAZsyAmcyAzMyA/8yqAMyqM8yqZsyqmcyqzMyq/8zVAMzVM8zVZszVmczVzMzV/8z/AMz/M8z/Zsz/mcz/zMz///8AAP8AM/8AZv8Amf8AzP8A//8rAP8rM/8rZv8rmf8rzP8r//9VAP9VM/9VZv9Vmf9VzP9V//+AAP+AM/+AZv+Amf+AzP+A//+qAP+qM/+qZv+qmf+qzP+q///VAP/VM//VZv/Vmf/VzP/V////AP//M///Zv//mf//zP///wAAAAAAAAAAAAAAACH5BAEAAPsALAAAAAB1ACMAAAj/APcJHEiwoMGDCBMqXMhQID167ug1nEix4sJ236RplAauXT2LFdtl2ygtm7mPIFOqNEhvGsmX3ySuRDjy5caYM3OGtMkTnD6dA2vy3GgOqFGE5mxmy8jzHFChGs+Zg6oxm8yjR9u9dEeQHjilV1NqJdr1nM12WI26I5kt4deXaFWSLHqQ6UZwaXWyXUiPqlOQb0sqHHsz70qoE82S/AaSZNiD9aC2DfkNL9akGx8r7EtymuaEdi0ztCvtb8N6G03rXLsxbsXAGl0rZK3xM8LA2CgSpquTHknRFjHfZYhteEWhtg0G5vp0Y+6VtFsnVKwRJHXeC4UaJY1ypT6q2WQL/yQsTXxDfRunTdw4OSds8yqFswcHUeRvlcgZ7s75naTq1VQN1R5IhAF3m3QrkceRYfYNJdhMJDEkFEpTZQMOcxdRhV1e55CWmk6BwUcQew4p1VE7EO1TH2wfGmZQO+ZIJSKBJCZUYIkO5lhVci7mFaFbCO7ToI42bdjjkfsIZ2RQG7HUDjjF6dgRklR2VSNLhSVUD4xSLQXOOe1gWOWY+wgl5kDU/UfmmhVRZ6BA+bEpp0U/EoReVXPmSZFd/4VDlp6AzlanQFFKw2OggArlGm0DIuroQOTJxOejlBIkVFG+NVnppplGJdSbmyLKoqahbhrgjKXqyVmQqW7ajlTdGQIUEAAh/lsAWRwDgYAEHQPoAUQDB0gRRP8BRWQZFDIIAIAoAjiGK0IzCADAFgK3bStCFauq00GrqtNBAAAAPgAAAL8eAwEHbAoYC4P5G+G/HTUk0IeXQ2q2az2az2az2azgADs="
    }
}
