@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactRequest
import org.aralozkaya.msngr.data.GroupInvitation
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.RequestsPage
import org.aralozkaya.msngr.pages.components.GroupEditorDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class RequestMessagesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val id = "00000000-0000-0000-0009-123456789abc@live.com"
    private val message = "Merhaba & <hello> ğüşi 🙂\nSecond line"

    @Test fun creationSendsTheMessageWithInitialInvitations() {
        var finished = false
        var created = 0
        compose.setContent {
            MaterialTheme { GroupEditorDialog(owner, emptyList(), onCreate = {
                created++; Result.success(id)
            }, onInvite = { group, emails, text ->
                assertEquals(id, group); assertEquals(listOf(peer), emails); assertEquals(message, text)
                Result.success(Unit)
            }, onFinished = { _, _ -> finished = true }, onDismiss = {}) }
        }
        compose.onNodeWithTag("group-name").performTextInput("Custom message group")
        compose.onNodeWithTag("group-invite-emails").performTextInput(peer)
        compose.onNodeWithTag("group-invitation-message").performScrollTo().performTextInput(message)
        compose.onNodeWithTag("group-editor-submit").performClick()
        compose.waitUntil(5000) { finished }
        assertEquals(1, created)
    }

    @Test fun existingGroupSendsTheMessageWithAManuallyEnteredRecipient() {
        var finished = false
        compose.setContent {
            MaterialTheme { GroupEditorDialog(owner, emptyList(),
                group = Contact(id, "Msngr capture UI", id, Presence.Offline, isGroup = true),
                onCreate = { error("Existing group") }, onInvite = { group, emails, text ->
                    assertEquals(id, group); assertEquals(listOf(peer), emails); assertEquals(message, text)
                    Result.success(Unit)
                }, onFinished = { _, _ -> finished = true }, onDismiss = {}) }
        }
        compose.onNodeWithTag("group-invite-emails").performTextInput(peer)
        compose.onNodeWithTag("group-invitation-message").performScrollTo().performTextInput(message)
        compose.onNodeWithTag("group-editor-submit").assertIsEnabled().performClick()
        compose.waitUntil(5000) { finished }
    }

    @Test fun requestCardsAndContactDecisionShowOnlyTheirOwnCustomMessage() {
        val second = "00000000-0000-0000-0009-123456789abd@live.com"
        val contactText = "Contact message & <hello> 🙂"
        compose.setContent {
            MaterialTheme { RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                listOf(ContactRequest(peer, "Pending/test", message = contactText)), { _, _, _ -> Result.success(Unit) },
                invitations = listOf(GroupInvitation(id, "Msngr capture A", peer, message = message),
                    GroupInvitation(second, "Msngr capture B", peer, message = "Separate group message"))) }
        }
        compose.onNodeWithTag("contact-request-message-$peer", useUnmergedTree = true).assertTextEquals(contactText)
        compose.onNodeWithTag("group-invitation-message-$id", useUnmergedTree = true).performScrollTo().assertTextEquals(message)
        compose.onNodeWithTag("group-invitation-message-$second", useUnmergedTree = true).performScrollTo().assertTextEquals("Separate group message")
        File(compose.activity.filesDir, "request-messages.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("accept-request-$peer").performScrollTo().performClick()
        compose.onNodeWithTag("contact-request-decision-message").assertIsDisplayed().assertTextEquals(contactText)
    }
}
