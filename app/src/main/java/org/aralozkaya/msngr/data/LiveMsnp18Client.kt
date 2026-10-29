package org.aralozkaya.msngr.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.aralozkaya.msngr.AccountIdentity
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.MessengerPlusTextCodec
import org.aralozkaya.msngr.MsnEmoticonRange
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.ServerConfig
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.deviceEndpointName
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.MsnInkCodec
import org.aralozkaya.msngr.protocol.MsnVoiceClip
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.Msnp18SocketTransport
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.aralozkaya.msngr.protocol.VoiceClip
import org.aralozkaya.msngr.trimMessengerPlusWhitespace
import org.aralozkaya.msngr.withoutUnicodeEmoji
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Network-backed MSNP18 client. This implementation never substitutes sample contacts, messages,
 * or login results: connection, protocol negotiation, redirects, and SSO all use the selected
 * community service's endpoints.
 */
internal class LiveMsnp18Client(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val transport: MsnpTransport = Msnp18SocketTransport(),
    private val switchboardTransportFactory: () -> MsnpTransport = { Msnp18SocketTransport() },
    private val authenticateSso: suspend (
        host: String,
        email: String,
        password: String,
        policy: String,
        nonce: String,
    ) -> SsoToken = { host, email, password, policy, nonce ->
        MsnpSsoAuthenticator.authenticate(host, email, password, policy, nonce)
    },
    private val fetchAddressBook: suspend (
        host: String,
        ticket: String,
        signedInEmail: String,
    ) -> AddressBookSnapshot = { host, ticket, signedInEmail ->
        MsnpAddressBookService.fetchContacts(host, ticket, signedInEmail)
    },
    private val fetchDisplayPicture: suspend (url: String, maxBytes: Int) -> ByteArray? =
        ::fetchRemoteDisplayPicture,
    private val fetchStorageProfile: (suspend (
        host: String,
        ticket: String,
    ) -> StorageProfile)? = null,
    private val updateStorageDisplayPicture: suspend (
        host: String,
        ticket: String,
        photoResourceId: String,
        image: ByteArray,
    ) -> Unit = MsnpStorageService::updateDisplayPicture,
    private val createStorageDisplayPicture: suspend (
        host: String,
        ticket: String,
        profileResourceId: String,
        image: ByteArray,
    ) -> String = MsnpStorageService::createDisplayPicture,
    private val removeStorageDisplayPicture: suspend (
        host: String,
        ticket: String,
        expressionProfileResourceId: String,
        photoResourceId: String,
    ) -> Unit = MsnpStorageService::removeDisplayPicture,
    private val winkStore: WinkStore? = null,
    private val contactDisplayPictureCache: ContactDisplayPictureCache =
        EmptyContactDisplayPictureCache,
    private val contactTilePollIntervalMillis: Long = CONTACT_TILE_POLL_INTERVAL_MILLIS,
    private val initialContactPictureRefreshDelayMillis: Long =
        INITIAL_CONTACT_PICTURE_REFRESH_DELAY_MILLIS,
    private val offlineMessageService: OfflineMessageService = MsnpOfflineMessages,
    private val offlineRetryDelayMillis: Long = 30_000L,
    private val endpointName: () -> String = { deviceEndpointName() },
    private val addAddressBookContact: suspend (String, String, String, String, String) -> String =
        MsnpAddressBookService::addContact,
    private val removeAddressBookContact: suspend (String, String, String) -> Unit =
        MsnpAddressBookService::removeContact,
    private val restoreAddressBookContact: suspend (String, String, String) -> Unit =
        MsnpAddressBookService::restoreContact,
    private val fetchMessengerMemberships: suspend (String, String, String) -> MessengerMemberships =
        MsnpAddressBookService::fetchMemberships,
    private val changeMembership: suspend (String, String, String, String, Boolean, String?) -> Unit =
        MsnpAddressBookService::changeMembership,
    private val acceptAddressBookContact: suspend (String, String, String, String) -> String =
        MsnpAddressBookService::acceptContact,
    private val createAddressBookGroup: suspend (String, String, String, String) -> String = MsnpAddressBookService::createGroup,
    private val inviteAddressBookGroupMember: suspend (String, String, String, String, String) -> Unit = MsnpAddressBookService::inviteGroupMember,
    private val respondAddressBookGroupInvitation: suspend (String, String, String, Boolean) -> Unit = MsnpAddressBookService::respondToGroupInvitation,
    private val leaveAddressBookGroup: suspend (String, String, String, String) -> Unit = MsnpAddressBookService::leaveGroup,
    private val fetchAddressBookGroupMembers: suspend (String, String, String) -> List<GroupMember> = MsnpAddressBookService::fetchGroupMembers,
    private val nudgeLimiter: NudgeRateLimiter = NudgeRateLimiter(),
) : MsnpClient {
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableContactRequests = MutableStateFlow<List<ContactRequest>>(emptyList())
    private val mutableGroupInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    // Escargot's personal roster may lag behind successful create/join responses until reconnect.
    private val pendingRosterGroups = ConcurrentHashMap<String, Contact>()
    private val leftGroupIds = ConcurrentHashMap.newKeySet<String>()
    private val handledGroupInvitations = ConcurrentHashMap.newKeySet<String>()
    private var requestsAccountId = ""
    private val mutableMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val mutableTyping = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val mutableSessions = MutableStateFlow<List<AccountSession>>(emptyList())
    private var accountSessionsReceived: CompletableDeferred<Unit>? = null
    private val mutableEvents = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 16)
    override val nudgeCooldowns = nudgeLimiter.cooldowns
    private val transactionIds = AtomicInteger(0)
    private val messageIds = AtomicLong(System.currentTimeMillis() shl 16)
    private val connectionLossReported = AtomicBoolean(false)
    private val knownContactPresences = ConcurrentHashMap<String, Presence>()
    private var presenceAccountId = ""
    private var restoringContactPresence = false
    private var initialPresenceSync = false
    private val sendMutex = Mutex()
    private val rosterMutationMutex = Mutex()
    private var subscribedContacts = emptyMap<String, Contact>()
    private val deletedContactIds = ConcurrentHashMap.newKeySet<String>()
    private val switchboardMutexes = ConcurrentHashMap<String, Mutex>()
    private val displayPictureRequestSemaphore = Semaphore(MAX_CONCURRENT_DISPLAY_PICTURE_REQUESTS)
    private val pendingNotificationTransactions =
        ConcurrentHashMap<Int, CompletableDeferred<MsnpCommand>>()
    private val switchboards = ConcurrentHashMap<String, MsnpSwitchboardSession>()
    private val activeSwitchboards = ConcurrentHashMap.newKeySet<MsnpSwitchboardSession>()
    private val pictureSessions = ConcurrentHashMap<String, MsnpP2pSession>()
    private data class SharedVoice(val data: LocalDisplayPicture, val createdAt: Long)
    private val sharedVoiceClips = ConcurrentHashMap<String, SharedVoice>()
    private data class SharedWink(val data: LocalDisplayPicture, val createdAt: Long, val recipients: Set<String>)
    private val sharedWinks = ConcurrentHashMap<String, SharedWink>()
    private class PendingWink(val messageId: Long)
    private val pendingWinks = ConcurrentHashMap<String, PendingWink>()
    private val winkLimiter = NudgeRateLimiter()
    private val pendingVoiceClips = ConcurrentHashMap<String, Long>()
    private val circleDeliveries = ConcurrentHashMap<Int, Pair<String, Long>>()
    private val circleSegments = CircleSegmentAssembler()
    private val typingJobs = ConcurrentHashMap<String, Job>()
    private val lastTypingSentAt = ConcurrentHashMap<String, Long>()
    private val contactDisplayPictures = ConcurrentHashMap<String, MsnDisplayPicture>()
    private val displayedContactDisplayPictureHashes = ConcurrentHashMap<String, String>()
    private val removedContactTileHashes = ConcurrentHashMap<String, String>()
    private val contactEndpointIds = ConcurrentHashMap<String, String>()
    private val contactEndpointWaiters =
        ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val contactDisplayPictureJobs = ConcurrentHashMap<String, Job>()
    private val contactDisplayPictureStateLock = Any()
    private val contactDisplayPictureCacheMutex = Mutex()
    private val ownDisplayPictureMutex = Mutex()
    private val mutablePictureStorageIssue = MutableStateFlow<String?>(null)
    private val contactStorageIds = ConcurrentHashMap<String, String>()
    private val contactTileThumbnailHashes = ConcurrentHashMap<String, String>()
    private val contactTileHashes = ConcurrentHashMap<String, String>()
    private var receiverJob: Job? = null
    private var offlineReceiverJob: Job? = null
    private var offlineRefreshRequests: Channel<Unit>? = null
    private var offlineServer = ""
    private var offlineTicket = ""
    private var addressBookRefreshJob: Job? = null
    private var addressBookFollowupJob: Job? = null
    private var addressBookRefreshRequests: Channel<Unit>? = null
    private var addressBookServer = ""
    private var addressBookTicket = ""
    private var currentDisplayName = ""
    private var circleAuthenticated = false
    private var heartbeatJob: Job? = null
    private var contactTilePollJob: Job? = null
    private var initialContactPictureRefreshJob: Job? = null
    private var initialContactPictureRefreshSignal: CompletableDeferred<Unit>? = null
    private var signedInEmail = ""
    private var signedInAtMillis = 0L
    private var signedInStorageAccount = ""
    private var signedInIdentity: AccountIdentity? = null
    private var machineGuid = ""
    private var switchboardFallbackPort = DEFAULT_SWITCHBOARD_PORT
    private var currentPresence = Presence.Online
    private var currentPersonalMessage = ""
    private var storageServer = ""
    private var storageOwnerCid = "0"
    private var contactTileHosts = emptyList<String>()
    private var notificationServerHost = ""
    private var storageTicket = ""
    private var storageProfileResourceId: String? = null
    private var storageExpressionProfileResourceId: String? = null
    private var storagePhotoResourceId: String? = null
    private var storageDisplayPictureAttached = false
    private var storageDisplayPictureHash: String? = null
    @Volatile
    private var localDisplayPicture: LocalDisplayPicture? = null
    @Volatile
    private var appInForeground = true
    private var lastAdvertisedDisplayPictureHash: String? = null
    @Volatile private var ownPresencePicture: String? = null
    private var ownPresencePicturePending = false
    @Volatile private var ownPictureSourceEndpoint: String? = null
    @Volatile private var ownPresenceSourceHint: String? = null
    @Volatile private var ignoredOwnPresencePicture: String? = null
    @Volatile private var downloadedOwnPicture: ByteArray? = null
    private var ownPictureDownload: CompletableDeferred<ByteArray>? = null

    override val contacts = mutableContacts.asStateFlow()
    override val contactRequests = mutableContactRequests.asStateFlow()
    override val groupInvitations = mutableGroupInvitations.asStateFlow()
    override val messages = mutableMessages.asStateFlow()
    override val typing = mutableTyping.asStateFlow()
    override val events = mutableEvents.asSharedFlow()
    override val sessions = mutableSessions.asStateFlow()
    override val displayPictureStorageIssue = mutablePictureStorageIssue.asStateFlow()

    override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> = runCatching {
        val email = request.email.trim()
        require(email.isNotEmpty() && request.password.isNotEmpty()) {
            "Email and password are required."
        }

        val accountId = AccountIdentity.forServer(email, request.serverConfig, false).storageId
        nudgeLimiter.selectAccount(accountId)
        val sameAccount = requestsAccountId == accountId
        val restoreContactPresence = presenceAccountId == accountId
        resetConnection(preserveRequests = sameAccount, preserveContactPresence = restoreContactPresence)
        restoringContactPresence = restoreContactPresence
        initialPresenceSync = true
        connectionLossReported.set(false)
        switchboardFallbackPort = request.serverConfig.switchboardPort
        val challenge = negotiate(request.serverConfig, email)
        val token = authenticateSso(
            request.serverConfig.authServer,
            email,
            request.password,
            challenge.policy,
            challenge.nonce,
        )
        val endpointGuid = UUID.randomUUID().toString()
        val transactionId = nextTransactionId()
        send(
            MsnpCommand(
                verb = "USR",
                transactionId = transactionId,
                parameters = listOf(
                    "SSO",
                    "S",
                    token.ticket,
                    token.response,
                    endpointGuid,
                ),
            ),
        )
        val authenticated = awaitTransaction(transactionId, setOf("USR"))
        if (authenticated.parameters.firstOrNull()?.equals("OK", ignoreCase = true) != true) {
            throw LiveMsnpException("The community server did not accept this sign-in.")
        }

        signedInEmail = email
        signedInAtMillis = System.currentTimeMillis()
        signedInStorageAccount = AccountIdentity.forServer(email, request.serverConfig, false).storageId
        winkLimiter.selectAccount(signedInStorageAccount)
        signedInIdentity = AccountIdentity.forServer(email, request.serverConfig, false)
        machineGuid = endpointGuid
        accountSessionsReceived = CompletableDeferred()
        mutableSessions.value = listOf(AccountSession(endpointGuid, endpointName(), isCurrent = true,
            presence = request.initialPresence))
        currentPresence = request.initialPresence
        storageServer = request.serverConfig.storageServer
        storageTicket = token.storageTicket
        offlineServer = request.serverConfig.mainServer
        offlineTicket = token.offlineTicket
        offlineRefreshRequests = Channel(Channel.CONFLATED)
        val signInData = coroutineScope {
            val addressBook = async {
                fetchAddressBook(
                    request.serverConfig.addressBookServer,
                    token.contactsTicket,
                    email,
                )
            }
            val storage = async {
                storageOwnerCid = addressBook.await().ownerCid ?: "0"
                val profileResult = runCatching {
                    fetchCurrentStorageProfile()
                }
                val profile = profileResult.getOrNull()
                val picture = profile?.displayPictureUrl?.let { url ->
                    runCatching {
                        fetchDisplayPicture(url, MAX_DISPLAY_PICTURE_BYTES)
                    }.getOrNull()
                }
                StorageSignInData(
                    profile = profile,
                    picture = picture,
                    available = profileResult.isSuccess,
                )
            }
            SignInData(addressBook.await(), storage.await())
        }
        val addressBook = signInData.addressBook
        val storageProfile = signInData.storage.profile
        val storedDisplayPicture = signInData.storage.picture
        storageProfileResourceId = storageProfile?.profileResourceId
        storageExpressionProfileResourceId = storageProfile?.expressionProfileResourceId
        storagePhotoResourceId = storageProfile?.photoResourceId
        storageDisplayPictureAttached = storageProfile?.displayPictureUrl != null
        storageDisplayPictureHash = storedDisplayPicture?.sha256()
        currentPersonalMessage = storageProfile?.personalStatus.orEmpty()
        localDisplayPicture = storedDisplayPicture
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { bytes ->
                LocalDisplayPicture(MsnDisplayPicture.create(email, bytes), bytes)
            }
        val groupMembers = addressBook.contacts.flatMap(Contact::members)
        val cachedPictures = runCatching {
            contactDisplayPictureCache.load(signedInStorageAccount,
                (addressBook.contacts.map(Contact::email) + groupMembers.map(GroupMember::email)).distinct())
        }.getOrDefault(emptyMap())
        groupMembers.forEach { member -> member.storageId?.let { contactStorageIds[member.email.lowercase()] = it } }
        contactStorageIds.putAll(addressBook.contactStorageIds)
        contactTileHosts = listOf(
            request.serverConfig.mainServer,
            request.serverConfig.storageServer,
            request.serverConfig.addressBookServer,
            notificationServerHost,
        ).mapNotNull(String::httpHostOrNull).distinct()
        contactTileHashes.putAll(
            cachedPictures.mapValues { (_, cached) -> cached.bytes.sha256() },
        )
        displayedContactDisplayPictureHashes.putAll(
            cachedPictures.mapValues { (_, cached) -> cached.picture.sha1d },
        )
        mutableContacts.value = addressBook.contacts.map { contact ->
            contact.copy(
                avatarUri = cachedPictures[contact.email.lowercase()]?.bytes?.toImageDataUri() ?: contact.avatarUri,
                members = contact.members.map { member ->
                    member.copy(avatarUri = cachedPictures[member.email.lowercase()]?.bytes?.toImageDataUri() ?: member.avatarUri)
                },
            ).withGroupPresence(email)
        }
        mutableMessages.value = emptyMap()
        mutableTyping.value = emptyMap()
        val pendingIds = addressBook.contactRequests.map(ContactRequest::id).toSet()
        publishInitialContactList(addressBook.contacts.filterNot { it.isGroup || it.id in pendingIds })
        send(Msnp18Protocol.endpointCapabilities(nextTransactionId()))
        send(
            Msnp18Protocol.privateEndpointData(
                nextTransactionId(),
                request.initialPresence,
                endpointName = endpointName(),
            ),
        )
        send(
            Msnp18Protocol.changePresence(
                nextTransactionId(),
                request.initialPresence,
                localDisplayPicture?.metadata,
            ),
        )
        lastAdvertisedDisplayPictureHash = localDisplayPicture?.metadata?.sha1d
        transport.setReadTimeout(0)
        scheduleInitialContactPictureRefresh()
        // Queue events received during circle authentication until sign-in has completed.
        val refreshRequests = Channel<Unit>(Channel.CONFLATED)
        addressBookRefreshRequests = refreshRequests
        startReceiver()
        val circles = addressBook.contacts.filter(Contact::isGroup)
        if (circles.isNotEmpty() || addressBook.circleTicket != null) {
            val ticket = checkNotNull(addressBook.circleTicket) { "The group authentication ticket is missing." }
            val authenticatedCircles = requestNotification(MsnpCircles.authenticate(nextTransactionId(), ticket))
            if (authenticatedCircles.verb != "USR" || authenticatedCircles.parameters.firstOrNull() != "OK") {
                throw authenticatedCircles.toProtocolError()
            }
            circleAuthenticated = true
            circles.chunked(MAX_CONTACTS_PER_ADL).forEach { batch ->
                val response = requestNotification(MsnpCircles.subscribe(nextTransactionId(), batch))
                if (response.verb != "ADL" || response.parameters.firstOrNull() != "OK") {
                    throw response.toProtocolError()
                }
            }
        }
        addressBookServer = request.serverConfig.addressBookServer
        addressBookTicket = token.contactsTicket
        currentDisplayName = addressBook.ownerDisplayName?.takeIf(String::isNotBlank)
            ?: storageProfile?.displayName?.takeIf(String::isNotBlank) ?: email.substringBefore('@')
        if (!addressBook.failedBlockedLookup) mutableContactRequests.value = addressBook.contactRequests
        mutableGroupInvitations.value = addressBook.groupInvitations
        requestsAccountId = signedInStorageAccount
        presenceAccountId = signedInStorageAccount
        startAddressBookRefresh(refreshRequests, request.serverConfig.addressBookServer, token.contactsTicket, email, endpointGuid)
        if (addressBook.failedBlockedLookup) refreshRequests.trySend(Unit)
        startHeartbeat()
        startContactTilePolling()

        check(machineGuid == endpointGuid) { "This session was signed out during sign-in." }

        UserProfileSnapshot(
            username = addressBook.ownerDisplayName
                ?.takeIf(String::isNotBlank)
                ?: storageProfile?.displayName
                ?.takeIf(String::isNotBlank)
                ?: email.substringBefore('@').ifBlank { "You" },
            email = email,
            presence = request.initialPresence,
            avatarUri = storedDisplayPicture?.toImageDataUri(),
            statusMessage = storageProfile?.personalStatus.orEmpty(),
            displayPictureFetched = signInData.storage.available && storageProfile != null && storageProfile.displayPictureStateKnown &&
                (storageProfile.displayPictureUrl == null || storedDisplayPicture?.isNotEmpty() == true),
        )
    }.onFailure {
        val accountId = AccountIdentity.forServer(request.email, request.serverConfig, false).storageId
        resetConnection(
            preserveRequests = requestsAccountId == accountId,
            preserveContactPresence = presenceAccountId == accountId,
        )
    }

    override suspend fun changePresence(presence: Presence) {
        if (signedInEmail.isEmpty()) return
        currentPresence = presence
        mutableSessions.update { list -> list.map { if (it.isCurrent) it.copy(presence = presence) else it } }
        runCatching {
            send(
                Msnp18Protocol.changePresence(
                    nextTransactionId(),
                    presence,
                    localDisplayPicture?.metadata,
                ),
            )
        }.onSuccess {
            lastAdvertisedDisplayPictureHash = localDisplayPicture?.metadata?.sha1d
        }
    }

    override suspend fun changeNickname(nickname: String) {
        if (signedInEmail.isEmpty()) return
        runCatching { send(Msnp18Protocol.changeNickname(nextTransactionId(), nickname)) }
            .onSuccess { currentDisplayName = nickname }
    }

    override suspend fun changePersonalMessage(statusMessage: String) {
        if (signedInEmail.isEmpty()) return
        currentPersonalMessage = statusMessage.trim()
        runCatching {
            sendCurrentPersonalMessage()
        }
    }

    override suspend fun fetchOwnDisplayPicture() = ownDisplayPictureMutex.withLock { fetchOwnDisplayPictureLocked() }

    private suspend fun fetchOwnDisplayPictureLocked(): org.aralozkaya.msngr.IncomingDisplayPicture {
        if (signedInEmail.isBlank()) return org.aralozkaya.msngr.IncomingDisplayPicture()
        val endpoint = machineGuid
        val account = signedInStorageAccount
        return try {
            val profile = fetchCurrentStorageProfile()
            if (!profile.displayPictureStateKnown) return fetchOwnPictureFromEndpoint()
            val bytes = profile.displayPictureUrl?.let { url ->
                fetchDisplayPicture(url, MAX_DISPLAY_PICTURE_BYTES)?.takeIf { it.isNotEmpty() }
                    ?: return fetchOwnPictureFromEndpoint()
            }
            if (endpoint != machineGuid || account != signedInStorageAccount) return org.aralozkaya.msngr.IncomingDisplayPicture()
            storageProfileResourceId = profile.profileResourceId
            storageExpressionProfileResourceId = profile.expressionProfileResourceId
            storagePhotoResourceId = profile.photoResourceId
            storageDisplayPictureAttached = profile.displayPictureUrl != null
            storageDisplayPictureHash = bytes?.sha256()
            if (hasRemoteOwnPictureAnnouncement()) fetchOwnPictureFromEndpoint(bytes)
            else org.aralozkaya.msngr.IncomingDisplayPicture(bytes, fetched = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fetchOwnPictureFromEndpoint() }
    }

    /** Roaming storage and another active endpoint are independent picture sources. */
    private fun hasRemoteOwnPictureAnnouncement(): Boolean {
        val raw = ownPresencePicture ?: return false
        val source = ownPresenceSourceHint ?: ownPictureSourceEndpoint ?: return false
        return source != machineGuid && raw != ignoredOwnPresencePicture &&
            sessions.value.any { it.id == source && !it.isCurrent }
    }

    private suspend fun fetchOwnPictureFromEndpoint(storageBytes: ByteArray? = null): org.aralozkaya.msngr.IncomingDisplayPicture {
        val raw = ownPresencePicture ?: return org.aralozkaya.msngr.IncomingDisplayPicture()
        val source = ownPresenceSourceHint ?: ownPictureSourceEndpoint ?: return org.aralozkaya.msngr.IncomingDisplayPicture()
        if (source == machineGuid || raw == ignoredOwnPresencePicture || sessions.value.none { it.id == source && !it.isCurrent })
            return org.aralozkaya.msngr.IncomingDisplayPicture()
        if (raw == "0") return org.aralozkaya.msngr.IncomingDisplayPicture(fetched = true)
        val picture = MsnDisplayPicture.parse(raw) ?: return org.aralozkaya.msngr.IncomingDisplayPicture()
        listOfNotNull(storageBytes, downloadedOwnPicture).firstOrNull(picture::matches)?.let {
            return org.aralozkaya.msngr.IncomingDisplayPicture(it.copyOf(), true)
        }
        picture.downloadUrl?.let { url ->
            fetchDisplayPicture(url, MAX_DISPLAY_PICTURE_BYTES)?.takeIf(picture::matches)?.let {
                if (ownPresencePicture == raw && (ownPresenceSourceHint ?: ownPictureSourceEndpoint) == source) {
                    downloadedOwnPicture = it.copyOf()
                    return org.aralozkaya.msngr.IncomingDisplayPicture(it, true)
                }
            }
        }
        val endpoint = machineGuid
        val download = CompletableDeferred<ByteArray>()
        ownPictureDownload = download
        return try {
            contactEndpointIds[signedInEmail.lowercase()] = source
            val bytes = withTimeoutOrNull(NOTIFICATION_TRANSACTION_TIMEOUT_MILLIS) {
                switchboardFor(signedInEmail).requestDisplayPicture(picture)
                download.await()
            }
            if (bytes != null && endpoint == machineGuid && ownPresencePicture == raw && (ownPresenceSourceHint ?: ownPictureSourceEndpoint) == source)
                org.aralozkaya.msngr.IncomingDisplayPicture(bytes, true)
            else org.aralozkaya.msngr.IncomingDisplayPicture()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { org.aralozkaya.msngr.IncomingDisplayPicture() }
        finally { if (ownPictureDownload === download) ownPictureDownload = null }
    }

    override suspend fun changeDisplayPicture(image: ByteArray?) = ownDisplayPictureMutex.withLock {
        check(signedInEmail.isNotEmpty()) { "Sign in before changing your display picture." }
        val endpoint = machineGuid
        val normalized = image?.takeIf(ByteArray::isNotEmpty)?.copyOf()
        // A different endpoint can delete or replace the photo resource at any moment.
        // Never mutate a resource ID cached at sign-in, or report success after a storage outage.
        val profile = try { refreshStorageProfileIdentifiers() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            check(endpoint == machineGuid) { "The signed-in session changed. Please try again." }
            // MSNP pictures also work without roaming (as in WLM with roaming disabled).
            // Keep that ability during a profile-service outage, with an explicit notice.
            ignoredOwnPresencePicture = ownPresencePicture
            adoptIncomingDisplayPictureLocked(normalized)
            mutablePictureStorageIssue.value = "Picture updated for this session. Your service couldn't save it for future sign-ins."
            return@withLock
        }
        check(profile.displayPictureStateKnown) { "The service did not return a complete display picture. Try again." }
        val remoteBytes = profile.displayPictureUrl?.let { fetchDisplayPicture(it, MAX_DISPLAY_PICTURE_BYTES) }
        check(endpoint == machineGuid) { "The signed-in session changed. Please try again." }
        storageDisplayPictureHash = remoteBytes?.sha256()
        val pictureContentChanged = when {
            normalized == null -> storageDisplayPictureAttached
            else -> normalized.sha256() != storageDisplayPictureHash
        }
        if (pictureContentChanged) {
            if (normalized == null) {
                if (storageDisplayPictureAttached) {
                    val photoResourceId = storagePhotoResourceId
                        ?: refreshStorageProfileIdentifiers().photoResourceId
                        ?: throw LiveMsnpException(
                            "The storage service did not return a photo resource ID.",
                        )
                    val expressionProfileResourceId = storageExpressionProfileResourceId
                        ?: refreshStorageProfileIdentifiers().expressionProfileResourceId
                        ?: throw LiveMsnpException(
                            "The storage service did not return an expression profile resource ID.",
                        )
                    removeStorageDisplayPicture(
                        storageServer,
                        storageTicket,
                        expressionProfileResourceId,
                        photoResourceId,
                    )
                    // Some services acknowledge an unsupported DeleteRelationships action.
                    // Do not discard the user's saved original unless removal really took effect.
                    var confirmed = false
                    repeat(3) { attempt ->
                        if (!confirmed) {
                            if (attempt > 0) delay(500L * attempt)
                            val stored = refreshStorageProfileIdentifiers()
                            confirmed = stored.displayPictureStateKnown && stored.displayPictureUrl == null
                        }
                    }
                    check(confirmed) { "The service did not remove the roaming picture. Your local picture was kept. Try again." }
                    storagePhotoResourceId = null
                    storageDisplayPictureAttached = false
                }
            } else {
                if (storagePhotoResourceId == null && storageProfileResourceId == null) {
                    refreshStorageProfileIdentifiers()
                }
                val photoResourceId = storagePhotoResourceId
                if (photoResourceId != null) {
                    updateStorageDisplayPicture(
                        storageServer,
                        storageTicket,
                        photoResourceId,
                        normalized,
                    )
                } else {
                    val profileResourceId = storageProfileResourceId
                        ?: refreshStorageProfileIdentifiers().profileResourceId
                        ?: throw LiveMsnpException(
                            "The storage service did not return a profile resource ID.",
                        )
                    storagePhotoResourceId = createStorageDisplayPicture(
                        storageServer,
                        storageTicket,
                        profileResourceId,
                        normalized,
                    )
                }
                storageDisplayPictureAttached = true
            }
            storageDisplayPictureHash = normalized?.sha256()
        }
        check(endpoint == machineGuid) { "The signed-in session changed. Please try again." }
        // A community server can keep advertising the first endpoint's old object after
        // a successful local upload. Ignore that unchanged publication until it changes.
        ignoredOwnPresencePicture = ownPresencePicture
        adoptIncomingDisplayPictureLocked(normalized)
        mutablePictureStorageIssue.value = null
    }

    override suspend fun adoptIncomingDisplayPicture(image: ByteArray?) = ownDisplayPictureMutex.withLock {
        adoptIncomingDisplayPictureLocked(image)
    }

    private suspend fun adoptIncomingDisplayPictureLocked(image: ByteArray?) {
        if (signedInEmail.isEmpty()) return
        val normalized = image?.takeIf(ByteArray::isNotEmpty)?.copyOf()
        localDisplayPicture = normalized
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { bytes ->
                LocalDisplayPicture(MsnDisplayPicture.create(signedInEmail, bytes), bytes)
            }
        val advertisedHash = localDisplayPicture?.metadata?.sha1d
        val pictureContentChanged = advertisedHash != lastAdvertisedDisplayPictureHash
        if (advertisedHash == lastAdvertisedDisplayPictureHash) return
        if (pictureContentChanged) {
            // Escargot stores a new MSNObject from a same-status CHG without notifying contacts.
            // Briefly transition to another online state and immediately restore the real state
            // so its presence worker broadcasts the new MSNObject. The back-to-back commands are
            // coalesced by Escargot, while the final CHG contains the user's actual state.
            displayPictureRefreshPresence()?.let { refreshPresence ->
                send(
                    Msnp18Protocol.changePresence(
                        nextTransactionId(),
                        refreshPresence,
                        localDisplayPicture?.metadata,
                    ),
                )
            }
        }
        send(
            Msnp18Protocol.changePresence(
                nextTransactionId(),
                currentPresence,
                localDisplayPicture?.metadata,
            ),
        )
        lastAdvertisedDisplayPictureHash = advertisedHash
        if (pictureContentChanged) {
            // Re-publish the rest of the presence data as the Messenger-era clients did.
            sendCurrentPersonalMessage()
        }
    }

    private suspend fun refreshStorageProfileIdentifiers(): StorageProfile {
        val endpoint = machineGuid
        val profile = fetchCurrentStorageProfile()
        check(endpoint == machineGuid && signedInEmail.isNotEmpty()) { "The signed-in session changed. Please try again." }
        storageProfileResourceId = profile.profileResourceId
        storageExpressionProfileResourceId = profile.expressionProfileResourceId
        storagePhotoResourceId = profile.photoResourceId
        storageDisplayPictureAttached = profile.displayPictureUrl != null
        return profile
    }

    private suspend fun fetchCurrentStorageProfile(): StorageProfile =
        fetchStorageProfile?.invoke(storageServer, storageTicket)
            ?: MsnpStorageService.fetchProfile(storageServer, storageTicket, storageOwnerCid)

    private fun displayPictureRefreshPresence(): Presence? = when (currentPresence) {
        Presence.Online -> Presence.Away
        Presence.Busy, Presence.Away -> Presence.Online
        Presence.Offline -> null
    }

    private suspend fun sendCurrentPersonalMessage() {
        send(
            Msnp18Protocol.personalMessage(
                transactionId = nextTransactionId(),
                statusMessage = currentPersonalMessage,
                machineGuid = machineGuid,
            ),
        )
    }

    override suspend fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ) {
        sendMessageWithResult(contactId, text, renderedEmoticonRanges)
    }

    override suspend fun sendMessageWithResult(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ): Result<Unit> = sendStyledMessage(contactId, text, renderedEmoticonRanges, NativeTextStyle())

    override suspend fun sendStyledMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
        style: NativeTextStyle,
    ): Result<Unit> {
        val encoded = MessengerPlusTextCodec.normalizeProfileMarkupForChat(
            text.withoutUnicodeEmoji(),
        )
        // Plus! pads custom sound announcements to a minimum length understood by native clients.
        val normalized = if (org.aralozkaya.msngr.protocol.MsnEmotionSound.parse(encoded) != null) encoded
            else encoded.trimMessengerPlusWhitespace().text
        if (normalized.isEmpty()) return Result.success(Unit)
        val email = contactEmail(contactId)
        val message = ChatMessage(
            id = messageIds.incrementAndGet(),
            contactId = email.lowercase(),
            author = MessageAuthor.Me,
            text = normalized,
            timestampMillis = System.currentTimeMillis(),
            renderedEmoticonRanges = renderedEmoticonRanges,
            isSending = true,
            nativeStyle = style,
        )
        appendMessage(message)
        return deliverMessage(message)
    }

    override suspend fun sendInkMessage(contactId: String, drawing: InkDrawing): Result<Unit> {
        val nonEmpty = drawing.strokes.filter { it.points.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Result.success(Unit)
        val email = contactEmail(contactId)
        val message = ChatMessage(
            id = messageIds.incrementAndGet(),
            contactId = email.lowercase(),
            author = MessageAuthor.Me,
            text = "Handwriting",
            timestampMillis = System.currentTimeMillis(),
            isSending = true,
            inkDrawing = drawing.copy(strokes = nonEmpty),
        )
        appendMessage(message)
        return deliverMessage(message)
    }

    override suspend fun retryMessage(message: ChatMessage): Result<Unit> {
        if (message.author == MessageAuthor.Contact && message.wink != null) return retryWinkDownload(message)
        while (true) {
            val conversations = mutableMessages.value
            val messages = conversations[message.contactId].orEmpty()
            // A restored failure may exist only in history until its first retry.
            val current = messages.firstOrNull { it.id == message.id } ?: message
            if (current.author != MessageAuthor.Me || current.isNudge ||
                current.isSending || current.deliveryError == null
            ) return Result.success(Unit)
            val pending = current.copy(isSending = true, deliveryError = null)
            val updated = if (messages.any { it.id == current.id }) {
                messages.map { if (it.id == current.id) pending else it }
            } else {
                (messages + pending).sortedBy { it.timestampMillis }
            }
            // Claim the retry before suspending so repeated taps cannot send twice.
            if (mutableMessages.compareAndSet(
                    conversations,
                    conversations + (message.contactId to updated),
                )
            ) return deliverMessage(pending)
        }
    }

    private suspend fun retryWinkDownload(message: ChatMessage): Result<Unit> = withContext(Dispatchers.IO) {
        val account = signedInStorageAccount
        var pendingMessage: ChatMessage
        while (true) {
            val conversations = mutableMessages.value
            val messages = conversations[message.contactId].orEmpty()
            val current = messages.firstOrNull { it.id == message.id } ?: message
            if (current.author != MessageAuthor.Contact || current.wink == null ||
                current.isWinkPending || current.winkTransferError == null) return@withContext Result.success(Unit)
            pendingMessage = current.copy(isWinkPending = true, winkTransferError = null, autoPlayWink = false)
            val updated = if (messages.any { it.id == current.id }) {
                messages.map { if (it.id == current.id) pendingMessage else it }
            } else (messages + pendingMessage).sortedBy { it.timestampMillis }
            if (mutableMessages.compareAndSet(conversations, conversations + (message.contactId to updated))) break
        }
        val metadata = pendingMessage.wink!!.metadata
        val contactId = message.contactId
        val key = "$contactId\n${metadata.creator.lowercase()}\n${metadata.sha1d}"
        val pending = PendingWink(message.id)
        if (pendingWinks.putIfAbsent(key, pending) != null) {
            val error = IllegalStateException("This wink is already downloading. Try again shortly.")
            updateWink(contactId, message.id, error.message)
            return@withContext Result.failure(error)
        }
        startWinkTimeout(key, contactId, pending)
        try {
            check(account.isNotBlank()) { "Sign in before retrying this wink." }
            val email = contactEmail(contactId)
            checkGroupAvailable(email)
            val group = mutableContacts.value.firstOrNull { it.id == email.lowercase() && it.isGroup }
            if (group != null) check(group.members.any {
                it.email.equals(metadata.creator, true) && it.presence.isOnline
            }) {
                "The wink sender is no longer online in this group."
            }
            check(mutableContacts.value.none { it.id == contactId && it.isBlocked }) { "Unblock this contact before retrying." }
            val session = switchboardFor(if (group != null) metadata.creator else email)
            check(account == signedInStorageAccount) { "The selected account changed. Try again." }
            session.retryWink(metadata)
            Result.success(Unit)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            if (pendingWinks.remove(key, pending) && account == signedInStorageAccount)
                updateWink(contactId, message.id, "Wink download was interrupted.")
            throw cancel
        } catch (error: Exception) {
            if (pendingWinks.remove(key, pending) && account == signedInStorageAccount)
                updateWink(contactId, message.id, error.message ?: "Wink download failed.")
            Result.failure(error)
        }
    }

    override suspend fun sendWink(contactId: String, wink: org.aralozkaya.msngr.protocol.WinkReference, bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val email = contactEmail(contactId)
            checkGroupAvailable(email)
            val group = mutableContacts.value.firstOrNull { it.id == email.lowercase() && it.isGroup }
            check(group == null || group.groupFeatures(signedInEmail).winks) {
                "All online group members must support winks."
            }
            check(wink.metadata.matches(bytes)) { "Wink data does not match its metadata." }
            requireNotNull(org.aralozkaya.msngr.protocol.MsnWinkPackage.parse(bytes)) { "Invalid wink package." }
            val metadata = MsnDisplayPicture.create(signedInEmail, bytes, 8,
                location = wink.metadata.location,
                friendly = MsnBase64.encode((wink.name + '\u0000').toByteArray(Charsets.UTF_16LE)))
                .copy(extraAttributes = wink.metadata.extraAttributes)
            val reference = wink.copy(metadata = metadata)
            winkStore?.receive(signedInStorageAccount, email, reference, bytes)
            sharedWinks["${email.lowercase()}\n${metadata.sha1d}"] = SharedWink(
                LocalDisplayPicture(metadata, bytes), System.nanoTime(), emptySet())
            val message = ChatMessage(messageIds.incrementAndGet(), email.lowercase(), MessageAuthor.Me,
                "Wink: ${wink.name}", System.currentTimeMillis(), isSending = true, wink = reference)
            appendMessage(message)
            deliverMessage(message).getOrThrow()
            updateWink(message.contactId, message.id, null)
        }
    }

    override suspend fun sendVoiceClip(contactId: String, clip: VoiceClip): Result<Unit> {
        val email = contactEmail(contactId)
        if (isGroup(email)) return Result.failure(IllegalStateException("Voice clips are not supported in group chats."))
        val valid = MsnVoiceClip.parse(clip.bytes())
            ?: return Result.failure(IllegalArgumentException("Invalid voice clip."))
        val message = ChatMessage(messageIds.incrementAndGet(), email.lowercase(), MessageAuthor.Me,
            "Voice clip", System.currentTimeMillis(), isSending = true, voiceClip = valid)
        appendMessage(message)
        return deliverMessage(message)
    }

    private suspend fun deliverMessage(message: ChatMessage): Result<Unit> {
        val email = contactEmail(message.contactId)
        val result = runCatching {
            check(signedInEmail.isNotEmpty()) { "The Messenger session is not connected." }
            checkGroupAvailable(email)
            check(
                mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.isBlocked != true,
            ) {
                "Messages cannot be sent to a blocked contact."
            }
            if (message.wink != null) {
                val group = mutableContacts.value.firstOrNull { it.id == email.lowercase() && it.isGroup }
                check(group == null || group.groupFeatures(signedInEmail).winks) {
                    "All online group members must support winks."
                }
                check(mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.presence != Presence.Offline) {
                    "Winks require the contact to be online."
                }
                val metadata = message.wink.metadata
                val bytes = winkStore?.bytes(signedInStorageAccount, metadata)
                    ?: sharedWinks["${email.lowercase()}\n${metadata.sha1d}"]?.data?.bytes
                    ?: error("This wink is no longer cached. Add it to your library to send it again.")
                val now = System.nanoTime()
                sharedWinks.entries.removeIf { now - it.value.createdAt > 600_000_000_000L }
                while (sharedWinks.size >= 16) sharedWinks.entries.minByOrNull { it.value.createdAt }?.let { sharedWinks.remove(it.key, it.value) }
                val recipients = group?.members?.filter {
                    it.presence.isOnline && !it.email.equals(signedInEmail, true)
                }?.map { it.email.lowercase() }?.toSet() ?: setOf(email.lowercase())
                sharedWinks["${email.lowercase()}\n${metadata.sha1d}"] = SharedWink(LocalDisplayPicture(metadata, bytes), now, recipients)
                if (group != null) {
                    sendCircleMessages(message, MsnpCircles.winkMessages(
                        email, signedInEmail, machineGuid, metadata, ::nextTransactionId,
                    ))
                } else switchboardFor(email).sendWink(metadata)
            } else if (message.voiceClip != null) {
                check(!isGroup(email)) { "Voice clips are not supported in group chats." }
                check(mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.presence != Presence.Offline) {
                    "Voice clips require the contact to be online."
                }
                val bytes = message.voiceClip.bytes()
                val metadata = MsnDisplayPicture.create(signedInEmail, bytes, 11)
                    .copy(location = "0", sha1c = null)
                val now = System.nanoTime()
                sharedVoiceClips.entries.removeIf { now - it.value.createdAt > 600_000_000_000L }
                if (sharedVoiceClips.size >= 64) sharedVoiceClips.entries.minByOrNull { it.value.createdAt }
                    ?.let { sharedVoiceClips.remove(it.key, it.value) }
                sharedVoiceClips["${email.lowercase()}\n${metadata.sha1d}"] = SharedVoice(LocalDisplayPicture(metadata, bytes), now)
                switchboardFor(email).sendVoiceClip(metadata)
            } else if (message.inkDrawing != null) {
                if (isGroup(email)) {
                    val format = mutableContacts.value.first { it.id == email.lowercase() }
                        .groupFeatures(signedInEmail).inkFormat
                    check(format != null) { "All online group members must support handwriting." }
                    val commands = MsnpCircles.inkMessages(
                        groupEmail = email,
                        senderEmail = signedInEmail,
                        endpointId = machineGuid,
                        drawing = message.inkDrawing,
                        useGif = format == GroupInkFormat.Gif,
                        nextTransactionId = ::nextTransactionId,
                    )
                    sendCircleMessages(message, commands)
                } else {
                    check(
                        mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.presence != Presence.Offline,
                    ) {
                        "Handwriting messages require the contact to be online."
                    }
                    val board = try {
                        switchboardFor(email)
                    } catch (error: SwitchboardException) {
                        if (error.protocolCode == 217) {
                            setContactOffline(email)
                            throw LiveMsnpException("Handwriting messages require the contact to be online.")
                        }
                        throw error
                    }
                    board.sendInk(message.inkDrawing)
                }
            } else if (isGroup(email)) {
                val command = MsnpCircles.message(nextTransactionId(), email, signedInEmail, machineGuid, message.text, style = message.nativeStyle)
                val transaction = checkNotNull(command.transactionId)
                circleDeliveries[transaction] = message.contactId to message.id
                try {
                    send(command)
                } catch (error: Exception) {
                    circleDeliveries.remove(transaction)
                    throw error
                }
                scope.launch {
                    delay(NOTIFICATION_TRANSACTION_TIMEOUT_MILLIS)
                    circleDeliveries.remove(transaction)
                }
            } else if (mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.presence == Presence.Offline) {
                sendOfflineMessage(email, message)
            } else {
                val board = try { switchboardFor(email) } catch (error: SwitchboardException) {
                    // CAL 217 proves no message was submitted to the switchboard. A timeout
                    // or a failure after send must never trigger an automatic duplicate send.
                    if (error.protocolCode != 217) throw error
                    setContactOffline(email)
                    sendOfflineMessage(email, message)
                    null
                }
                board?.sendText(message.text, message.nativeStyle)
            }
            Unit
        }
        val failure = result.exceptionOrNull()
        mutableMessages.update { conversations ->
            // Do not restore a conversation erased while this message was sending.
            val messages = conversations[message.contactId] ?: return@update conversations
            conversations + (message.contactId to messages.map {
                if (it.id == message.id) it.copy(
                    isSending = false,
                    deliveryError = failure?.let { error ->
                        error.message?.trim()?.takeIf(String::isNotBlank)
                            ?: "Message could not be sent."
                    } ?: it.deliveryError,
                ) else it
            })
        }
        result.onFailure { error ->
            if (signedInEmail.isNotEmpty()) {
                discardSwitchboard(email)
                reportConnectionLoss(error)
            }
        }
        return result
    }

    private suspend fun sendOfflineMessage(email: String, message: ChatMessage) {
        val command = MsnpOfflineMessages.send(nextTransactionId(), email, message.text, message.nativeStyle)
        val transaction = checkNotNull(command.transactionId)
        circleDeliveries[transaction] = message.contactId to message.id
        try { send(command) } catch (error: Exception) {
            circleDeliveries.remove(transaction)
            throw error
        }
        // UUM has no success ACK. Preserve correlated server rejections, as with SDG.
        scope.launch {
            delay(NOTIFICATION_TRANSACTION_TIMEOUT_MILLIS)
            circleDeliveries.remove(transaction)
        }
    }

    override suspend fun sendTyping(contactId: String) {
        if (signedInEmail.isEmpty()) return
        val email = contactEmail(contactId)
        val id = email.lowercase()
        if (mutableContacts.value.firstOrNull { it.id == id }?.presence == Presence.Offline) return
        val switchboard = switchboards[id]
        if (switchboard == null && !isGroup(id)) return
        if (isGroup(id) && mutableContacts.value.firstOrNull { it.id == id }?.presence?.isOnline != true) return
        if (mutableContacts.value.firstOrNull { it.id == id }?.isBlocked == true) return
        val now = System.currentTimeMillis()
        var shouldSend = false
        lastTypingSentAt.compute(id) { _, previous ->
            if (previous == null || now - previous >= TYPING_SEND_INTERVAL_MILLIS) {
                shouldSend = true
                now
            } else {
                previous
            }
        }
        if (!shouldSend) return
        try {
            if (isGroup(id)) {
                send(MsnpCircles.message(nextTransactionId(), email, signedInEmail, machineGuid, type = "Control"))
            } else switchboard?.sendTyping()
        } catch (error: Exception) {
            lastTypingSentAt.remove(id, now)
            discardSwitchboard(email)
            reportConnectionLoss(error)
        }
    }

    override suspend fun sendNudge(contactId: String) {
        sendNudgeWithResult(contactId)
    }

    override suspend fun sendNudgeWithResult(contactId: String): Result<Unit> =
        runCatching {
            check(signedInEmail.isNotEmpty()) { "The Messenger session is not connected." }
            val email = contactEmail(contactId)
            check(mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.presence != Presence.Offline) {
                "Nudges cannot be sent to an offline contact."
            }
            checkGroupAvailable(email)
            check(mutableContacts.value.firstOrNull { it.id == email.lowercase() }?.isBlocked != true) {
                "Nudges cannot be sent to a blocked contact."
            }
            val reservation = nudgeLimiter.reserveSend(email)
            var succeeded = false
            try {
                if (isGroup(email)) {
                    send(MsnpCircles.message(nextTransactionId(), email, signedInEmail, machineGuid, type = "Nudge"))
                } else switchboardFor(email).sendNudge()
                succeeded = true
            } finally {
                nudgeLimiter.finishSend(reservation, succeeded)
            }
            appendMessage(
                ChatMessage(
                    id = messageIds.incrementAndGet(),
                    contactId = email.lowercase(),
                    author = MessageAuthor.System,
                    text = "You sent a nudge.",
                    timestampMillis = System.currentTimeMillis(),
                    isNudge = true,
                ),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (error is NudgeCooldownException) return@onFailure
            if (signedInEmail.isNotEmpty()) {
                val email = runCatching { contactEmail(contactId) }.getOrDefault(contactId)
                discardSwitchboard(email)
                reportConnectionLoss(error)
                appendDeliveryFailure(
                    contactId = email.lowercase(),
                    operation = "Nudge",
                    error = error,
                )
            }
        }

    override suspend fun blockContact(contactId: String) = rosterMutationMutex.withLock {
        changeContactBlocking(contactId, blocked = true)
    }

    override suspend fun addContact(email: String, message: String) = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in before adding a contact." }
        val normalized = validateContactAddress(email, owner, mutableContacts.value)
        fun checkSession() {
            check(machineGuid == endpoint && signedInEmail == owner) { "This session ended while adding the contact." }
        }
        val guid = addAddressBookContact(addressBookServer, addressBookTicket, normalized, currentDisplayName, message)
        checkSession()
        try {
            // A removed entry may be absent from ABFindContactsPaged. Always restore its flag by the returned GUID.
            restoreAddressBookContact(addressBookServer, addressBookTicket, guid)
            checkSession()
            // WLM sends these as two incremental commands after ABContactAdd succeeds.
            for (list in listOf(Msnp18Protocol.ALLOW_LIST, Msnp18Protocol.FORWARD_LIST)) {
                checkSession()
                val response = requestNotification(Msnp18Protocol.addToContactList(nextTransactionId(), normalized, list))
                checkSession()
                if (response.verb != "ADL" || response.parameters.firstOrNull() != "OK") throw response.toProtocolError()
            }
            contactStorageIds[normalized] = guid
            deletedContactIds.remove(normalized)
            updateContact(normalized) { existing ->
                (existing ?: Contact(normalized, null, normalized, Presence.Offline))
                    .copy(isForwardListContact = true)
            }
            subscribedContacts = subscribedContacts + (normalized to mutableContacts.value.first { it.id == normalized })
        } catch (error: TimeoutCancellationException) {
            // A transaction timeout is a failed network operation, not a cancelled form.
            throw LiveMsnpException("The contact was saved, but the connection timed out. Reconnect to refresh your contacts.", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LiveMsnpException("The contact was saved, but this session could not update it. Reconnect to refresh your contacts.", error)
        } finally {
            // Recover a persisted SOAP add even if the current socket could not subscribe.
            if (machineGuid == endpoint && signedInEmail == owner) addressBookRefreshRequests?.trySend(Unit)
        }
    }

    override suspend fun deleteContact(contactId: String, block: Boolean) = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        val ticket = addressBookTicket
        val host = addressBookServer
        check(owner.isNotBlank() && ticket.isNotBlank()) { "Sign in before deleting a contact." }
        val id = contactEmail(contactId).lowercase()
        val current = mutableContacts.value.firstOrNull { it.id == id }
        require(!id.equals(owner, true)) { "You cannot delete yourself as a contact." }
        require(current?.isGroup != true) { "Groups cannot be deleted with this action." }
        require(current?.isForwardListContact == true || id in deletedContactIds) { "This contact is no longer in your contacts." }
        fun checkSession() {
            check(machineGuid == endpoint && signedInEmail == owner) { "This session ended while deleting the contact." }
        }
        var appliedBlock = false
        try {
            if (block && current?.isBlocked != true) {
                if (current == null) updateContact(id) { Contact(id, null, id, Presence.Offline, isForwardListContact = false) }
                changeContactBlocking(id, blocked = true)
                checkSession()
                appliedBlock = true
            }
            if (id !in deletedContactIds || current?.isForwardListContact == true) {
                val guid = contactStorageIds[id] ?: throw LiveMsnpException("The contact identifier is unavailable. Reconnect and try again.")
                removeAddressBookContact(host, ticket, guid)
            }
            checkSession()
        } catch (error: TimeoutCancellationException) {
            throw LiveMsnpException("The connection timed out while deleting the contact. Reconnect and check your contacts before trying again.", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (appliedBlock) throw LiveMsnpException("The contact was blocked, but could not be deleted. Please try again.", error)
            throw error
        }
        // SOAP has persisted the deletion. Keep late presence and conversation events off the roster.
        deletedContactIds.add(id)
        updateContact(id) { it?.copy(isForwardListContact = false) }
        closeSwitchboards(id)
        typingJobs.remove(id)?.cancel()
        contactDisplayPictureJobs.remove(id)?.cancel()
        mutableTyping.update { it - id }
        mutableContacts.value.firstOrNull { it.id == id }?.let { subscribedContacts = subscribedContacts + (id to it) }
        try {
            val response = requestNotification(Msnp18Protocol.removeFromContactList(nextTransactionId(), id, Msnp18Protocol.FORWARD_LIST))
            checkSession()
            if (response.verb != "RML" || response.parameters.firstOrNull() != "OK") throw response.toProtocolError()
        } catch (error: TimeoutCancellationException) {
            throw LiveMsnpException("The contact was deleted, but the connection timed out. Reconnect to refresh your contacts.", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LiveMsnpException("The contact was deleted, but this session could not update it. Reconnect to refresh your contacts.", error)
        } finally {
            if (machineGuid == endpoint && signedInEmail == owner) addressBookRefreshRequests?.trySend(Unit)
        }
    }

    override suspend fun unblockContact(contactId: String) = rosterMutationMutex.withLock {
        changeContactBlocking(contactId, blocked = false)
    }

    override suspend fun createGroup(name: String): String = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in before creating a group." }
        val normalized = validateGroupName(name)
        val guid = createAddressBookGroup(addressBookServer, addressBookTicket, normalized, owner)
        checkGroupSession(owner, endpoint)
        val id = "${guid.lowercase()}@live.com"
        val group = Contact(id, normalized, id, Presence.Offline, isGroup = true,
            members = listOf(GroupMember(owner, currentDisplayName, currentPresence, role = GroupMemberRole.Owner)))
        pendingRosterGroups[id] = group
        mutableContacts.update { it.filterNot { old -> old.id == id } + group }
        addressBookRefreshRequests?.trySend(Unit)
        id
    }

    override suspend fun inviteGroupMembers(groupId: String, emails: List<String>, message: String) = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in before inviting group members." }
        val group = mutableContacts.value.firstOrNull { it.id == groupId && it.isGroup }
            ?: error("This group is no longer available.")
        val members = fetchAddressBookGroupMembers(addressBookServer, addressBookTicket, group.email.substringBefore('@'))
        checkGroupSession(owner, endpoint)
        require(group.copy(members = members).canInviteGroupMembers(owner)) { "Only owners and administrators can invite group members." }
        // The service checks current pending state on every retry. A local sent-email cache
        // would prevent inviting someone again after they decline or leave the group.
        val invitees = validateGroupInvitees(emails, owner).filterNot { email -> members.any { it.email.equals(email, true) } }
        try {
            for (email in invitees) {
                checkGroupSession(owner, endpoint)
                inviteAddressBookGroupMember(addressBookServer, addressBookTicket, group.email.substringBefore('@'), email, message)
                checkGroupSession(owner, endpoint)
            }
        } finally {
            if (signedInEmail == owner && machineGuid == endpoint) addressBookRefreshRequests?.trySend(Unit)
        }
    }

    override suspend fun leaveGroup(groupId: String): Unit = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in before leaving a group." }
        val group = mutableContacts.value.firstOrNull { it.id == groupId && it.isGroup }
            ?: error("This group is no longer available.")
        // Check the current server membership, so a role change cannot bypass the owner guard.
        val members = fetchAddressBookGroupMembers(addressBookServer, addressBookTicket, group.email.substringBefore('@'))
        checkGroupSession(owner, endpoint)
        require(group.copy(members = members).canLeaveGroup(owner)) { "Group owners cannot leave their group." }
        val ownGuid = members.first { it.email.equals(owner, true) }.storageId
            ?: error("Your group member identifier is unavailable. Refresh and try again.")
        leaveAddressBookGroup(addressBookServer, addressBookTicket, group.email.substringBefore('@'), ownGuid)
        checkGroupSession(owner, endpoint)
        leftGroupIds += groupId
        pendingRosterGroups.remove(groupId)
        mutableContacts.update { it.filterNot { old -> old.id == groupId } }
        mutableTyping.update { it - groupId }
        typingJobs.remove(groupId)?.cancel()
        subscribedContacts = subscribedContacts - groupId
        addressBookRefreshRequests?.trySend(Unit)
    }

    override suspend fun refreshGroupInvitations() = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in to load group invitations." }
        val snapshot = fetchAddressBook(addressBookServer, addressBookTicket, owner)
        checkGroupSession(owner, endpoint)
        updateGroupInvitations(snapshot.groupInvitations)
    }

    override suspend fun respondToGroupInvitation(groupId: String, accept: Boolean): Unit = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in before responding to a group invitation." }
        val snapshot = fetchAddressBook(addressBookServer, addressBookTicket, owner)
        checkGroupSession(owner, endpoint)
        updateGroupInvitations(snapshot.groupInvitations)
        val invitation = mutableGroupInvitations.value.firstOrNull { it.id == groupId }
            ?: error("This group invitation has already been handled or withdrawn.")
        respondAddressBookGroupInvitation(addressBookServer, addressBookTicket, invitation.id.substringBefore('@'), accept)
        checkGroupSession(owner, endpoint)
        handledGroupInvitations += groupId
        mutableGroupInvitations.update { it.filterNot { old -> old.id == groupId } }
        if (accept) {
            val group = Contact(invitation.id, invitation.groupName, invitation.id, Presence.Offline, isGroup = true)
            pendingRosterGroups[group.id] = group
            mutableContacts.update { it.filterNot { old -> old.id == groupId } + group }
        }
        addressBookRefreshRequests?.trySend(Unit)
    }

    private fun checkGroupSession(owner: String, endpoint: String) {
        check(signedInEmail == owner && machineGuid == endpoint) { "This session ended while updating the group." }
    }

    private fun updateGroupInvitations(invitations: List<GroupInvitation>) {
        handledGroupInvitations.retainAll(invitations.map(GroupInvitation::id).toSet())
        mutableGroupInvitations.value = invitations.filterNot { it.id in handledGroupInvitations }
    }

    override suspend fun refreshContactRequests() = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        check(owner.isNotBlank() && addressBookTicket.isNotBlank()) { "Sign in to load contact requests." }
        val memberships = fetchMessengerMemberships(addressBookServer, addressBookTicket, owner)
        check(machineGuid == endpoint && signedInEmail == owner) { "This session ended while loading requests." }
        mutableContactRequests.value = memberships.requests
    }

    override suspend fun respondToContactRequest(requestId: String, accept: Boolean, block: Boolean): Unit = rosterMutationMutex.withLock {
        val owner = signedInEmail
        val endpoint = machineGuid
        val host = addressBookServer
        val ticket = addressBookTicket
        check(owner.isNotBlank() && ticket.isNotBlank()) { "Sign in before responding to a contact request." }
        require(!accept || !block) { "An accepted contact cannot be blocked." }
        fun checkSession() {
            check(machineGuid == endpoint && signedInEmail == owner) { "This session ended while responding to the request." }
        }
        // Another signed-in client may already have answered or the sender may have withdrawn it.
        val memberships = fetchMessengerMemberships(host, ticket, owner)
        checkSession()
        mutableContactRequests.value = memberships.requests
        val request = memberships.requests.firstOrNull { it.id == requestId.lowercase() }
            ?: throw LiveMsnpException("This contact request has already been handled or withdrawn.")
        val email = request.id
        require(email != owner.lowercase()) { "You cannot respond to a request from yourself." }
        suspend fun membership(role: String, remove: Boolean = false, id: String? = null) {
            checkSession()
            changeMembership(host, ticket, email, role, remove, id)
            checkSession()
        }
        suspend fun publish(list: Int, remove: Boolean = false) {
            checkSession()
            val response = requestNotification(if (remove)
                Msnp18Protocol.removeFromContactList(nextTransactionId(), email, list)
            else Msnp18Protocol.addToContactList(nextTransactionId(), email, list))
            checkSession()
            if (response.verb != (if (remove) "RML" else "ADL") || response.parameters.firstOrNull() != "OK")
                throw response.toProtocolError()
        }
        try {
            if (accept) {
                val existing = mutableContacts.value.firstOrNull { it.id == email && it.isForwardListContact }
                val guid = existing?.let { contactStorageIds[email] }
                    ?: acceptAddressBookContact(host, ticket, email, currentDisplayName)
                checkSession()
                restoreAddressBookContact(host, ticket, guid)
                checkSession()
                contactStorageIds[email] = guid
                membership("Allow")
                publish(Msnp18Protocol.ALLOW_LIST)
                publish(Msnp18Protocol.FORWARD_LIST)
                deletedContactIds.remove(email)
                updateContact(email) { old ->
                    (old ?: Contact(email, request.displayName, email, Presence.Offline))
                        .copy(isForwardListContact = true, isBlocked = false)
                }
            } else if (block) {
                membership("Allow", remove = true)
                membership("Block")
                publish(Msnp18Protocol.ALLOW_LIST, remove = true)
                publish(Msnp18Protocol.BLOCK_LIST)
                updateContact(email) { old ->
                    (old ?: Contact(email, request.displayName, email, Presence.Offline, isForwardListContact = false))
                        .copy(isBlocked = true)
                }
            }
            membership("Reverse")
            // Clear Pending last: failed saves remain answerable instead of losing the invitation.
            membership("Pending", remove = true, id = request.membershipId)
            mutableContactRequests.update { it.filterNot { item -> item.id == email } }
            mutableContacts.value.firstOrNull { it.id == email }?.let {
                subscribedContacts = subscribedContacts + (email to it)
            }
        } catch (error: TimeoutCancellationException) {
            throw LiveMsnpException("The connection timed out while responding. Refresh requests before trying again.", error)
        } catch (error: CancellationException) { throw error }
        finally {
            if (machineGuid == endpoint && signedInEmail == owner) addressBookRefreshRequests?.trySend(Unit)
        }
    }

    private suspend fun changeContactBlocking(contactId: String, blocked: Boolean) {
        if (signedInEmail.isEmpty()) return
        val owner = signedInEmail
        val endpoint = machineGuid
        val email = contactEmail(contactId)
        val id = email.lowercase()
        val current = mutableContacts.value.firstOrNull { it.id == id } ?: return
        if (current.isBlocked == blocked) return

        if (current.isGroup) {
            val response = requestNotification(
                MsnpCircles.subscribe(nextTransactionId(), listOf(current.copy(isBlocked = blocked)), initial = false),
            )
            check(machineGuid == endpoint && signedInEmail == owner) { "This session ended while changing the contact." }
            if (response.verb != "ADL" || response.parameters.firstOrNull() != "OK") throw response.toProtocolError()
            updateContact(email) { it?.copy(isBlocked = blocked) }
            return
        }

        val commands = if (blocked) {
            listOf(
                Msnp18Protocol.removeFromContactList(
                    nextTransactionId(),
                    email,
                    Msnp18Protocol.ALLOW_LIST,
                ),
                Msnp18Protocol.addToContactList(
                    nextTransactionId(),
                    email,
                    Msnp18Protocol.BLOCK_LIST,
                ),
            )
        } else {
            listOf(
                Msnp18Protocol.removeFromContactList(
                    nextTransactionId(),
                    email,
                    Msnp18Protocol.BLOCK_LIST,
                ),
                Msnp18Protocol.addToContactList(
                    nextTransactionId(),
                    email,
                    Msnp18Protocol.ALLOW_LIST,
                ),
            )
        }
        commands.forEach { command ->
            val response = requestNotification(command)
            check(machineGuid == endpoint && signedInEmail == owner) { "This session ended while changing the contact." }
            if (response.verb.firstOrNull()?.isDigit() == true) throw response.toProtocolError()
            if (response.verb != command.verb ||
                !response.parameters.firstOrNull().equals("OK", ignoreCase = true)
            ) {
                throw LiveMsnpException("The notification server rejected the contact-list change.")
            }
        }
        if (blocked) {
            closeSwitchboards(id)
            typingJobs.remove(id)?.cancel()
            mutableTyping.value = mutableTyping.value - id
        }
        updateContact(email) { contact ->
            contact?.copy(
                presence = if (blocked) Presence.Offline else contact.presence,
                unreadCount = if (blocked) 0 else contact.unreadCount,
                isMarkedUnread = !blocked && contact.isMarkedUnread,
                isBlocked = blocked,
            )
        }
    }

    override fun markRead(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) contact.copy(unreadCount = 0, isMarkedUnread = false) else contact
            }
        }
    }

    override fun markUnread(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) {
                    contact.copy(isMarkedUnread = true)
                } else contact
            }
        }
    }

    override fun clearMessages(contactId: String) {
        winkStore?.clearReceived(signedInStorageAccount, contactId)
        mutableMessages.update { it - contactId }
        markRead(contactId)
    }

    override fun clearMessages() {
        winkStore?.clearReceived(signedInStorageAccount)
        mutableMessages.value = emptyMap()
        mutableContacts.update { contacts -> contacts.map { it.copy(unreadCount = 0, isMarkedUnread = false) } }
    }

    override fun setAppInForeground(isForeground: Boolean) {
        appInForeground = isForeground
        if (isForeground) {
            startContactTilePolling()
        } else {
            contactTilePollJob?.cancel()
            contactTilePollJob = null
        }
    }

    override fun close() {
        resetConnection()
    }

    override suspend fun logOutSession(sessionId: String) {
        val session = sessions.value.firstOrNull { it.id == sessionId } ?: return
        require(!session.isCurrent) { "Use local logout to end the current session." }
        val response = requestNotification(Msnp18Protocol.logOutSession(nextTransactionId(), signedInEmail, session.id))
        if (response.verb == "217") {
            throw LiveMsnpException(
                "The server could not deliver remote sign-out (MSNP 217). " +
                    "If all sessions appear offline, change your status to Online and try again.",
            )
        }
        if (response.verb != "UUN" || response.parameters.firstOrNull() != "OK") {
            throw response.toProtocolError()
        }
        val ended = withTimeoutOrNull(10_000) {
            sessions.first { list -> list.none { it.id == session.id } }
        }
        check(ended != null) { "${session.deviceName} did not confirm logout. Please try again." }
    }

    override suspend fun logOutOtherSessions() {
        val publication = checkNotNull(accountSessionsReceived) { "This session has already ended." }
        check(withTimeoutOrNull(10_000) { publication.await(); true } == true) {
            "The server did not provide the active sessions. Please try again."
        }
        super<MsnpClient>.logOutOtherSessions()
    }

    override fun startOfflineMessageDelivery(persist: suspend (ChatMessage) -> Boolean) {
        offlineReceiverJob?.cancel()
        val requests = offlineRefreshRequests ?: return
        val host = offlineServer
        val ticket = offlineTicket
        val owner = signedInEmail
        val signedInAt = signedInAtMillis
        if (ticket.isBlank()) return
        offlineReceiverJob = scope.launch {
            val published = mutableSetOf<String>()
            for (request in requests) {
                var announcedOfflineMessages = false
                var retry = true
                while (isActive && retry) {
                    retry = false
                    try {
                        val metadata = offlineMessageService.metadata(host, ticket)
                        debugOfflineMessages("Retrieved metadata for ${metadata.size} queued messages")
                        for (entry in metadata) {
                            if (!isActive || signedInEmail != owner || offlineRefreshRequests !== requests) return@launch
                            var stage = "download"
                            try {
                                val message = MsnpOfflineMessages.parseMessage(
                                    offlineMessageService.message(host, ticket, entry.id), entry,
                                )
                                if (!isActive || offlineRefreshRequests !== requests) return@launch
                                // A failed disk write must leave the server copy available for retry.
                                stage = "storage"
                                val isNew = persist(message)
                                if (!isActive || offlineRefreshRequests !== requests) return@launch
                                if (published.add(entry.id.lowercase(java.util.Locale.ROOT))) {
                                    appendMessage(message)
                                    updateContact(entry.sender) { contact -> contact ?: Contact(
                                        entry.sender, entry.senderName, entry.sender, Presence.Offline,
                                        isForwardListContact = false,
                                    ) }
                                    if (isNew && mutableContacts.value.firstOrNull { it.id == entry.sender }?.isBlocked != true) {
                                        incrementUnread(entry.sender)
                                        // Invisible sessions also receive live messages through this queue.
                                        // Server timestamps have second precision; only announce older messages.
                                        val receivedWhileSignedOut = message.timestampMillis / 1000 < signedInAt / 1000
                                        if (receivedWhileSignedOut && !announcedOfflineMessages) {
                                            mutableEvents.emit(MessengerEvent.OfflineMessagesReceived)
                                            announcedOfflineMessages = true
                                        }
                                        mutableEvents.emit(MessengerEvent.IncomingMessage(entry.sender, message.id, isOffline = true))
                                    }
                                }
                                stage = "acknowledgement"
                                offlineMessageService.delete(host, ticket, entry.id)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                debugOfflineMessages("Message $stage failed (${error.javaClass.simpleName}); retrying")
                                retry = true
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        debugOfflineMessages("Metadata retrieval failed (${error.javaClass.simpleName}); retrying")
                        retry = true
                    }
                    if (retry) delay(offlineRetryDelayMillis)
                }
            }
        }
        requests.trySend(Unit)
    }

    private fun debugOfflineMessages(message: String) {
        // Diagnostic counts/stages only: never log credentials, sender identities, or message text.
        if (BuildConfig.DEBUG) runCatching { Log.i("MsnpOffline", message) }
    }

    private suspend fun negotiate(config: ServerConfig, email: String): SsoChallenge {
        var host = config.mainServer
        var port = config.mainPort
        repeat(MAX_NOTIFICATION_REDIRECTS) {
            notificationServerHost = host
            transport.connect(host, port)

            val versionTransaction = nextTransactionId()
            send(Msnp18Protocol.version(versionTransaction))
            val version = awaitTransaction(versionTransaction, setOf("VER"))
            if (Msnp18Protocol.VERSION !in version.parameters) {
                throw LiveMsnpException("The selected server does not support MSNP18.")
            }

            val clientVersionTransaction = nextTransactionId()
            send(Msnp18Protocol.clientVersion(clientVersionTransaction, email))
            awaitTransaction(clientVersionTransaction, setOf("CVR"))

            val signInTransaction = nextTransactionId()
            send(Msnp18Protocol.beginSso(signInTransaction, email))
            val response = awaitTransaction(signInTransaction, setOf("USR", "XFR"))
            if (response.verb == "XFR") {
                if (!response.parameters.firstOrNull().equals("NS", ignoreCase = true)) {
                    throw LiveMsnpException("The community server returned an unsupported redirect.")
                }
                val endpoint = response.parameters.getOrNull(1)
                    ?: throw LiveMsnpException("The community server returned an invalid redirect.")
                val redirected = endpoint.parseNotificationEndpoint(config.mainPort)
                host = redirected.first
                port = redirected.second
                transport.close()
                return@repeat
            }

            if (!response.parameters.getOrNull(0).equals("SSO", ignoreCase = true) ||
                !response.parameters.getOrNull(1).equals("S", ignoreCase = true)
            ) {
                throw LiveMsnpException("The community server returned an invalid SSO challenge.")
            }
            return SsoChallenge(
                policy = response.parameters.getOrNull(2)
                    ?: throw LiveMsnpException("The SSO policy was missing."),
                nonce = response.parameters.getOrNull(3)
                    ?: throw LiveMsnpException("The SSO nonce was missing."),
            )
        }
        throw LiveMsnpException("The community server redirected too many times.")
    }

    private suspend fun awaitTransaction(
        transactionId: Int,
        expectedVerbs: Set<String>,
    ): MsnpCommand {
        while (true) {
            val command = transport.receive()
            if (command.transactionId != transactionId) continue
            if (command.verb.firstOrNull()?.isDigit() == true) {
                throw command.toProtocolError()
            }
            if (command.verb in expectedVerbs) return command
        }
    }

    private fun startReceiver() {
        receiverJob?.cancel()
        receiverJob = scope.launch {
            while (isActive && signedInEmail.isNotEmpty()) {
                val command = try {
                    transport.receive()
                } catch (error: Exception) {
                    if (isActive) reportConnectionLoss(error, force = true)
                    break
                }
                val waiter = command.transactionId
                    ?.let(pendingNotificationTransactions::remove)
                if (waiter != null) {
                    waiter.complete(command)
                    continue
                }
                handleIncoming(command)
            }
            pendingNotificationTransactions.values.forEach {
                it.completeExceptionally(
                    IllegalStateException("The notification server disconnected."),
                )
            }
            pendingNotificationTransactions.clear()
        }
    }

    private fun startAddressBookRefresh(requests: Channel<Unit>, host: String, ticket: String, email: String, endpoint: String) {
        subscribedContacts = mutableContacts.value.associateBy(Contact::id)
        addressBookRefreshJob = scope.launch {
            for (request in requests) {
                // Coalesce a burst of NOT/ADL/RML events without polling the address book.
                delay(200)
                while (requests.tryReceive().isSuccess) { /* drain the current burst */ }
                for (attempt in 0..2) {
                    try {
                        rosterMutationMutex.withLock {
                            var snapshot = fetchAddressBook(host, ticket, email)
                            if (machineGuid != endpoint || signedInEmail != email) return@launch
                            leftGroupIds.retainAll(snapshot.contacts.filter(Contact::isGroup).map(Contact::id).toSet())
                            snapshot = snapshot.copy(contacts = snapshot.contacts.filterNot { it.id in leftGroupIds })
                            snapshot.contacts.filter(Contact::isGroup).forEach { pendingRosterGroups.remove(it.id) }
                            val supplemental = pendingRosterGroups.values.toList().mapNotNull { group ->
                                val members = fetchAddressBookGroupMembers(host, ticket, group.email.substringBefore('@'))
                                checkGroupSession(email, endpoint)
                                if (members.none { it.email.equals(email, true) }) {
                                    pendingRosterGroups.remove(group.id)
                                    null
                                } else group.copy(members = members)
                            }
                            snapshot = snapshot.copy(contacts = snapshot.contacts + supplemental)
                            val previousIds = mutableContacts.value.map(Contact::id).toSet()
                            snapshot.contacts.flatMap(Contact::members).forEach { member ->
                                member.storageId?.let { contactStorageIds[member.email.lowercase()] = it }
                            }
                            contactStorageIds.putAll(snapshot.contactStorageIds)
                            deletedContactIds.removeAll(snapshot.contacts.filter { it.isForwardListContact && !it.isGroup }.map(Contact::id).toSet())
                            mutableContacts.update { mergeAddressBookUpdate(it, snapshot, email) }
                            if (!snapshot.failedBlockedLookup) mutableContactRequests.value = snapshot.contactRequests
                            updateGroupInvitations(snapshot.groupInvitations)
                            val refreshed = mutableContacts.value
                            val updatedIds = refreshed.map(Contact::id).toSet()
                            (previousIds - updatedIds).forEach { id ->
                                closeSwitchboards(id)
                                typingJobs.remove(id)?.cancel()
                                mutableTyping.update { it - id }
                            }
                            val changed = refreshed.filter {
                                val old = subscribedContacts[it.id]
                                (it.isForwardListContact || it.isBlocked) &&
                                    mutableContactRequests.value.none { request -> request.id == it.id } &&
                                    (old == null || old.isBlocked != it.isBlocked || old.isForwardListContact != it.isForwardListContact)
                            }
                            val circles = changed.filter(Contact::isGroup)
                            if (circles.isNotEmpty() && !circleAuthenticated) {
                                val response = requestNotification(MsnpCircles.authenticate(nextTransactionId(),
                                    checkNotNull(snapshot.circleTicket) { "The group authentication ticket is missing." }))
                                check(response.verb == "USR" && response.parameters.firstOrNull() == "OK")
                                circleAuthenticated = true
                            }
                            circles.chunked(MAX_CONTACTS_PER_ADL).forEach { batch ->
                                val response = requestNotification(MsnpCircles.subscribe(nextTransactionId(), batch))
                                check(response.verb == "ADL" && response.parameters.firstOrNull() == "OK")
                            }
                            changed.filterNot(Contact::isGroup).chunked(MAX_CONTACTS_PER_ADL).forEach { batch ->
                                val response = requestNotification(Msnp18Protocol.initialContactMemberships(nextTransactionId(),
                                    batch.map { it.email to ((if (it.isForwardListContact) 1 else 0) or (if (it.isBlocked) 4 else 2)) }))
                                check(response.verb == "ADL" && response.parameters.firstOrNull() == "OK")
                            }
                            subscribedContacts = refreshed.associateBy(Contact::id)
                            check(!snapshot.failedBlockedLookup && snapshot.failedMemberLookups.isEmpty()) {
                                "Some address-book details could not be refreshed."
                            }
                        }
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (attempt < 2) delay(1_000L * (attempt + 1))
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && signedInEmail.isNotEmpty()) {
                delay(NOTIFICATION_HEARTBEAT_MILLIS)
                runCatching { send(Msnp18Protocol.ping()) }.getOrElse { break }
            }
        }
    }

    private suspend fun handleIncoming(command: MsnpCommand) {
        if (command.verb.firstOrNull()?.isDigit() == true) {
            val delivery = command.transactionId?.let(circleDeliveries::remove)
            if (delivery != null) mutableMessages.update { conversations ->
                val messages = conversations[delivery.first] ?: return@update conversations
                conversations + (delivery.first to messages.map { message ->
                    if (message.id == delivery.second) message.copy(isSending = false,
                        deliveryError = command.toProtocolError().message) else message
                })
            }
            return
        }
        if (handleCirclePresence(command)) return
        when (command.verb) {
            "UBN" -> {
                if (remoteLogoutRequest(command, signedInEmail, machineGuid)) {
                    endRemoteSession("You were logged out from another session.")
                }
            }
            "OUT" -> if (command.parameters.firstOrNull() == "SSD") {
                reportConnectionLoss(LiveMsnpException("The server is restarting."), force = true)
            } else {
                endRemoteSession("The server ended this session. Please sign in again.")
            }
            "NOT" -> if (command.payload?.let(::isAddressBookNotification) == true) {
                notifyOwnDisplayPictureChanged()
                addressBookRefreshRequests?.trySend(Unit)
                // Escargot publishes before its one-second membership database flush. A bounded
                // follow-up catches newly invited/created groups omitted by the first snapshot.
                addressBookFollowupJob?.cancel()
                val endpoint = machineGuid
                addressBookFollowupJob = scope.launch {
                    delay(1_500)
                    if (machineGuid == endpoint) addressBookRefreshRequests?.trySend(Unit)
                }
            }
            "ADL", "RML" -> if (command.payload != null) addressBookRefreshRequests?.trySend(Unit)
            "SDG" -> receiveCircleMessage(command)
            "NFY" -> receiveCirclePublication(command)
            "ILN", "NLN" -> updateContactPresence(command)
            "FLN" -> setContactOffline(command.parameters.firstOrNull())
            "UBX" -> updateContactPersonalMessage(command)
            "MSG" -> if (command.isInitialDataNotification()) {
                if (initialPresenceSync) {
                    // Contacts absent from the completed presence burst are now known offline.
                    knownContactPresences.clear()
                    knownContactPresences.putAll(mutableContacts.value.associate { it.id to it.presence })
                    initialPresenceSync = false
                }
                initialContactPictureRefreshSignal?.complete(Unit)
                offlineRefreshRequests?.trySend(Unit)
            } else if (command.payload?.let(MsnpOfflineMessages::notification) != null) {
                offlineRefreshRequests?.trySend(Unit)
            }
            "RNG" -> scope.launch { acceptIncomingSwitchboard(command) }
        }
    }

    private fun isGroup(id: String): Boolean = mutableContacts.value.any { it.id == id.lowercase() && it.isGroup }

    private suspend fun sendCircleMessages(message: ChatMessage, commands: List<MsnpCommand>) {
        for (command in commands) {
            val transaction = checkNotNull(command.transactionId)
            circleDeliveries[transaction] = message.contactId to message.id
            try {
                send(command)
            } catch (error: Exception) {
                circleDeliveries.remove(transaction)
                throw error
            }
            scope.launch {
                delay(NOTIFICATION_TRANSACTION_TIMEOUT_MILLIS)
                circleDeliveries.remove(transaction)
            }
        }
    }

    private fun checkGroupAvailable(id: String) {
        val contact = mutableContacts.value.firstOrNull { it.id == id.lowercase() }
        if (contact?.isGroup == true) check(contact.presence.isOnline && !contact.isBlocked) {
            "${contact.displayName} is currently offline. You can send a message when more group members are online."
        }
        // A removed circle must never fall through to a private switchboard.
        if (id.startsWith("00000000-0000-0000-0009-")) check(contact?.isGroup == true) {
            "You are no longer a member of this group."
        }
    }

    private fun receiveCircleMessage(command: MsnpCommand) {
        val rawEnvelope = parseCircleEnvelope(command.payload ?: return) ?: return
        val id = rawEnvelope.groupId ?: return
        val group = mutableContacts.value.firstOrNull { it.id == id && it.isGroup } ?: return
        if (group.isBlocked) return
        val envelope = circleSegments.accept(rawEnvelope) ?: return
        val sender = envelope.senderEmail ?: return
        val mine = sender.equals(signedInEmail, true)
        val type = envelope.headers["message-type"]
        if (type == "Control" && envelope.headers["message-subtype"] == "Typing") {
            if (mine) return
            mutableTyping.update { it + (id to true) }
            typingJobs.remove(id)?.cancel()
            typingJobs[id] = scope.launch {
                delay(TYPING_DISPLAY_MILLIS)
                mutableTyping.update { it + (id to false) }
            }
            return
        }
        val contentType = envelope.headers["content-type"].orEmpty().substringBefore(';').trim()
        if (type.equals("Wink", true) || contentType.equals(Msnp18Protocol.NUDGE_CONTENT_TYPE, true)) {
            val offer = parseSwitchboardMime(
                "Content-Type: ${Msnp18Protocol.NUDGE_CONTENT_TYPE}\r\n\r\n".toByteArray() + envelope.body,
            ) as? SwitchboardMimeMessage.WinkOffer ?: return
            if (!offer.metadata.creator.equals(sender, true) || group.members.none { it.email.equals(sender, true) }) return
            val senderEndpoint = normalizeEndpointId(envelope.routing["from"].orEmpty().substringAfter(";epid=", ""))
            if (mine && senderEndpoint == normalizeEndpointId(machineGuid)) return
            receiveCircleWink(group, sender, offer.metadata)
            return
        }
        val isInk = type.equals("Ink", ignoreCase = true) ||
            contentType.equals(Msnp18Protocol.INK_ISF_CONTENT_TYPE, ignoreCase = true) ||
            contentType.equals(Msnp18Protocol.INK_GIF_CONTENT_TYPE, ignoreCase = true)
        if (type != "Text" && type != "Nudge" && !isInk) return
        val inkDrawing = if (isInk) {
            MsnInkCodec.decodeMimeBody(contentType, envelope.body) ?: return
        } else {
            null
        }
        val senderName = group.members.firstOrNull { it.email == sender }?.displayName
            ?: mutableContacts.value.firstOrNull { it.email.equals(sender, true) }?.displayName ?: sender
        val nudge = type == "Nudge"
        val allowNudgeAlert = nudge && !mine && nudgeLimiter.allowReceivedAlert(id, sender)
        if (nudge && mine) nudgeLimiter.observeOwnNudge(id)
        val message = ChatMessage(
            id = messageIds.incrementAndGet(), contactId = id,
            author = if (nudge) MessageAuthor.System else if (mine) MessageAuthor.Me else MessageAuthor.Contact,
            text = when {
                nudge -> if (mine) "You sent a nudge." else "$senderName sent a nudge!"
                isInk -> "Handwriting"
                else -> String(envelope.body, StandardCharsets.UTF_8)
            },
            timestampMillis = System.currentTimeMillis(), isNudge = nudge,
            senderEmail = sender, senderName = senderName,
            nativeStyle = if (nudge || isInk) NativeTextStyle() else
                NativeTextStyle.fromFormatHeader(envelope.headers["x-mms-im-format"]),
            inkDrawing = inkDrawing,
            autoPlayEmotionSound = !mine && !nudge && !isInk &&
                org.aralozkaya.msngr.protocol.MsnEmotionSound.parse(String(envelope.body, StandardCharsets.UTF_8)) != null,
        )
        appendMessage(message)
        typingJobs.remove(id)?.cancel()
        mutableTyping.update { it + (id to false) }
        if (!mine) {
            incrementUnread(id)
            if (!nudge || allowNudgeAlert) {
                mutableEvents.tryEmit(if (nudge) MessengerEvent.IncomingNudge(id)
                    else MessengerEvent.IncomingMessage(id, message.id))
            }
        }
    }

    private fun receiveCircleWink(group: Contact, sender: String, metadata: MsnDisplayPicture) {
        if (!winkLimiter.allowReceivedAlert(group.id, sender)) return
        val key = "${group.id}\n$sender\n${metadata.sha1d}"
        val id = messageIds.incrementAndGet()
        val pending = PendingWink(id)
        if (pendingWinks.putIfAbsent(key, pending) != null) return
        val mine = sender.equals(signedInEmail, true)
        val name = winkName(metadata)
        appendMessage(ChatMessage(id, group.id, if (mine) MessageAuthor.Me else MessageAuthor.Contact,
            "Wink: $name", System.currentTimeMillis(),
            senderEmail = sender, senderName = group.members.firstOrNull { it.email.equals(sender, true) }?.displayName ?: sender,
            wink = org.aralozkaya.msngr.protocol.WinkReference(metadata, name), isWinkPending = true))
        if (!mine) {
            incrementUnread(group.id)
            mutableEvents.tryEmit(MessengerEvent.IncomingMessage(group.id, id))
        }
        mutableTyping.update { it + (group.id to false) }
        startWinkTimeout(key, group.id, pending)
        val account = signedInStorageAccount
        scope.launch {
            try {
                val cached = winkStore?.bytes(account, metadata)
                if (cached != null) completeWinkTransfer(sender, metadata, cached)
                else {
                    // The announcement belongs to the circle; MSNObject bytes use the sender's
                    // existing point-to-point transport without creating a private wink message.
                    val session = switchboardFor(sender)
                    check(account == signedInStorageAccount) { "The selected account changed. Try again." }
                    session.retryWink(metadata)
                }
            } catch (error: Exception) {
                if (pendingWinks.remove(key, pending) && account == signedInStorageAccount)
                    updateWink(group.id, id, error.message ?: "Wink download failed.")
            }
        }
    }

    private fun winkName(metadata: MsnDisplayPicture): String = runCatching {
        MsnBase64.decode(metadata.friendly)?.toString(Charsets.UTF_16LE)?.trimEnd('\u0000')
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 120 } ?: "Wink"

    /** One object download can satisfy offers in several conversations with the same sender. */
    private fun completeWinkTransfer(sender: String, metadata: MsnDisplayPicture, bytes: ByteArray) {
        val suffix = "\n${sender.lowercase()}\n${metadata.sha1d}"
        val waiting = pendingWinks.entries.filter { it.key.endsWith(suffix) }
            .filter { pendingWinks.remove(it.key, it.value) }
        if (waiting.isEmpty()) return
        val account = signedInStorageAccount
        scope.launch {
            val validationError = runCatching {
                check(metadata.matches(bytes)) { "Wink data does not match its metadata." }
                requireNotNull(org.aralozkaya.msngr.protocol.MsnWinkPackage.parse(bytes)) { "Unsupported or invalid wink package." }
            }.exceptionOrNull()
            for ((key, pending) in waiting) {
                if (account != signedInStorageAccount) return@launch
                val contactId = key.substringBefore('\n')
                val reference = mutableMessages.value[contactId]?.firstOrNull { it.id == pending.messageId }?.wink ?: continue
                val error = validationError ?: runCatching {
                    winkStore?.receive(account, contactId, reference, bytes)
                }.exceptionOrNull()
                if (account == signedInStorageAccount) updateWink(contactId, pending.messageId,
                    error?.let { "Wink could not be opened: ${it.message ?: "invalid package"}" })
            }
        }
    }

    private fun receiveCirclePublication(command: MsnpCommand) {
        val envelope = parseCircleEnvelope(command.payload ?: return) ?: return
        val id = envelope.groupId ?: return
        if (!isGroup(id)) {
            addressBookRefreshRequests?.trySend(Unit)
            return
        }
        if (command.parameters.firstOrNull() == "DEL" && envelope.headers["uri"] == "/circle") {
            mutableContacts.update { it.filterNot { group -> group.id == id } }
            addressBookRefreshRequests?.trySend(Unit)
            return
        }
        val publication = parseCirclePublication(envelope.body)
        val removed = if (command.parameters.firstOrNull() == "DEL") {
            envelope.headers["uri"]?.substringAfter("/user(1:", "")?.substringBefore(')')?.lowercase()
        } else null
        updateContact(id) { group -> group?.copy(
            nickname = publication?.name ?: group.nickname,
            members = when {
                !removed.isNullOrEmpty() -> group.members.map {
                    if (it.email == removed) it.copy(presence = Presence.Offline, clientCapabilities = 0L) else it
                }
                publication?.roster != null -> {
                    val online = publication.roster.toSet()
                    val known = group.members.associateBy(GroupMember::email)
                    (known.keys + online).map { email ->
                        val member = known[email] ?: GroupMember(email,
                            mutableContacts.value.firstOrNull { it.email.equals(email, true) }?.displayName ?: email)
                        member.copy(presence = if (email in online) {
                            if (member.presence.isOnline) member.presence else Presence.Online
                        } else Presence.Offline,
                            clientCapabilities = if (email in online && member.presence.isOnline) member.clientCapabilities else 0L)
                    }
                }
                else -> group.members
            },
        ) }
    }

    /** Circle member presence must never create a direct contact or overwrite its status. */
    private fun handleCirclePresence(command: MsnpCommand): Boolean {
        val raw = when (command.verb) {
            "NLN", "ILN" -> command.parameters.getOrNull(1)
            "FLN", "UBX" -> command.parameters.firstOrNull()
            else -> null
        } ?: return false
        if (";via=9:" !in raw) return false
        val id = raw.substringAfter(";via=9:").substringBefore(';').lowercase()
        val email = raw.substringBefore(';').substringAfter(':').lowercase()
        if (!isGroup(id)) return true
        val status = when (command.verb) {
            "NLN", "ILN" -> Presence.fromMsnpCode(command.parameters.firstOrNull().orEmpty())
            "FLN" -> Presence.Offline
            else -> null
        }
        val hasNetworkId = command.verb == "ILN" && command.parameters.getOrNull(2)?.toIntOrNull() != null
        val nicknameIndex = if (hasNetworkId) 3 else 2
        val name = if (command.verb == "NLN" || command.verb == "ILN")
            command.parameters.getOrNull(nicknameIndex)?.decodeMsnpValue() else null
        updateContact(id) { group -> group?.copy(members = group.members.toMutableList().apply {
            val index = indexOfFirst { it.email == email }
            val previous = getOrNull(index) ?: GroupMember(email)
            val member = previous.copy(
                displayName = name ?: previous.displayName,
                presence = status ?: previous.presence,
                clientCapabilities = when {
                    status?.isOnline == true -> parseClientCapabilities(command.parameters.getOrNull(nicknameIndex + 1))
                    status == Presence.Offline -> 0L
                    else -> previous.clientCapabilities
                },
            )
            if (index < 0) add(member) else set(index, member)
        }) }
        return true
    }

    private suspend fun updateContactPresence(command: MsnpCommand) {
        val presence = Presence.fromMsnpCode(command.parameters.getOrNull(0).orEmpty())
        val email = command.parameters.getOrNull(1)?.normalizedIdentity().orEmpty()
        if (email.isEmpty()) return
        if (email.equals(signedInEmail, ignoreCase = true)) {
            val hasNetworkId = command.verb == "ILN" && command.parameters.getOrNull(2)?.toIntOrNull() != null
            val raw = command.parameters.getOrNull(if (hasNetworkId) 5 else 4)
            val picture = MsnDisplayPicture.parse(raw)
            if (raw == "0" || picture?.creator?.normalizedIdentity()?.equals(email, true) == true) {
                ownPresencePicturePending = true
                val sourceHint = normalizeEndpointId(command.parameters.getOrNull(1).orEmpty().substringAfter(';', ""))
                val token = picture?.sha1d ?: "0"
                val previous = ownPresencePicture?.let { MsnDisplayPicture.parse(it)?.sha1d ?: it }
                if (previous != token || ownPresenceSourceHint != sourceHint) {
                    ownPresencePicture = raw
                    ownPresenceSourceHint = sourceHint
                    ignoredOwnPresencePicture = null
                    downloadedOwnPicture = null
                    notifyOwnDisplayPictureChanged()
                }
            }
            return
        }
        val previousContact = mutableContacts.value.firstOrNull { it.id == email.lowercase() }
        if (previousContact?.isBlocked == true) return
        val hasNetworkId = command.verb == "ILN" &&
            command.parameters.getOrNull(2)?.toIntOrNull() != null
        val nicknameIndex = if (hasNetworkId) 3 else 2
        val capabilitiesIndex = nicknameIndex + 1
        val nickname = command.parameters.getOrNull(nicknameIndex)
            ?.decodeMsnpValue()
            ?.takeIf(String::isNotBlank)
        updateContact(email) { existing ->
            (existing ?: Contact(
                id = email.lowercase(),
                nickname = nickname,
                email = email,
                presence = presence,
                // Presence may arrive for an address-book-only entry or during an in-flight add.
                isForwardListContact = false,
            )).copy(
                nickname = nickname ?: existing?.nickname,
                presence = presence,
            )
        }
        if (presence.isOnline) {
            updateContactDisplayPicture(
                email = email,
                rawObject = command.parameters.getOrNull(capabilitiesIndex + 1),
            )
        } else {
            contactDisplayPictureJobs.remove(email.lowercase())?.cancel()
        }
        // The roster is rebuilt on reconnect, so compare with the last observed server presence,
        // not its temporary offline defaults. ILN can also reveal a sign-in during the outage.
        val previousPresence = knownContactPresences.put(email.lowercase(), presence)
        if (previousPresence != null && previousPresence != presence &&
            (!initialPresenceSync || restoringContactPresence) &&
            (command.verb != "ILN" || restoringContactPresence)
        ) {
            mutableEvents.tryEmit(MessengerEvent.PresenceChanged(email.lowercase(), presence, previousPresence))
        }
    }

    private suspend fun updateContactDisplayPicture(
        email: String,
        rawObject: String?,
        force: Boolean = false,
    ) {
        val id = email.lowercase()
        val picture = MsnDisplayPicture.parse(rawObject)
        if (picture == null) {
            if (rawObject?.trim() == NO_DISPLAY_PICTURE_OBJECT) {
                removeContactDisplayPicture(email)
            } else {
                // Initial presence bursts can omit or temporarily advertise invalid picture
                // metadata. Keep the last validated avatar until an explicit "0" arrives.
                synchronized(contactDisplayPictureStateLock) {
                    contactDisplayPictureJobs.remove(id)?.cancel()
                    contactDisplayPictures.remove(id)
                }
            }
            return
        }
        if (!picture.creator.normalizedIdentity().equals(email, ignoreCase = true)) return
        pictureTrace("ANNOUNCED size=${picture.size} direct=${picture.downloadUrl != null}")
        val (previous, displayedHash, hasDisplayedAvatar) =
            synchronized(contactDisplayPictureStateLock) {
                removedContactTileHashes.remove(id)
                Triple(
                    contactDisplayPictures.put(id, picture),
                    displayedContactDisplayPictureHashes[id],
                    mutableContacts.value.resolveContact(id)?.avatarUri != null,
                )
            }
        if (!force && displayedHash == picture.sha1d && hasDisplayedAvatar) {
            contactDisplayPictureJobs.remove(id)?.cancel()
            return
        }
        if (previous?.sha1d == picture.sha1d &&
            contactDisplayPictureJobs[id]?.isActive == true
        ) {
            return
        }
        val directUrl = picture.downloadUrl
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val bytes = directUrl?.let { url ->
                    displayPictureRequestSemaphore.withPermit {
                        if (contactDisplayPictures[id]?.sha1d != picture.sha1d) {
                            return@withPermit null
                        }
                        runCatching {
                            fetchDisplayPicture(url, MAX_DISPLAY_PICTURE_BYTES)
                        }.getOrNull()
                    }
                }
                if (bytes != null && picture.matches(bytes)) {
                    val accepted = synchronized(contactDisplayPictureStateLock) {
                        if (contactDisplayPictures[id]?.sha1d != picture.sha1d) {
                            false
                        } else {
                            displayedContactDisplayPictureHashes[id] = picture.sha1d
                            contactTileHashes[id] = bytes.sha256()
                            updateContactAvatar(email, bytes.toImageDataUri())
                            true
                        }
                    }
                    if (!accepted) return@launch
                    cacheContactDisplayPicture(email, picture, bytes)
                } else {
                    val endpointReady = awaitContactEndpoint(id)
                    pictureTrace("P2P endpointReady=$endpointReady")
                    requestContactDisplayPicture(email, picture)
                }
            } finally {
                contactDisplayPictureJobs.remove(id, job)
            }
        }
        contactDisplayPictureJobs.put(id, job)?.cancel()
        job.start()
    }

    private suspend fun removeContactDisplayPicture(email: String) {
        val id = email.lowercase()
        synchronized(contactDisplayPictureStateLock) {
            contactDisplayPictureJobs.remove(id)?.cancel()
            contactDisplayPictures.remove(id)
            displayedContactDisplayPictureHashes.remove(id)
            val removedHash = contactTileHashes.remove(id)
            if (removedHash != null) {
                removedContactTileHashes[id] = removedHash
            } else {
                removedContactTileHashes.remove(id)
            }
            contactTileThumbnailHashes.remove(id)
            updateContactAvatar(email, null)
        }

        val account = signedInStorageAccount.takeIf(String::isNotBlank) ?: return
        contactDisplayPictureCacheMutex.withLock {
            runCatching {
                contactDisplayPictureCache.remove(account, email)
            }
        }
    }

    private suspend fun requestContactDisplayPicture(
        email: String,
        picture: MsnDisplayPicture,
    ) {
        if (contactDisplayPictures[email.lowercase()]?.sha1d != picture.sha1d) return
        val startedAt = monotonicMillis()
        pictureTrace("P2P request-start")
        runCatching { switchboardFor(email).requestDisplayPicture(picture) }
            .onSuccess {
                pictureTrace(
                    "P2P request-sent elapsed=${monotonicMillis() - startedAt}ms",
                )
            }
            .onFailure {
                pictureTrace(
                    "P2P request-failed elapsed=${monotonicMillis() - startedAt}ms",
                )
            }
    }

    private suspend fun awaitContactEndpoint(contactId: String): Boolean {
        if (contactEndpointIds[contactId] != null) return true
        val waiter = contactEndpointWaiters.computeIfAbsent(contactId) {
            CompletableDeferred()
        }
        try {
            if (contactEndpointIds[contactId] == null) {
                withTimeoutOrNull(DISPLAY_PICTURE_ENDPOINT_TIMEOUT_MILLIS) {
                    waiter.await()
                }
            }
        } finally {
            contactEndpointWaiters.remove(contactId, waiter)
        }
        return contactEndpointIds[contactId] != null
    }

    private fun setContactOffline(rawIdentity: String?) {
        val email = rawIdentity?.normalizedIdentity().orEmpty()
        if (email.isEmpty()) return
        val id = email.lowercase()
        val previousPresence = knownContactPresences.put(id, Presence.Offline)
        contactDisplayPictureJobs.remove(id)?.cancel()
        contactEndpointWaiters.remove(id)?.cancel()
        contactDisplayPictures.remove(id)
        closeSwitchboards(id)
        updateContact(email) { existing -> existing?.copy(presence = Presence.Offline) }
        if (previousPresence != null && previousPresence != Presence.Offline &&
            (!initialPresenceSync || restoringContactPresence)
        ) {
            mutableEvents.tryEmit(MessengerEvent.PresenceChanged(id, Presence.Offline, previousPresence))
        }
    }

    private fun updateContactPersonalMessage(command: MsnpCommand) {
        val email = command.parameters.firstOrNull()?.normalizedIdentity().orEmpty()
        val payload = command.payload ?: return
        if (email.isEmpty()) return
        if (email.equals(signedInEmail, ignoreCase = true)) {
            val current = sessions.value.firstOrNull(AccountSession::isCurrent) ?: return
            parseAccountSessions(payload, current, sessions.value)?.let {
                mutableSessions.value = it
                accountSessionsReceived?.complete(Unit)
            }
            val firstEndpoint = ENDPOINT_DATA_ID.find(payload.toString(Charsets.UTF_8))?.groupValues?.getOrNull(1)
                ?.let(::normalizeEndpointId)
            if (ownPictureSourceEndpoint != firstEndpoint) {
                downloadedOwnPicture = null
                if (ownPictureSourceEndpoint != null && !ownPresencePicturePending && ownPresenceSourceHint == null) ownPresencePicture = null
                closeSwitchboards(email.lowercase())
                pictureSessions.remove(email.lowercase())
            }
            ownPictureSourceEndpoint = firstEndpoint
            ownPresencePicturePending = false
            // PSM is account-wide. Retain a change from another place when a later
            // picture update republishes UUX, instead of restoring our stale message.
            val personalMessage = if (Regex("<PSM(?:>|\\s|/)").containsMatchIn(payload.toString(Charsets.UTF_8)))
                Msnp18Protocol.parsePersonalMessage(payload) else null
            if (personalMessage != null) currentPersonalMessage = personalMessage
            notifyOwnDisplayPictureChanged(personalMessage)
            return
        }
        val endpointId = ENDPOINT_DATA_ID.find(String(payload, StandardCharsets.UTF_8))
            ?.groupValues
            ?.getOrNull(1)
        if (!endpointId.isNullOrBlank()) {
            val id = email.lowercase()
            contactEndpointIds[id] = endpointId
            pictureTrace("ENDPOINT announced")
            contactEndpointWaiters.remove(id)?.complete(Unit)
        }
        val personalMessage = Msnp18Protocol.parsePersonalMessage(payload)
        updateContact(email) { existing -> existing?.copy(statusMessage = personalMessage) }
    }

    private fun notifyOwnDisplayPictureChanged(personalMessage: String? = null) {
        signedInIdentity?.let { mutableEvents.tryEmit(MessengerEvent.OwnDisplayPictureChanged(it, personalMessage)) }
    }

    private fun updateContact(email: String, transform: (Contact?) -> Contact?) {
        val id = email.lowercase()
        mutableContacts.update { current ->
            val existing = current.firstOrNull { it.id == id }
            val transformed = transform(existing)?.withGroupPresence(signedInEmail) ?: return@update current
            val updated = if (id in deletedContactIds) transformed.copy(isForwardListContact = false) else transformed
            if (existing == null) {
                current + updated
            } else {
                current.map { if (it.id == id) updated else it }
            }
        }
    }

    private fun updateContactAvatar(email: String, avatarUri: String?) {
        mutableContacts.update { contacts -> contacts.map { contact ->
            when {
                !contact.isGroup && contact.email.equals(email, true) -> contact.copy(avatarUri = avatarUri)
                contact.isGroup -> contact.copy(members = contact.members.map { member ->
                    if (member.email.equals(email, true)) member.copy(avatarUri = avatarUri) else member
                })
                else -> contact
            }
        } }
    }

    /**
     * Escargot stores a WLM picture update immediately, but currently does not broadcast a fresh
     * MSNObject to contacts. Poll its lightweight public user-tile resource so the contact list can
     * still reflect the upload without waiting for an unrelated presence change.
     */
    private fun startContactTilePolling() {
        contactTilePollJob?.cancel()
        if (!appInForeground || contactStorageIds.isEmpty() || contactTileHosts.isEmpty()) return
        contactTilePollJob = scope.launch {
            while (isActive && signedInEmail.isNotEmpty()) {
                val onlineContactIds = mutableContacts.value
                    .asSequence()
                    .filter { it.presence.isOnline && !it.isBlocked }
                    .map(Contact::id)
                    .filter(contactStorageIds::containsKey)
                    .toList()
                coroutineScope {
                    onlineContactIds.forEach { contactId ->
                        launch {
                            displayPictureRequestSemaphore.withPermit {
                                pollContactTile(contactId)
                            }
                        }
                    }
                }
                delay(contactTilePollIntervalMillis)
            }
        }
    }

    private fun scheduleInitialContactPictureRefresh() {
        initialContactPictureRefreshJob?.cancel()
        initialContactPictureRefreshSignal?.cancel()
        val rosterComplete = CompletableDeferred<Unit>()
        initialContactPictureRefreshSignal = rosterComplete
        initialContactPictureRefreshJob = scope.launch {
            withTimeoutOrNull(initialContactPictureRefreshDelayMillis) {
                rosterComplete.await()
            }
            refreshOnlineContactPictures()
        }
    }

    /**
     * Initial MSNP18 presence announcements can precede working display-picture metadata. Once the
     * server finishes that burst, explicitly fetch every online contact's stored tile. If a server
     * has no public tile for a contact, retry the MSNObject path advertised by their presence.
     */
    private suspend fun refreshOnlineContactPictures() = coroutineScope {
        mutableContacts.value
            .filter { it.presence.isOnline && !it.isBlocked }
            .forEach { contact ->
                launch {
                    displayPictureRequestSemaphore.withPermit {
                        pollContactTile(contact.id, force = true)
                    }
                    if (!hasVerifiedContactPicture(contact.id)) {
                        contactDisplayPictures[contact.id]?.let { picture ->
                            updateContactDisplayPicture(
                                email = contact.email,
                                rawObject = picture.toEncodedValue(),
                                force = true,
                            )
                        }
                    }
                }
            }
    }

    override suspend fun refreshContactPicture(contactId: String) {
        val contact = mutableContacts.value.resolveContact(contactId) ?: return
        // Public storage is available independently of a contact's MSNP presence.
        displayPictureRequestSemaphore.withPermit {
            pollContactTile(contactId, force = true)
        }
        // A storage thumbnail can be stale or smaller than the announced original.
        if (!hasVerifiedContactPicture(contactId) && contact.presence.isOnline) {
            contactDisplayPictures[contactId]?.let { picture ->
                updateContactDisplayPicture(contact.email, picture.toEncodedValue(), force = true)
            }
        }
    }

    private suspend fun pollContactTile(contactId: String, force: Boolean = false): Boolean {
        synchronized(contactDisplayPictureStateLock) {
            if (hasVerifiedContactPicture(contactId)) return true
        }
        val accountEmail = signedInEmail.takeIf(String::isNotBlank) ?: return false
        val storageAccount = signedInStorageAccount
        val endpointGuid = machineGuid
        val storageId = contactStorageIds[contactId] ?: return false
        val thumbnail = fetchContactTile(storageId, "small")
        if (signedInEmail != accountEmail || machineGuid != endpointGuid) return false
        if (thumbnail != null) {
            val thumbnailHash = thumbnail.sha256()
            val unchanged = synchronized(contactDisplayPictureStateLock) {
                val previousThumbnailHash =
                    contactTileThumbnailHashes.put(contactId, thumbnailHash)
                removedContactTileHashes[contactId] == null &&
                    previousThumbnailHash == thumbnailHash
            }
            if (!force && unchanged) {
                return true
            }
        }
        val bytes = fetchContactTile(storageId, "static") ?: return false
        val hash = bytes.sha256()
        val accepted = synchronized(contactDisplayPictureStateLock) {
            if (signedInEmail != accountEmail || machineGuid != endpointGuid) return@synchronized false
            // An exact MSNObject can finish while an older or smaller HTTP fallback is in flight.
            if (hasVerifiedContactPicture(contactId)) return true
            removedContactTileHashes[contactId]?.let { removedHash ->
                if (removedHash == hash) {
                    pictureTrace("STORAGE ignored stale tile after removal")
                    return@synchronized false
                }
                removedContactTileHashes.remove(contactId, removedHash)
            }
            if (!force && contactTileHashes[contactId] == hash) return@synchronized true
            contactTileHashes[contactId] = hash

            val announcedPicture = contactDisplayPictures[contactId]
            if (announcedPicture?.matches(bytes) == true) {
                displayedContactDisplayPictureHashes[contactId] = announcedPicture.sha1d
            }
            pictureTrace("STORAGE tile-updated bytes=${bytes.size}")
            updateContactAvatar(contactId, bytes.toImageDataUri())
            true
        }
        if (accepted) {
            contactDisplayPictureCacheMutex.withLock {
                val isCurrent = synchronized(contactDisplayPictureStateLock) {
                    signedInEmail == accountEmail && machineGuid == endpointGuid &&
                        contactTileHashes[contactId] == hash
                }
                if (isCurrent) {
                    val email = contactEmail(contactId)
                    runCatching {
                        contactDisplayPictureCache.store(
                            storageAccount, email, MsnDisplayPicture.create(email, bytes), bytes,
                        )
                    }
                }
            }
        }
        return accepted
    }

    /** Called under contactDisplayPictureStateLock; a new MSNObject still permits replacement. */
    private fun hasVerifiedContactPicture(contactId: String): Boolean {
        val displayedHash = displayedContactDisplayPictureHashes[contactId] ?: return false
        val announcedHash = contactDisplayPictures[contactId]?.sha1d
        val uri = mutableContacts.value.resolveContact(contactId)?.avatarUri ?: return false
        return announcedHash == displayedHash ||
            (announcedHash == null && uri.startsWith("data:image/gif;"))
    }

    private suspend fun fetchContactTile(storageId: String, size: String): ByteArray? {
        for (host in contactTileHosts) {
            val bytes = runCatching {
                fetchDisplayPicture(
                    "https://$host/storage/usertile/$storageId/$size",
                    MAX_CONTACT_TILE_BYTES,
                )
            }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private suspend fun switchboardFor(email: String): MsnpSwitchboardSession {
        val id = email.lowercase()
        switchboards[id]?.let { return it }
        return switchboardMutexes.computeIfAbsent(id) { Mutex() }.withLock {
            switchboards[id]?.let { return@withLock it }
            val startedAt = monotonicMillis()
            pictureTrace("SWITCHBOARD request-start")
            val invitation = requestSwitchboard()
            pictureTrace(
                "SWITCHBOARD invitation elapsed=${monotonicMillis() - startedAt}ms",
            )
            lateinit var session: MsnpSwitchboardSession
            session = MsnpSwitchboardSession(
                signedInEmail = signedInEmail,
                machineGuid = machineGuid,
                contactEmail = email,
                invitation = invitation,
                transport = switchboardTransportFactory(),
                scope = scope,
                eventSink = { event -> handleSwitchboardEvent(session, event) },
                localPicture = ::localDisplayPictureSnapshot,
                localVoiceClip = { hash -> localVoiceClipSnapshot(session.contactEmail, hash) },
                localWink = { hash -> localWinkSnapshot(session.contactEmail, hash) },
                cachedWink = { metadata -> winkStore?.bytes(signedInStorageAccount, metadata) },
                acceptWink = { winkLimiter.allowReceivedAlert(session.contactEmail, session.contactEmail) },
                remoteEndpointId = contactEndpointIds[id],
                sharedPictureSession = reusablePictureSession(id),
            )
            pictureSessions[id] = session.pictureSession
            activeSwitchboards.add(session)
            try {
                session.connect()
                pictureTrace(
                    "SWITCHBOARD ready elapsed=${monotonicMillis() - startedAt}ms",
                )
                switchboards[id] = session
                session
            } catch (error: Exception) {
                session.close()
                throw error
            }
        }
    }

    private suspend fun requestSwitchboard(): SwitchboardInvitation {
        val transactionId = nextTransactionId()
        val response = requestNotification(
            Msnp18Protocol.switchboardTransfer(transactionId),
        )
        if (response.verb.firstOrNull()?.isDigit() == true) throw response.toProtocolError()
        if (response.verb != "XFR" ||
            !response.parameters.firstOrNull().equals("SB", ignoreCase = true) ||
            !response.parameters.getOrNull(2).equals("CKI", ignoreCase = true)
        ) {
            throw LiveMsnpException("The notification server returned an invalid switchboard.")
        }
        val endpoint = response.parameters.getOrNull(1)
            ?: throw LiveMsnpException("The switchboard endpoint was missing.")
        val (host, port) = endpoint.parseNotificationEndpoint(switchboardFallbackPort)
        return SwitchboardInvitation(
            host = host,
            port = port,
            authKey = response.parameters.getOrNull(3)
                ?: throw LiveMsnpException("The switchboard authentication key was missing."),
        )
    }

    private suspend fun publishInitialContactList(contacts: List<Contact>) {
        val batches = contacts
            .distinctBy { it.email.lowercase() }
            .chunked(MAX_CONTACTS_PER_ADL)
            .ifEmpty { listOf(emptyList()) }
        batches.forEach { batch ->
            val transactionId = nextTransactionId()
            send(
                Msnp18Protocol.initialContactMemberships(
                    transactionId,
                    batch.map { contact ->
                        val forwardList = if (contact.isForwardListContact) {
                            Msnp18Protocol.FORWARD_LIST
                        } else {
                            0
                        }
                        contact.email to (forwardList or if (contact.isBlocked) {
                            Msnp18Protocol.BLOCK_LIST
                        } else {
                            Msnp18Protocol.ALLOW_LIST
                        })
                    },
                ),
            )
            val response = awaitTransaction(transactionId, setOf("ADL"))
            if (!response.parameters.firstOrNull().equals("OK", ignoreCase = true)) {
                throw LiveMsnpException("The notification server rejected the contact list.")
            }
        }
    }

    private suspend fun requestNotification(command: MsnpCommand): MsnpCommand {
        val transactionId = checkNotNull(command.transactionId)
        val deferred = CompletableDeferred<MsnpCommand>()
        check(pendingNotificationTransactions.putIfAbsent(transactionId, deferred) == null)
        try {
            send(command)
            return withTimeout(NOTIFICATION_TRANSACTION_TIMEOUT_MILLIS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            reportConnectionLoss(error, force = true)
            throw error
        } finally {
            pendingNotificationTransactions.remove(transactionId, deferred)
        }
    }

    private suspend fun acceptIncomingSwitchboard(command: MsnpCommand) {
        val sessionId = command.parameters.getOrNull(0) ?: return
        val endpoint = command.parameters.getOrNull(1) ?: return
        if (!command.parameters.getOrNull(2).equals("CKI", ignoreCase = true)) return
        val authKey = command.parameters.getOrNull(3) ?: return
        val rawInviter = command.parameters.getOrNull(4).orEmpty()
        val inviterEmail = rawInviter.normalizedIdentity()
        if (inviterEmail.isEmpty()) return
        val fromOwnSession = inviterEmail.equals(signedInEmail, ignoreCase = true)
        val inviterEndpoint = normalizeEndpointId(rawInviter.substringAfter(';', ""))
            ?: if (fromOwnSession) sessions.value.firstOrNull { !it.isCurrent }?.id else null
        if (fromOwnSession && inviterEndpoint == machineGuid) return
        if (mutableContacts.value.firstOrNull { it.id == inviterEmail.lowercase() }?.isBlocked == true) return
        val (host, port) = endpoint.parseNotificationEndpoint(switchboardFallbackPort)
        val id = if (fromOwnSession) "" else inviterEmail.lowercase()
        if (!fromOwnSession && !isGroupWinkPeer(id)) ensureSwitchboardContact(id)
        lateinit var session: MsnpSwitchboardSession
        val previousSession = switchboards[id]
        session = MsnpSwitchboardSession(
            signedInEmail = signedInEmail,
            machineGuid = machineGuid,
            contactEmail = id,
            invitation = SwitchboardInvitation(
                host = host,
                port = port,
                authKey = authKey,
                sessionId = sessionId,
                inviterEmail = inviterEmail,
            ),
            transport = switchboardTransportFactory(),
            scope = scope,
            eventSink = { event -> handleSwitchboardEvent(session, event) },
            localPicture = ::localDisplayPictureSnapshot,
            localVoiceClip = { hash -> localVoiceClipSnapshot(session.contactEmail, hash) },
            localWink = { hash -> localWinkSnapshot(session.contactEmail, hash) },
            cachedWink = { metadata -> winkStore?.bytes(signedInStorageAccount, metadata) },
            acceptWink = { winkLimiter.allowReceivedAlert(session.contactEmail, session.contactEmail) },
            remoteEndpointId = contactEndpointIds[id],
            sharedPictureSession = reusablePictureSession(id),
        )
        if (!fromOwnSession) pictureSessions[id] = session.pictureSession
        activeSwitchboards.add(session)
        runCatching { session.connect() }
            // Native WLM can move an existing P2P bridge to a second switchboard. Keep
            // the old receiver alive for its remaining packets and retain sequence state.
            .onSuccess { if (session.contactEmail.isNotEmpty()) registerIncomingSwitchboard(session) }
            .onFailure {
                session.close()
                if (switchboards[id] === previousSession) previousSession?.resumePictureTransfers()
            }
    }

    private fun registerIncomingSwitchboard(session: MsnpSwitchboardSession) {
        if (session.isClosed) return
        val id = session.contactEmail
        if (mutableContacts.value.firstOrNull { it.id == id }?.isBlocked == true) {
            session.close()
            return
        }
        if (!isGroupWinkPeer(id)) ensureSwitchboardContact(id)
        pictureSessions[id] = session.pictureSession
        switchboards[id] = session
    }

    private fun ensureSwitchboardContact(id: String) {
        updateContact(id) { existing -> existing ?: Contact(id, null, id, Presence.Online) }
    }

    private fun isGroupWinkPeer(id: String): Boolean =
        sharedWinks.entries.any { (key, shared) ->
            isGroup(key.substringBefore('\n')) && id in shared.recipients &&
                System.nanoTime() - shared.createdAt <= 600_000_000_000L
        } || pendingWinks.keys.any { key ->
            isGroup(key.substringBefore('\n')) && key.substringAfter('\n').substringBefore('\n') == id
        }

    private fun handleSwitchboardEvent(
        session: MsnpSwitchboardSession,
        event: SwitchboardEvent,
    ) {
        if (event is SwitchboardEvent.DisplayPicture && event.senderEmail.equals(signedInEmail, true)) {
            if (MsnDisplayPicture.parse(ownPresencePicture)?.matches(event.bytes) == true) {
                downloadedOwnPicture = event.bytes.copyOf()
                ownPictureDownload?.complete(event.bytes.copyOf())
            }
            return
        }
        val contactId = session.contactEmail
        if (contactId.isEmpty()) {
            if (event == SwitchboardEvent.Closed) activeSwitchboards.remove(session)
            return
        }
        if (mutableContacts.value.firstOrNull { it.id == contactId }?.isBlocked == true &&
            event != SwitchboardEvent.Closed
        ) {
            session.close()
            return
        }
        if (event is SwitchboardEvent.Text || event is SwitchboardEvent.Ink ||
            event is SwitchboardEvent.WinkOffered || event is SwitchboardEvent.VoiceClipOffered ||
            event is SwitchboardEvent.Nudge
        ) ensureSwitchboardContact(contactId)
        when (event) {
            SwitchboardEvent.ContactResolved -> registerIncomingSwitchboard(session)
            is SwitchboardEvent.WinkOffered -> {
                if (isGroup(contactId)) return
                val key = "$contactId\n${event.senderEmail.lowercase()}\n${event.metadata.sha1d}"
                val id = messageIds.incrementAndGet()
                val pending = PendingWink(id)
                if (pendingWinks.putIfAbsent(key, pending) != null) return
                val mine = event.senderEmail.equals(signedInEmail, true)
                val name = winkName(event.metadata)
                appendMessage(ChatMessage(id, contactId, if (mine) MessageAuthor.Me else MessageAuthor.Contact,
                    "Wink: $name", System.currentTimeMillis(), wink = org.aralozkaya.msngr.protocol.WinkReference(event.metadata, name), isWinkPending = true))
                if (!mine) { incrementUnread(contactId); mutableEvents.tryEmit(MessengerEvent.IncomingMessage(contactId, id)) }
                mutableTyping.update { it + (contactId to false) }
                startWinkTimeout(key, contactId, pending)
            }
            is SwitchboardEvent.WinkReceived -> {
                completeWinkTransfer(event.senderEmail, event.metadata, event.bytes)
            }
            is SwitchboardEvent.VoiceClipOffered -> {
                if (isGroup(contactId)) return
                val key = "$contactId\n${event.senderEmail.lowercase()}\n${event.metadata.sha1d}"
                val id = messageIds.incrementAndGet()
                if (pendingVoiceClips.putIfAbsent(key, id) != null) return
                val mine = event.senderEmail.equals(signedInEmail, true)
                appendMessage(ChatMessage(id, contactId, if (mine) MessageAuthor.Me else MessageAuthor.Contact,
                    "Voice clip", System.currentTimeMillis(), isVoiceClipPending = true))
                if (!mine) {
                    incrementUnread(contactId)
                    mutableEvents.tryEmit(MessengerEvent.IncomingMessage(contactId, id))
                }
                mutableTyping.update { it + (contactId to false) }
                scope.launch {
                    delay(45_000)
                    if (pendingVoiceClips.remove(key, id)) updateVoiceClip(contactId, id, null)
                }
            }
            is SwitchboardEvent.VoiceClipReceived -> {
                val key = "$contactId\n${event.senderEmail.lowercase()}\n${event.metadata.sha1d}"
                val id = pendingVoiceClips.remove(key) ?: return
                updateVoiceClip(contactId, id, MsnVoiceClip.parse(event.bytes))
            }
            is SwitchboardEvent.Ink -> {
                val mine = event.senderEmail.equals(signedInEmail, ignoreCase = true)
                val message = ChatMessage(
                    id = messageIds.incrementAndGet(),
                    contactId = contactId,
                    author = if (mine) MessageAuthor.Me else MessageAuthor.Contact,
                    text = "Handwriting",
                    timestampMillis = System.currentTimeMillis(),
                    inkDrawing = event.drawing,
                )
                appendMessage(message)
                if (!mine) incrementUnread(contactId)
                mutableTyping.value = mutableTyping.value + (contactId to false)
                typingJobs.remove(contactId)?.cancel()
                if (!mine) mutableEvents.tryEmit(MessengerEvent.IncomingMessage(contactId, message.id))
            }
            is SwitchboardEvent.Text -> {
                val mine = event.senderEmail.equals(signedInEmail, ignoreCase = true)
                val message = ChatMessage(
                    id = messageIds.incrementAndGet(),
                    contactId = contactId,
                    author = if (mine) MessageAuthor.Me else MessageAuthor.Contact,
                    text = event.text,
                    autoPlayEmotionSound = !mine && org.aralozkaya.msngr.protocol.MsnEmotionSound.parse(event.text) != null,
                    nativeStyle = event.style,
                    timestampMillis = System.currentTimeMillis(),
                )
                appendMessage(message)
                if (!mine) incrementUnread(contactId)
                mutableTyping.value = mutableTyping.value + (contactId to false)
                typingJobs.remove(contactId)?.cancel()
                if (!mine) mutableEvents.tryEmit(MessengerEvent.IncomingMessage(contactId, message.id))
            }
            is SwitchboardEvent.Typing -> {
                mutableTyping.value = mutableTyping.value + (contactId to true)
                typingJobs.remove(contactId)?.cancel()
                typingJobs[contactId] = scope.launch {
                    delay(TYPING_DISPLAY_MILLIS)
                    mutableTyping.value = mutableTyping.value + (contactId to false)
                    typingJobs.remove(contactId)
                }
            }
            is SwitchboardEvent.Nudge -> {
                val mine = event.senderEmail.equals(signedInEmail, ignoreCase = true)
                val allowAlert = !mine && nudgeLimiter.allowReceivedAlert(contactId, event.senderEmail)
                if (mine) nudgeLimiter.observeOwnNudge(contactId)
                val message = ChatMessage(
                    id = messageIds.incrementAndGet(),
                    contactId = contactId,
                    author = MessageAuthor.System,
                    text = if (mine) "You sent a nudge." else "You received a nudge!",
                    timestampMillis = System.currentTimeMillis(),
                    isNudge = true,
                )
                appendMessage(message)
                if (!mine) {
                    incrementUnread(contactId)
                    if (allowAlert) {
                        mutableEvents.tryEmit(MessengerEvent.IncomingNudge(contactId))
                    }
                }
            }
            is SwitchboardEvent.DisplayPicture -> {
                val picture = synchronized(contactDisplayPictureStateLock) {
                    val currentPicture = contactDisplayPictures[contactId]
                        ?: return@synchronized null
                    if (!currentPicture.matches(event.bytes)) return@synchronized null
                    pictureTrace("P2P received bytes=${event.bytes.size}")
                    displayedContactDisplayPictureHashes[contactId] = currentPicture.sha1d
                    contactTileHashes[contactId] = event.bytes.sha256()
                    updateContactAvatar(event.senderEmail, event.bytes.toImageDataUri())
                    currentPicture
                } ?: return
                scope.launch {
                    cacheContactDisplayPicture(event.senderEmail, picture, event.bytes)
                }
            }
            SwitchboardEvent.Closed -> {
                activeSwitchboards.remove(session)
                switchboards.remove(contactId, session)
                typingJobs.remove(contactId)?.cancel()
                mutableTyping.value = mutableTyping.value + (contactId to false)
            }
        }
    }

    private fun localDisplayPictureSnapshot(): LocalDisplayPicture? {
        return localDisplayPicture
    }

    private fun localWinkSnapshot(peer: String, hash: String): LocalDisplayPicture? =
        sharedWinks.values.firstOrNull {
            it.data.metadata.sha1d == hash && peer.lowercase() in it.recipients &&
                System.nanoTime() - it.createdAt <= 600_000_000_000L
        }?.data

    private fun updateWink(contactId: String, id: Long, error: String?) {
        mutableMessages.update { conversations ->
            conversations + (contactId to conversations[contactId].orEmpty().map {
                if (it.id == id) it.copy(isWinkPending = false, winkTransferError = error, autoPlayWink = error == null) else it
            })
        }
    }

    private fun startWinkTimeout(key: String, contactId: String, pending: PendingWink) {
        scope.launch {
            delay(45_000)
            // Each attempt has its own identity, so an old timeout cannot fail a retry.
            if (pendingWinks.remove(key, pending)) updateWink(contactId, pending.messageId, "Wink download timed out.")
        }
    }

    private fun localVoiceClipSnapshot(peer: String, hash: String): LocalDisplayPicture? =
        sharedVoiceClips["${peer.lowercase()}\n$hash"]?.takeIf {
            System.nanoTime() - it.createdAt <= 600_000_000_000L
        }?.data

    private fun updateVoiceClip(contactId: String, id: Long, clip: VoiceClip?) {
        mutableMessages.update { conversations ->
            val messages = conversations[contactId] ?: return@update conversations
            conversations + (contactId to messages.map {
                if (it.id == id) it.copy(voiceClip = clip, isVoiceClipPending = false,
                    voiceClipTransferError = if (clip == null) "Voice clip could not be downloaded." else null) else it
            })
        }
    }

    private suspend fun cacheContactDisplayPicture(
        contactEmail: String,
        picture: MsnDisplayPicture,
        bytes: ByteArray,
    ) {
        val accountEmail = signedInEmail.takeIf(String::isNotBlank) ?: return
        val storageAccount = signedInStorageAccount
        val contactId = contactEmail.lowercase()
        val hash = bytes.sha256()
        contactDisplayPictureCacheMutex.withLock {
            val isCurrent = synchronized(contactDisplayPictureStateLock) {
                signedInEmail == accountEmail &&
                    signedInStorageAccount == storageAccount &&
                    contactDisplayPictures[contactId]?.sha1d == picture.sha1d &&
                    contactTileHashes[contactId] == hash
            }
            if (!isCurrent) return@withLock
            runCatching {
                contactDisplayPictureCache.store(storageAccount, contactEmail, picture, bytes)
            }
        }
    }

    private fun contactEmail(contactId: String): String = mutableContacts.value
        .firstOrNull { it.id == contactId }
        ?.email
        ?.normalizedIdentity()
        ?: contactId.normalizedIdentity()

    private fun appendMessage(message: ChatMessage) {
        mutableMessages.update { current ->
            current + (message.contactId to (current[message.contactId].orEmpty() + message))
        }
    }

    private fun appendDeliveryFailure(
        contactId: String,
        operation: String,
        error: Throwable,
    ) {
        val reason = error.message?.trim()?.takeIf(String::isNotBlank)
        appendMessage(
            ChatMessage(
                id = messageIds.incrementAndGet(),
                contactId = contactId,
                author = MessageAuthor.System,
                text = if (reason == null) {
                    "$operation could not be sent."
                } else {
                    "$operation could not be sent: $reason"
                },
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun incrementUnread(contactId: String) {
        mutableContacts.update { contacts ->
            contacts.map { contact ->
                if (contact.id == contactId) contact.copy(unreadCount = contact.unreadCount + 1)
                else contact
            }
        }
    }

    private suspend fun send(command: MsnpCommand) {
        try {
            sendMutex.withLock { transport.send(command) }
        } catch (error: Exception) {
            reportConnectionLoss(error)
            throw error
        }
    }

    private fun discardSwitchboard(email: String) {
        closeSwitchboards(email.lowercase())
    }

    private fun closeSwitchboards(contactId: String) {
        switchboards.remove(contactId)?.close()
        activeSwitchboards.filter { it.contactEmail.equals(contactId, ignoreCase = true) }
            .forEach(MsnpSwitchboardSession::close)
    }

    private fun reusablePictureSession(contactId: String): MsnpP2pSession? {
        val session = pictureSessions[contactId] ?: return null
        val endpoint = contactEndpointIds[contactId]
        val destinationEndpoint = session.destination.substringAfter(';', "").trim('{', '}')
        return session.takeIf { destinationEndpoint.equals(endpoint?.trim('{', '}').orEmpty(), ignoreCase = true) }
    }

    private fun reportConnectionLoss(error: Throwable, force: Boolean = false) {
        if (
            signedInEmail.isBlank() ||
            error is CancellationException && error !is TimeoutCancellationException
        ) {
            return
        }
        if (!force && !error.isTransportFailure()) return
        if (!connectionLossReported.compareAndSet(false, true)) return
        mutableEvents.tryEmit(MessengerEvent.ConnectionLost(error.message))
    }

    private fun endRemoteSession(reason: String) {
        if (signedInEmail.isBlank()) return
        // Suppress EOF/write failures and close every switchboard before exposing the sign-out.
        connectionLossReported.set(true)
        resetConnection()
        mutableEvents.tryEmit(MessengerEvent.SignedOut(reason))
    }

    private fun Throwable.isTransportFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is IOException) return true
            val message = current.message.orEmpty().lowercase()
            if (
                "broken pipe" in message ||
                "connection reset" in message ||
                "socket closed" in message ||
                "not connected" in message ||
                "disconnected" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun nextTransactionId(): Int = transactionIds.incrementAndGet()

    private fun resetConnection(preserveRequests: Boolean = false, preserveContactPresence: Boolean = false) {
        if (!preserveContactPresence) {
            knownContactPresences.clear()
            presenceAccountId = ""
        }
        restoringContactPresence = false
        initialPresenceSync = false
        if (!preserveRequests) {
            mutableContactRequests.value = emptyList()
            mutableGroupInvitations.value = emptyList()
            requestsAccountId = ""
        }
        offlineReceiverJob?.cancel()
        offlineReceiverJob = null
        offlineRefreshRequests?.close()
        offlineRefreshRequests = null
        offlineServer = ""
        offlineTicket = ""
        addressBookRefreshJob?.cancel()
        addressBookRefreshJob = null
        addressBookFollowupJob?.cancel()
        addressBookFollowupJob = null
        addressBookRefreshRequests?.close()
        addressBookRefreshRequests = null
        addressBookServer = ""
        addressBookTicket = ""
        currentDisplayName = ""
        subscribedContacts = emptyMap()
        pendingRosterGroups.clear()
        leftGroupIds.clear()
        handledGroupInvitations.clear()
        deletedContactIds.clear()
        circleAuthenticated = false
        receiverJob?.cancel()
        receiverJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        contactTilePollJob?.cancel()
        contactTilePollJob = null
        initialContactPictureRefreshJob?.cancel()
        initialContactPictureRefreshJob = null
        initialContactPictureRefreshSignal?.cancel()
        initialContactPictureRefreshSignal = null
        pendingNotificationTransactions.values.forEach { it.cancel() }
        pendingNotificationTransactions.clear()
        activeSwitchboards.toList().forEach(MsnpSwitchboardSession::close)
        activeSwitchboards.clear()
        pictureSessions.clear()
        sharedWinks.clear()
        pendingWinks.clear()
        sharedVoiceClips.clear()
        pendingVoiceClips.clear()
        circleDeliveries.clear()
        circleSegments.clear()
        switchboards.clear()
        switchboardMutexes.clear()
        typingJobs.values.forEach(Job::cancel)
        typingJobs.clear()
        lastTypingSentAt.clear()
        contactDisplayPictureJobs.values.forEach(Job::cancel)
        contactDisplayPictureJobs.clear()
        contactDisplayPictures.clear()
        displayedContactDisplayPictureHashes.clear()
        removedContactTileHashes.clear()
        contactStorageIds.clear()
        contactTileThumbnailHashes.clear()
        contactTileHashes.clear()
        contactEndpointIds.clear()
        contactEndpointWaiters.values.forEach { it.cancel() }
        contactEndpointWaiters.clear()
        signedInEmail = ""
        signedInAtMillis = 0L
        signedInStorageAccount = ""
        signedInIdentity = null
        machineGuid = ""
        accountSessionsReceived?.completeExceptionally(LiveMsnpException("This session has already ended."))
        accountSessionsReceived = null
        mutableSessions.value = emptyList()
        currentPresence = Presence.Online
        currentPersonalMessage = ""
        storageServer = ""
        storageOwnerCid = "0"
        contactTileHosts = emptyList()
        notificationServerHost = ""
        storageTicket = ""
        storageProfileResourceId = null
        storageExpressionProfileResourceId = null
        storagePhotoResourceId = null
        storageDisplayPictureAttached = false
        storageDisplayPictureHash = null
        localDisplayPicture = null
        lastAdvertisedDisplayPictureHash = null
        ownPresencePicture = null
        ownPresencePicturePending = false
        ownPictureSourceEndpoint = null
        ownPresenceSourceHint = null
        ignoredOwnPresencePicture = null
        downloadedOwnPicture = null
        ownPictureDownload?.cancel()
        ownPictureDownload = null
        mutablePictureStorageIssue.value = null
        switchboardFallbackPort = DEFAULT_SWITCHBOARD_PORT
        transport.close()
        mutableContacts.value = emptyList()
        mutableMessages.value = emptyMap()
        mutableTyping.value = emptyMap()
    }

    private fun MsnpCommand.toProtocolError(): LiveMsnpException = when (verb.toIntOrNull()) {
        911 -> LiveMsnpException("The community server rejected these credentials.")
        500, 601, 910, 921 -> LiveMsnpException("The community server is unavailable.")
        else -> LiveMsnpException("The community server returned MSNP error $verb.")
    }

    private fun pictureTrace(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.i("MsnpPicture", message) }
    }

    private data class SsoChallenge(val policy: String, val nonce: String)

    private data class SignInData(
        val addressBook: AddressBookSnapshot,
        val storage: StorageSignInData,
    )

    private data class StorageSignInData(
        val profile: StorageProfile?,
        val picture: ByteArray?,
        val available: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as StorageSignInData

            if (available != other.available) return false
            if (profile != other.profile) return false
            if (!picture.contentEquals(other.picture)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = available.hashCode()
            result = 31 * result + (profile?.hashCode() ?: 0)
            result = 31 * result + (picture?.contentHashCode() ?: 0)
            return result
        }
    }

    private companion object {
        const val MAX_NOTIFICATION_REDIRECTS = 5
        const val MAX_CONTACTS_PER_ADL = 150
        const val DEFAULT_SWITCHBOARD_PORT = 1864
        const val NOTIFICATION_TRANSACTION_TIMEOUT_MILLIS = 15_000L
        const val TYPING_SEND_INTERVAL_MILLIS = 5_000L
        const val TYPING_DISPLAY_MILLIS = 6_000L
        const val NOTIFICATION_HEARTBEAT_MILLIS = 45_000L
        const val MAX_DISPLAY_PICTURE_BYTES = org.aralozkaya.msngr.protocol.DisplayPictureLimits.MAX_BYTES
        const val MAX_CONTACT_TILE_BYTES = 1024 * 1024
        const val DISPLAY_PICTURE_ENDPOINT_TIMEOUT_MILLIS = 500L
        const val MAX_CONCURRENT_DISPLAY_PICTURE_REQUESTS = 6
        const val CONTACT_TILE_POLL_INTERVAL_MILLIS = 2_000L
        const val INITIAL_CONTACT_PICTURE_REFRESH_DELAY_MILLIS = 5_000L
        const val NO_DISPLAY_PICTURE_OBJECT = "0"
        val ENDPOINT_DATA_ID = Regex(
            "<EndpointData\\s+[^>]*id=\"\\{?([^}\"]+)\\}?\"",
            RegexOption.IGNORE_CASE,
        )
    }
}

private class LiveMsnpException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class SsoToken(
    val ticket: String,
    val response: String,
    val contactsTicket: String,
    val storageTicket: String,
    val offlineTicket: String = "",
)

/** Windows Live ID RST request and MSNP18 RPS proof generation. */
private object MsnpSsoAuthenticator {
    private const val SOAP_ACTION =
        "http://schemas.xmlsoap.org/ws/2004/04/security/trust/RST/Issue"
    private const val MESSENGER_DOMAIN = "messengerclear.live.com"
    private const val CONTACTS_DOMAIN = "contacts.msn.com"
    private const val STORAGE_DOMAIN = "storage.live.com"
    private val secureRandom = SecureRandom()

    suspend fun authenticate(
        host: String,
        email: String,
        password: String,
        policy: String,
        nonce: String,
    ): SsoToken = withContext(Dispatchers.IO) {
        val body = buildRequest(email, password, policy)
        val connection = (URL("https://${host.normalizedHost()}/RST.srf").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MILLIS
            readTimeout = HTTP_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", SOAP_ACTION)
            setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val detail = responseBody.xmlText("faultstring")
                    .ifBlank { "The authentication service rejected these credentials." }
                throw LiveMsnpException(detail)
            }

            val credentials = parseSsoCredentials(responseBody)
            val credential = credentials[MESSENGER_DOMAIN]
                ?.takeIf { it.ticket.isNotBlank() && it.secret.isNotBlank() }
                ?: throw LiveMsnpException(
                    "The authentication service did not return a Messenger credential.",
                )
            val contactsTicket = credentials[CONTACTS_DOMAIN]
                ?.ticket
                ?.takeIf(String::isNotBlank)
                ?: throw LiveMsnpException(
                    "The authentication service did not return an address-book credential.",
                )
            val storageTicket = credentials[STORAGE_DOMAIN]
                ?.ticket
                ?.takeIf(String::isNotBlank)
                ?: throw LiveMsnpException(
                    "The authentication service did not return a storage credential.",
                )
            SsoToken(
                ticket = credential.ticket,
                response = createRpsResponse(credential.secret, nonce),
                contactsTicket = contactsTicket,
                storageTicket = storageTicket,
                offlineTicket = credentials["messenger.msn.com"]?.ticket
                    ?.takeIf(String::isNotBlank)
                    ?: throw LiveMsnpException("The authentication service did not return an offline-message credential."),
            )
        } catch (error: LiveMsnpException) {
            throw error
        } catch (error: Exception) {
            throw LiveMsnpException("Could not authenticate with the community service.", error)
        } finally {
            connection.disconnect()
        }
    }

    fun buildRequest(email: String, password: String, policy: String): String {
        val requests = listOf(
            Triple(1, MESSENGER_DOMAIN, policy),
            Triple(2, "messenger.msn.com", "?id=507"),
            Triple(3, "contacts.msn.com", "MBI"),
            Triple(4, "messengersecure.live.com", "MBI_SSL"),
            Triple(5, STORAGE_DOMAIN, "MBI"),
            Triple(6, "sup.live.com", "MBI"),
        ).joinToString(separator = "") { (id, domain, policyReference) ->
            """
            <wst:RequestSecurityToken Id="RST$id">
              <wst:RequestType>http://schemas.xmlsoap.org/ws/2004/04/security/trust/Issue</wst:RequestType>
              <wsp:AppliesTo><wsa:EndpointReference><wsa:Address>${domain.escapeXml()}</wsa:Address></wsa:EndpointReference></wsp:AppliesTo>
              <wsse:PolicyReference URI="${policyReference.escapeXml()}"></wsse:PolicyReference>
            </wst:RequestSecurityToken>
            """.trimIndent()
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:wsse="http://schemas.xmlsoap.org/ws/2003/06/secext"
              xmlns:wsp="http://schemas.xmlsoap.org/ws/2002/12/policy"
              xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/03/addressing"
              xmlns:wst="http://schemas.xmlsoap.org/ws/2004/04/trust">
              <Header>
                <ps:AuthInfo xmlns:ps="http://schemas.microsoft.com/Passport/SoapServices/PPCRL" Id="PPAuthInfo">
                  <ps:HostingApp>{7108E71A-9926-4FCB-BCC9-9A9D3F32E423}</ps:HostingApp>
                  <ps:BinaryVersion>4</ps:BinaryVersion><ps:UIVersion>1</ps:UIVersion>
                  <ps:Cookies></ps:Cookies><ps:RequestParams>AQAAAAIAAABsYwQAAAAxMDMz</ps:RequestParams>
                </ps:AuthInfo>
                <wsse:Security><wsse:UsernameToken Id="user">
                  <wsse:Username>${email.escapeXml()}</wsse:Username>
                  <wsse:Password>${password.take(16).escapeXml()}</wsse:Password>
                </wsse:UsernameToken></wsse:Security>
              </Header>
              <Body>
                <ps:RequestMultipleSecurityTokens xmlns:ps="http://schemas.microsoft.com/Passport/SoapServices/PPCRL" Id="RSTS">
                  <wst:RequestSecurityToken Id="RST0">
                    <wst:RequestType>http://schemas.xmlsoap.org/ws/2004/04/security/trust/Issue</wst:RequestType>
                    <wsp:AppliesTo><wsa:EndpointReference><wsa:Address>http://Passport.NET/tb</wsa:Address></wsa:EndpointReference></wsp:AppliesTo>
                  </wst:RequestSecurityToken>
                  $requests
                </ps:RequestMultipleSecurityTokens>
              </Body>
            </Envelope>
        """.trimIndent().trimStart()
    }

    fun parseTickets(xml: String): Map<String, String> =
        parseSsoCredentials(xml).mapValues { it.value.ticket }

    private fun parseSsoCredentials(xml: String): Map<String, MessengerCredential> {
        val document = secureDocumentBuilderFactory().newDocumentBuilder()
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))
        val responses = document.getElementsByTagNameNS("*", "RequestSecurityTokenResponse")
        val credentials = mutableMapOf<String, MessengerCredential>()
        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val address = response.firstText("Address")
            val ticket = response.firstText("BinarySecurityToken")
            val secret = response.firstText("BinarySecret")
            if (address.isNotBlank() && ticket.isNotBlank()) {
                credentials[address] = MessengerCredential(ticket, secret)
            }
        }
        if (credentials.isNotEmpty()) return credentials
        val fault = document.documentElement.firstText("faultstring")
        throw LiveMsnpException(fault.ifBlank {
            "The authentication service returned an invalid response."
        })
    }

    private fun createRpsResponse(encodedSecret: String, nonce: String): String {
        val secret = Base64.decode(encodedSecret, Base64.DEFAULT)
        val hashKey = deriveKey(secret, "SESSION KEY HASH")
        val encryptionKey = deriveKey(secret, "SESSION KEY ENCRYPTION")
        val nonceBytes = nonce.toByteArray(StandardCharsets.UTF_8)
        val paddingLength = 8 - (nonceBytes.size % 8)
        val paddedNonce = nonceBytes + ByteArray(paddingLength) { paddingLength.toByte() }
        val iv = ByteArray(8).also(secureRandom::nextBytes)
        val hash = hmacSha1(hashKey, nonceBytes)
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(encryptionKey, "DESede"),
                IvParameterSpec(iv),
            )
            doFinal(paddedNonce)
        }
        val header = ByteBuffer.allocate(7 * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(28)
            .putInt(1)
            .putInt(0x6603)
            .putInt(0x8004)
            .putInt(iv.size)
            .putInt(hash.size)
            .putInt(cipher.size)
            .array()
        val response = ByteArrayOutputStream().use {
            it.write(header)
            it.write(iv)
            it.write(hash)
            it.write(cipher)
            it.toByteArray()
        }
        return Base64.encodeToString(response, Base64.NO_WRAP)
    }

    private fun deriveKey(key: ByteArray, data: String): ByteArray {
        val magic = "WS-SecureConversation".toByteArray(StandardCharsets.UTF_8)
        val value = data.toByteArray(StandardCharsets.UTF_8)
        val hash1 = hmacSha1(key, magic + value)
        val hash2 = hmacSha1(key, hash1 + magic + value)
        val hash3 = hmacSha1(key, hash1)
        val hash4 = hmacSha1(key, hash3 + magic + value)
        return hash2 + hash4.copyOf(4)
    }

    private fun hmacSha1(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(key, "HmacSHA1"))
            doFinal(value)
        }

    private data class MessengerCredential(val ticket: String, val secret: String)

    private const val HTTP_TIMEOUT_MILLIS = 10_000
}

private fun String.normalizedHost(): String = trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

private fun String.parseNotificationEndpoint(defaultPort: Int): Pair<String, Int> {
    val value = trim()
    val separator = value.lastIndexOf(':')
    if (separator <= 0) return value to defaultPort
    val parsedPort = value.substring(separator + 1).toIntOrNull() ?: return value to defaultPort
    return value.substring(0, separator) to parsedPort
}

private fun String.normalizedIdentity(): String {
    val withoutEndpoint = substringBefore(';')
    val prefix = withoutEndpoint.substringBefore(':', missingDelimiterValue = "")
    return if (prefix.toIntOrNull() != null) withoutEndpoint.substringAfter(':') else withoutEndpoint
}

private fun String.decodeMsnpValue(): String = runCatching {
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}.getOrDefault(this)

private fun String.escapeXml(): String = buildString(length) {
    this@escapeXml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}

private fun Element.firstText(localName: String): String =
    getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty().trim()

private fun String.xmlText(localName: String): String = runCatching {
    val document = secureDocumentBuilderFactory().newDocumentBuilder()
        .parse(byteInputStream(StandardCharsets.UTF_8))
    document.documentElement.firstText(localName)
}.getOrDefault("")

private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }

internal fun buildMsnpSsoRequest(
    email: String,
    password: String,
    policy: String,
): String = MsnpSsoAuthenticator.buildRequest(email, password, policy)

internal fun parseMsnpSsoTickets(xml: String): Map<String, String> =
    MsnpSsoAuthenticator.parseTickets(xml)

private suspend fun fetchRemoteDisplayPicture(url: String, maxBytes: Int): ByteArray? =
    withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return@withContext null
        if (parsed.protocol !in setOf("http", "https")) return@withContext null
        val connection = (parsed.openConnection() as? HttpURLConnection)
            ?: return@withContext null
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
            if (connection.responseCode !in 200..299) return@withContext null
            val declaredLength = connection.contentLengthLong
            if (declaredLength > maxBytes) return@withContext null
            connection.inputStream.use { input ->
                val initialCapacity = declaredLength
                    .takeIf { it in 1..maxBytes.toLong() }
                    ?.toInt()
                    ?: (8 * 1024)
                val output = ByteArrayOutputStream(initialCapacity)
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) return@withContext null
                    output.write(buffer, 0, count)
                }
                output.toByteArray().takeIf(ByteArray::isNotEmpty)
            }
        } finally {
            connection.disconnect()
        }
    }

private fun ByteArray.toImageDataUri(): String {
    val mimeType = when {
        hasPngSignature() -> "image/png"
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
            this[2] == 0xFF.toByte() -> "image/jpeg"
        size >= 6 && String(this, 0, 6, StandardCharsets.US_ASCII).startsWith("GIF") -> "image/gif"
        else -> "image/*"
    }
    return "data:$mimeType;base64,${MsnBase64.encode(this)}"
}

private fun ByteArray.hasPngSignature(): Boolean = size >= 8 &&
    this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
    this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() &&
    this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() &&
    this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

private fun String.httpHostOrNull(): String? = trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')
    .substringBefore(':')
    .takeIf { host ->
        host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

private fun MsnpCommand.isInitialDataNotification(): Boolean = payload
    ?.toString(StandardCharsets.UTF_8)
    ?.contains(INITIAL_DATA_NOTIFICATION_CONTENT_TYPE, ignoreCase = true)
    ?: false

private const val INITIAL_DATA_NOTIFICATION_CONTENT_TYPE =
    "text/x-msmsgsinitialmdatanotification"
