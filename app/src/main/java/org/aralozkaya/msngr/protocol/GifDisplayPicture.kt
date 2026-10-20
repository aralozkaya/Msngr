package org.aralozkaya.msngr.protocol

/** GIF structure and timing, without decompressing pixels or trusting dimensions from the network. */
internal data class GifDisplayPicture(
    val width: Int,
    val height: Int,
    val frameDelaysMillis: List<Int>,
    val frameDelaysCentiseconds: List<Int> = frameDelaysMillis.map { it / 10 },
    val loopCount: Int? = null,
) {
    val durationMillis: Int get() = frameDelaysMillis.sum()

    companion object {
        const val MAX_BYTES = DisplayPictureLimits.MAX_BYTES
        private const val MAX_FRAMES = 500

        fun hasSignature(bytes: ByteArray): Boolean = bytes.size >= 6 &&
            (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a" ||
                String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a")

        fun parse(
            bytes: ByteArray,
            maxBytes: Int = MAX_BYTES,
            maxPixels: Long = DisplayPictureLimits.MAX_PIXELS,
        ): GifDisplayPicture? = runCatching {
            require(hasSignature(bytes) && bytes.size in 14..maxBytes)
            var position = 6
            fun byte(): Int = bytes[position++].toInt() and 0xff
            fun word(): Int = byte() or (byte() shl 8)
            fun skip(count: Int) {
                require(count >= 0 && count <= bytes.size - position)
                position += count
            }
            fun blocks() {
                var count = byte()
                while (count != 0) {
                    skip(count)
                    count = byte()
                }
            }
            val width = word()
            val height = word()
            require(width > 0 && height > 0 && width.toLong() * height <= maxPixels)
            val packed = byte()
            skip(2)
            if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
            val delays = mutableListOf<Int>()
            val rawDelays = mutableListOf<Int>()
            var rawDelay = 0
            var loopCount: Int? = null
            var delay = 100
            while (true) {
                when (byte()) {
                    0x21 -> when (byte()) {
                        0xf9 -> {
                            require(byte() == 4)
                            skip(1)
                            // Match Android's treatment of absent/very short GIF frame delays.
                            rawDelay = word()
                            delay = rawDelay.let { if (it < 2) 100 else it * 10 }
                            skip(1)
                            require(byte() == 0)
                        }
                        0xff -> {
                            val length = byte()
                            require(length <= bytes.size - position)
                            val app = String(bytes, position, length, Charsets.US_ASCII)
                            skip(length)
                            var count = byte()
                            while (count != 0) {
                                require(count <= bytes.size - position)
                                if (app in listOf("NETSCAPE2.0", "ANIMEXTS1.0") && count == 3 && bytes[position] == 1.toByte()) {
                                    skip(1)
                                    loopCount = word()
                                } else skip(count)
                                count = byte()
                            }
                        }
                        else -> blocks()
                    }
                    0x2c -> {
                        val left = word()
                        val top = word()
                        val frameWidth = word()
                        val frameHeight = word()
                        require(frameWidth > 0 && frameHeight > 0)
                        require(left + frameWidth <= width && top + frameHeight <= height)
                        val flags = byte()
                        if (flags and 0x80 != 0) skip(3 * (1 shl ((flags and 7) + 1)))
                        require(byte() in 2..8)
                        blocks()
                        require(delays.size < MAX_FRAMES)
                        delays += delay
                        rawDelays += rawDelay
                        delay = 100
                        rawDelay = 0
                    }
                    0x3b -> {
                        require(delays.isNotEmpty())
                        return GifDisplayPicture(width, height, delays, rawDelays, loopCount)
                    }
                    else -> return null
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }.getOrNull()
    }
}
