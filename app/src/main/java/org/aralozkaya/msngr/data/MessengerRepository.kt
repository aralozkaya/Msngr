package org.aralozkaya.msngr.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.AccountIdentity
import org.aralozkaya.msngr.MessengerPlusTextCodec
import org.aralozkaya.msngr.MsnEmoticonRange
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.ServerConfig
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.InkDrawing
import java.util.Locale

internal class CredentialsRejectedException(message: String) : IllegalStateException(message)
internal class SessionUnavailableException(message: String) : IllegalStateException(message)

data class SignInRequest(
    val email: String,
    val password: String,
    val serverConfig: ServerConfig,
    val initialPresence: Presence = Presence.Online,
)

data class Contact(
    val id: String,
    val nickname: String?,
    val email: String,
    val presence: Presence,
    val statusMessage: String = "",
    val currentMedia: CurrentMedia? = null,
    val avatarUri: String? = null,
    val unreadCount: Int = 0,
    val isBlocked: Boolean = false,
    val isForwardListContact: Boolean = true,
    val isMarkedUnread: Boolean = false,
    val isGroup: Boolean = false,
    val members: List<GroupMember> = emptyList(),
    val clientCapabilities: Long = 0L,
    val extendedCapabilities: Long = 0L,
    val networkId: Int = MSNP_WINDOWS_LIVE_NETWORK_ID,
    val categoryIds: Set<String> = emptySet(),
) {
    /** Accepted members only; pending invitations do not participate in the conversation. */
    val joinedMembers: List<GroupMember>
        get() = members.filterNot(GroupMember::isPending)

    val isUnread: Boolean
        get() = isMarkedUnread || unreadCount > 0

    val displayName: String
        get() = nickname?.takeIf { it.messengerPlusPlainText().isNotBlank() } ?: email
}

enum class MessageAuthor { Me, Contact, System }

data class GroupMember(
    val email: String,
    val displayName: String = email,
    val presence: Presence = Presence.Offline,
    val avatarUri: String? = null,
    val storageId: String? = null,
    val role: GroupMemberRole = GroupMemberRole.Unknown,
    val clientCapabilities: Long = 0L,
    val extendedCapabilities: Long = 0L,
    val networkId: Int = MSNP_WINDOWS_LIVE_NETWORK_ID,
    /** The group invitation has been sent and is still awaiting a response. */
    val isPending: Boolean = false,
)

internal fun Contact.withGroupPresence(ownEmail: String): Contact = if (!isGroup) this else copy(
    presence = if (!isBlocked && joinedMembers.any { !it.email.equals(ownEmail, true) && it.presence.isOnline })
        Presence.Online else Presence.Offline,
)

/** Resolve group-only members without adding them to the user's contact list. */
internal fun List<Contact>.resolveContact(contactId: String, profile: UserProfileSnapshot? = null): Contact? {
    firstOrNull { it.id.equals(contactId, ignoreCase = true) }?.let { return it }
    if (profile != null && profile.email.equals(contactId, ignoreCase = true)) {
        return Contact(
            profile.email.lowercase(),
            profile.username,
            profile.email,
            profile.presence,
            statusMessage = profile.statusMessage,
            currentMedia = profile.currentMedia,
            avatarUri = profile.avatarUri,
            isForwardListContact = false,
            clientCapabilities = if (profile.presence.isOnline) org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID else 0L,
            extendedCapabilities = if (profile.presence.isOnline) org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong() else 0L,
        )
    }
    val members = asSequence().filter(Contact::isGroup).flatMap { it.members.asSequence() }
        .filter { it.email.equals(contactId, ignoreCase = true) }.toList()
    val member = members.minByOrNull { it.presence.priority } ?: return null
    return Contact(
        member.email.lowercase(),
        member.displayName,
        member.email,
        member.presence,
        avatarUri = members.firstNotNullOfOrNull { it.avatarUri },
        isForwardListContact = false,
        clientCapabilities = member.clientCapabilities,
        extendedCapabilities = member.extendedCapabilities,
        networkId = member.networkId,
    )
}

data class ChatMessage(
    val id: Long,
    val contactId: String,
    val author: MessageAuthor,
    val text: String,
    val timestampMillis: Long,
    val isNudge: Boolean = false,
    /** Null renders every shortcut; a non-null list renders only the listed local ranges. */
    val renderedEmoticonRanges: List<MsnEmoticonRange>? = null,
    val isSending: Boolean = false,
    val deliveryError: String? = null,
    val senderEmail: String? = null,
    val senderName: String? = null,
    val nativeStyle: NativeTextStyle = NativeTextStyle(),
    val inkDrawing: InkDrawing? = null,
    val voiceClip: org.aralozkaya.msngr.protocol.VoiceClip? = null,
    val isVoiceClipPending: Boolean = false,
    val voiceClipTransferError: String? = null,
    val wink: org.aralozkaya.msngr.protocol.WinkReference? = null,
    val isWinkPending: Boolean = false,
    val winkTransferError: String? = null,
    val autoPlayWink: Boolean = false,
    val autoPlayEmotionSound: Boolean = false,
    val fileTransfer: FileTransfer? = null,
) {
    val emotionSound: org.aralozkaya.msngr.protocol.EmotionSoundMessage?
        get() = if (author != MessageAuthor.System && !isInk && !isVoiceClip && wink == null && fileTransfer == null) org.aralozkaya.msngr.protocol.MsnEmotionSound.parse(text) else null
    val isInk: Boolean get() = inkDrawing != null
    val isVoiceClip: Boolean get() = voiceClip != null || isVoiceClipPending || voiceClipTransferError != null
}

