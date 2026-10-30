@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.CompactSearchField
import org.aralozkaya.msngr.ContactProfileActivity
import org.aralozkaya.msngr.GroupDetailsActivity
import org.aralozkaya.msngr.MessengerPlusEditorController
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.hasMessengerPlusVisibleContent
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.LoggedInUserProfileCard
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.withExpandedMsnMenuTokens

private enum class ContactSort(val label: String) {
    Status("Status"), Name("Name"),
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
    listState: LazyListState = rememberLazyListState(),
    drafts: Map<String, String> = emptyMap(),
    onDeleteContact: ((Contact) -> Unit)? = null,
) {
    val context = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(ContactSort.Status) }
    var hideOffline by rememberSaveable { mutableStateOf(false) }
    var showSortOptions by rememberSaveable { mutableStateOf(false) }
    var groupsExpanded by rememberSaveable { mutableStateOf(true) }
    var contactsExpanded by rememberSaveable { mutableStateOf(true) }
    val searchFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() }
    val searchBarState = rememberSearchBarState()
    val searchEditorController = remember { MessengerPlusEditorController() }
    var searchFocused by remember { mutableStateOf(false) }
    var leaveSearchRequested by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = searchFocused || searchFieldState.text.isNotEmpty()) {
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
    LaunchedEffect(search) {
        if (search.isNotEmpty()) {
            groupsExpanded = true
            contactsExpanded = true
        }
    }
    val groupsTransition = updateTransition(groupsExpanded, label = "Groups expansion")
    val contactsTransition = updateTransition(contactsExpanded, label = "Other contacts expansion")
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
    val groups = sorted.filter(Contact::isGroup)
    val visible = sorted.filterNot(Contact::isGroup)
    PageContentList(
        listState = listState,
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LoggedInUserProfileCard(
                profile = profile,
                onPresenceSelected = onPresenceSelected,
                onEditProfile = onEditProfile,
            )
        }
        item {
            val focusTransition = updateTransition(searchFocused, label = "Search focus")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    state = searchBarState,
                    modifier = Modifier.weight(1f).testTag("contacts_search_bar"),
                    inputField = {
                        // Keep the app's native editor so search filters the page in place.
                        CompactSearchField(
                            state = searchFieldState,
                            placeholder = "Search",
                            clearContentDescription = "Clear search",
                            modifier = Modifier.fillMaxWidth(),
                            controller = searchEditorController,
                            onFocusChanged = { searchFocused = it },
                        )
                    },
                )
                focusTransition.AnimatedVisibility(
                    visible = { !it },
                    enter = expandHorizontally(
                        MaterialTheme.motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.Start,
                    ) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    exit = shrinkHorizontally(
                        MaterialTheme.motionScheme.defaultSpatialSpec(),
                        shrinkTowards = Alignment.Start,
                    ) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                ) {
                    FilledTonalButton(
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(start = ButtonGroupDefaults.HorizontalArrangement.spacing),
                        onClick = { showSortOptions = true },
                        contentPadding =
                            ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort",
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)),
                        )
                        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MinHeight)))
                        Text("Sort")
                    }
                }
            }
        }
        item(key = "groups-section") {
            ExpandableContactSection("Groups", groups.size, groupsTransition, { groupsExpanded = !groupsExpanded }) {
                groups.forEachIndexed { index, group ->
                    key(group.id) {
                        ContactRow(index = index + 1, count = groups.size + 1, contact = group,
                            onClick = { onOpenChat(group) }, onOpenProfile = {
                                context.startActivity(GroupDetailsActivity.createIntent(context, group.id))
                            },
                            contextActionLabel = "Block", onContextAction = { onBlockContact(group) },
                            onReadStateChange = { onReadStateChange(group, it) },
                            hasDraft = drafts[group.id]?.hasMessengerPlusVisibleContent() == true)
                    }
                }
                if (groups.isEmpty()) {
                    EmptySectionItem {
                        Text(when {
                            search.isNotEmpty() -> "No groups found"
                            hideOffline -> "No online groups to show"
                            else -> "No groups to show"
                        })
                    }
                }
            }
        }
        item(key = "contacts-section") {
            ExpandableContactSection("Other Contacts", visible.size, contactsTransition, { contactsExpanded = !contactsExpanded }) {
                if (visible.isEmpty()) {
                    EmptySectionItem {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (search.isNotEmpty()) "No contacts found" else "No contacts to show")
                            Text(when {
                                search.isNotEmpty() -> "Try a different name or email."
                                hideOffline -> "Offline contacts are hidden."
                                else -> "Your contact list is empty."
                            })
                        }
                    }
                } else {
                    visible.forEachIndexed { index, contact ->
                        key(contact.id) {
                            ContactRow(
                                index = index + 1,
                                count = visible.size + 1,
                                contact = contact,
                                hasDraft = drafts[contact.id]?.hasMessengerPlusVisibleContent() == true,
                                onClick = { onOpenChat(contact) },
                                onOpenProfile = {
                                    context.startActivity(ContactProfileActivity.createIntent(context, contact.id))
                                },
                                contextActionLabel = "Block",
                                onContextAction = { onBlockContact(contact) },
                                onReadStateChange = { unread -> onReadStateChange(contact, unread) },
                                onDeleteContact = onDeleteContact?.let { delete -> { delete(contact) } },
                            )
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
private fun ContactSectionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
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
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(count.toString(), style = MaterialTheme.typography.labelLarge)
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
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title"
                    )
                }
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptySectionItem(content: @Composable () -> Unit) {
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
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ContactSectionHeader(title, count, transition.targetState, onToggle)
        // Bottom alignment slides the entire child block out from behind the parent.
        // Child rows retain their size and spacing throughout the container transform.
        transition.AnimatedVisibility(
            visible = { it },
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = ListItemDefaults.SegmentedGap),
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
                text = "Sort",
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
                    content = { Text(option.label) },
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
                content = { Text("Hide offline groups and contacts") },
            )
        }
    }
}
