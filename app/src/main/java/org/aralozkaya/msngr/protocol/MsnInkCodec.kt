package org.aralozkaya.msngr.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class InkPoint(
    val x: Float,
    val y: Float,
)

data class InkStroke(
    val points: List<InkPoint>,
    val colorRgb: Int = 0x000000,
    val widthHimetric: Int = NORMAL_PEN_WIDTH_HIMETRIC,
) {
    init {
        require(colorRgb in 0..0xFFFFFF)
        require(widthHimetric > 0)
    }

    val widthPx: Float
        get() = max(1.5f, widthHimetric * PIXELS_PER_HIMETRIC)

    companion object {
        const val FINE_PEN_WIDTH_HIMETRIC = 10
        const val NORMAL_PEN_WIDTH_HIMETRIC = 100
        const val BOLD_PEN_WIDTH_HIMETRIC = 200
        const val SMALL_ERASER_HIMETRIC = 212
        const val BIG_ERASER_HIMETRIC = 500
        const val HIMETRIC_PER_PIXEL = 2540f / 96f
        const val PIXELS_PER_HIMETRIC = 96f / 2540f
    }
}

data class InkDrawing(
    val strokes: List<InkStroke>,
    val canvasWidthPx: Float = DEFAULT_CANVAS_WIDTH_PX,
    val canvasHeightPx: Float = DEFAULT_CANVAS_HEIGHT_PX,
    val fallbackGifBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InkDrawing) return false
        return strokes == other.strokes &&
            canvasWidthPx == other.canvasWidthPx &&
            canvasHeightPx == other.canvasHeightPx &&
            fallbackGifBytes.contentEquals(other.fallbackGifBytes)
    }

    override fun hashCode(): Int {
        var result = strokes.hashCode()
        result = 31 * result + canvasWidthPx.hashCode()
        result = 31 * result + canvasHeightPx.hashCode()
        result = 31 * result + (fallbackGifBytes?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val DEFAULT_CANVAS_WIDTH_PX = 360f
        const val DEFAULT_CANVAS_HEIGHT_PX = 132f
    }
}

/**
 * Windows Live Messenger / Tablet PC Ink Serialized Format (ISF) and Fortified GIF codec.
 *
 * Implements the exact binary ISF specification used by `InkObj.dll` (`IInkDisp::Save` / `Load`)
 * and WPF `PresentationCore.dll`, including Multi-Byte Encoding (MBE), Delta-Delta coordinate
 * transformation, Indexed Huffman bit-stream coding, Gorilla fixed-width coding, and GIF89a
 * Comment Extension (`0x21 0xFE`) fortification.
 */
object MsnInkCodec {
    const val BASE64_PREFIX = "base64:"

    private const val TAG_INK_SPACE_RECTANGLE = 0x00
    private const val TAG_GUID_TABLE = 0x01
    private const val TAG_DRAWING_ATTRIBUTES_TABLE = 0x02
    private const val TAG_DRAWING_ATTRIBUTES_BLOCK = 0x03
    private const val TAG_STROKE_DESCRIPTOR_TABLE = 0x04
    private const val TAG_STROKE_DESCRIPTOR_BLOCK = 0x05
    private const val TAG_BUTTONS = 0x06
    private const val TAG_NO_X = 0x07
    private const val TAG_NO_Y = 0x08
    private const val TAG_DIDX = 0x09
    private const val TAG_STROKE = 0x0A
    private const val TAG_STROKE_PROPERTY_LIST = 0x0B
    private const val TAG_POINT_PROPERTY = 0x0C
    private const val TAG_SIDX = 0x0D
    private const val TAG_COMPRESSION_HEADER = 0x0E
    private const val TAG_TRANSFORM_TABLE = 0x0F
    private const val TAG_TRANSFORM = 0x10
    private const val TAG_TRANSFORM_ISOTROPIC_SCALE = 0x11
    private const val TAG_TRANSFORM_ANISOTROPIC_SCALE = 0x12
    private const val TAG_TRANSFORM_ROTATE = 0x13
    private const val TAG_TRANSFORM_TRANSLATE = 0x14
    private const val TAG_TRANSFORM_SCALE_AND_TRANSLATE = 0x15
    private const val TAG_TRANSFORM_QUAD = 0x16
    private const val TAG_TIDX = 0x17
    private const val TAG_METRIC_TABLE = 0x18
    private const val TAG_METRIC_BLOCK = 0x19
    private const val TAG_MIDX = 0x1A
    private const val TAG_MANTISSA = 0x1B
    private const val TAG_PERSISTENT_FORMAT = 0x1C
    private const val TAG_HIMETRIC_SIZE = 0x1D
    private const val TAG_STROKE_IDS = 0x1E
    private const val TAG_EXTENDED_TRANSFORM_TABLE = 0x1F

    private const val DA_TAG_COLOR = 0x44
    private const val DA_TAG_PEN_WIDTH = 0x45
    private const val DA_TAG_PEN_HEIGHT = 0x46
    private const val DA_TAG_PEN_TIP = 0x47
    private const val DA_TAG_DRAWING_FLAGS = 0x48
    private const val DA_TAG_RASTER_OPERATION = 0x4F
    private const val DA_TAG_TRANSPARENCY = 0x50

