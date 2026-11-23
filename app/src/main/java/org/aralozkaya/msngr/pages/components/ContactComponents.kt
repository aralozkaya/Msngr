@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.LocalLayoutSettings
import org.aralozkaya.msngr.LocalMessengerScene
import org.aralozkaya.msngr.MessengerPlusText
import org.aralozkaya.msngr.MsnEmoticonText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.SceneBackdrop
import org.aralozkaya.msngr.UiDensity
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.avatarInitial
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.isFavorite
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.localizeUserMessage
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.quantityNumberSentenceResource
import org.aralozkaya.msngr.rememberAvatarImage
import org.aralozkaya.msngr.sceneContentColor
import org.aralozkaya.msngr.ui.theme.avatar
import org.aralozkaya.msngr.ui.theme.ownDisplayPicture
import org.aralozkaya.msngr.ui.theme.pill

@Composable
fun LoggedInUserProfileCard(
    profile: UserProfileSnapshot,
    onPresenceSelected: (Presence) -> Unit,
    onEditProfile: () -> Unit,
) {
    val context = LocalContext.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    var presenceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val groupInteractionSource = remember { MutableInteractionSource() }
    val scene = LocalMessengerScene.current
    val sceneInk = sceneContentColor()

    ElevatedCard(
        onClick = onEditProfile,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("logged_in_user_card"),
    ) {
        SceneBackdrop(scene, Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name = profile.username.messengerPlusPlainText(),
                    avatarUri = profile.avatarUri,
                    presence = profile.presence,
                    showPresenceIndicator = false,
                    size = 56.dp,
                    shape = MaterialTheme.shapes.ownDisplayPicture,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MessengerPlusText(
                        text = profile.username,
                        sceneGlow = scene != null,
                        color = if (scene != null) sceneInk else androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val currentMedia = profile.currentMedia
                    val statusStyle = MaterialTheme.typography.bodyMedium
                    if (currentMedia != null) {
                        CurrentMediaLine(
                            media = currentMedia,
                            modifier = Modifier.testTag("own-profile-current-media"),
                            sceneGlow = scene != null,
                            textColor = if (scene != null) sceneInk else MaterialTheme.colorScheme.onSurfaceVariant,
                            iconTint = if (scene != null) sceneInk else MaterialTheme.colorScheme.primary,
                            style = statusStyle,
                        )
                    } else {
                        MsnEmoticonText(
                            text = profile.statusMessage.ifBlank {
                                stringResource(R.string.profile_no_personal_message)
                            }.let { localizeUserMessage(it) },
                            emoticonStyle = emoticonSettings.style,
                            sceneGlow = scene != null,
                            style = statusStyle,
                            color = if (scene != null) sceneInk else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        FilledTonalButton(
                            onClick = { presenceMenuExpanded = true },
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PresenceDot(profile.presence, modifier = Modifier.size(9.dp))
                                Text(
                                    profile.presence.displayOwnStatusLabel(),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.profile_change_status),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenuPopup(
                            expanded = presenceMenuExpanded,
                            onDismissRequest = { presenceMenuExpanded = false },
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(0, 1),
                                interactionSource = groupInteractionSource,
                            ) {
                                Presence.entries.forEachIndexed { index, presence ->
                                    DropdownMenuItem(
                                        text = { Text(presence.displayOwnStatusLabel()) },
                                        onClick = {
                                            presenceMenuExpanded = false
                                            onPresenceSelected(presence)
                                        },
                                        shape = MenuDefaults.itemShape(index, Presence.entries.size).shape
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactRow(
    index: Int,
    count: Int,
    contact: Contact,
    onClick: (() -> Unit)?,
    contextActionLabel: String? = null,
    onContextAction: () -> Unit = {},
    onReadStateChange: ((Boolean) -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null,
    hasDraft: Boolean = false,
    supportingText: String? = null,
    onDeleteContact: (() -> Unit)? = null,
    accessLevel: String? = null,
    profileActionLabel: String? = null,
    isOwnContact: Boolean = false,
    isPendingMember: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    var contextMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val groupInteractionSource = remember { MutableInteractionSource() }
    val layout = LocalLayoutSettings.current
    val density = layout.density
    val compact = layout.isCompact
    val inlineStatus = compact && layout.compactInlineStatusMessage
    val hidePresenceText = compact && layout.compactHidePresenceText
    val hasLongClickActions = contextActionLabel != null ||
        onReadStateChange != null ||
        onDeleteContact != null ||
        onToggleFavorite != null
    val longClickLabel = stringResource(R.string.contact_long_click_actions)
    val rowSpacing = when (density) {
        UiDensity.Minimal -> 8.dp
        UiDensity.Small -> 10.dp
        UiDensity.Medium -> 12.dp
        UiDensity.Regular -> 14.dp
    }
    val nameStyle = when (density) {
        UiDensity.Minimal, UiDensity.Small -> MaterialTheme.typography.bodyMedium
        UiDensity.Medium -> MaterialTheme.typography.titleSmall
        UiDensity.Regular -> MaterialTheme.typography.titleMedium
    }.copy(fontWeight = FontWeight.SemiBold)
    val supportingStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium

    Box(modifier = Modifier.fillMaxWidth()) {
        ContactReadAction(
            contactId = contact.id,
            unread = contact.isUnread,
            onReadStateChange = onReadStateChange,
            onClick = onClick ?: {},
            swipeEnabled = !compact,
        ) { rowModifier, onRowClick ->
            val rowContent: @Composable () -> Unit = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing),
                ) {
                    val avatarProfileDescription = profileActionLabel ?: stringResource(
                        if (contact.isGroup) R.string.contact_view_group_members_of else R.string.contact_view_profile_of,
                        contact.displayName.messengerPlusPlainText(),
                    )
                    val profileClickModifier = if (onOpenProfile != null) {
                        val rippleRadius = when (density) {
                            UiDensity.Minimal -> 16.dp
                            UiDensity.Small -> 20.dp
                            UiDensity.Medium -> 24.dp
                            UiDensity.Regular -> 28.dp
                        }
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = false, radius = rippleRadius),
                                role = Role.Button,
                                onClickLabel = profileActionLabel ?: stringResource(
                                    if (contact.isGroup) R.string.contact_view_group_members else R.string.contact_view_profile,
                                ),
                                onClick = onOpenProfile,
                            )
                            .testTag("contact-profile-${contact.id}")
                            .semantics {
                                contentDescription = avatarProfileDescription
                            }
                    } else Modifier

                    if (density == UiDensity.Minimal) {
                        Box(
                            modifier = profileClickModifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!isPendingMember) {
                                PresenceDot(
                                    presence = contact.presence,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                    } else {
                        val avatarSize = when (density) {
                            UiDensity.Small -> 32.dp
                            UiDensity.Medium -> 44.dp
                            UiDensity.Regular -> 56.dp
                            UiDensity.Minimal -> 32.dp
                        }
                        Avatar(
                            name = contact.displayName.messengerPlusPlainText(),
                            avatarUri = contact.avatarUri,
                            presence = contact.presence,
                            showPresenceIndicator = !isPendingMember,
                            size = avatarSize,
                            modifier = profileClickModifier,
                        )
                    }

                    val resolvedSupportingText = if (!hasDraft && (supportingText != null || contact.isGroup || contact.currentMedia == null || !contact.presence.isOnline)) {
                        supportingText ?: if (contact.isGroup) {
                            quantityNumberSentenceResource(
                                count = contact.joinedMembers.size,
                                oneBeforeRes = R.string.group_members_count_singular_before_number,
                                oneAfterRes = R.string.group_members_count_singular_after_number,
                                otherBeforeRes = R.string.group_members_count_plural_before_number,
                                otherAfterRes = R.string.group_members_count_plural_after_number,
                            )
                        } else {
                            localizeUserMessage(contact.statusMessage.ifBlank { contact.email })
                        }
                    } else {
                        null
                    }

                    val supportingContent: @Composable (Modifier) -> Unit = { contentModifier ->
                        if (hasDraft) {
                            Row(
                                modifier = contentModifier,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (compact) 12.dp else 14.dp),
                                )
                                Text(
                                    text = stringResource(R.string.contact_draft_label),
                                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else if (resolvedSupportingText != null) {
                            MsnEmoticonText(
                                text = if (inlineStatus) "- $resolvedSupportingText" else resolvedSupportingText,
                                modifier = contentModifier,
                                emoticonStyle = emoticonSettings.style,
                                style = supportingStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (contact.currentMedia != null) {
                            CurrentMediaLine(
                                media = contact.currentMedia,
                                modifier = contentModifier.testTag("contact-current-media-${contact.id}"),
                                style = supportingStyle,
                            )
                        }
                    }

                    if (inlineStatus) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MsnEmoticonText(
                                text = contact.displayName,
                                emoticonStyle = emoticonSettings.style,
                                style = nameStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isOwnContact) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) { Text(stringResource(R.string.common_you_singular)) }
                            }
                            if (isPendingMember) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ) { Text(stringResource(R.string.contact_badge_pending)) }
                            }
                            supportingContent(Modifier.weight(1f, fill = false))
                        }
                    } else {
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                            ) {
                                MsnEmoticonText(
                                    text = contact.displayName,
                                    modifier = Modifier.weight(1f, fill = false),
                                    emoticonStyle = emoticonSettings.style,
                                    style = nameStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isOwnContact) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ) { Text(stringResource(R.string.common_you_singular)) }
                                }
                                if (isPendingMember) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    ) { Text(stringResource(R.string.contact_badge_pending)) }
                                }
                            }
                            supportingContent(Modifier)
                        }
                    }

                    val showPresenceLabel = !isPendingMember && !hidePresenceText
                    val hasStatusLabels = accessLevel != null || showPresenceLabel
                    val statusLabels: @Composable () -> Unit = {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 4.dp),
                        ) {
                            val labelStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
                            accessLevel?.let {
                                Text(it, style = labelStyle)
                            }
                            if (showPresenceLabel) Text(
                                text = if (contact.isGroup) stringResource(R.string.common_group_singular) else contact.presence.displayLabel(),
                                style = labelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val unreadBadge: (@Composable () -> Unit)? = when {
                        contact.unreadCount > 0 -> {
                            { Badge { Text(contact.unreadCount.coerceAtMost(99).toString()) } }
                        }
                        contact.isMarkedUnread -> {
                            {
                                val unreadDesc = stringResource(R.string.contact_unread_description)
                                Badge(modifier = Modifier.semantics { contentDescription = unreadDesc }) {
                                    Text("")
                                }
                            }
                        }
                        else -> null
                    }
                    if (compact) {
                        if (hasStatusLabels || unreadBadge != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (hasStatusLabels) statusLabels()
                                unreadBadge?.invoke()
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            statusLabels()
                            unreadBadge?.invoke()
                        }
                    }
                }
            }
            val modifier = rowModifier.fillMaxWidth().testTag("contact-row-${contact.id}")
            val shapes = ListItemDefaults.segmentedShapes(index, count)
            if (compact) {
                val minHeight = when (density) {
                    UiDensity.Minimal -> if (inlineStatus) 34.dp else 38.dp
                    UiDensity.Small -> if (inlineStatus) 38.dp else 44.dp
                    UiDensity.Medium -> if (inlineStatus) 46.dp else 52.dp
                    UiDensity.Regular -> 56.dp
                }
                val horizontalPad = if (density == UiDensity.Medium) 12.dp else 10.dp
                val verticalPad = when (density) {
                    UiDensity.Minimal -> 4.dp
                    UiDensity.Small -> 6.dp
                    UiDensity.Medium -> 8.dp
                    UiDensity.Regular -> 8.dp
                }
                val compactModifier = modifier.heightIn(min = minHeight)
                val compactPadding = PaddingValues(horizontal = horizontalPad, vertical = verticalPad)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    if (onClick == null) {
                        SegmentedListItem(
                            shapes = shapes,
                            modifier = compactModifier,
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = compactPadding,
                            content = rowContent,
                        )
                    } else {
                        SegmentedListItem(
                            onClick = onRowClick,
                            onLongClick = if (hasLongClickActions) {
                                { contextMenuExpanded = true }
                            } else null,
                            onLongClickLabel = if (hasLongClickActions) longClickLabel else null,
                            shapes = shapes,
                            modifier = compactModifier,
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = compactPadding,
                            content = rowContent,
                        )
                    }
                }
            } else {
                if (onClick == null) {
                    SegmentedListItem(shapes = shapes, modifier = modifier, content = rowContent)
                } else {
                    SegmentedListItem(
                        onClick = onRowClick,
                        onLongClick = if (hasLongClickActions) {
                            { contextMenuExpanded = true }
                        } else null,
                        onLongClickLabel = longClickLabel,
                        shapes = shapes,
                        modifier = modifier,
                        content = rowContent,
                    )
                }
            }
        }
        DropdownMenuPopup(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false },
        ) {
            val primaryActions = buildList<Pair<String, () -> Unit>> {
                onReadStateChange?.let { changeReadState ->
                    add(
                        stringResource(
                            if (contact.isUnread) R.string.contact_mark_as_read else R.string.contact_mark_as_unread,
                        ) to { changeReadState(!contact.isUnread) },
                    )
                }
                contextActionLabel?.let { label ->
                    add(label to onContextAction)
                }
            }
            val categoryActions = buildList<Triple<String, (@Composable () -> Unit)?, () -> Unit>> {
                onToggleFavorite?.let { toggleFavorite ->
                    val isFav = contact.isFavorite
                    add(
                        Triple(
                            stringResource(
                                if (isFav) R.string.contact_remove_from_favorites else R.string.contact_add_to_favorites,
                            ),
                            {
                                Icon(
                                    imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = null,
                                )
                            },
                            toggleFavorite,
                        ),
                    )
                }
            }
            val totalGroups = (if (categoryActions.isNotEmpty()) 1 else 0) +
                (if (primaryActions.isNotEmpty()) 1 else 0) +
                (if (onDeleteContact != null) 1 else 0)
            var currentGroupIndex = 0

            if (categoryActions.isNotEmpty()) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(currentGroupIndex++, totalGroups),
                    interactionSource = groupInteractionSource,
                ) {
                    categoryActions.forEachIndexed { actionIndex, (label, leading, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = leading,
                            onClick = {
                                contextMenuExpanded = false
                                action()
                            },
                            shape = MenuDefaults.itemShape(actionIndex, categoryActions.size).shape,
                        )
                    }
                }
            }
            if (primaryActions.isNotEmpty()) {
                if (currentGroupIndex > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(currentGroupIndex++, totalGroups),
                    interactionSource = groupInteractionSource,
                ) {
                    primaryActions.forEachIndexed { actionIndex, (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                contextMenuExpanded = false
                                action()
                            },
                            shape = MenuDefaults.itemShape(actionIndex, primaryActions.size).shape,
                        )
                    }
                }
            }
            onDeleteContact?.let { delete ->
                if (currentGroupIndex > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(currentGroupIndex, totalGroups),
                    interactionSource = groupInteractionSource,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contact_delete_action), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            contextMenuExpanded = false
                            delete()
                        },
                        shape = MenuDefaults.standaloneItemShape,
                    )
                }
            }

        }
    }
}

@Composable
fun Avatar(
    name: String,
    avatarUri: String?,
    presence: Presence,
    modifier: Modifier = Modifier,
    showPresenceIndicator: Boolean = true,
    imageVersion: Int = 0,
    highResolution: Boolean = false,
    size: Dp = 56.dp,
    shape: Shape = MaterialTheme.shapes.avatar,
) {
    val painter = rememberAvatarImage(avatarUri, highResolution, imageVersion).painter

    Box(modifier = modifier.size(size)) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = stringResource(R.string.contact_avatar_description, name),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = avatarInitial(name),
                        style = when {
                            size <= 36.dp -> MaterialTheme.typography.labelLarge
                            size <= 48.dp -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleLarge
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (showPresenceIndicator) {
            val indicatorSize = if (size == 56.dp) 18.dp else (size * (18f / 56f)).coerceAtLeast(12.dp)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(indicatorSize),
                shape = MaterialTheme.shapes.pill,
                color = MaterialTheme.colorScheme.surface,
            ) {
                PresenceDot(
                    presence = presence,
                    modifier = Modifier.padding(if (size <= 36.dp) 2.dp else 3.dp).fillMaxSize(),
                )
            }
        }
    }
}
