@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.PluralsRes
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.plus
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.ContactRequestInbox
import org.aralozkaya.msngr.notifications.BackgroundConnectionController
import org.aralozkaya.msngr.pages.ContactsPage
import org.aralozkaya.msngr.pages.components.ContactsActionMenu
import org.aralozkaya.msngr.pages.components.AddContactDialog
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.showActionSnackbar
import org.aralozkaya.msngr.pages.components.DeleteContactDialog
import org.aralozkaya.msngr.pages.components.GroupEditorDialog
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.pages.components.MockServerBanner
import org.aralozkaya.msngr.pages.components.SessionsDialog
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.aralozkaya.msngr.pages.UpdateBadge
import org.aralozkaya.msngr.updates.updateManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : MsngrActivity() {
    private val snackbarHostState = SnackbarHostState()
    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && SessionPrefs.canOpenAuthenticatedScreens(this)) syncRequestNotification()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return
        val repository = appContainer.messengerRepository
        AppShortcuts.activateAccount(this, repository.profile.value.email)
        lifecycleScope.launch {
            repository.contacts
                .map { contacts ->
                    AppShortcuts.recentContactPhotos(
                        this@MainActivity,
                        repository.profile.value.email,
                        contacts,
                    )
                }
                .distinctUntilChanged()
                .collect { photos ->
                    AppShortcuts.syncContactPhotos(
                        this@MainActivity,
                        repository.profile.value.email,
                        photos,
                    )
                }
        }
        setContent {
            MsngrTheme {
                MsngrApp(
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    onOpenProfile = { startActivity(ProfileActivity.createIntent(this)) },
                    onOpenSettings = { startActivity(SettingsActivity.createIntent(this)) },
                    onOpenBlockedContacts = {
                        startActivity(BlockedContactsActivity.createIntent(this))
                    },
                    onOpenRequests = { startActivity(RequestsActivity.createIntent(this)) },
                    onOpenGroup = { id, invitationsSent ->
                        startActivity(GroupDetailsActivity.createIntent(this, id, invitationsSent))
                    },
                    onOpenChat = { contactId ->
                        startActivity(ChatActivity.createIntent(this, contactId))
                    },
                    onSimulateReconnect = {
                        startActivity(ReconnectActivity.createSimulationIntent(this))
                    },
                    onLogout = {
                        BackgroundConnectionController.stop(this)
                        AppShortcuts.clearRecentChats(this)
                        repository.logout()
                        SessionPrefs.logout()
                        startActivity(
                            LoginActivity.createIntent(
                                context = this,
                                allowAutomaticSignIn = false,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            },
                        )
                        finish()
                    },
                )
            }
        }
        showGroupLeftNotice(intent)
        requestNotificationPermissionOnce()
        BackgroundConnectionController.startIfEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showGroupLeftNotice(intent)
    }

    private fun showGroupLeftNotice(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_LEFT_GROUP_NAME) ?: return
        intent.removeExtra(EXTRA_LEFT_GROUP_NAME)
        lifecycleScope.launch {
            snackbarHostState.showActionSnackbar(getString(R.string.group_leave_success, name))
        }
    }

    override fun onResume() {
        super.onResume()
        if (redirectToLoginIfNeeded()) return
        val repository = appContainer.messengerRepository
        repository.setLocalProfile(
            SessionPrefs.mergeServerProfile(this, repository.profile.value),
        )
        syncRequestNotification()
        lifecycleScope.launch {
            repository.refreshContactRequests()
            repository.refreshGroupInvitations()
            // Include offline invitations discovered by the sign-in refresh, even if
            // the notification observer or permission grant preceded that snapshot.
            syncRequestNotification()
        }
    }

    private fun syncRequestNotification() {
        val repository = appContainer.messengerRepository
        (application as MsngrApplication).notificationManager.syncContactRequests(
            ContactRequestInbox(repository.contactRequestAccountId, repository.contactRequests.value))
        (application as MsngrApplication).notificationManager.syncGroupInvitations(
            org.aralozkaya.msngr.data.GroupInvitationInbox(repository.contactRequestAccountId, repository.groupInvitations.value))
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_PREFS = "notification_permission"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "requested"
        private const val EXTRA_LEFT_GROUP_NAME = "left_group_name"

        internal fun groupLeftIntent(context: Context, groupName: String): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_LEFT_GROUP_NAME, groupName)
    }
}

