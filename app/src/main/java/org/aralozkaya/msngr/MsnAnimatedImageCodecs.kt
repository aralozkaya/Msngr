package org.aralozkaya.msngr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Messenger emoticons are authored on a 19x19 pixel grid. */
internal const val MSN_EMOTICON_GRID_PX = 19

/*
 * Platform-independent parsers and compositors for SVG, APNG, and GIF emoticons. Keeping this
 * layer free of android.graphics lets JVM unit tests verify every asset in app/src/main/assets.
 */

internal data class SvgScanline(
    val x: Float,
    val y: Float,
    val width: Float,
)

internal sealed class SvgShape {
    abstract val fillArgb: Int?
    abstract val strokeArgb: Int?
    abstract val strokeWidth: Float
    abstract val strokeCap: String
    abstract val strokeJoin: String

    /**
     * Fast path for pixel-perfect-svg paths (`M x,y h w ...`), where each horizontal stroke of
     * width 1 centered on `y` covers `[x, y - 0.5] .. [x + w, y + 0.5]` in user space.
     */
    data class Scanlines(
        val lines: List<SvgScanline>,
        val colorArgb: Int,
        override val strokeWidth: Float = 1f,
    ) : SvgShape() {
        override val fillArgb: Int? = null
        override val strokeArgb: Int = colorArgb
        override val strokeCap: String = "butt"
        override val strokeJoin: String = "miter"
    }

    data class PathData(
        val d: String,
        override val fillArgb: Int?,
        override val strokeArgb: Int?,
        override val strokeWidth: Float,
        override val strokeCap: String,
        override val strokeJoin: String,
    ) : SvgShape()

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rx: Float,
        val ry: Float,
        override val fillArgb: Int?,
        override val strokeArgb: Int?,
        override val strokeWidth: Float,
        override val strokeCap: String,
        override val strokeJoin: String,
    ) : SvgShape()

    data class Circle(
        val cx: Float,
        val cy: Float,
        val r: Float,
        override val fillArgb: Int?,
        override val strokeArgb: Int?,
        override val strokeWidth: Float,
        override val strokeCap: String,
        override val strokeJoin: String,
    ) : SvgShape()

    data class Ellipse(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        override val fillArgb: Int?,
        override val strokeArgb: Int?,
        override val strokeWidth: Float,
        override val strokeCap: String,
        override val strokeJoin: String,
    ) : SvgShape()

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        override val strokeArgb: Int?,
        override val strokeWidth: Float,
        override val strokeCap: String,
        override val strokeJoin: String,
    ) : SvgShape() {
        override val fillArgb: Int? = null
    }

    data class Poly(
        val points: FloatArray,
        val closed: Boolean,
        override val fillArgb: Int?,
        override val strokeArgb: Int?,
        override val strokeWidth: Float,
        override val strokeCap: String,
        override val strokeJoin: String,
    ) : SvgShape()
}

internal class SvgDocument(
    val width: Float,
    val height: Float,
    val viewBoxX: Float,
    val viewBoxY: Float,
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    val crispEdges: Boolean,
    val shapes: List<SvgShape>,
) {
    /**
     * Rasterizes scanline/rect pixel-grid SVGs into a `width x height` ARGB pixel array on the JVM
     * (and for 1:1 grid verification).
     */
    fun rasterizeGrid(): IntArray {
        val gridWidth = max(1, viewBoxWidth.roundToInt())
        val gridHeight = max(1, viewBoxHeight.roundToInt())
        val canvas = IntArray(gridWidth * gridHeight)
        shapes.forEach { shape ->
            when (shape) {
                is SvgShape.Scanlines -> {
                    val halfStroke = shape.strokeWidth / 2f
                    shape.lines.forEach { line ->
                        val left = (line.x - viewBoxX).roundToInt().coerceIn(0, gridWidth)
                        val right = (line.x + line.width - viewBoxX).roundToInt().coerceIn(left, gridWidth)
                        val top = (line.y - halfStroke - viewBoxY).roundToInt().coerceIn(0, gridHeight)
                        val bottom = (line.y + halfStroke - viewBoxY).roundToInt().coerceIn(top, gridHeight)
                        for (py in top until bottom) {
                            for (px in left until right) {
                                canvas[py * gridWidth + px] = blendSourceOver(canvas[py * gridWidth + px], shape.colorArgb)
                            }
                        }
                    }
                }
                is SvgShape.Rect -> {
                    val color = shape.fillArgb ?: return@forEach
                    val left = (shape.x - viewBoxX).roundToInt().coerceIn(0, gridWidth)
                    val right = (shape.x + shape.width - viewBoxX).roundToInt().coerceIn(left, gridWidth)
                    val top = (shape.y - viewBoxY).roundToInt().coerceIn(0, gridHeight)
                    val bottom = (shape.y + shape.height - viewBoxY).roundToInt().coerceIn(top, gridHeight)
                    for (py in top until bottom) {
                        for (px in left until right) {
                            canvas[py * gridWidth + px] = blendSourceOver(canvas[py * gridWidth + px], color)
                        }
                    }
                }
                else -> Unit
            }
        }
        return canvas
    }
}

