@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.CompactSearchField
import org.aralozkaya.msngr.ContactProfileActivity
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.GroupDetailsActivity
import org.aralozkaya.msngr.LocalFavoritesLayoutSettings
import org.aralozkaya.msngr.LocalLayoutSettings
import org.aralozkaya.msngr.MessengerPlusEditorController
import org.aralozkaya.msngr.MsnEmoticonText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactCategory
import org.aralozkaya.msngr.data.FAVORITES_CATEGORY_ID
import org.aralozkaya.msngr.data.GROUPS_CATEGORY_ID
import org.aralozkaya.msngr.data.OTHER_CONTACTS_CATEGORY_ID
import org.aralozkaya.msngr.data.belongsToCategory
import org.aralozkaya.msngr.data.resolveOrderedCategoryIds
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.hasMessengerPlusVisibleContent
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.LoggedInUserProfileCard
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.withExpandedMsnMenuTokens

private enum class ContactSort(@param:StringRes val labelRes: Int) {
    Status(R.string.contacts_sort_status), Name(R.string.contacts_sort_name),
}

@Composable
fun ContactsPage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    contacts: List<Contact>,
    profile: UserProfileSnapshot,
    onPresenceSelected: (Presence) -> Unit,
    onEditProfile: () -> Unit,
    onOpenChat: (Contact) -> Unit,
    onBlockContact: (Contact) -> Unit,
    onReadStateChange: (Contact, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isCurrentPage: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    drafts: Map<String, String> = emptyMap(),
    onDeleteContact: ((Contact) -> Unit)? = null,
    categories: List<ContactCategory> = emptyList(),
    categoryOrder: List<String> = emptyList(),
    hiddenCategoryIds: Set<String> = emptySet(),
    onToggleFavorite: ((Contact) -> Unit)? = null,
    onEditCategory: ((ContactCategory) -> Unit)? = null,
    onDeleteCategory: ((ContactCategory) -> Unit)? = null,
) {
    val context = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(ContactSort.Status) }
    var hideOffline by rememberSaveable { mutableStateOf(false) }
    var showSortOptions by rememberSaveable { mutableStateOf(false) }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    val searchFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() }
    val searchBarState = rememberSearchBarState()
    val searchEditorController = remember { MessengerPlusEditorController() }
    var searchFocused by remember { mutableStateOf(false) }
    var leaveSearchRequested by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = isCurrentPage && (searchFocused || searchFieldState.text.isNotEmpty())) {
        leaveSearchRequested = true
        searchEditorController.hideKeyboard()
    }
    LaunchedEffect(leaveSearchRequested, keyboardVisible) {
        if (leaveSearchRequested && !keyboardVisible) {
            searchFieldState.edit { replace(0, length, "") }
            focusManager.clearFocus()
            searchEditorController.clearFocusAndHideKeyboard()
            searchFocused = false
            leaveSearchRequested = false
        }
    }
    val search = searchFieldState.text.toString().withExpandedMsnMenuTokens().trim()
    val orderedCategoryIds = remember(categoryOrder, categories, hiddenCategoryIds) {
        resolveOrderedCategoryIds(categoryOrder, categories).filterNot { it in hiddenCategoryIds }
    }
    val customCategoriesById = remember(categories) {
        categories.filterNot(ContactCategory::isBuiltIn).associateBy(ContactCategory::id)
    }
    LaunchedEffect(search, orderedCategoryIds) {
        if (search.isNotEmpty()) {
            orderedCategoryIds.forEach { expandedSections[it] = true }
        }
    }
    val comparator = when (sort) {
        ContactSort.Name -> compareBy<Contact, String>(String.CASE_INSENSITIVE_ORDER) {
            it.displayName.messengerPlusPlainText()
        }
        ContactSort.Status -> compareBy<Contact> { it.presence.priority }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.messengerPlusPlainText() }
    }
    val sorted = contacts.filter { !it.isBlocked && (it.isGroup || it.isForwardListContact) }
        .filterNot { hideOffline && !it.presence.isOnline }
        .filter {
            it.displayName.messengerPlusPlainText().contains(search, ignoreCase = true) ||
                it.email.contains(search, ignoreCase = true)
        }.sortedWith(comparator)
    val layout = LocalLayoutSettings.current
    val compact = layout.isCompact
    val compactSearchAndSort = layout.compactSearchAndSort
    val favoritesLayout = LocalFavoritesLayoutSettings.current
    val itemSpacing = if (compact) 6.dp else 12.dp
    PageContentList(
        modifier = modifier.fillMaxSize(),
        listState = listState,
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        item {
            LoggedInUserProfileCard(
                profile = profile,
                onPresenceSelected = onPresenceSelected,
                onEditProfile = onEditProfile,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    top = if (compactSearchAndSort) 0.dp else (24.dp - itemSpacing),
                    bottom = if (compactSearchAndSort) 0.dp else (12.dp - itemSpacing),
                ),
                horizontalArrangement = ButtonGroupDefaults.HorizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compactSearchAndSort) {
                    CompactSearchField(
                        state = searchFieldState,
                        placeholder = stringResource(R.string.contacts_search_placeholder),
                        clearContentDescription = stringResource(R.string.contacts_search_clear),
                        modifier = Modifier.weight(1f).testTag("contacts_search_bar"),
                        compact = true,
                        controller = searchEditorController,
                        onFocusChanged = { searchFocused = it },
                    )
                    val compactButtonHeight = 36.dp
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        TextButton(
                            onClick = { showSortOptions = true },
                            modifier = Modifier
                                .testTag("contacts_sort_button")
                                .defaultMinSize(minWidth = compactButtonHeight, minHeight = compactButtonHeight),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.contacts_sort_action),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                } else {
                    SearchBar(
                        state = searchBarState,
                        modifier = Modifier.weight(1f).testTag("contacts_search_bar"),
                        inputField = {
                            // Keep the app's native editor so search filters the page in place.
                            CompactSearchField(
                                state = searchFieldState,
                                placeholder = stringResource(R.string.contacts_search_placeholder),
                                clearContentDescription = stringResource(R.string.contacts_search_clear),
                                modifier = Modifier.fillMaxWidth(),
                                controller = searchEditorController,
                                onFocusChanged = { searchFocused = it },
                            )
                        },
                    )
                    val iconSize = ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)
                    FilledTonalButton(
                        onClick = { showSortOptions = true },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .testTag("contacts_sort_button")
                            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                            .widthIn(min = ButtonDefaults.MinHeight),
                        contentPadding = if (searchFocused) {
                            PaddingValues(horizontal = (ButtonDefaults.MinHeight - iconSize) / 2)
                        } else {
                            ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true)
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.contacts_sort_action),
                            modifier = Modifier.size(iconSize),
                        )
                        if (!searchFocused) {
                            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MinHeight)))
                            Text(stringResource(R.string.contacts_sort_action))
                        }
                    }
                }
            }
        }
        orderedCategoryIds.forEach { categoryId ->
            val sectionKey = when (categoryId) {
                FAVORITES_CATEGORY_ID -> "favorites-section"
                GROUPS_CATEGORY_ID -> "groups-section"
                OTHER_CONTACTS_CATEGORY_ID -> "contacts-section"
                else -> "category-section-$categoryId"
            }
            item(key = sectionKey) {
                val expanded = expandedSections[categoryId] ?: true
                val transition = updateTransition(expanded, label = "Section $categoryId expansion")
                val onToggle = { expandedSections[categoryId] = !expanded }
                val sectionItems = sorted.filter { it.belongsToCategory(categoryId) }
                val renderRow: @Composable (Int, Int, Contact) -> Unit = { itemIndex, itemCount, contact ->
                    val sectionCompact = LocalLayoutSettings.current.isCompact
                    key("$categoryId-${contact.id}") {
                        ContactRow(
                            index = if (sectionCompact) itemIndex else itemIndex + 1,
                            count = if (sectionCompact) itemCount else itemCount + 1,
                            contact = contact,
                            hasDraft = drafts[contact.id]?.hasMessengerPlusVisibleContent() == true,
                            onClick = { onOpenChat(contact) },
                            onOpenProfile = {
                                context.startActivity(
                                    if (contact.isGroup) GroupDetailsActivity.createIntent(context, contact.id)
                                    else ContactProfileActivity.createIntent(context, contact.id),
                                )
                            },
                            contextActionLabel = stringResource(R.string.contacts_block_action),
                            onContextAction = { onBlockContact(contact) },
                            onReadStateChange = { unread -> onReadStateChange(contact, unread) },
                            onDeleteContact = if (contact.isGroup) null else onDeleteContact?.let { delete -> { delete(contact) } },
                            onToggleFavorite = onToggleFavorite?.let { toggle -> { toggle(contact) } },
                        )
                    }
                }

                when (categoryId) {
                    FAVORITES_CATEGORY_ID -> {
                        CompositionLocalProvider(LocalLayoutSettings provides favoritesLayout) {
                            ExpandableContactSection(
                                title = stringResource(R.string.contacts_section_favorites),
                                count = sectionItems.size,
                                transition = transition,
                                onToggle = onToggle,
                                modifier = Modifier.testTag("section-$FAVORITES_CATEGORY_ID"),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(if (LocalLayoutSettings.current.isCompact) 18.dp else 22.dp),
                                    )
                                },
                            ) {
                                if (sectionItems.isEmpty()) {
                                    EmptySectionItem {
                                        Text(
                                            stringResource(
                                                when {
                                                    search.isNotEmpty() -> R.string.contacts_favorites_empty_search_you_singular
                                                    hideOffline -> R.string.contacts_favorites_empty_online
                                                    else -> R.string.contacts_favorites_empty
                                                },
                                            ),
                                        )
                                    }
                                } else {
                                    sectionItems.forEachIndexed { index, contact ->
                                        renderRow(index, sectionItems.size, contact)
                                    }
                                }
                            }
                        }
                    }

                    GROUPS_CATEGORY_ID -> {
                        ExpandableContactSection(
                            title = stringResource(R.string.contacts_section_groups_plural),
                            count = sectionItems.size,
                            transition = transition,
                            onToggle = onToggle,
                            modifier = Modifier.testTag("section-$GROUPS_CATEGORY_ID"),
                        ) {
                            sectionItems.forEachIndexed { index, group ->
                                renderRow(index, sectionItems.size, group)
                            }
                            if (sectionItems.isEmpty()) {
                                EmptySectionItem {
                                    Text(
                                        stringResource(
                                            when {
                                                search.isNotEmpty() -> R.string.contacts_groups_empty_search_you_singular
                                                hideOffline -> R.string.contacts_groups_empty_online
                                                else -> R.string.contacts_groups_empty
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    OTHER_CONTACTS_CATEGORY_ID -> {
                        ExpandableContactSection(
                            title = stringResource(R.string.contacts_section_other),
                            count = sectionItems.size,
                            transition = transition,
                            onToggle = onToggle,
                            modifier = Modifier.testTag("section-$OTHER_CONTACTS_CATEGORY_ID"),
                        ) {
                            if (sectionItems.isEmpty()) {
                                EmptySectionItem {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(if (search.isNotEmpty()) R.string.contacts_empty_search else R.string.contacts_empty))
                                        Text(
                                            stringResource(
                                                when {
                                                    search.isNotEmpty() -> R.string.contacts_empty_search_subtitle
                                                    hideOffline -> R.string.contacts_empty_offline_subtitle
                                                    else -> R.string.contacts_empty_list_subtitle_you_singular
                                                },
                                            ),
                                        )
                                    }
                                }
                            } else {
                                sectionItems.forEachIndexed { index, contact ->
                                    renderRow(index, sectionItems.size, contact)
                                }
                            }
                        }
                    }

                    else -> {
                        val category = customCategoriesById[categoryId] ?: return@item
                        val showCategoryMenu = category.isEditable && (onEditCategory != null || onDeleteCategory != null)
                        ExpandableContactSection(
                            title = category.name,
                            count = sectionItems.size,
                            transition = transition,
                            onToggle = onToggle,
                            modifier = Modifier.testTag("section-${category.id}"),
                            headerActions = if (showCategoryMenu) {
                                {
                                    CategoryHeaderMenu(
                                        category = category,
                                        onEdit = onEditCategory?.let { edit -> { edit(category) } },
                                        onDelete = onDeleteCategory?.let { delete -> { delete(category) } },
                                    )
                                }
                            } else null,
                        ) {
                            if (sectionItems.isEmpty()) {
                                EmptySectionItem {
                                    Text(
                                        stringResource(
                                            when {
                                                search.isNotEmpty() -> R.string.contacts_category_empty_search_you_singular
                                                hideOffline -> R.string.contacts_category_empty_online
                                                else -> R.string.contacts_category_empty
                                            },
                                        ),
                                    )
                                }
                            } else {
                                sectionItems.forEachIndexed { index, contact ->
                                    renderRow(index, sectionItems.size, contact)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortOptions) {
        ContactSortSheet(
            sort = sort,
            hideOffline = hideOffline,
            onSortSelected = { sort = it },
            onHideOfflineChanged = { hideOffline = it },
            onDismissRequest = { showSortOptions = false },
        )
    }
}

@Composable
private fun CategoryHeaderMenu(
    category: ContactCategory,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var expanded by remember(category.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val compact = LocalLayoutSettings.current.isCompact
    Box {
        IconButton(
            onClick = { expanded = true },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .size(
                    IconButtonDefaults.smallContainerSize(
                        widthOption = IconButtonDefaults.IconButtonWidthOption.Narrow,
                    ),
                )
                .testTag("category-options-${category.id}"),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.category_options_description),
                modifier = Modifier.size(if (compact) 18.dp else 20.dp),
            )
        }
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (onEdit != null) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(0, if (onDelete != null) 2 else 1),
                    interactionSource = interactionSource,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.category_edit_action)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onEdit()
                        },
                        shape = MenuDefaults.standaloneItemShape,
                    )
                }
            }
            if (onEdit != null && onDelete != null) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            if (onDelete != null) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(if (onEdit != null) 1 else 0, if (onEdit != null) 2 else 1),
                    interactionSource = interactionSource,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.category_delete_action), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            expanded = false
                            onDelete()
                        },
                        shape = MenuDefaults.standaloneItemShape,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ContactSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    headerActions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val stateLabel = stringResource(if (expanded) R.string.contacts_section_expanded else R.string.contacts_section_collapsed)
    val toggleDesc = stringResource(
        if (expanded) R.string.contacts_section_collapse else R.string.contacts_section_expand,
        title,
    )
    if (LocalLayoutSettings.current.isCompact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .semantics {
                    role = Role.Button
                    stateDescription = stateLabel
                }
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            MsnEmoticonText(
                text = title,
                emoticonStyle = emoticonSettings.style,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            headerActions?.invoke()
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = toggleDesc,
                modifier = Modifier.size(20.dp),
            )
        }
        return
    }
    SegmentedListItem(
        onClick = onToggle,
        shapes = ListItemDefaults.segmentedShapes(
            index = 0,
            // An empty expanded section still contains the header and an empty-state row.
            count = if (expanded) count.coerceAtLeast(1) + 1 else 1,
        ),
        colors = ListItemDefaults.segmentedColors(),
        modifier = Modifier.fillMaxWidth()
            .semantics {
                role = Role.Button
                stateDescription = stateLabel
            },
        leadingContent = leadingIcon,
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                headerActions?.invoke()
                Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(4.dp))
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(IconButtonDefaults.standardShape)
                        .background(
                            color = if(expanded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
                            shape = IconButtonDefaults.standardShape
                        )
                        .size(
                            IconButtonDefaults.smallContainerSize(
                                widthOption = IconButtonDefaults.IconButtonWidthOption.Narrow,
                            ),
                        )
                ) {
                    Icon(
                        tint = MaterialTheme.colorScheme.onSurface,
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = toggleDesc,
                    )
                }
            }
        },
    ) {
        MsnEmoticonText(
            text = title,
            emoticonStyle = emoticonSettings.style,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun EmptySectionItem(content: @Composable () -> Unit) {
    if (LocalLayoutSettings.current.isCompact) {
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                content()
            }
        }
        return
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun ExpandableContactSection(
    title: String,
    count: Int,
    transition: Transition<Boolean>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    headerActions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val compact = LocalLayoutSettings.current.isCompact
    Column(modifier.fillMaxWidth()) {
        ContactSectionHeader(
            title = title,
            count = count,
            expanded = transition.targetState,
            onToggle = onToggle,
            leadingIcon = leadingIcon,
            headerActions = headerActions,
        )
        // Bottom alignment slides the entire child block out from behind the parent.
        // Child rows retain their size and spacing throughout the container transform.
        transition.AnimatedVisibility(
            visible = { it },
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 0.dp else ListItemDefaults.SegmentedGap),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactSortSheet(
    sort: ContactSort,
    hideOffline: Boolean,
    onSortSelected: (ContactSort) -> Unit,
    onHideOfflineChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            Text(
                text = stringResource(R.string.contacts_sort_action),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            ContactSort.entries.forEachIndexed { index, option ->
                SegmentedListItem(
                    selected = sort == option,
                    onClick = { onSortSelected(option) },
                    shapes = ListItemDefaults.segmentedShapes(index, ContactSort.entries.size),
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        RadioButton(
                            selected = sort == option,
                            onClick = null,
                        )
                    },
                    content = { Text(stringResource(option.labelRes)) },
                )
            }

            Spacer(Modifier.height(10.dp))
            SegmentedListItem(
                checked = hideOffline,
                onCheckedChange = onHideOfflineChanged,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hide_offline_contacts"),
                trailingContent = {
                    Switch(
                        checked = hideOffline,
                        onCheckedChange = null,
                    )
                },
                content = { Text(stringResource(R.string.contacts_hide_offline)) },
            )
        }
    }
}
