package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class MessengerScene(
    val id: String,
    val name: String,
    val group: String,
    val file: String,
    val seedArgb: Int,
    val animated: Boolean,
    val wideFile: String? = null,
) {
    val assetPath: String get() = "scenes/$file"
    val previewPath: String get() = "scenes/${id}_preview.webp"
    val collectionName: String get() = when (group) {
        "Windows Live Messenger 2009" -> "Messenger 2009"
        "Windows Live Messenger 2011 and 2012" -> "Messenger 2011–2012"
        else -> group
    }
}

internal fun messengerScene(id: String): MessengerScene =
    MessengerScenes.firstOrNull { it.id == id } ?: MessengerScenes.first { it.id == "daisyhill" }

internal val LocalMessengerScene = staticCompositionLocalOf<MessengerScene?> { null }

internal data class ScenePresentation(
    val showInConversations: Boolean = true,
    val darkTint: Boolean = false,
    val isDark: Boolean = false,
)

internal val LocalScenePresentation = staticCompositionLocalOf { ScenePresentation() }

@Composable
internal fun sceneContentColor(): Color =
    if (LocalScenePresentation.current.darkTint) Color(0xFFF5F7FA) else SceneInk

/** Used only inside image-backed headers. Menus and buttons keep their own Material surfaces. */
internal val SceneInk = Color(0xFF172331)

@Composable
internal fun SceneText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(text, modifier.sceneTextGlow(layout, LocalMessengerScene.current != null, LocalScenePresentation.current.darkTint),
        style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it })
}

@Composable
internal fun SceneBackdrop(
    scene: MessengerScene?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val darkTint = LocalScenePresentation.current.darkTint
    Box(modifier.testTag("scene_backdrop")) {
        if (scene != null) {
            SceneArtwork(scene, Modifier.matchParentSize().testTag("scene_header_artwork"))
            if (darkTint) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.58f)).testTag("scene_dark_tint"))
            } else {
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.36f), Color.White.copy(alpha = 0.12f)))))
            }
        }
        CompositionLocalProvider(
            LocalMessengerScene provides scene,
            LocalContentColor provides if (scene != null) sceneContentColor() else LocalContentColor.current,
        ) {
            content()
        }
    }
}

internal data class SceneFrames(val bitmaps: List<Bitmap>, val delays: IntArray)

/** Content sniffing is intentional: APNG artwork often has a .png filename. */
internal fun decodeSceneFrames(bytes: ByteArray): SceneFrames {
    val animation = when {
        bytes.size >= 6 && bytes[0] == 71.toByte() && bytes[1] == 73.toByte() && bytes[2] == 70.toByte() ->
            GifCodec.decode(bytes, padToGrid = false)
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 80.toByte() ->
            ApngCodec.parse(bytes)?.let { apng ->
                ApngCodec.composite(apng, padToGrid = false) { frame ->
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size))
                    IntArray(frame.width * frame.height).also {
                        bitmap.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height)
                        bitmap.recycle()
                    }
                }
            }
        else -> null
    }
    return if (animation != null) {
        SceneFrames(animation.frames.map { Bitmap.createBitmap(it, animation.width, animation.height,
            Bitmap.Config.ARGB_8888) }, animation.delaysMillis)
    } else {
        SceneFrames(listOf(requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))), intArrayOf(0))
    }
}

@Composable
internal fun SceneArtwork(
    scene: MessengerScene,
    modifier: Modifier = Modifier,
    previewOnly: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val file = if (maxWidth >= 553.dp && scene.wideFile != null) "scenes/${scene.wideFile}" else scene.assetPath
        val context = LocalContext.current
        val frames by produceState<SceneFrames?>(null, context, file, previewOnly) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(if (previewOnly) scene.previewPath else file).use {
                        decodeSceneFrames(it.readBytes())
                    }
                }.getOrElse { context.scenePreview(scene) }
            }
        }
        var index by remember(scene.id, previewOnly) { mutableIntStateOf(0) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(frames, lifecycle) {
            val loaded = frames ?: return@LaunchedEffect
            if (loaded.bitmaps.size <= 1) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(loaded.delays[index].coerceAtLeast(20).toLong())
                    index = (index + 1) % loaded.bitmaps.size
                }
            }
        }
        frames?.let { loaded ->
            Image(loaded.bitmaps[index.coerceAtMost(loaded.bitmaps.lastIndex)].asImageBitmap(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alignment = Alignment.Center)
        }
    }
}

private fun Context.scenePreview(scene: MessengerScene): SceneFrames? = runCatching {
    assets.open(scene.previewPath).use { decodeSceneFrames(it.readBytes()) }
}.getOrNull()

/** Draws the same shaped glyphs behind the original styled text, preserving spans and ellipsis. */
internal fun Modifier.sceneTextGlow(layout: TextLayoutResult?, enabled: Boolean, darkBackground: Boolean = false): Modifier =
    if (!enabled) this else drawWithContent {
        layout?.let {
            drawText(it, color = Color.Black.copy(alpha = if (darkBackground) 0.45f else 0.20f),
                topLeft = Offset(0f, 1.dp.toPx()),
                shadow = Shadow(Color.Black.copy(alpha = if (darkBackground) 0.45f else 0.18f), Offset(0f, 1.dp.toPx()), 2.dp.toPx()))
            drawText(it, color = Color.White.copy(alpha = if (darkBackground) 0.20f else 1f),
                drawStyle = Stroke((if (darkBackground) 0.5.dp else 1.5.dp).toPx()),
                shadow = Shadow(Color.White.copy(alpha = if (darkBackground) 0.18f else 0.95f),
                    Offset.Zero, (if (darkBackground) 1.dp else 3.dp).toPx()))
        }
        drawContent()
    }
