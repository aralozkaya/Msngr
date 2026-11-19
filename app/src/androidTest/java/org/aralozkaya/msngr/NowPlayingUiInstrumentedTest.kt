package org.aralozkaya.msngr

import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.ProfilePage
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.LoggedInUserProfileCard
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.MsnCurrentMedia
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingUiInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun profilePageToggleRequiresNotificationAccessShowsRestrictedHintAndControlsLivePreview() {
        val repository = compose.activity.appContainer.repositoryFor(useMockServer = true)
        runBlocking {
            repository.signIn(
                SignInRequest(
                    MockServerData.ACCOUNT_EMAIL,
                    MockServerData.ACCOUNT_PASSWORD,
                    ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
                ),
            ).getOrThrow()
        }
        val fakeSource = FakeInstrumentedSessionSource()
        val enabledByAccount = mutableMapOf<AccountIdentity, Boolean>()
        var accessGranted = false
        var openedNotificationSettings = 0
        var openedAppInfo = 0
        val controller = NowPlayingController(
            readEnabled = { enabledByAccount[it] ?: false },
            writeEnabled = { account, enabled -> enabledByAccount[account] = enabled },
            checkNotificationAccess = { accessGranted },
            sessionSource = fakeSource,
            debounceMillis = 20L,
        )
        controller.attach(repository)
        try {
            compose.setContent {
                MsngrTheme {
                    ProfilePage(
                        innerPadding = PaddingValues(),
                        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                        onProfileSaved = {},
                        nowPlayingController = controller,
                        onOpenNotificationAccessSettings = { openedNotificationSettings++ },
                        onOpenAppInfoSettings = { openedAppInfo++ },
                    )
                }
            }

            compose.onNode(hasScrollAction())
                .performScrollToNode(hasTestTag("profile-now-playing-toggle"))
            compose.onNodeWithTag("profile-now-playing-toggle").assertIsOff()

            // Without notification access, toggling prompts for permission instead of enabling.
            compose.onNodeWithTag("profile-now-playing-toggle").performClick()
            compose.onNodeWithText(
                compose.activity.getString(R.string.profile_now_playing_permission_title),
            ).assertIsDisplayed()
            compose.onNodeWithTag("profile-now-playing-open-settings").assertIsDisplayed()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                compose.onNodeWithTag("profile-now-playing-open-app-info")
                    .assertIsDisplayed()
                    .performClick()
                compose.runOnIdle { assertEquals(1, openedAppInfo) }
            }
            compose.onNodeWithTag("profile-now-playing-open-settings").performClick()
            compose.runOnIdle { assertEquals(1, openedNotificationSettings) }
            compose.onNodeWithTag("profile-now-playing-toggle").assertIsOff()

            // Grant permission and toggle ON -> shows idle state, then live preview when track starts.
            accessGranted = true
            compose.onNodeWithTag("profile-now-playing-toggle").performClick()
            compose.onNodeWithTag("profile-now-playing-toggle").assertIsOn()
            compose.onNode(hasScrollAction())
                .performScrollToNode(hasTestTag("profile-now-playing-empty"))
            compose.onNodeWithTag("profile-now-playing-empty").assertIsDisplayed()

            val track = MsnCurrentMedia.music("Bohemian Rhapsody", "Queen", "A Night at the Opera")!!
            compose.runOnIdle { fakeSource.emit(track) }
            compose.onNode(hasScrollAction())
                .performScrollToNode(hasTestTag("profile-now-playing-preview"))
            compose.onNodeWithText("Bohemian Rhapsody - Queen").assertIsDisplayed()

            // Revoked permission while enabled shows warning + grant button.
            accessGranted = false
            compose.runOnIdle { controller.refreshState() }
            compose.onNode(hasScrollAction())
                .performScrollToNode(hasTestTag("profile-now-playing-grant-access"))
            compose.onNodeWithTag("profile-now-playing-grant-access").assertIsDisplayed()

            compose.onNodeWithText(MockServerData.ACCOUNT_EMAIL)
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Share the song playing on your device with Messenger contacts")
                .assertDoesNotExist()
            compose.onNodeWithText("Managed by your selected community service")
                .assertDoesNotExist()

            // Toggling OFF turns the feature back off cleanly.
            compose.onNodeWithTag("profile-now-playing-toggle").performClick()
            compose.onNodeWithTag("profile-now-playing-toggle").assertIsOff()
            compose.runOnIdle {
                assertFalse(controller.uiState.value.enabled)
                assertTrue(fakeSource.stopped)
            }
        } finally {
            controller.close()
            repository.logout()
        }
    }

    @Test
    fun loggedInUserProfileCardShowsCurrentMediaWhenActiveAndStatusOtherwise() {
        val track = MsnCurrentMedia.music("Starlight", "Muse")!!
        val profileState = mutableStateOf(
            UserProfileSnapshot(
                username = "Aral",
                email = "aral@example.com",
                presence = Presence.Online,
                statusMessage = "At the desk",
                currentMedia = track,
            ),
        )
        compose.setContent {
            MsngrTheme {
                LoggedInUserProfileCard(
                    profile = profileState.value,
                    onPresenceSelected = {},
                    onEditProfile = {},
                )
            }
        }
        compose.onNodeWithTag("own-profile-current-media").assertIsDisplayed()
        compose.onNodeWithText("Starlight - Muse").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.current_media_listening_description, "Starlight - Muse"),
        ).assertExists()
        compose.onNodeWithText("At the desk").assertDoesNotExist()

        compose.runOnIdle {
            profileState.value = profileState.value.copy(currentMedia = null)
        }
        compose.onNodeWithTag("own-profile-current-media").assertDoesNotExist()
        compose.onNodeWithText("At the desk").assertIsDisplayed()
    }

    @Test
    fun contactRowShowsCurrentMediaOnlyWhenOnlineAndWithoutDraft() {
        val track = MsnCurrentMedia.music("Time", "Pink Floyd")!!
        val baseContact = Contact(
            id = "friend@example.com",
            nickname = "Friend",
            email = "friend@example.com",
            presence = Presence.Online,
            statusMessage = "Busy coding",
            currentMedia = track,
        )
        val contactState = mutableStateOf(baseContact)
        val hasDraftState = mutableStateOf(false)
        compose.setContent {
            MsngrTheme {
                ContactRow(
                    index = 0,
                    count = 1,
                    contact = contactState.value,
                    hasDraft = hasDraftState.value,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithTag("contact-current-media-friend@example.com").assertIsDisplayed()
        compose.onNodeWithText("Time - Pink Floyd").assertIsDisplayed()
        compose.onNodeWithText("Busy coding").assertDoesNotExist()

        // Offline falls back to status message.
        compose.runOnIdle {
            contactState.value = baseContact.copy(presence = Presence.Offline)
        }
        compose.onNodeWithTag("contact-current-media-friend@example.com").assertDoesNotExist()
        compose.onNodeWithText("Busy coding").assertIsDisplayed()

        // Draft indicator keeps priority over current media while online.
        compose.runOnIdle {
            contactState.value = baseContact
            hasDraftState.value = true
        }
        compose.onNodeWithTag("contact-current-media-friend@example.com").assertDoesNotExist()
        compose.onNodeWithText(
            compose.activity.getString(R.string.contact_draft_label),
        ).assertIsDisplayed()
    }

    @Test
    fun contactProfilePageShowsStatusAndListeningToWithAlbumSupportingText() {
        val track = MsnCurrentMedia.music(
            title = "Everything In Its Right Place",
            artist = "Radiohead",
            album = "Kid A",
        )!!
        val contact = Contact(
            id = "friend@example.com",
            nickname = "Friend",
            email = "friend@example.com",
            presence = Presence.Online,
            statusMessage = "Working late",
            currentMedia = track,
        )
        compose.setContent {
            MsngrTheme {
                ContactProfilePage(
                    contact = contact,
                    innerPadding = PaddingValues(),
                    scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    onBlockContact = {},
                    onEraseHistory = {},
                )
            }
        }
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("contact-profile-current-media"))
        compose.onNodeWithText("Working late").assertIsDisplayed()
        compose.onNodeWithText(
            compose.activity.getString(R.string.current_media_listening_label),
        ).assertIsDisplayed()
        compose.onNodeWithText("Everything In Its Right Place - Radiohead").assertIsDisplayed()
        compose.onNodeWithText("Kid A").assertIsDisplayed()
    }

    @Test
    fun listenerFiltersOutAllNotificationTypesWhileMediaSessionAccessStillWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = NowPlayingAccess.listenerComponent(context)
        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        }
        val metaData = serviceInfo.metaData
        assertNotNull(metaData)
        assertTrue(metaData!!.containsKey(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES))
        assertEquals("", metaData.getString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES))
        assertEquals(
            "conversations|alerting|silent|ongoing",
            metaData.getString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES),
        )

        val wasGranted = NowPlayingAccess.isNotificationAccessGranted(context)
        var session: MediaSession? = null
        try {
            if (!wasGranted) {
                runNotificationShellCommand(
                    "cmd notification allow_listener ${component.flattenToString()}",
                )
            }
            val grantDeadline = SystemClock.elapsedRealtime() + 3_000L
            while (!NowPlayingAccess.isNotificationAccessGranted(context) &&
                SystemClock.elapsedRealtime() < grantDeadline
            ) {
                SystemClock.sleep(100)
            }
            assertTrue(NowPlayingAccess.isNotificationAccessGranted(context))

            compose.runOnUiThread {
                session = MediaSession(context, "now-playing-filter-test").apply {
                    setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, "Paranoid Android")
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Radiohead")
                            .build(),
                    )
                    setPlaybackState(
                        PlaybackState.Builder()
                            .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                            .build(),
                    )
                    isActive = true
                }
            }

            val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)!!
            var matchingController: MediaController? = null
            val sessionDeadline = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < sessionDeadline) {
                val candidate = mediaSessionManager.getActiveSessions(component).firstOrNull { controller ->
                    controller.packageName == context.packageName &&
                        controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "Paranoid Android" &&
                        controller.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                if (candidate != null) {
                    matchingController = candidate
                    break
                }
                SystemClock.sleep(100)
            }
            assertNotNull(matchingController)
            assertEquals("Radiohead", matchingController?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST))
        } finally {
            compose.runOnUiThread {
                session?.run {
                    isActive = false
                    release()
                }
            }
            if (!wasGranted) {
                runNotificationShellCommand(
                    "cmd notification disallow_listener ${component.flattenToString()}",
                )
            }
        }
    }

    private fun runNotificationShellCommand(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private class FakeInstrumentedSessionSource : NowPlayingSessionSource {
        var stopped = false
            private set
        private var onMediaChanged: ((CurrentMedia?) -> Unit)? = null

        override fun start(
            onMediaChanged: (CurrentMedia?) -> Unit,
            onAccessLost: () -> Unit,
        ): Boolean {
            stopped = false
            this.onMediaChanged = onMediaChanged
            return true
        }

        override fun stop() {
            stopped = true
            onMediaChanged = null
        }

        fun emit(media: CurrentMedia?) {
            onMediaChanged?.invoke(media)
        }
    }
}
