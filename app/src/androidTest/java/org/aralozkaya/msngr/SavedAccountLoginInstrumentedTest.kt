package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.enums.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedAccountLoginInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private var scenario: ActivityScenario<LoginActivity>? = null
    private val alice = "alice-login-test@example.com"
    private val bob = "bob-login-test@example.com"
    private val bobMock get() = AccountIdentity.forLogin(bob, useMockServer = true)
    private val bobReal get() = AccountIdentity.forLogin(bob)

    @Before
    fun openLoginWithTwoSavedAccounts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.logout()
        listOf(alice, bob).forEach { email ->
            SessionPrefs.disableRememberedCredentials(context, account = AccountIdentity.forLogin(email, useMockServer = true))
            SessionPrefs.completeLogin(
                context, email, "password-$email", true, ProviderOptions.ESCARGOT,
                ServerOverrides(), useMockServer = true, automaticSignIn = true,
                loginPresence = if (email == alice) Presence.Away else Presence.Busy,
            )
            SessionPrefs.saveUserProfile(
                context, if (email == alice) "Alice profile" else "Bob profile",
                Presence.Online, avatarUri = null, statusMessage = "Message from $email",
            )
            EmoticonPrefs.setConvertTypedShortcuts(context, email != alice)
            SessionPrefs.logout()
        }
        SessionPrefs.disableRememberedCredentials(context, account = bobReal)
        SessionPrefs.completeLogin(
            context, bob, "real-password-$bob", true, ProviderOptions.ESCARGOT,
            ServerOverrides(), useMockServer = false, loginPresence = Presence.Away,
        )
        SessionPrefs.saveUserProfile(context, "Real Bob", Presence.Away, avatarUri = null, statusMessage = "Real message")
        SessionPrefs.logout()
        SessionPrefs.selectLoginAccount(context, bobMock)
        scenario = ActivityScenario.launch(LoginActivity.createIntent(context, allowAutomaticSignIn = false))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Welcome to Msngr").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun closeLoginAndRemoveFixtures() {
        scenario?.close()
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.logout()
        listOf(AccountIdentity.forLogin(alice, useMockServer = true), bobMock, bobReal)
            .forEach { SessionPrefs.disableRememberedCredentials(context, account = it) }
    }

    @Test
    fun savedAccountSelectionLoadsItsCredentialsAndRestoresItsOwnProfile() {
        selectAlice()
        compose.onNodeWithText("Away").assertExists()
        compose.onNodeWithText("Sign in").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("Alice profile")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        val profile = context.appContainer.messengerRepository.profile.value
        assertEquals(alice, profile.email)
        assertEquals("Alice profile", profile.username)
        assertEquals("Message from $alice", profile.statusMessage)
        assertFalse(EmoticonPrefs.state(context).value.convertTypedShortcuts)
        assertEquals("Bob profile", SessionPrefs.mergeServerProfile(
            context, UserProfileSnapshot("Server Bob", bob, Presence.Online), account = bobMock,
        ).username)
    }

    @Test
    fun loginSwitchesPreserveManualLoginAndForgetOnlyTheSelectedProfile() {
        selectAlice()
        compose.onNodeWithTag("automatic_sign_in").onChildren()[0].performClick()
        assertFalse(SessionPrefs.readLoginSnapshot(context, alice).automaticSignIn)
        assertTrue(SessionPrefs.readLoginSnapshot(context, alice).savePassword)
        assertEquals("Alice profile", SessionPrefs.readUserProfile(context).username)

        compose.onNodeWithTag("remember_credentials").onChildren()[0].performClick()
        assertPassword("")
        assertFalse(SessionPrefs.rememberedAccounts(context).contains(alice))
        assertTrue(SessionPrefs.rememberedAccounts(context).contains(bob))
        assertEquals("password-$bob", SessionPrefs.readLoginSnapshot(context, bobMock).password)
        val serverProfile = UserProfileSnapshot("Server Alice", alice, Presence.Online)
        assertEquals(serverProfile, SessionPrefs.mergeServerProfile(context, serverProfile))
        assertFalse(SessionPrefs.canOpenAuthenticatedScreens(context))
    }

    @Test
    fun enteringANewEmailClearsThePreviousAccountsPasswordAndOptions() {
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("new-login-test@example.com")
        assertPassword("")
        assertEquals("", SessionPrefs.readLoginSnapshot(context).password)
        assertEquals("password-$bob", SessionPrefs.readLoginSnapshot(context, bobMock).password)
        assertFalse(SessionPrefs.canOpenAuthenticatedScreens(context))
    }

    private fun selectAlice() {
        compose.onNodeWithContentDescription("Choose saved account").performClick()
        compose.onNodeWithText("$alice · Mock").performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains(alice)
        assertPassword("password-$alice")
    }

    private fun assertPassword(value: String) {
        compose.onAllNodes(hasSetTextAction())[1].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(value)),
        )
    }

    @Test
    fun togglingMockModeLoadsAndForgetsOnlyThatModesSavedAccount() {
        assertPassword("password-$bob")
        compose.onNodeWithTag("use_mock_server").onChildren()[0].performClick()
        assertPassword("real-password-$bob")
        compose.onNodeWithText("Away").assertExists()
        assertEquals("Real Bob", SessionPrefs.readUserProfile(context).username)
        assertFalse(SessionPrefs.readLoginSnapshot(context).useMockServer)

        compose.onNodeWithTag("remember_credentials").onChildren()[0].performClick()
        assertFalse(SessionPrefs.readLoginSnapshot(context, bobReal).savePassword)
        assertEquals("password-$bob", SessionPrefs.readLoginSnapshot(context, bobMock).password)

        compose.onNodeWithTag("use_mock_server").onChildren()[0].performClick()
        assertPassword("password-$bob")
        assertEquals("Bob profile", SessionPrefs.readUserProfile(context).username)
        assertTrue(SessionPrefs.readLoginSnapshot(context).useMockServer)
    }
}
