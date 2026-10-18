package org.aralozkaya.msngr.pages.components

import androidx.compose.ui.graphics.Color
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceColorTest {
    @Test
    fun presenceIndicatorsUseFixedSemanticColors() {
        assertEquals(Color(0xFF2E7D32), presenceColor(Presence.Online))
        assertEquals(Color(0xFFC62828), presenceColor(Presence.Busy))
        assertEquals(Color(0xFFEF6C00), presenceColor(Presence.Away))
        assertEquals(Color(0xFF757575), presenceColor(Presence.Offline))
    }
}
