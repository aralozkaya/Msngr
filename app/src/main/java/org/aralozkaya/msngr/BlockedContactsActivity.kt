package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.pages.BlockedContactsPage
import org.aralozkaya.msngr.pages.components.DeleteContactDialog
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class BlockedContactsActivity : MsngrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return

        setContent {
            MsngrTheme {
                BlockedContactsRoute(
                    repository = appContainer.messengerRepository,
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        redirectToLoginIfNeeded()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, BlockedContactsActivity::class.java)
    }
}

@Composable
private fun BlockedContactsRoute(
    repository: MessengerRepository,
    onBack: () -> Unit,
) {
    val contacts by repository.contacts.collectAsState()
    var deleteTarget by remember { mutableStateOf<Contact?>(null) }
    val context = LocalContext.current
    SecondaryPageScaffold(
        title = "Blocked contacts",
        onBack = onBack,
    ) { innerPadding, scrollBehavior ->
        BlockedContactsPage(
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            contacts = contacts,
            onUnblockContact = { repository.unblockContact(it.id) },
            onDeleteContact = { deleteTarget = it },
            onOpenProfile = { context.startActivity(ContactProfileActivity.createIntent(context, it.id)) },
        )
    }
    deleteTarget?.let { target ->
        DeleteContactDialog(
            contact = target,
            isMock = SessionPrefs.isMockServerActive(),
            onDeleteContact = { block -> repository.deleteContact(target.id, block) },
            onDeleted = { deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}
