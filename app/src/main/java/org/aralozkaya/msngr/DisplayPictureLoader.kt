package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.DisplayPictureLimits
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import org.aralozkaya.msngr.protocol.MsnBase64
import java.io.SequenceInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

private const val AVATAR_DECODE_TARGET_SIZE = 384
private const val PROFILE_PICTURE_MAX_PIXELS = 4_194_304L
private const val MAX_INLINE_IMAGE_BYTES = DisplayPictureLimits.MAX_SOURCE_BYTES
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

/** Bundled originals and Android picker URIs share the same decoding and upload path. */
internal fun Context.openDisplayPictureInputStream(uri: String): InputStream? =
    if (uri.startsWith(DEFAULT_DISPLAY_PICTURE_URI_PREFIX)) {
        val file = uri.removePrefix(DEFAULT_DISPLAY_PICTURE_URI_PREFIX)
        require('/' !in file && '\\' !in file && file != "." && file != "..")
        assets.open("display_pictures/$file")
    } else contentResolver.openInputStream(uri.toUri())

/** Restoration must never silently resize a previously selected picture. */
internal suspend fun Context.loadDisplayPictureBytes(uri: String?): ByteArray? =
    withContext(Dispatchers.IO) {
        if (uri.isNullOrBlank()) return@withContext null
        runCatching {
            inspectDisplayPicture(uri).takeUnless { it.requiresResize }?.bytes
        }.getOrNull()
    }

/** Decodes a UI-sized bitmap once and shares it across every avatar using the same URI. */
internal suspend fun Context.loadAvatarBitmap(uri: String?, highResolution: Boolean = false): Bitmap? {
    if (uri.isNullOrBlank()) return null
    val cacheKey = avatarCacheKey(uri, highResolution)
    displayPictureBitmapCache.get(cacheKey)?.let { return it }
    return withContext(Dispatchers.IO) {
        val pending = CompletableDeferred<Bitmap?>()
        pendingAvatarDecodes.putIfAbsent(cacheKey, pending)?.let { return@withContext it.await() }
        try {
            val decoded = displayPictureBitmapCache.get(cacheKey) ?: runCatching {
                decodeSampledAvatar(uri, highResolution)
            }.getOrNull()?.also { displayPictureBitmapCache.put(cacheKey, it) }
            pending.complete(decoded)
            decoded
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            pendingAvatarDecodes.remove(cacheKey, pending)
        }
    }
}

internal fun cachedAvatarBitmap(uri: String?, highResolution: Boolean = false): Bitmap? =
    uri?.takeIf(String::isNotBlank)?.let { displayPictureBitmapCache.get(avatarCacheKey(it, highResolution)) }

private fun avatarCacheKey(uri: String, highResolution: Boolean): String =
    if (highResolution) "profile:$uri" else uri

private fun Context.decodeSampledAvatar(uri: String, highResolution: Boolean): Bitmap? {
    val inlineBytes = uri.inlineImageBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (inlineBytes != null) {
        BitmapFactory.decodeByteArray(inlineBytes, 0, inlineBytes.size, bounds)
    } else {
        openDisplayPictureInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sampleSize = avatarSampleSize(bounds.outWidth, bounds.outHeight, highResolution)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return if (inlineBytes != null) {
        BitmapFactory.decodeByteArray(inlineBytes, 0, inlineBytes.size, options)
    } else {
        openDisplayPictureInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }
}

internal fun avatarSampleSize(width: Int, height: Int, highResolution: Boolean): Int {
    require(width > 0 && height > 0)
    var sample = 1
    if (!highResolution) {
        while (minOf(width, height) / sample / 2 >= AVATAR_DECODE_TARGET_SIZE) sample *= 2
    }
    // Panoramas can have a tiny short edge and still need a large decode.
    while (((width - 1L) / sample + 1) * ((height - 1L) / sample + 1) > PROFILE_PICTURE_MAX_PIXELS) {
        sample *= 2
    }
    return sample
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
    return openDisplayPictureInputStream(uri)?.use { input ->
        val header = ByteArray(6)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count <= 0) return@use null
            offset += count
        }
        if (!GifDisplayPicture.hasSignature(header)) return@use null
        SequenceInputStream(header.inputStream(), input).readBytesBounded(GifDisplayPicture.MAX_BYTES) {
            getString(R.string.display_picture_gif_too_large)
        }
    }
}
