package org.aralozkaya.msngr

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.notifications.BackgroundConnectionController
import org.aralozkaya.msngr.notifications.BackgroundDeliveryPrefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DisplayPictureSyncLaunchInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun authenticatedLaunchAndWarmForegroundFetchCompareAndShowTheChoiceUntilResolved() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val email = "launch-picture-sync@example.test"
        val account = AccountIdentity.forLogin(email, useMockServer = true)
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        var scenario: ActivityScenario<MainActivity>? = null
        val old = context.assets.open("display_pictures/beach_chairs.png").use { it.readBytes() }
        val incoming = context.assets.open("display_pictures/animatedusertile3.gif").use { it.readBytes() }
        try {
            SessionPrefs.logout()
            repository.signIn(SignInRequest(email, "password",
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            SessionPrefs.completeLogin(context, email, "password", true, ProviderOptions.ESCARGOT,
                ServerOverrides(), useMockServer = true)
            BackgroundDeliveryPrefs.setKeepsConnectionAlive(context, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            OwnDisplayPictureStore.write(context, account, old)
            // Simulate the server changing while the account's saved local baseline stays old.
            repository.updateProfile("You", incoming.displayPictureDataUri(), incoming).getOrThrow()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("display-picture-conflict").assertExists() }.isSuccess
            }
            assertArrayEquals(old, OwnDisplayPictureStore.read(context, account).bytes)
            compose.onNodeWithText("Not now").performClick()
            compose.onNodeWithTag("display-picture-conflict").assertDoesNotExist()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("display-picture-conflict").assertExists() }.isSuccess
            }
            compose.onNodeWithTag("use-incoming-picture").performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("display-picture-conflict").assertDoesNotExist() }.isSuccess
            }
            assertArrayEquals(incoming, OwnDisplayPictureStore.read(context, account).bytes)
            assertArrayEquals(incoming, context.loadDisplayPictureBytes(repository.profile.value.avatarUri))
            scenario.recreate()
            compose.onNodeWithTag("display-picture-conflict").assertDoesNotExist()
            assertEquals(Presence.Online, repository.profile.value.presence)
        } finally {
            scenario?.close()
            BackgroundConnectionController.stop(context)
            repository.logout()
            SessionPrefs.disableRememberedCredentials(context, account = account)
            SessionPrefs.logout()
        }
    }
}
