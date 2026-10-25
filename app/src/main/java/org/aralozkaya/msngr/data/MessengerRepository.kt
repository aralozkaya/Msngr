package org.aralozkaya.msngr.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.AccountIdentity
import org.aralozkaya.msngr.MessengerPlusTextCodec
import org.aralozkaya.msngr.MsnEmoticonRange
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import org.aralozkaya.msngr.ServerConfig
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.trimMessengerPlusWhitespace
import org.aralozkaya.msngr.withoutUnicodeEmoji
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
    val avatarUri: String? = null,
    val unreadCount: Int = 0,
    val isBlocked: Boolean = false,
    val isForwardListContact: Boolean = true,
    val isMarkedUnread: Boolean = false,
    val isGroup: Boolean = false,
    val members: List<GroupMember> = emptyList(),
) {
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
)

internal fun Contact.withGroupPresence(ownEmail: String): Contact = if (!isGroup) this else copy(
    presence = if (!isBlocked && members.any { !it.email.equals(ownEmail, true) && it.presence.isOnline })
        Presence.Online else Presence.Offline,
)

/** Resolve group-only members without adding them to the user's contact list. */
internal fun List<Contact>.resolveContact(contactId: String, profile: UserProfileSnapshot? = null): Contact? {
    firstOrNull { it.id.equals(contactId, ignoreCase = true) }?.let { return it }
    if (profile != null && profile.email.equals(contactId, ignoreCase = true)) {
        return Contact(profile.email.lowercase(), profile.username, profile.email, profile.presence,
            statusMessage = profile.statusMessage, avatarUri = profile.avatarUri, isForwardListContact = false)
    }
    val members = asSequence().filter(Contact::isGroup).flatMap { it.members.asSequence() }
        .filter { it.email.equals(contactId, ignoreCase = true) }.toList()
    val member = members.minByOrNull { it.presence.priority } ?: return null
    return Contact(member.email.lowercase(), member.displayName, member.email, member.presence,
        avatarUri = members.firstNotNullOfOrNull { it.avatarUri }, isForwardListContact = false)
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
) {
    val isInk: Boolean get() = inkDrawing != null
}
private fun unusedChatMessageMarker() = listOf<Unit>(
)

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
}

private val emptyAccountSessions = MutableStateFlow<List<AccountSession>>(emptyList()).asStateFlow()
private val emptyContactRequests = MutableStateFlow<List<ContactRequest>>(emptyList()).asStateFlow()
private val emptyGroupInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList()).asStateFlow()
private val noPictureStorageIssue = MutableStateFlow<String?>(null).asStateFlow()
private val emptyNudgeCooldowns = MutableStateFlow<Map<String, Long>>(emptyMap()).asStateFlow()

interface MsnpClient {
    val nudgeCooldowns: StateFlow<Map<String, Long>> get() = emptyNudgeCooldowns
    val displayPictureStorageIssue: StateFlow<String?> get() = noPictureStorageIssue
    val contacts: StateFlow<List<Contact>>
    val messages: StateFlow<Map<String, List<ChatMessage>>>
    val typing: StateFlow<Map<String, Boolean>>
    val events: SharedFlow<MessengerEvent>
    val sessions: StateFlow<List<AccountSession>> get() = emptyAccountSessions
    val contactRequests: StateFlow<List<ContactRequest>> get() = emptyContactRequests
    val groupInvitations: StateFlow<List<GroupInvitation>> get() = emptyGroupInvitations

    suspend fun createGroup(name: String): String = throw UnsupportedOperationException("Creating groups is not supported by this client.")
    suspend fun inviteGroupMembers(groupId: String, emails: List<String>, message: String = "") { throw UnsupportedOperationException("Group invitations are not supported by this client.") }
    suspend fun leaveGroup(groupId: String) { throw UnsupportedOperationException("Leaving groups is not supported by this client.") }
    suspend fun refreshGroupInvitations() = Unit
    suspend fun respondToGroupInvitation(groupId: String, accept: Boolean) { throw UnsupportedOperationException("Group invitations are not supported by this client.") }

    suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot>
    suspend fun changePresence(presence: Presence)
    suspend fun changeNickname(nickname: String)
    suspend fun changePersonalMessage(statusMessage: String)
    suspend fun changeDisplayPicture(image: ByteArray?)
    suspend fun fetchOwnDisplayPicture(): org.aralozkaya.msngr.IncomingDisplayPicture =
        org.aralozkaya.msngr.IncomingDisplayPicture()
    /** Announce a downloaded server picture without writing it back to roaming storage. */
    suspend fun adoptIncomingDisplayPicture(image: ByteArray?) = Unit
    suspend fun refreshContactPicture(contactId: String) = Unit
    suspend fun logOutSession(sessionId: String) {
        throw UnsupportedOperationException("Session management is not supported by this client.")
    }
    suspend fun logOutOtherSessions() {
        for (session in sessions.value.filterNot(AccountSession::isCurrent)) logOutSession(session.id)
    }
    suspend fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>? = null,
    )
    suspend fun sendMessageWithResult(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>? = null,
    ): Result<Unit> = runCatching { sendMessage(contactId, text, renderedEmoticonRanges) }
    suspend fun sendStyledMessage(
        contactId: String, text: String, renderedEmoticonRanges: List<MsnEmoticonRange>?,
        style: NativeTextStyle,
    ): Result<Unit> = sendMessageWithResult(contactId, text, renderedEmoticonRanges)
    suspend fun retryMessage(message: ChatMessage): Result<Unit> =
        Result.failure(UnsupportedOperationException("Message retry is not supported."))
    suspend fun sendInkMessage(contactId: String, drawing: InkDrawing): Result<Unit> =
        Result.failure(UnsupportedOperationException("Handwriting messages are not supported."))
    suspend fun sendTyping(contactId: String)
    suspend fun sendNudge(contactId: String)
    suspend fun sendNudgeWithResult(contactId: String): Result<Unit> =
        runCatching { sendNudge(contactId) }
    suspend fun blockContact(contactId: String)
    suspend fun unblockContact(contactId: String)
    suspend fun addContact(email: String, message: String = "") {
        throw UnsupportedOperationException("Adding contacts is not supported by this client.")
    }
    suspend fun deleteContact(contactId: String, block: Boolean = false) {
        throw UnsupportedOperationException("Deleting contacts is not supported by this client.")
    }
    suspend fun refreshContactRequests() = Unit
    suspend fun respondToContactRequest(requestId: String, accept: Boolean, block: Boolean = false) {
        throw UnsupportedOperationException("Contact requests are not supported by this client.")
    }
    fun markRead(contactId: String)
    fun markUnread(contactId: String)
    fun clearMessages()
    fun clearMessages(contactId: String)
    fun setAppInForeground(isForeground: Boolean) = Unit
    /** Starts only after the repository has selected this account's durable history. */
    fun startOfflineMessageDelivery(persist: suspend (ChatMessage) -> Boolean) = Unit
    fun close()
}

/** Single source of truth for the bundled mock server fixtures used by the app and tests. */
object MockServerData {
    const val ACCOUNT_EMAIL = "mock-user@example.com"
    const val ACCOUNT_PASSWORD = "mock-password"
    const val ACCOUNT_NICKNAME = "Mock Profile"
    const val ACCOUNT_STATUS_MESSAGE = "Testing with mock data"
    const val REJECTED_CREDENTIALS_MESSAGE =
        "The community server rejected these credentials."

    fun rejects(email: String): Boolean = email.contains("fail", ignoreCase = true)

    fun contacts(): List<Contact> = listOf(
        Contact("selin", "Selin", "selin@msngr.dev", Presence.Online, "I love MSN!", unreadCount = 2),
        Contact("mert", "Mert", "mert@msngr.dev", Presence.Busy, "Heads down today"),
        Contact("derya", "Derya", "derya@msngr.dev", Presence.Away, "At the studio", unreadCount = 1),
        Contact("can", null, "can@msngr.dev", Presence.Offline, "Ping me later"),
        Contact("zeynep", "Zeynep", "zeynep@msngr.dev", Presence.Online, "Coffee first", unreadCount = 3),
        Contact("ece", "Ece", "ece@msngr.dev", Presence.Online, "Shipping build assets"),
        Contact("deniz", "Deniz", "deniz@msngr.dev", Presence.Busy, "In a call", unreadCount = 2),
    )

    fun replyTo(message: String): String = when {
        message.contains("hello", ignoreCase = true) -> "Hey! Great to see you online 👋"
        message.endsWith('?') -> "I think so — just like the old Messenger days."
        else -> "Got it! This mock reply arrived through the MSNP event stream."
    }
}

