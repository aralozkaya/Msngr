package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import org.aralozkaya.msngr.data.ContactRequest
import org.aralozkaya.msngr.pages.ContactInvitationSettingsScreen
import org.aralozkaya.msngr.pages.RequestsPage
import org.aralozkaya.msngr.pages.SettingsDestination
import org.aralozkaya.msngr.pages.SettingsScreen
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ContactInvitationSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context

    @Before fun prepareAccount() {
        val target = compose.activity
        context = object : ContextWrapper(target.createDeviceProtectedStorageContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "msngr_session_secure_keyset") {
                    target.applicationContext.getSharedPreferences(name, mode)
                } else super.getSharedPreferences("contact_invitation_settings_test_$name", mode)
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        SessionPrefs.logout()
        SessionPrefs.rememberedProfiles(context).forEach {
            SessionPrefs.disableRememberedCredentials(context, account = it.accountIdentity)
        }
        SessionPrefs.completeLogin(context, "invitation-settings@example.com", "test-password", true,
            ProviderOptions.ESCARGOT, ServerOverrides(), useMockServer = true)
    }

    @After fun clearAccount() {
        SessionPrefs.disableRememberedCredentials(context)
        SessionPrefs.logout()
        AccountPreferences.reload(compose.activity)
    }

    @Test fun dedicatedPageKeepsChoicesAcrossReopeningAndDisablingAtLargeText() {
        val destination = mutableStateOf<SettingsDestination?>(null)
        val version = mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context,
                LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    key(version.intValue) {
                        SecondaryPageScaffold(destination.value?.title ?: "Settings",
                            { destination.value = null }) { padding, scroll ->
                            if (destination.value == SettingsDestination.ContactInvitations) {
                                ContactInvitationSettingsScreen(padding, scroll)
                            } else CompositionLocalProvider(LocalContext provides compose.activity) {
                                SettingsScreen(padding, scroll, { destination.value = it }, {})
                            }
                        }
                    }
                }
            }
        }
        fun openPage() {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("contact_invitation_settings"))
            compose.onNodeWithTag("contact_invitation_settings").performClick()
        }
        openPage()
        compose.onNodeWithText("Contact Invitations").assertIsDisplayed()
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").assertIsOff()
        compose.onNodeWithTag("block_declined_contact_invitations").assertDoesNotExist()
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").performClick().assertIsOn()
        compose.onNodeWithTag("block_declined_contact_invitations").assertIsOff().assertIsEnabled().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(ContactInvitationSettings(true, true), ContactInvitationPrefs.state(context).value)
            destination.value = null
            version.intValue++
        }
        openPage()
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").assertIsOn()
        compose.onNodeWithTag("block_declined_contact_invitations").assertIsOn().assertIsEnabled()
        File(compose.activity.getExternalFilesDir(null), "contact-invitation-settings-large-text.png")
            .outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").performClick().assertIsOff()
        compose.onNodeWithTag("block_declined_contact_invitations").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ContactInvitationSettings(false, true), ContactInvitationPrefs.state(context).value)
        }
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").performClick().assertIsOn()
        compose.onNodeWithTag("block_declined_contact_invitations").assertIsOn().assertIsEnabled()
    }

    @Test fun collapsedOptionRetainsItsSavedValueAndReappearsAsAnEditableCheckbox() {
        ContactInvitationPrefs.saveDeclineDefaults(context, block = true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    SecondaryPageScaffold("Contact Invitations", {}) { padding, scroll ->
                        ContactInvitationSettingsScreen(padding, scroll)
                    }
                }
            }
        }
        val option = compose.onNodeWithTag("block_declined_contact_invitations")
        val toggle = compose.onNodeWithTag("use_contact_invitation_decline_defaults")
        option.assertIsDisplayed().assertIsOn().assertIsEnabled()
        toggle.performClick().assertIsOff()
        option.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ContactInvitationSettings(false, true), ContactInvitationPrefs.state(context).value)
        }
        toggle.performClick().assertIsOn()
        option.assertIsEnabled().performClick().assertIsOff()
        toggle.performClick().assertIsOff()
        option.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ContactInvitationSettings(false, false), ContactInvitationPrefs.state(context).value)
        }
    }

    @Test fun successfulDialogDefaultUpdatesPageAndNextDeclineUsesEditedSettings() {
        val first = ContactRequest("first@example.com", "Pending/first")
        val second = ContactRequest("second@example.com", "Pending/second")
        val third = ContactRequest("third@example.com", "Pending/third")
        val requests = mutableStateOf(listOf(first))
        val showSettings = mutableStateOf(false)
        val calls = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    val settings by remember { ContactInvitationPrefs.state(context) }.collectAsState()
                    SecondaryPageScaffold(if (showSettings.value) "Contact Invitations" else "Requests & invitations",
                        {}) { padding, scroll ->
                        if (showSettings.value) ContactInvitationSettingsScreen(padding, scroll)
                        else RequestsPage(padding, scroll, requests.value, { id, accept, block ->
                            calls += Triple(id, accept, block)
                            requests.value = emptyList()
                            Result.success(Unit)
                        }, invitationSettings = settings,
                            onSaveDeclineDefaults = { ContactInvitationPrefs.saveDeclineDefaults(context, it) })
                    }
                }
            }
        }
        compose.onNodeWithTag("decline-request-${first.id}").performClick()
        compose.onNodeWithTag("contact-request-block").assertIsOff()
        compose.onNodeWithTag("contact-request-set-default").performClick()
        compose.onNodeWithTag("contact-request-submit").performClick()
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        compose.runOnIdle { showSettings.value = true }
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").assertIsOn()
        compose.onNodeWithTag("block_declined_contact_invitations").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { requests.value = listOf(second); showSettings.value = false }
        compose.onNodeWithTag("decline-request-${second.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        compose.runOnIdle { showSettings.value = true }
        compose.onNodeWithTag("use_contact_invitation_decline_defaults").performClick().assertIsOff()
        compose.runOnIdle { requests.value = listOf(third); showSettings.value = false }
        compose.onNodeWithTag("decline-request-${third.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertIsDisplayed()
        compose.onNodeWithTag("contact-request-block").assertIsOn()
        compose.onNodeWithText("Decide later").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple(first.id, false, false), Triple(second.id, false, true)), calls)
            assertEquals(ContactInvitationSettings(false, true), ContactInvitationPrefs.state(context).value)
        }
    }
}
