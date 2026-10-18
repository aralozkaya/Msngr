package org.aralozkaya.msngr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeGestureDetectorTest {
    @Test
    fun revealsOnlyForAForcefulShakeAndAppliesCooldown() {
        val detector = ShakeGestureDetector()

        assertFalse(detector.onAcceleration(0f, 9.81f, 0f, timestampNanos = 1L))
        assertTrue(detector.onAcceleration(30f, 0f, 0f, timestampNanos = 2L))
        assertFalse(
            detector.onAcceleration(
                30f,
                0f,
                0f,
                timestampNanos = 500_000_000L,
            ),
        )
        assertTrue(
            detector.onAcceleration(
                30f,
                0f,
                0f,
                timestampNanos = 1_000_000_002L,
            ),
        )
    }
}