/** Bundled MSNP18 test client with sample contacts and simulated event delivery. */
class MockMsnp18Client(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val signInDelayMillis: Long = 650L,
    private val addContactDelayMillis: Long = 2_000L,
    private val deleteContactDelayMillis: Long = 2_000L,
    private val contactRequestDelayMillis: Long = 2_000L,
    private val requestFixtures: (String) -> List<ContactRequest> = { email ->
        if (email.equals("owner@example.test", true))
            listOf(ContactRequest("peer@example.test", "Pending/mock-listener", "peer"))
        else emptyList()
    },
    private val groupInvitationFixtures: (String) -> List<GroupInvitation> = { emptyList() },
) : MsnpClient {
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableContactRequests = MutableStateFlow<List<ContactRequest>>(emptyList())
    private val mutableGroupInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    private val mutableMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val mutableTyping = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val mutableSessions = MutableStateFlow<List<AccountSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 16)
    private val ids = AtomicLong(System.currentTimeMillis() shl 16)
    private val nudgeLimiter = NudgeRateLimiter()
    override val nudgeCooldowns = nudgeLimiter.cooldowns
    private var presenceJob: Job? = null
    private var signedInEmail = ""
    private var ownDisplayPicture: ByteArray? = null
    private var sessionGeneration = 0L
    private val contactAdditionMutex = Mutex()

    override val contacts = mutableContacts.asStateFlow()
    override val contactRequests = mutableContactRequests.asStateFlow()
    override val groupInvitations = mutableGroupInvitations.asStateFlow()
    override val messages = mutableMessages.asStateFlow()
    override val typing = mutableTyping.asStateFlow()
    override val events = mutableEvents.asSharedFlow()
    override val sessions = mutableSessions.asStateFlow()

    override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> {
        if (signInDelayMillis > 0) delay(signInDelayMillis)
        if (request.email.isBlank() || request.password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password are required."))
        }
        if (MockServerData.rejects(request.email)) {
            return Result.failure(
                IllegalStateException(MockServerData.REJECTED_CREDENTIALS_MESSAGE),
            )
        }

        sessionGeneration++
        nudgeLimiter.selectAccount(AccountIdentity.forServer(request.email, request.serverConfig, true).storageId)
        if (!signedInEmail.equals(request.email, true)) mutableContactRequests.value = requestFixtures(request.email)
        if (!signedInEmail.equals(request.email, true)) mutableGroupInvitations.value = groupInvitationFixtures(request.email)
        if (!signedInEmail.equals(request.email, true)) ownDisplayPicture = null
        signedInEmail = request.email
        mutableContacts.value = MockServerData.contacts()
        mutableSessions.value = listOf(AccountSession("mock-current", "This device", isCurrent = true,
            presence = request.initialPresence))
        mutableMessages.value = emptyMap()
        startPresenceUpdates()
        return Result.success(
            UserProfileSnapshot(
                username = request.email.substringBefore('@').ifBlank { "You" },
                email = request.email,
                presence = request.initialPresence,
                avatarUri = ownDisplayPicture?.let { bytes ->
                    "data:${org.aralozkaya.msngr.protocol.DisplayPictureFormat.detect(bytes)?.mimeType};base64," +
                        org.aralozkaya.msngr.protocol.MsnBase64.encode(bytes)
                },
            ),
        )
    }

    override suspend fun changePresence(presence: Presence) {
        // A live client sends CHG <state> <caps>; the repository updates the local profile.
        delay(80)
        mutableSessions.update { it.map { session -> session.copy(presence = presence) } }
    }

    override suspend fun fetchOwnDisplayPicture() =
        org.aralozkaya.msngr.IncomingDisplayPicture(ownDisplayPicture?.copyOf(), fetched = signedInEmail.isNotBlank())

    override suspend fun adoptIncomingDisplayPicture(image: ByteArray?) { ownDisplayPicture = image?.copyOf() }

    override suspend fun createGroup(name: String): String = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before creating a group." }
        val normalized = validateGroupName(name)
        val generation = sessionGeneration
        if (addContactDelayMillis > 0) delay(addContactDelayMillis)
        check(generation == sessionGeneration && signedInEmail.isNotBlank()) { "This session ended while creating the group." }
        val id = "${java.util.UUID.randomUUID()}@live.com"
        mutableContacts.update { it + Contact(id, normalized, id, Presence.Offline, isGroup = true,
            members = listOf(GroupMember(signedInEmail, role = GroupMemberRole.Owner))) }
        id
    }

    override suspend fun inviteGroupMembers(groupId: String, emails: List<String>, message: String) = contactAdditionMutex.withLock {
        val generation = sessionGeneration
        val group = mutableContacts.value.firstOrNull { it.id == groupId && it.isGroup } ?: error("This group is no longer available.")
        require(group.canInviteGroupMembers(signedInEmail)) { "Only owners and administrators can invite group members." }
        validateGroupInvitees(emails, signedInEmail, group.members)
        // Invitation delivery is simulated locally; pending invitees are not accepted members.
        if (addContactDelayMillis > 0) delay(addContactDelayMillis)
        check(generation == sessionGeneration && signedInEmail.isNotBlank()) { "This session ended while inviting group members." }
        Unit
    }

    override suspend fun leaveGroup(groupId: String) = contactAdditionMutex.withLock {
        val group = mutableContacts.value.firstOrNull { it.id == groupId && it.isGroup } ?: error("This group is no longer available.")
        require(group.canLeaveGroup(signedInEmail)) { "Group owners cannot leave their group." }
        mutableContacts.update { it.filterNot { item -> item.id == groupId } }
        mutableTyping.update { it - groupId }
    }

    override suspend fun respondToGroupInvitation(groupId: String, accept: Boolean) = contactAdditionMutex.withLock {
        val invitation = mutableGroupInvitations.value.firstOrNull { it.id == groupId } ?: error("This group invitation has already been handled.")
        if (accept) mutableContacts.update { it + Contact(invitation.id, invitation.groupName, invitation.id,
            Presence.Offline, isGroup = true, members = listOf(GroupMember(signedInEmail, role = GroupMemberRole.User))) }
        mutableGroupInvitations.update { it.filterNot { item -> item.id == groupId } }
    }

    override suspend fun addContact(email: String, message: String) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before adding a contact." }
        val normalized = validateContactAddress(email, signedInEmail, mutableContacts.value)
        val generation = sessionGeneration
        if (addContactDelayMillis > 0) delay(addContactDelayMillis)
        check(sessionGeneration == generation && signedInEmail.isNotBlank()) {
            "This session ended while adding the contact."
        }
        // Pure local data: no transport, authentication, address-book, or picture requests.
        mutableContacts.update { contacts ->
            val old = contacts.firstOrNull { it.email.equals(normalized, true) }
            contacts.filterNot { it.email.equals(normalized, true) } +
                (old ?: Contact(normalized, normalized.substringBefore('@'), normalized, Presence.Online,
                    statusMessage = "Mock contact · Test data only"))
                    .copy(isForwardListContact = true, presence = Presence.Online)
        }
    }

    override suspend fun deleteContact(contactId: String, block: Boolean) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before deleting a contact." }
        val current = mutableContacts.value.firstOrNull { it.id.equals(contactId, true) }
            ?: throw IllegalArgumentException("This contact is no longer available.")
        require(!current.isGroup) { "Groups cannot be deleted with this action." }
        val generation = sessionGeneration
        if (deleteContactDelayMillis > 0) delay(deleteContactDelayMillis)
        check(sessionGeneration == generation && signedInEmail.isNotBlank()) {
            "This session ended while deleting the contact."
        }
        if (block && !current.isBlocked) blockContact(current.id)
        mutableContacts.update { contacts -> contacts.map {
            if (it.id == current.id) it.copy(isForwardListContact = false) else it
        } }
        mutableTyping.update { it - current.id }
    }

    override suspend fun changeNickname(nickname: String) {
        // A live client sends PRP <trid> MFN <encoded-friendly-name>.
        delay(80)
    }

    override suspend fun respondToContactRequest(requestId: String, accept: Boolean, block: Boolean) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before responding to a contact request." }
        require(!accept || !block) { "An accepted contact cannot be blocked." }
        val request = mutableContactRequests.value.firstOrNull { it.id == requestId.lowercase() }
            ?: error("This contact request has already been handled.")
        val generation = sessionGeneration
        if (contactRequestDelayMillis > 0) delay(contactRequestDelayMillis)
        check(sessionGeneration == generation && signedInEmail.isNotBlank()) { "This session ended while responding to the request." }
        if (accept || block) mutableContacts.update { contacts ->
            val existing = contacts.firstOrNull { it.email.equals(request.email, true) }
            contacts.filterNot { it.email.equals(request.email, true) } +
                (existing ?: Contact(request.id, request.displayName, request.email, Presence.Offline,
                    isForwardListContact = false)).copy(isForwardListContact = accept, isBlocked = block)
        }
        mutableContactRequests.update { it.filterNot { item -> item.id == request.id } }
    }

    override suspend fun changePersonalMessage(statusMessage: String) {
        // A live client sends UUX <trid> <payload-length> with the MSNP personal-message XML.
        delay(80)
    }

    override suspend fun changeDisplayPicture(image: ByteArray?) {
        ownDisplayPicture = image?.copyOf()
        // A live client advertises a type-3 MSNObject in CHG and serves it over MSNSLP/P2P.
        delay(80)
    }

    override suspend fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ) {
        sendStyledMessage(contactId, text, renderedEmoticonRanges, NativeTextStyle())
    }

    override suspend fun sendStyledMessage(
        contactId: String, text: String, renderedEmoticonRanges: List<MsnEmoticonRange>?,
        style: NativeTextStyle,
    ): Result<Unit> {
        val encoded = MessengerPlusTextCodec.normalizeProfileMarkupForChat(
            text.withoutUnicodeEmoji(),
        )
        val normalized = encoded.trimMessengerPlusWhitespace().text
        if (normalized.isEmpty()) return Result.success(Unit)
        appendMessage(
            ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.Me,
                text = normalized,
                timestampMillis = System.currentTimeMillis(),
                renderedEmoticonRanges = renderedEmoticonRanges,
                nativeStyle = style,
            ),
        )
        scope.launch {
            delay(500)
            mutableTyping.update { it + (contactId to true) }
            delay(1_000)
            mutableTyping.update { it + (contactId to false) }
            val group = mutableContacts.value.firstOrNull { it.id == contactId && it.isGroup }
            val groupSender = group?.members?.firstOrNull { !it.email.equals(signedInEmail, ignoreCase = true) }
                ?: group?.members?.firstOrNull()
            val incoming = ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.Contact,
                text = MockServerData.replyTo(normalized.messengerPlusPlainText()),
                timestampMillis = System.currentTimeMillis(),
                senderEmail = groupSender?.email,
                senderName = groupSender?.displayName,
            )
            appendMessage(incoming)
            incrementUnread(contactId)
            mutableEvents.emit(MessengerEvent.IncomingMessage(contactId, incoming.id))
        }
        return Result.success(Unit)
    }

    override suspend fun sendTyping(contactId: String) {
        // Kept as an explicit operation so the live transport can send text/x-msmsgscontrol.
    }

    override suspend fun sendInkMessage(contactId: String, drawing: InkDrawing): Result<Unit> {
        val nonEmpty = drawing.strokes.filter { it.points.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Result.success(Unit)
        appendMessage(
            ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.Me,
                text = "Handwriting",
                timestampMillis = System.currentTimeMillis(),
                inkDrawing = drawing.copy(strokes = nonEmpty),
            ),
        )
        scope.launch {
            delay(500)
            mutableTyping.update { it + (contactId to true) }
            delay(1_000)
            mutableTyping.update { it + (contactId to false) }
            val replyDrawing = InkDrawing(
                canvasWidthPx = 220f,
                canvasHeightPx = 90f,
                strokes = listOf(
                    InkStroke(
                        points = listOf(
                            InkPoint(24f, 45f),
                            InkPoint(58f, 24f),
                            InkPoint(96f, 64f),
                            InkPoint(142f, 28f),
                            InkPoint(188f, 52f),
                        ),
                        colorRgb = 0x0000FF,
                        widthHimetric = 100,
                    ),
                ),
            )
            val group = mutableContacts.value.firstOrNull { it.id == contactId && it.isGroup }
            val groupSender = group?.members?.firstOrNull { !it.email.equals(signedInEmail, ignoreCase = true) }
                ?: group?.members?.firstOrNull()
            val incoming = ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.Contact,
                text = "Handwriting",
                timestampMillis = System.currentTimeMillis(),
                senderEmail = groupSender?.email,
                senderName = groupSender?.displayName,
                inkDrawing = replyDrawing,
            )
            appendMessage(incoming)
            incrementUnread(contactId)
            mutableEvents.emit(MessengerEvent.IncomingMessage(contactId, incoming.id))
        }
        return Result.success(Unit)
    }

    private fun unusedSendTypingMarker() {
        // Kept as an explicit operation so the live transport can send text/x-msmsgscontrol.
    }

    override suspend fun sendNudge(contactId: String) {
        sendNudgeWithResult(contactId)
    }

    override suspend fun sendNudgeWithResult(contactId: String): Result<Unit> = runCatching {
        val reservation = nudgeLimiter.reserveSend(contactId)
        appendMessage(
            ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.System,
                text = "You sent a nudge.",
                timestampMillis = System.currentTimeMillis(),
                isNudge = true,
            ),
        )
        nudgeLimiter.finishSend(reservation, succeeded = true)
        scope.launch {
            delay(900)
            val incoming = ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.System,
                text = "You received a nudge!",
                timestampMillis = System.currentTimeMillis(),
                isNudge = true,
            )
            appendMessage(incoming)
            if (nudgeLimiter.allowReceivedAlert(contactId, contactId)) {
                mutableEvents.emit(MessengerEvent.IncomingNudge(contactId))
            }
        }
        Unit
    }

    override suspend fun blockContact(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) {
                    contact.copy(
                        presence = Presence.Offline,
                        unreadCount = 0,
                        isMarkedUnread = false,
                        isBlocked = true,
                    )
                } else {
                    contact
                }
            }
        }
    }

    override suspend fun unblockContact(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) contact.copy(isBlocked = false) else contact
            }
        }
    }

    override fun markRead(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { if (it.id == contactId) it.copy(unreadCount = 0, isMarkedUnread = false) else it }
        }
    }

    override fun markUnread(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map {
                if (it.id == contactId) it.copy(isMarkedUnread = true) else it
            }
        }
    }

    override fun clearMessages(contactId: String) {
        mutableMessages.update { it - contactId }
        markRead(contactId)
    }

    override fun clearMessages() {
        mutableMessages.value = emptyMap()
        mutableContacts.update { contacts -> contacts.map { it.copy(unreadCount = 0, isMarkedUnread = false) } }
    }

    override fun close() {
        mutableContactRequests.value = emptyList()
        mutableGroupInvitations.value = emptyList()
        sessionGeneration++
        signedInEmail = ""
        mutableSessions.value = emptyList()
        presenceJob?.cancel()
        presenceJob = null
        mutableContacts.value = emptyList()
        mutableMessages.value = emptyMap()
        mutableTyping.value = emptyMap()
    }

    private fun appendMessage(message: ChatMessage) {
        mutableMessages.update { current ->
            current + (message.contactId to (current[message.contactId].orEmpty() + message))
        }
    }

    private fun incrementUnread(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) contact.copy(unreadCount = contact.unreadCount + 1)
                else contact
            }
        }
    }

    private fun startPresenceUpdates() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            var cursor = 0
            val cycle = listOf(Presence.Online, Presence.Busy, Presence.Away, Presence.Offline)
            while (isActive) {
                delay(12_000)
                val unblocked = mutableContacts.value.filterNot(Contact::isBlocked)
                if (unblocked.isEmpty()) continue
                val contact = unblocked[cursor % unblocked.size]
                val next = cycle[(cycle.indexOf(contact.presence) + 1) % cycle.size]
                mutableContacts.update { list ->
                    list.map { if (it.id == contact.id) it.copy(presence = next) else it }
                }
                mutableEvents.emit(MessengerEvent.PresenceChanged(contact.id, next, contact.presence))
                cursor++
            }
        }
    }

}

