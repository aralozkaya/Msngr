@file:Suppress("DEPRECATION")

package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.pages.DisplayPictureResizeDialog
import org.aralozkaya.msngr.protocol.DisplayPictureFormat
import org.aralozkaya.msngr.protocol.DisplayPictureLimits
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import org.aralozkaya.msngr.protocol.ProfileGifEncoder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class ProfilePictureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity

    @Test fun staticOriginalsKeepPixelsBytesAndMimeOnSelectionRestoreAndDownload() = runBlocking {
        for ((compression, format) in listOf(Bitmap.CompressFormat.PNG to DisplayPictureFormat.PNG,
            Bitmap.CompressFormat.JPEG to DisplayPictureFormat.JPEG)) {
            val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3456ab.toInt()) }
            val bytes = ByteArrayOutputStream().use { bitmap.compress(compression, 100, it); it.toByteArray() }
            bitmap.recycle()
            val source = File(context.cacheDir, "original.${format.extension}").apply { writeBytes(bytes) }
            val output = File(context.cacheDir, "export.${format.extension}")
            try {
                val selected = context.inspectDisplayPicture(Uri.fromFile(source).toString())
                assertFalse(selected.requiresResize)
                assertEquals(512, selected.width)
                assertEquals(format, selected.format)
                assertArrayEquals(bytes, context.loadDisplayPictureBytes(selected.dataUri))
                assertTrue(context.saveContactPicture(selected.dataUri, Uri.fromFile(output)))
                assertArrayEquals(bytes, output.readBytes())
                val xml = org.aralozkaya.msngr.data.MsnpStorageService.buildUpdateDocumentRequest("ticket", "photo", bytes)
                assertTrue(xml.contains("<MimeType>${format.mimeType}</MimeType>"))
            } finally { source.delete(); output.delete() }
        }
    }

    @Test fun oversizedGifRequiresConsentAndResizingKeepsFramesTimingAndTransparency() = runBlocking {
        val random = Random(891)
        val encoder = ProfileGifEncoder(320, 240, 2)
        repeat(6) { frame ->
            encoder.addFrame(IntArray(320 * 240) { i ->
                if (i % 320 < 20) 0 else 0xff000000.toInt() or (random.nextInt(255) * 0x010101)
            }, 5 + frame)
        }
        val bytes = encoder.finish()
        assertTrue(bytes.size > DisplayPictureLimits.MAX_BYTES)
        val source = File(context.cacheDir, "oversized.gif").apply { writeBytes(bytes) }
        try {
            val selected = context.inspectDisplayPicture(Uri.fromFile(source).toString())
            assertTrue(selected.requiresResize)
            assertNull(context.loadDisplayPictureBytes(Uri.fromFile(source).toString()))
            val resized = resizeDisplayPicture(selected)
            assertFalse(resized.requiresResize)
            assertTrue(resized.width > 96)
            assertEquals(6, resized.animation?.frameDelaysMillis?.size)
            assertEquals(selected.animation?.frameDelaysCentiseconds, resized.animation?.frameDelaysCentiseconds)
            assertEquals(2, resized.animation?.loopCount)
            assertTrue(kotlin.math.abs(resized.width.toDouble() / resized.height - 4.0 / 3) < .03)
            val movie = requireNotNull(Movie.decodeByteArray(resized.bytes, 0, resized.bytes.size))
            val poster = Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888)
            movie.setTime(0); movie.draw(Canvas(poster), 0f, 0f)
            assertEquals(0, poster.getPixel(0, 0) ushr 24)
            poster.recycle()
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(resized.bytes, context.loadDisplayPictureBytes(resized.dataUri))
        } finally { source.delete() }
    }

    @Test fun resizeDialogOffersExplicitCancelAndContinue() {
        var resize = 0; var cancel = 0
        val selection = DisplayPictureSelection(ByteArray(220_000), DisplayPictureFormat.PNG, 512, 512)
        compose.setContent { DisplayPictureResizeDialog(selection, { resize++ }, { cancel++ }) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancel); assertEquals(0, resize) }
        compose.onNodeWithText("Resize and continue").performClick()
        compose.runOnIdle { assertEquals(1, resize) }
    }

    @Test fun oversizedStaticPictureResizesWithoutCroppingAndKeepsItsSource() = runBlocking {
        val random = Random(278)
        val pixels = IntArray(600 * 400) { 0xff000000.toInt() or random.nextInt(0x1000000) }
        val bitmap = Bitmap.createBitmap(pixels, 600, 400, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val file = File(context.cacheDir, "large-static.png").apply { writeBytes(bytes) }
        try {
            val selected = context.inspectDisplayPicture(Uri.fromFile(file).toString())
            assertTrue(selected.requiresResize)
            val resized = resizeDisplayPicture(selected)
            assertFalse(resized.requiresResize)
            assertEquals(DisplayPictureFormat.PNG, resized.format)
            assertTrue(resized.width > 96)
            assertTrue(kotlin.math.abs(resized.width.toDouble() / resized.height - 1.5) < .02)
            assertArrayEquals(bytes, file.readBytes())
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(resized.bytes, 0, resized.bytes.size))
            assertEquals(resized.width, decoded.width); decoded.recycle()
        } finally { file.delete() }
    }

    /** Optional private fixture supplied by the developer; never bundled into the APK or repository. */
    @Test fun attachedKnockGifPreservesAll39Frames() = runBlocking {
        val input = File(context.filesDir, "profile-picture-validation/knock-knock-knock.gif")
        assumeTrue(input.exists())
        val selected = context.inspectDisplayPicture(Uri.fromFile(input).toString())
        assertEquals(416, selected.width); assertEquals(326, selected.height)
        assertEquals(39, selected.animation?.frameDelaysMillis?.size)
        assertTrue(selected.requiresResize)
        val resized = resizeDisplayPicture(selected)
        assertFalse(resized.requiresResize)
        assertEquals(39, resized.animation?.frameDelaysMillis?.size)
        assertEquals(selected.animation?.frameDelaysCentiseconds, resized.animation?.frameDelaysCentiseconds)
        val output = File(context.getExternalFilesDir(null), "knock-resized.gif")
        output.writeBytes(ByteArray(DisplayPictureLimits.MAX_BYTES + 1))
        assertTrue(context.saveContactPicture(resized.dataUri, Uri.fromFile(output)))
        assertArrayEquals(resized.bytes, output.readBytes())
        val movie = requireNotNull(Movie.decodeByteArray(resized.bytes, 0, resized.bytes.size))
        val bitmap = Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888)
        val colors = mutableSetOf<Int>()
        var elapsed = 0
        resized.animation!!.frameDelaysMillis.forEach { delay ->
            bitmap.eraseColor(0); movie.setTime(elapsed); movie.draw(Canvas(bitmap), 0f, 0f)
            val pixels = IntArray(resized.width * resized.height)
            bitmap.getPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
            colors += pixels.contentHashCode(); elapsed += delay
        }
        bitmap.recycle()
        assertTrue("Distinct frames: ${colors.size}", colors.size > 20)
    }
}
