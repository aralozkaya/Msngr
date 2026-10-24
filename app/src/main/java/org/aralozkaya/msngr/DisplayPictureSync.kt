package org.aralozkaya.msngr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.protocol.DisplayPictureFormat
import org.aralozkaya.msngr.protocol.MsnBase64
import java.util.UUID

internal fun ByteArray.displayPictureDataUri(): String =
    "data:${requireNotNull(DisplayPictureFormat.detect(this)).mimeType};base64,${MsnBase64.encode(this)}"

internal data class DisplayPictureConflict(
    val account: AccountIdentity,
    val local: SavedDisplayPicture,
    val incoming: IncomingDisplayPicture,
    val id: String = UUID.randomUUID().toString(),
) {
    val incomingUri: String? get() = incoming.bytes?.displayPictureDataUri()
}

internal data class DisplayPictureSyncState(
    val conflict: DisplayPictureConflict? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/** Shared by sign-in, background restore, and foreground launches; never overwrites an unresolved local snapshot. */
internal class DisplayPictureSync(private val context: Context, private val pollIntervalMillis: Long = 30_000) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var observationJob: Job? = null
    private var pollingJob: Job? = null
    private var observedRepository: MessengerRepository? = null
    private var foreground = false
    private var stateAccount: AccountIdentity? = null
    @Volatile private var dismissedConflict: DisplayPictureConflict? = null
    @Volatile private var foregroundRestore: Pair<MessengerRepository, AccountIdentity>? = null
    private val mutableState = MutableStateFlow(DisplayPictureSyncState())
    val state = mutableState.asStateFlow()

    internal fun close() { scope.cancel() }

    /** Serialize explicit edits with downloads and conflict choices, including the durable save. */
    suspend fun saveProfile(repository: MessengerRepository, username: String, avatarUri: String?,
        pictureBytes: ByteArray?, pictureChanged: Boolean, statusMessage: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val account = checkNotNull(currentAccount(repository)) { "The signed-in account changed. Please try again." }
                    repository.updateProfile(username, avatarUri, pictureBytes, updateDisplayPicture = pictureChanged).getOrThrow()
                    check(currentAccount(repository) == account) { "The signed-in account changed. Please try again." }
                    val saved = if (pictureChanged) OwnDisplayPictureStore.write(context, account, pictureBytes) else null
                    SessionPrefs.saveUserProfile(context, username, repository.profile.value.presence,
                        avatarUri = saved?.uri ?: avatarUri, statusMessage = statusMessage, saveDisplayPicture = false)
                    if (saved != null) {
                        showLocal(repository, account, saved)
                        dismissedConflict = null
                        stateAccount = account
                        mutableState.value = DisplayPictureSyncState(notice = repository.displayPictureStorageIssue.value)
                    }
                    repository.setStatusMessage(statusMessage)
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

    fun attach(repository: MessengerRepository) {
        if (observedRepository === repository) return
        observationJob?.cancel()
        pollingJob?.cancel()
        refreshJob?.cancel()
        observedRepository = repository
        stateAccount = null
        foregroundRestore = null
        dismissedConflict = null
        mutableState.value = DisplayPictureSyncState()
        observationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.events.collect { event ->
                if (event is MessengerEvent.OwnDisplayPictureChanged &&
                    event.account == repository.signedInAccountIdentity) {
                    if (currentAccount(repository) == event.account && event.personalMessage != null) {
                        val profile = repository.profile.value
                        SessionPrefs.saveUserProfile(context, profile.username, profile.presence,
                            avatarUri = profile.avatarUri, statusMessage = event.personalMessage, saveDisplayPicture = false)
                    }
                    // The notification is an invalidation, never proof of removal.
                    // Refresh even in the background, retaining choices for the next foreground.
                    refresh(repository)
                }
            }
        }
        startPolling()
    }

    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
        startPolling()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        val repository = observedRepository ?: return
        if (!foreground) return
        pollingJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                // Some community servers omit profile-change notifications altogether.
                // Do not reset a dismissed choice on each poll.
                refresh(repository)
            }
        }
    }

    fun requestRefresh(repository: MessengerRepository) {
        val restored = foregroundRestore
        if (restored?.first === repository && restored.second == repository.signedInAccountIdentity) {
            foregroundRestore = null
            return
        }
        if (refreshJob?.isActive == true) return
        dismissedConflict = null
        refreshJob = scope.launch { refresh(repository) }
    }

    fun choose(repository: MessengerRepository, id: String, incoming: Boolean) {
        scope.launch { resolve(repository, id, incoming) }
    }

    fun consumeNotice(notice: String) {
        if (state.value.notice == notice) mutableState.value = state.value.copy(notice = null)
    }

    fun dismiss(id: String) {
        if (!state.value.busy && state.value.conflict?.id == id) {
            dismissedConflict = state.value.conflict
            mutableState.value = DisplayPictureSyncState()
        }
        // Keep the local baseline. A later launch will fetch and compare again.
    }

    suspend fun restore(repository: MessengerRepository, serverProfile: UserProfileSnapshot, skipNextRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account = currentAccount(repository) ?: return@withLock
            val bytes = serverProfile.avatarUri?.let { context.loadDisplayPictureBytes(it) }
            val incoming = validate(IncomingDisplayPicture(bytes, serverProfile.displayPictureFetched &&
                (serverProfile.avatarUri == null || bytes != null)))
            reconcile(repository, account, incoming)
            // Sign-in already fetched this snapshot. In particular, first login must not
            // immediately run a second comparison after its server picture was adopted.
            if (skipNextRefresh && incoming.fetched) foregroundRestore = repository to account
        }
    }

    suspend fun refresh(repository: MessengerRepository) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account = currentAccount(repository) ?: run {
                mutableState.value = DisplayPictureSyncState()
                return@withLock
            }
            val baseline = OwnDisplayPictureStore.read(context, account)
            val incoming = try { repository.fetchOwnDisplayPicture() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { IncomingDisplayPicture() }
            if (currentAccount(repository) != account) return@withLock
            val latest = OwnDisplayPictureStore.read(context, account)
            if (latest.initialized != baseline.initialized || !samePicture(latest.bytes, baseline.bytes)) {
                // A profile edit completed while this download was in flight.
                showLocal(repository, account, latest)
                mutableState.value = DisplayPictureSyncState()
                return@withLock
            }
            reconcile(repository, account, validate(incoming))
        }
    }

    suspend fun resolve(repository: MessengerRepository, id: String, incoming: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conflict = state.value.conflict?.takeIf { it.id == id } ?: return@withLock
            if (currentAccount(repository) != conflict.account) {
                mutableState.value = DisplayPictureSyncState()
                return@withLock
            }
            val latest = OwnDisplayPictureStore.read(context, conflict.account)
            if (!samePicture(latest.bytes, conflict.local.bytes) || latest.initialized != conflict.local.initialized) {
                reconcile(repository, conflict.account, conflict.incoming)
                return@withLock
            }
            mutableState.value = DisplayPictureSyncState(conflict, busy = true)
            try {
                val current = validate(repository.fetchOwnDisplayPicture())
                check(current.fetched) { "The latest picture could not be checked. Try again." }
                if (currentAccount(repository) != conflict.account) return@withLock
                if (!samePicture(current.bytes, conflict.incoming.bytes)) {
                    // Another change arrived while the choice was visible. Show that version
                    // before adopting or overwriting anything on the user's behalf.
                    reconcile(repository, conflict.account, current)
                    return@withLock
                }
                apply(repository, conflict.account, if (incoming) conflict.incoming.bytes else conflict.local.bytes,
                    publish = !incoming)
                if (currentAccount(repository) == conflict.account) mutableState.value = DisplayPictureSyncState(
                    notice = if (incoming) null else repository.displayPictureStorageIssue.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = DisplayPictureSyncState(conflict, error = error.message ?: "The display picture could not be updated. Try again.")
            } finally {
                if (state.value.busy) mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    private suspend fun validate(incoming: IncomingDisplayPicture): IncomingDisplayPicture {
        if (!incoming.fetched || incoming.bytes == null) return incoming
        val picture = try { context.inspectDisplayPicture(incoming.bytes.displayPictureDataUri()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        return if (picture != null && !picture.requiresResize) incoming else IncomingDisplayPicture()
    }

    private suspend fun reconcile(repository: MessengerRepository, account: AccountIdentity, incoming: IncomingDisplayPicture) {
        if (currentAccount(repository) != account) return
        var local = OwnDisplayPictureStore.read(context, account)
        if (!local.initialized) {
            // Migrate older content/file references only when they can actually be read.
            val legacy = AccountPreferences.forAccount(context, "msngr_profile", account).getString("avatar_uri", null)
            val bytes = legacy?.let { context.loadDisplayPictureBytes(it) }
            if (bytes != null) local = OwnDisplayPictureStore.write(context, account, bytes)
        }
        if (currentAccount(repository) != account) return
        if (stateAccount != account) {
            mutableState.value = DisplayPictureSyncState()
            stateAccount = account
        }
        val decision = displayPictureSyncDecision(local.initialized, local.bytes, incoming, DisplayPictureSyncPrefs.behavior(context, account))
        when (decision) {
            DisplayPictureSyncDecision.Unavailable -> if (local.initialized) {
                // A saved original is still available to P2P peers during a roaming outage.
                // Adopting it only announces this endpoint; it does not overwrite storage.
                try { repository.useDisplayPicture(local.uri, local.bytes, publish = false, account = account) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the local preview while the connection recovers. */ }
                showLocal(repository, account, local)
            }
            DisplayPictureSyncDecision.Ask -> {
                showLocal(repository, account, local)
                val dismissed = dismissedConflict
                if (dismissed?.account == account && samePicture(dismissed.local.bytes, local.bytes) &&
                    samePicture(dismissed.incoming.bytes, incoming.bytes)) return
                val existing = state.value.conflict
                if (existing == null || !samePicture(existing.incoming.bytes, incoming.bytes) || !samePicture(existing.local.bytes, local.bytes)) {
                    mutableState.value = DisplayPictureSyncState(DisplayPictureConflict(account, local, incoming))
                }
            }
            else -> try {
                apply(repository, account,
                    if (decision == DisplayPictureSyncDecision.Local) local.bytes else incoming.bytes,
                    publish = decision == DisplayPictureSyncDecision.Local)
                mutableState.value = DisplayPictureSyncState(
                    notice = if (decision == DisplayPictureSyncDecision.Local) repository.displayPictureStorageIssue.value else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (local.initialized) {
                    showLocal(repository, account, local)
                    mutableState.value = DisplayPictureSyncState(DisplayPictureConflict(account, local, incoming),
                        error = error.message ?: "The display picture could not be updated. Try again.")
                }
            }
        }
    }

    private suspend fun apply(repository: MessengerRepository, account: AccountIdentity, bytes: ByteArray?, publish: Boolean) {
        if (currentAccount(repository) != account) return
        repository.useDisplayPicture(bytes?.displayPictureDataUri(), bytes, publish, account)
        // Upload/announcement must succeed before replacing the durable baseline.
        if (currentAccount(repository) != account) return
        val saved = OwnDisplayPictureStore.write(context, account, bytes)
        showLocal(repository, account, saved)
    }

    private fun showLocal(repository: MessengerRepository, account: AccountIdentity, saved: SavedDisplayPicture) {
        repository.showStoredDisplayPicture(saved.uri, account)
    }

    private fun currentAccount(repository: MessengerRepository): AccountIdentity? {
        if (!SessionPrefs.canOpenAuthenticatedScreens(context)) return null
        val account = SessionPrefs.preferenceAccount(context) ?: return null
        return account.takeIf { it == repository.signedInAccountIdentity }
    }

    private fun samePicture(left: ByteArray?, right: ByteArray?) =
        left == null && right == null || left != null && right?.contentEquals(left) == true
}

internal val Context.displayPictureSync: DisplayPictureSync
    get() = (applicationContext as MsngrApplication).displayPictureSync
