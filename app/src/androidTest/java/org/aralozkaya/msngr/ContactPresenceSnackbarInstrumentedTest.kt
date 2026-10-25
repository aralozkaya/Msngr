package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactPresenceSnackbarInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun busyNoticePersistsUntilDismissedAndDoesNotReappearOnProfileUpdates() {
        val contact = mutableStateOf(contact(Presence.Busy))
        compose.setContent {
            MaterialTheme {
                Scaffold(snackbarHost = { ContactPresenceSnackbar(contact.value) }) { innerPadding ->
                    Box(Modifier.padding(innerPadding))
                }
            }
        }
        compose.onNodeWithText("Alex is busy and may not respond").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText("Alex is busy and may not respond").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Alex is busy and may not respond").assertDoesNotExist()
        compose.runOnIdle { contact.value = contact.value.copy(nickname = "Alex Updated") }
        compose.onNodeWithText("Alex Updated is busy and may not respond").assertDoesNotExist()
        compose.runOnIdle { contact.value = contact.value.copy(presence = Presence.Away) }
        compose.onNodeWithText("Alex Updated is away and may not respond").assertIsDisplayed()
    }

    @Test
    fun presenceUpdatesReplaceNoticeAndAvailableOrUnknownContactsHaveNoNotice() {
        val contact = mutableStateOf<Contact?>(null)
        compose.setContent {
            MaterialTheme {
                Scaffold(snackbarHost = { ContactPresenceSnackbar(contact.value) }) { innerPadding ->
                    Box(Modifier.padding(innerPadding))
                }
            }
        }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        compose.runOnIdle { contact.value = contact(Presence.Away) }
        compose.onNodeWithText("Alex is away and may not respond").assertIsDisplayed()
        compose.runOnIdle { contact.value = contact(Presence.Offline) }
        val offline = "Alex is offline. Messages you send will be delivered when they sign in."
        compose.onNodeWithText(offline).assertIsDisplayed()
        compose.onNodeWithText("Alex is away and may not respond").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText(offline).assertIsDisplayed()
        compose.runOnIdle { contact.value = contact(Presence.Online) }
        compose.onNodeWithText(offline).assertDoesNotExist()
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test
    fun appearOfflineNoticeTracksOwnStatusAndStaysDismissedOnProfileUpdates() {
        val contact = mutableStateOf(contact(Presence.Online))
        val ownPresence = mutableStateOf(Presence.Online)
        compose.setContent {
            MaterialTheme {
                Scaffold(snackbarHost = {
                    ContactPresenceSnackbar(contact.value, ownPresence = ownPresence.value)
                }) { innerPadding ->
                    Box(Modifier.padding(innerPadding))
                }
            }
        }
        compose.onNodeWithText(appearOfflineNotice).assertDoesNotExist()
        compose.runOnIdle { ownPresence.value = Presence.Offline }
        compose.onNodeWithText(appearOfflineNotice).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText(appearOfflineNotice).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText(appearOfflineNotice).assertDoesNotExist()
        compose.runOnIdle { contact.value = contact.value.copy(nickname = "Alex Updated") }
        compose.onNodeWithText(appearOfflineNotice).assertDoesNotExist()
        compose.runOnIdle { ownPresence.value = Presence.Away }
        compose.onNodeWithText(appearOfflineNotice).assertDoesNotExist()
        compose.runOnIdle { ownPresence.value = Presence.Offline }
        compose.onNodeWithText(appearOfflineNotice).assertIsDisplayed()
        compose.runOnIdle { ownPresence.value = Presence.Online }
        compose.onNodeWithText(appearOfflineNotice).assertDoesNotExist()
    }

    @Test
    fun appearOfflineNoticeAppliesToAllNonOfflineContactsAndPreservesOtherNotices() {
        val contact = mutableStateOf<Contact?>(null)
        compose.setContent {
            MaterialTheme {
                Scaffold(snackbarHost = {
                    ContactPresenceSnackbar(contact.value, ownPresence = Presence.Offline)
                }) { innerPadding ->
                    Box(Modifier.padding(innerPadding))
                }
            }
        }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        for (presence in listOf(Presence.Online, Presence.Busy, Presence.Away)) {
            compose.runOnIdle { contact.value = contact(presence) }
            compose.onNodeWithText(appearOfflineNotice).assertIsDisplayed()
            compose.onNodeWithText("Alex is busy and may not respond").assertDoesNotExist()
            compose.onNodeWithText("Alex is away and may not respond").assertDoesNotExist()
        }
        compose.runOnIdle { contact.value = contact(Presence.Offline) }
        compose.onNodeWithText(appearOfflineNotice).assertDoesNotExist()
        compose.onNodeWithText("Alex is offline. Messages you send will be delivered when they sign in.")
            .assertIsDisplayed()
        compose.runOnIdle { contact.value = null }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        compose.runOnIdle { contact.value = contact(Presence.Online).copy(isGroup = true) }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        compose.runOnIdle { contact.value = contact.value?.copy(presence = Presence.Offline) }
        compose.onNodeWithText("Alex is currently offline. You can send a message when more group members are online.")
            .assertIsDisplayed()
    }

    private val appearOfflineNotice = "Your status is set to Appear offline. You can communicate with your contacts " +
        "but they will know you are appearing offline."

    private fun contact(presence: Presence) = Contact(
        id = "alex@example.com",
        nickname = "\u0002Alex\u0002",
        email = "alex@example.com",
        presence = presence,
    )
}
