package org.aralozkaya.msngr.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.PageContentList

@Composable
fun BlockedContactsPage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    contacts: List<Contact>,
    onUnblockContact: (Contact) -> Unit,
    onDeleteContact: ((Contact) -> Unit)? = null,
    onOpenProfile: ((Contact) -> Unit)? = null,
) {
    val blocked = contacts
        .filter(Contact::isBlocked)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {
            it.displayName.messengerPlusPlainText()
        })

    if (blocked.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No blocked contacts", textAlign = TextAlign.Center)
        }
    } else {
        PageContentList(
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            blockedContactsContent(blocked, onUnblockContact, onDeleteContact, onOpenProfile)
        }
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
