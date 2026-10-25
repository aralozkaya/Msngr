@file:Suppress("DEPRECATION")

package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.protocol.MsnBase64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AnimatedAvatarInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun gif() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("animated-profile/two-frame.gif").use { it.readBytes() }
    private fun uri(bytes: ByteArray) = "data:image/gif;base64,${MsnBase64.encode(bytes)}"

    @Test fun nativeDecoderRendersDifferentFrames() {
        val bytes = gif()
        val movie = requireNotNull(Movie.decodeByteArray(bytes, 0, bytes.size))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        movie.setTime(0)
        movie.draw(canvas, 0f, 0f)
        val first = bitmap.getPixel(0, 0)
        movie.setTime(150)
        movie.draw(canvas, 0f, 0f)
        assertNotEquals(first, bitmap.getPixel(0, 0))
        bitmap.recycle()
    }

    @Test fun selectingRestoringAndExportingGifPreservesEveryByte() = runBlocking {
        val context = compose.activity
        val bytes = gif()
        val source = File(context.cacheDir, "selected.gif").apply { writeBytes(bytes) }
        val destination = File(context.cacheDir, "exported.gif")
        try {
            assertArrayEquals(bytes, context.loadDisplayPictureBytes(Uri.fromFile(source).toString()))
            assertArrayEquals(bytes, context.loadDisplayPictureBytes(uri(bytes)))
            assertTrue(context.saveContactPicture(uri(bytes), Uri.fromFile(destination)))
            assertArrayEquals(bytes, destination.readBytes())
            assertNotNull(context.loadAvatarBitmap(uri(bytes))) // Notification/shortcut poster.
            assertNull(context.loadDisplayPictureBytes(uri(bytes.copyOf(bytes.size - 1))))
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test fun avatarAnimatesAndStopsWhenItsLifecycleStops() {
        var painter: AnimatedAvatarPainter? = null
        val uri = uri(gif())
        compose.setContent {
            val loaded = rememberAvatarImage(uri).painter
            painter = loaded as? AnimatedAvatarPainter
            if (loaded != null) Image(loaded, null, Modifier.size(40.dp).testTag("animated-avatar"))
        }
        compose.waitUntil(5_000) { painter != null }
        val first = compose.onNodeWithTag("animated-avatar").captureToImage().toPixelMap()[10, 10]
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("animated-avatar").captureToImage().toPixelMap()[10, 10] != first
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(3_000) { painter?.isRunning == false }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(3_000) { painter?.isRunning == true }
    }
}
