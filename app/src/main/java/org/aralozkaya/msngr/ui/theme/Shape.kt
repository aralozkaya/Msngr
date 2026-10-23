package org.aralozkaya.msngr.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The stock Material 3 shape scale, shared by every Msngr surface and component. */
internal val MsngrShapes = Shapes()

internal val ProfileCardCornerRadius = 28.dp
internal val ProfileCardPressedCornerRadius = 16.dp
internal val OwnDisplayPictureCornerRadius = 28.dp

/** Semantic shape roles used by custom Msngr components. */
internal val Shapes.contentCard: CornerBasedShape
    get() = medium

internal val Shapes.avatar: CornerBasedShape
    get() = CircleShape

internal val Shapes.ownDisplayPicture: CornerBasedShape
    get() = RoundedCornerShape(OwnDisplayPictureCornerRadius)

internal val Shapes.pill: CornerBasedShape
    get() = CircleShape

/**
 * Consecutive bubbles share tighter corners on the speaker edge, with rounded outer corners
 * opening and closing the group. Standalone bubbles use the same fixed rounding as the
 * composer, staying pill-like when short without turning circular as they grow taller.
 */
internal fun Shapes.chatBubble(
    isOutgoing: Boolean?,
    joinsPrevious: Boolean = false,
    joinsNext: Boolean = false,
): CornerBasedShape = when (isOutgoing) {
    true -> extraLarge.copy(
        topEnd = if (joinsPrevious) CornerSize(4.dp) else extraLarge.topEnd,
        bottomEnd = if (joinsNext) CornerSize(4.dp) else extraLarge.bottomEnd,
    )
    false -> extraLarge.copy(
        topStart = if (joinsPrevious) CornerSize(4.dp) else extraLarge.topStart,
        bottomStart = if (joinsNext) CornerSize(4.dp) else extraLarge.bottomStart,
    )
    null -> large
}
