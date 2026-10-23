@file:Suppress("DEPRECATION") // Movie provides a compositing GIF decoder on every supported API.

package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.DisplayPictureFormat
import org.aralozkaya.msngr.protocol.DisplayPictureLimits
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import org.aralozkaya.msngr.protocol.ProfileGifEncoder
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

internal data class DisplayPictureSelection(
    val bytes: ByteArray,
    val format: DisplayPictureFormat,
    val width: Int,
    val height: Int,
    val animation: GifDisplayPicture? = null,
) {
    val requiresResize: Boolean get() = bytes.size > DisplayPictureLimits.MAX_BYTES ||
        width.toLong() * height > DisplayPictureLimits.MAX_PIXELS
    val dataUri: String get() = "data:${format.mimeType};base64,${org.aralozkaya.msngr.protocol.MsnBase64.encode(bytes)}"
}

internal fun Context.readProfilePictureBytes(uri: String): ByteArray {
    uri.inlineImageBytes()?.let { return it }
    return requireNotNull(openDisplayPictureInputStream(uri)) { "That image could not be opened." }.use { input ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= DisplayPictureLimits.MAX_SOURCE_BYTES - output.size()) {
                    "Select an image smaller than 32 MiB."
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}

internal suspend fun Context.inspectDisplayPicture(uri: String): DisplayPictureSelection = withContext(Dispatchers.IO) {
    val bytes = readProfilePictureBytes(uri)
    val format = requireNotNull(DisplayPictureFormat.detect(bytes)) {
        "Choose a PNG, JPEG, BMP, or GIF picture. Animated pictures must use GIF."
    }
    if (format == DisplayPictureFormat.GIF) {
        val gif = requireNotNull(GifDisplayPicture.parse(bytes, DisplayPictureLimits.MAX_SOURCE_BYTES,
            DisplayPictureLimits.MAX_SOURCE_PIXELS)) { "This GIF is damaged or exceeds the 500-frame or 16-megapixel safety limit." }
        require(gif.width.toLong() * gif.height * gif.frameDelaysMillis.size <= DisplayPictureLimits.MAX_ANIMATION_PIXELS) {
            "This animation is too complex to resize safely. Choose a smaller GIF."
        }
        requireNotNull(Movie.decodeByteArray(bytes, 0, bytes.size)) { "This GIF could not be decoded." }
        DisplayPictureSelection(bytes, format, gif.width, gif.height, gif)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image is damaged or could not be decoded." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= DisplayPictureLimits.MAX_SOURCE_PIXELS) {
            "Choose an image of at most 16 megapixels."
        }
        // Do not flatten an APNG into a still picture.
        require(format != DisplayPictureFormat.PNG || !bytes.hasAnimatedPngChunk()) {
            "Animated PNG pictures are not supported by Messenger. Choose an animated GIF."
        }
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "This image is damaged or could not be decoded."
        }.recycle()
        DisplayPictureSelection(bytes, format, bounds.outWidth, bounds.outHeight)
    }
}

/** Called only after the user accepts the resize dialog. Keeps aspect ratio, frames, and timing. */
internal suspend fun resizeDisplayPicture(selection: DisplayPictureSelection): DisplayPictureSelection = withContext(Dispatchers.Default) {
    if (!selection.requiresResize) return@withContext selection
    val source = if (selection.animation == null) requireNotNull(
        BitmapFactory.decodeByteArray(selection.bytes, 0, selection.bytes.size),
    ) else null
    val movie = selection.animation?.let { requireNotNull(Movie.decodeByteArray(selection.bytes, 0, selection.bytes.size)) }
    suspend fun encode(edge: Int): DisplayPictureSelection {
        coroutineContext.ensureActive()
        val longest = max(selection.width, selection.height)
        val width = (selection.width.toLong() * edge / longest).toInt().coerceAtLeast(1)
        val height = (selection.height.toLong() * edge / longest).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val bytes = if (movie != null) {
                val gif = requireNotNull(selection.animation)
                val encoder = ProfileGifEncoder(width, height, gif.loopCount)
                val pixels = IntArray(width * height)
                var elapsed = 0
                gif.frameDelaysMillis.forEachIndexed { index, delay ->
                    coroutineContext.ensureActive()
                    bitmap.eraseColor(Color.TRANSPARENT)
                    movie.setTime(elapsed)
                    canvas.save()
                    canvas.scale(width.toFloat() / movie.width(), height.toFloat() / movie.height())
                    movie.draw(canvas, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    encoder.addFrame(pixels, gif.frameDelaysCentiseconds[index])
                    elapsed += delay
                }
                encoder.finish()
            } else {
                canvas.drawBitmap(requireNotNull(source), null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            }
            val format = if (movie != null) DisplayPictureFormat.GIF else DisplayPictureFormat.PNG
            return DisplayPictureSelection(bytes, format, width, height,
                if (movie != null) requireNotNull(GifDisplayPicture.parse(bytes, DisplayPictureLimits.MAX_SOURCE_BYTES)) else null)
        } finally { bitmap.recycle() }
    }
    try {
        // Find the largest output that fits, using full palettes and lossless PNG for stills.
        var low = 1
        var high = minOf(max(selection.width, selection.height),
            (max(selection.width, selection.height) * sqrt(DisplayPictureLimits.MAX_PIXELS.toDouble() /
                (selection.width.toLong() * selection.height))).toInt()).coerceAtLeast(1)
        var best: DisplayPictureSelection? = null
        while (low <= high) {
            val edge = (low + high) / 2
            val candidate = encode(edge)
            if (!candidate.requiresResize) { best = candidate; low = edge + 1 } else high = edge - 1
        }
        requireNotNull(best) { "This picture cannot fit Messenger's 200 KB limit while preserving its animation." }
    } finally { source?.recycle() }
}

private fun ByteArray.hasAnimatedPngChunk(): Boolean {
    var offset = 8
    while (offset + 12 <= size) {
        val length = ((this[offset].toLong() and 255) shl 24) or ((this[offset + 1].toLong() and 255) shl 16) or
            ((this[offset + 2].toLong() and 255) shl 8) or (this[offset + 3].toLong() and 255)
        if (String(this, offset + 4, 4, Charsets.US_ASCII) == "acTL") return true
        if (length > size - offset - 12) return false
        offset += length.toInt() + 12
    }
    return false
}
