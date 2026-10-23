package org.aralozkaya.msngr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.MessengerRepository
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
)

/** Shared by sign-in, background restore, and foreground launches; never overwrites an unresolved local snapshot. */
internal class DisplayPictureSync(private val context: Context) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    @Volatile private var dismissedConflict: DisplayPictureConflict? = null
    @Volatile private var foregroundRestore: Pair<MessengerRepository, AccountIdentity>? = null
    private val mutableState = MutableStateFlow(DisplayPictureSyncState())
    val state = mutableState.asStateFlow()

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
            val incoming = IncomingDisplayPicture(bytes, serverProfile.displayPictureFetched &&
                (serverProfile.avatarUri == null || bytes != null))
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
            val incoming = try { repository.fetchOwnDisplayPicture() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { IncomingDisplayPicture() }
            if (currentAccount(repository) != account) return@withLock
            val validated = if (incoming.fetched && incoming.bytes != null) {
                val picture = try { context.inspectDisplayPicture(incoming.bytes.displayPictureDataUri()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (picture != null && !picture.requiresResize) incoming else IncomingDisplayPicture()
            } else incoming
            reconcile(repository, account, validated)
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
                apply(repository, conflict.account, if (incoming) conflict.incoming.bytes else conflict.local.bytes,
                    publish = !incoming)
                if (currentAccount(repository) == conflict.account) mutableState.value = DisplayPictureSyncState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = DisplayPictureSyncState(conflict, error = error.message ?: "The display picture could not be updated. Try again.")
            } finally {
                if (state.value.busy) mutableState.value = state.value.copy(busy = false)
            }
        }
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
        if (state.value.conflict?.account != account) mutableState.value = DisplayPictureSyncState()
        val decision = displayPictureSyncDecision(local.initialized, local.bytes, incoming, DisplayPictureSyncPrefs.behavior(context, account))
        when (decision) {
            DisplayPictureSyncDecision.Unavailable -> if (local.initialized) showLocal(repository, account, local)
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
                mutableState.value = DisplayPictureSyncState()
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
