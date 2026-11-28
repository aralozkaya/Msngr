package org.aralozkaya.msngr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aralozkaya.msngr.AccountIdentity
import org.aralozkaya.msngr.MessengerPlusTextCodec
import org.aralozkaya.msngr.MsnEmoticonRange
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import org.aralozkaya.msngr.trimMessengerPlusWhitespace
import org.aralozkaya.msngr.withoutUnicodeEmoji
import java.util.concurrent.atomic.AtomicLong

object MockServerData {
    const val ACCOUNT_EMAIL = "mock-user@example.com"
    const val ACCOUNT_PASSWORD = "mock-password"
    const val ACCOUNT_NICKNAME = "Mock Profile"
    const val ACCOUNT_STATUS_MESSAGE = "Testing with mock data"
    const val REJECTED_CREDENTIALS_MESSAGE =
        "The community server rejected these credentials."

    fun rejects(email: String): Boolean = email.contains("fail", ignoreCase = true)

    fun contacts(): List<Contact> = listOf(
        Contact("selin", "Selin", "selin@msngr.dev", Presence.Online, "I love MSN!", unreadCount = 2,
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        Contact("mert", "Mert", "mert@msngr.dev", Presence.Busy, "Heads down today",
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        Contact("derya", "Derya", "derya@msngr.dev", Presence.Away, "At the studio", unreadCount = 1,
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        Contact("can", null, "can@msngr.dev", Presence.Offline, "Ping me later"),
        Contact("zeynep", "Zeynep", "zeynep@msngr.dev", Presence.Online, "Coffee first", unreadCount = 3,
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        Contact("ece", "Ece", "ece@msngr.dev", Presence.Online, "Shipping build assets",
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        Contact("deniz", "Deniz", "deniz@msngr.dev", Presence.Busy, "In a call", unreadCount = 2,
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
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
    private val photoCacheDirectory: java.io.File? = null,
    private val transferStepDelayMillis: Long = 350L,
) : MsnpClient {
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableCategories = MutableStateFlow<List<ContactCategory>>(emptyList())
    private val mutableContactRequests = MutableStateFlow<List<ContactRequest>>(emptyList())
    private val mutableGroupInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    private val mutableMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val mutableTyping = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val mutableSessions = MutableStateFlow<List<AccountSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 16)
    private val sessionLock = Any()
    private val ids = AtomicLong(System.currentTimeMillis() shl 16)
    private val nudgeLimiter = NudgeRateLimiter()
    override val nudgeCooldowns = nudgeLimiter.cooldowns
    private fun newSessionScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    @Volatile private var sessionScope = newSessionScope()
    private var signedInEmail = ""
    private var ownDisplayPicture: ByteArray? = null
    internal var currentMedia: CurrentMedia? = null
        private set
    @Volatile private var sessionGeneration = 0L
    private val contactAdditionMutex = Mutex()

    override val contacts = mutableContacts.asStateFlow()
    override val categories = mutableCategories.asStateFlow()
    override val contactRequests = mutableContactRequests.asStateFlow()
    override val groupInvitations = mutableGroupInvitations.asStateFlow()
    override val messages = mutableMessages.asStateFlow()
    override val typing = mutableTyping.asStateFlow()
    override val events = mutableEvents.asSharedFlow()
    override val sessions = mutableSessions.asStateFlow()
    private val mutableGameSessions = MutableStateFlow<Map<String, GameSession>>(emptyMap())
    override val gameSessions = mutableGameSessions.asStateFlow()
    private val mutablePhotoShares = MutableStateFlow<Map<String, PhotoShare>>(emptyMap())
    override val photoShares = mutablePhotoShares.asStateFlow()
    private val mockFileTransferJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mockPhotoShareJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mockTempPhotoFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.File>()

    override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> {
        if (signInDelayMillis > 0) delay(signInDelayMillis)
        if (request.email.isBlank() || request.password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password are required."))
        }
        if (MockServerData.rejects(request.email)) {
            return Result.failure(
                CredentialsRejectedException(MockServerData.REJECTED_CREDENTIALS_MESSAGE),
            )
        }

        return Result.success(openSession(request))
    }

    private fun openSession(request: SignInRequest): UserProfileSnapshot = synchronized(sessionLock) {
        sessionScope.cancel()
        sessionScope = newSessionScope()
        sessionGeneration++
        mutableTyping.value = emptyMap()
        mutableGameSessions.value = emptyMap()
        nudgeLimiter.selectAccount(AccountIdentity.forServer(request.email, request.serverConfig, true).storageId)
        if (!signedInEmail.equals(request.email, true)) {
            mutableContactRequests.value = requestFixtures(request.email)
            mutableGroupInvitations.value = groupInvitationFixtures(request.email)
            mutableCategories.value = emptyList()
            ownDisplayPicture = null
        }
        currentMedia = null
        signedInEmail = request.email
        mutableContacts.value = MockServerData.contacts()
        mutableSessions.value = listOf(AccountSession("mock-current", "This device", isCurrent = true,
            presence = request.initialPresence))
        mutableMessages.value = emptyMap()
        resetMockTransfers()
        startPresenceUpdates()
        UserProfileSnapshot(
            username = request.email.substringBefore('@').ifBlank { "You" },
            email = request.email,
            presence = request.initialPresence,
            avatarUri = ownDisplayPicture?.let { bytes ->
                "data:${org.aralozkaya.msngr.protocol.DisplayPictureFormat.detect(bytes)?.mimeType};base64," +
                    org.aralozkaya.msngr.protocol.MsnBase64.encode(bytes)
            },
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

    override suspend fun createCategory(name: String, memberIds: List<String>): String = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before creating a category." }
        val normalized = validateCategoryName(name, mutableCategories.value)
        val id = java.util.UUID.randomUUID().toString()
        mutableCategories.update { it + ContactCategory(id = id, name = normalized, serverGuid = id) }
        val targetMembers = memberIds.map(String::lowercase).toSet()
        if (targetMembers.isNotEmpty()) {
            mutableContacts.update { list ->
                list.map { contact ->
                    if (contact.id.lowercase() in targetMembers) {
                        contact.copy(categoryIds = (contact.categoryIds - OTHER_CONTACTS_CATEGORY_ID) + id)
                    } else contact
                }
            }
        }
        id
    }

    override suspend fun renameCategory(categoryId: String, newName: String) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before editing a category." }
        val existing = mutableCategories.value.firstOrNull { it.id == categoryId }
            ?: error("This category no longer exists.")
        require(existing.isEditable) { "This category cannot be edited." }
        val normalized = validateCategoryName(newName, mutableCategories.value, excludeCategoryId = categoryId)
        mutableCategories.update { list ->
            list.map { if (it.id == categoryId) it.copy(name = normalized) else it }
        }
    }

    override suspend fun updateCategoryMembers(categoryId: String, memberIds: Set<String>) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before updating a category." }
        val existing = mutableCategories.value.firstOrNull { it.id == categoryId }
            ?: error("This category no longer exists.")
        require(existing.isEditable) { "This category cannot be edited." }
        val normalizedMembers = memberIds.map(String::lowercase).toSet()
        mutableContacts.update { list ->
            list.map { contact ->
                val shouldContain = contact.id.lowercase() in normalizedMembers
                val next = if (shouldContain) {
                    (contact.categoryIds - OTHER_CONTACTS_CATEGORY_ID) + categoryId
                } else {
                    contact.categoryIds - categoryId
                }
                if (next == contact.categoryIds) contact else contact.copy(categoryIds = next)
            }
        }
    }

    override suspend fun deleteCategory(categoryId: String) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before deleting a category." }
        val existing = mutableCategories.value.firstOrNull { it.id == categoryId }
            ?: error("This category no longer exists.")
        require(existing.isDeletable) { "This category cannot be deleted." }
        mutableCategories.update { list -> list.filterNot { it.id == categoryId } }
        mutableContacts.update { list ->
            list.map { contact ->
                if (categoryId in contact.categoryIds) {
                    contact.copy(categoryIds = contact.categoryIds - categoryId)
                } else contact
            }
        }
    }

    override suspend fun setContactCategories(contactId: String, categoryIds: Set<String>) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before updating categories." }
        val validCustomIds = mutableCategories.value.map(ContactCategory::id).toSet()
        val effective = categoryIds.filter {
            it == FAVORITES_CATEGORY_ID || it in validCustomIds
        }.toSet()
        mutableContacts.update { list ->
            list.map { contact ->
                if (!contact.id.equals(contactId, ignoreCase = true)) contact
                else contact.copy(categoryIds = effective)
            }
        }
    }

    override suspend fun setContactFavorite(contactId: String, favorite: Boolean) = contactAdditionMutex.withLock {
        check(signedInEmail.isNotBlank()) { "Sign in before updating favorites." }
        mutableContacts.update { list ->
            list.map { contact ->
                if (!contact.id.equals(contactId, ignoreCase = true)) contact
                else {
                    val next = if (favorite) contact.categoryIds + FAVORITES_CATEGORY_ID
                    else contact.categoryIds - FAVORITES_CATEGORY_ID
                    contact.copy(categoryIds = next)
                }
            }
        }
    }

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
        val invitees = validateGroupInvitees(emails, signedInEmail, group.members)
        // Invitation delivery is simulated locally; pending invitees are not accepted members.
        if (addContactDelayMillis > 0) delay(addContactDelayMillis)
        check(generation == sessionGeneration && signedInEmail.isNotBlank()) { "This session ended while inviting group members." }
        mutableContacts.update { contacts -> contacts.map { contact ->
            if (contact.id != groupId) contact else contact.copy(members = contact.members + invitees.map {
                GroupMember(it, role = GroupMemberRole.User, isPending = true)
            })
        } }
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
                    .copy(
                        isForwardListContact = true,
                        presence = Presence.Online,
                        clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
                        extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong(),
                    )
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

    override suspend fun changeCurrentMedia(media: CurrentMedia?) {
        currentMedia = media
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
    ): Result<Unit> = synchronized(sessionLock) {
        val encoded = MessengerPlusTextCodec.normalizeProfileMarkupForChat(
            text.withoutUnicodeEmoji(),
        )
        val normalized = if (org.aralozkaya.msngr.protocol.MsnEmotionSound.parse(encoded) != null) encoded
            else encoded.trimMessengerPlusWhitespace().text
        if (normalized.isEmpty()) return@synchronized Result.success(Unit)
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
        scheduleReply(contactId, MockServerData.replyTo(normalized.messengerPlusPlainText()))
        Result.success(Unit)
    }

    private inline fun inSession(generation: Long, action: () -> Unit): Unit = synchronized(sessionLock) {
        if (generation == sessionGeneration) action()
    }

    private fun scheduleReply(contactId: String, text: String, ink: InkDrawing? = null) {
        val generation = sessionGeneration
        sessionScope.launch {
            delay(500)
            inSession(generation) { mutableTyping.update { it + (contactId to true) } }
            delay(1_000)
            inSession(generation) { mutableTyping.update { it + (contactId to false) } }
            inSession(generation) {
                val group = mutableContacts.value.firstOrNull { it.id == contactId && it.isGroup }
                val groupSender = group?.joinedMembers?.firstOrNull { !it.email.equals(signedInEmail, ignoreCase = true) }
                    ?: group?.joinedMembers?.firstOrNull()
                val incoming = ChatMessage(
                    id = ids.incrementAndGet(),
                    contactId = contactId,
                    author = MessageAuthor.Contact,
                    text = text,
                    inkDrawing = ink,
                    timestampMillis = System.currentTimeMillis(),
                    senderEmail = groupSender?.email,
                    senderName = groupSender?.displayName,
                )
                appendMessage(incoming)
                incrementUnread(contactId)
                publishSessionEvent(MessengerEvent.IncomingMessage(contactId, incoming.id))
            }
        }
    }

    private fun publishSessionEvent(event: MessengerEvent) {
        if (!mutableEvents.tryEmit(event)) {
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) { mutableEvents.emit(event) }
        }
    }

    override suspend fun sendTyping(contactId: String) {
        // Kept as an explicit operation so the live transport can send text/x-msmsgscontrol.
    }

    override suspend fun sendWink(contactId: String, wink: org.aralozkaya.msngr.protocol.WinkReference, bytes: ByteArray): Result<Unit> = runCatching {
        require(wink.metadata.matches(bytes))
        requireNotNull(org.aralozkaya.msngr.protocol.MsnWinkPackage.parse(bytes))
        val contact = mutableContacts.value.firstOrNull { it.id == contactId }
        check(contact?.isBlocked != true && contact?.presence?.isOnline == true)
        check(contact.recipientFeatures(signedInEmail).winks) {
            if (contact.isGroup) "All online group members must support winks."
            else "This contact's client does not support winks."
        }
        appendMessage(ChatMessage(ids.incrementAndGet(), contactId, MessageAuthor.Me, "Wink: ${wink.name}",
            System.currentTimeMillis(), wink = wink, autoPlayWink = true))
    }

    override suspend fun sendVoiceClip(contactId: String, clip: org.aralozkaya.msngr.protocol.VoiceClip): Result<Unit> = runCatching {
        val contact = mutableContacts.value.firstOrNull { it.id == contactId }
        check(contact?.isGroup != true) { "Voice clips are not supported in group chats." }
        check(contact?.isBlocked != true && contact?.presence != Presence.Offline) { "Voice clips require an online contact." }
        check(contact?.recipientFeatures(signedInEmail)?.voiceClips == true) {
            "This contact's client does not support voice clips."
        }
        requireNotNull(org.aralozkaya.msngr.protocol.MsnVoiceClip.parse(clip.bytes()))
        appendMessage(ChatMessage(ids.incrementAndGet(), contactId, MessageAuthor.Me, "Voice clip",
            System.currentTimeMillis(), voiceClip = clip))
    }

    override suspend fun sendInkMessage(contactId: String, drawing: InkDrawing): Result<Unit> = synchronized(sessionLock) {
        val nonEmpty = drawing.strokes.filter { it.points.isNotEmpty() }
        if (nonEmpty.isEmpty()) return@synchronized Result.success(Unit)
        val contact = mutableContacts.value.firstOrNull { it.id == contactId }
        if (contact == null || !contact.presence.isOnline || contact.isBlocked ||
            contact.recipientFeatures(signedInEmail).inkFormat == null
        ) {
            return@synchronized Result.failure(IllegalStateException(
                if (contact?.isGroup == true) "All online group members must support handwriting."
                else "This contact's client does not support handwriting.",
            ))
        }
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
        scheduleReply(contactId, "Handwriting", replyDrawing)
        Result.success(Unit)
    }

    override suspend fun sendNudge(contactId: String) {
        sendNudgeWithResult(contactId)
    }

    private inline fun <T> sessionOperation(action: () -> T): Result<T> =
        runCatching { synchronized(sessionLock, action) }

    override suspend fun sendNudgeWithResult(contactId: String): Result<Unit> = sessionOperation {
        val contact = mutableContacts.value.firstOrNull { it.id == contactId }
        check(contact != null && !contact.isBlocked && contact.presence.isOnline) {
            "Nudges require an online contact."
        }
        check(contact.recipientFeatures(signedInEmail).nudges) {
            if (contact.isGroup) "All online group members must support nudges."
            else "This contact's client does not support nudges."
        }
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
        val generation = sessionGeneration
        sessionScope.launch {
            delay(900)
            inSession(generation) {
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
                    publishSessionEvent(MessengerEvent.IncomingNudge(contactId))
                }
            }
        }
    }

    override suspend fun blockContact(contactId: String) {
        mutableTyping.update { it - contactId }
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) {
                    contact.copy(
                        presence = Presence.Offline,
                        unreadCount = 0,
                        isMarkedUnread = false,
                        isBlocked = true,
                        clientCapabilities = 0L,
                        extendedCapabilities = 0L,
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
                if (contact.id == contactId) contact.copy(isBlocked = false).withGroupPresence(signedInEmail) else contact
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

    override suspend fun startGame(contactId: String, entry: GameCatalogEntry): Result<Unit> = runCatching {
        val key = contactId.lowercase()
        val contact = mutableContacts.value.firstOrNull { it.id.equals(key, true) || it.email.equals(key, true) }
        check(contact != null && !contact.isGroup && !contact.isBlocked && contact.presence != Presence.Offline &&
            contact.recipientFeatures(signedInEmail).activities) {
            "This contact is unavailable for games and activities."
        }
        val sessionId = MsnpP2pUtil.randomGuid()
        val initial = GameSession(
            id = sessionId,
            appId = entry.appId,
            appName = entry.name,
            entry = entry,
            outgoing = true,
            status = GameSessionStatus.Invited,
        )
        mutableGameSessions.update { it + (contact.id to initial) }
        appendMessage(
            ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contact.id,
                author = MessageAuthor.System,
                text = "Game invitation sent: ${entry.name}.",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        sessionScope.launch {
            delay(650)
            mutableGameSessions.update { current ->
                val active = current[contact.id]?.takeIf { it.id == sessionId && it.status == GameSessionStatus.Invited }
                    ?: return@update current
                current + (contact.id to active.copy(status = GameSessionStatus.Active, remoteAppLoaded = true))
            }
        }
    }

    override suspend fun acceptGame(contactId: String, id: String): Result<Unit> = runCatching {
        val key = contactId.lowercase()
        mutableGameSessions.update { current ->
            val session = current[key]?.takeIf { it.id == id } ?: return@update current
            current + (key to session.copy(status = GameSessionStatus.Active, remoteAppLoaded = true))
        }
    }

    override suspend fun stopGame(contactId: String, id: String): Result<Unit> = runCatching {
        val key = contactId.lowercase()
        var closedSession: GameSession? = null
        mutableGameSessions.update { current ->
            val session = current[key]?.takeIf { it.id == id } ?: return@update current
            val nextStatus = if (!session.outgoing && session.status == GameSessionStatus.Invited) {
                GameSessionStatus.Declined
            } else {
                GameSessionStatus.Stopped
            }
            val updated = session.copy(status = nextStatus)
            closedSession = updated
            current + (key to updated)
        }
        closedSession?.let { session ->
            appendMessage(
                ChatMessage(
                    id = ids.incrementAndGet(),
                    contactId = key,
                    author = MessageAuthor.System,
                    text = if (session.status == GameSessionStatus.Declined) {
                        "${session.appName} invitation declined."
                    } else {
                        "You closed ${session.appName}."
                    },
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun initializeGameChannel(contactId: String, id: String): Result<Unit> = runCatching {
        val key = contactId.lowercase()
        mutableGameSessions.update { current ->
            val session = current[key]?.takeIf { it.id == id } ?: return@update current
            current + (key to session.copy(localChannelInitialized = true, remoteAppLoaded = true))
        }
    }

    override suspend fun sendGameData(contactId: String, id: String, payload: String): Result<Unit> = runCatching {
        val key = contactId.lowercase()
        val session = mutableGameSessions.value[key]?.takeIf { it.id == id && it.status == GameSessionStatus.Active }
            ?: return@runCatching
        if (session.appId == 101000 && payload.length == 1 && payload[0] in '0'..'8') {
            sessionScope.launch {
                delay(450)
                mutableGameSessions.update { current ->
                    val latest = current[key]?.takeIf { it.id == id && it.status == GameSessionStatus.Active }
                        ?: return@update current
                    val taken = latest.receivedPackets.mapNotNull { it.payload.toIntOrNull() }.toSet() +
                        setOfNotNull(payload.toIntOrNull())
                    val replyCell = (0..8).firstOrNull { it !in taken } ?: return@update current
                    val packet = GameDataPacket(sequence = ids.incrementAndGet(), payload = replyCell.toString())
                    current + (key to latest.copy(receivedPackets = latest.receivedPackets + packet))
                }
            }
        }
    }

    override suspend fun sendFile(contactId: String, file: java.io.File, name: String): Result<Unit> = runCatching {
        check(signedInEmail.isNotBlank()) { "Sign in before sharing files." }
        val contact = resolveMockContact(contactId)
        check(contact?.isGroup != true) { "File transfers are only available in one-to-one chats." }
        check(contact != null && !contact.isBlocked && contact.presence.isOnline) {
            "This contact is unavailable for file transfers."
        }
        check(contact.recipientFeatures(signedInEmail).files) {
            "This contact's client does not support file transfers."
        }
        require(file.isFile) { "The selected file is unavailable." }
        require(org.aralozkaya.msngr.protocol.MsnFileContext.validName(name)) { "The file name is unsupported." }
        require(!org.aralozkaya.msngr.protocol.MsnFileContext.isExecutable(name)) {
            "Executable files cannot be shared."
        }
        val activeForPeer = mutableMessages.value[contact.id].orEmpty().count { it.fileTransfer?.active == true }
        check(activeForPeer < 8) { "Too many active file transfers." }

        val id = MsnpP2pUtil.randomGuid()
        val size = file.length()
        val generation = sessionGeneration
        val initial = FileTransfer(
            id = id,
            name = name,
            size = size,
            outgoing = true,
            status = FileTransferStatus.Offered,
            transferred = 0L,
            localPath = file.absolutePath,
        )
        appendMessage(
            ChatMessage(
                id = ids.incrementAndGet(),
                contactId = contact.id,
                author = MessageAuthor.Me,
                text = "File: $name",
                timestampMillis = System.currentTimeMillis(),
                fileTransfer = initial,
            ),
        )
        val job = sessionScope.launch {
            delay(transferStepDelayMillis)
            if (sessionGeneration != generation) return@launch
            updateMockFileTransfer(contact.id, id) {
                if (it.status == FileTransferStatus.Offered) it.copy(status = FileTransferStatus.Transferring) else it
            }
            val steps = 4
            for (step in 1..steps) {
                delay(transferStepDelayMillis)
                if (sessionGeneration != generation) return@launch
                val transferred = if (step == steps || size == 0L) size else (size * step) / steps
                val done = step == steps
                updateMockFileTransfer(contact.id, id) { current ->
                    if (current.status != FileTransferStatus.Transferring) current
                    else current.copy(
                        transferred = transferred,
                        status = if (done) FileTransferStatus.Complete else FileTransferStatus.Transferring,
                    )
                }
            }
        }
        mockFileTransferJobs[id] = job
        job.invokeOnCompletion { mockFileTransferJobs.remove(id, job) }
    }

    override suspend fun acceptFile(contactId: String, id: String): Result<Unit> = runCatching {
        val resolvedId = resolveMockContact(contactId)?.id ?: contactId
        val existing = mutableMessages.value[resolvedId].orEmpty().firstOrNull { it.fileTransfer?.id == id }?.fileTransfer
            ?: error("This file offer has ended.")
        check(!existing.outgoing && existing.status == FileTransferStatus.Offered)
        val generation = sessionGeneration
        updateMockFileTransfer(resolvedId, id) { it.copy(status = FileTransferStatus.Transferring) }
        val job = sessionScope.launch {
            val steps = 4
            for (step in 1..steps) {
                delay(transferStepDelayMillis)
                if (sessionGeneration != generation) return@launch
                val transferred = if (step == steps || existing.size == 0L) existing.size else (existing.size * step) / steps
                val done = step == steps
                updateMockFileTransfer(resolvedId, id) { current ->
                    if (current.status != FileTransferStatus.Transferring) current
                    else current.copy(
                        transferred = transferred,
                        status = if (done) FileTransferStatus.Complete else FileTransferStatus.Transferring,
                    )
                }
            }
        }
        mockFileTransferJobs[id] = job
        job.invokeOnCompletion { mockFileTransferJobs.remove(id, job) }
    }

    override suspend fun cancelFile(contactId: String, id: String): Result<Unit> = runCatching {
        val resolvedId = resolveMockContact(contactId)?.id ?: contactId
        mockFileTransferJobs.remove(id)?.cancel()
        updateMockFileTransfer(resolvedId, id) { current ->
            if (!current.active) current
            else {
                val declined = !current.outgoing && current.status == FileTransferStatus.Offered
                current.copy(status = if (declined) FileTransferStatus.Declined else FileTransferStatus.Cancelled)
            }
        }
    }

    override suspend fun sharePhotos(contactId: String, photos: List<PhotoToShare>): Result<Unit> = sessionOperation {
        check(signedInEmail.isNotBlank()) { "Sign in before sharing photos." }
        val contact = resolveMockContact(contactId)
        check(contact != null && !contact.isGroup && !contact.isBlocked && contact.presence.isOnline) {
            "This contact is unavailable for photo sharing."
        }
        check(contact.recipientFeatures(signedInEmail).photos) {
            "This contact's client does not support photo sharing."
        }
        require(photos.size in 1..32)
        val existing = mutablePhotoShares.value[contact.id]
        check(existing?.status != PhotoShareStatus.Invited) { "Wait for the photo invitation to be accepted." }
        val generation = sessionGeneration

        if (existing?.status == PhotoShareStatus.Active) {
            require(existing.photos.size + photos.size <= 32) { "A photo share can contain up to 32 photos." }
            val startIndex = existing.photos.maxOfOrNull { it.sortIndex ?: 0 } ?: 0
            val added = photos.mapIndexed { idx, item ->
                buildMockSharedPhoto(item, existing.id, startIndex + idx + 1)
            }
            mutablePhotoShares.update { all ->
                val current = all[contact.id]?.takeIf { it.id == existing.id && it.status == PhotoShareStatus.Active }
                    ?: return@update all
                all + (contact.id to current.copy(
                    photos = current.photos + added,
                    selectedId = added.lastOrNull()?.id ?: current.selectedId,
                ))
            }
            val unfinished = mutablePhotoShares.value.getValue(contact.id).photos
                .filterNot { it.downloadedByPeer }.mapTo(mutableSetOf()) { it.id }
            launchMockPhotoProgress(contact.id, existing.id, unfinished, generation)
        } else {
            val id = MsnpP2pUtil.randomGuid()
            val pendingBytes = photos.sumOf { MsnpPhotoSession.scopedPreview(it.preview, id).size.toLong() }
            mutablePhotoShares.update { it + (contact.id to PhotoShare(id = id, outgoing = true, pendingBytes = pendingBytes)) }
            appendMessage(
                ChatMessage(
                    id = ids.incrementAndGet(),
                    contactId = contact.id,
                    author = MessageAuthor.System,
                    text = "Photo sharing invitation sent.",
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
            val job = sessionScope.launch {
                delay(transferStepDelayMillis)
                val photoIds = synchronized(sessionLock) {
                    val current = mutablePhotoShares.value[contact.id]
                    if (sessionGeneration != generation || current?.id != id || current.status != PhotoShareStatus.Invited) return@launch
                    val items = photos.mapIndexed { idx, item -> buildMockSharedPhoto(item, id, idx + 1) }
                    mutablePhotoShares.update { all -> all + (contact.id to current.copy(
                        status = PhotoShareStatus.Active,
                        pendingBytes = 0L,
                        photos = items,
                        selectedId = items.firstOrNull()?.id,
                    )) }
                    items.mapTo(mutableSetOf()) { it.id }
                }
                stepMockPhotoProgress(contact.id, id, photoIds, generation)
            }
            mockPhotoShareJobs[id] = job
            job.invokeOnCompletion { mockPhotoShareJobs.remove(id, job) }
        }
    }

    override suspend fun acceptPhotos(contactId: String, id: String): Result<Unit> = sessionOperation {
        val resolvedId = resolveMockContact(contactId)?.id ?: contactId
        mutablePhotoShares.update { all ->
            val current = all[resolvedId]?.takeIf { it.id == id && !it.outgoing && it.status == PhotoShareStatus.Invited }
                ?: return@update all
            all + (resolvedId to current.copy(status = PhotoShareStatus.Active))
        }
    }

    override suspend fun stopPhotos(contactId: String, id: String): Result<Unit> = sessionOperation {
        val resolvedId = resolveMockContact(contactId)?.id ?: contactId
        val current = mutablePhotoShares.value[resolvedId]
        if (current?.id != id || current.status !in listOf(PhotoShareStatus.Invited, PhotoShareStatus.Active)) {
            return@sessionOperation
        }
        mockPhotoShareJobs.remove(id)?.cancel()
        val status = if (!current.outgoing && current.status == PhotoShareStatus.Invited) {
            PhotoShareStatus.Declined
        } else {
            PhotoShareStatus.Stopped
        }
        mutablePhotoShares.update { all ->
            all + (resolvedId to all.getValue(resolvedId).copy(status = status, pendingBytes = 0L))
        }
        appendMessage(
            ChatMessage(
                id = ids.incrementAndGet(),
                contactId = resolvedId,
                author = MessageAuthor.System,
                text = if (status == PhotoShareStatus.Declined) {
                    "Photo sharing invitation declined."
                } else {
                    "You stopped sharing photos."
                },
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun selectPhoto(contactId: String, id: String, photoId: String): Result<Unit> = sessionOperation {
        val resolvedId = resolveMockContact(contactId)?.id ?: contactId
        mutablePhotoShares.update { all ->
            val current = all[resolvedId]?.takeIf { it.id == id } ?: return@update all
            if (current.photos.none { it.id.equals(photoId, true) }) return@update all
            all + (resolvedId to current.copy(selectedId = photoId))
        }
    }

    private fun resolveMockContact(contactId: String): Contact? =
        mutableContacts.value.firstOrNull { it.id.equals(contactId, true) || it.email.equals(contactId, true) }

    private fun updateMockFileTransfer(contactId: String, transferId: String, transform: (FileTransfer) -> FileTransfer) {
        mutableMessages.update { all ->
            val entries = all[contactId] ?: return@update all
            var changed = false
            val updated = entries.map { msg ->
                val transfer = msg.fileTransfer
                if (transfer?.id == transferId) {
                    val next = transform(transfer)
                    if (next != transfer) {
                        changed = true
                        msg.copy(fileTransfer = next)
                    } else msg
                } else msg
            }
            if (changed) all + (contactId to updated) else all
        }
    }

    private fun buildMockSharedPhoto(item: PhotoToShare, shareId: String, sortIndex: Int): SharedPhoto {
        val creator = signedInEmail.ifBlank { MockServerData.ACCOUNT_EMAIL }
        val scopedPreview = MsnpPhotoSession.scopedPreview(item.preview, shareId)
        val thumbMeta = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create(creator, item.thumbnail, 15, "0")
        val previewMeta = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create(creator, scopedPreview, 15, "0")
        val previewPath = runCatching {
            val dir = photoCacheDirectory?.apply { mkdirs() }
            val file = java.io.File.createTempFile("mock-photo-", ".jpg", dir)
            try {
                file.writeBytes(scopedPreview)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
            mockTempPhotoFiles.add(file)
            file.absolutePath
        }.getOrNull()
        return SharedPhoto(
            id = MsnpP2pUtil.randomGuid(),
            name = item.name,
            hash = item.hash,
            thumbnail = thumbMeta,
            preview = previewMeta,
            localPath = previewPath,
            originalPath = item.originalPath,
            sortIndex = sortIndex,
            outgoing = true,
            previewSentBytes = 0L,
        )
    }

    private fun launchMockPhotoProgress(contactId: String, shareId: String, photoIds: Set<String>, generation: Long) {
        mockPhotoShareJobs.remove(shareId)?.cancel()
        val job = sessionScope.launch { stepMockPhotoProgress(contactId, shareId, photoIds, generation) }
        mockPhotoShareJobs[shareId] = job
        job.invokeOnCompletion { mockPhotoShareJobs.remove(shareId, job) }
    }

    private suspend fun stepMockPhotoProgress(contactId: String, shareId: String, photoIds: Set<String>, generation: Long) {
        val steps = 4
        for (step in 1..steps) {
            delay(transferStepDelayMillis)
            if (sessionGeneration != generation) return
            val done = step == steps
            mutablePhotoShares.update { all ->
                val current = all[contactId]?.takeIf { it.id == shareId && it.status == PhotoShareStatus.Active }
                    ?: return@update all
                all + (contactId to current.copy(photos = current.photos.map { photo ->
                    if (photo.id !in photoIds) photo
                    else {
                        val size = photo.preview.size.toLong()
                        val sent = if (done) size else (size * step) / steps
                        photo.copy(
                            previewSentBytes = maxOf(photo.previewSentBytes, sent),
                            downloadedByPeer = photo.downloadedByPeer || done,
                        )
                    }
                }))
            }
        }
    }

    private fun resetMockTransfers() {
        mockFileTransferJobs.values.toList().forEach(Job::cancel)
        mockFileTransferJobs.clear()
        mockPhotoShareJobs.values.toList().forEach(Job::cancel)
        mockPhotoShareJobs.clear()
        mutablePhotoShares.value = emptyMap()
        mockTempPhotoFiles.toList().forEach {
            it.delete()
            mockTempPhotoFiles.remove(it)
        }
    }

    override fun close() = synchronized(sessionLock) {
        mutableContactRequests.value = emptyList()
        mutableGroupInvitations.value = emptyList()
        sessionGeneration++
        signedInEmail = ""
        currentMedia = null
        mutableSessions.value = emptyList()
        sessionScope.cancel()
        mutableContacts.value = emptyList()
        mutableMessages.value = emptyMap()
        mutableTyping.value = emptyMap()
        mutableGameSessions.value = emptyMap()
        resetMockTransfers()
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
        sessionScope.launch {
            var cursor = 0
            val cycle = listOf(Presence.Online, Presence.Busy, Presence.Away, Presence.Offline)
            while (isActive) {
                delay(12_000)
                val unblocked = mutableContacts.value.filterNot(Contact::isBlocked)
                if (unblocked.isEmpty()) continue
                val contact = unblocked[cursor % unblocked.size]
                val next = cycle[(cycle.indexOf(contact.presence) + 1) % cycle.size]
                mutableContacts.update { list ->
                    list.map {
                        if (it.id == contact.id) {
                            it.copy(
                                presence = next,
                                clientCapabilities = if (next.isOnline) org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID else 0L,
                                extendedCapabilities = if (next.isOnline) org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong() else 0L,
                            )
                        } else it
                    }
                }
                mutableEvents.emit(MessengerEvent.PresenceChanged(contact.id, next, contact.presence))
                cursor++
            }
        }
    }

}
