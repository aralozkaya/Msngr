package org.aralozkaya.msngr.protocol

/** GIF structure and timing, without decompressing pixels or trusting dimensions from the network. */
internal data class GifDisplayPicture(
    val width: Int,
    val height: Int,
    val frameDelaysMillis: List<Int>,
) {
    val durationMillis: Int get() = frameDelaysMillis.sum()

    companion object {
        const val MAX_BYTES = 200 * 1024
        private const val MAX_PIXELS = 1_048_576L
        private const val MAX_FRAMES = 500

        fun hasSignature(bytes: ByteArray): Boolean = bytes.size >= 6 &&
            (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a" ||
                String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a")

        fun parse(bytes: ByteArray): GifDisplayPicture? = runCatching {
            require(hasSignature(bytes) && bytes.size in 14..MAX_BYTES)
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
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
            val packed = byte()
            skip(2)
            if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
            val delays = mutableListOf<Int>()
            var delay = 100
            while (true) {
                when (byte()) {
                    0x21 -> when (byte()) {
                        0xf9 -> {
                            require(byte() == 4)
                            skip(1)
                            // Match Android's treatment of absent/very short GIF frame delays.
                            delay = word().let { if (it < 2) 100 else it * 10 }
                            skip(1)
                            require(byte() == 0)
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
                        delay = 100
                    }
                    0x3b -> {
                        require(delays.isNotEmpty())
                        return GifDisplayPicture(width, height, delays)
                    }
                    else -> return null
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }.getOrNull()
    }
}