data class ConversationState(
    val messages: List<ChatMessage> = emptyList(),
    val isContactTyping: Boolean = false,
    val isHistoryLoaded: Boolean = false,
    val canLoadOlderMessages: Boolean = false,
)

sealed interface MessengerEvent {
    data class IncomingMessage(val contactId: String, val messageId: Long, val isOffline: Boolean = false) : MessengerEvent
    data object OfflineMessagesReceived : MessengerEvent
    data class IncomingNudge(val contactId: String) : MessengerEvent
    data class PresenceChanged(
        val contactId: String,
        val presence: Presence,
        val previousPresence: Presence,
    ) : MessengerEvent
    data class ConnectionLost(val reason: String?) : MessengerEvent
    data class SignedOut(val reason: String) : MessengerEvent
    data class OwnDisplayPictureChanged(val account: AccountIdentity, val personalMessage: String? = null) : MessengerEvent
    data class OwnPersonalStatusChanged(val account: AccountIdentity, val statusMessage: String) : MessengerEvent
    data class OwnCurrentMediaChanged(val account: AccountIdentity, val currentMedia: CurrentMedia?) : MessengerEvent
    data class OwnPresenceChanged(val account: AccountIdentity, val presence: Presence) : MessengerEvent
    data class ConversationRepliedElsewhere(val contactId: String) : MessengerEvent
}

data class PortalState(
    val config: MsgrClientConfig = MsgrClientConfig(),
    val bannerAd: MessengerBannerAd? = null,
    val textAd: MessengerTextAd? = null,
    val whatsNew: List<WhatsNewActivity> = emptyList(),
    val isLoadingConfig: Boolean = false,
    val isLoadingWhatsNew: Boolean = false,
    val configError: String? = null,
    val whatsNewError: String? = null,
)