internal object SvgCodec {
    fun parse(bytes: ByteArray): SvgDocument {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val root = document.documentElement
        require(root != null && root.tagName.substringAfterLast(':').equals("svg", ignoreCase = true)) {
            "Not an SVG document."
        }
        val viewBoxValues = root.getAttribute("viewBox")
            .trim()
            .split(Regex("[\\s,]+"))
            .filter(String::isNotEmpty)
            .mapNotNull(String::toFloatOrNull)
        val rawWidth = root.getAttribute("width").parseSvgLength()
        val rawHeight = root.getAttribute("height").parseSvgLength()
        val viewBoxX = if (viewBoxValues.size == 4) viewBoxValues[0] else 0f
        val viewBoxY = if (viewBoxValues.size == 4) viewBoxValues[1] else 0f
        val viewBoxWidth = if (viewBoxValues.size == 4) {
            viewBoxValues[2]
        } else {
            rawWidth ?: MSN_EMOTICON_GRID_PX.toFloat()
        }
        val viewBoxHeight = if (viewBoxValues.size == 4) {
            viewBoxValues[3]
        } else {
            rawHeight ?: MSN_EMOTICON_GRID_PX.toFloat()
        }
        val width = rawWidth ?: viewBoxWidth
        val height = rawHeight ?: viewBoxHeight
        require(width > 0f && height > 0f && viewBoxWidth > 0f && viewBoxHeight > 0f) {
            "SVG has non-positive dimensions."
        }
        val crispEdges = root.getAttribute("shape-rendering").equals("crispEdges", ignoreCase = true)
        val shapes = mutableListOf<SvgShape>()
        parseChildren(root, StyleContext(), shapes)
        require(shapes.isNotEmpty()) { "SVG contains no drawable shapes." }
        return SvgDocument(
            width = width,
            height = height,
            viewBoxX = viewBoxX,
            viewBoxY = viewBoxY,
            viewBoxWidth = viewBoxWidth,
            viewBoxHeight = viewBoxHeight,
            crispEdges = crispEdges,
            shapes = shapes,
        )
    }

