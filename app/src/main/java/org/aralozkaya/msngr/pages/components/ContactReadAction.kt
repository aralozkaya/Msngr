package org.aralozkaya.msngr.pages.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import org.aralozkaya.msngr.R

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
    val actionLabel = stringResource(if (unread) R.string.contact_mark_as_read else R.string.contact_mark_as_unread)
    val stateLabel = stringResource(if (unread) R.string.contact_unread_description else R.string.contact_read_description)
    SwipeRevealAction(
        itemKey = contactId,
        label = actionLabel,
        icon = if (unread) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
        onAction = { onReadStateChange(!unread) },
        onClick = onClick,
        closeAfterAction = true,
    ) { modifier, onRowClick ->
        content(modifier.semantics { stateDescription = stateLabel }, onRowClick)
    }
}
