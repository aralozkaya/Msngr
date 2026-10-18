@file:Suppress("DEPRECATION") // Movie is the platform GIF decoder on Android 7 and 8.

package org.aralozkaya.msngr

import android.graphics.Movie
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import java.nio.ByteBuffer

internal data class AvatarImage(
    val painter: Painter?,
    val isGif: Boolean = false,
    val isLoading: Boolean = false,
)

/** Each visible avatar owns its decoder/playhead; cached bitmaps remain safe for notifications. */
@Composable
internal fun rememberAvatarImage(
    uri: String?,
    highResolution: Boolean = false,
    imageVersion: Int = 0,
): AvatarImage = key(uri, highResolution, imageVersion) {
    val context = LocalContext.current
    val initial = remember {
        AvatarImage(cachedAvatarBitmap(uri, highResolution)?.let { BitmapPainter(it.asImageBitmap()) },
            isLoading = !uri.isNullOrBlank())
    }
    val image by produceState(initial) {
        value = withContext(Dispatchers.IO) {
            val bytes = runCatching { context.loadAvatarGifBytes(uri) }.getOrNull()
            val info = bytes?.let(GifDisplayPicture::parse)
            val animation = if (info != null && info.frameDelaysMillis.size > 1) {
                runCatching { AnimatedAvatarPainter.decode(requireNotNull(bytes), info) }.getOrNull()
            } else null
            AvatarImage(
                animation ?: context.loadAvatarBitmap(uri, highResolution)?.let { BitmapPainter(it.asImageBitmap()) },
                isGif = info != null,
            )
        }
    }
    val animation = image.painter as? AnimatedAvatarPainter
    DisposableEffect(animation) {
        animation?.attach()
        onDispose { animation?.dispose() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animation, lifecycle) {
        val painter = animation ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            painter.play()
        }
    }
    image
}

internal class AnimatedAvatarPainter private constructor(
    private val drawable: Drawable?,
    private val movie: Movie?,
    val info: GifDisplayPicture,
) : Painter() {
    private var timeMillis by mutableIntStateOf(0)
    private var invalidation by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    var isRunning by mutableStateOf(false)
        private set
    override val intrinsicSize = Size(info.width.toFloat(), info.height.toFloat())

    fun attach() {
        drawable?.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { invalidation++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, this@AnimatedAvatarPainter, `when`)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what, this@AnimatedAvatarPainter)
            }
        }
    }

    fun dispose() {
        if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.stop()
        drawable?.callback = null
        handler.removeCallbacksAndMessages(this)
    }

    suspend fun play() {
        isRunning = true
        try {
            if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
                drawable.start()
                awaitCancellation()
            } else {
                while (true) {
                    var time = 0
                    for (frameDelay in info.frameDelaysMillis) {
                        timeMillis = time
                        delay(frameDelay.toLong())
                        time += frameDelay
                    }
                }
            }
        } finally {
            isRunning = false
            if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.stop()
        }
    }

    override fun DrawScope.onDraw() {
        val time = timeMillis
        @Suppress("UNUSED_VARIABLE") val version = invalidation // Invalidate drawing, not composition.
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val saved = native.save()
            try {
                native.scale(size.width / info.width, size.height / info.height)
                if (drawable != null) {
                    drawable.setBounds(0, 0, info.width, info.height)
                    drawable.draw(native)
                } else {
                    movie?.setTime(time)
                    movie?.draw(native, 0f, 0f)
                }
            } finally {
                native.restoreToCount(saved)
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray, info: GifDisplayPicture): AnimatedAvatarPainter? {
            return if (Build.VERSION.SDK_INT >= 28) {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    as? AnimatedImageDrawable ?: return null
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                AnimatedAvatarPainter(drawable, null, info)
            } else {
                val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
                AnimatedAvatarPainter(null, movie, info)
            }
        }
    }
}
