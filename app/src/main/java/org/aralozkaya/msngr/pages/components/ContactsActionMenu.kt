@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.R

@Composable
fun ContactsActionMenu(
    modifier: Modifier = Modifier,
    onAddContact: () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onCreateCategory: () -> Unit = {},
    categoryCreationVisible: Boolean = false,
    onCreateCategoryBoundsChanged: (Rect) -> Unit = {},
    groupCreationVisible: Boolean = false,
    onCreateGroupBoundsChanged: (Rect) -> Unit = {},
    contactInvitationVisible: Boolean = false,
    onAddContactBoundsChanged: (Rect) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) R.string.contacts_actions_close else R.string.contacts_actions_open,
    )

    BackHandler(enabled = expanded) { expanded = false }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ActionMenuToggle(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                label = toggleLabel,
                icon = Icons.Filled.Add,
            )
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = onAddContact,
            modifier = Modifier.dialogSource(contactInvitationVisible, onAddContactBoundsChanged),
            text = { Text(stringResource(R.string.contacts_action_add_contact)) },
            icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = onCreateGroup,
            modifier = Modifier.dialogSource(groupCreationVisible, onCreateGroupBoundsChanged),
            text = { Text(stringResource(R.string.contacts_action_create_group)) },
            icon = { Icon(Icons.Filled.GroupAdd, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = onCreateCategory,
            modifier = Modifier.dialogSource(categoryCreationVisible, onCreateCategoryBoundsChanged),
            text = { Text(stringResource(R.string.contacts_action_create_category)) },
            icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
        )
    }
}

private fun Modifier.dialogSource(dialogVisible: Boolean, onBoundsChanged: (Rect) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        onBoundsChanged(Rect(coordinates.positionOnScreen(), coordinates.size.toSize()))
    }.graphicsLayer { alpha = if (dialogVisible) 0f else 1f }
