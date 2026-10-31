package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.pages.BlockedContactsPage
import org.aralozkaya.msngr.pages.components.DeleteContactDialog
import org.aralozkaya.msngr.pages.components.GroupBlockingDialog
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class BlockedContactsActivity : MsngrActivity() {
    private var refreshing by mutableStateOf(false)
    private var refreshError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return

        setContent {
            MsngrTheme {
                BlockedContactsRoute(
                    repository = appContainer.messengerRepository,
                    onBack = ::finish,
                    refreshing = refreshing,
                    refreshError = refreshError,
                    onRefresh = ::refreshBlockedContacts,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!redirectToLoginIfNeeded()) refreshBlockedContacts()
    }

    private fun refreshBlockedContacts() {
        if (refreshing) return
        refreshing = true
        refreshError = null
        lifecycleScope.launch {
            try {
                val result = appContainer.messengerRepository.refreshBlockedContacts()
                if (result.isFailure) refreshError = getString(R.string.blocked_contacts_refresh_failed)
            } finally { refreshing = false }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, BlockedContactsActivity::class.java)
    }
}

@Composable
internal fun BlockedContactsRoute(
    repository: MessengerRepository,
    onBack: () -> Unit,
    refreshing: Boolean = false,
    refreshError: String? = null,
    onRefresh: () -> Unit = {},
) {
    val contacts by repository.contacts.collectAsState()
    var deleteTarget by remember { mutableStateOf<Contact?>(null) }
    var unblockGroupTarget by remember { mutableStateOf<Contact?>(null) }
    val context = LocalContext.current
    SecondaryPageScaffold(
        title = stringResource(R.string.blocked_contacts_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh, enabled = !refreshing,
                modifier = Modifier.testTag("blocked-contacts-refresh")) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.requests_refresh))
            }
        },
    ) { innerPadding, scrollBehavior ->
        BlockedContactsPage(
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            contacts = contacts,
            onUnblockContact = { if (it.isGroup) unblockGroupTarget = it else repository.unblockContact(it.id) },
            onDeleteContact = { deleteTarget = it },
            onOpenProfile = { context.startActivity(ContactProfileActivity.createIntent(context, it.id)) },
            refreshing = refreshing,
            refreshError = refreshError,
            onRefresh = onRefresh,
        )
    }
    unblockGroupTarget?.let { group ->
        GroupBlockingDialog(group, blocked = false,
            onSetBlocked = { repository.setGroupBlocked(group.id, false) },
            onChanged = { unblockGroupTarget = null }, onDismiss = { unblockGroupTarget = null })
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
