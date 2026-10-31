package org.aralozkaya.msngr.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.PageContentList

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BlockedContactsPage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    contacts: List<Contact>,
    onUnblockContact: (Contact) -> Unit,
    onDeleteContact: ((Contact) -> Unit)? = null,
    onOpenProfile: ((Contact) -> Unit)? = null,
    refreshing: Boolean = false,
    refreshError: String? = null,
    onRefresh: () -> Unit = {},
) {
    val blocked = contacts
        .filter(Contact::isBlocked)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {
            it.displayName.messengerPlusPlainText()
        })

    if (refreshing || blocked.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (refreshing) {
                val description = stringResource(R.string.blocked_contacts_refreshing)
                LoadingIndicator(Modifier.testTag("blocked-contacts-refresh-indicator")
                    .semantics { contentDescription = description })
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (refreshError != null) BlockedContactsRefreshError(refreshError, onRefresh)
                    else Text(stringResource(R.string.blocked_contacts_empty), textAlign = TextAlign.Center)
                }
            }
        }
    } else {
        PageContentList(
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (refreshError != null) item(key = "refresh-error") {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlockedContactsRefreshError(refreshError, onRefresh)
                }
            }
            blockedContactsContent(blocked, onUnblockContact, onDeleteContact, onOpenProfile)
        }
    }
}

@Composable
private fun BlockedContactsRefreshError(message: String, onRefresh: () -> Unit) {
    Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
        modifier = Modifier.testTag("blocked-contacts-refresh-error").semantics { liveRegion = LiveRegionMode.Polite })
    OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("blocked-contacts-retry")) {
        Text(stringResource(R.string.requests_refresh))
    }
}

private fun LazyListScope.blockedContactsContent(
    blocked: List<Contact>,
    onUnblockContact: (Contact) -> Unit,
    onDeleteContact: ((Contact) -> Unit)?,
    onOpenProfile: ((Contact) -> Unit)?,
) {
    blocked.forEachIndexed { index, contact ->
        item(key = contact.id) {
            ContactRow(
                index = index,
                count = blocked.size,
                contact = contact,
                onClick = {},
                contextActionLabel = "Unblock",
                onContextAction = { onUnblockContact(contact) },
                onDeleteContact = if (contact.isForwardListContact && !contact.isGroup) {
                    onDeleteContact?.let { delete -> { delete(contact) } }
                } else null,
                onOpenProfile = if (!contact.isGroup) onOpenProfile?.let { open -> { open(contact) } } else null,
            )
        }
    }
}
