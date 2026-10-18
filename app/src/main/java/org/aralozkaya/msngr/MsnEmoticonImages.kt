package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.core.graphics.withSave
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Messenger emoticons are 19x19 px (19dp in UI density units). */
internal val MSN_EMOTICON_SIZE_DP = MSN_EMOTICON_GRID_PX.dp

/** Decoded emoticon artwork that can be drawn inside a square box. */
internal sealed class MsnEmoticonImage {
    abstract val width: Int
    abstract val height: Int
    open val isAnimated: Boolean get() = false

    /** Draws the frame visible at [uptimeMillis] inside the square at ([left], [top]) of size [boxPx]. */
    abstract fun draw(canvas: Canvas, left: Float, top: Float, boxPx: Float, uptimeMillis: Long)

    /** Milliseconds until the next animation frame, or null for static artwork. */
    open fun millisUntilNextFrame(uptimeMillis: Long): Long? = null
}

/** Raster artwork: a static PNG/GIF (single frame), or a composited APNG/GIF animation. */
internal class MsnFrameEmoticonImage(
    private val frames: List<Bitmap>,
    private val frameDurationsMillis: IntArray,
) : MsnEmoticonImage() {
    init {
        require(frames.isNotEmpty() && frames.size == frameDurationsMillis.size)
    }

    override val width: Int = frames.first().width
    override val height: Int = frames.first().height
    private val totalDurationMillis = frameDurationsMillis.sum().toLong()
    override val isAnimated: Boolean = frames.size > 1 && totalDurationMillis > 0
    private val dstRect = RectF()

    override fun draw(canvas: Canvas, left: Float, top: Float, boxPx: Float, uptimeMillis: Long) {
        if (boxPx <= 0f) return
        val frame = frames[frameIndexAt(uptimeMillis)]
        val scale = min(boxPx / width.toFloat(), boxPx / height.toFloat())
        val drawnWidth = width * scale
        val drawnHeight = height * scale
        val dx = left + (boxPx - drawnWidth) / 2f
        val dy = top + (boxPx - drawnHeight) / 2f
        synchronized(dstRect) {
            dstRect.set(dx, dy, dx + drawnWidth, dy + drawnHeight)
            canvas.drawBitmap(frame, null, dstRect, PIXEL_PAINT)
        }
    }

    // A shared clock keeps every copy of the same emoticon animating in step, like Messenger did.
    override fun millisUntilNextFrame(uptimeMillis: Long): Long? {
        if (!isAnimated) return null
        var elapsed = uptimeMillis % totalDurationMillis
        frameDurationsMillis.forEach { duration ->
            if (elapsed < duration) return duration - elapsed
            elapsed -= duration
        }
        return 1L
    }

    private fun frameIndexAt(uptimeMillis: Long): Int {
        if (!isAnimated) return 0
        var elapsed = uptimeMillis % totalDurationMillis
        frameDurationsMillis.forEachIndexed { index, duration ->
            if (elapsed < duration) return index
            elapsed -= duration
        }
        return frames.lastIndex
    }
}

