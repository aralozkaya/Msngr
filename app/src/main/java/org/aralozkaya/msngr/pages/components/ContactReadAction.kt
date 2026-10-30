package org.aralozkaya.msngr.pages.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

@Composable
internal fun ContactReadAction(
    contactId: String,
    unread: Boolean,
    onReadStateChange: ((Boolean) -> Unit)?,
    onClick: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit,
) {
    if (onReadStateChange == null) {
        content(Modifier, onClick)
        return
    }
    SwipeRevealAction(
        itemKey = contactId,
        label = if (unread) "Mark as read" else "Mark as unread",
        icon = if (unread) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
        onAction = { onReadStateChange(!unread) },
        onClick = onClick,
        closeAfterAction = true,
    ) { modifier, onRowClick ->
        content(modifier.semantics { stateDescription = if (unread) "Unread" else "Read" }, onRowClick)
    }
}