    /** Exact 21-byte X/Y HIMETRIC metric block emitted by WLM 2009 `IInkDisp::Save`. */
    private val WLM_METRIC_BLOCK = byteArrayOf(
        0x19, 0x14,
        0x32, 0x08, 0x00, 0x80.toByte(), 0x28, 0x02, 0x38, 0x86.toByte(), 0x2B, 0x42,
        0x33, 0x08, 0x00, 0xC0.toByte(), 0x16, 0x02, 0xB7.toByte(), 0x6D, 0x2B, 0x42,
    )

    /** Standard Huffman Bit-Amount Arrays (BAA) from `HuffCodec.HuffBits.DefaultBAAData`. */
    private val DEFAULT_BAA: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 2, 4, 6, 8, 12, 16, 24, 32),
        intArrayOf(0, 1, 1, 2, 4, 8, 12, 16, 24, 32),
        intArrayOf(0, 1, 1, 1, 2, 4, 8, 14, 22, 32),
        intArrayOf(0, 2, 2, 3, 5, 8, 12, 16, 24, 32),
        intArrayOf(0, 3, 4, 5, 8, 12, 16, 24, 32),
        intArrayOf(0, 4, 6, 8, 12, 16, 24, 32),
        intArrayOf(0, 6, 8, 12, 16, 24, 32),
        intArrayOf(0, 7, 8, 12, 16, 24, 32),
    )

    fun encodeBase64IsfPayload(drawing: InkDrawing): String =
        BASE64_PREFIX + MsnBase64.encode(encodeIsf(drawing))

    fun encodeBase64GifPayload(drawing: InkDrawing): String =
        BASE64_PREFIX + MsnBase64.encode(encodeFortifiedGif(drawing))

    fun decodeMimeBody(contentType: String, rawBody: ByteArray): InkDrawing? {
        if (rawBody.isEmpty()) return null
        val isRawGif = rawBody.size >= 6 &&
            rawBody[0] == 'G'.code.toByte() &&
            rawBody[1] == 'I'.code.toByte() &&
            rawBody[2] == 'F'.code.toByte()
        val isRawIsf = rawBody[0] == 0.toByte()
        val decodedBytes = if (isRawGif || isRawIsf) {
            rawBody
        } else {
            val text = rawBody.toString(Charsets.UTF_8).trim { it.isWhitespace() || it == '\u0000' }
            val payloadText = if (text.startsWith(BASE64_PREFIX, ignoreCase = true)) {
                text.substring(BASE64_PREFIX.length)
            } else {
                text
            }
            val cleanBase64 = payloadText.filterNot { it.isWhitespace() || it == '\u0000' }
            MsnBase64.decode(cleanBase64)?.takeIf(ByteArray::isNotEmpty) ?: rawBody
        }
        val normalizedType = contentType.substringBefore(';').trim().lowercase()
        return when (normalizedType) {
            Msnp18Protocol.INK_ISF_CONTENT_TYPE -> decodeIsf(decodedBytes) ?: decodeGif(decodedBytes)
            Msnp18Protocol.INK_GIF_CONTENT_TYPE -> decodeGif(decodedBytes) ?: decodeIsf(decodedBytes)
            else -> decodeIsf(decodedBytes) ?: decodeGif(decodedBytes)
        }
    }

    /**
     * Encodes an [InkDrawing] into a WLM-compatible binary ISF stream (`application/x-ms-ink`).
     */
    fun encodeIsf(drawing: InkDrawing): ByteArray {
        val nonEmptyStrokes = drawing.strokes.filter { it.points.isNotEmpty() }
        require(nonEmptyStrokes.isNotEmpty()) { "Cannot encode an empty handwriting drawing." }

        val minX = nonEmptyStrokes.minOf { stroke -> stroke.points.minOf(InkPoint::x) }
        val minY = nonEmptyStrokes.minOf { stroke -> stroke.points.minOf(InkPoint::y) }
        val maxX = nonEmptyStrokes.maxOf { stroke -> stroke.points.maxOf(InkPoint::x) }
        val maxY = nonEmptyStrokes.maxOf { stroke -> stroke.points.maxOf(InkPoint::y) }
        val maxPadPx = nonEmptyStrokes.maxOf { ceil(it.widthPx).toInt() }.coerceAtLeast(8)

        // Normalize coordinates so all encoded pixel points are positive and tightly framed at the top,
        // matching WLM's CInkEditControl serialization behavior.
        val shiftX = if (minX < maxPadPx) (maxPadPx - minX) else 0f
        val shiftY = (maxPadPx - minY)

        val widthPx = max(
            16,
            ceil(max(drawing.canvasWidthPx, maxX + shiftX + maxPadPx)).roundToInt(),
        )
        val heightPx = max(
            16,
            ceil(maxY + shiftY + maxPadPx).roundToInt(),
        )

        val body = ByteArrayOutputStream()

        // 0x1C PersistenceFormat: 0x10000 (ISF / base64 stream format)
        body.write(byteArrayOf(TAG_PERSISTENT_FORMAT.toByte(), 0x03, 0x80.toByte(), 0x80.toByte(), 0x04))

        // 0x1D HimetricSize (stores SignEncoded pixel dimensions as in InkObj.dll)
        val sizePayload = ByteArrayOutputStream().apply {
            writeSignedMbe(widthPx)
            writeSignedMbe(heightPx)
        }.toByteArray()
        body.writeMbe(TAG_HIMETRIC_SIZE)
        body.writeMbe(sizePayload.size)
        body.write(sizePayload)

        // Drawing attributes table (0x02) or single block (0x03)
        data class DaKey(val colorRgb: Int, val widthHimetric: Int)
        val daEntries = mutableListOf<ByteArray>()
        val daIndexByKey = LinkedHashMap<DaKey, Int>()
        val strokeDaIndices = IntArray(nonEmptyStrokes.size)

        nonEmptyStrokes.forEachIndexed { strokeIndex, stroke ->
            val key = DaKey(stroke.colorRgb and 0xFFFFFF, stroke.widthHimetric.coerceIn(1, 5000))
            val daIndex = daIndexByKey.getOrPut(key) {
                val entry = ByteArrayOutputStream()
                // DrawingFlags = 0x11 (FitToCurve | AntiAliased)
                entry.writeMbe(DA_TAG_DRAWING_FLAGS)
                entry.writeMbe(0x11)
                val colorRef = rgbToColorRef(key.colorRgb)
                if (colorRef != 0) {
                    entry.writeMbe(DA_TAG_COLOR)
                    entry.writeMbe(colorRef)
                }
                entry.writeMbe(DA_TAG_PEN_WIDTH)
                entry.writeMbe(key.widthHimetric)
                daEntries.add(entry.toByteArray())
                daEntries.lastIndex
            }
            strokeDaIndices[strokeIndex] = daIndex
        }

        if (daEntries.size == 1) {
            body.writeMbe(TAG_DRAWING_ATTRIBUTES_BLOCK)
            body.writeMbe(daEntries[0].size)
            body.write(daEntries[0])
        } else {
            val tableBytes = ByteArrayOutputStream()
            for (entry in daEntries) {
                tableBytes.writeMbe(entry.size)
                tableBytes.write(entry)
            }
            val tableArray = tableBytes.toByteArray()
            body.writeMbe(TAG_DRAWING_ATTRIBUTES_TABLE)
            body.writeMbe(tableArray.size)
            body.write(tableArray)
        }

        // 0x19 MetricBlock
        body.write(WLM_METRIC_BLOCK)

        // 0x15 TransformScaleAndTranslate (26.458334f, 26.458334f, 0.125f, 0.125f)
        body.writeMbe(TAG_TRANSFORM_SCALE_AND_TRANSLATE)
        val transformBytes = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(InkStroke.HIMETRIC_PER_PIXEL)
            .putFloat(InkStroke.HIMETRIC_PER_PIXEL)
            .putFloat(0.125f)
            .putFloat(0.125f)
            .array()
        body.write(transformBytes)

        // Strokes
        var currentDaIndex = 0
        nonEmptyStrokes.forEachIndexed { index, stroke ->
            val daIndex = strokeDaIndices[index]
            if (daIndex != currentDaIndex) {
                body.writeMbe(TAG_DIDX)
                body.writeMbe(daIndex)
                currentDaIndex = daIndex
            }
            val xs = IntArray(stroke.points.size) { i -> (stroke.points[i].x + shiftX).roundToInt() }
            val ys = IntArray(stroke.points.size) { i -> (stroke.points[i].y + shiftY).roundToInt() }
            val strokePayload = ByteArrayOutputStream().apply {
                writeMbe(stroke.points.size)
                write(compressPacketData(xs))
                write(compressPacketData(ys))
            }.toByteArray()
            body.writeMbe(TAG_STROKE)
            body.writeMbe(strokePayload.size)
            body.write(strokePayload)
        }

        val bodyBytes = body.toByteArray()
        return ByteArrayOutputStream(bodyBytes.size + 6).apply {
            writeMbe(0) // ISF stream version 0
            writeMbe(bodyBytes.size)
            write(bodyBytes)
        }.toByteArray()
    }

    /**
     * Encodes an [InkDrawing] into a Fortified GIF (`image/gif`), rendering the strokes into a
     * GIF89a image and embedding the binary ISF stream in a GIF Comment Extension (`0x21 0xFE`)
     * right before the `0x3B` trailer so both `IInkDisp::Load` and plain GIF viewers can load it.
     */
    fun encodeFortifiedGif(drawing: InkDrawing): ByteArray {
        val isfBytes = encodeIsf(drawing)
        val normalized = decodeIsf(isfBytes) ?: drawing
        val width = normalized.canvasWidthPx.roundToInt().coerceIn(16, 600)
        val height = normalized.canvasHeightPx.roundToInt().coerceIn(16, 400)
        val pixels = renderPixels(normalized, width, height)
        val encoder = ProfileGifEncoder(width, height, loopCount = null)
        encoder.addFrame(pixels, delayCentiseconds = 0)
        val plainGif = encoder.finish()

        // Insert GIF Comment Extension (0x21 0xFE) before the trailing 0x3B byte.
        val out = ByteArrayOutputStream(plainGif.size + isfBytes.size + 16)
        out.write(plainGif, 0, plainGif.size - 1)
        out.write(0x21)
        out.write(0xFE)
        var offset = 0
        while (offset < isfBytes.size) {
            val chunkLen = min(255, isfBytes.size - offset)
            out.write(chunkLen)
            out.write(isfBytes, offset, chunkLen)
            offset += chunkLen
        }
        out.write(0x00)
        out.write(0x3B)
        return out.toByteArray()
    }

    /**
     * Decodes a Fortified GIF (`image/gif`) or plain GIF into an [InkDrawing].
     */
    fun decodeGif(bytes: ByteArray): InkDrawing? {
        if (bytes.size < 13) return null
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        if (header != "GIF87a" && header != "GIF89a") return null
        val gifWidth = ((bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)).toFloat()
        val gifHeight = ((bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)).toFloat()

        // Search for embedded ISF inside GIF Comment Extension (0x21 0xFE) sub-blocks.
        extractGifCommentExtensions(bytes).forEach { commentBytes ->
            decodeIsf(commentBytes)?.let { decoded ->
                return decoded.copy(
                    canvasWidthPx = if (gifWidth > 0f) gifWidth else decoded.canvasWidthPx,
                    canvasHeightPx = if (gifHeight > 0f) gifHeight else decoded.canvasHeightPx,
                    fallbackGifBytes = bytes.copyOf(),
                )
            }
        }

        return InkDrawing(
            strokes = emptyList(),
            canvasWidthPx = gifWidth.coerceAtLeast(16f),
            canvasHeightPx = gifHeight.coerceAtLeast(16f),
            fallbackGifBytes = bytes.copyOf(),
        )
    }

    /**
     * Decodes a binary ISF stream (`application/x-ms-ink`) into an [InkDrawing].
     */
    fun decodeIsf(bytes: ByteArray): InkDrawing? = runCatching {
        if (bytes.size < 4) return null
        val cursor = MbeCursor(bytes, 0)
        val version = cursor.readMbe()
        if (version != 0) return null
        val streamLen = cursor.readMbe()
        val streamEnd = (cursor.pos + streamLen).coerceAtMost(bytes.size)
        if (streamLen <= 0 || cursor.pos >= streamEnd) return null

        data class ParsedDa(
            val colorRgb: Int = 0x000000,
            val widthHimetric: Int = InkStroke.NORMAL_PEN_WIDTH_HIMETRIC,
        )

        data class Transform(
            val sx: Float = InkStroke.HIMETRIC_PER_PIXEL,
            val sy: Float = InkStroke.HIMETRIC_PER_PIXEL,
            val dx: Float = 0f,
            val dy: Float = 0f,
        )

        data class RawStroke(
            val xs: IntArray,
            val ys: IntArray,
            val daIndex: Int,
            val transform: Transform,
        )

        val daList = mutableListOf<ParsedDa>()
        val transforms = mutableListOf<Transform>()
        var currentDaIndex = 0
        var currentTransform = Transform()
        var inkRectLeft: Int? = null
        var inkRectTop: Int? = null
        var inkRectRight: Int? = null
        var inkRectBottom: Int? = null
        var headerWidthPx = 0f
        var headerHeightPx = 0f
        val rawStrokes = mutableListOf<RawStroke>()

        fun parseDaBlock(endPos: Int): ParsedDa {
            var colorRgb = 0x000000
            var widthHimetric = InkStroke.NORMAL_PEN_WIDTH_HIMETRIC
            while (cursor.pos < endPos) {
                val propTag = cursor.readMbe()
                when (propTag) {
                    DA_TAG_COLOR -> {
                        val colorRef = cursor.readMbe()
                        colorRgb = colorRefToRgb(colorRef)
                    }
                    DA_TAG_PEN_WIDTH -> {
                        widthHimetric = cursor.readMbe().coerceIn(1, 5000)
                    }
                    DA_TAG_PEN_HEIGHT,
                    DA_TAG_PEN_TIP,
                    DA_TAG_DRAWING_FLAGS,
                    DA_TAG_TRANSPARENCY,
                    -> {
                        cursor.readMbe()
                    }
                    DA_TAG_RASTER_OPERATION -> {
                        // RasterOperation in ISF is stored as a compressed byte/short block or MBE.
                        val len = cursor.readMbe()
                        cursor.pos = (cursor.pos + len).coerceAtMost(endPos)
                    }
                    else -> {
                        if (propTag >= 0x40) {
                            val len = cursor.readMbe()
                            cursor.pos = (cursor.pos + len).coerceAtMost(endPos)
                        } else {
                            cursor.readMbe()
                        }
                    }
                }
            }
            cursor.pos = endPos
            return ParsedDa(colorRgb = colorRgb, widthHimetric = widthHimetric)
        }

        fun readTransformTag(tag: Int): Transform = when (tag) {
            TAG_TRANSFORM -> {
                val m11 = cursor.readFloat()
                cursor.readFloat()
                cursor.readFloat()
                val m22 = cursor.readFloat()
                val dx = cursor.readFloat()
                val dy = cursor.readFloat()
                Transform(sx = m11, sy = m22, dx = dx, dy = dy)
            }
            TAG_TRANSFORM_ISOTROPIC_SCALE -> {
                val s = cursor.readFloat()
                Transform(sx = s, sy = s)
            }
            TAG_TRANSFORM_ANISOTROPIC_SCALE -> {
                val sx = cursor.readFloat()
                val sy = cursor.readFloat()
                Transform(sx = sx, sy = sy)
            }
            TAG_TRANSFORM_ROTATE -> {
                cursor.readMbe()
                currentTransform
            }
            TAG_TRANSFORM_TRANSLATE -> {
                val dx = cursor.readFloat()
                val dy = cursor.readFloat()
                Transform(dx = dx, dy = dy)
            }
            TAG_TRANSFORM_SCALE_AND_TRANSLATE -> {
                val sx = cursor.readFloat()
                val sy = cursor.readFloat()
                val dx = cursor.readFloat()
                val dy = cursor.readFloat()
                Transform(sx = sx, sy = sy, dx = dx, dy = dy)
            }
            TAG_TRANSFORM_QUAD -> {
                cursor.pos = (cursor.pos + 24).coerceAtMost(streamEnd)
                currentTransform
            }
            else -> currentTransform
        }

        while (cursor.pos < streamEnd) {
            val tag = cursor.readMbe()
            when (tag) {
                TAG_INK_SPACE_RECTANGLE -> {
                    inkRectLeft = cursor.readSignedMbe()
                    inkRectTop = cursor.readSignedMbe()
                    inkRectRight = cursor.readSignedMbe()
                    inkRectBottom = cursor.readSignedMbe()
                }
                TAG_DRAWING_ATTRIBUTES_BLOCK -> {
                    val blockLen = cursor.readMbe()
                    val blockEnd = (cursor.pos + blockLen).coerceAtMost(streamEnd)
                    daList.add(parseDaBlock(blockEnd))
                }
                TAG_DRAWING_ATTRIBUTES_TABLE -> {
                    val tableLen = cursor.readMbe()
                    val tableEnd = (cursor.pos + tableLen).coerceAtMost(streamEnd)
                    while (cursor.pos < tableEnd) {
                        val entryLen = cursor.readMbe()
                        val entryEnd = (cursor.pos + entryLen).coerceAtMost(tableEnd)
                        daList.add(parseDaBlock(entryEnd))
                    }
                    cursor.pos = tableEnd
                }
                TAG_TRANSFORM,
                TAG_TRANSFORM_ISOTROPIC_SCALE,
                TAG_TRANSFORM_ANISOTROPIC_SCALE,
                TAG_TRANSFORM_ROTATE,
                TAG_TRANSFORM_TRANSLATE,
                TAG_TRANSFORM_SCALE_AND_TRANSLATE,
                TAG_TRANSFORM_QUAD,
                -> {
                    val t = readTransformTag(tag)
                    transforms.add(t)
                    currentTransform = t
                }
                TAG_TRANSFORM_TABLE -> {
                    val tableLen = cursor.readMbe()
                    val tableEnd = (cursor.pos + tableLen).coerceAtMost(streamEnd)
                    while (cursor.pos < tableEnd) {
                        val subTag = cursor.readMbe()
                        transforms.add(readTransformTag(subTag))
                    }
                    transforms.firstOrNull()?.let { currentTransform = it }
                    cursor.pos = tableEnd
                }
                TAG_TIDX -> {
                    val tIdx = cursor.readMbe()
                    transforms.getOrNull(tIdx)?.let { currentTransform = it }
                }
                TAG_DIDX -> {
                    currentDaIndex = cursor.readMbe()
                }
                TAG_SIDX,
                TAG_MIDX,
                -> {
                    cursor.readMbe()
                }
                TAG_BUTTONS,
                TAG_NO_X,
                TAG_NO_Y,
                -> Unit
                TAG_HIMETRIC_SIZE -> {
                    val blockLen = cursor.readMbe()
                    val blockEnd = (cursor.pos + blockLen).coerceAtMost(streamEnd)
                    if (cursor.pos < blockEnd) {
                        val w = cursor.readSignedMbe()
                        val h = if (cursor.pos < blockEnd) cursor.readSignedMbe() else 0
                        if (w > 0 && h > 0) {
                            headerWidthPx = w.toFloat()
                            headerHeightPx = h.toFloat()
                        }
                    }
                    cursor.pos = blockEnd
                }
                TAG_STROKE -> {
                    val strokeLen = cursor.readMbe()
                    val strokeEnd = (cursor.pos + strokeLen).coerceAtMost(streamEnd)
                    val numPoints = cursor.readMbe()
                    if (numPoints in 1..100_000 && cursor.pos < strokeEnd) {
                        val xs = decompressPacketData(bytes, cursor, strokeEnd, numPoints)
                        val ys = if (cursor.pos < strokeEnd) {
                            decompressPacketData(bytes, cursor, strokeEnd, numPoints)
                        } else {
                            IntArray(numPoints)
                        }
                        rawStrokes.add(RawStroke(xs, ys, currentDaIndex, currentTransform))
                    }
                    cursor.pos = strokeEnd
                }
                else -> {
                    val blockLen = cursor.readMbe()
                    cursor.pos = (cursor.pos + blockLen).coerceAtMost(streamEnd)
                }
            }
        }

        if (rawStrokes.isEmpty()) return null

        val originHimetricX = (inkRectLeft ?: 0).toFloat()
        val originHimetricY = (inkRectTop ?: 0).toFloat()

        val strokes = rawStrokes.map { raw ->
            val da = daList.getOrNull(raw.daIndex) ?: daList.firstOrNull() ?: ParsedDa()
            val pts = List(raw.xs.size) { i ->
                val hx = raw.xs[i] * raw.transform.sx + raw.transform.dx - originHimetricX
                val hy = raw.ys[i] * raw.transform.sy + raw.transform.dy - originHimetricY
                InkPoint(
                    x = hx * InkStroke.PIXELS_PER_HIMETRIC,
                    y = hy * InkStroke.PIXELS_PER_HIMETRIC,
                )
            }
            InkStroke(
                points = pts,
                colorRgb = da.colorRgb,
                widthHimetric = da.widthHimetric,
            )
        }

        // If any decoded stroke still has negative coordinates, shift into the positive quadrant.
        val minX = strokes.minOf { s -> s.points.minOf(InkPoint::x) }
        val minY = strokes.minOf { s -> s.points.minOf(InkPoint::y) }
        val shiftX = if (minX < 0f) -minX + 6f else 0f
        val shiftY = if (minY < 0f) -minY + 6f else 0f
        val normalizedStrokes = if (shiftX != 0f || shiftY != 0f) {
            strokes.map { s ->
                s.copy(points = s.points.map { p -> InkPoint(p.x + shiftX, p.y + shiftY) })
            }
        } else {
            strokes
        }

        val maxX = normalizedStrokes.maxOf { s -> s.points.maxOf(InkPoint::x) + s.widthPx }
        val maxY = normalizedStrokes.maxOf { s -> s.points.maxOf(InkPoint::y) + s.widthPx }
        val rectWidthPx = if (inkRectLeft != null && inkRectRight != null && inkRectRight > inkRectLeft) {
            (inkRectRight - inkRectLeft) * InkStroke.PIXELS_PER_HIMETRIC
        } else 0f
        val rectHeightPx = if (inkRectTop != null && inkRectBottom != null && inkRectBottom > inkRectTop) {
            (inkRectBottom - inkRectTop) * InkStroke.PIXELS_PER_HIMETRIC
        } else 0f

        val canvasWidth = maxOf(16f, headerWidthPx, rectWidthPx, maxX + 8f)
        val canvasHeight = maxOf(16f, headerHeightPx, rectHeightPx, maxY + 8f)

        InkDrawing(
            strokes = normalizedStrokes,
            canvasWidthPx = canvasWidth,
            canvasHeightPx = canvasHeight,
        )
    }.getOrNull()

    internal fun compressPacketData(values: IntArray, baaIndex: Int = 1): ByteArray {
        val baa = DEFAULT_BAA[baaIndex.coerceIn(0, DEFAULT_BAA.lastIndex)]
        val mins = IntArray(baa.size)
        var m = 1
        for (i in 1 until baa.size) {
            mins[i] = m
            m += 1 shl (baa[i] - 1)
        }
        val writer = BitStreamWriter()
        var d1 = 0L
        var d2 = 0L
        for (value in values) {
            val v = value.toLong()
            val diff = (v + d2 - (d1 shl 1)).toInt()
            d2 = d1
            d1 = v
            huffEncodeOne(writer, diff, baa, mins)
        }
        val packed = writer.toByteArray()
        return ByteArray(1 + packed.size).also { out ->
            out[0] = (0x80 or baaIndex).toByte()
            packed.copyInto(out, destinationOffset = 1)
        }
    }

    private fun huffEncodeOne(writer: BitStreamWriter, value: Int, baa: IntArray, mins: IntArray) {
        if (value == 0) {
            writer.writeBits(0, 1)
            return
        }
        val neg = if (value < 0) 1 else 0
        val mag = abs(value.toLong())
        var bucket = baa.lastIndex
        for (i in 1 until baa.size) {
            val maxMag = mins[i].toLong() + (1L shl (baa[i] - 1)) - 1L
            if (mag <= maxMag) {
                bucket = i
                break
            }
        }
        writer.writeBits(((1 shl bucket) - 1) shl 1, bucket + 1)
        val offset = ((mag - mins[bucket].toLong()) shl 1) or neg.toLong()
        writer.writeBitsLong(offset, baa[bucket])
    }

    private fun decompressPacketData(
        bytes: ByteArray,
        cursor: MbeCursor,
        limit: Int,
        count: Int,
    ): IntArray {
        require(cursor.pos < limit) { "Unexpected end of ISF stroke packet stream." }
        val algo = bytes[cursor.pos].toInt() and 0xFF
        val top2 = algo and 0xC0
        return when (top2) {
            0x80 -> {
                val baaIdx = (algo and 0x1F).coerceIn(0, DEFAULT_BAA.lastIndex)
                val baa = DEFAULT_BAA[baaIdx]
                val mins = LongArray(baa.size)
                var m = 1L
                for (i in 1 until baa.size) {
                    mins[i] = m
                    m += 1L shl (baa[i] - 1)
                }
                val reader = BitStreamReader(bytes, cursor.pos + 1, limit)
                var d1 = 0L
                var d2 = 0L
                val out = IntArray(count)
                for (i in 0 until count) {
                    val (xf, extra) = huffDecodeOne(reader, baa, mins)
                    val diff = if (extra != 0) {
                        val positive = (extra and 1) == 0
                        val full = ((extra.toLong() shr 1) shl 30) or (xf.toLong() and 0x3FFFFFFFL)
                        if (positive) full else -full
                    } else {
                        xf.toLong()
                    }
                    val value = diff - d2 + (d1 shl 1)
                    d2 = d1
                    d1 = value
                    out[i] = value.toInt()
                }
                cursor.pos = reader.nextByteIndex()
                out
            }
            0x00 -> {
                val deltaDelta = (algo and 0x20) != 0
                val bitCount = (algo and 0x1F).let { if (it == 0) 32 else it }
                cursor.pos += 1
                val out = IntArray(count)
                var outIdx = 0
                var d1 = 0L
                var d2 = 0L
                if (deltaDelta && count >= 2) {
                    val p0 = cursor.readSignedMbe().toLong()
                    val v0 = p0 - d2 + (d1 shl 1)
                    d2 = d1
                    d1 = v0
                    out[outIdx++] = v0.toInt()

                    val p1 = cursor.readSignedMbe().toLong()
                    val v1 = p1 - d2 + (d1 shl 1)
                    d2 = d1
                    d1 = v1
                    out[outIdx++] = v1.toInt()
                }
                val rem = count - outIdx
                val reader = BitStreamReader(bytes, cursor.pos, limit)
                val signMask = 1L shl (bitCount - 1)
                val signExt = if (bitCount >= 32) 0L else (-1L shl bitCount)
                for (i in 0 until rem) {
                    var raw = reader.readBitsLong(bitCount)
                    if ((raw and signMask) != 0L) {
                        raw = raw or signExt
                    }
                    val signedVal = raw.toInt().toLong()
                    if (deltaDelta) {
                        val value = signedVal - d2 + (d1 shl 1)
                        d2 = d1
                        d1 = value
                        out[outIdx++] = value.toInt()
                    } else {
                        out[outIdx++] = signedVal.toInt()
                    }
                }
                cursor.pos = (cursor.pos + ((rem * bitCount + 7) shr 3)).coerceAtMost(limit)
                out
            }
            else -> error("Unsupported ISF packet compression algorithm: 0x${algo.toString(16)}")
        }
    }

    private fun huffDecodeOne(
        reader: BitStreamReader,
        baa: IntArray,
        mins: LongArray,
    ): Pair<Int, Int> {
        var prefix = 0
        while (!reader.isAtEnd() && reader.readBit() == 1) {
            prefix++
        }
        if (prefix == 0) return 0 to 0
        if (prefix < baa.size) {
            val bitCount = baa[prefix]
            val raw = reader.readBitsLong(bitCount)
            val neg = (raw and 1L) != 0L
            val mag = (raw ushr 1) + mins[prefix]
            return (if (neg) -mag.toInt() else mag.toInt()) to 0
        }
        if (prefix == baa.size) {
            val (extra, _) = huffDecodeOne(reader, baa, mins)
            val (data, _) = huffDecodeOne(reader, baa, mins)
            return data to extra
        }
        error("Invalid ISF Huffman prefix length: $prefix")
    }

    private fun extractGifCommentExtensions(bytes: ByteArray): List<ByteArray> {
        val comments = mutableListOf<ByteArray>()
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xFE.toByte()) {
                var pos = i + 2
                val out = ByteArrayOutputStream()
                var valid = false
                while (pos < bytes.size) {
                    val blockLen = bytes[pos].toInt() and 0xFF
                    pos++
                    if (blockLen == 0) {
                        valid = true
                        break
                    }
                    if (pos + blockLen > bytes.size) break
                    out.write(bytes, pos, blockLen)
                    pos += blockLen
                }
                if (valid && out.size() > 0) {
                    comments.add(out.toByteArray())
                }
            }
            i++
        }
        return comments
    }

    private fun renderPixels(drawing: InkDrawing, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        for (stroke in drawing.strokes) {
            val argb = (0xFF shl 24) or (stroke.colorRgb and 0xFFFFFF)
            val radius = (stroke.widthPx / 2f).coerceAtLeast(1f)
            val pts = stroke.points
            if (pts.size == 1) {
                drawCircle(pixels, width, height, pts[0].x, pts[0].y, radius, argb)
            } else {
                for (i in 0 until pts.size - 1) {
                    drawSegment(
                        pixels = pixels,
                        width = width,
                        height = height,
                        x0 = pts[i].x,
                        y0 = pts[i].y,
                        x1 = pts[i + 1].x,
                        y1 = pts[i + 1].y,
                        radius = radius,
                        argb = argb,
                    )
                }
            }
        }
        return pixels
    }

    private fun drawSegment(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        argb: Int,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = sqrt(dx * dx + dy * dy)
        val steps = max(1, ceil(dist * 2f).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            drawCircle(pixels, width, height, x0 + dx * t, y0 + dy * t, radius, argb)
        }
    }

    private fun drawCircle(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        argb: Int,
    ) {
        val r2 = radius * radius
        val minX = max(0, (cx - radius).toInt())
        val maxX = min(width - 1, ceil(cx + radius).toInt())
        val minY = max(0, (cy - radius).toInt())
        val maxY = min(height - 1, ceil(cy + radius).toInt())
        for (y in minY..maxY) {
            val dy = y - cy
            for (x in minX..maxX) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    pixels[y * width + x] = argb
                }
            }
        }
    }

    fun rgbToColorRef(rgb: Int): Int {
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF
        return r or (g shl 8) or (b shl 16)
    }

    fun colorRefToRgb(colorRef: Int): Int {
        val r = colorRef and 0xFF
        val g = (colorRef ushr 8) and 0xFF
        val b = (colorRef ushr 16) and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    private fun ByteArrayOutputStream.writeMbe(value: Int) {
        var v = value.toLong() and 0xFFFFFFFFL
        while (v > 0x7FL) {
            write((0x80L or (v and 0x7FL)).toInt())
            v = v ushr 7
        }
        write((v and 0x7FL).toInt())
    }

    private fun ByteArrayOutputStream.writeSignedMbe(value: Int) {
        val encoded = if (value < 0) {
            ((-value.toLong()) shl 1) or 1L
        } else {
            value.toLong() shl 1
        }
        var v = encoded
        while (v > 0x7FL) {
            write((0x80L or (v and 0x7FL)).toInt())
            v = v ushr 7
        }
        write((v and 0x7FL).toInt())
    }

    private class MbeCursor(val bytes: ByteArray, var pos: Int) {
        fun readMbe(): Int {
            var result = 0L
            var shift = 0
            while (pos < bytes.size && shift < 35) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result.toInt()
        }

        fun readSignedMbe(): Int {
            val u = readMbe().toLong() and 0xFFFFFFFFL
            return if ((u and 1L) != 0L) {
                -(u ushr 1).toInt()
            } else {
                (u ushr 1).toInt()
            }
        }

        fun readFloat(): Float {
            require(pos + 4 <= bytes.size) { "Unexpected EOF reading float in ISF." }
            val bits = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }
    }

    private class BitStreamReader(
        private val bytes: ByteArray,
        private val startPos: Int,
        private val limit: Int,
    ) {
        private var bytePos = startPos
        private var bitIdx = 0

        fun isAtEnd(): Boolean = bytePos >= limit

        fun readBit(): Int {
            if (bytePos >= limit) return 0
            val bit = (bytes[bytePos].toInt() ushr (7 - bitIdx)) and 1
            bitIdx++
            if (bitIdx == 8) {
                bitIdx = 0
                bytePos++
            }
            return bit
        }

        fun readBitsLong(count: Int): Long {
            var v = 0L
            repeat(count) {
                v = (v shl 1) or readBit().toLong()
            }
            return v
        }

        fun nextByteIndex(): Int = (bytePos + if (bitIdx > 0) 1 else 0).coerceAtLeast(startPos + 1)
    }

    private class BitStreamWriter {
        private val buffer = ByteArrayOutputStream()
        private var currentByte = 0
        private var remainingBits = 8

        fun writeBits(bits: Int, count: Int) {
            writeBitsLong(bits.toLong() and 0xFFFFFFFFL, count)
        }

        fun writeBitsLong(bits: Long, count: Int) {
            for (i in (count - 1) downTo 0) {
                val b = ((bits ushr i) and 1L).toInt()
                currentByte = currentByte or (b shl (remainingBits - 1))
                remainingBits--
                if (remainingBits == 0) {
                    buffer.write(currentByte)
                    currentByte = 0
                    remainingBits = 8
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (remainingBits < 8) {
                buffer.write(currentByte)
                currentByte = 0
                remainingBits = 8
            }
            return buffer.toByteArray()
        }
    }
}
