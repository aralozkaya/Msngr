package org.aralozkaya.msngr.pages.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.SceneText
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.CurrentMediaType

internal fun CurrentMedia.icon(): ImageVector = when (type) {
    CurrentMediaType.Music -> Icons.Default.MusicNote
    CurrentMediaType.Games -> Icons.Default.SportsEsports
    CurrentMediaType.Office -> Icons.Default.Work
    CurrentMediaType.Other -> Icons.Default.MusicNote
}

@StringRes
internal fun CurrentMedia.labelRes(): Int = when (type) {
    CurrentMediaType.Music, CurrentMediaType.Other -> R.string.current_media_listening_label
    CurrentMediaType.Games -> R.string.current_media_playing_game_label
    CurrentMediaType.Office -> R.string.current_media_office_label
}

@StringRes
internal fun CurrentMedia.descriptionRes(): Int = when (type) {
    CurrentMediaType.Music, CurrentMediaType.Other -> R.string.current_media_listening_description
    CurrentMediaType.Games -> R.string.current_media_playing_game_description
    CurrentMediaType.Office -> R.string.current_media_office_description
}

@Composable
internal fun CurrentMediaLine(
    media: CurrentMedia,
    modifier: Modifier = Modifier,
    sceneGlow: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val description = stringResource(media.descriptionRes(), media.displayText)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = media.icon(),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        if (sceneGlow) {
            SceneText(
                text = media.displayText,
                style = style,
                color = textColor,
            )
        } else {
            Text(
                text = media.displayText,
                style = style,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
