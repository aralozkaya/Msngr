package org.aralozkaya.msngr

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in check against an already signed-in same-account desktop displaying (None).
 * Uses the encrypted saved account; never logs credentials or captures private screenshots.
 * The synthetic comparison baseline is local only and restored in finally.
 */
class MultiPresencePictureLiveInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun nativeRemovalOpensTheRealChoiceAndCanBeAdoptedWithoutRoamingStorage() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("multiPresencePictureInterop") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val login = SessionPrefs.readLoginSnapshot(context)
        assumeTrue(login.savePassword && !login.useMockServer && login.password.isNotBlank())
        val account = AccountIdentity.forServer(login.email, login.resolvedServers, false)
        SessionPrefs.activateRememberedSession(context)
        val original = OwnDisplayPictureStore.read(context, account)
        val profileBefore = SessionPrefs.readUserProfile(context)
        val policy = DisplayPictureSyncPrefs.behavior(context, account)
        val repository = context.appContainer.repositoryFor(false)
        val sync = context.displayPictureSync
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            assertTrue(repository.signIn(SignInRequest(login.email, login.password, login.resolvedServers,
                login.loginPresence)).isSuccess)
            DisplayPictureSyncPrefs.setBehavior(context, DisplayPictureSyncBehavior.AskEveryTime)
            val baseline = context.assets.open("display_pictures/beach_chairs.png").use { it.readBytes() }
            OwnDisplayPictureStore.write(context, account, baseline)
            sync.refresh(repository)
            val conflict = requireNotNull(sync.state.value.conflict) { "The desktop must advertise no display picture." }
            assertNull(conflict.incoming.bytes)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("display-picture-conflict").assertExists() }.isSuccess
            }
            compose.onNodeWithTag("use-incoming-picture").performClick()
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("display-picture-conflict").assertDoesNotExist() }.isSuccess
            }
            assertTrue(OwnDisplayPictureStore.read(context, account).initialized)
            assertNull(OwnDisplayPictureStore.read(context, account).bytes)
            assertNull(repository.profile.value.avatarUri)
        } finally {
            scenario?.close()
            if (original.initialized) OwnDisplayPictureStore.write(context, account, original.bytes)
            else OwnDisplayPictureStore.clear(context, account)
            DisplayPictureSyncPrefs.setBehavior(context, policy)
            SessionPrefs.saveUserProfile(context, profileBefore.username, profileBefore.presence,
                profileBefore.avatarUri, profileBefore.statusMessage, saveDisplayPicture = false)
            repository.logout()
            SessionPrefs.logout()
        }
    }
}
