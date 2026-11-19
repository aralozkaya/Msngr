package org.aralozkaya.msngr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.MsnCurrentMedia

internal data class NowPlayingUiState(
    val enabled: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val detectedMedia: CurrentMedia? = null,
)

/**
 * Coordinates the opt-in "What I'm listening to?" feature with [NowPlayingSessionSource] and
 * publishes MSNP `<CurrentMedia>` changes through [MessengerRepository].
 */
internal class NowPlayingController internal constructor(
    private val readEnabled: (AccountIdentity) -> Boolean,
    private val writeEnabled: (AccountIdentity, Boolean) -> Unit,
    private val checkNotificationAccess: () -> Boolean,
    private val sessionSource: NowPlayingSessionSource,
    private val debounceMillis: Long = 750L,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    constructor(context: Context) : this(
        readEnabled = { account -> NowPlayingPrefs.isEnabled(context.applicationContext, account) },
        writeEnabled = { account, enabled -> NowPlayingPrefs.setEnabled(context.applicationContext, enabled, account) },
        checkNotificationAccess = { NowPlayingAccess.isNotificationAccessGranted(context.applicationContext) },
        sessionSource = AndroidNowPlayingSessionSource(context.applicationContext),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = mutableUiState.asStateFlow()

    private var observedRepository: MessengerRepository? = null
    private var observationJob: Job? = null
    private var publishJob: Job? = null
    private var activeAccount: AccountIdentity? = null
    private var monitoringActive = false
    private var isStartingMonitoring = false
    private var detectedMedia: CurrentMedia? = null
    private var hasPublishedOwnMedia = false
    private var lastPublishedKey: String? = null
    private var desiredMediaKey: String? = null

    fun hasNotificationAccess(): Boolean = checkNotificationAccess()

    fun attach(repository: MessengerRepository) {
        scope.launch {
            if (observedRepository === repository) {
                reconcile(immediate = true)
                return@launch
            }
            observationJob?.cancel()
            publishJob?.cancel()
            publishJob = null
            stopMonitoringLocked()
            observedRepository = repository
            activeAccount = null
            hasPublishedOwnMedia = false
            lastPublishedKey = null
            desiredMediaKey = null
            observationJob = scope.launch {
                combine(
                    repository.profile.map { it.email },
                    repository.connectionIssue,
                ) { email, issue ->
                    email to issue
                }.distinctUntilChanged().collect {
                    reconcile(immediate = true)
                }
            }
            reconcile(immediate = true)
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            val account = observedRepository?.signedInAccountIdentity ?: return@launch
            writeEnabled(account, enabled)
            reconcile(immediate = true, explicitDisable = !enabled)
        }
    }

    fun refreshState() {
        scope.launch {
            reconcile(immediate = true)
        }
    }

    fun onNotificationAccessChanged() {
        scope.launch {
            reconcile(immediate = true)
        }
    }

    fun close() {
        publishJob?.cancel()
        publishJob = null
        observationJob?.cancel()
        observationJob = null
        monitoringActive = false
        detectedMedia = null
        desiredMediaKey = null
        lastPublishedKey = null
        hasPublishedOwnMedia = false
        sessionSource.stop()
        scope.cancel()
    }

    private fun stopMonitoringLocked() {
        if (monitoringActive) {
            sessionSource.stop()
            monitoringActive = false
        }
        detectedMedia = null
    }

    private fun reconcile(
        immediate: Boolean,
        explicitDisable: Boolean = false,
    ) {
        val repo = observedRepository
        val account = repo?.signedInAccountIdentity
        val connected = repo != null &&
            account != null &&
            repo.hasSignedInSession &&
            repo.connectionIssue.value == null

        if (account != activeAccount) {
            publishJob?.cancel()
            publishJob = null
            stopMonitoringLocked()
            activeAccount = account
            hasPublishedOwnMedia = false
            lastPublishedKey = null
            desiredMediaKey = null
        }

        if (!connected) {
            publishJob?.cancel()
            publishJob = null
            lastPublishedKey = null
            desiredMediaKey = null
        }

        val enabled = account?.let(readEnabled) ?: false
        var hasAccess = checkNotificationAccess()
        var shouldMonitor = account != null && enabled && hasAccess

        if (shouldMonitor && !monitoringActive) {
            monitoringActive = true
            isStartingMonitoring = true
            val started = try {
                sessionSource.start(
                    onMediaChanged = { media ->
                        if (isStartingMonitoring) {
                            detectedMedia = media
                        } else {
                            scope.launch {
                                if (!monitoringActive) return@launch
                                detectedMedia = media
                                reconcile(immediate = false)
                            }
                        }
                    },
                    onAccessLost = {
                        if (isStartingMonitoring) {
                            monitoringActive = false
                            detectedMedia = null
                        } else {
                            scope.launch {
                                stopMonitoringLocked()
                                reconcile(immediate = true)
                            }
                        }
                    },
                )
            } finally {
                isStartingMonitoring = false
            }
            if (!started) {
                monitoringActive = false
                detectedMedia = null
                hasAccess = false
                shouldMonitor = false
            }
        } else if (!shouldMonitor && monitoringActive) {
            stopMonitoringLocked()
        }

        mutableUiState.value = NowPlayingUiState(
            enabled = enabled,
            hasNotificationAccess = hasAccess,
            detectedMedia = if (shouldMonitor) detectedMedia else null,
        )

        if (repo == null || account == null || !repo.hasSignedInSession || repo.connectionIssue.value != null) {
            return
        }

        if (enabled && hasAccess) {
            val activeMedia = detectedMedia
            if (activeMedia != null) {
                schedulePublish(
                    repo = repo,
                    account = account,
                    media = activeMedia,
                    immediate = immediate,
                )
            } else if (hasPublishedOwnMedia) {
                schedulePublish(
                    repo = repo,
                    account = account,
                    media = null,
                    immediate = immediate,
                )
            } else {
                publishJob?.cancel()
                publishJob = null
                desiredMediaKey = null
            }
        } else if (hasPublishedOwnMedia || explicitDisable) {
            schedulePublish(
                repo = repo,
                account = account,
                media = null,
                immediate = true,
            )
        } else {
            publishJob?.cancel()
            publishJob = null
            desiredMediaKey = null
        }
    }

    private fun schedulePublish(
        repo: MessengerRepository,
        account: AccountIdentity,
        media: CurrentMedia?,
        immediate: Boolean,
    ) {
        val key = publishKeyFor(media)
        if (key == lastPublishedKey) {
            publishJob?.cancel()
            publishJob = null
            desiredMediaKey = key
            return
        }
        if (publishJob?.isActive == true && desiredMediaKey == key && !immediate) {
            return
        }
        publishJob?.cancel()
        desiredMediaKey = key
        publishJob = scope.launch {
            if (!immediate && debounceMillis > 0L) {
                delay(debounceMillis)
            }
            if (observedRepository !== repo ||
                repo.signedInAccountIdentity != account ||
                !repo.hasSignedInSession ||
                repo.connectionIssue.value != null ||
                desiredMediaKey != key
            ) {
                return@launch
            }
            try {
                repo.updateCurrentMedia(media).onSuccess {
                    if (observedRepository === repo && repo.signedInAccountIdentity == account) {
                        lastPublishedKey = key
                        hasPublishedOwnMedia = media != null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun publishKeyFor(media: CurrentMedia?): String =
        if (media == null) CLEAR_KEY else MsnCurrentMedia.encode(media)

    private companion object {
        const val CLEAR_KEY = "<none>"
    }
}

internal val Context.nowPlayingController: NowPlayingController
    get() = (applicationContext as MsngrApplication).nowPlayingController
