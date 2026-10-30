@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun NotificationCustomizationRow(
    name: String,
    summary: String,
    contactId: String,
    onClick: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SwipeRevealAction(
        itemKey = contactId,
        label = "Reset notifications for $name",
        icon = Icons.Outlined.DeleteOutline,
        onAction = onReset,
        onClick = onClick,
        modifier = modifier.testTag("swipe_notification_custom_$contactId"),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error,
        ),
        actionModifier = Modifier.testTag("reset_notification_custom_$contactId"),
        iconModifier = Modifier.testTag("reset_notification_custom_icon_$contactId"),
    ) { rowModifier, onRowClick ->
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(0, 1),
            onClick = onRowClick,
            modifier = rowModifier.fillMaxWidth().testTag("notification_custom_$contactId"),
            supportingContent = { Text(summary) },
            content = { Text(name) },
        )
    }
}
