package org.aralozkaya.msngr

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.CompletableDeferred
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.BlockedContactsPage
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.DeleteContactDialog
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DeleteContactDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val contact = Contact("peer@example.test", "Listener", "peer@example.test", Presence.Online)

    @Test fun blockedContactsCanBeDeletedOnceAndKeepTheirExistingBlock() {
        val current = mutableStateOf(contact.copy(isBlocked = true))
        val visible = mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        compose.setContent {
            MsngrTheme {
                BlockedContactsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    listOf(current.value), onUnblockContact = {}, onDeleteContact = { visible.value = true })
                if (visible.value) DeleteContactDialog(current.value, { calls += it; Result.success(Unit) },
                    { visible.value = false }, { visible.value = false })
            }
        }
        compose.onNodeWithTag("contact-row-${contact.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Delete contact").performClick()
        compose.onNodeWithTag("delete-contact-block").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("This contact will stay blocked").assertIsDisplayed()
        compose.onNodeWithTag("delete-contact-submit").performClick()
        compose.onNodeWithTag("delete-contact-modal").assertDoesNotExist()
        assertEquals(listOf(false), calls)
        compose.runOnIdle { current.value = current.value.copy(isForwardListContact = false) }
        compose.onNodeWithTag("contact-row-${contact.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Unblock").assertIsDisplayed()
        compose.onNodeWithText("Delete contact").assertDoesNotExist()
    }

    @Test fun longPressRequiresConfirmationAndCancelDoesNotDelete() {
        val visible = mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        compose.setContent {
            MsngrTheme {
                ContactRow(0, 1, contact, onClick = {}, onDeleteContact = { visible.value = true })
                if (visible.value) DeleteContactDialog(contact, { calls += it; Result.success(Unit) },
                    { visible.value = false }, { visible.value = false })
            }
        }
        compose.onNodeWithText("Listener").performTouchInput { longClick() }
        compose.onNodeWithText("Delete contact").performClick()
        compose.onNodeWithText("Delete contact?").assertIsDisplayed()
        compose.onNodeWithTag("delete-contact-block").assertIsOff()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("delete-contact-modal").assertDoesNotExist()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Listener").performTouchInput { longClick() }
        compose.onNodeWithText("Delete contact").performClick()
        compose.onNodeWithTag("delete-contact-submit").performClick()
        compose.onNodeWithTag("delete-contact-modal").assertDoesNotExist()
        assertEquals(listOf(false), calls)
    }

    @Test fun profileDeleteCanBlockAndKeepsHistoryActionSeparate() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var erased = false
        var closed = false
        compose.setContent {
            MsngrTheme {
                ContactProfilePage(contact, PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    onBlockContact = {}, onEraseHistory = { erased = true },
                    onDeleteContact = { target, block -> calls += target.id to block; Result.success(Unit) },
                    onContactDeleted = { closed = true })
            }
        }
        compose.onNodeWithText("Delete contact").performScrollTo().performClick()
        compose.onNodeWithTag("delete-contact-block").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("delete-contact-submit").performClick()
        compose.onNodeWithTag("delete-contact-modal").assertDoesNotExist()
        assertEquals(listOf(contact.id to true), calls)
        assertTrue(closed)
        assertFalse(erased)
    }

    @Test fun pendingDeleteKeepsButtonColorsAndFailureRetainsCheckedOptionForRetry() {
        val response = CompletableDeferred<Result<Unit>>()
        val calls = mutableListOf<Boolean>()
        var deleted = false
        var contentColor = Color.Unspecified
        var containerColor = Color.Unspecified
        compose.setContent {
            MsngrTheme {
                contentColor = MaterialTheme.colorScheme.onError
                containerColor = MaterialTheme.colorScheme.error
                DeleteContactDialog(contact, {
                    calls += it
                    if (calls.size == 1) response.await() else Result.success(Unit)
                }, { deleted = true }, {})
            }
        }
        compose.onNodeWithTag("delete-contact-block").performClick()
        val before = compose.onNodeWithTag("delete-contact-modal").fetchSemanticsNode().boundsInRoot
        val actionBefore = compose.onNodeWithTag("delete-contact-submit").fetchSemanticsNode().boundsInRoot
        val idleButton = compose.onNodeWithTag("delete-contact-submit").captureToImage().asAndroidBitmap()
        compose.onNodeWithTag("delete-contact-submit").performClick()
        compose.onNodeWithTag("delete-contact-submit").assertIsEnabled()
        compose.mainClock.advanceTimeBy(1000)
        val loadingButton = compose.onNodeWithTag("delete-contact-submit").captureToImage().asAndroidBitmap()
        assertEquals(idleButton.width, loadingButton.width)
        assertEquals(idleButton.height, loadingButton.height)
        assertEquals(containerColor.toArgb(), idleButton.getPixel(idleButton.width / 8, idleButton.height / 2))
        assertEquals(containerColor.toArgb(), loadingButton.getPixel(loadingButton.width / 8, loadingButton.height / 2))
        val pixels = IntArray(loadingButton.width * loadingButton.height)
        loadingButton.getPixels(pixels, 0, loadingButton.width, 0, 0, loadingButton.width, loadingButton.height)
        assertTrue("Spinner must inherit the destructive button content color", pixels.any { it == contentColor.toArgb() })
        compose.onNodeWithTag("delete-contact-submit").performClick()
        compose.runOnIdle { assertEquals(1, calls.size) }
        compose.onNodeWithTag("delete-contact-block").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        val modal = compose.onNodeWithTag("delete-contact-modal").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("delete-contact-loading-overlay").assertDoesNotExist()
        val spinner = compose.onNodeWithTag("delete-contact-spinner", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, modal.width, 1f)
        assertEquals(before.height, modal.height, 1f)
        assertEquals(actionBefore.center.x, spinner.center.x, 1f)
        assertEquals(actionBefore.center.y, spinner.center.y, 1f)
        compose.onNodeWithText("Deleting contact…").assertDoesNotExist()
        File(compose.activity.filesDir, "contact-delete-loading-action.png").outputStream().use {
            compose.onNodeWithTag("delete-contact-modal").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        response.complete(Result.failure(IllegalStateException("The server could not delete this contact.")))
        compose.onNodeWithText("The server could not delete this contact.").assertIsDisplayed()
        compose.onNodeWithTag("delete-contact-block").assertIsOn()
        compose.onNodeWithTag("delete-contact-spinner", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("delete-contact-submit").performClick()
        compose.waitUntil(5000) { deleted }
        assertEquals(listOf(true, true), calls)
    }

    @Test fun groupAndNonForwardProfilesDoNotOfferDeletion() {
        val current = mutableStateOf(contact.copy(isGroup = true))
        compose.setContent {
            MsngrTheme {
                ContactProfilePage(current.value, PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    onBlockContact = {}, onEraseHistory = {}, onDeleteContact = { _, _ -> error("Must not run") })
            }
        }
        compose.onNodeWithText("Delete contact").assertDoesNotExist()
        compose.runOnIdle { current.value = contact.copy(isForwardListContact = false) }
        compose.onNodeWithText("Delete contact").assertDoesNotExist()
    }
}
