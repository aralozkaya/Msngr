package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.SignInRequest
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
class MockUserJourneyInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun openAuthenticatedMockSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        runCatching { context.appContainer.messengerRepository.logout() }
        SessionPrefs.disableRememberedCredentials(context)
        SessionPrefs.logout()
        EmoticonPrefs.setConvertTypedShortcuts(context, true)
        EmoticonPrefs.setUseOriginalEmoticons(context, true)
        MessengerPlusPrefs.setEnabled(context, true)

        val repository = context.appContainer.repositoryFor(useMockServer = true)
        val serverConfig = ServerPresets.resolveForProvider(
            ProviderOptions.ESCARGOT,
            ServerOverrides(),
        )
        val serverProfile = repository.signIn(
            SignInRequest(
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                serverConfig = serverConfig,
                initialPresence = Presence.Busy,
            ),
        ).getOrThrow()
        repository.clearConversationHistory()
        SessionPrefs.completeLogin(
            context = context,
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            savePassword = true,
            provider = ProviderOptions.ESCARGOT,
            customServers = ServerOverrides(),
            useMockServer = true,
            automaticSignIn = true,
            loginPresence = Presence.Busy,
        )
        SessionPrefs.saveUserProfile(
            context = context,
            username = MockServerData.ACCOUNT_EMAIL.substringBefore('@'),
            presence = Presence.Busy,
            statusMessage = MockServerData.ACCOUNT_STATUS_MESSAGE,
        )
        repository.setLocalProfile(SessionPrefs.mergeServerProfile(context, serverProfile))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeMockSession() {
        scenario?.close()
        runBlocking {
            runCatching { context.appContainer.messengerRepository.clearConversationHistory() }
        }
        runCatching { context.appContainer.messengerRepository.logout() }
        MessengerPlusPrefs.setEnabled(context, true)
        SessionPrefs.disableRememberedCredentials(context)
        SessionPrefs.logout()
    }

    @Test
    fun openingChatCreatesTheMessageComposer() {
        val contact = MockServerData.contacts().first()
        waitForText(contact.nickname.orEmpty())

        compose.onNodeWithText(contact.nickname.orEmpty()).performClick()

        waitForText("Start a conversation with ${contact.displayName}.")
        compose.onNodeWithContentDescription("Format text").assertDoesNotExist()
        val messageField = compose.onAllNodes(hasSetTextAction())[0]
        messageField.performTextReplacement("Select this text to style it")
        messageField.assertTextEquals("Select this text to style it")
        compose.onNodeWithTag(MESSENGER_PLUS_COLOR_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun disabledMessengerPlusHidesFormattingAndSendsPlainText() {
        MessengerPlusPrefs.setEnabled(context, false)
        val contact = MockServerData.contacts().first()
        waitForText(contact.nickname.orEmpty())

        compose.onNodeWithText(contact.nickname.orEmpty()).performClick()

        waitForText("Start a conversation with ${contact.displayName}.")
        compose.onNodeWithContentDescription("Format text").assertDoesNotExist()
        val messageField = compose.onAllNodes(hasSetTextAction())[0]
        messageField.performTextReplacement("[b]plain text[/b]")
        messageField.assertTextEquals("plain text")
        compose.onNodeWithContentDescription("Send message").performClick()
        waitForText("plain text")
    }

    @Test
    fun authenticatedMockJourneyCoversProfileSettingsChatAndLogout() {
        waitForText(MockServerData.contacts().first().nickname.orEmpty())
        compose.onNodeWithText(MOCK_BANNER).assertExists()
        compose.onNodeWithText("Other Contacts").assertExists()
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onAllNodesWithText("Sort")[1].assertExists()
        compose.onNodeWithText("Status").assertExists()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithTag("hide_offline_contacts").assertExists()
        compose.onNodeWithContentDescription("Close sheet").performClick()

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Hide offline groups and contacts").assertDoesNotExist()
        compose.onNodeWithText("About").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        compose.onNodeWithText(MOCK_BANNER).assertExists()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Emoticons").assertExists()
        compose.onNodeWithText("Messenger Plus!").performClick()
        waitForText("Enable Messenger Plus! rendering")
        val messengerPlusToggle = compose.onNodeWithTag("messenger_plus_enabled")
        messengerPlusToggle.assertIsOn()
        compose.onNodeWithText(MESSENGER_PLUS_DISCLAIMER).assertExists()
        messengerPlusToggle.performClick().assertIsOff()
        compose.runOnIdle { assertFalse(MessengerPlusPrefs.isEnabled(context)) }
        messengerPlusToggle.performClick().assertIsOn()
        compose.runOnIdle { assertTrue(MessengerPlusPrefs.isEnabled(context)) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("keep_messenger_connected").assertDoesNotExist()
        compose.onNodeWithTag("sign_out_other_sessions_on_sign_in").assertDoesNotExist()
        compose.onNodeWithText("Delete all chat histories").assertExists()
        compose.onNodeWithText("Account and services").assertDoesNotExist()
        compose.onNodeWithText("Connection").performScrollTo().performClick()
        waitForText("Connection information")
        compose.onNodeWithText("Sign me out of all other places when I sign in to Msngr").assertExists()
        compose.onNodeWithText("Keep Msngr Connected").assertExists()
        compose.onNodeWithTag("keep_messenger_connected").assertExists()
        compose.onNodeWithText("Connection information").performClick()
        waitForText("Provider")
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Remembered credentials").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Authentication"))
        compose.onNodeWithText("Authentication").assertExists()
        compose.onNodeWithText("Connected services").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Mail"))
        compose.onNodeWithText("Mail").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Keep Msngr Connected")
        compose.onNodeWithText("Connection information").performClick()
        waitForText("Provider")
        pressBack()
        waitForText("Keep Msngr Connected")
        pressBack()
        waitForText("Settings")

        compose.onNodeWithText("Appearance").performClick()
        waitForText("System")
        compose.onNodeWithText("System").assertExists()
        compose.onNodeWithText("Dynamic").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Emoticons").performClick()
        waitForText("Convert typed shortcuts")
        val shortcutToggle = compose.onNodeWithTag("convert_typed_shortcuts")
        shortcutToggle.assertIsOn().performClick().assertIsOff()
        assertFalse(EmoticonPrefs.state(context).value.convertTypedShortcuts)
        assertFalse(
            AccountPreferences.preferences(context, "msngr_emoticons")
                .getBoolean("convert_typed_shortcuts", true),
        )
        shortcutToggle.performClick().assertIsOn()
        val originalToggle = compose.onNodeWithTag("use_original_emoticons")
        originalToggle.assertIsOn().performClick().assertIsOff()
        assertFalse(EmoticonPrefs.state(context).value.useOriginalEmoticons)
        assertFalse(
            AccountPreferences.preferences(context, "msngr_emoticons")
                .getBoolean("use_original_emoticons", true),
        )
        originalToggle.performClick().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Settings")
        compose.onNodeWithText("About").performScrollTo().performClick()
        waitForText("Developer")
        compose.onNode(isDialog()).assertDoesNotExist()
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        compose.onNodeWithText("Version $version").assertExists()
        compose.onNodeWithText("Developer").assertExists()
        compose.onNodeWithText("Aral Özkaya").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Settings")
        compose.onNodeWithContentDescription("Back").performClick()

        waitForText(MockServerData.ACCOUNT_EMAIL.substringBefore('@'))
        compose.onNodeWithText(MockServerData.ACCOUNT_EMAIL.substringBefore('@')).performClick()
        waitForText("Edit profile")
        compose.onNodeWithText(MOCK_BANNER).assertExists()
        compose.onNodeWithText("Plus tags: [b], [i], [u], [s], [c=…], and [g=…,…]")
            .assertDoesNotExist()
        compose.onNodeWithText("Visible to contacts · Plus formatting tags are supported")
            .assertDoesNotExist()
        compose.onNodeWithText("Visible to your Messenger contacts").assertExists()
        compose.onNodeWithContentDescription("Format display name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Format personal message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Display name").assertExists()
        compose.onNodeWithContentDescription("Personal message").assertExists()
        val profileFields = compose.onAllNodes(hasSetTextAction())
        profileFields[0].assertTextContains(MockServerData.ACCOUNT_EMAIL.substringBefore('@'))
        profileFields[1].assertTextContains(MockServerData.ACCOUNT_STATUS_MESSAGE)
        profileFields[0].performTextReplacement("${MockServerData.ACCOUNT_NICKNAME}😀")
        profileFields[0].assertTextContains(MockServerData.ACCOUNT_NICKNAME)
        profileFields[1].performTextReplacement("Temporary mock message")
        compose.onNodeWithContentDescription("Clear personal message")
            .assertExists()
            .performClick()
        profileFields[1].performTextReplacement(MockServerData.ACCOUNT_STATUS_MESSAGE)
        compose.onNodeWithText("Save profile").performClick()

        waitForText(MockServerData.ACCOUNT_NICKNAME)
        compose.onAllNodesWithText(MockServerData.ACCOUNT_STATUS_MESSAGE)[0].assertExists()
        val contact = MockServerData.contacts().first()
        waitForText(contact.nickname.orEmpty())
        compose.onNodeWithText(contact.nickname.orEmpty()).performClick()
        waitForText(context.getString(R.string.empty_conversation_warning))
        compose.onNodeWithText(MOCK_BANNER).assertExists()

        val messageField = compose.onAllNodes(hasSetTextAction())[0]
        messageField.performTextReplacement("hello from mock UI")
        compose.onNodeWithContentDescription("Send message").performClick()
        waitForText("hello from mock UI")
        waitForText(MockServerData.replyTo("hello from mock UI"), timeoutMillis = 5_000)

        messageField.performTextReplacement("keyboard😀 input")
        messageField.assertTextEquals("keyboard input")
        compose.onNodeWithContentDescription("Send message").performClick()
        waitForText("keyboard input")

        compose.onNodeWithContentDescription("Show emoticons").assertExists()
        messageField.performTextReplacement("(y)")
        compose.onNodeWithContentDescription("Send message").performClick()
        waitForText("(y)")
        compose.onNodeWithContentDescription("Send nudge").performClick()
        waitForText("You sent a nudge.")
        compose.onNodeWithContentDescription("Back").performClick()

        waitForText(MockServerData.ACCOUNT_NICKNAME)
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Log out").performClick()
        waitForText("Log out?")
        compose.onNodeWithText("Log out").performClick()

        waitForText("Welcome to Msngr")
        compose.onNodeWithText(MOCK_BANNER).assertExists()
        compose.onNodeWithText("Use mock server").assertExists()
        compose.onNodeWithText("Sign me in automatically").assertExists()
        compose.onNodeWithText("Busy").assertExists()
        val remembered = SessionPrefs.readLoginSnapshot(context)
        assertEquals(MockServerData.ACCOUNT_PASSWORD, remembered.password)
        assertTrue(remembered.automaticSignIn)
        assertEquals(Presence.Busy, remembered.loginPresence)
        assertFalse(SessionPrefs.canOpenAuthenticatedScreens(context))
        val persistedProfile = SessionPrefs.readUserProfile(context)
        assertEquals(MockServerData.ACCOUNT_NICKNAME, persistedProfile.username)
        assertEquals(MockServerData.ACCOUNT_STATUS_MESSAGE, persistedProfile.statusMessage)

        compose.onNodeWithTag("automatic_sign_in").onChildren()[0].performClick()
        assertFalse(SessionPrefs.readLoginSnapshot(context).automaticSignIn)

        compose.onNodeWithTag("remember_credentials").onChildren()[0].performClick()
        val cleared = SessionPrefs.readLoginSnapshot(context)
        assertEquals("", cleared.password)
        assertFalse(cleared.savePassword)
        assertFalse(cleared.automaticSignIn)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 3_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val MOCK_BANNER = "Mock server active · Test data only"
    }
}
