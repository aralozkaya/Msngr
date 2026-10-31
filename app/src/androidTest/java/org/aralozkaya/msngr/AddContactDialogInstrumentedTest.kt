package org.aralozkaya.msngr

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.CompletableDeferred
import org.aralozkaya.msngr.pages.components.AddContactDialog
import org.aralozkaya.msngr.pages.components.ContactsActionMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class AddContactDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"

    @Test fun fabOpensFormAndInvalidOrSelfAddressCannotSubmit() {
        val visible = mutableStateOf(false)
        val bounds = mutableStateOf<Rect?>(null)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ContactsActionMenu(Modifier.align(Alignment.BottomEnd),
                        onAddContact = { visible.value = true }, contactInvitationVisible = visible.value,
                        onAddContactBoundsChanged = { bounds.value = it })
                }
                if (visible.value) AddContactDialog(owner, emptyList(), { _, _ -> calls++; Result.success(Unit) },
                    { visible.value = false }, { visible.value = false }, sourceBounds = bounds.value)
            }
        }
        compose.onNodeWithContentDescription("Open contact actions").performClick()
        compose.onNodeWithText("Add a contact").performClick()
        val modal = compose.onNodeWithTag("add-contact-modal").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertNotNull(bounds.value)
            assertEquals(compose.activity.window.decorView.width.toFloat(), modal.width, 1f)
            assertEquals(compose.activity.window.decorView.height.toFloat(), modal.height, 1f)
        }
        saveInvitationScreenshot("contact-invitation.png")
        compose.onNodeWithTag("add-contact-submit").assertIsNotEnabled()
        compose.onNodeWithTag("add-contact-email").performTextInput("invalid")
        compose.onNodeWithTag("add-contact-submit").assertIsNotEnabled()
        compose.onNodeWithTag("add-contact-email").performImeAction()
        compose.onNodeWithTag("add-contact-error").assertDoesNotExist()
        compose.onNodeWithTag("add-contact-email").performTextReplacement(peer)
        compose.onNodeWithTag("add-contact-submit").assertIsEnabled()
        compose.onNodeWithTag("add-contact-email").performTextClearance()
        compose.onNodeWithTag("add-contact-submit").assertIsNotEnabled()
        compose.onNodeWithTag("add-contact-email").performTextInput(owner)
        compose.onNodeWithTag("add-contact-submit").assertIsEnabled()
        compose.onNodeWithTag("add-contact-submit").performClick()
        compose.onNodeWithText("You cannot add yourself as a contact.").assertIsDisplayed()
        compose.onNodeWithTag("action-error-snackbar").assertDoesNotExist()
        assertEquals(0, calls)
        compose.onNodeWithTag("add-contact-dismiss").performClick()
        compose.onNodeWithTag("add-contact-email").assertDoesNotExist()
    }

    @Test fun invitationDialogReturnsToItsLaunchActionOnCloseAndBack() {
        val visible = mutableStateOf(false)
        val bounds = mutableStateOf<Rect?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ContactsActionMenu(Modifier.align(Alignment.BottomEnd),
                        onAddContact = { visible.value = true }, contactInvitationVisible = visible.value,
                        onAddContactBoundsChanged = { bounds.value = it })
                }
                if (visible.value) AddContactDialog(owner, emptyList(),
                    onAddContact = { _, _ -> error("Closing must not send an invitation") },
                    onAdded = {}, onDismiss = { visible.value = false }, sourceBounds = bounds.value)
            }
        }
        compose.onNodeWithContentDescription("Open contact actions").performClick()
        compose.onNodeWithText("Add a contact").performClick()
        compose.onNodeWithTag("add-contact-dismiss").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("add-contact-dismiss").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("add-contact-dismiss").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("add-contact-modal").assertExists()
        saveInvitationScreenshot("contact-invitation-return.png")
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("add-contact-modal").assertDoesNotExist()
        compose.onNodeWithText("Add a contact").assertIsDisplayed().performClick()
        pressBack()
        compose.onNodeWithTag("add-contact-modal").assertDoesNotExist()
        compose.onNodeWithText("Add a contact").assertIsDisplayed()
    }

    @Test fun inFlightSubmissionIsDisabledAndFailureKeepsAddressForRetry() {
        val response = CompletableDeferred<Result<Unit>>()
        var calls = 0
        var added = false
        var dismissed = false
        compose.setContent {
            MaterialTheme { AddContactDialog(owner, emptyList(), { address, message ->
                assertEquals(peer, address)
                assertEquals("Contact hello & ğüşi", message)
                calls++
                if (calls == 1) response.await() else Result.success(Unit)
            }, { added = true }, { dismissed = true }, initialEmail = peer) }
        }
        compose.onNodeWithTag("add-contact-email").assertTextContains(peer)
        compose.onNodeWithTag("add-contact-message").performTextInput("Contact hello & ğüşi")
        val modalBefore = compose.onNodeWithTag("add-contact-modal").fetchSemanticsNode().boundsInRoot
        val actionBefore = compose.onNodeWithTag("add-contact-submit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("add-contact-submit").performClick()
        compose.waitUntil(5000) { calls == 1 }
        compose.onNodeWithTag("add-contact-submit").assertIsEnabled()
        compose.onNodeWithTag("add-contact-dismiss").assertIsNotEnabled()
        compose.onNodeWithTag("add-contact-email").assertIsNotEnabled()
        compose.onNodeWithTag("add-contact-message").assertIsNotEnabled()
        compose.onNodeWithTag("add-contact-loading-overlay").assertDoesNotExist()
        val modal = compose.onNodeWithTag("add-contact-modal").fetchSemanticsNode().boundsInRoot
        val spinner = compose.onNodeWithTag("add-contact-spinner", useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(modalBefore.width, modal.width, 1f)
        assertEquals(modalBefore.height, modal.height, 1f)
        assertEquals(actionBefore.center.x, spinner.center.x, 1f)
        assertEquals(actionBefore.center.y, spinner.center.y, 1f)
        compose.onNodeWithText("Adding contact…").assertDoesNotExist()
        File(compose.activity.filesDir, "contact-add-loading-action.png").outputStream().use {
            compose.onNodeWithTag("add-contact-modal").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertFalse(dismissed)
        pressBack()
        compose.onNodeWithTag("add-contact-spinner", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed) }
        response.complete(Result.failure(IllegalStateException("The server could not add this contact.")))
        compose.onNodeWithText("The server could not add this contact.").assertIsDisplayed()
        compose.onNodeWithTag("action-error-snackbar").assertIsDisplayed()
        compose.onNodeWithTag("add-contact-error").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("The server could not add this contact.").assertDoesNotExist()
        compose.onNodeWithTag("add-contact-spinner", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("add-contact-email").assertTextContains(peer)
        compose.onNodeWithTag("add-contact-message").assertTextContains("Contact hello & ğüşi")
        compose.onNodeWithTag("add-contact-submit").performClick()
        compose.waitUntil(5000) { added }
        assertEquals(2, calls)
    }

    private fun saveInvitationScreenshot(name: String) {
        File(compose.activity.filesDir, name).outputStream().use {
            compose.onNodeWithTag("add-contact-modal").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
