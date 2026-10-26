package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceClipLevelTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test fun silenceAndEmptyInputAreFlat() {
        assertEquals(0f, voiceClipLevel(ByteArray(640)), 0f)
        assertEquals(0f, voiceClipLevel(ByteArray(0)), 0f)
    }

    @Test fun respondsToVolumeWithEqualLevelsForBothPolarities() {
        assertEquals(0.25f, voiceClipLevel(pcm(2_048, -2_048)), 0.001f)
        assertEquals(0.5f, voiceClipLevel(pcm(4_096, -4_096)), 0.001f)
        assertEquals(1f, voiceClipLevel(pcm(32_767, -32_768)), 0f)
        assertTrue(voiceClipLevel(pcm(500)) < voiceClipLevel(pcm(1_000)))
    }

    @Test fun usesOnlyCapturedWholeSamplesAndIgnoresStaleBufferBytes() {
        assertEquals(0f, voiceClipLevel(pcm(0, 32_767), 2), 0f)
        assertEquals(0f, voiceClipLevel(pcm(0, 32_767), 3), 0f)
        assertEquals(0f, voiceClipLevel(pcm(32_767), -1), 0f)
        assertEquals(1f, voiceClipLevel(pcm(32_767), 999), 0f)
    }
}
