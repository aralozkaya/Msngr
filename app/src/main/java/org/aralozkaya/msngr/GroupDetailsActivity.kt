package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.isFavorite
import org.aralozkaya.msngr.data.resolveContact
import org.aralozkaya.msngr.pages.GroupDetailsPage
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.AddContactDialog
import org.aralozkaya.msngr.pages.components.ContactCategoriesDialog
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
            val categories by repository.categories.collectAsState()
            val profile by repository.profile.collectAsState()
            val group = contacts.firstOrNull { it.id == groupId && it.isGroup }
            val members = group?.members.orEmpty().mapNotNull { contacts.resolveContact(it.email, profile) }
            var showInvite by rememberSaveable { mutableStateOf(false) }
            var showLeave by rememberSaveable { mutableStateOf(false) }
            var showManageCategories by rememberSaveable { mutableStateOf(false) }
            var blockingTarget by rememberSaveable { mutableStateOf<Boolean?>(null) }
            var addContactEmail by rememberSaveable { mutableStateOf<String?>(null) }
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
                    title = androidx.compose.ui.res.stringResource(R.string.group_details_title),
                    onBack = ::finish,
                    actions = {
                        if (group != null && !group.isBlocked) {
                            val isFav = group.isFavorite
                            IconButton(
                                onClick = {
                                    val nextFav = !isFav
                                    scope.launch {
                                        val result = repository.setContactFavorite(group.id, nextFav)
                                        if (result.isSuccess) {
                                            snackbarHostState.showActionSnackbar(
                                                getString(
                                                    if (nextFav) R.string.contact_favorites_added
                                                    else R.string.contact_favorites_removed,
                                                ),
                                            )
                                        } else {
                                            snackbarHostState.showActionSnackbar(
                                                result.exceptionOrNull()?.message
                                                    ?: getString(R.string.contact_categories_update_failed),
                                                isError = true,
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("group_favorite_button"),
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = stringResource(
                                        if (isFav) R.string.contact_remove_from_favorites
                                        else R.string.contact_add_to_favorites,
                                    ),
                                    tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        GroupDetailsMenu(
                            group = group,
                            ownEmail = profile.email,
                            onInviteMembers = { showInvite = true },
                            onLeaveGroup = { showLeave = true },
                            onManageCategories = if (group != null && !group.isBlocked) {
                                { showManageCategories = true }
                            } else null,
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
                        onAddContact = { addContactEmail = it.email },
                    )
                }
                addContactEmail?.let { email ->
                    AddContactDialog(
                        ownEmail = profile.email,
                        contacts = contacts,
                        initialEmail = email,
                        isMock = SessionPrefs.isMockServerActive(),
                        onAddContact = repository::addContact,
                        onAdded = {
                            addContactEmail = null
                            scope.launch {
                                snackbarHostState.showActionSnackbar(getString(
                                    if (SessionPrefs.isMockServerActive()) R.string.contact_add_mock_success
                                    else R.string.contact_add_success,
                                ))
                            }
                        },
                        onDismiss = { addContactEmail = null },
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
                if (showManageCategories && group != null) {
                    ContactCategoriesDialog(
                        contact = group,
                        categories = categories,
                        onSave = { next -> repository.setContactCategories(group.id, next) },
                        onSaved = {
                            showManageCategories = false
                            scope.launch {
                                snackbarHostState.showActionSnackbar(getString(R.string.contact_categories_updated))
                            }
                        },
                        onDismiss = { showManageCategories = false },
                    )
                }
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
