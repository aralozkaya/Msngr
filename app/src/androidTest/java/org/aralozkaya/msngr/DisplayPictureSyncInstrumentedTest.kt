@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.ConnectionSettingsScreen
import org.aralozkaya.msngr.pages.DisplayPictureConflictDialog
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class DisplayPictureSyncInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var repository: MessengerRepository
    private lateinit var client: PictureClient
    private lateinit var sync: DisplayPictureSync
    private val email = "display-picture-sync@example.test"
    private val account get() = AccountIdentity.forLogin(email, useMockServer = true)
    private val old get() = context.assets.open("display_pictures/beach_chairs.png").use { it.readBytes() }
    private val new get() = context.assets.open("display_pictures/animatedusertile3.gif").use { it.readBytes() }

    @Before fun prepare() {
        SessionPrefs.logout()
        val target = compose.activity
        val prefix = "picture_sync_test_${UUID.randomUUID()}_"
        directory = File(target.noBackupFilesDir, prefix).apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "msngr_session_secure_keyset") target.applicationContext.getSharedPreferences(name, mode)
                else super.getSharedPreferences(prefix + name, mode)
        }
        login()
        client = PictureClient()
        repository = MessengerRepository(client, useMockServer = true)
        sync = DisplayPictureSync(context)
    }

    @After fun cleanup() {
        repository.logout()
        SessionPrefs.rememberedProfiles(context).forEach { SessionPrefs.disableRememberedCredentials(context, account = it.accountIdentity) }
        SessionPrefs.logout()
        directory.deleteRecursively()
        AccountPreferences.reload(compose.activity)
    }

    @Test fun firstLoginUsesServerDirectlyUnderEveryPolicyIncludingNoPicture() = runBlocking {
        val profile = connect(IncomingDisplayPicture(new, true))
        for (behavior in DisplayPictureSyncBehavior.entries) for (bytes in listOf(new, null)) {
            OwnDisplayPictureStore.clear(context, account)
            DisplayPictureSyncPrefs.setBehavior(context, behavior)
            val fresh = DisplayPictureSync(context)
            fresh.restore(repository, profile.copy(avatarUri = bytes?.displayPictureDataUri()))
            assertNull(fresh.state.value.conflict)
            assertTrue(OwnDisplayPictureStore.read(context, account).initialized)
            assertArrayEquals(bytes, OwnDisplayPictureStore.read(context, account).bytes)
            assertTrue(client.published.isEmpty())
        }
    }

    @Test fun changedServerPictureSurvivesControllerRestartUntilUserChoosesIncoming() = runBlocking {
        val profile = connect(IncomingDisplayPicture(old, true))
        sync.restore(repository, profile)
        client.incoming = IncomingDisplayPicture(new, true)
        val relaunched = DisplayPictureSync(context)
        relaunched.refresh(repository)
        assertEquals(1, client.fetches)
        val conflict = requireNotNull(relaunched.state.value.conflict)
        assertArrayEquals(old, conflict.local.bytes)
        assertArrayEquals(new, conflict.incoming.bytes)
        assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
        assertArrayEquals(old, context.loadDisplayPictureBytes(repository.profile.value.avatarUri))
        assertTrue(client.published.isEmpty())
        relaunched.resolve(repository, conflict.id, incoming = true)
        assertNull(relaunched.state.value.conflict)
        assertArrayEquals(new, OwnDisplayPictureStore.read(context, account).bytes)
        assertArrayEquals(new, context.loadDisplayPictureBytes(SessionPrefs.readUserProfile(context).avatarUri))
        assertTrue(client.published.isEmpty())
        relaunched.refresh(repository)
        assertNull(relaunched.state.value.conflict)
    }

    @Test fun firstForegroundSignInDoesNotCompareAgainBeforeTheNextLaunch() = runBlocking {
        val profile = connect(IncomingDisplayPicture(old, true))
        sync.restore(repository, profile, skipNextRefresh = true)
        client.incoming = IncomingDisplayPicture(new, true)
        sync.requestRefresh(repository)
        assertEquals(0, client.fetches)
        assertNull(sync.state.value.conflict)
        sync.refresh(repository)
        assertEquals(1, client.fetches)
        assertNotNull(sync.state.value.conflict)
    }

    @Test fun keepingLocalPublishesItsOriginalBytesAndFailedUploadCanBeRetried() = runBlocking {
        connect(IncomingDisplayPicture(new, true))
        OwnDisplayPictureStore.write(context, account, old)
        sync.refresh(repository)
        val conflict = requireNotNull(sync.state.value.conflict)
        client.uploadFailure = IOException("Storage is unavailable. Try again.")
        sync.resolve(repository, conflict.id, incoming = false)
        assertNotNull(sync.state.value.conflict)
        assertEquals("Storage is unavailable. Try again.", sync.state.value.error)
        assertFalse(sync.state.value.busy)
        assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
        client.uploadFailure = null
        sync.resolve(repository, conflict.id, incoming = false)
        assertArrayEquals(old, client.published.single())
        assertNull(sync.state.value.conflict)
        sync.refresh(repository)
        assertNull(sync.state.value.conflict)
    }

    @Test fun alwaysIncomingAndAlwaysLocalResolveWithoutPrompting() = runBlocking {
        connect(IncomingDisplayPicture(new, true))
        OwnDisplayPictureStore.write(context, account, old)
        DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AlwaysIncoming)
        sync.refresh(repository)
        assertNull(sync.state.value.conflict)
        assertArrayEquals(new, OwnDisplayPictureStore.read(context, account).bytes)
        assertTrue(client.published.isEmpty())
        OwnDisplayPictureStore.write(context, account, old)
        DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AlwaysLocal)
        sync.refresh(repository)
        assertNull(sync.state.value.conflict)
        assertArrayEquals(old, client.published.single())
        assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
    }

    @Test fun explicitLocalAndServerRemovalsAreComparedAndResolvedAsRealChoices() = runBlocking {
        connect(IncomingDisplayPicture(new, true))
        OwnDisplayPictureStore.write(context, account, null)
        sync.refresh(repository)
        assertNull(sync.state.value.conflict?.local?.uri)
        assertNotNull(sync.state.value.conflict)
        DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AlwaysLocal)
        sync.refresh(repository)
        assertEquals(1, client.published.size)
        assertNull(client.published.single())
        assertTrue(OwnDisplayPictureStore.read(context, account).initialized)
        OwnDisplayPictureStore.write(context, account, old)
        client.incoming = IncomingDisplayPicture(null, true)
        DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AskEveryTime)
        sync.refresh(repository)
        val conflict = requireNotNull(sync.state.value.conflict)
        assertNull(conflict.incomingUri)
        sync.resolve(repository, conflict.id, incoming = true)
        assertNull(repository.profile.value.avatarUri)
        assertTrue(OwnDisplayPictureStore.read(context, account).initialized)
        assertNull(OwnDisplayPictureStore.read(context, account).bytes)
    }

    @Test fun failedAndInvalidFetchesPreserveLocalAndDoNotInitializeFirstLogin() = runBlocking {
        val profile = connect(IncomingDisplayPicture())
        sync.restore(repository, profile)
        assertFalse(OwnDisplayPictureStore.read(context, account).initialized)
        client.incoming = IncomingDisplayPicture(byteArrayOf(1, 2, 3), true)
        sync.refresh(repository)
        assertFalse(OwnDisplayPictureStore.read(context, account).initialized)
        OwnDisplayPictureStore.write(context, account, old)
        for (incoming in listOf(IncomingDisplayPicture(), IncomingDisplayPicture(byteArrayOf(1, 2), true))) {
            client.incoming = incoming
            sync.refresh(repository)
            assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
            assertArrayEquals(old, context.loadDisplayPictureBytes(repository.profile.value.avatarUri))
            assertNull(sync.state.value.conflict)
        }
    }

    @Test fun identicalBytesWithDifferentUrisDoNotAskAndInlineSaveRestoresAfterSignOut() = runBlocking {
        connect(IncomingDisplayPicture(new, true))
        SessionPrefs.saveUserProfile(context, "You", Presence.Online, new.displayPictureDataUri())
        val fileUri = SessionPrefs.readUserProfile(context).avatarUri
        assertTrue(fileUri?.startsWith("file:") == true)
        SessionPrefs.logout()
        SessionPrefs.activateRememberedSession(context)
        assertArrayEquals(new, context.loadDisplayPictureBytes(SessionPrefs.readUserProfile(context).avatarUri))
        sync.refresh(repository)
        assertNull(sync.state.value.conflict)
        assertTrue(client.published.isEmpty())
    }

    @Test fun deferredChoiceIsPreservedForTheNextLaunchAndNotImmediatelyReopened() = runBlocking {
        connect(IncomingDisplayPicture(new, true))
        OwnDisplayPictureStore.write(context, account, old)
        sync.refresh(repository)
        sync.dismiss(requireNotNull(sync.state.value.conflict).id)
        sync.refresh(repository)
        assertNull(sync.state.value.conflict)
        assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
        val nextLaunch = DisplayPictureSync(context)
        nextLaunch.refresh(repository)
        assertNotNull(nextLaunch.state.value.conflict)
    }

    @Test fun fetchForPreviousAccountCannotChangeAnotherAccountsPictureOrPreferences() = runBlocking {
        connect(IncomingDisplayPicture(new, true))
        OwnDisplayPictureStore.write(context, account, old)
        DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AlwaysIncoming)
        client.fetchStarted = CompletableDeferred()
        client.fetchGate = CompletableDeferred()
        val refresh = async(Dispatchers.IO) { sync.refresh(repository) }
        client.fetchStarted!!.await()
        SessionPrefs.completeLogin(context, "other@example.test", "password", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = true)
        client.fetchGate!!.complete(Unit)
        refresh.await()
        val other = requireNotNull(SessionPrefs.preferenceAccount(context))
        assertFalse(OwnDisplayPictureStore.read(context, other).initialized)
        assertEquals(DisplayPictureSyncBehavior.AskEveryTime, DisplayPictureSyncPrefs.behavior(context))
        assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
        assertNull(sync.state.value.conflict)
    }

    @Test fun forgettingAccountDeletesItsSavedPictureAndPolicy() {
        OwnDisplayPictureStore.write(context, account, old)
        DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AlwaysLocal)
        SessionPrefs.disableRememberedCredentials(context, account = account)
        assertFalse(OwnDisplayPictureStore.read(context, account).initialized)
        assertEquals(DisplayPictureSyncBehavior.AskEveryTime, DisplayPictureSyncPrefs.behavior(context, account))
    }

    @Test fun comparisonModalPlacesOldPictureArrowAndIncomingPictureInOrderAndHandlesBusyState() {
        val local = OwnDisplayPictureStore.write(context, account, old)
        val conflict = DisplayPictureConflict(account, local, IncomingDisplayPicture(new, true))
        var state by mutableStateOf(DisplayPictureSyncState(conflict))
        var oldChoices = 0; var newChoices = 0; var dismissals = 0
        compose.setContent {
            MsngrTheme { DisplayPictureConflictDialog(conflict, state, { oldChoices++ }, { newChoices++ }, { dismissals++ }) }
        }
        val left = compose.onNodeWithTag("old-display-picture").fetchSemanticsNode().boundsInRoot
        val arrow = compose.onNodeWithContentDescription("Changed to").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("incoming-display-picture").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right < arrow.left && arrow.right < right.left)
        screenshot("display-picture-conflict")
        compose.onNodeWithTag("keep-local-picture").performClick()
        compose.onNodeWithTag("use-incoming-picture").performClick()
        assertEquals(1, oldChoices); assertEquals(1, newChoices)
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithTag("keep-local-picture").assertIsNotEnabled()
        compose.onNodeWithTag("use-incoming-picture").assertIsNotEnabled()
        compose.onNodeWithText("Not now").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false, error = "Try again") }
        compose.onNodeWithTag("picture-sync-error").assertExists()
        compose.onNodeWithTag("use-incoming-picture").assertIsEnabled()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, dismissals)
    }

    @Test fun connectionSettingDefaultsToAskAndPersistsEveryChoice() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MsngrTheme { ConnectionSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior(), {}) }
            }
        }
        assertEquals(DisplayPictureSyncBehavior.AskEveryTime, DisplayPictureSyncPrefs.behavior(context))
        compose.onNodeWithTag("display-picture-sync-setting").performScrollTo().performClick()
        compose.onNodeWithTag("picture-sync-AskEveryTime").assertIsSelected()
        screenshot("display-picture-sync-setting")
        for (behavior in DisplayPictureSyncBehavior.entries) {
            compose.onNodeWithTag("picture-sync-${behavior.name}").performClick()
            assertEquals(behavior, DisplayPictureSyncPrefs.behavior(context))
            compose.onNodeWithTag("display-picture-sync-setting").performScrollTo().performClick()
            compose.onNodeWithTag("picture-sync-${behavior.name}").assertIsSelected()
        }
    }

    private fun login() = SessionPrefs.completeLogin(context, email, "password", true, ProviderOptions.ESCARGOT,
        ServerOverrides(), useMockServer = true)

    private suspend fun connect(incoming: IncomingDisplayPicture): UserProfileSnapshot {
        client.incoming = incoming
        return repository.signIn(SignInRequest(email, "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
    }

    private class PictureClient : MsnpClient by MockMsnp18Client(signInDelayMillis = 0) {
        var incoming = IncomingDisplayPicture()
        var fetches = 0
        var uploadFailure: Exception? = null
        var fetchStarted: CompletableDeferred<Unit>? = null
        var fetchGate: CompletableDeferred<Unit>? = null
        val published = mutableListOf<ByteArray?>()
        override suspend fun signIn(request: SignInRequest) = Result.success(UserProfileSnapshot("You", request.email,
            request.initialPresence, incoming.bytes?.takeIf { org.aralozkaya.msngr.protocol.DisplayPictureFormat.detect(it) != null }?.displayPictureDataUri(),
            displayPictureFetched = incoming.fetched))
        override suspend fun fetchOwnDisplayPicture(): IncomingDisplayPicture {
            fetches++
            fetchStarted?.complete(Unit)
            fetchGate?.await()
            return incoming
        }
        override suspend fun changeDisplayPicture(image: ByteArray?) {
            uploadFailure?.let { throw it }
            published += image?.copyOf()
            incoming = IncomingDisplayPicture(image?.copyOf(), true)
        }
        override suspend fun adoptIncomingDisplayPicture(image: ByteArray?) = Unit
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Window entry animations run on the platform clock, outside Compose's test clock.
        android.os.SystemClock.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        output.mkdirs()
        try { File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
