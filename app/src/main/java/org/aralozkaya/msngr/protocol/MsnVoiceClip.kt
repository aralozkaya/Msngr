package org.aralozkaya.msngr.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A validated, self-contained MSN Siren7 WAV. No remote URLs or external file paths. */
data class VoiceClip(val wav: List<Byte>, val durationMillis: Int) {
    fun bytes(): ByteArray = wav.toByteArray()
}

internal object SirenCodec {
    init { System.loadLibrary("msngr_siren") }
    external fun encode(pcm: ByteArray): ByteArray?
    external fun decode(frames: ByteArray): ByteArray?
}

object MsnVoiceClip {
    const val OBJECT_TYPE = 11
    const val SAMPLE_RATE = 16_000
    const val MAX_DURATION_MILLIS = 15_000
    const val MAX_WAV_BYTES = 64 * 1024
    private const val FRAME_SAMPLES = 320
    private const val FRAME_BYTES = 40

    data class WaveData(val frames: ByteArray, val samples: Int)

    fun encode(pcm: ByteArray): VoiceClip {
        require(pcm.size in 2..480_000 && pcm.size % 2 == 0) { "Record up to 15 seconds of audio." }
        val samples = pcm.size / 2
        val padded = pcm.copyOf(((pcm.size + 639) / 640) * 640)
        val frames = checkNotNull(SirenCodec.encode(padded)) { "Voice clip could not be encoded." }
        val buffer = ByteBuffer.allocate(60 + frames.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray())
        buffer.putInt(20).putShort(0x028e).putShort(1).putInt(SAMPLE_RATE).putInt(2_000)
            .putShort(FRAME_BYTES.toShort()).putShort(0).putShort(2).putShort(FRAME_SAMPLES.toShort())
        buffer.put("fact".toByteArray()).putInt(4).putInt(samples)
        buffer.put("data".toByteArray()).putInt(frames.size).put(frames)
        return VoiceClip(buffer.array().toList(), (samples * 1_000 / SAMPLE_RATE).coerceAtLeast(1))
    }

    fun parse(bytes: ByteArray): VoiceClip? = inspect(bytes)?.let {
        VoiceClip(bytes.toList(), (it.samples * 1_000 / SAMPLE_RATE).coerceAtLeast(1))
    }

    fun decode(clip: VoiceClip): ByteArray {
        val data = requireNotNull(inspect(clip.bytes())) { "Invalid voice clip." }
        val decoded = checkNotNull(SirenCodec.decode(data.frames)) { "Voice clip could not be played." }
        return decoded.copyOf(data.samples * 2)
    }

    /** RIFF chunks may be reordered, extended, or padded; never trust declared allocation sizes. */
    fun inspect(bytes: ByteArray): WaveData? = runCatching {
        if (bytes.size !in 60..MAX_WAV_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || b.getInt(4) != bytes.size - 8) return null
        var offset = 12
        var format = false
        var samples: Int? = null
        var frames: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val size = b.getInt(offset + 4)
            if (size < 0 || size > bytes.size - offset - 8) return null
            val start = offset + 8
            when (tag(offset)) {
                "fmt " -> {
                    if (format || size < 20) return null
                    if ((b.getShort(start).toInt() and 0xffff) != 0x028e ||
                        b.getShort(start + 2).toInt() != 1 || b.getInt(start + 4) != SAMPLE_RATE ||
                        b.getInt(start + 8) != 2_000 || b.getShort(start + 12).toInt() != FRAME_BYTES ||
                        b.getShort(start + 14).toInt() != 0 || b.getShort(start + 16).toInt() != 2 ||
                        b.getShort(start + 18).toInt() != FRAME_SAMPLES) return null
                    format = true
                }
                "fact" -> {
                    if (samples != null || size < 4) return null
                    samples = b.getInt(start)
                }
                "data" -> {
                    if (frames != null || size !in FRAME_BYTES..30_000 || size % FRAME_BYTES != 0) return null
                    frames = bytes.copyOfRange(start, start + size)
                }
            }
            offset = start + size + (size and 1)
        }
        if (offset != bytes.size || !format) return null
        val data = frames ?: return null
        val count = samples ?: (data.size / FRAME_BYTES * FRAME_SAMPLES)
        if (count !in 1..240_000 || count > data.size / FRAME_BYTES * FRAME_SAMPLES ||
            count <= (data.size / FRAME_BYTES - 1) * FRAME_SAMPLES) return null
        WaveData(data, count)
    }.getOrNull()
}
