package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

private const val DISPLAY_PICTURE_SIZE = 96
private const val DECODE_TARGET_SIZE = DISPLAY_PICTURE_SIZE * 4
private const val AVATAR_DECODE_TARGET_SIZE = 384
private const val PROFILE_PICTURE_MAX_PIXELS = 4_194_304L
private const val MAX_INLINE_IMAGE_BYTES = 1_048_576
private const val MIN_BITMAP_CACHE_KIB = 4 * 1024
private const val MAX_BITMAP_CACHE_KIB = 16 * 1024

private val displayPictureBitmapCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024L / 16L)
        .coerceIn(MIN_BITMAP_CACHE_KIB.toLong(), MAX_BITMAP_CACHE_KIB.toLong())
        .toInt(),
) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}
private val pendingAvatarDecodes = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

/** Preserves GIF animation; converts other documents to the compact square PNG used by MSNP. */
internal suspend fun Context.loadDisplayPictureBytes(uri: String?): ByteArray? =
    withContext(Dispatchers.IO) {
        if (uri.isNullOrBlank()) return@withContext null
        displayPictureBitmapCache.remove(uri)
        displayPictureBitmapCache.remove(avatarCacheKey(uri, highResolution = true))
        runCatching {
            val inlineBytes = uri.inlineImageBytes()
            val parsedUri = if (inlineBytes == null) uri.toUri() else null
            val gif = loadAvatarGifBytes(uri)
            if (gif != null) {
                requireNotNull(GifDisplayPicture.parse(gif)) { "Unsupported animated profile picture." }
                return@runCatching gif
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (inlineBytes != null) {
                BitmapFactory.decodeByteArray(inlineBytes, 0, inlineBytes.size, bounds)
            } else {
                contentResolver.openInputStream(requireNotNull(parsedUri))?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= DECODE_TARGET_SIZE &&
                bounds.outHeight / (sampleSize * 2) >= DECODE_TARGET_SIZE
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val source = if (inlineBytes != null) {
                BitmapFactory.decodeByteArray(inlineBytes, 0, inlineBytes.size, options)
            } else {
                contentResolver.openInputStream(requireNotNull(parsedUri))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            } ?: return@runCatching null
            try {
                val side = min(source.width, source.height)
                val square = Bitmap.createBitmap(
                    source,
                    (source.width - side) / 2,
                    (source.height - side) / 2,
                    side,
                    side,
                )
                try {
                    val scaled = square.scale(DISPLAY_PICTURE_SIZE, DISPLAY_PICTURE_SIZE)
                    try {
                        ByteArrayOutputStream().use { output ->
                            check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                            output.toByteArray()
                        }
                    } finally {
                        if (scaled !== square) scaled.recycle()
                    }
                } finally {
                    if (square !== source) square.recycle()
                }
            } finally {
                source.recycle()
            }
        }.getOrNull()
    }

/** Decodes a UI-sized bitmap once and shares it across every avatar using the same URI. */
internal suspend fun Context.loadAvatarBitmap(uri: String?, highResolution: Boolean = false): Bitmap? {
    if (uri.isNullOrBlank()) return null
    val cacheKey = avatarCacheKey(uri, highResolution)
    displayPictureBitmapCache.get(cacheKey)?.let { return it }
    val pending = CompletableDeferred<Bitmap?>()
    pendingAvatarDecodes.putIfAbsent(cacheKey, pending)?.let { return it.await() }
    try {
        val decoded = withContext(Dispatchers.IO) {
            displayPictureBitmapCache.get(cacheKey) ?: runCatching {
                decodeSampledAvatar(uri, highResolution)
            }.getOrNull()?.also { displayPictureBitmapCache.put(cacheKey, it) }
        }
        pending.complete(decoded)
        return decoded
    } catch (error: Throwable) {
        pending.completeExceptionally(error)
        throw error
    } finally {
        pendingAvatarDecodes.remove(cacheKey, pending)
    }
}

internal fun cachedAvatarBitmap(uri: String?, highResolution: Boolean = false): Bitmap? =
    uri?.takeIf(String::isNotBlank)?.let { displayPictureBitmapCache.get(avatarCacheKey(it, highResolution)) }

private fun avatarCacheKey(uri: String, highResolution: Boolean): String =
    if (highResolution) "profile:$uri" else uri

private fun Context.decodeSampledAvatar(uri: String, highResolution: Boolean): Bitmap? {
    val inlineBytes = uri.inlineImageBytes()
    val parsedUri = if (inlineBytes == null) uri.toUri() else null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (inlineBytes != null) {
        BitmapFactory.decodeByteArray(inlineBytes, 0, inlineBytes.size, bounds)
    } else {
        contentResolver.openInputStream(requireNotNull(parsedUri))?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    if (highResolution) {
        // Keep original pixels for profile viewing, with a memory limit for unusually large files.
        while (bounds.outWidth.toLong() / sampleSize * (bounds.outHeight / sampleSize) > PROFILE_PICTURE_MAX_PIXELS) {
            sampleSize *= 2
        }
    } else {
        while (
            min(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= AVATAR_DECODE_TARGET_SIZE
        ) {
            sampleSize *= 2
        }
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return if (inlineBytes != null) {
        BitmapFactory.decodeByteArray(inlineBytes, 0, inlineBytes.size, options)
    } else {
        contentResolver.openInputStream(requireNotNull(parsedUri))?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }
}

internal fun String.inlineImageBytes(): ByteArray? {
    if (!startsWith("data:image/", ignoreCase = true)) return null
    val marker = ";base64,"
    val markerIndex = indexOf(marker, ignoreCase = true)
    require(markerIndex >= 0) { "Unsupported inline display-picture encoding." }
    val encoded = substring(markerIndex + marker.length)
    require(encoded.length <= (MAX_INLINE_IMAGE_BYTES * 4 / 3) + 4) {
        "Inline display picture is too large."
    }
    return requireNotNull(MsnBase64.decode(encoded)) { "Invalid inline display picture." }
        .also { bytes ->
            require(bytes.size <= MAX_INLINE_IMAGE_BYTES) { "Inline display picture is too large." }
        }
}

/** Reads GIF data with a hard limit, including document providers that misreport their MIME type. */
internal fun Context.loadAvatarGifBytes(uri: String?): ByteArray? {
    if (uri.isNullOrBlank()) return null
    uri.inlineImageBytes()?.let { return it.takeIf(GifDisplayPicture::hasSignature) }
    return contentResolver.openInputStream(uri.toUri())?.use { input ->
        val header = ByteArray(6)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count <= 0) return@use null
            offset += count
        }
        if (!GifDisplayPicture.hasSignature(header)) return@use null
        ByteArrayOutputStream().use { output ->
            output.write(header)
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= GifDisplayPicture.MAX_BYTES - output.size()) { "GIF is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
