@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.canInviteGroupMembers
import org.aralozkaya.msngr.data.canLeaveGroup

@Composable
internal fun GroupDetailsMenu(
    group: Contact?,
    ownEmail: String,
    onInviteMembers: () -> Unit,
    onLeaveGroup: () -> Unit,
    onOpenNotifications: (() -> Unit)? = null,
) {
    val canInvite = group?.canInviteGroupMembers(ownEmail) == true
    val canLeave = group?.canLeaveGroup(ownEmail) == true
    val canCustomize = group != null && onOpenNotifications != null
    val groupCount = (if (canInvite) 1 else 0) + (if (canLeave) 1 else 0) + (if (canCustomize) 1 else 0)
    if (groupCount == 0) return

    var expanded by remember(group?.id, ownEmail) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box {
        IconButton(
            onClick = { expanded = true },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Group options")
        }
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (canInvite) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(0, groupCount),
                    modifier = Modifier.testTag("group-invite-menu-group"),
                    interactionSource = interactionSource,
                ) {
                    DropdownMenuItem(
                        text = { Text("Invite contacts", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                        shape = if (canCustomize || canLeave) MenuDefaults.leadingItemShape else MenuDefaults.standaloneItemShape,
                        modifier = Modifier.testTag("group-invite"),
                        onClick = { expanded = false; onInviteMembers() },
                    )
                }
            }
            if (canInvite && (canCustomize || canLeave)) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            if (canCustomize) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(if (canInvite) 1 else 0, groupCount),
                    modifier = Modifier.testTag("group-notifications-menu-group"),
                    interactionSource = interactionSource,
                ) {
                    DropdownMenuItem(
                        text = { Text("Custom notifications") },
                        leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                        shape = when {
                            canInvite && canLeave -> MenuDefaults.middleItemShape
                            canInvite -> MenuDefaults.trailingItemShape
                            canLeave -> MenuDefaults.leadingItemShape
                            else -> MenuDefaults.standaloneItemShape
                        },
                        modifier = Modifier.testTag("group_notification_settings"),
                        onClick = { expanded = false; onOpenNotifications?.invoke() },
                    )
                }
            }
            if (canCustomize && canLeave) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            if (canLeave) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(groupCount - 1, groupCount),
                    modifier = Modifier.testTag("group-leave-menu-group"),
                    interactionSource = interactionSource,
                ) {
                    DropdownMenuItem(
                        text = { Text("Leave group", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.GroupOff, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error)
                        },
                        shape = if (canInvite || canCustomize) MenuDefaults.trailingItemShape else MenuDefaults.standaloneItemShape,
                        modifier = Modifier.testTag("group-leave"),
                        onClick = { expanded = false; onLeaveGroup() },
                    )
                }
            }
        }
    }
}
