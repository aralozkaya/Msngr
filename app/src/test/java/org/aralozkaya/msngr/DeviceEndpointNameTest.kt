package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEndpointNameTest {
    @Test fun prefersConfiguredDeviceName() {
        assertEquals("Aral's téléphone & tablet", resolveDeviceEndpointName(
            { "  Aral's téléphone & tablet  " }, "Pixel 9",
        ))
    }

    @Test fun fallsBackToModelWhenNameIsMissingOrUnreadable() {
        for (name in listOf(null, "", "   ")) {
            assertEquals("Pixel 9", resolveDeviceEndpointName({ name }, " Pixel 9 "))
        }
        assertEquals("Pixel 9", resolveDeviceEndpointName(
            { throw SecurityException("Setting unavailable") }, "Pixel 9",
        ))
    }

    @Test fun fallsBackWhenModelIsAlsoUnavailable() {
        for (model in listOf(null, "", "   ", "unknown", "UNKNOWN")) {
            assertEquals("Android", resolveDeviceEndpointName({ null }, model))
        }
    }
}
