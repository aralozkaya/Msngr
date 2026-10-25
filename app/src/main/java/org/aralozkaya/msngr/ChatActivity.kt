@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.nudgeMonotonicMillis
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.notifications.otherUnreadCount
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.pages.components.KeyboardPickerSheet
import org.aralozkaya.msngr.pages.components.KeyboardSheetTile
import org.aralozkaya.msngr.pages.components.KeyboardSheetTileGrid
import org.aralozkaya.msngr.pages.components.MockServerBanner
import org.aralozkaya.msngr.pages.components.PresenceDot
import org.aralozkaya.msngr.pages.components.isKeyboardRising
import org.aralozkaya.msngr.pages.components.keyboardSheetWindowInsets
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.aralozkaya.msngr.ui.theme.chatBubble
import java.text.DateFormat
import java.util.Date

class ChatActivity : MsngrActivity() {
    internal val notificationConversationId: String? get() = intent.getStringExtra(EXTRA_CONTACT_ID)

    override fun onResume() {
        super.onResume()
        notificationConversationId?.let { (application as MsngrApplication).inAppNotifications.dismissConversation(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        if (contactId.isNullOrBlank()) {
            finish()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            setLocusContext(
                android.content.LocusId(
                    org.aralozkaya.msngr.notifications.MsngrNotificationManager.conversationShortcutId(contactId),
                ),
                null,
            )
        }
        setContent {
            MsngrTheme {
                ChatRoute(
                    repository = appContainer.messengerRepository,
                    contactId = contactId,
                    onBack = ::finish,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_CONTACT_ID = "contact_id"

        fun createIntent(context: Context, contactId: String): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_CONTACT_ID, contactId)
    }
}

@Composable
private fun ChatRoute(
    repository: MessengerRepository,
    contactId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val drafts by repository.drafts.collectAsState()
    val textStyles by repository.textStyles.collectAsState()
    val profile by repository.profile.collectAsState()
    val contact by repository.contact(contactId).collectAsState(initial = null)
    var lastKnownContact by remember(contactId) { mutableStateOf<Contact?>(null) }
    LaunchedEffect(contact) { if (contact != null) lastKnownContact = contact }
    val conversation by repository.conversation(contactId).collectAsState(initial = null)
    val lifecycleOwner = LocalLifecycleOwner.current
    val contacts by repository.contacts.collectAsState()
    val nudgeCooldowns by repository.nudgeCooldowns.collectAsState()
    val nudgeDeadline = nudgeCooldowns[contactId.lowercase(java.util.Locale.ROOT)]
    fun remainingNudgeCooldown(): Long = when (nudgeDeadline) {
        null -> 0L
        Long.MAX_VALUE -> Long.MAX_VALUE
        else -> (nudgeDeadline - nudgeMonotonicMillis()).coerceAtLeast(0L)
    }
    var nudgeCooldownMillis by remember(nudgeDeadline) { mutableStateOf(remainingNudgeCooldown()) }
    LaunchedEffect(nudgeDeadline) {
        do {
            nudgeCooldownMillis = remainingNudgeCooldown()
            if (nudgeCooldownMillis == 0L || nudgeCooldownMillis == Long.MAX_VALUE) break
            delay(200)
        } while (true)
    }

    LaunchedEffect(contactId) {
        repository.loadConversation(contactId)
    }
    LaunchedEffect(profile.email, contact?.id, contact?.displayName, contact?.avatarUri) {
        contact?.let { resolvedContact ->
            AppShortcuts.recordRecentChat(
                context = context,
                accountEmail = profile.email,
                contactId = resolvedContact.id,
                displayName = resolvedContact.displayName.messengerPlusPlainText(),
                avatarUri = resolvedContact.avatarUri,
            )
        }
    }

    var nudgeShakeCount by rememberSaveable(contactId) { mutableIntStateOf(0) }
    LaunchedEffect(contactId, lifecycleOwner) {
        observeConversationReadState(repository, contactId, lifecycleOwner.lifecycle)
    }
    LaunchedEffect(contactId, lifecycleOwner) {
        observeConversationNudgeShakes(repository, contactId, lifecycleOwner.lifecycle) {
            nudgeShakeCount++
        }
    }

    ChatScreen(
        otherUnreadCount = otherUnreadCount(contacts, contactId),
        nudgeCooldownMillis = nudgeCooldownMillis,
        nudgeShakeCount = nudgeShakeCount,
        nativeStyle = textStyles[contactId.lowercase(java.util.Locale.ROOT)] ?: NativeTextStyle(),
        onNativeStyleChange = { repository.updateTextStyle(contactId, it) },
        onSizeNoticeRequested = { repository.markTextSizeNoticeShown(contactId) },
        draft = drafts[contactId].orEmpty(),
        onDraftChange = { repository.updateDraft(contactId, it) },
        contact = contact ?: lastKnownContact?.copy(presence = Presence.Offline),
        ownPresence = profile.presence,
        isConversationAvailable = contact != null,
        messages = conversation?.messages.orEmpty(),
        isHistoryLoaded = conversation?.isHistoryLoaded == true,
        canLoadOlderMessages = conversation?.canLoadOlderMessages == true,
        isTyping = conversation?.isContactTyping == true,
        onBack = onBack,
        onSendMessage = { text, renderedEmoticonRanges ->
            repository.sendMessage(contactId, text, renderedEmoticonRanges)
        },
        onRetryMessage = { repository.retryMessage(contactId, it.id) },
        onSendTyping = { repository.sendTyping(contactId) },
        onSendNudge = { repository.sendNudge(contactId) },
        onLoadOlderMessages = { repository.loadOlderMessages(contactId) },
    )
}

@Composable
internal fun ChatScreen(
    draft: String,
    onDraftChange: (String) -> Unit,
    contact: Contact?,
    isConversationAvailable: Boolean = true,
    messages: List<ChatMessage>,
    isHistoryLoaded: Boolean,
    canLoadOlderMessages: Boolean,
    isTyping: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String, List<MsnEmoticonRange>?) -> Unit,
    onRetryMessage: (ChatMessage) -> Unit,
    onSendTyping: () -> Unit,
    onSendNudge: () -> Unit,
    onLoadOlderMessages: suspend () -> Unit,
    nativeStyle: NativeTextStyle = NativeTextStyle(),
    onNativeStyleChange: (NativeTextStyle) -> Unit = {},
    onSizeNoticeRequested: () -> Boolean = { false },
    nudgeShakeCount: Int = 0,
    otherUnreadCount: Int = 0,
    nudgeCooldownMillis: Long = 0L,
    ownPresence: Presence = Presence.Online,
) {
    val nudgeShakeState = rememberNudgeShakeState()
    var handledNudgeShakeCount by rememberSaveable(contact?.id) { mutableIntStateOf(0) }
    LaunchedEffect(nudgeShakeCount) {
        if (nudgeShakeCount > handledNudgeShakeCount) {
            handledNudgeShakeCount = nudgeShakeCount
            nudgeShakeState.trigger()
        }
    }
    val listState = rememberLazyListState()
    val topBarColor = chatTopBarContainerColor(listState)
    val scenePresentation = LocalScenePresentation.current
    val scene = LocalMessengerScene.current.takeIf { scenePresentation.showInConversations }
    val sceneInk = sceneContentColor()
    val sceneView = LocalView.current
    SideEffect {
        (sceneView.context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, sceneView)
                .isAppearanceLightStatusBars = if (scene != null) !scenePresentation.darkTint else !scenePresentation.isDark
        }
    }
    val context = LocalContext.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val messengerPlusEnabled = LocalMessengerPlusEnabled.current
    LaunchedEffect(messages.lastOrNull()?.id, isTyping) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LaunchedEffect(listState, canLoadOlderMessages, isHistoryLoaded) {
        if (!isHistoryLoaded || !canLoadOlderMessages) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val oldestVisibleIndex = layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            layoutInfo.totalItemsCount > 0 &&
                    oldestVisibleIndex >= layoutInfo.totalItemsCount - HISTORY_PAGE_LOAD_AHEAD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadOlderMessages() }
    }
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(listState, imeInsets, density) {
        var previousImeHeight = 0
        var previousViewportHeight = 0
        snapshotFlow {
            imeInsets.getBottom(density) to listState.layoutInfo.viewportSize.height
        }.collectLatest { (imeHeight, viewportHeight) ->
            val keyboardOpening = imeHeight > previousImeHeight ||
                    (imeHeight > 0 && viewportHeight < previousViewportHeight)
            previousImeHeight = imeHeight
            previousViewportHeight = viewportHeight
            if (keyboardOpening) {
                // Follow the keyboard directly; index zero is the newest message.
                listState.scrollToItem(0)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .offset { nudgeShakeState.offsetDp.toIntOffset(this) }
            .testTag(CHAT_SCREEN_SHAKE_CONTAINER_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { ContactPresenceSnackbar(contact, isConversationAvailable, ownPresence) },
        topBar = {
            Column {
                SceneBackdrop(scene) {
                    TopAppBar(
                        modifier = Modifier.clickable(
                            enabled = contact != null && isConversationAvailable,
                            onClickLabel = if (contact?.isGroup == true) "View group members" else "View contact profile",
                            role = androidx.compose.ui.semantics.Role.Button,
                        ) {
                            contact?.let {
                                if (it.isGroup) context.startActivity(GroupDetailsActivity.createIntent(context, it.id))
                                else context.startActivity(
                                    ContactProfileActivity.createIntent(
                                        context,
                                        it.id
                                    )
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = if (scene != null) Color.Transparent else topBarColor,
                            scrolledContainerColor = if (scene != null) Color.Transparent else topBarColor,
                            navigationIconContentColor = if (scene != null) sceneInk else MaterialTheme.colorScheme.onSurface,
                            titleContentColor = if (scene != null) sceneInk else MaterialTheme.colorScheme.onSurface,
                        ),
                        navigationIcon = {
                            IconButton(
                                onClick = onBack,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.semantics {
                                    if (otherUnreadCount > 0) stateDescription = "$otherUnreadCount unread messages in other chats"
                                },
                            ) {
                                BadgedBox(badge = {
                                    if (otherUnreadCount > 0) Badge(Modifier.testTag("other_chats_unread_badge")) {
                                        Text(if (otherUnreadCount > 99) "99+" else otherUnreadCount.toString())
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        title = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Avatar(
                                    name = contact?.displayName?.messengerPlusPlainText() ?: "Contact",
                                    avatarUri = contact?.avatarUri,
                                    presence = contact?.presence ?: Presence.Offline,
                                    modifier = Modifier.size(42.dp),
                                    showPresenceIndicator = false,
                                )
                                Column {
                                    MessengerPlusText(
                                        text = contact?.displayName ?: "Conversation",
                                        sceneGlow = scene != null,
                                        color = if (scene != null) sceneInk else Color.Unspecified,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PresenceDot(
                                            presence = contact?.presence ?: Presence.Offline,
                                            modifier = Modifier.size(7.dp),
                                        )
                                        SceneText(
                                            text = if (isTyping) {
                                                "typing…"
                                            } else if (contact?.isGroup == true) {
                                                "${contact.members.size} ${if (contact.members.size > 1) "members" else "member"}"
                                            } else {
                                                contact?.presence?.label ?: "Offline"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (scene != null) sceneInk else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                if (SessionPrefs.isMockServerActive()) MockServerBanner()
            }
        },
        bottomBar = {
            MessageComposer(
                nativeStyle = nativeStyle,
                onNativeStyleChange = onNativeStyleChange,
                onSizeNoticeRequested = onSizeNoticeRequested,
                enabled = isConversationAvailable && (contact?.isGroup != true || contact.presence.isOnline),
                nudgeEnabled = isConversationAvailable && contact?.presence?.isOnline != false && nudgeCooldownMillis == 0L,
                nudgeCooldownMillis = nudgeCooldownMillis,
                draft = draft,
                onDraftChange = {
                    onDraftChange(it)
                    if (it.hasMessengerPlusVisibleContent()) onSendTyping()
                },
                onSendMessage = {
                    val message = draft.toMsnOutgoingMessage(
                        convertTypedShortcuts = emoticonSettings.convertTypedShortcuts,
                        messengerPlusEnabled = messengerPlusEnabled,
                    )
                    if (message.text.isNotEmpty()) {
                        onSendMessage(message.text, message.renderedEmoticonRanges)
                        onDraftChange("")
                    }
                },
                onSendNudge = {
                    nudgeShakeState.trigger()
                    onSendNudge()
                },
                emoticonSettings = emoticonSettings,
                messengerPlusEnabled = messengerPlusEnabled,
            )
        },
    ) { innerPadding ->
        ChatMessageList(
            messages = messages,
            listState = listState,
            emoticonSettings = emoticonSettings,
            innerPadding = innerPadding,
            isGroup = contact?.isGroup == true,
            isHistoryLoaded = isHistoryLoaded,
            onRetryMessage = onRetryMessage,
            textSize = nativeStyle.size,
        )
    }
    }
}

private const val HISTORY_PAGE_LOAD_AHEAD = 20

@Composable
internal fun chatTopBarContainerColor(listState: LazyListState): Color {
    // The reversed list reaches the visual top when it cannot scroll forward.
    // Use that boundary directly so direction changes and bottom overscroll cannot
    // reset the bar's color while older messages are still above the viewport.
    val color by animateColorAsState(
        targetValue = if (listState.canScrollForward) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "Chat top bar color",
    )
    return color
}

@Composable
internal fun ChatMessageList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    emoticonSettings: EmoticonSettings,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    isHistoryLoaded: Boolean = true,
    onRetryMessage: (ChatMessage) -> Unit = {},
    textSize: Int = NativeTextStyle.DEFAULT_SIZE,
) {
    val layoutDirection = LocalLayoutDirection.current
    // Index zero is the bottom anchor, even when history arrives after composition.
    // The first measure and subsequent IME resizes therefore show the newest message
    // without a corrective scroll. Storage and bubble grouping stay chronological.
    val newestFirst = remember(messages) { messages.asReversed() }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.Top,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = innerPadding.calculateTopPadding())
            .consumeWindowInsets(innerPadding),
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 14.dp,
            top = 14.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 14.dp,
            bottom = innerPadding.calculateBottomPadding() + 14.dp,
        ),
    ) {
        if (isHistoryLoaded && messages.isEmpty()) {
            item(key = "empty-conversation") {
                Text(
                    text = stringResource(R.string.empty_conversation_warning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(newestFirst, key = { _, message -> message.id }) { index, message ->
            val joinsPrevious = newestFirst.getOrNull(index + 1).groupsWith(message)
            val joinsNext = message.groupsWith(newestFirst.getOrNull(index - 1))
            MessageBubble(
                message = message,
                textSize = textSize,
                useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
                showSenderName = isGroup,
                joinsPrevious = joinsPrevious,
                joinsNext = joinsNext,
                onRetry = { onRetryMessage(message) },
                modifier = Modifier.padding(
                    top = when {
                        index == newestFirst.lastIndex -> 0.dp
                        joinsPrevious -> 3.dp
                        else -> 12.dp
                    },
                ),
            )
        }
    }
}

@Composable
private fun MessageComposer(
    enabled: Boolean = true,
    nudgeEnabled: Boolean = enabled,
    nudgeCooldownMillis: Long = 0L,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onSendNudge: () -> Unit,
    emoticonSettings: EmoticonSettings,
    messengerPlusEnabled: Boolean,
    nativeStyle: NativeTextStyle,
    onNativeStyleChange: (NativeTextStyle) -> Unit,
    onSizeNoticeRequested: () -> Boolean,
) {
    val editorController = remember { MessengerPlusEditorController() }
    val emoticonSheet = rememberEmoticonSheetState()
    val colorSheet = rememberMessengerPlusColorSheetState()
    var showTextStyle by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, messengerPlusEnabled) {
        if (!enabled || !messengerPlusEnabled) colorSheet.dismiss()
        if (!enabled) {
            showTextStyle = false
            emoticonSheet.dismiss()
            editorController.hideKeyboard()
        }
    }
    EmoticonPanel(
        state = emoticonSheet,
        useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
        canDelete = draft.isNotEmpty(),
        onFallbackInsert = { token -> onDraftChange(draft + token) },
    )
    MessengerPlusColorSheet(colorSheet)
    if (showTextStyle) NativeTextStyleSheet(
        style = nativeStyle,
        onStyleChange = onNativeStyleChange,
        onSizeNoticeRequested = onSizeNoticeRequested,
        onDismissRequest = { showTextStyle = false },
    )
    val composerBackgroundColor = MaterialTheme.colorScheme.surface

    val bottomInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .union(keyboardSheetWindowInsets())
    val slotBackgroundColor = if (isKeyboardRising()) {
        composerBackgroundColor
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (enabled && nudgeCooldownMillis > 0L) {
            Text(
                text = if (nudgeCooldownMillis == Long.MAX_VALUE) "Sending nudge…" else
                    "You can send another nudge in ${(nudgeCooldownMillis + 999) / 1_000} seconds.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().background(composerBackgroundColor)
                    .padding(start = 16.dp, top = 6.dp, end = 16.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(composerBackgroundColor)
                .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f, true)
                    .requiredHeightIn(min = 56.dp),
            ) {
                Row(
                    modifier = Modifier
                        .pointerInput(editorController, enabled) {
                            detectTapGestures {
                                if (!enabled) return@detectTapGestures
                                editorController.requestFocusAndShowKeyboard()
                            }
                        }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { emoticonSheet.toggle(editorController) },
                        enabled = enabled,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        if (emoticonSheet.visible) {
                            Icon(Icons.Default.Keyboard, contentDescription = "Show keyboard")
                        } else {
                            Icon(Icons.Default.EmojiEmotions, contentDescription = "Show emoticons")
                        }
                    }
                    if (!draft.hasMessengerPlusVisibleContent()) {
                        IconButton(
                            onClick = onSendNudge,
                            enabled = nudgeEnabled,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = "Send nudge"
                            )
                        }
                    }
                    MsnEmoticonEditor(
                        nativeStyle = nativeStyle,
                        onTextStyle = {
                            colorSheet.dismiss()
                            emoticonSheet.dismiss()
                            editorController.hideKeyboard()
                            showTextStyle = true
                        },
                        enabled = enabled,
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .weight(1f)
                            .requiredHeightIn(min = 48.dp),
                        convertTypedShortcuts = emoticonSettings.convertTypedShortcuts,
                        useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
                        messengerPlusEnabled = messengerPlusEnabled,
                        controller = editorController,
                        onColors = if (messengerPlusEnabled) {
                            { colorSheet.open(editorController) }
                        } else {
                            null
                        },
                    )
                }
            }
            FilledIconButton(
                modifier = Modifier
                    .requiredHeight(56.dp)
                    .aspectRatio(1f, true),
                onClick = onSendMessage,
                shapes = IconButtonDefaults.shapes(
                    pressedShape = IconButtonDefaults.mediumPressedShape,
                ),
                enabled = enabled && draft.hasMessengerPlusVisibleContent(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
            }
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .background(slotBackgroundColor)
                .windowInsetsBottomHeight(bottomInsets),
        )
    }
}

internal class EmoticonSheetState {
    private var activeController by mutableStateOf<MessengerPlusEditorController?>(null)
    val visible: Boolean get() = activeController != null
    val canDelete: Boolean get() = activeController?.hasContent == true

    fun isOpenFor(controller: MessengerPlusEditorController): Boolean =
        activeController === controller

    fun open(controller: MessengerPlusEditorController) {
        activeController = controller
    }

    fun dismiss() {
        activeController = null
    }

    fun toggle(controller: MessengerPlusEditorController) {
        // The keyboard covers the sheet; the scaffold then removes it.
        if (activeController === controller) controller.requestFocusAndShowKeyboard()
        else open(controller)
    }

    fun insert(emoticon: MsnEmoticonShortcut, onFallbackInsert: (Char) -> Unit = {}) {
        val token = emoticon.menuToken
        if (activeController?.insert(token.toString()) != true) {
            onFallbackInsert(token)
        }
    }

    fun deleteBackward() {
        activeController?.deleteBackward()
    }
}

@Composable
internal fun rememberEmoticonSheetState(): EmoticonSheetState =
    remember { EmoticonSheetState() }

@Composable
internal fun EmoticonPanel(
    state: EmoticonSheetState,
    useOriginalEmoticons: Boolean,
    canDelete: Boolean = state.canDelete,
    backspaceDescription: String = "Backspace message",
    onSearchFocusChanged: (Boolean) -> Unit = {},
    onFallbackInsert: (Char) -> Unit = {},
) {
    val searchFieldState = rememberTextFieldState()
    val searchEditorController = remember { MessengerPlusEditorController() }
    var searchFocused by remember { mutableStateOf(false) }
    var leaveSearchRequested by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    val leaveSearch: () -> Unit = {
        if (searchFocused) {
            leaveSearchRequested = true
            searchEditorController.hideKeyboard()
        }
    }
    LaunchedEffect(leaveSearchRequested, keyboardVisible) {
        if (leaveSearchRequested && !keyboardVisible) {
            focusManager.clearFocus()
            searchEditorController.clearFocusAndHideKeyboard()
            leaveSearchRequested = false
        }
    }
    val query = searchFieldState.text.toString().withExpandedMsnMenuTokens().trim()
    val emoticons = remember(query) {
        MSN_EMOTICON_SHORTCUTS.filter { emoticon ->
            emoticon.name.contains(query, ignoreCase = true) ||
                emoticon.allShortcuts.any { it.contains(query, ignoreCase = true) } ||
                emoticon.unicode.contains(query)
        }
    }
    KeyboardPickerSheet(
        visible = state.visible,
        onDismissRequest = state::dismiss,
        paneTitle = stringResource(R.string.emoticon_search_hint),
        testTag = EMOTICON_PICKER_TAG,
        expandable = true,
        header = {
            BackHandler(enabled = searchFocused, onBack = leaveSearch)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactSearchField(
                    state = searchFieldState,
                    placeholder = stringResource(R.string.emoticon_search_hint),
                    clearContentDescription = stringResource(R.string.emoticon_search_clear),
                    controller = searchEditorController,
                    modifier = Modifier.weight(1f),
                    onFocusChanged = {
                        searchFocused = it
                        onSearchFocusChanged(it)
                    },
                    leadingIcon = {
                        if (searchFocused) {
                            IconButton(onClick = leaveSearch) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.emoticon_search_back),
                                )
                            }
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                    },
                )
                IconButton(
                    onClick = state::deleteBackward,
                    enabled = canDelete,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = backspaceDescription,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    ) {
        if (emoticons.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.emoticon_search_empty))
            }
        } else {
            KeyboardSheetTileGrid(items = emoticons) { emoticon ->
                TooltipBox(
                    positionProvider =
                        TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = {
                        val tooltipText = "${emoticon.name}  ${emoticon.tooltipShortcut}"
                        PlainTooltip(
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Assertive
                                paneTitle = tooltipText
                            },
                        ) {
                            Text(tooltipText)
                        }
                    },
                    state = rememberTooltipState(),
                ) {
                    KeyboardSheetTile(
                        onClick = {
                            state.insert(emoticon, onFallbackInsert)
                            leaveSearch()
                            searchFieldState.edit { replace(0, length, "") }
                        },
                    ) {
                        MsnEmoticonGlyph(
                            emoticon = emoticon,
                            useOriginalEmoticons = useOriginalEmoticons,
                        )
                    }
                }
            }
        }
    }
}

private fun ChatMessage?.groupsWith(next: ChatMessage?): Boolean =
    this != null && next != null &&
            author != MessageAuthor.System && author == next.author &&
            senderEmail == next.senderEmail &&
            !isNudge && !next.isNudge &&
            next.timestampMillis - timestampMillis in 0L..(5 * 60 * 1_000L)

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    useOriginalEmoticons: Boolean,
    showSenderName: Boolean = false,
    joinsPrevious: Boolean = false,
    joinsNext: Boolean = false,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    textSize: Int = NativeTextStyle.DEFAULT_SIZE,
) {
    var showDeliveryError by rememberSaveable(message.id) { mutableStateOf(false) }
    val deliveryError = message.deliveryError
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val onCopy = remember(context, haptic, message.text) {
        {
            copyChatMessage(context, message.text)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val alignment = when (message.author) {
        MessageAuthor.Me -> Alignment.CenterEnd
        MessageAuthor.Contact -> Alignment.CenterStart
        MessageAuthor.System -> Alignment.Center
    }
    val containerColor = when (message.author) {
        MessageAuthor.Me -> MaterialTheme.colorScheme.primaryContainer
        MessageAuthor.Contact -> MaterialTheme.colorScheme.surfaceContainerHigh
        MessageAuthor.System -> if (message.isNudge) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    }
    val supportingColor = when (message.author) {
        MessageAuthor.Me -> MaterialTheme.colorScheme.onPrimaryContainer
        MessageAuthor.Contact -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageAuthor.System -> if (message.isNudge) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        val containerWidth = maxWidth
        Surface(
            modifier = Modifier
                .widthIn(max = containerWidth * (2f / 3f), min = containerWidth * (1f / 4f)),
            shape = MaterialTheme.shapes.chatBubble(
                isOutgoing = when (message.author) {
                    MessageAuthor.Me -> true
                    MessageAuthor.Contact -> false
                    MessageAuthor.System -> null
                },
                joinsPrevious = joinsPrevious,
                joinsNext = joinsNext,
            ),
            color = containerColor,
        ) {
            Column(
                modifier = Modifier
                    .indication(interactionSource, ripple())
                    .copyMessageOnLongPress(
                        label = stringResource(R.string.chat_copy_message),
                        interactionSource = interactionSource,
                        onCopy = onCopy,
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                if (showSenderName && message.author == MessageAuthor.Contact && !joinsPrevious) {
                    message.senderName?.let { name ->
                        MessengerPlusText(
                            text = name, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                MsnEmoticonText(
                    text = message.text,
                    nativeStyle = if (message.author == MessageAuthor.System) message.nativeStyle
                        else message.nativeStyle.copy(size = textSize),
                    useOriginalEmoticons = useOriginalEmoticons,
                    renderedShortcutRanges = message.renderedEmoticonRanges,
                    linkify = true,
                    style = if (message.isNudge) {
                        MaterialTheme.typography.titleMedium
                    } else MaterialTheme.typography.bodyLarge,
                )
                Row(
                    modifier = Modifier.align(
                        if (message.author == MessageAuthor.Me) Alignment.End else Alignment.Start,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(message.timestampMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = supportingColor,
                    )
                    if (message.author == MessageAuthor.Me) {
                        if (deliveryError != null) {
                            IconButton(
                                onClick = { showDeliveryError = true },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = stringResource(R.string.chat_message_failed),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else if (message.isSending) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = stringResource(R.string.chat_message_sending),
                                tint = supportingColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (showDeliveryError && deliveryError != null) {
        AlertDialog(
            onDismissRequest = { showDeliveryError = false },
            title = { Text(stringResource(R.string.chat_message_failed)) },
            text = { Text(deliveryError) },
            confirmButton = {
                Button(onClick = {
                    showDeliveryError = false
                    onRetry()
                }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.chat_message_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeliveryError = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
