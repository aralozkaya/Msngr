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
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.pages.RequestsPage
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class RequestsActivity : MsngrActivity() {
    private var refreshing by mutableStateOf(false)
    private var refreshError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return
        setContent {
            MsngrTheme {
                RequestsRoute(appContainer.messengerRepository, ::finish,
                    refreshing, refreshError, ::refreshRequests,
                    onJoined = { startActivity(GroupDetailsActivity.createIntent(this, it)) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!redirectToLoginIfNeeded()) refreshRequests()
    }

    private fun refreshRequests() {
        if (refreshing) return
        refreshing = true
        refreshError = null
        lifecycleScope.launch {
            try {
                val repository = appContainer.messengerRepository
                // Try both inboxes even if one service returns a failure.
                val contacts = repository.refreshContactRequests()
                val groups = repository.refreshGroupInvitations()
                refreshError = listOfNotNull(contacts.exceptionOrNull(), groups.exceptionOrNull())
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { it.message ?: getString(R.string.requests_refresh_failed) }
            } finally { refreshing = false }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, RequestsActivity::class.java)
    }
}

@Composable
internal fun RequestsRoute(repository: MessengerRepository, onBack: () -> Unit,
                          refreshing: Boolean, refreshError: String?, onRefresh: () -> Unit,
                          onJoined: (String) -> Unit) {
    val requests by repository.contactRequests.collectAsState()
    val invitations by repository.groupInvitations.collectAsState()
    val context = LocalContext.current
    val invitationSettings by remember(context) { ContactInvitationPrefs.state(context) }.collectAsState()
    SecondaryPageScaffold(
        stringResource(R.string.requests_title), onBack,
        actions = {
            IconButton(onClick = onRefresh, enabled = !refreshing,
                modifier = Modifier.testTag("requests-refresh")) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.requests_refresh))
            }
        },
    ) { padding, scrollBehavior ->
        RequestsPage(padding, scrollBehavior, requests, repository::respondToContactRequest,
            refreshing, refreshError, onRefresh, SessionPrefs.isMockServerActive(),
            invitations, repository::respondToGroupInvitation, onJoined,
            invitationSettings = invitationSettings,
            onSaveDeclineDefaults = { ContactInvitationPrefs.saveDeclineDefaults(context, it) })
    }
}
