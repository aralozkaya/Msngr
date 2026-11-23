package org.aralozkaya.msngr.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.CompactSearchField
import org.aralozkaya.msngr.LocalLayoutSettings
import org.aralozkaya.msngr.MessengerPlusText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.numberSentenceResource
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.quantityNumberSentenceResource
import org.aralozkaya.msngr.withExpandedMsnMenuTokens

@Composable
fun GroupDetailsPage(
    group: Contact?,
    members: List<Contact>,
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onOpenChat: (Contact) -> Unit,
    onOpenProfile: (Contact) -> Unit,
    ownEmail: String,
    onEditProfile: () -> Unit,
    onRefreshPicture: suspend (Contact) -> Unit = {},
    onAddContact: (Contact) -> Unit = {},
) {
    val searchFieldState = rememberSaveable(group?.id, saver = TextFieldState.Saver) { TextFieldState() }
    val search = searchFieldState.text.toString().withExpandedMsnMenuTokens().trim()
    val membership = group?.members.orEmpty().associateBy { it.email.lowercase() }
    val pendingCount = members.count { membership[it.email.lowercase()]?.isPending == true }
    val memberCount = members.size - pendingCount
    val visible = members.filter {
        it.displayName.messengerPlusPlainText().contains(search, ignoreCase = true) ||
            it.email.contains(search, ignoreCase = true)
    }.sortedWith(compareBy<Contact> { membership[it.email.lowercase()]?.isPending == true }
        .thenBy { if (membership[it.email.lowercase()]?.isPending == true) 0 else it.presence.priority }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.messengerPlusPlainText() })

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        if (group == null) {
            Text(stringResource(R.string.group_details_unavailable), Modifier.padding(24.dp))
            return@Column
        }
        val layout = LocalLayoutSettings.current
        val compactSearchAndSort = layout.compactSearchAndSort
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactSearchAndSort) 8.dp else 12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth()
            ) {
                MessengerPlusText(text = group.displayName, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val memberText = quantityNumberSentenceResource(
                    memberCount,
                    R.string.group_members_count_singular_before_number,
                    R.string.group_members_count_singular_after_number,
                    R.string.group_members_count_plural_before_number,
                    R.string.group_members_count_plural_after_number,
                )
                val summaryText = if (pendingCount > 0) {
                    val pendingText = numberSentenceResource(
                        R.string.group_pending_count_before_number,
                        pendingCount,
                        R.string.group_pending_count_after_number,
                    )
                    stringResource(R.string.group_count_separator, memberText, pendingText)
                } else {
                    memberText
                }
                Text(summaryText, style = MaterialTheme.typography.bodyMedium)
            }
            CompactSearchField(
                state = searchFieldState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.group_search_members_placeholder),
                clearContentDescription = stringResource(R.string.contacts_search_clear),
                compact = compactSearchAndSort,
            )
        }
        PageContentList(
            modifier = Modifier.fillMaxWidth().weight(1f),
            innerPadding = PaddingValues(),
            scrollBehavior = scrollBehavior,
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(visible, key = { _, member -> member.id }) { index, member ->
                val isOwnContact = member.email.equals(ownEmail, ignoreCase = true)
                val groupMember = membership[member.email.lowercase()]
                val isPending = groupMember?.isPending == true
                LaunchedEffect(member.id, isOwnContact) {
                    if (!isOwnContact) onRefreshPicture(member)
                }
                ContactRow(
                    index = index,
                    count = visible.size,
                    contact = member,
                    supportingText = member.email,
                    accessLevel = if (isPending) null else (groupMember?.role ?: GroupMemberRole.Unknown).displayLabel(),
                    isPendingMember = isPending,
                    onClick = if (isOwnContact) null else { {
                        if (member.isForwardListContact) onOpenChat(member) else onAddContact(member)
                    } },
                    onOpenProfile = { if (isOwnContact) onEditProfile() else onOpenProfile(member) },
                    profileActionLabel = if (isOwnContact) stringResource(R.string.group_edit_profile_action) else null,
                    isOwnContact = isOwnContact,
                )
            }
            if (visible.isEmpty()) item {
                Text(
                    stringResource(if (members.isEmpty()) R.string.group_members_empty_none else R.string.group_members_empty_search),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}