@Composable
private fun MsngrApp(
    repository: MessengerRepository,
    snackbarHostState: SnackbarHostState,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBlockedContacts: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenGroup: (String, Boolean) -> Unit,
    onOpenChat: (String) -> Unit,
    onSimulateReconnect: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val updateState by context.updateManager.state.collectAsState()
    val resources = LocalResources.current
    val contacts by repository.contacts.collectAsState()
    val drafts by repository.drafts.collectAsState()
    val profile by repository.profile.collectAsState()
    val sessions by repository.sessions.collectAsState()
    val contactRequests by repository.contactRequests.collectAsState()
    val groupInvitations by repository.groupInvitations.collectAsState()
    val requestCount = contactRequests.size + groupInvitations.size
    val pendingCount = requestCount + if (updateState.hasUpdate) 1 else 0
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var addContactBounds by remember { mutableStateOf<Rect?>(null) }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    var createGroupBounds by remember { mutableStateOf<Rect?>(null) }
    var deleteTarget by remember { mutableStateOf<Contact?>(null) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val groupInteractionSource = remember { MutableInteractionSource() }
    val contactsListState = rememberLazyListState()
    var topBarHeight by remember { mutableIntStateOf(0) }
    val showProfileButton by remember {
        derivedStateOf {
            val layout = contactsListState.layoutInfo
            val profileItem = layout.visibleItemsInfo.firstOrNull { it.index == 0 }
            // Convert item offsets to screen coordinates, accounting for the
            // app bar covering the top of this edge-to-edge list.
            topBarHeight > 0 && layout.totalItemsCount > 0 &&
                (profileItem == null ||
                    profileItem.offset + profileItem.size - layout.viewportStartOffset <= topBarHeight)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column(Modifier.onSizeChanged { topBarHeight = it.height }) {
                MediumFlexibleTopAppBar(
                    title = { Text("Msngr") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AnimatedVisibility(
                            visible = showProfileButton,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            IconButton(
                                onClick = onOpenProfile,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(Icons.Default.Person, contentDescription = "Open profile")
                            }
                        }
                        IconButton(
                            onClick = { menuExpanded = true },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            BadgedBox(badge = {
                                if (pendingCount > 0) {
                                    CountBadge(pendingCount, "requests-icon-badge", R.plurals.pending_items_badge)
                                }
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Open menu")
                            }
                        }
                        DropdownMenuPopup(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(0, 2),
                                interactionSource = groupInteractionSource,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.requests_title)) },
                                    leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                                    trailingIcon = if (requestCount > 0) {
                                        { CountBadge(requestCount, "requests-menu-badge") }
                                    } else null,
                                    onClick = { menuExpanded = false; onOpenRequests() },
                                    shape = MenuDefaults.leadingItemShape,
                                )
                                DropdownMenuItem(
                                    text = { Text("Blocked contacts") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Block, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenBlockedContacts()
                                    },
                                    shape = MenuDefaults.middleItemShape,
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    trailingIcon = if (updateState.hasUpdate) {
                                        { UpdateBadge(Modifier.testTag("updates-settings-menu-badge")) }
                                    } else null,
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenSettings()
                                    },
                                    shape = MenuDefaults.middleItemShape,
                                )
                                if (SessionPrefs.isMockServerActive()) {
                                    DropdownMenuItem(
                                        text = { Text("Simulate reconnection") },
                                        shape = MenuDefaults.middleItemShape,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onSimulateReconnect()
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(1, 2),
                                interactionSource = groupInteractionSource,
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (sessions.size > 1) "Log out…" else "Log out",
                                            color = MaterialTheme.colorScheme.error)
                                    },
                                    supportingText = if (sessions.size > 1) {
                                        { Text("${sessions.size} active sessions") }
                                    } else null,
                                    shape = MenuDefaults.trailingItemShape,
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Logout,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        if (sessions.size > 1) showSessions = true
                                        else showLogoutConfirmation = true
                                    },
                                )
                            }
                        }
                    },
                )
                if (SessionPrefs.isMockServerActive()) MockServerBanner()
            }
        },
        floatingActionButton = {
            ContactsActionMenu(
                onAddContact = { showAddContact = true },
                contactInvitationVisible = showAddContact,
                onAddContactBoundsChanged = { addContactBounds = it },
                onCreateGroup = { showCreateGroup = true },
                groupCreationVisible = showCreateGroup,
                onCreateGroupBoundsChanged = { createGroupBounds = it },
            )
        },
        snackbarHost = { ActionSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ContactsPage(
            drafts = drafts,
            listState = contactsListState,
            // Let the final contact scroll clear of the floating action button.
            innerPadding = innerPadding.plus(PaddingValues(bottom = 56.dp)),
            scrollBehavior = scrollBehavior,
            contacts = contacts,
            profile = profile,
            onPresenceSelected = { presence ->
                repository.setPresence(presence)
                SessionPrefs.saveUserProfile(
                    context = context,
                    username = profile.username,
                    presence = presence,
                    avatarUri = profile.avatarUri,
                    statusMessage = profile.statusMessage,
                )
            },
            onEditProfile = onOpenProfile,
            onOpenChat = { onOpenChat(it.id) },
            onBlockContact = { repository.blockContact(it.id) },
            onDeleteContact = { deleteTarget = it },
            onReadStateChange = { contact, unread ->
                if (unread) repository.markUnread(contact.id) else repository.markRead(contact.id)
            },
        )
    }

    if (showCreateGroup) {
        GroupEditorDialog(profile.email, contacts, onCreate = repository::createGroup,
            sourceBounds = createGroupBounds,
            onInvite = repository::inviteGroupMembers,
            onFinished = { id, invitationsSent -> showCreateGroup = false; onOpenGroup(id, invitationsSent) },
            onDismiss = { showCreateGroup = false })
    }

    if (showAddContact) {
        AddContactDialog(
            sourceBounds = addContactBounds,
            ownEmail = profile.email,
            contacts = contacts,
            isMock = SessionPrefs.isMockServerActive(),
            onAddContact = repository::addContact,
            onAdded = {
                showAddContact = false
                scope.launch {
                    snackbarHostState.showActionSnackbar(resources.getString(
                        if (SessionPrefs.isMockServerActive()) R.string.contact_add_mock_success
                        else R.string.contact_add_success,
                    ))
                }
            },
            onDismiss = { showAddContact = false },
        )
    }

    deleteTarget?.let { target ->
        DeleteContactDialog(
            contact = target,
            isMock = SessionPrefs.isMockServerActive(),
            onDeleteContact = { block -> repository.deleteContact(target.id, block) },
            onDeleted = {
                deleteTarget = null
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(
                        if (SessionPrefs.isMockServerActive()) R.string.contact_delete_mock_success
                        else R.string.contact_delete_success,
                    ))
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (showSessions) {
        SessionsDialog(
            sessions = sessions,
            onLogOutSessions = repository::logOutSessions,
            onCurrentSessionLoggedOut = onLogout,
            onDismiss = { showSessions = false },
        )
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text("Log out?") },
            text = {
                Text("This ends the current session. Remembered credentials stay saved on this device.")
            },
            confirmButton = {
                Button(
                    onClick = onLogout,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutConfirmation = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun CountBadge(count: Int, tag: String, @PluralsRes descriptionRes: Int = R.plurals.requests_badge) {
    val description = pluralStringResource(descriptionRes, count, count)
    Badge(Modifier.testTag(tag).semantics { contentDescription = description }) {
        Text(if (count > 99) "99+" else count.toString())
    }
}
