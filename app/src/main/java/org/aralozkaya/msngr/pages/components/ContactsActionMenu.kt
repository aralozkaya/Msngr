@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import org.aralozkaya.msngr.R

@Composable
fun ContactsActionMenu(
    modifier: Modifier = Modifier,
    onAddContact: () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    groupCreationVisible: Boolean = false,
    onCreateGroupBoundsChanged: (Rect) -> Unit = {},
    contactInvitationVisible: Boolean = false,
    onAddContactBoundsChanged: (Rect) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) R.string.contacts_actions_close else R.string.contacts_actions_open,
    )
    val menuState = stringResource(
        if (expanded) R.string.contacts_actions_expanded else R.string.contacts_actions_collapsed,
    )

    BackHandler(enabled = expanded) { expanded = false }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    if (expanded) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above,
                ),
                tooltip = { PlainTooltip { Text(toggleLabel) } },
                state = rememberTooltipState(),
            ) {
                ToggleFloatingActionButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    containerCornerRadius = ToggleFloatingActionButtonDefaults.containerCornerRadius(),
                    modifier = Modifier.semantics {
                        contentDescription = toggleLabel
                        stateDescription = menuState
                        traversalIndex = -1f
                    },
                ) {
                    Icon(
                        imageVector = if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.animateIcon({ checkedProgress }),
                    )
                }
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = onAddContact,
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    onAddContactBoundsChanged(Rect(
                        coordinates.positionOnScreen(),
                        Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                    ))
                }
                .graphicsLayer { alpha = if (contactInvitationVisible) 0f else 1f },
            text = { Text(stringResource(R.string.contacts_action_add_contact)) },
            icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            // Keep the source in place so the dialog can transform back into it.
            onClick = onCreateGroup,
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    onCreateGroupBoundsChanged(Rect(
                        coordinates.positionOnScreen(),
                        Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                    ))
                }
                .graphicsLayer { alpha = if (groupCreationVisible) 0f else 1f },
            text = { Text(stringResource(R.string.contacts_action_create_group)) },
            icon = { Icon(Icons.Filled.GroupAdd, contentDescription = null) },
        )
    }
}
