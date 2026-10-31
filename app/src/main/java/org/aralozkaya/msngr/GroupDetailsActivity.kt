package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.resolveContact
import org.aralozkaya.msngr.pages.GroupDetailsPage
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.GroupDetailsMenu
import org.aralozkaya.msngr.pages.components.GroupBlockingDialog
import org.aralozkaya.msngr.pages.components.GroupEditorDialog
import org.aralozkaya.msngr.pages.components.LeaveGroupDialog
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.pages.components.showActionSnackbar
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class GroupDetailsActivity : MsngrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        if (groupId.isNullOrBlank()) {
            finish()
            return
        }
        val repository = appContainer.messengerRepository
        setContent {
            val contacts by repository.contacts.collectAsState()
            val profile by repository.profile.collectAsState()
            val group = contacts.firstOrNull { it.id == groupId && it.isGroup }
            val members = group?.members.orEmpty().mapNotNull { contacts.resolveContact(it.email, profile) }
            var showInvite by rememberSaveable { mutableStateOf(false) }
            var showLeave by rememberSaveable { mutableStateOf(false) }
            var blockingTarget by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                if (intent.getBooleanExtra(EXTRA_INVITATIONS_SENT, false)) {
                    intent.removeExtra(EXTRA_INVITATIONS_SENT)
                    snackbarHostState.showActionSnackbar(getString(R.string.group_invitations_sent))
                }
            }
            MsngrTheme {
                SecondaryPageScaffold(
                    title = "Group details",
                    onBack = ::finish,
                    actions = {
                        GroupDetailsMenu(
                            group = group,
                            ownEmail = profile.email,
                            onInviteMembers = { showInvite = true },
                            onLeaveGroup = { showLeave = true },
                            onChangeBlocking = { group?.let { blockingTarget = !it.isBlocked } },
                            onOpenNotifications = {
                                group?.let { target ->
                                    startActivity(ConversationNotificationSettingsActivity.createIntent(
                                        this@GroupDetailsActivity, target.id, target.displayName.messengerPlusPlainText(), true))
                                }
                            },
                        )
                    },
                    snackbarHost = { ActionSnackbarHost(snackbarHostState) },
                ) { padding, scroll ->
                    GroupDetailsPage(
                        group = group,
                        members = members,
                        innerPadding = padding,
                        scrollBehavior = scroll,
                        onOpenChat = {
                            if (!it.email.equals(profile.email, ignoreCase = true)) {
                                startActivity(ChatActivity.createIntent(this, it.id))
                            }
                        },
                        onOpenProfile = { startActivity(ContactProfileActivity.createIntent(this, it.id)) },
                        ownEmail = profile.email,
                        onEditProfile = { startActivity(ProfileActivity.createIntent(this)) },
                        onRefreshPicture = { repository.refreshContactPicture(it.id) },
                    )
                }
                blockingTarget?.let { blocked ->
                    if (group != null) GroupBlockingDialog(group, blocked,
                        onSetBlocked = { repository.setGroupBlocked(group.id, blocked) },
                        onChanged = { blockingTarget = null }, onDismiss = { blockingTarget = null })
                }
                if (showInvite && group != null) GroupEditorDialog(profile.email, contacts, group,
                    onCreate = repository::createGroup, onInvite = repository::inviteGroupMembers,
                    onFinished = { _, invitationsSent ->
                        showInvite = false
                        if (invitationsSent) scope.launch {
                            snackbarHostState.showActionSnackbar(getString(R.string.group_invitations_sent))
                        }
                    }, onDismiss = { showInvite = false })
                if (showLeave && group != null) LeaveGroupDialog(
                    groupName = group.displayName.messengerPlusPlainText(),
                    onLeave = { repository.leaveGroup(groupId) },
                    onLeft = {
                        startActivity(MainActivity.groupLeftIntent(this, group.displayName.messengerPlusPlainText()))
                        finish()
                    },
                    onError = { message ->
                        showLeave = false
                        scope.launch { snackbarHostState.showActionSnackbar(message, isError = true) }
                    },
                    onDismiss = { showLeave = false },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        redirectToLoginIfNeeded()
    }

    companion object {
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_INVITATIONS_SENT = "invitations_sent"

        fun createIntent(context: Context, groupId: String, invitationsSent: Boolean = false): Intent =
            Intent(context, GroupDetailsActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, groupId)
                .putExtra(EXTRA_INVITATIONS_SENT, invitationsSent)
    }
}
