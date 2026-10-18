package org.aralozkaya.msngr.ui.theme

import androidx.compose.foundation.shape.CircleShape
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeRolesTest {
    private val shapes = MsngrShapes

    @Test
    fun contentCardsUseTheMaterialMediumShape() {
        assertEquals(shapes.medium, shapes.contentCard)
    }

    @Test
    fun avatarsAndPillsAreFullyRounded() {
        assertEquals(CircleShape, shapes.avatar)
        assertEquals(CircleShape, shapes.pill)
    }

    @Test
    fun standaloneChatBubblesUseTheSameFixedRoundingAsTheComposer() {
        val outgoing = shapes.chatBubble(isOutgoing = true)
        val incoming = shapes.chatBubble(isOutgoing = false)

        assertEquals(shapes.extraLarge, outgoing)
        assertEquals(shapes.extraLarge, incoming)
    }

    @Test
    fun systemMessagesUseAnEvenMaterialShape() {
        assertEquals(shapes.large, shapes.chatBubble(isOutgoing = null))
    }
}