class MessengerRepository(
    private val client: MsnpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val awaitNetworkAccess: suspend () -> Unit = {},
    private val historyStore: ConversationHistoryStore = InMemoryConversationHistoryStore(),
    private val messengerPlusEnabled: () -> Boolean = { true },
    private val draftStore: ConversationDraftStore = ConversationDraftStore(),
    private val onConversationRead: (String) -> Unit = {},
    private val useMockServer: Boolean = client is MockMsnp18Client,
    private val onRemoteSignOut: () -> Unit = {},
    private val signOutOtherSessionsOnSignIn: (AccountIdentity) -> Boolean = { false },
    private val styleStore: ConversationStyleStore = ConversationStyleStore(),
) {
    private var nicknameJob: Job? = null
    private var personalMessageJob: Job? = null
    private var displayPictureJob: Job? = null
    private var automaticReconnectJob: Job? = null
    private val connectionMutex = Mutex()
    private val historyMutationMutex = Mutex()
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
    val drafts: StateFlow<Map<String, String>> = draftStore.drafts
    val textStyles = styleStore.styles
    val contacts: StateFlow<List<Contact>> = client.contacts
    val nudgeCooldowns: StateFlow<Map<String, Long>> = client.nudgeCooldowns
    val pendingNudgeShakes: StateFlow<Set<String>> = mutablePendingNudgeShakes.asStateFlow()
    val contactRequests: StateFlow<List<ContactRequest>> = client.contactRequests
    val groupInvitations: StateFlow<List<GroupInvitation>> = client.groupInvitations
    val groupInvitationInbox: StateFlow<GroupInvitationInbox> = combine(client.groupInvitations, mutableRequestAccount) { invitations, account ->
        GroupInvitationInbox(account, if (account == null) emptyList() else invitations)
    }.stateIn(scope, SharingStarted.Eagerly, GroupInvitationInbox())
    val contactRequestInbox: StateFlow<ContactRequestInbox> = combine(client.contactRequests, mutableRequestAccount) { requests, account ->
        ContactRequestInbox(account, if (account == null) emptyList() else requests)
    }.stateIn(scope, SharingStarted.Eagerly, ContactRequestInbox())
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

    init {
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event is MessengerEvent.IncomingNudge) {
                    val key = normalizeNudgeShakeKey(event.contactId)
                    if (key.isNotEmpty()) {
                        mutablePendingNudgeShakes.update { it + key }
                    }
                } else if (event is MessengerEvent.OwnDisplayPictureChanged && event.account == signedInAccountIdentity) {
                    event.personalMessage?.let { status -> mutableProfile.update { it.copy(statusMessage = status) } }
                } else if (event is MessengerEvent.SignedOut) {
                    scope.launch {
                        connectionMutex.withLock {
                            logout()
                            onRemoteSignOut()
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
        scope.launch {
            client.messages.collect { messages ->
                historyMutationMutex.withLock {
                    if (lastSignInRequest != null && messages == client.messages.value) {
                        historyStore.merge(messages)
                    }
                }
            }
        }
    }

    suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> =
        connectionMutex.withLock {
            // Package replacement and the launcher can restore the same account concurrently.
            if (
                lastSignInRequest == request &&
                mutableConnectionIssue.value == null
            ) {
                return@withLock Result.success(mutableProfile.value)
            }
            val hadSignedInSession = lastSignInRequest != null
            val requestAccount = AccountIdentity.forServer(request.email, request.serverConfig, useMockServer).storageId
            if (mutableRequestAccount.value != requestAccount) {
                mutableRequestAccount.value = null
                mutablePendingNudgeShakes.value = emptySet()
            }
            mutableSignOutReason.value = null
            val result = signInClient(request)
            result.getOrNull()?.let { profile ->
                withContext(Dispatchers.IO) {
                    historyMutationMutex.withLock {
                        val account = AccountIdentity.forServer(request.email, request.serverConfig, useMockServer)
                        draftStore.selectAccount(account.storageId)
                        styleStore.selectAccount(account.storageId)
                        historyStore.selectAccount(account.storageId)
                        historyStore.merge(client.messages.value)
                    }
                }
                lastSignInRequest = request
                mutableProfile.value = profile
                mutableRequestAccount.value = requestAccount
                mutableConnectionIssue.value = null
                startOfflineMessageDelivery(request)
            }
            if (result.isFailure && hadSignedInSession) {
                mutableConnectionIssue.value = result.exceptionOrNull()
                    ?.message
                    ?.takeIf(String::isNotBlank)
                    ?: "Connection interrupted"
            }
            result
        }

    suspend fun reconnect(): Result<UserProfileSnapshot> = connectionMutex.withLock {
        val request = lastSignInRequest
            ?: return@withLock Result.failure(
                IllegalStateException("The previous sign-in session is no longer available."),
            )
        val reconnectRequest = request.copy(initialPresence = mutableProfile.value.presence)
        val result = signInClient(reconnectRequest)
        result.getOrNull()?.let { profile ->
            withContext(Dispatchers.IO) {
                historyMutationMutex.withLock {
                    historyStore.selectAccount(AccountIdentity.forServer(
                        reconnectRequest.email, reconnectRequest.serverConfig, useMockServer,
                    ).storageId)
                    historyStore.merge(client.messages.value)
                }
            }
            lastSignInRequest = reconnectRequest
            mutableProfile.value = profile
            mutableConnectionIssue.value = null
            startOfflineMessageDelivery(reconnectRequest)
        }
        result
    }

    private suspend fun signInClient(request: SignInRequest): Result<UserProfileSnapshot> {
        val result = client.signIn(request)
        if (result.isFailure || !signOutOtherSessionsOnSignIn(
                AccountIdentity.forServer(request.email, request.serverConfig, useMockServer),
            )) return result
        return try {
            client.logOutOtherSessions()
            result
        } catch (cancelled: CancellationException) {
            client.close()
            throw cancelled
        } catch (error: Exception) {
            client.close()
            Result.failure(IllegalStateException(
                "Could not sign you out of all other places. ${error.message.orEmpty()}", error,
            ))
        }
    }

    private fun startOfflineMessageDelivery(request: SignInRequest) {
        val account = AccountIdentity.forServer(request.email, request.serverConfig, useMockServer)
        client.startOfflineMessageDelivery { message ->
            withContext(Dispatchers.IO) {
                historyMutationMutex.withLock {
                    check(lastSignInRequest?.let {
                        AccountIdentity.forServer(it.email, it.serverConfig, useMockServer)
                    } == account) { "The account changed while offline messages were downloading." }
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
    ) {
        mutableProfile.value = profile
        if (publishStoredSettings) {
            nicknameJob?.cancel()
            nicknameJob = scope.launch {
                client.changeNickname(
                    profile.username.trim().withoutMessengerPlusFormattingIfDisabled(),
                )
            }
            personalMessageJob?.cancel()
            personalMessageJob = scope.launch {
                client.changePersonalMessage(
                    profile.statusMessage.trim().withoutMessengerPlusFormattingIfDisabled(),
                )
            }
            if (publishDisplayPicture && (displayPictureBytes != null || profile.avatarUri == null)) {
                displayPictureJob?.cancel()
                displayPictureJob = scope.launch {
                    runCatching { client.changeDisplayPicture(displayPictureBytes) }
                }
            }
        }
    }

    fun setPresence(presence: Presence) {
        mutableProfile.update { it.copy(presence = presence) }
        scope.launch { client.changePresence(presence) }
    }

    fun setStatusMessage(statusMessage: String) {
        val normalized = statusMessage
            .replace('\r', ' ')
            .replace('\n', ' ')
            .withoutMessengerPlusFormattingIfDisabled()
        if (mutableProfile.value.statusMessage == normalized) return
        mutableProfile.update { it.copy(statusMessage = normalized) }
        personalMessageJob?.cancel()
        personalMessageJob = scope.launch {
            delay(PERSONAL_MESSAGE_DEBOUNCE_MILLIS)
            client.changePersonalMessage(normalized.trim())
        }
    }

    suspend fun updateProfile(
        username: String,
        avatarUri: String?,
        displayPictureBytes: ByteArray? = null,
        updateDisplayPicture: Boolean? = null,
    ): Result<Unit> = connectionMutex.withLock { runCatching {
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
        mutableProfile.update {
            it.copy(username = normalizedUsername, avatarUri = if (displayPictureChanged) avatarUri else it.avatarUri)
        }
    } }

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
        mutableProfile.update { it.copy(avatarUri = avatarUri) }
    }

    fun conversation(contactId: String): Flow<ConversationState> =
        combine(historyStore.messages, client.typing, historyStore.pageStates) {
                messages, typing, pageStates ->
            val pageState = pageStates[contactId] ?: ConversationHistoryPageState()
            ConversationState(
                messages = messages[contactId].orEmpty(),
                isContactTyping = typing[contactId] == true,
                isHistoryLoaded = pageState.isInitialPageLoaded,
                canLoadOlderMessages = pageState.canLoadOlder,
            )
        }

    suspend fun loadConversation(contactId: String) = withContext(Dispatchers.IO) {
        historyMutationMutex.withLock { historyStore.loadLatest(contactId) }
    }

    suspend fun loadOlderMessages(contactId: String) = withContext(Dispatchers.IO) {
        historyMutationMutex.withLock { historyStore.loadOlder(contactId) }
    }

    fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>? = null,
    ) {
        val outgoing = prepareOutgoingMessage(text, renderedEmoticonRanges)
        val style = textStyles.value[contactId.lowercase(java.util.Locale.ROOT)] ?: NativeTextStyle()
        scope.launch { client.sendStyledMessage(contactId, outgoing.first, outgoing.second, style) }
    }

    suspend fun sendMessageNow(contactId: String, text: String): Result<Unit> =
        client.sendStyledMessage(
            contactId,
            text.withoutMessengerPlusFormattingIfDisabled(),
            null,
            textStyles.value[contactId.lowercase(java.util.Locale.ROOT)] ?: NativeTextStyle(),
        )

    fun sendInk(contactId: String, drawing: InkDrawing) {
        scope.launch { client.sendInkMessage(contactId, drawing) }
    }

    suspend fun sendInkNow(contactId: String, drawing: InkDrawing): Result<Unit> =
        client.sendInkMessage(contactId, drawing)

    fun retryMessage(contactId: String, messageId: Long) {
        scope.launch {
            val message = messageSnapshot(contactId, messageId) ?: return@launch
            if (message.author == MessageAuthor.Me && !message.isNudge &&
                !message.isSending && message.deliveryError != null
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

    fun updateDraft(contactId: String, text: String) = draftStore.update(contactId, text)
    fun updateTextStyle(contactId: String, style: NativeTextStyle) = styleStore.update(contactId, style)
    fun markTextSizeNoticeShown(contactId: String): Boolean = styleStore.markSizeNoticeShown(contactId)

    fun sendTyping(contactId: String) {
        scope.launch { client.sendTyping(contactId) }
    }

    fun sendNudge(contactId: String) {
        scope.launch { client.sendNudge(contactId) }
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
        scope.launch { runCatching { client.blockContact(contactId) } }
    }

    fun unblockContact(contactId: String) {
        scope.launch { runCatching { client.unblockContact(contactId) } }
    }

    suspend fun addContact(email: String, message: String = ""): Result<Unit> = connectionMutex.withLock {
        try {
            check(lastSignInRequest != null && mutableSignOutReason.value == null) { "Sign in before adding a contact." }
            client.addContact(email, message)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun createGroup(name: String): Result<String> = groupOperation { client.createGroup(name) }
    suspend fun inviteGroupMembers(groupId: String, emails: List<String>, message: String = ""): Result<Unit> = groupOperation { client.inviteGroupMembers(groupId, emails, message) }
    suspend fun leaveGroup(groupId: String): Result<Unit> = groupOperation {
        client.leaveGroup(groupId)
        clearPendingNudgeShake(groupId)
        onConversationRead(groupId)
    }
    suspend fun refreshGroupInvitations(): Result<Unit> = groupOperation { client.refreshGroupInvitations() }
    suspend fun respondToGroupInvitation(groupId: String, accept: Boolean): Result<Unit> = groupOperation { client.respondToGroupInvitation(groupId, accept) }

    private suspend fun <T> groupOperation(operation: suspend () -> T): Result<T> = connectionMutex.withLock {
        try {
            check(lastSignInRequest != null && mutableSignOutReason.value == null) { "Sign in before updating groups." }
            Result.success(operation())
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun deleteContact(contactId: String, block: Boolean = false): Result<Unit> = connectionMutex.withLock {
        try {
            check(lastSignInRequest != null && mutableSignOutReason.value == null) { "Sign in before deleting a contact." }
            client.deleteContact(contactId, block)
            clearPendingNudgeShake(contactId)
            onConversationRead(contactId)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun refreshContactRequests(): Result<Unit> = connectionMutex.withLock {
        try {
            check(lastSignInRequest != null) { "Sign in to load contact requests." }
            client.refreshContactRequests()
            Result.success(Unit)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun respondToContactRequest(requestId: String, accept: Boolean, block: Boolean = false): Result<Unit> = connectionMutex.withLock {
        try {
            check(lastSignInRequest != null && mutableSignOutReason.value == null) { "Sign in before responding to a contact request." }
            client.respondToContactRequest(requestId, accept, block)
            Result.success(Unit)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    fun markRead(contactId: String, dismissNotification: Boolean = true) {
        client.markRead(contactId)
        if (dismissNotification) onConversationRead(contactId)
    }

    fun markUnread(contactId: String) = client.markUnread(contactId)

    suspend fun clearConversationHistory(contactId: String) = withContext(Dispatchers.IO) {
        historyMutationMutex.withLock {
            clearPendingNudgeShake(contactId)
            historyStore.clearContact(contactId)
            client.clearMessages(contactId)
        }
    }

    suspend fun clearConversationHistory() {
        withContext(Dispatchers.IO) {
            historyMutationMutex.withLock {
                mutablePendingNudgeShakes.value = emptySet()
                client.clearMessages()
                historyStore.clearAll()
            }
        }
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

    fun messageSnapshot(contactId: String, messageId: Long): ChatMessage? =
        client.messages.value[contactId]?.firstOrNull { it.id == messageId }
            ?: historyStore.messages.value[contactId]?.firstOrNull { it.id == messageId }

    fun logout() {
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
        draftStore.deselectAccount()
        styleStore.deselectAccount()
        mutableConnectionIssue.value = null
        client.close()
        mutableProfile.value = UserProfileSnapshot("You", "", Presence.Online)
    }

    /** End remote sessions first; keep this session usable if any remote action fails. */
    suspend fun logOutSessions(sessionIds: Set<String>): Result<Unit> = connectionMutex.withLock {
        runCatching {
            check(hasSignedInSession) { "This session has already ended." }
            val selected = sessions.value.filter { it.id in sessionIds }
            for (session in selected.filterNot(AccountSession::isCurrent)) {
                if (sessions.value.any { it.id == session.id }) client.logOutSession(session.id)
            }
            if (selected.any(AccountSession::isCurrent)) logout()
        }
    }

    private fun startAutomaticReconnect() {
        if (
            automaticReconnectJob?.isActive == true ||
            lastSignInRequest == null ||
            !appInForeground && !persistentBackgroundConnection
        ) {
            return
        }
        automaticReconnectJob = scope.launch {
            var attempt = 0
            while (isActive && mutableConnectionIssue.value != null && lastSignInRequest != null) {
                delay(reconnectDelayMillis(attempt++))
                awaitNetworkAccess()
                if (mutableConnectionIssue.value == null) break
                reconnect()
            }
        }
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