/** Vector SVG artwork, rasterized once per drawn size. */
internal class MsnVectorEmoticonImage(
    private val document: SvgDocument,
) : MsnEmoticonImage() {
    override val width: Int = max(1, document.width.roundToInt())
    override val height: Int = max(1, document.height.roundToInt())

    private val rasters = object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean =
            size > MAX_CACHED_RASTERS
    }

    override fun draw(canvas: Canvas, left: Float, top: Float, boxPx: Float, uptimeMillis: Long) {
        val targetPx = max(1, boxPx.roundToInt())
        val bitmap = raster(targetPx)
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + boxPx, top + boxPx),
            PIXEL_PAINT,
        )
    }

    private fun raster(boxPx: Int): Bitmap = synchronized(this) {
        rasters.getOrPut(boxPx) {
            Bitmap.createBitmap(boxPx, boxPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                renderToCanvas(Canvas(bitmap), boxPx.toFloat(), boxPx.toFloat())
            }
        }
    }

    private fun renderToCanvas(canvas: Canvas, targetWidth: Float, targetHeight: Float) {
        val scaleX = targetWidth / document.viewBoxWidth
        val scaleY = targetHeight / document.viewBoxHeight
        val paint = Paint().apply {
            isAntiAlias = !document.crispEdges
            isDither = false
        }
        document.shapes.forEach { shape ->
            when (shape) {
                is SvgShape.Scanlines -> {
                    paint.style = Paint.Style.FILL
                    paint.color = shape.colorArgb
                    val halfStroke = shape.strokeWidth / 2f
                    shape.lines.forEach { line ->
                        val l = (line.x - document.viewBoxX) * scaleX
                        val t = (line.y - halfStroke - document.viewBoxY) * scaleY
                        val r = (line.x + line.width - document.viewBoxX) * scaleX
                        val b = (line.y + halfStroke - document.viewBoxY) * scaleY
                        canvas.drawRect(l, t, r, b, paint)
                    }
                }
                else -> {
                    canvas.withSave {
                        scale(scaleX, scaleY)
                        translate(-document.viewBoxX, -document.viewBoxY)
                        drawGeneralShape(this, shape, paint)
                    }
                }
            }
        }
    }

    private fun drawGeneralShape(canvas: Canvas, shape: SvgShape, paint: Paint) {
        val path = when (shape) {
            is SvgShape.Scanlines -> return
            is SvgShape.PathData -> runCatching { PathParser.createPathFromPathData(shape.d) }.getOrNull() ?: return
            is SvgShape.Rect -> Path().apply {
                if (shape.rx > 0f || shape.ry > 0f) {
                    addRoundRect(
                        RectF(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
                        shape.rx,
                        shape.ry,
                        Path.Direction.CW,
                    )
                } else {
                    addRect(
                        shape.x,
                        shape.y,
                        shape.x + shape.width,
                        shape.y + shape.height,
                        Path.Direction.CW,
                    )
                }
            }
            is SvgShape.Circle -> Path().apply {
                addCircle(shape.cx, shape.cy, shape.r, Path.Direction.CW)
            }
            is SvgShape.Ellipse -> Path().apply {
                addOval(
                    RectF(shape.cx - shape.rx, shape.cy - shape.ry, shape.cx + shape.rx, shape.cy + shape.ry),
                    Path.Direction.CW,
                )
            }
            is SvgShape.Line -> Path().apply {
                moveTo(shape.x1, shape.y1)
                lineTo(shape.x2, shape.y2)
            }
            is SvgShape.Poly -> Path().apply {
                val pts = shape.points
                if (pts.size >= 2) {
                    moveTo(pts[0], pts[1])
                    var i = 2
                    while (i + 1 < pts.size) {
                        lineTo(pts[i], pts[i + 1])
                        i += 2
                    }
                    if (shape.closed) close()
                }
            }
        }
        shape.fillArgb?.let { fill ->
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawPath(path, paint)
        }
        shape.strokeArgb?.let { stroke ->
            paint.style = Paint.Style.STROKE
            paint.color = stroke
            paint.strokeWidth = shape.strokeWidth
            paint.strokeCap = when (shape.strokeCap) {
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
            paint.strokeJoin = when (shape.strokeJoin) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val MAX_CACHED_RASTERS = 4
    }
}

/**
 * Loads emoticon artwork from the app's assets. The format is chosen by file extension:
 * - `.svg`: vector artwork (static);
 * - `.png`/`.apng`: animated PNG, or a plain static PNG;
 * - `.gif`: animated or static GIF;
 * - anything else the platform bitmap decoder understands, as a static image.
 *
 * Decoded artwork is cached for the life of the process; failures are cached too so a broken file
 * is only attempted once and callers fall through to the next candidate.
 */
internal object MsnEmoticonImages {
    private const val TAG = "MsnEmoticonImages"
    private val failed = Any()
    private val cache = ConcurrentHashMap<String, Any>()

    /** Loads the first candidate that decodes successfully. */
    fun loadFirst(context: Context, candidates: List<String>): MsnEmoticonImage? =
        candidates.firstNotNullOfOrNull { load(context, it) }

    fun load(context: Context, path: String): MsnEmoticonImage? {
        cache[path]?.let { return it as? MsnEmoticonImage }
        val decoded = try {
            context.assets.open(path).use { decode(path, it.readBytes()) }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to load emoticon artwork $path", error)
            null
        }
        val winner = cache.putIfAbsent(path, decoded ?: failed) ?: (decoded ?: failed)
        return winner as? MsnEmoticonImage
    }

    private fun decode(path: String, bytes: ByteArray): MsnEmoticonImage =
        when (path.substringAfterLast('.', "").lowercase()) {
            "svg" -> MsnVectorEmoticonImage(SvgCodec.parse(bytes))
            "png", "apng" -> ApngCodec.parse(bytes)?.let(::compositeApng) ?: decodeStaticBitmap(bytes)
            "gif" -> decodeGif(bytes)
            else -> decodeStaticBitmap(bytes)
        }

    private fun decodeGif(bytes: ByteArray): MsnEmoticonImage =
        GifCodec.decode(bytes).toFrameImage()

    private fun compositeApng(animation: ApngAnimation): MsnEmoticonImage =
        ApngCodec.composite(animation) { frame ->
            val bitmap = requireNotNull(
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size, bitmapOptions()),
            ) { "Unable to decode APNG frame." }
            val pixels = IntArray(frame.width * frame.height)
            bitmap.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            bitmap.recycle()
            pixels
        }.toFrameImage()

    private fun RasterAnimation.toFrameImage(): MsnFrameEmoticonImage {
        val bitmaps = frames.map { pixels ->
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
        return MsnFrameEmoticonImage(bitmaps, delaysMillis)
    }

    private fun decodeStaticBitmap(bytes: ByteArray): MsnEmoticonImage {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions())) {
            "Unsupported image format."
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return RasterAnimation(width, height, listOf(pixels), intArrayOf(0))
            .padToEmoticonGrid()
            .toFrameImage()
    }

    private fun bitmapOptions() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
}

/** Nearest-neighbour sampling: pixel art must not be smoothed when scaled up. */
private val PIXEL_PAINT = Paint().apply {
    isAntiAlias = false
    isFilterBitmap = false
    isDither = false
}

/**
 * Draws the emoticon's artwork inside the layout bounds of [modifier], animating it when
 * [staticOnly] is false and the resolved asset has multiple frames.
 */
@Composable
internal fun MsnEmoticonArtwork(
    emoticon: MsnEmoticonShortcut,
    staticOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val candidates = if (staticOnly) {
        emoticon.selectorAssetCandidates
    } else {
        emoticon.artworkAssetCandidates
    }
    val image = remember(context, candidates) {
        MsnEmoticonImages.loadFirst(context, candidates)
    }
    var frameTime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    if (image != null && image.isAnimated && !staticOnly) {
        LaunchedEffect(image) {
            while (true) {
                val wait = image.millisUntilNextFrame(SystemClock.uptimeMillis()) ?: break
                delay(wait)
                // Waiting for a frame pauses the animation while the window isn't being drawn.
                frameTime = withFrameMillis { SystemClock.uptimeMillis() }
            }
        }
    }
    Spacer(
        modifier = modifier
            .semantics {
                contentDescription = emoticon.name
                role = Role.Image
            }
            .drawBehind {
                val boxPx = min(size.width, size.height)
                val left = (size.width - boxPx) / 2f
                val top = (size.height - boxPx) / 2f
                image?.draw(
                    canvas = drawContext.canvas.nativeCanvas,
                    left = left,
                    top = top,
                    boxPx = boxPx,
                    uptimeMillis = if (staticOnly) 0L else frameTime,
                )
            },
    )
}
