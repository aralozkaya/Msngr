package org.aralozkaya.msngr

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.MsnCurrentMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class NowPlayingMonitorTest {
    @Test
    fun selectorPrefersPlayingOverPausedFallsBackToTransitioningAndIgnoresOwnPackage() {
        val ownPackage = "org.aralozkaya.msngr"
        val sessions = listOf(
            MediaSessionSnapshot(ownPackage, MediaPlaybackCategory.Playing, "Wink Sound", "Msngr", null),
            MediaSessionSnapshot("com.spotify.music", MediaPlaybackCategory.Inactive, "Paused Track", "Artist A", null),
            MediaSessionSnapshot("com.google.android.apps.youtube.music", MediaPlaybackCategory.Playing, "Active Song", "Artist B", "Album B"),
        )
        val selected = NowPlayingMediaSelector.selectCurrentMedia(sessions, ownPackage)
        assertEquals("Active Song - Artist B", selected?.displayText)
        assertEquals("Album B", selected?.album)

        val bufferingOnly = listOf(
            MediaSessionSnapshot("com.spotify.music", MediaPlaybackCategory.Transitioning, "Next Track", "Artist C", null),
        )
        assertEquals(
            "Next Track - Artist C",
            NowPlayingMediaSelector.selectCurrentMedia(bufferingOnly, ownPackage)?.displayText,
        )

        val untitledAndPaused = listOf(
            MediaSessionSnapshot("com.spotify.music", MediaPlaybackCategory.Playing, "   ", "Artist", null),
            MediaSessionSnapshot("org.videolan.vlc", MediaPlaybackCategory.Inactive, "Stopped", "Artist", null),
        )
        assertNull(NowPlayingMediaSelector.selectCurrentMedia(untitledAndPaused, ownPackage))
    }

    @Test
    fun controllerIsOffByDefaultAndDoesNotClearRemoteMediaOnSignIn() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val client = RecordingMediaClient()
        val repository = MessengerRepository(client)
        val source = FakeNowPlayingSessionSource()
        val enabledByAccount = mutableMapOf<AccountIdentity, Boolean>()
        val controller = NowPlayingController(
            readEnabled = { enabledByAccount[it] ?: false },
            writeEnabled = { account, enabled -> enabledByAccount[account] = enabled },
            checkNotificationAccess = { true },
            sessionSource = source,
            debounceMillis = 40L,
            dispatcher = dispatcher,
        )
        try {
            val request = SignInRequest(
                MockServerData.ACCOUNT_EMAIL,
                MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
            )
            repository.signIn(request).getOrThrow()
            controller.attach(repository)
            delay(80)

            assertFalse(controller.uiState.value.enabled)
            assertFalse(source.isStarted)
            assertTrue(client.mediaPublications.isEmpty())
        } finally {
            controller.close()
            repository.logout()
            executor.shutdownNow()
        }
    }

    @Test
    fun controllerDebouncesTransitionsClearsOnPauseOrDisableAndPreservesStatusMessage() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val client = RecordingMediaClient()
        val repository = MessengerRepository(client)
        val source = FakeNowPlayingSessionSource()
        val enabledByAccount = mutableMapOf<AccountIdentity, Boolean>()
        val controller = NowPlayingController(
            readEnabled = { enabledByAccount[it] ?: false },
            writeEnabled = { account, enabled -> enabledByAccount[account] = enabled },
            checkNotificationAccess = { true },
            sessionSource = source,
            debounceMillis = 80L,
            dispatcher = dispatcher,
        )
        val trackA = MsnCurrentMedia.music("Song A", "Artist A")!!
        val trackB = MsnCurrentMedia.music("Song B", "Artist B")!!
        val trackC = MsnCurrentMedia.music("Song C", "Artist C")!!
        try {
            val request = SignInRequest(
                MockServerData.ACCOUNT_EMAIL,
                MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
            )
            repository.signIn(request).getOrThrow()
            repository.updateStatusMessage("Keep this PSM").getOrThrow()
            controller.attach(repository)

            source.initialMedia = trackA
            controller.setEnabled(true)
            withTimeout(2_000) {
                controller.uiState.first { it.enabled && it.detectedMedia == trackA }
            }
            withTimeout(2_000) {
                while (client.mediaPublications.lastOrNull() != trackA) delay(10)
            }
            assertEquals("Keep this PSM", repository.profile.value.statusMessage)

            // Rapid transitions should coalesce into only the final track.
            source.emitMedia(trackB)
            delay(15)
            source.emitMedia(trackC)
            withTimeout(2_000) {
                while (client.mediaPublications.lastOrNull() != trackC) delay(10)
            }
            assertFalse(trackB in client.mediaPublications)

            // Pausing clears <CurrentMedia> while leaving statusMessage untouched.
            source.emitMedia(null)
            withTimeout(2_000) {
                while (client.mediaPublications.isEmpty() || client.mediaPublications.last() != null) delay(10)
            }
            assertNull(repository.profile.value.currentMedia)
            assertEquals("Keep this PSM", repository.profile.value.statusMessage)

            // Play again, then disable immediately clears without waiting for debounce.
            source.emitMedia(trackA)
            withTimeout(2_000) {
                while (client.mediaPublications.lastOrNull() != trackA) delay(10)
            }
            controller.setEnabled(false)
            withTimeout(2_000) {
                controller.uiState.first { !it.enabled && it.detectedMedia == null }
            }
            withTimeout(2_000) {
                while (client.mediaPublications.last() != null) delay(10)
            }
            assertFalse(source.isStarted)
            assertEquals("Keep this PSM", repository.profile.value.statusMessage)
        } finally {
            controller.close()
            repository.logout()
            executor.shutdownNow()
        }
    }

    @Test
    fun controllerHandlesPermissionRevocationReconnectAccountSwitchAndLogout() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val client = RecordingMediaClient()
        val repository = MessengerRepository(client)
        val source = FakeNowPlayingSessionSource()
        var accessGranted = true
        val server = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
        val accountOne = AccountIdentity.forServer("one@example.com", server, false)
        val enabledByAccount = mutableMapOf(accountOne to true)
        val controller = NowPlayingController(
            readEnabled = { enabledByAccount[it] ?: false },
            writeEnabled = { account, enabled -> enabledByAccount[account] = enabled },
            checkNotificationAccess = { accessGranted },
            sessionSource = source,
            debounceMillis = 30L,
            dispatcher = dispatcher,
        )
        val track = MsnCurrentMedia.music("Reconnect Song", "Artist")!!
        try {
            source.initialMedia = track
            controller.attach(repository)
            repository.signIn(SignInRequest("one@example.com", "pw", server)).getOrThrow()
            withTimeout(2_000) {
                while (client.mediaPublications.lastOrNull() != track) delay(10)
            }

            // Connection lost -> reconnect re-publishes current media.
            val beforeReconnectCount = client.mediaPublications.size
            client.events.emit(MessengerEvent.ConnectionLost("Dropped"))
            withTimeout(2_000) {
                repository.connectionIssue.first { it != null }
            }
            // Let the controller's StateFlow combine collector observe the disconnect before reconnecting.
            repeat(3) { withContext(dispatcher) {} }
            repository.reconnect().getOrThrow()
            withTimeout(2_000) {
                while (client.mediaPublications.size <= beforeReconnectCount) delay(10)
            }
            assertEquals(track, client.mediaPublications.last())

            // Revoking permission stops monitoring and clears published media.
            accessGranted = false
            source.emitAccessLost()
            withTimeout(2_000) {
                controller.uiState.first { !it.hasNotificationAccess && it.detectedMedia == null }
            }
            withTimeout(2_000) {
                while (client.mediaPublications.last() != null) delay(10)
            }
            assertFalse(source.isStarted)

            // Restore permission, verify logout stops monitoring, and account two stays off by default.
            accessGranted = true
            controller.refreshState()
            withTimeout(2_000) {
                while (client.mediaPublications.lastOrNull() != track) delay(10)
            }
            assertTrue(source.isStarted)

            repository.logout()
            withTimeout(2_000) {
                controller.uiState.first { !it.enabled && it.detectedMedia == null }
            }
            assertFalse(source.isStarted)

            val publicationsBeforeAccountTwo = client.mediaPublications.size
            repository.signIn(SignInRequest("two@example.com", "pw", server)).getOrThrow()
            delay(80)
            assertEquals(publicationsBeforeAccountTwo, client.mediaPublications.size)
            assertFalse(controller.uiState.value.enabled)
            assertFalse(source.isStarted)
        } finally {
            controller.close()
            repository.logout()
            executor.shutdownNow()
        }
    }

    private class FakeNowPlayingSessionSource : NowPlayingSessionSource {
        var initialMedia: CurrentMedia? = null
        var isStarted = false
            private set
        private var onMediaChanged: ((CurrentMedia?) -> Unit)? = null
        private var onAccessLost: (() -> Unit)? = null

        override fun start(
            onMediaChanged: (CurrentMedia?) -> Unit,
            onAccessLost: () -> Unit,
        ): Boolean {
            isStarted = true
            this.onMediaChanged = onMediaChanged
            this.onAccessLost = onAccessLost
            onMediaChanged(initialMedia)
            return true
        }

        override fun stop() {
            isStarted = false
            onMediaChanged = null
            onAccessLost = null
        }

        fun emitMedia(media: CurrentMedia?) {
            onMediaChanged?.invoke(media)
        }

        fun emitAccessLost() {
            onAccessLost?.invoke()
        }
    }

    private class RecordingMediaClient : MsnpClient {
        override val contacts = MutableStateFlow<List<Contact>>(emptyList())
        override val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
        override val typing = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override val events = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 32)
        val mediaPublications = CopyOnWriteArrayList<CurrentMedia?>()
        var failNextReconnect = false

        override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> {
            if (failNextReconnect) return Result.failure(IOException("offline"))
            return Result.success(
                UserProfileSnapshot(
                    username = request.email.substringBefore('@'),
                    email = request.email,
                    presence = request.initialPresence,
                ),
            )
        }

        override suspend fun changePresence(presence: Presence) = Unit
        override suspend fun changeNickname(nickname: String) = Unit
        override suspend fun changePersonalMessage(statusMessage: String) = Unit
        override suspend fun changeCurrentMedia(media: CurrentMedia?) {
            mediaPublications += media
        }
        override suspend fun changeDisplayPicture(image: ByteArray?) = Unit
        override suspend fun sendMessage(
            contactId: String,
            text: String,
            renderedEmoticonRanges: List<MsnEmoticonRange>?,
        ) = Unit
        override suspend fun sendTyping(contactId: String) = Unit
        override suspend fun sendNudge(contactId: String) = Unit
        override suspend fun blockContact(contactId: String) = Unit
        override suspend fun unblockContact(contactId: String) = Unit
        override fun markRead(contactId: String) = Unit
        override fun markUnread(contactId: String) = Unit
        override fun clearMessages() = Unit
        override fun clearMessages(contactId: String) = Unit
        override fun close() = Unit
    }
}
