package org.aralozkaya.msngr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aralozkaya.msngr.MsnEmoticonRange
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.UserProfileSnapshot
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.InkDrawing

private val emptyAccountSessions = MutableStateFlow<List<AccountSession>>(emptyList()).asStateFlow()
private val emptyContactRequests = MutableStateFlow<List<ContactRequest>>(emptyList()).asStateFlow()
private val emptyGroupInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList()).asStateFlow()
private val emptyCategories = MutableStateFlow<List<ContactCategory>>(emptyList()).asStateFlow()
private val noPictureStorageIssue = MutableStateFlow<String?>(null).asStateFlow()
private val emptyPhotoShares = MutableStateFlow<Map<String, PhotoShare>>(emptyMap()).asStateFlow()
private val emptyGameSessions = MutableStateFlow<Map<String, GameSession>>(emptyMap()).asStateFlow()
private val emptyNudgeCooldowns = MutableStateFlow<Map<String, Long>>(emptyMap()).asStateFlow()
private val emptyPortalState = MutableStateFlow(PortalState()).asStateFlow()

interface MsnpClient {
    val nudgeCooldowns: StateFlow<Map<String, Long>> get() = emptyNudgeCooldowns
    val portalState: StateFlow<PortalState> get() = emptyPortalState
    suspend fun refreshPortal(includeAds: Boolean = true) = Unit
    suspend fun refreshWhatsNew() = Unit
    suspend fun refreshAds() = Unit
    val displayPictureStorageIssue: StateFlow<String?> get() = noPictureStorageIssue
    val contacts: StateFlow<List<Contact>>
    val messages: StateFlow<Map<String, List<ChatMessage>>>
    val typing: StateFlow<Map<String, Boolean>>
    val events: SharedFlow<MessengerEvent>
    val sessions: StateFlow<List<AccountSession>> get() = emptyAccountSessions
    val contactRequests: StateFlow<List<ContactRequest>> get() = emptyContactRequests
    val groupInvitations: StateFlow<List<GroupInvitation>> get() = emptyGroupInvitations
    val categories: StateFlow<List<ContactCategory>> get() = emptyCategories

    suspend fun createCategory(name: String, memberIds: List<String> = emptyList()): String =
        throw UnsupportedOperationException("Creating categories is not supported by this client.")
    suspend fun renameCategory(categoryId: String, newName: String) {
        throw UnsupportedOperationException("Renaming categories is not supported by this client.")
    }
    suspend fun updateCategoryMembers(categoryId: String, memberIds: Set<String>) {
        throw UnsupportedOperationException("Updating categories is not supported by this client.")
    }
    suspend fun deleteCategory(categoryId: String) {
        throw UnsupportedOperationException("Deleting categories is not supported by this client.")
    }
    suspend fun setContactCategories(contactId: String, categoryIds: Set<String>) {
        throw UnsupportedOperationException("Updating contact categories is not supported by this client.")
    }
    suspend fun setContactFavorite(contactId: String, favorite: Boolean) {
        throw UnsupportedOperationException("Updating favorite contacts is not supported by this client.")
    }

    suspend fun createGroup(name: String): String = throw UnsupportedOperationException("Creating groups is not supported by this client.")
    suspend fun inviteGroupMembers(groupId: String, emails: List<String>, message: String = "") { throw UnsupportedOperationException("Group invitations are not supported by this client.") }
    suspend fun leaveGroup(groupId: String) { throw UnsupportedOperationException("Leaving groups is not supported by this client.") }
    suspend fun refreshGroupInvitations() = Unit
    suspend fun respondToGroupInvitation(groupId: String, accept: Boolean) { throw UnsupportedOperationException("Group invitations are not supported by this client.") }

    suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot>
    suspend fun changePresence(presence: Presence)
    suspend fun changeNickname(nickname: String)
    suspend fun changePersonalMessage(statusMessage: String)
    suspend fun changeCurrentMedia(media: CurrentMedia?) = Unit
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
    val photoShares: StateFlow<Map<String, PhotoShare>> get() = emptyPhotoShares
    suspend fun requestPhotoOriginal(contactId: String, shareId: String, photoId: String): Result<Unit> = Result.failure(UnsupportedOperationException("Photo sharing is unavailable."))
    suspend fun sharePhotos(contactId: String, photos: List<PhotoToShare>): Result<Unit> = Result.failure(UnsupportedOperationException("Photo sharing is unavailable."))
    suspend fun acceptPhotos(contactId: String, id: String): Result<Unit> = Result.failure(UnsupportedOperationException("Photo sharing is unavailable."))
    suspend fun stopPhotos(contactId: String, id: String): Result<Unit> = Result.failure(UnsupportedOperationException("Photo sharing is unavailable."))
    suspend fun selectPhoto(contactId: String, id: String, photoId: String): Result<Unit> = Result.failure(UnsupportedOperationException("Photo sharing is unavailable."))
    val gameSessions: StateFlow<Map<String, GameSession>> get() = emptyGameSessions
    suspend fun startGame(contactId: String, entry: GameCatalogEntry): Result<Unit> = Result.failure(UnsupportedOperationException("Games and activities are unavailable."))
    suspend fun acceptGame(contactId: String, id: String): Result<Unit> = Result.failure(UnsupportedOperationException("Games and activities are unavailable."))
    suspend fun stopGame(contactId: String, id: String): Result<Unit> = Result.failure(UnsupportedOperationException("Games and activities are unavailable."))
    suspend fun initializeGameChannel(contactId: String, id: String): Result<Unit> = Result.failure(UnsupportedOperationException("Games and activities are unavailable."))
    suspend fun sendGameData(contactId: String, id: String, payload: String): Result<Unit> = Result.failure(UnsupportedOperationException("Games and activities are unavailable."))
    suspend fun sendFile(contactId: String, file: java.io.File, name: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("File transfers are unavailable."))
    suspend fun acceptFile(contactId: String, id: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("File transfers are unavailable."))
    suspend fun cancelFile(contactId: String, id: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("File transfers are unavailable."))
    suspend fun sendVoiceClip(contactId: String, clip: org.aralozkaya.msngr.protocol.VoiceClip): Result<Unit> =
        Result.failure(UnsupportedOperationException("Voice clips are not supported."))
    suspend fun sendWink(contactId: String, wink: org.aralozkaya.msngr.protocol.WinkReference, bytes: ByteArray): Result<Unit> =
        Result.failure(UnsupportedOperationException("Winks are unavailable."))
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
    suspend fun refreshBlockedContacts() = Unit
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
