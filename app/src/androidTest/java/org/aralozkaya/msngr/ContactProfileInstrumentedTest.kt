package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContactProfileInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val contact = Contact(
        id = "friend@example.com",
        nickname = "Friendly contact",
        email = "friend@example.com",
        presence = Presence.Away,
        statusMessage = "Back soon",
    )

    @Test
    fun profileShowsContactDetailsAndHandlesMissingPicture() {
        showProfile(contact)
        compose.onNodeWithText("Friendly contact").assertExists()
        compose.onNodeWithText("Away").assertExists()
        compose.onNodeWithText("friend@example.com").performScrollTo().assertExists()
        compose.onNodeWithText("Back soon").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("View display picture").assertIsNotEnabled()
    }

    @Test
    fun tappingPictureOpensDownloadableViewerAndCloseReturnsToProfile() {
        showProfile(contact.copy(avatarUri = imageUri()))
        compose.onNodeWithContentDescription("View display picture").performClick()
        compose.onNodeWithText("Display picture").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Download picture").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithContentDescription("Close picture").performClick()
        compose.onNodeWithText("Download picture").assertDoesNotExist()
        compose.onNodeWithText("Friendly contact").assertExists()
    }

    @Test
    fun avatarTapOpensProfileAndNameTapOpensChat() {
        var profilesOpened = 0
        var chatsOpened = 0
        compose.setContent {
            MsngrTheme {
                ContactRow(
                    index = 0,
                    count = 1,
                    contact = contact,
                    onClick = { chatsOpened++ },
                    onOpenProfile = { profilesOpened++ },
                    onReadStateChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription("View Friendly contact's profile")
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, profilesOpened)
            assertEquals(0, chatsOpened)
        }
        compose.onNodeWithText("Friendly contact").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, profilesOpened)
            assertEquals(1, chatsOpened)
        }
    }

    @Test
    fun contactMenuOmitsProfileAndKeepsReadAction() {
        var profilesOpened = 0
        var chatsOpened = 0
        var markedUnread = false
        compose.setContent {
            MsngrTheme {
                ContactRow(
                    index = 0,
                    count = 1,
                    contact = contact,
                    onClick = { chatsOpened++ },
                    onOpenProfile = { profilesOpened++ },
                    onReadStateChange = { markedUnread = it },
                )
            }
        }
        compose.onNodeWithText("Friendly contact").performTouchInput { longClick() }
        compose.onNodeWithText("View profile").assertDoesNotExist()
        compose.onNodeWithText("Mark as unread").performClick()
        compose.runOnIdle {
            assertTrue(markedUnread)
            assertEquals(0, profilesOpened)
            assertEquals(0, chatsOpened)
        }
    }

    @Test
    fun downloadWritesReadablePngAndRejectsUnavailableImage() = runBlocking {
        val output = File.createTempFile("profile-export", ".png", compose.activity.cacheDir)
        try {
            val original = imageUri(size = 1024)
            val thumbnail = requireNotNull(compose.activity.loadAvatarBitmap(original))
            assertTrue(thumbnail.width < 1024)
            assertTrue(compose.activity.saveContactPicture(original, Uri.fromFile(output)))
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            assertEquals(1024, decoded.width)
            assertEquals(1024, decoded.height)
            decoded.recycle()
            assertFalse(compose.activity.saveContactPicture("data:image/png;base64,invalid", Uri.fromFile(output)))
        } finally {
            output.delete()
        }
    }

    @Test
    fun eraseHistoryRequiresConfirmationAndTargetsTheDisplayedContact() {
        val erasedContacts = mutableListOf<Contact>()
        showProfile(contact, onEraseHistory = { erasedContacts += it })
        compose.onNodeWithText("Erase history").performScrollTo().performClick()
        compose.onNodeWithText("Erase chat history?").assertExists()
        compose.runOnIdle { assertTrue(erasedContacts.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Erase chat history?").assertDoesNotExist()
        compose.runOnIdle { assertTrue(erasedContacts.isEmpty()) }
        compose.onNodeWithText("Erase history").performClick()
        compose.onNodeWithText("Erase").performClick()
        compose.onNodeWithText("Erase chat history?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(contact), erasedContacts) }
    }

    @Test
    fun erasingHistoryReplacesItsActionAndBlocksDismissal() {
        val response = CompletableDeferred<Unit>()
        var calls = 0
        showProfile(contact, onEraseHistory = { calls++; response.await() })
        compose.onNodeWithText("Erase history").performScrollTo().performClick()
        compose.onNodeWithText("Erase").performClick()
        compose.waitUntil(5000) { calls == 1 }
        compose.onNodeWithTag("chat-history-erase-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Erase").assertDoesNotExist()
        compose.onNodeWithText("Erasing chat history…").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithTag("chat-history-erase-loading-overlay").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("chat-history-erase-loading", useUnmergedTree = true).assertIsDisplayed()
        response.complete(Unit)
        compose.onNodeWithTag("chat-history-erase-loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Erase chat history?").assertDoesNotExist()
    }

    @Test
    fun eraseFailureKeepsConfirmationOpenForRetry() {
        showProfile(contact, onEraseHistory = { throw java.io.IOException("Cannot delete") })
        compose.onNodeWithText("Erase history").performScrollTo().performClick()
        compose.onNodeWithText("Erase").performClick()
        compose.onNodeWithText("Could not erase history. Please try again.").assertExists()
        compose.onNodeWithText("Erase").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Erase chat history?").assertDoesNotExist()
    }

    private fun showProfile(value: Contact, onEraseHistory: suspend (Contact) -> Unit = {}) {
        compose.setContent {
            MsngrTheme {
                ContactProfilePage(
                    contact = value,
                    innerPadding = PaddingValues(),
                    scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    onBlockContact = {},
                    onEraseHistory = onEraseHistory,
                )
            }
        }
    }

    private fun imageUri(size: Int = 4): String {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }
}