class MessengerRepository(
    private val client: MsnpClient,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val awaitNetworkAccess: suspend () -> Unit = {},
    private val historyStore: ConversationHistoryStore = InMemoryConversationHistoryStore(),
    private val messengerPlusEnabled: () -> Boolean = { true },
    private val draftStore: ConversationDraftStore = ConversationDraftStore(),
    private val onConversationRead: (String) -> Unit = {},
    private val useMockServer: Boolean = client is MockMsnp18Client,
    private val onRemoteSignOut: (AccountIdentity) -> Unit = {},
    private val signOutOtherSessionsOnSignIn: (AccountIdentity) -> Boolean = { false },
    private val styleStore: ConversationStyleStore = ConversationStyleStore(),
    private val onOwnPresenceChanged: (AccountIdentity, Presence) -> Unit = { _, _ -> },
) {
    private val repositoryScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var nicknameJob: Job? = null
    private var personalMessageJob: Job? = null
    private var displayPictureJob: Job? = null
    private var automaticReconnectJob: Job? = null
    private val connectionMutex = Mutex()
    private val sessionLock = Any()
    @Volatile private var sessionGeneration = 0L
    private var retired = false
    @Volatile private var lastSignInRequest: SignInRequest? = null
    @Volatile private var appInForeground = true
    @Volatile private var persistentBackgroundConnection = false
    private val mutableConnectionIssue = MutableStateFlow<String?>(null)
    private val mutableSignOutReason = MutableStateFlow<String?>(null)
    private val mutableProfile = MutableStateFlow(
        UserProfileSnapshot("You", "", Presence.Online),
    )
    private val mutableRequestAccount = MutableStateFlow<String?>(null)
    private val mutablePendingNudgeShakes = MutableStateFlow<Set<String>>(emptySet())

    val profile: StateFlow<UserProfileSnapshot> = mutableProfile.asStateFlow()
    val drafts: StateFlow<Map<String, String>> = accountData(draftStore.drafts)
    val textStyles = accountData(styleStore.styles)
    val contacts: StateFlow<List<Contact>> = client.contacts
    val portalState: StateFlow<PortalState> = client.portalState
    internal val liveTransferMessages get() = client.messages
    val nudgeCooldowns: StateFlow<Map<String, Long>> = client.nudgeCooldowns
    private val mutablePendingEmotionSounds = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
    private val queuedEmotionSoundIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val pendingEmotionSounds = mutablePendingEmotionSounds.asStateFlow()
    fun consumePendingEmotionSound(contactId: String, id: Long) {
        mutablePendingEmotionSounds.update { if (it[contactId]?.id == id) it - contactId else it }
    }
    private val mutablePendingWinks = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
    private val queuedWinkIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val pendingWinks = mutablePendingWinks.asStateFlow()
    fun consumePendingWink(contactId: String, id: Long) {
        mutablePendingWinks.update { if (it[contactId]?.id == id) it - contactId else it }
    }
    val pendingNudgeShakes: StateFlow<Set<String>> = mutablePendingNudgeShakes.asStateFlow()
    val contactRequests: StateFlow<List<ContactRequest>> = client.contactRequests
    val groupInvitations: StateFlow<List<GroupInvitation>> = client.groupInvitations
    val categories: StateFlow<List<ContactCategory>> = client.categories
    val groupInvitationInbox: StateFlow<GroupInvitationInbox> = combine(client.groupInvitations, mutableRequestAccount) { invitations, account ->
        GroupInvitationInbox(account, if (account == null) emptyList() else invitations)
    }.stateIn(repositoryScope, SharingStarted.Eagerly, GroupInvitationInbox())
    val contactRequestInbox: StateFlow<ContactRequestInbox> = combine(client.contactRequests, mutableRequestAccount) { requests, account ->
        ContactRequestInbox(account, if (account == null) emptyList() else requests)
    }.stateIn(repositoryScope, SharingStarted.Eagerly, ContactRequestInbox())
    val events: SharedFlow<MessengerEvent> = client.events
    val sessions: StateFlow<List<AccountSession>> = client.sessions
    val displayPictureStorageIssue: StateFlow<String?> = client.displayPictureStorageIssue
    val signOutReason: StateFlow<String?> = mutableSignOutReason.asStateFlow()
    val connectionIssue: StateFlow<String?> = mutableConnectionIssue.asStateFlow()
    val hasSignedInSession: Boolean get() = lastSignInRequest != null
    val signedInAccountIdentity: AccountIdentity? get() = lastSignInRequest?.let {
        AccountIdentity.forServer(it.email, it.serverConfig, useMockServer)
    }
    val contactRequestAccountId: String? get() = mutableRequestAccount.value

    private fun <T> accountData(source: StateFlow<Map<String, T>>): StateFlow<Map<String, T>> =
        combine(source, mutableRequestAccount) { values, owner -> if (owner == null) emptyMap() else values }
            .stateIn(repositoryScope + Dispatchers.Unconfined, SharingStarted.Eagerly, emptyMap())

    init {
        repositoryScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event is MessengerEvent.IncomingNudge) {
                    val key = normalizeNudgeShakeKey(event.contactId)
                    if (key.isNotEmpty()) {
                        mutablePendingNudgeShakes.update { it + key }
                    }
                } else if (event is MessengerEvent.OwnDisplayPictureChanged && event.account == signedInAccountIdentity) {
                    event.personalMessage?.let { status -> mutableProfile.update { it.copy(statusMessage = status) } }
                } else if (event is MessengerEvent.OwnPersonalStatusChanged && event.account == signedInAccountIdentity) {
                    mutableProfile.update { it.copy(statusMessage = event.statusMessage) }
                } else if (event is MessengerEvent.OwnCurrentMediaChanged && event.account == signedInAccountIdentity) {
                    mutableProfile.update { it.copy(currentMedia = event.currentMedia) }
                } else if (event is MessengerEvent.OwnPresenceChanged && event.account == signedInAccountIdentity) {
                    mutableProfile.update { it.copy(presence = event.presence) }
                    onOwnPresenceChanged(event.account, event.presence)
                } else if (event is MessengerEvent.ConversationRepliedElsewhere) {
                    clearPendingNudgeShake(event.contactId)
                    mutablePendingWinks.update { it - event.contactId }
                    mutablePendingEmotionSounds.update { it - event.contactId }
                    onConversationRead(event.contactId)
                } else if (event is MessengerEvent.SignedOut) {
                    val generation = synchronized(sessionLock) { sessionGeneration }
                    repositoryScope.launch {
                        connectionMutex.withLock {
                            val account = synchronized(sessionLock) {
                                if (retired || generation != sessionGeneration) return@withLock
                                val owner = signedInAccountIdentity ?: return@withLock
                                logout()
                                owner
                            }
                            onRemoteSignOut(account)
                            mutableSignOutReason.value = event.reason
                        }
                    }
                } else if (event is MessengerEvent.ConnectionLost && lastSignInRequest != null) {
                    mutableConnectionIssue.value =
                        event.reason?.takeIf(String::isNotBlank) ?: "Connection interrupted"
                    startAutomaticReconnect()
                }
            }
        }
        repositoryScope.launch {
            client.messages.collect { messages ->
                synchronized(sessionLock) {
                    if (mutableRequestAccount.value != null && messages == client.messages.value) {
                        historyStore.merge(messages)
                        messages.forEach { (contactId, entries) ->
                            entries.lastOrNull { it.autoPlayEmotionSound && it.emotionSound != null }?.let { message ->
                                if (message.id > (queuedEmotionSoundIds[contactId] ?: Long.MIN_VALUE)) {
                                    queuedEmotionSoundIds[contactId] = message.id
                                    mutablePendingEmotionSounds.update { it + (contactId to message) }
                                }
                            }
                            entries.lastOrNull { it.autoPlayWink && it.wink != null && it.winkTransferError == null }?.let { message ->
                                if (message.id > (queuedWinkIds[contactId] ?: Long.MIN_VALUE)) {
                                    queuedWinkIds[contactId] = message.id
                                    mutablePendingWinks.update { it + (contactId to message) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> =
        signIn(request, activateRemembered = null)

    internal suspend fun signIn(
        request: SignInRequest,
        activateRemembered: (() -> Boolean)?,
    ): Result<UserProfileSnapshot> = connectionMutex.withLock {
        val account = AccountIdentity.forServer(request.email, request.serverConfig, useMockServer)
        val generation = synchronized(sessionLock) {
            if (retired || (activateRemembered != null && lastSignInRequest != null && signedInAccountIdentity != account)) {
                return@withLock expiredSession()
            }
            if (lastSignInRequest == request && mutableConnectionIssue.value == null) {
                if (activateRemembered?.invoke() == false) return@withLock expiredSession()
                return@withLock Result.success(mutableProfile.value)
            }
            if (lastSignInRequest != null && account != signedInAccountIdentity) logout()
            mutableSignOutReason.value = null
            if (lastSignInRequest == null) sessionGeneration++
            sessionGeneration
        }
        authenticate(request, account, generation, activateRemembered)
    }

    suspend fun reconnect(expected: AccountIdentity? = null): Result<UserProfileSnapshot> = connectionMutex.withLock {
        val request = lastSignInRequest
            ?: return@withLock Result.failure(
                SessionUnavailableException("The previous sign-in session is no longer available."),
            )
        val reconnectRequest = request.copy(initialPresence = mutableProfile.value.presence)
        val account = AccountIdentity.forServer(request.email, request.serverConfig, useMockServer)
        val generation = synchronized(sessionLock) {
            if (request !== lastSignInRequest || retired || (expected != null && expected != account)) return@withLock expiredSession()
            sessionGeneration
        }
        authenticate(reconnectRequest, account, generation)
    }

    private fun expiredSession() = Result.failure<UserProfileSnapshot>(
        SessionUnavailableException("The sign-in session has ended."),
    )

    private suspend fun authenticate(
        request: SignInRequest,
        account: AccountIdentity,
        generation: Long,
        activateRemembered: (() -> Boolean)? = null,
    ): Result<UserProfileSnapshot> {
        var clientAccepted = false
        try {
            val profile = client.signIn(request).getOrThrow()
            clientAccepted = true
            synchronized(sessionLock) { requireCurrentSession(generation) }
            if (signOutOtherSessionsOnSignIn(account)) {
                try {
                    client.logOutOtherSessions()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw IllegalStateException("Could not sign you out of all other places. ${error.message.orEmpty()}", error)
                }
            }
            withContext(Dispatchers.IO) {
                synchronized(sessionLock) {
                    requireCurrentSession(generation)
                    draftStore.selectAccount(account.storageId)
                    styleStore.selectAccount(account.storageId)
                    historyStore.selectAccount(account.storageId)
                    historyStore.merge(client.messages.value)
                    if (activateRemembered?.invoke() == false) throw SessionUnavailableException("The selected account has changed.")
                    lastSignInRequest = request
                    mutableRequestAccount.value = account.storageId
                    mutableProfile.value = profile
                    mutableConnectionIssue.value = null
                    startOfflineMessageDelivery(account)
                }
            }
            return Result.success(profile)
        } catch (error: Exception) {
            synchronized(sessionLock) {
                if (clientAccepted || error is CancellationException) client.close()
                if (generation == sessionGeneration) {
                    if (lastSignInRequest == null) {
                        deselectStores()
                    } else {
                        mutableConnectionIssue.value = error.message?.takeIf(String::isNotBlank) ?: "Connection interrupted"
                    }
                }
            }
            if (error is CancellationException) throw error
            return Result.failure(error)
        }
    }

    private fun requireCurrentSession(generation: Long) {
        if (generation != sessionGeneration) throw SessionUnavailableException("The sign-in session has ended.")
    }

    private fun startOfflineMessageDelivery(account: AccountIdentity) {
        client.startOfflineMessageDelivery { message ->
            withContext(Dispatchers.IO) {
                synchronized(sessionLock) {
                    check(mutableRequestAccount.value == account.storageId) { "The account changed while offline messages were downloading." }
                    historyStore.persistOfflineMessage(message)
                }
            }
        }
    }

    fun setLocalProfile(
        profile: UserProfileSnapshot,
        publishStoredSettings: Boolean = false,
        displayPictureBytes: ByteArray? = null,
        publishDisplayPicture: Boolean = true,
        publishStatusMessage: Boolean = publishStoredSettings,
        account: AccountIdentity? = null,
    ): Unit = synchronized(sessionLock) {
        if (account != null && signedInAccountIdentity != account) return
        mutableProfile.value = if (
            profile.email.equals(mutableProfile.value.email, ignoreCase = true) &&
            profile.currentMedia == null
        ) {
            profile.copy(currentMedia = mutableProfile.value.currentMedia)
        } else {
            profile
        }
        if (publishStoredSettings) {
            nicknameJob?.cancel()
            nicknameJob = repositoryScope.launch {
                runCatching {
                    client.changeNickname(
                        profile.username.trim().withoutMessengerPlusFormattingIfDisabled(),
                    )
                }
            }
            if (publishStatusMessage) {
                personalMessageJob?.cancel()
                personalMessageJob = repositoryScope.launch {
                    runCatching {
                        client.changePersonalMessage(
                            profile.statusMessage.trim().withoutMessengerPlusFormattingIfDisabled(),
                        )
                    }
                }
            }
            if (publishDisplayPicture && (displayPictureBytes != null || profile.avatarUri == null)) {
                displayPictureJob?.cancel()
                displayPictureJob = repositoryScope.launch {
                    runCatching { client.changeDisplayPicture(displayPictureBytes) }
                }
            }
        }
    }

    fun setPresence(presence: Presence) {
        mutableProfile.update { it.copy(presence = presence) }
        repositoryScope.launch { client.changePresence(presence) }
    }

    fun setStatusMessage(statusMessage: String) {
        val normalized = statusMessage
            .replace('\r', ' ')
            .replace('\n', ' ')
            .withoutMessengerPlusFormattingIfDisabled()
        if (mutableProfile.value.statusMessage == normalized) return
        mutableProfile.update { it.copy(statusMessage = normalized) }
        personalMessageJob?.cancel()
        personalMessageJob = repositoryScope.launch {
            delay(PERSONAL_MESSAGE_DEBOUNCE_MILLIS)
            runCatching { client.changePersonalMessage(normalized.trim()) }
        }
    }

    /** Updates and awaits publication of the signed-in user's personal status message. */
    suspend fun updateStatusMessage(statusMessage: String, account: AccountIdentity? = null): Result<Unit> = connectionMutex.withLock {
        if (account != null && signedInAccountIdentity != account) {
            return@withLock Result.failure(SessionUnavailableException("The signed-in account changed."))
        }
        val normalized = statusMessage
            .replace('\r', ' ')
            .replace('\n', ' ')
            .withoutMessengerPlusFormattingIfDisabled()
            .trim()
        personalMessageJob?.cancel()
        val generation = sessionGeneration
        val previous = mutableProfile.value.statusMessage
        mutableProfile.update { it.copy(statusMessage = normalized) }
        val result = runCatching {
            client.changePersonalMessage(normalized)
        }
        result.onFailure { error ->
            mutableProfile.update { current ->
                if (generation == sessionGeneration && current.statusMessage == normalized) current.copy(statusMessage = previous) else current
            }
            if (error is CancellationException) throw error
        }
        result
    }

    /** Updates and awaits publication of the signed-in user's MSNP `<CurrentMedia>` state. */
    suspend fun updateCurrentMedia(media: CurrentMedia?): Result<Unit> {
        if (!hasSignedInSession) return Result.success(Unit)
        val generation = sessionGeneration
        val previous = mutableProfile.value.currentMedia
        mutableProfile.update { it.copy(currentMedia = media) }
        val result = runCatching {
            client.changeCurrentMedia(media)
        }
        result.onFailure { error ->
            mutableProfile.update { current ->
                if (generation == sessionGeneration && current.currentMedia == media) current.copy(currentMedia = previous) else current
            }
            if (error is CancellationException) throw error
        }
        return result
    }

    suspend fun updateProfile(
        username: String,
        avatarUri: String?,
        displayPictureBytes: ByteArray? = null,
        updateDisplayPicture: Boolean? = null,
    ): Result<Unit> = connectionMutex.withLock {
        clientResult {
            val generation = sessionGeneration
            val normalizedUsername = username.trim().withoutMessengerPlusFormattingIfDisabled()
            val nicknameChanged = mutableProfile.value.username != normalizedUsername
            val displayPictureChanged = updateDisplayPicture ?: (mutableProfile.value.avatarUri != avatarUri ||
                displayPictureBytes != null)
            if (displayPictureChanged) {
                displayPictureJob?.cancel()
                client.changeDisplayPicture(displayPictureBytes)
            }
            if (nicknameChanged) {
                nicknameJob?.cancel()
                client.changeNickname(normalizedUsername)
            }
            synchronized(sessionLock) {
                requireCurrentSession(generation)
                mutableProfile.update {
                    it.copy(username = normalizedUsername, avatarUri = if (displayPictureChanged) avatarUri else it.avatarUri)
                }
            }
        }
    }

    fun contact(contactId: String): Flow<Contact?> =
        contacts.combine(profile) { list, currentProfile -> list.resolveContact(contactId, currentProfile) }

    suspend fun refreshContactPicture(contactId: String) = client.refreshContactPicture(contactId)

    suspend fun fetchOwnDisplayPicture(): org.aralozkaya.msngr.IncomingDisplayPicture =
        client.fetchOwnDisplayPicture()

    internal fun showStoredDisplayPicture(avatarUri: String?, account: AccountIdentity) {
        mutableProfile.update { current ->
            if (signedInAccountIdentity == account && current.email.trim().equals(account.email, ignoreCase = true))
                current.copy(avatarUri = avatarUri)
            else current
        }
    }

    /** Reconciliation changes only the picture; it must not overwrite newer profile text edits. */
    suspend fun useDisplayPicture(avatarUri: String?, bytes: ByteArray?, publish: Boolean, account: AccountIdentity) = connectionMutex.withLock {
        check(signedInAccountIdentity == account) { "The signed-in account changed. Please try again." }
        displayPictureJob?.cancel()
        if (publish) client.changeDisplayPicture(bytes) else client.adoptIncomingDisplayPicture(bytes)
        synchronized(sessionLock) {
            check(signedInAccountIdentity == account) { "The signed-in account changed. Please try again." }
            mutableProfile.update { it.copy(avatarUri = avatarUri) }
        }
    }

    fun conversation(contactId: String): Flow<ConversationState> =
        combine(historyStore.messages, client.typing, historyStore.pageStates, mutableRequestAccount) {
                messages, typing, pageStates, owner ->
            if (owner == null) return@combine ConversationState()
            val pageState = pageStates[contactId] ?: ConversationHistoryPageState()
            ConversationState(
                messages = messages[contactId].orEmpty(),
                isContactTyping = typing[contactId] == true,
                isHistoryLoaded = pageState.isInitialPageLoaded,
                canLoadOlderMessages = pageState.canLoadOlder,
            )
        }

    private suspend fun withHistory(action: () -> Unit) {
        val generation = synchronized(sessionLock) { sessionGeneration }
        withContext(Dispatchers.IO) {
            synchronized(sessionLock) {
                if (retired || generation != sessionGeneration) throw CancellationException("The account changed.")
                action()
            }
        }
    }

    suspend fun loadConversation(contactId: String) = withHistory { historyStore.loadLatest(contactId) }

    suspend fun loadOlderMessages(contactId: String) = withHistory { historyStore.loadOlder(contactId) }

    fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>? = null,
    ) {
        val outgoing = prepareOutgoingMessage(text, renderedEmoticonRanges)
        val style = textStyles.value[contactId.lowercase(java.util.Locale.ROOT)] ?: NativeTextStyle()
        repositoryScope.launch { client.sendStyledMessage(contactId, outgoing.first, outgoing.second, style) }
    }

    suspend fun sendMessageNow(contactId: String, text: String): Result<Unit> =
        client.sendStyledMessage(
            contactId,
            text.withoutMessengerPlusFormattingIfDisabled(),
            null,
            textStyles.value[contactId.lowercase(java.util.Locale.ROOT)] ?: NativeTextStyle(),
        )

    /** A null contact means the notification target was removed or blocked before delivery. */
    internal suspend fun sendNotificationAction(
        accountKey: String,
        contactId: String,
        reply: String?,
    ): Result<Contact?> = connectionMutex.withLock {
        if (signedInAccountIdentity?.storageKey != accountKey) {
            return@withLock Result.failure(SessionUnavailableException("The account for this notification has changed."))
        }
        val contact = contactSnapshot(contactId)?.takeUnless(Contact::isBlocked)
            ?: return@withLock Result.success(null)
        clientResult {
            if (reply == null) {
                sendNudgeNow(contactId).getOrThrow()
            } else {
                markRead(contactId, dismissNotification = false)
                sendMessageNow(contactId, reply).getOrThrow()
            }
            contact
        }
    }

    fun sendInk(contactId: String, drawing: InkDrawing) {
        repositoryScope.launch { client.sendInkMessage(contactId, drawing) }
    }

    suspend fun sendInkNow(contactId: String, drawing: InkDrawing): Result<Unit> =
        client.sendInkMessage(contactId, drawing)

    suspend fun sendEmotionSound(contactId: String, sound: org.aralozkaya.msngr.protocol.EmotionSound): Result<Unit> {
        val result = client.sendStyledMessage(contactId, org.aralozkaya.msngr.protocol.MsnEmotionSound.message(sound), null,
            textStyles.value[contactId.lowercase(java.util.Locale.ROOT)] ?: NativeTextStyle())
        if (result.isSuccess) client.messages.value[contactId]?.lastOrNull { it.author == MessageAuthor.Me && it.emotionSound?.sound?.id == sound.id }?.let { message ->
            mutablePendingEmotionSounds.update { it + (contactId to message) }
        }
        return result
    }

    val photoShares: StateFlow<Map<String, PhotoShare>> get() = client.photoShares
    suspend fun requestPhotoOriginal(contactId: String, shareId: String, photoId: String): Result<Unit> = client.requestPhotoOriginal(contactId, shareId, photoId)
    suspend fun sharePhotos(contactId: String, photos: List<PhotoToShare>): Result<Unit> = client.sharePhotos(contactId, photos)
    suspend fun acceptPhotos(contactId: String, id: String): Result<Unit> = client.acceptPhotos(contactId, id)
    suspend fun stopPhotos(contactId: String, id: String): Result<Unit> = client.stopPhotos(contactId, id)
    suspend fun selectPhoto(contactId: String, id: String, photoId: String): Result<Unit> = client.selectPhoto(contactId, id, photoId)

    val gameSessions: StateFlow<Map<String, GameSession>> get() = client.gameSessions
    suspend fun startGame(contactId: String, entry: GameCatalogEntry): Result<Unit> = client.startGame(contactId, entry)
    suspend fun acceptGame(contactId: String, id: String): Result<Unit> = client.acceptGame(contactId, id)
    suspend fun stopGame(contactId: String, id: String): Result<Unit> = client.stopGame(contactId, id)
    suspend fun initializeGameChannel(contactId: String, id: String): Result<Unit> = client.initializeGameChannel(contactId, id)
    suspend fun sendGameData(contactId: String, id: String, payload: String): Result<Unit> = client.sendGameData(contactId, id, payload)

    suspend fun sendFile(contactId: String, file: java.io.File, name: String): Result<Unit> = client.sendFile(contactId, file, name)
    suspend fun acceptFile(contactId: String, id: String): Result<Unit> = client.acceptFile(contactId, id)
    suspend fun cancelFile(contactId: String, id: String): Result<Unit> = client.cancelFile(contactId, id)

    suspend fun sendWink(contactId: String, wink: org.aralozkaya.msngr.protocol.WinkReference, bytes: ByteArray): Result<Unit> =
        client.sendWink(contactId, wink, bytes)

    suspend fun sendVoiceClip(contactId: String, clip: org.aralozkaya.msngr.protocol.VoiceClip): Result<Unit> =
        client.sendVoiceClip(contactId, clip)

    fun retryMessage(contactId: String, messageId: Long) {
        repositoryScope.launch {
            val message = messageSnapshot(contactId, messageId) ?: return@launch
            if ((message.author == MessageAuthor.Me && !message.isNudge &&
                !message.isSending && message.deliveryError != null) ||
                (message.author == MessageAuthor.Contact && message.wink != null &&
                    !message.isWinkPending && message.winkTransferError != null)
            ) {
                client.retryMessage(message)
            }
        }
    }

    private fun prepareOutgoingMessage(
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ): Pair<String, List<MsnEmoticonRange>?> {
        if (messengerPlusEnabled()) return text to renderedEmoticonRanges
        val parsed = MessengerPlusTextCodec.parse(text)
        return parsed.text to renderedEmoticonRanges?.mapNotNull(parsed::textRangeForSource)
    }

    private fun String.withoutMessengerPlusFormattingIfDisabled(): String =
        if (messengerPlusEnabled()) this else messengerPlusPlainText()

    fun updateDraft(contactId: String, text: String) = synchronized(sessionLock) {
        if (mutableRequestAccount.value != null) draftStore.update(contactId, text)
    }

    fun updateTextStyle(contactId: String, style: NativeTextStyle) = synchronized(sessionLock) {
        if (mutableRequestAccount.value != null) styleStore.update(contactId, style)
    }

    fun markTextSizeNoticeShown(contactId: String): Boolean = synchronized(sessionLock) {
        mutableRequestAccount.value != null && styleStore.markSizeNoticeShown(contactId)
    }

    fun sendTyping(contactId: String) {
        repositoryScope.launch { client.sendTyping(contactId) }
    }

    fun sendNudge(contactId: String) {
        repositoryScope.launch { client.sendNudge(contactId) }
    }

    suspend fun sendNudgeNow(contactId: String): Result<Unit> =
        client.sendNudgeWithResult(contactId)

    fun consumePendingNudgeShake(contactId: String): Boolean {
        val key = normalizeNudgeShakeKey(contactId)
        if (key.isEmpty()) return false
        var consumed = false
        mutablePendingNudgeShakes.update { current ->
            if (key in current) {
                consumed = true
                current - key
            } else {
                consumed = false
                current
            }
        }
        return consumed
    }

    private fun clearPendingNudgeShake(contactId: String) {
        val key = normalizeNudgeShakeKey(contactId)
        if (key.isNotEmpty()) {
            mutablePendingNudgeShakes.update { it - key }
        }
    }

    private fun normalizeNudgeShakeKey(contactId: String): String =
        contactId.trim().lowercase(Locale.ROOT)

    fun blockContact(contactId: String) {
        clearPendingNudgeShake(contactId)
        repositoryScope.launch { runCatching { client.blockContact(contactId) } }
    }

    fun unblockContact(contactId: String) {
        repositoryScope.launch { runCatching { client.unblockContact(contactId) } }
    }

    suspend fun addContact(email: String, message: String = ""): Result<Unit> =
        signedInOperation("Sign in before adding a contact.") { client.addContact(email, message) }

    suspend fun createCategory(name: String, memberIds: List<String> = emptyList()): Result<String> =
        signedInOperation { client.createCategory(name, memberIds) }

    suspend fun renameCategory(categoryId: String, newName: String): Result<Unit> =
        signedInOperation { client.renameCategory(categoryId, newName) }

    suspend fun updateCategoryMembers(categoryId: String, memberIds: Set<String>): Result<Unit> =
        signedInOperation { client.updateCategoryMembers(categoryId, memberIds) }

    suspend fun deleteCategory(categoryId: String): Result<Unit> =
        signedInOperation { client.deleteCategory(categoryId) }

    suspend fun setContactCategories(contactId: String, categoryIds: Set<String>): Result<Unit> =
        signedInOperation { client.setContactCategories(contactId, categoryIds) }

    suspend fun setContactFavorite(contactId: String, favorite: Boolean): Result<Unit> =
        signedInOperation { client.setContactFavorite(contactId, favorite) }

    suspend fun createGroup(name: String): Result<String> = signedInOperation { client.createGroup(name) }
    suspend fun setGroupBlocked(groupId: String, blocked: Boolean): Result<Unit> = signedInOperation {
        val group = client.contacts.value.firstOrNull { it.id == groupId && it.isGroup }
            ?: error("This group is no longer available.")
        if (blocked) client.blockContact(group.id) else client.unblockContact(group.id)
        if (blocked) {
            clearPendingNudgeShake(groupId)
            mutablePendingWinks.update { it - groupId }
            mutablePendingEmotionSounds.update { it - groupId }
            queuedEmotionSoundIds.remove(groupId)
            onConversationRead(groupId)
        }
    }
    suspend fun inviteGroupMembers(groupId: String, emails: List<String>, message: String = ""): Result<Unit> = signedInOperation { client.inviteGroupMembers(groupId, emails, message) }
    suspend fun leaveGroup(groupId: String): Result<Unit> = signedInOperation {
        client.leaveGroup(groupId)
        clearPendingNudgeShake(groupId)
        onConversationRead(groupId)
    }
    suspend fun refreshGroupInvitations(): Result<Unit> = signedInOperation { client.refreshGroupInvitations() }
    suspend fun respondToGroupInvitation(groupId: String, accept: Boolean): Result<Unit> = signedInOperation { client.respondToGroupInvitation(groupId, accept) }

    private suspend fun <T> signedInOperation(
        signInMessage: String = "Sign in before updating groups.",
        operation: suspend () -> T,
    ): Result<T> = connectionMutex.withLock {
        clientResult {
            check(lastSignInRequest != null) { signInMessage }
            operation()
        }
    }

    suspend fun deleteContact(contactId: String, block: Boolean = false): Result<Unit> =
        signedInOperation("Sign in before deleting a contact.") {
            client.deleteContact(contactId, block)
            clearPendingNudgeShake(contactId)
            mutablePendingWinks.update { it - contactId }
            mutablePendingEmotionSounds.update { it - contactId }
            queuedEmotionSoundIds.remove(contactId)
            queuedWinkIds.remove(contactId)
            onConversationRead(contactId)
        }

    suspend fun refreshContactRequests(): Result<Unit> =
        signedInOperation("Sign in to load contact requests.") { client.refreshContactRequests() }

    suspend fun refreshBlockedContacts(): Result<Unit> =
        signedInOperation("Sign in to load blocked contacts.") { client.refreshBlockedContacts() }

    suspend fun respondToContactRequest(requestId: String, accept: Boolean, block: Boolean = false): Result<Unit> =
        signedInOperation("Sign in before responding to a contact request.") {
            client.respondToContactRequest(requestId, accept, block)
        }

    fun markRead(contactId: String, dismissNotification: Boolean = true) {
        client.markRead(contactId)
        if (dismissNotification) onConversationRead(contactId)
    }

    fun markUnread(contactId: String) = client.markUnread(contactId)

    suspend fun clearConversationHistory(contactId: String) = withHistory {
        historyStore.clearContact(contactId)
        clearPendingNudgeShake(contactId)
        mutablePendingWinks.update { it - contactId }
        mutablePendingEmotionSounds.update { it - contactId }
        queuedEmotionSoundIds.remove(contactId)
        queuedWinkIds.remove(contactId)
        client.clearMessages(contactId)
    }

    suspend fun clearConversationHistory() = withHistory {
        historyStore.clearAll()
        mutablePendingNudgeShakes.value = emptySet()
        mutablePendingWinks.value = emptyMap()
        mutablePendingEmotionSounds.value = emptyMap()
        queuedEmotionSoundIds.clear()
        queuedWinkIds.clear()
        client.clearMessages()
    }

    fun setAppInForeground(isForeground: Boolean) {
        appInForeground = isForeground
        client.setAppInForeground(isForeground)
        if (isForeground && mutableConnectionIssue.value != null) {
            startAutomaticReconnect()
        } else if (!isForeground && !persistentBackgroundConnection) {
            automaticReconnectJob?.cancel()
            automaticReconnectJob = null
        }
    }

    fun setPersistentBackgroundConnection(enabled: Boolean) {
        persistentBackgroundConnection = enabled
        if (enabled && mutableConnectionIssue.value != null) {
            startAutomaticReconnect()
        } else if (!enabled && !appInForeground) {
            automaticReconnectJob?.cancel()
            automaticReconnectJob = null
        }
    }

    fun contactSnapshot(contactId: String): Contact? =
        contacts.value.resolveContact(contactId, profile.value)

    suspend fun refreshPortal(includeAds: Boolean = true) = client.refreshPortal(includeAds)

    suspend fun refreshWhatsNew() = client.refreshWhatsNew()

    suspend fun refreshAds() = client.refreshAds()

    fun messageSnapshot(contactId: String, messageId: Long): ChatMessage? = synchronized(sessionLock) {
        if (retired) return null
        client.messages.value[contactId]?.firstOrNull { it.id == messageId }
            ?: historyStore.messages.value[contactId]?.firstOrNull { it.id == messageId }
    }

    private fun deselectStores() {
        historyStore.deselectAccount()
        draftStore.deselectAccount()
        styleStore.deselectAccount()
    }

    internal fun retireIfIdle(): Boolean {
        if (!connectionMutex.tryLock()) return false
        try {
            if (hasSignedInSession) return false
            retire()
            return true
        } finally {
            connectionMutex.unlock()
        }
    }

    internal fun retire(): Unit = synchronized(sessionLock) {
        if (retired) return
        retired = true
        logout()
        repositoryScope.cancel()
    }

    fun logout(): Unit = synchronized(sessionLock) {
        sessionGeneration++
        if (mutableRequestAccount.value != null) deselectStores()
        mutablePendingWinks.value = emptyMap()
        mutablePendingEmotionSounds.value = emptyMap()
        queuedEmotionSoundIds.clear()
        queuedWinkIds.clear()
        mutableRequestAccount.value = null
        mutablePendingNudgeShakes.value = emptySet()
        automaticReconnectJob?.cancel()
        automaticReconnectJob = null
        persistentBackgroundConnection = false
        nicknameJob?.cancel()
        nicknameJob = null
        personalMessageJob?.cancel()
        personalMessageJob = null
        displayPictureJob?.cancel()
        displayPictureJob = null
        lastSignInRequest = null
        mutableConnectionIssue.value = null
        client.close()
        mutableProfile.value = UserProfileSnapshot("You", "", Presence.Online)
    }

    suspend fun logOutSessions(sessionIds: Set<String>): Result<Unit> =
        signedInOperation("This session has already ended.") {
            val selected = sessions.value.filter { it.id in sessionIds }
            for (session in selected.filterNot(AccountSession::isCurrent)) {
                if (sessions.value.any { it.id == session.id }) client.logOutSession(session.id)
            }
            if (selected.any(AccountSession::isCurrent)) logout()
        }

    private fun startAutomaticReconnect() {
        if (
            automaticReconnectJob?.isActive == true ||
            lastSignInRequest == null ||
            !appInForeground && !persistentBackgroundConnection
        ) {
            return
        }
        automaticReconnectJob = repositoryScope.launch {
            var attempt = 0
            while (isActive && mutableConnectionIssue.value != null && lastSignInRequest != null) {
                delay(reconnectDelayMillis(attempt++))
                awaitNetworkAccess()
                if (mutableConnectionIssue.value == null) break
                reconnect()
            }
        }
    }

    private inline fun <T> clientResult(operation: () -> T): Result<T> = try {
        Result.success(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private companion object {
        const val PERSONAL_MESSAGE_DEBOUNCE_MILLIS = 500L

        fun reconnectDelayMillis(attempt: Int): Long = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 5_000L
            3 -> 10_000L
            4 -> 30_000L
            else -> 60_000L
        }
    }
}
