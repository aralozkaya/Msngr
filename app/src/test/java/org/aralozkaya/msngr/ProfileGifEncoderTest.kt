package org.aralozkaya.msngr

import org.aralozkaya.msngr.protocol.GifDisplayPicture
import org.aralozkaya.msngr.protocol.ProfileGifEncoder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class ProfileGifEncoderTest {
    @Test fun independentlyDecodesLzwDictionaryGrowthAndResetsWithoutLosingPixels() {
        val random = Random(471)
        val pixels = IntArray(192 * 128) { 0xff000000.toInt() or (random.nextInt(256) * 0x010101) }
        val encoder = ProfileGifEncoder(192, 128, 3)
        encoder.addFrame(pixels, 5)
        encoder.addFrame(pixels.reversedArray(), 17)
        val bytes = encoder.finish()
        val info = requireNotNull(GifDisplayPicture.parse(bytes))
        assertEquals(listOf(5, 17), info.frameDelaysCentiseconds)
        assertEquals(3, info.loopCount)
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            reader.input = input
            assertEquals(2, reader.getNumImages(true))
            for (frame in 0..1) {
                val image = reader.read(frame)
                val actual = image.getRGB(0, 0, 192, 128, null, 0, 192)
                assertArrayEquals(if (frame == 0) pixels else pixels.reversedArray(), actual)
            }
            reader.dispose()
        }
    }

    @Test fun transparencyAndShortDelaysSurviveEncoding() {
        val encoder = ProfileGifEncoder(2, 2, null)
        encoder.addFrame(intArrayOf(0, -65536, -16711936, -16776961), 0)
        val bytes = encoder.finish()
        assertNull(requireNotNull(GifDisplayPicture.parse(bytes)).loopCount)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(0, image.getRGB(0, 0) ushr 24)
        assertEquals(-65536, image.getRGB(1, 0))
        assertEquals(-16711936, image.getRGB(0, 1))
    }

    @Test fun adaptivePaletteHandlesPhotographicFrames() {
        val pixels = IntArray(128 * 128) { i -> 0xff000000.toInt() or ((i * 14797) and 0xffffff) }
        val encoder = ProfileGifEncoder(128, 128, 0)
        encoder.addFrame(pixels, 10)
        val image = ImageIO.read(ByteArrayInputStream(encoder.finish()))
        assertEquals(128, image.width)
        val error = pixels.indices.sumOf { i ->
            val actual = image.getRGB(i % 128, i / 128)
            listOf(16, 8, 0).sumOf { s -> kotlin.math.abs((pixels[i] shr s and 255) - (actual shr s and 255)) }
        }.toDouble() / (pixels.size * 3)
        assertTrue("Mean channel error $error", error < 16)
    }
}
