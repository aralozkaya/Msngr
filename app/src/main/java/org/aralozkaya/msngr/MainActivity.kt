@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import kotlin.math.abs
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactRequestInbox
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.notifications.BackgroundConnectionController
import org.aralozkaya.msngr.pages.ContactsPage
import org.aralozkaya.msngr.pages.DisplayPictureSyncHost
import org.aralozkaya.msngr.pages.PortalPage
import org.aralozkaya.msngr.pages.UpdateBadge
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.AddContactDialog
import org.aralozkaya.msngr.pages.components.ContactsActionMenu
import org.aralozkaya.msngr.pages.components.DeleteContactDialog
import org.aralozkaya.msngr.pages.components.GroupEditorDialog
import org.aralozkaya.msngr.pages.components.GroupBlockingDialog
import org.aralozkaya.msngr.pages.components.MockServerBanner
import org.aralozkaya.msngr.pages.components.PortalSitesActionMenu
import org.aralozkaya.msngr.pages.components.SessionsDialog
import org.aralozkaya.msngr.pages.components.showActionSnackbar
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.aralozkaya.msngr.updates.updateManager

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
                DisplayPictureSyncHost(repository)
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
                    onOpenContactProfile = { contactId ->
                        startActivity(ContactProfileActivity.createIntent(this, contactId))
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
        if (savedInstanceState == null) displayPictureSync.requestRefresh(repository)
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
            snackbarHostState.showActionSnackbar(getString(R.string.group_leave_success_you_singular, name))
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
internal fun MsngrApp(
    repository: MessengerRepository,
    snackbarHostState: SnackbarHostState,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBlockedContacts: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenGroup: (String, Boolean) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenContactProfile: (String) -> Unit,
    onSimulateReconnect: () -> Unit,
    onLogout: () -> Unit,
    pagerState: PagerState = rememberPagerState(initialPage = 1) { 2 },
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
    contactsListState: LazyListState = rememberLazyListState(),
    portalListState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val updateState by context.updateManager.state.collectAsState()
    val appearance by remember(context) { AppearancePrefs.state(context) }.collectAsState()
    val resources = LocalResources.current
    val contacts by repository.contacts.collectAsState()
    val drafts by repository.drafts.collectAsState()
    val profile by repository.profile.collectAsState()
    val portalState by repository.portalState.collectAsState()
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
    var blockGroupTarget by remember { mutableStateOf<Contact?>(null) }
    var activeWebTarget by remember { mutableStateOf<PortalWebTarget?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showPagerIndicator by remember { mutableStateOf(true) }
    val groupInteractionSource = remember { MutableInteractionSource() }
    var topBarHeight by remember { mutableIntStateOf(0) }
    val showProfileButton by remember {
        derivedStateOf {
            if (pagerState.currentPage != 1) return@derivedStateOf false
            val layout = contactsListState.layoutInfo
            val profileItem = layout.visibleItemsInfo.firstOrNull { it.index == 0 }
            // Convert item offsets to screen coordinates, accounting for the
            // app bar covering the top of this edge-to-edge list.
            topBarHeight > 0 && layout.totalItemsCount > 0 &&
                (profileItem == null ||
                    profileItem.offset + profileItem.size - layout.viewportStartOffset <= topBarHeight)
        }
    }

    LaunchedEffect(appearance.showAds, portalState.config) {
        if (
            appearance.showAds &&
            portalState.bannerAd == null &&
            portalState.textAd == null &&
            (portalState.config.bannerAdUrl != null || portalState.config.textAdUrl != null)
        ) {
            repository.refreshAds()
        }
    }

    LaunchedEffect(pagerState.isScrollInProgress, pagerState.currentPage) {
        showPagerIndicator = true
        if (!pagerState.isScrollInProgress) {
            delay(1500L)
            showPagerIndicator = false
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val activeList = if (pagerState.currentPage == 0) portalListState else contactsListState
        if (activeList.firstVisibleItemIndex == 0 && activeList.firstVisibleItemScrollOffset == 0) {
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val activeList = if (pagerState.settledPage == 0) portalListState else contactsListState
        val inactiveList = if (pagerState.settledPage == 0) contactsListState else portalListState
        if (inactiveList.firstVisibleItemIndex != 0 || inactiveList.firstVisibleItemScrollOffset != 0) {
            inactiveList.scrollToItem(0)
        }
        if (activeList.firstVisibleItemIndex == 0 && activeList.firstVisibleItemScrollOffset == 0) {
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
        }
    }

    BackHandler(enabled = pagerState.settledPage == 0 && activeWebTarget == null) {
        scope.launch { pagerState.animateScrollToPage(1) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column(Modifier.onSizeChanged { topBarHeight = it.height }) {
                MediumFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
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
                            ) {
                                Icon(Icons.Default.Person, contentDescription = stringResource(R.string.main_open_profile))
                            }
                        }
                        IconButton(
                            onClick = { menuExpanded = true },
                        ) {
                            BadgedBox(badge = {
                                if (pendingCount > 0) {
                                    CountBadge(
                                        count = pendingCount,
                                        tag = "requests-icon-badge",
                                        oneBeforeRes = R.string.pending_items_badge_singular_before_number,
                                        oneAfterRes = R.string.pending_items_badge_singular_after_number,
                                        otherBeforeRes = R.string.pending_items_badge_plural_before_number,
                                        otherAfterRes = R.string.pending_items_badge_plural_after_number,
                                    )
                                }
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.main_open_menu))
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
                                    trailingContent = if (requestCount > 0) {
                                        { CountBadge(requestCount, "requests-menu-badge") }
                                    } else null,
                                    onClick = { menuExpanded = false; onOpenRequests() },
                                    shape = MenuDefaults.leadingItemShape,
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.blocked_contacts_title)) },
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
                                    text = { Text(stringResource(R.string.settings_destination_root)) },
                                    trailingContent = if (updateState.hasUpdate) {
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
                                        text = { Text(stringResource(R.string.main_simulate_reconnection)) },
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
                                        Text(stringResource(if (sessions.size > 1) R.string.main_log_out_ellipsis else R.string.main_log_out),
                                            color = MaterialTheme.colorScheme.error)
                                    },
                                    supportingText = if (sessions.size > 1) {
                                        {
                                            Text(
                                                quantityNumberSentenceResource(
                                                    count = sessions.size,
                                                    oneBeforeRes = R.string.main_active_sessions_singular_before_number,
                                                    oneAfterRes = R.string.main_active_sessions_singular_after_number,
                                                    otherBeforeRes = R.string.main_active_sessions_plural_before_number,
                                                    otherAfterRes = R.string.main_active_sessions_plural_after_number,
                                                ),
                                            )
                                        }
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
            AnimatedContent(
                targetState = pagerState.currentPage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                // Counteract FloatingActionButtonMenu's outer bottom margin so the FAB sits at standard 16.dp spacing.
                modifier = Modifier.offset(y = 16.dp),
                label = "Main FAB",
            ) { page ->
                if (page == 0) {
                    PortalSitesActionMenu(
                        config = portalState.config,
                        onOpenWebTarget = { activeWebTarget = it },
                    )
                } else {
                    ContactsActionMenu(
                        onAddContact = { showAddContact = true },
                        contactInvitationVisible = showAddContact,
                        onAddContactBoundsChanged = { addContactBounds = it },
                        onCreateGroup = { showCreateGroup = true },
                        groupCreationVisible = showCreateGroup,
                        onCreateGroupBoundsChanged = { createGroupBounds = it },
                    )
                }
            }
        },
        snackbarHost = { ActionSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("main_pager"),
            ) { page ->
                val isCurrentPage = pagerState.settledPage == page
                var pageHasFocus by remember { mutableStateOf(false) }
                val pageModifier = Modifier
                    .onFocusChanged { pageHasFocus = it.hasFocus }
                    .focusGroup()
                LaunchedEffect(isCurrentPage) {
                    if (!isCurrentPage && pageHasFocus) {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                }
                if (page == 0) {
                    PortalPage(
                        modifier = pageModifier,
                        listState = portalListState,
                        // Let the final feed item scroll clear of the floating action button.
                        innerPadding = innerPadding.plus(PaddingValues(bottom = 56.dp)),
                        scrollBehavior = scrollBehavior,
                        portalState = portalState,
                        contacts = contacts,
                        showAds = appearance.showAds,
                        isMockServer = SessionPrefs.isMockServerActive(),
                        onRefresh = {
                            scope.launch {
                                repository.refreshPortal(includeAds = appearance.showAds)
                            }
                        },
                        onOpenWebTarget = { activeWebTarget = it },
                        onOpenContactProfile = onOpenContactProfile,
                    )
                } else {
                    ContactsPage(
                        modifier = pageModifier,
                        isCurrentPage = isCurrentPage,
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
                        onBlockContact = { if (it.isGroup) blockGroupTarget = it else repository.blockContact(it.id) },
                        onDeleteContact = { deleteTarget = it },
                        onReadStateChange = { contact, unread ->
                            if (unread) repository.markUnread(contact.id) else repository.markRead(contact.id)
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = showPagerIndicator,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp),
            ) {
                HomePagerIndicator(
                    pagerState = pagerState,
                    onSelectPage = { targetPage ->
                        scope.launch { pagerState.animateScrollToPage(targetPage) }
                    },
                )
            }
        }
    }

    activeWebTarget?.let { target ->
        PortalWebViewDialog(
            target = target,
            onDismiss = { activeWebTarget = null },
        )
    }

    blockGroupTarget?.let { group ->
        GroupBlockingDialog(group, blocked = true,
            onSetBlocked = { repository.setGroupBlocked(group.id, true) },
            onChanged = { blockGroupTarget = null }, onDismiss = { blockGroupTarget = null })
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
            title = { Text(stringResource(R.string.main_log_out_dialog_title)) },
            text = {
                Text(stringResource(R.string.main_log_out_dialog_message))
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
                    Text(stringResource(R.string.main_log_out))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutConfirmation = false },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun HomePagerIndicator(
    pagerState: PagerState,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeLabel = stringResource(R.string.portal_pager_home_label)
    val contactsLabel = stringResource(R.string.portal_pager_contacts_label_plural)
    val currentPosition = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, (pagerState.pageCount - 1).coerceAtLeast(0).toFloat())

    Surface(
        modifier = modifier.testTag("main_pager_indicator"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (page in 0 until pagerState.pageCount) {
                val activeFraction = 1f - abs(currentPosition - page).coerceIn(0f, 1f)
                val dotWidth = lerpDp(6.dp, 18.dp, activeFraction)
                val dotColor = lerpColor(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    MaterialTheme.colorScheme.primary,
                    activeFraction,
                )
                val pageLabel = if (page == 0) homeLabel else contactsLabel
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectPage(page) }
                        .padding(horizontal = 3.dp, vertical = 5.dp)
                        .testTag("main_pager_dot_$page")
                        .semantics {
                            selected = pagerState.currentPage == page
                            contentDescription = pageLabel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = dotWidth, height = 6.dp)
                            .background(dotColor, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountBadge(
    count: Int,
    tag: String,
    @StringRes oneBeforeRes: Int = R.string.requests_badge_singular_before_number,
    @StringRes oneAfterRes: Int = R.string.requests_badge_singular_after_number,
    @StringRes otherBeforeRes: Int = R.string.requests_badge_plural_before_number,
    @StringRes otherAfterRes: Int = R.string.requests_badge_plural_after_number,
) {
    val description = quantityNumberSentenceResource(
        count = count,
        oneBeforeRes = oneBeforeRes,
        oneAfterRes = oneAfterRes,
        otherBeforeRes = otherBeforeRes,
        otherAfterRes = otherAfterRes,
    )
    val badgeText = if (count > 99) {
        numberSentenceResource(
            R.string.main_count_badge_cap_before_number,
            formatLocalizedInt(99),
            R.string.main_count_badge_cap_after_number,
        )
    } else {
        formatLocalizedInt(count)
    }
    Badge(Modifier.testTag(tag).semantics { contentDescription = description }) {
        Text(badgeText)
    }
}