    private data class StyleContext(
        val fill: String? = null,
        val stroke: String? = null,
        val opacity: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
        val strokeWidth: Float = 1f,
        val strokeCap: String = "butt",
        val strokeJoin: String = "miter",
    ) {
        fun resolve(element: Element): StyleContext {
            val inlineStyles = element.getAttribute("style")
                .split(';')
                .mapNotNull { declaration ->
                    val colon = declaration.indexOf(':')
                    if (colon <= 0) null else {
                        declaration.substring(0, colon).trim().lowercase(Locale.ROOT) to
                            declaration.substring(colon + 1).trim()
                    }
                }
                .toMap()

            fun attr(name: String): String? =
                inlineStyles[name] ?: element.getAttribute(name).takeIf(String::isNotEmpty)

            val elementOpacity = attr("opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            return StyleContext(
                fill = attr("fill") ?: fill,
                stroke = attr("stroke") ?: stroke,
                opacity = (opacity * elementOpacity).coerceIn(0f, 1f),
                fillOpacity = attr("fill-opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: fillOpacity,
                strokeOpacity = attr("stroke-opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: strokeOpacity,
                strokeWidth = attr("stroke-width")?.parseSvgLength() ?: strokeWidth,
                strokeCap = attr("stroke-linecap")?.lowercase(Locale.ROOT) ?: strokeCap,
                strokeJoin = attr("stroke-linejoin")?.lowercase(Locale.ROOT) ?: strokeJoin,
            )
        }

        fun fillColor(defaultFill: String? = "#000000"): Int? =
            parseSvgColor(fill ?: defaultFill, opacity * fillOpacity)

        fun strokeColor(): Int? =
            if (strokeWidth <= 0f) null else parseSvgColor(stroke, opacity * strokeOpacity)
    }

    private fun parseChildren(parent: Element, parentStyle: StyleContext, out: MutableList<SvgShape>) {
        val currentStyle = parentStyle.resolve(parent)
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val tag = element.tagName.substringAfterLast(':').lowercase(Locale.ROOT)
            val style = currentStyle.resolve(element)
            when (tag) {
                "g", "svg" -> parseChildren(element, style, out)
                "path" -> {
                    val d = element.getAttribute("d").trim()
                    if (d.isEmpty()) continue
                    val strokeColor = style.strokeColor()
                    val scanlines = if (style.fill == null && strokeColor != null) {
                        tryParseScanlines(d)
                    } else {
                        null
                    }
                    if (scanlines != null && strokeColor != null) {
                        out += SvgShape.Scanlines(scanlines, strokeColor, style.strokeWidth)
                    } else {
                        val fillColor = style.fillColor(
                            defaultFill = if (style.fill == null && strokeColor != null && isOpenLinearPath(d)) {
                                "none"
                            } else {
                                "#000000"
                            },
                        )
                        if (fillColor != null || strokeColor != null) {
                            out += SvgShape.PathData(
                                d = d,
                                fillArgb = fillColor,
                                strokeArgb = strokeColor,
                                strokeWidth = style.strokeWidth,
                                strokeCap = style.strokeCap,
                                strokeJoin = style.strokeJoin,
                            )
                        }
                    }
                }
                "rect" -> {
                    val w = element.getAttribute("width").parseSvgLength() ?: 0f
                    val h = element.getAttribute("height").parseSvgLength() ?: 0f
                    if (w <= 0f || h <= 0f) continue
                    val rx = element.getAttribute("rx").parseSvgLength() ?: 0f
                    val ry = element.getAttribute("ry").parseSvgLength() ?: rx
                    out += SvgShape.Rect(
                        x = element.getAttribute("x").parseSvgLength() ?: 0f,
                        y = element.getAttribute("y").parseSvgLength() ?: 0f,
                        width = w,
                        height = h,
                        rx = rx,
                        ry = ry,
                        fillArgb = style.fillColor(),
                        strokeArgb = style.strokeColor(),
                        strokeWidth = style.strokeWidth,
                        strokeCap = style.strokeCap,
                        strokeJoin = style.strokeJoin,
                    )
                }
                "circle" -> {
                    val r = element.getAttribute("r").parseSvgLength() ?: 0f
                    if (r <= 0f) continue
                    out += SvgShape.Circle(
                        cx = element.getAttribute("cx").parseSvgLength() ?: 0f,
                        cy = element.getAttribute("cy").parseSvgLength() ?: 0f,
                        r = r,
                        fillArgb = style.fillColor(),
                        strokeArgb = style.strokeColor(),
                        strokeWidth = style.strokeWidth,
                        strokeCap = style.strokeCap,
                        strokeJoin = style.strokeJoin,
                    )
                }
                "ellipse" -> {
                    val rx = element.getAttribute("rx").parseSvgLength() ?: 0f
                    val ry = element.getAttribute("ry").parseSvgLength() ?: 0f
                    if (rx <= 0f || ry <= 0f) continue
                    out += SvgShape.Ellipse(
                        cx = element.getAttribute("cx").parseSvgLength() ?: 0f,
                        cy = element.getAttribute("cy").parseSvgLength() ?: 0f,
                        rx = rx,
                        ry = ry,
                        fillArgb = style.fillColor(),
                        strokeArgb = style.strokeColor(),
                        strokeWidth = style.strokeWidth,
                        strokeCap = style.strokeCap,
                        strokeJoin = style.strokeJoin,
                    )
                }
                "line" -> {
                    val strokeColor = style.strokeColor() ?: continue
                    out += SvgShape.Line(
                        x1 = element.getAttribute("x1").parseSvgLength() ?: 0f,
                        y1 = element.getAttribute("y1").parseSvgLength() ?: 0f,
                        x2 = element.getAttribute("x2").parseSvgLength() ?: 0f,
                        y2 = element.getAttribute("y2").parseSvgLength() ?: 0f,
                        strokeArgb = strokeColor,
                        strokeWidth = style.strokeWidth,
                        strokeCap = style.strokeCap,
                        strokeJoin = style.strokeJoin,
                    )
                }
                "polyline", "polygon" -> {
                    val numbers = element.getAttribute("points")
                        .trim()
                        .split(Regex("[\\s,]+"))
                        .filter(String::isNotEmpty)
                        .mapNotNull(String::toFloatOrNull)
                    if (numbers.size < 4) continue
                    val closed = tag == "polygon"
                    out += SvgShape.Poly(
                        points = numbers.toFloatArray(),
                        closed = closed,
                        fillArgb = style.fillColor(defaultFill = if (closed) "#000000" else "none"),
                        strokeArgb = style.strokeColor(),
                        strokeWidth = style.strokeWidth,
                        strokeCap = style.strokeCap,
                        strokeJoin = style.strokeJoin,
                    )
                }
            }
        }
    }

    /** Recognizes `M x,y h w ...` scanline paths generated by pixel-perfect-svg. */
    private fun tryParseScanlines(d: String): List<SvgScanline>? {
        val result = ArrayList<SvgScanline>()
        var index = 0
        var currentX = 0f
        var currentY = 0f
        while (index < d.length) {
            while (index < d.length && (d[index].isWhitespace() || d[index] == ',')) index++
            if (index >= d.length) break
            val command = d[index++]
            when (command) {
                'M', 'm' -> {
                    val x = d.readFloat(index) ?: return null
                    index = x.second
                    val y = d.readFloat(index) ?: return null
                    index = y.second
                    if (command == 'M') {
                        currentX = x.first
                        currentY = y.first
                    } else {
                        currentX += x.first
                        currentY += y.first
                    }
                }
                'h', 'H' -> {
                    val value = d.readFloat(index) ?: return null
                    index = value.second
                    val endX = if (command == 'h') currentX + value.first else value.first
                    val left = min(currentX, endX)
                    val width = kotlin.math.abs(endX - currentX)
                    if (width > 0f) result += SvgScanline(left, currentY, width)
                    currentX = endX
                }
                else -> return null
            }
        }
        return result.takeIf(List<SvgScanline>::isNotEmpty)
    }

    private fun isOpenLinearPath(d: String): Boolean =
        d.none { it == 'Z' || it == 'z' || it == 'C' || it == 'c' || it == 'Q' || it == 'q' || it == 'A' || it == 'a' }

    private fun String.readFloat(startIndex: Int): Pair<Float, Int>? {
        var i = startIndex
        while (i < length && (this[i].isWhitespace() || this[i] == ',')) i++
        if (i >= length) return null
        val numStart = i
        if (this[i] == '+' || this[i] == '-') i++
        var hasDigits = false
        while (i < length && this[i].isDigit()) {
            i++
            hasDigits = true
        }
        if (i < length && this[i] == '.') {
            i++
            while (i < length && this[i].isDigit()) {
                i++
                hasDigits = true
            }
        }
        if (!hasDigits) return null
        val value = substring(numStart, i).toFloatOrNull() ?: return null
        return value to i
    }

    private fun String.parseSvgLength(): Float? {
        val trimmed = trim().removeSuffix("px").trim()
        if (trimmed.isEmpty() || trimmed.endsWith('%')) return null
        return trimmed.toFloatOrNull()
    }

    private fun parseSvgColor(raw: String?, opacity: Float): Int? {
        val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (value.isEmpty() || value == "none" || value == "transparent") return null
        val alphaMultiplier = opacity.coerceIn(0f, 1f)
        if (alphaMultiplier <= 0f) return null
        val baseArgb = when {
            value.startsWith("#") -> {
                val hex = value.substring(1)
                when (hex.length) {
                    3 -> {
                        val r = hex[0].digitToIntOrNull(16) ?: return null
                        val g = hex[1].digitToIntOrNull(16) ?: return null
                        val b = hex[2].digitToIntOrNull(16) ?: return null
                        (0xFF shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
                    }
                    6 -> (0xFF shl 24) or (hex.toIntOrNull(16) ?: return null)
                    8 -> hex.toLongOrNull(16)?.toInt() ?: return null
                    else -> return null
                }
            }
            value.startsWith("rgb(") && value.endsWith(")") -> {
                val parts = value.removeSurrounding("rgb(", ")").split(',').map(String::trim)
                if (parts.size != 3) return null
                val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
                val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
                val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> NAMED_COLORS[value] ?: return null
        }
        val baseAlpha = (baseArgb ushr 24) and 0xFF
        val finalAlpha = (baseAlpha * alphaMultiplier).roundToInt().coerceIn(0, 255)
        if (finalAlpha == 0) return null
        return (finalAlpha shl 24) or (baseArgb and 0x00FFFFFF)
    }

    private val NAMED_COLORS = mapOf(
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "purple" to 0xFF800080.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
    )
}

/** A single APNG frame, repackaged as a standalone PNG that any PNG decoder can read. */
internal class ApngFrame(
    val width: Int,
    val height: Int,
    val xOffset: Int,
    val yOffset: Int,
    val delayMillis: Int,
    val disposeOp: Int,
    val blendOp: Int,
    val png: ByteArray,
)

internal class ApngAnimation(
    val width: Int,
    val height: Int,
    val frames: List<ApngFrame>,
)

/** A fully composited animation; every frame covers the whole canvas as non-premultiplied ARGB. */
internal class RasterAnimation(
    val width: Int,
    val height: Int,
    val frames: List<IntArray>,
    val delaysMillis: IntArray,
)

internal object ApngCodec {
    const val DISPOSE_NONE = 0
    const val DISPOSE_BACKGROUND = 1
    const val DISPOSE_PREVIOUS = 2
    const val BLEND_SOURCE = 0
    const val BLEND_OVER = 1

    /**
     * Splits an APNG into its frames. Returns null for an ordinary (non-animated) PNG so callers
     * can fall back to a regular bitmap decoder. Throws [IllegalArgumentException] when malformed.
     */
    fun parse(bytes: ByteArray): ApngAnimation? {
        require(bytes.hasPngSignature()) { "Not a PNG file." }
        var offset = PNG_SIGNATURE.size
        var header: ByteArray? = null
        var animated = false
        var seenImageData = false
        val sharedChunks = mutableListOf<Pair<String, ByteArray>>()
        val frames = mutableListOf<ApngFrame>()
        var pendingControl: FrameControl? = null
        val pendingData = ByteArrayOutputStream()

        fun flushFrame() {
            val control = pendingControl ?: return
            val imageHeader = requireNotNull(header) { "APNG frame appears before IHDR." }
            require(pendingData.size() > 0) { "APNG frame ${frames.size} has no image data." }
            frames += ApngFrame(
                width = control.width,
                height = control.height,
                xOffset = control.xOffset,
                yOffset = control.yOffset,
                delayMillis = control.delayMillis,
                // The first frame has nothing to revert to, so the spec treats PREVIOUS as BACKGROUND.
                disposeOp = if (frames.isEmpty() && control.disposeOp == DISPOSE_PREVIOUS) {
                    DISPOSE_BACKGROUND
                } else {
                    control.disposeOp
                },
                blendOp = control.blendOp,
                png = standalonePng(imageHeader, control.width, control.height, sharedChunks, pendingData.toByteArray()),
            )
            pendingControl = null
            pendingData.reset()
        }

        while (offset + CHUNK_OVERHEAD <= bytes.size) {
            val length = bytes.readInt(offset)
            require(length >= 0 && offset + CHUNK_OVERHEAD + length <= bytes.size) { "Truncated PNG chunk." }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            when (type) {
                "IHDR" -> header = bytes.copyOfRange(dataStart, dataStart + length)
                "acTL" -> animated = true
                "fcTL" -> {
                    flushFrame()
                    pendingControl = FrameControl.parse(bytes, dataStart, length)
                }
                "IDAT" -> {
                    seenImageData = true
                    // An IDAT without a preceding fcTL is a default image that isn't part of the animation.
                    if (pendingControl != null) pendingData.write(bytes, dataStart, length)
                }
                "fdAT" -> {
                    require(length >= 4) { "Malformed fdAT chunk." }
                    if (pendingControl != null) pendingData.write(bytes, dataStart + 4, length - 4)
                }
                "IEND" -> break
                else -> if (!seenImageData && type !in NON_SHARED_CHUNKS) {
                    sharedChunks += type to bytes.copyOfRange(dataStart, dataStart + length)
                }
            }
            offset += CHUNK_OVERHEAD + length
        }
        flushFrame()

        val imageHeader = requireNotNull(header) { "PNG has no IHDR chunk." }
        if (!animated) return null
        require(frames.isNotEmpty()) { "APNG has no frames." }
        val width = imageHeader.readInt(0)
        val height = imageHeader.readInt(4)
        require(width > 0 && height > 0) { "APNG has an empty canvas." }
        frames.forEach { frame ->
            require(
                frame.width > 0 && frame.height > 0 &&
                    frame.xOffset >= 0 && frame.yOffset >= 0 &&
                    frame.xOffset + frame.width <= width &&
                    frame.yOffset + frame.height <= height,
            ) { "APNG frame lies outside of the canvas." }
        }
        return ApngAnimation(width, height, frames)
    }

    /**
     * Composites every frame of [animation] according to its blend and disposal operations, then
     * centers the result on the 19x19 Messenger emoticon canvas when smaller.
     */
    fun composite(
        animation: ApngAnimation,
        decodeFramePixels: (ApngFrame) -> IntArray,
    ): RasterAnimation {
        val width = animation.width
        val height = animation.height
        val canvas = IntArray(width * height)
        val compositedFrames = ArrayList<IntArray>(animation.frames.size)
        val delays = IntArray(animation.frames.size)
        animation.frames.forEachIndexed { index, frame ->
            val restore = if (frame.disposeOp == DISPOSE_PREVIOUS) canvas.copyOf() else null
            val patch = decodeFramePixels(frame)
            require(patch.size == frame.width * frame.height) { "Decoded APNG frame size mismatch." }
            for (row in 0 until frame.height) {
                val canvasY = frame.yOffset + row
                val canvasRowOffset = canvasY * width
                val patchRowOffset = row * frame.width
                for (col in 0 until frame.width) {
                    val canvasIndex = canvasRowOffset + frame.xOffset + col
                    val sourcePixel = patch[patchRowOffset + col]
                    canvas[canvasIndex] = if (frame.blendOp == BLEND_SOURCE) {
                        sourcePixel
                    } else {
                        blendSourceOver(canvas[canvasIndex], sourcePixel)
                    }
                }
            }
            compositedFrames += canvas.copyOf()
            delays[index] = frame.delayMillis
            when (frame.disposeOp) {
                DISPOSE_BACKGROUND -> {
                    for (row in 0 until frame.height) {
                        val rowStart = (frame.yOffset + row) * width + frame.xOffset
                        canvas.fill(0, rowStart, rowStart + frame.width)
                    }
                }
                DISPOSE_PREVIOUS -> restore?.copyInto(canvas)
            }
        }
        return RasterAnimation(width, height, compositedFrames, delays).padToEmoticonGrid()
    }

    private class FrameControl(
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        val delayMillis: Int,
        val disposeOp: Int,
        val blendOp: Int,
    ) {
        companion object {
            fun parse(bytes: ByteArray, offset: Int, length: Int): FrameControl {
                require(length >= 26) { "Malformed fcTL chunk." }
                val delayNumerator = bytes.readUShort(offset + 20)
                val delayDenominator = bytes.readUShort(offset + 22).takeIf { it != 0 } ?: 100
                return FrameControl(
                    width = bytes.readInt(offset + 4),
                    height = bytes.readInt(offset + 8),
                    xOffset = bytes.readInt(offset + 12),
                    yOffset = bytes.readInt(offset + 16),
                    delayMillis = normalizedFrameDelay(delayNumerator * 1_000 / delayDenominator),
                    disposeOp = (bytes[offset + 24].toInt() and 0xFF).coerceIn(DISPOSE_NONE, DISPOSE_PREVIOUS),
                    blendOp = (bytes[offset + 25].toInt() and 0xFF).coerceIn(BLEND_SOURCE, BLEND_OVER),
                )
            }
        }
    }

    private fun standalonePng(
        header: ByteArray,
        width: Int,
        height: Int,
        sharedChunks: List<Pair<String, ByteArray>>,
        imageData: ByteArray,
    ): ByteArray {
        val frameHeader = header.copyOf().apply {
            writeInt(0, width)
            writeInt(4, height)
        }
        return ByteArrayOutputStream(imageData.size + 256).apply {
            write(PNG_SIGNATURE)
            writeChunk("IHDR", frameHeader)
            sharedChunks.forEach { (type, data) -> writeChunk(type, data) }
            writeChunk("IDAT", imageData)
            writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(ByteArray(4).apply { writeInt(0, data.size) })
        write(typeBytes)
        write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        write(ByteArray(4).apply { writeInt(0, crc) })
    }

    private fun ByteArray.hasPngSignature(): Boolean =
        size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val CHUNK_OVERHEAD = 12
    private val NON_SHARED_CHUNKS = setOf("acTL", "fcTL", "fdAT", "tEXt", "zTXt", "iTXt")
}

internal object GifCodec {
    /** Decodes and composites every frame of a GIF87a/GIF89a file, padded to the 19x19 emoticon grid. */
    fun decode(bytes: ByteArray): RasterAnimation {
        val reader = GifReader(bytes)
        val signature = reader.ascii(6)
        require(signature == "GIF87a" || signature == "GIF89a") { "Not a GIF file." }
        val width = reader.u16()
        val height = reader.u16()
        val screenFlags = reader.u8()
        reader.skip(2) // Background color index and pixel aspect ratio.
        require(width > 0 && height > 0) { "GIF has an empty canvas." }
        val globalColors = if (screenFlags and 0x80 != 0) reader.colorTable(2 shl (screenFlags and 0x07)) else null

        val canvas = IntArray(width * height)
        val frames = mutableListOf<IntArray>()
        val delays = mutableListOf<Int>()
        var delayMillis = 0
        var disposal = 0
        var transparentIndex = -1
        blocks@ while (reader.hasRemaining) {
            when (reader.u8()) {
                EXTENSION_INTRODUCER -> {
                    if (reader.u8() == GRAPHIC_CONTROL_LABEL) {
                        val blockSize = reader.u8()
                        val blockStart = reader.position
                        require(blockSize >= 4) { "Malformed GIF graphic control extension." }
                        val flags = reader.u8()
                        delayMillis = reader.u16() * 10
                        val transparent = reader.u8()
                        disposal = (flags shr 2) and 0x07
                        transparentIndex = if (flags and 0x01 != 0) transparent else -1
                        reader.position = blockStart + blockSize
                    }
                    reader.skipSubBlocks()
                }
                IMAGE_SEPARATOR -> {
                    val left = reader.u16()
                    val top = reader.u16()
                    val frameWidth = reader.u16()
                    val frameHeight = reader.u16()
                    val imageFlags = reader.u8()
                    val colors = if (imageFlags and 0x80 != 0) {
                        reader.colorTable(2 shl (imageFlags and 0x07))
                    } else {
                        requireNotNull(globalColors) { "GIF frame has no color table." }
                    }
                    val minimumCodeSize = reader.u8()
                    val indices = decodeLzw(reader.subBlocks(), minimumCodeSize, frameWidth * frameHeight)
                    val restore = if (disposal == DISPOSE_PREVIOUS) canvas.copyOf() else null
                    val rows = if (imageFlags and 0x40 != 0) interlacedRows(frameHeight) else null
                    for (sourceRow in 0 until frameHeight) {
                        val y = top + (rows?.get(sourceRow) ?: sourceRow)
                        if (y >= height) continue
                        for (column in 0 until frameWidth) {
                            val x = left + column
                            if (x >= width) break
                            val index = indices[sourceRow * frameWidth + column].toInt() and 0xFF
                            if (index != transparentIndex && index < colors.size) {
                                canvas[y * width + x] = colors[index]
                            }
                        }
                    }
                    frames += canvas.copyOf()
                    delays += normalizedFrameDelay(delayMillis)
                    when {
                        disposal == DISPOSE_BACKGROUND -> {
                            for (y in top until minOf(height, top + frameHeight)) {
                                val rowStart = y * width
                                canvas.fill(0, rowStart + left.coerceAtMost(width), rowStart + minOf(width, left + frameWidth))
                            }
                        }
                        restore != null -> restore.copyInto(canvas)
                    }
                    delayMillis = 0
                    disposal = 0
                    transparentIndex = -1
                }
                TRAILER -> break@blocks
                else -> break@blocks
            }
        }
        require(frames.isNotEmpty()) { "GIF has no frames." }
        return RasterAnimation(width, height, frames, delays.toIntArray()).padToEmoticonGrid()
    }

    /** Variable-length-code LZW decoder; returns color table indices, padding truncated data with 0. */
    private fun decodeLzw(data: ByteArray, minimumCodeSize: Int, pixelCount: Int): ByteArray {
        require(minimumCodeSize in 1..11) { "Invalid GIF LZW code size." }
        val output = ByteArray(pixelCount)
        val clearCode = 1 shl minimumCodeSize
        val endCode = clearCode + 1
        val prefix = ShortArray(MAX_LZW_CODES)
        val suffix = ByteArray(MAX_LZW_CODES)
        val stack = ByteArray(MAX_LZW_CODES + 1)
        for (code in 0 until clearCode) suffix[code] = code.toByte()

        var codeSize = minimumCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var nextCode = clearCode + 2
        var previousCode = -1
        var firstByte = 0
        var bitBuffer = 0
        var bitCount = 0
        var dataIndex = 0
        var written = 0
        while (written < pixelCount) {
            while (bitCount < codeSize) {
                if (dataIndex >= data.size) return output
                bitBuffer = bitBuffer or ((data[dataIndex++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            val code = bitBuffer and codeMask
            bitBuffer = bitBuffer ushr codeSize
            bitCount -= codeSize

            when {
                code == clearCode -> {
                    codeSize = minimumCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    nextCode = clearCode + 2
                    previousCode = -1
                }
                code == endCode -> return output
                previousCode == -1 -> {
                    require(code < clearCode) { "Invalid first GIF LZW code." }
                    output[written++] = suffix[code]
                    previousCode = code
                    firstByte = code
                }
                else -> {
                    require(code <= nextCode) { "Invalid GIF LZW code." }
                    var stackSize = 0
                    var current = code
                    if (code == nextCode) {
                        // The KwKwK case: the code being defined is the previous string plus its first byte.
                        stack[stackSize++] = firstByte.toByte()
                        current = previousCode
                    }
                    while (current >= clearCode) {
                        stack[stackSize++] = suffix[current]
                        current = prefix[current].toInt()
                    }
                    firstByte = suffix[current].toInt() and 0xFF
                    stack[stackSize++] = firstByte.toByte()
                    if (nextCode < MAX_LZW_CODES) {
                        prefix[nextCode] = previousCode.toShort()
                        suffix[nextCode] = firstByte.toByte()
                        nextCode++
                        if (nextCode and codeMask == 0 && nextCode < MAX_LZW_CODES) {
                            codeSize++
                            codeMask = (1 shl codeSize) - 1
                        }
                    }
                    previousCode = code
                    while (stackSize > 0 && written < pixelCount) {
                        output[written++] = stack[--stackSize]
                    }
                }
            }
        }
        return output
    }

    /** Maps each decoded row of an interlaced image to its row on the canvas. */
    private fun interlacedRows(height: Int): IntArray {
        val rows = IntArray(height)
        var decodedRow = 0
        for ((start, step) in INTERLACE_PASSES) {
            var row = start
            while (row < height) {
                rows[decodedRow++] = row
                row += step
            }
        }
        return rows
    }

    private class GifReader(private val bytes: ByteArray) {
        var position = 0
        val hasRemaining: Boolean get() = position < bytes.size

        fun u8(): Int {
            require(position < bytes.size) { "Truncated GIF file." }
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun skip(count: Int) {
            require(position + count <= bytes.size) { "Truncated GIF file." }
            position += count
        }

        fun ascii(count: Int): String {
            require(position + count <= bytes.size) { "Truncated GIF file." }
            return String(bytes, position, count, Charsets.US_ASCII).also { position += count }
        }

        fun colorTable(size: Int): IntArray = IntArray(size) {
            0xFF000000.toInt() or (u8() shl 16) or (u8() shl 8) or u8()
        }

        fun subBlocks(): ByteArray = ByteArrayOutputStream().apply {
            while (true) {
                val size = u8()
                if (size == 0) break
                require(position + size <= bytes.size) { "Truncated GIF data block." }
                write(bytes, position, size)
                position += size
            }
        }.toByteArray()

        fun skipSubBlocks() {
            while (true) {
                val size = u8()
                if (size == 0) break
                skip(size)
            }
        }
    }

    private const val EXTENSION_INTRODUCER = 0x21
    private const val GRAPHIC_CONTROL_LABEL = 0xF9
    private const val IMAGE_SEPARATOR = 0x2C
    private const val TRAILER = 0x3B
    private const val DISPOSE_BACKGROUND = 2
    private const val DISPOSE_PREVIOUS = 3
    private const val MAX_LZW_CODES = 4096
    private val INTERLACE_PASSES = listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)
}

/**
 * Centers animations that are narrower/shorter than 19x19 (such as the 17x19 birthday cake) onto
 * the 19x19 Messenger emoticon canvas so every emoticon shares the same 19x19 coordinate space.
 */
internal fun RasterAnimation.padToEmoticonGrid(): RasterAnimation {
    val targetWidth = max(MSN_EMOTICON_GRID_PX, width)
    val targetHeight = max(MSN_EMOTICON_GRID_PX, height)
    if (targetWidth == width && targetHeight == height) return this
    val padX = (targetWidth - width) / 2
    val padY = (targetHeight - height) / 2
    val paddedFrames = frames.map { source ->
        val padded = IntArray(targetWidth * targetHeight)
        for (row in 0 until height) {
            source.copyInto(
                destination = padded,
                destinationOffset = (padY + row) * targetWidth + padX,
                startIndex = row * width,
                endIndex = (row + 1) * width,
            )
        }
        padded
    }
    return RasterAnimation(targetWidth, targetHeight, paddedFrames, delaysMillis)
}

/** Straight (non-premultiplied) Porter-Duff source-over compositing of two ARGB pixels. */
private fun blendSourceOver(dst: Int, src: Int): Int {
    val srcA = (src ushr 24) and 0xFF
    if (srcA == 0) return dst
    if (srcA == 0xFF) return src
    val dstA = (dst ushr 24) and 0xFF
    if (dstA == 0) return src
    val invSrcA = 255 - srcA
    val outA = srcA + (dstA * invSrcA + 127) / 255
    if (outA == 0) return 0
    val srcR = (src ushr 16) and 0xFF
    val srcG = (src ushr 8) and 0xFF
    val srcB = src and 0xFF
    val dstR = (dst ushr 16) and 0xFF
    val dstG = (dst ushr 8) and 0xFF
    val dstB = dst and 0xFF
    val dstWeight = (dstA * invSrcA + 127) / 255
    val outR = ((srcR * srcA + dstR * dstWeight + outA / 2) / outA).coerceIn(0, 255)
    val outG = ((srcG * srcA + dstG * dstWeight + outA / 2) / outA).coerceIn(0, 255)
    val outB = ((srcB * srcA + dstB * dstWeight + outA / 2) / outA).coerceIn(0, 255)
    return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
}

/** Browsers treat near-zero frame delays as 100 ms; matching that keeps animations from racing. */
private fun normalizedFrameDelay(delayMillis: Int): Int = if (delayMillis <= 10) 100 else delayMillis

private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readUShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.writeInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
