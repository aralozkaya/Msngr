package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.avatar
import org.aralozkaya.msngr.ui.theme.pill

private val OnlineIndicatorColor = Color(0xFF2E7D32)
private val BusyIndicatorColor = Color(0xFFC62828)
private val AwayIndicatorColor = Color(0xFFEF6C00)
private val OfflineIndicatorColor = Color(0xFF757575)

@Composable
fun PresenceBadge(
    presence: Presence,
    isBlocked: Boolean,
    unreadCount: Int,
) {
    Surface(
        shape = MaterialTheme.shapes.pill,
        color = if (isBlocked) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresenceDot(
                presence = presence,
                isBlocked = isBlocked,
                modifier = Modifier.size(8.dp),
            )
            Text(
                text = if (isBlocked) "Blocked" else presence.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isBlocked) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            if (!isBlocked && unreadCount > 0) {
                Badge {
                    Text(unreadCount.toString())
                }
            }
        }
    }
}

@Composable
fun PresenceLabel(presence: Presence) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(MaterialTheme.shapes.avatar)
                .background(presenceColor(presence)),
        )
        Text(
            text = presence.label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun PresenceDot(
    presence: Presence,
    modifier: Modifier = Modifier,
    isBlocked: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.avatar)
            .background(
                if (isBlocked) {
                    BusyIndicatorColor
                } else {
                    presenceColor(presence)
                },
            ),
    )
}

fun presenceColor(presence: Presence): Color = when (presence) {
    Presence.Online -> OnlineIndicatorColor
    Presence.Busy -> BusyIndicatorColor
    Presence.Away -> AwayIndicatorColor
    Presence.Offline -> OfflineIndicatorColor
}
