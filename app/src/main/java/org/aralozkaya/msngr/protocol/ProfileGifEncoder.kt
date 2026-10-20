package org.aralozkaya.msngr.protocol

import java.io.ByteArrayOutputStream

/** Streaming GIF89a writer: full composited frames, local adaptive palettes, and GIF LZW. */
internal class ProfileGifEncoder(width: Int, height: Int, loopCount: Int?) {
    private val output = ByteArrayOutputStream()
    private val width = width
    private val height = height

    init {
        require(width in 1..65535 && height in 1..65535)
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height)
        output.write(byteArrayOf(0x70, 0, 0)) // Local palettes; no global color table.
        if (loopCount != null) {
            output.write(byteArrayOf(0x21, 0xff.toByte(), 11))
            output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            output.write(byteArrayOf(3, 1)); word(loopCount); output.write(0)
        }
    }

    fun addFrame(pixels: IntArray, delayCentiseconds: Int) {
        require(pixels.size == width * height)
        val transparent = pixels.any { it ushr 24 < 128 }
        val firstColor = if (transparent) 1 else 0
        val colors = quantize(pixels, 256 - firstColor)
        val palette = IntArray(256)
        colors.copyInto(palette, firstColor)
        val lookup = HashMap<Int, Int>()
        val indices = ByteArray(pixels.size) { i ->
            val pixel = pixels[i]
            if (pixel ushr 24 < 128) 0 else lookup.getOrPut(pixel and 0xffffff) {
                var best = firstColor
                var distance = Int.MAX_VALUE
                for (index in colors.indices) {
                    val color = colors[index]
                    val r = (pixel shr 16 and 255) - (color shr 16 and 255)
                    val g = (pixel shr 8 and 255) - (color shr 8 and 255)
                    val b = (pixel and 255) - (color and 255)
                    val d = r * r + g * g + b * b
                    if (d < distance) { distance = d; best = index + firstColor }
                }
                best
            }.toByte()
        }
        output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, (8 or if (transparent) 1 else 0).toByte()))
        word(delayCentiseconds); output.write(byteArrayOf(0, 0))
        output.write(0x2c); word(0); word(0); word(width); word(height); output.write(0x87)
        palette.forEach { color ->
            output.write(color shr 16 and 255); output.write(color shr 8 and 255); output.write(color and 255)
        }
        output.write(8)
        val compressed = lzw(indices)
        var offset = 0
        while (offset < compressed.size) {
            val count = minOf(255, compressed.size - offset)
            output.write(count); output.write(compressed, offset, count); offset += count
        }
        output.write(0)
    }

    fun finish(): ByteArray { output.write(0x3b); return output.toByteArray() }
    private fun word(value: Int) { output.write(value and 255); output.write(value shr 8 and 255) }

    private data class Color(val rgb: Int, val count: Int)
    private class Box(val colors: List<Color>) {
        val count = colors.sumOf { it.count }
        val ranges = intArrayOf(16, 8, 0).map { shift ->
            colors.maxOf { it.rgb shr shift and 255 } - colors.minOf { it.rgb shr shift and 255 }
        }
        val axis = ranges.indices.maxBy { ranges[it] }
        val priority = ranges[axis].toLong() * count
        fun split(): List<Box> {
            val shift = intArrayOf(16, 8, 0)[axis]
            val sorted = colors.sortedBy { it.rgb shr shift and 255 }
            var sum = 0
            var at = 0
            while (at < sorted.lastIndex && sum < count / 2) { sum += sorted[at].count; at++ }
            at = at.coerceIn(1, sorted.lastIndex)
            return listOf(Box(sorted.take(at)), Box(sorted.drop(at)))
        }
        fun average(): Int {
            fun component(shift: Int) = (colors.sumOf { (it.rgb shr shift and 255).toLong() * it.count } / count).toInt()
            return (component(16) shl 16) or (component(8) shl 8) or component(0)
        }
    }

    private fun quantize(pixels: IntArray, budget: Int): IntArray {
        val exact = pixels.asSequence().filter { it ushr 24 >= 128 }.map { it and 0xffffff }
            .distinct().take(budget + 1).toList()
        if (exact.size <= budget) return exact.ifEmpty { listOf(0) }.toIntArray()
        val counts = IntArray(32768)
        val red = LongArray(32768); val green = LongArray(32768); val blue = LongArray(32768)
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            val key = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
            counts[key]++; red[key] += r.toLong(); green[key] += g.toLong(); blue[key] += b.toLong()
        }
        val colors = counts.indices.filter { counts[it] > 0 }.map { i ->
            Color(((red[i] / counts[i]).toInt() shl 16) or
                ((green[i] / counts[i]).toInt() shl 8) or (blue[i] / counts[i]).toInt(), counts[i])
        }
        val boxes = mutableListOf(Box(colors))
        while (boxes.size < budget) {
            val box = boxes.filter { it.colors.size > 1 }.maxByOrNull { it.priority } ?: break
            boxes.remove(box); boxes.addAll(box.split())
        }
        return boxes.map { it.average() }.toIntArray()
    }

    private fun lzw(indices: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        var accumulator = 0; var bits = 0; var codeSize = 9; var next = 258
        val dictionary = HashMap<Int, Int>(4096)
        fun write(code: Int) {
            accumulator = accumulator or (code shl bits); bits += codeSize
            while (bits >= 8) { bytes.write(accumulator and 255); accumulator = accumulator ushr 8; bits -= 8 }
        }
        write(256)
        var prefix = indices[0].toInt() and 255
        for (i in 1 until indices.size) {
            val suffix = indices[i].toInt() and 255
            val key = (prefix shl 8) or suffix
            val found = dictionary[key]
            if (found != null) { prefix = found; continue }
            write(prefix)
            if (next < 4096) {
                dictionary[key] = next++
                // The decoder adds its dictionary entry one code after the encoder.
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                write(256); dictionary.clear(); next = 258; codeSize = 9
            }
            prefix = suffix
        }
        write(prefix)
        if (next == (1 shl codeSize) && codeSize < 12) codeSize++
        write(257)
        if (bits > 0) bytes.write(accumulator and 255)
        return bytes.toByteArray()
    }
}
