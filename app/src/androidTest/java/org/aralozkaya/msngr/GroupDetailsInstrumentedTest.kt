package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.GroupDetailsPage
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class GroupDetailsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val alice = Contact("alice@example.com", "[b]Alice[/b]", "alice@example.com", Presence.Online,
        statusMessage = "Back soon")
    private val bob = Contact("bob@other.example", "Bob", "bob@other.example", Presence.Offline)
    private val group = Contact("group@live.com", "Test", "group@live.com", Presence.Online, isGroup = true)

    @Test fun memberNameRendersOriginalEmoticons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = EmoticonPrefs.state(context).value.useOriginalEmoticons
        EmoticonPrefs.setUseOriginalEmoticons(context, true)
        try {
            compose.setContent {
                MsngrTheme {
                    GroupDetailsTestPage(group, listOf(alice.copy(nickname = "[b]Alice :)[/b]")))
                }
            }
            compose.onNodeWithContentDescription("Smile", useUnmergedTree = true).assertIsDisplayed()
        } finally { EmoticonPrefs.setUseOriginalEmoticons(context, previous) }
    }

    @Test fun searchMatchesVisibleNamesAndEmailsAndCanBeCleared() {
        compose.setContent {
            MsngrTheme {
                GroupDetailsTestPage(group, listOf(alice, bob))
            }
        }
        compose.onNodeWithContentDescription("Search members").performTextInput(" ALICE ")
        compose.onNodeWithText("alice@example.com").assertIsDisplayed()
        compose.onNodeWithText("Back soon").assertDoesNotExist()
        compose.onNodeWithText("Bob").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search members").performTextReplacement("OTHER.EXAMPLE")
        compose.onNodeWithText("Bob").assertIsDisplayed()
        compose.onNodeWithText("alice@example.com").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search members").performTextReplacement("missing")
        compose.onNodeWithText("No members found.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("alice@example.com").assertIsDisplayed()
        compose.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test fun memberPictureOpensProfileAndRowOpensOnlyPrivateChat() {
        val profileTargets = mutableListOf<String>()
        val chatTargets = mutableListOf<String>()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val image = try {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } finally { bitmap.recycle() }
        compose.setContent {
            MsngrTheme {
                GroupDetailsTestPage(group, listOf(alice.copy(avatarUri = image), bob),
                    onOpenChat = { chatTargets += it.id }, onOpenProfile = { profileTargets += it.id })
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Alice display picture", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("View Alice's profile").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(alice.id), profileTargets)
            assertTrue(chatTargets.isEmpty())
        }
        compose.onNodeWithText("alice@example.com").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(alice.id), profileTargets)
            assertEquals(listOf(alice.id), chatTargets)
        }
    }

    @Test fun detailsUseAPageToolbarWithBackNavigation() {
        var backPressed = false
        compose.setContent {
            MsngrTheme {
                GroupDetailsTestPage(group, listOf(alice, bob), onBack = { backPressed = true })
            }
        }
        compose.onNodeWithText("Group details").assertIsDisplayed()
        compose.onNodeWithText("2 members").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close sheet").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertTrue(backPressed) }
    }

    @Test fun ownMemberCannotOpenChatAndPictureOpensEditProfile() {
        val chatTargets = mutableListOf<String>()
        val profileTargets = mutableListOf<String>()
        var editProfileCount = 0
        compose.setContent {
            MsngrTheme {
                GroupDetailsTestPage(
                    group, listOf(alice, bob), ownEmail = "ALICE@EXAMPLE.COM",
                    onOpenChat = { chatTargets += it.id },
                    onOpenProfile = { profileTargets += it.id },
                    onEditProfile = { editProfileCount++ },
                )
            }
        }
        compose.onNodeWithTag("contact-row-${alice.id}", useUnmergedTree = true).assertHasNoClickAction()
        compose.onAllNodesWithText("You").assertCountEquals(1)
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithTag("contact-row-${alice.id}", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("You")))
        compose.onNodeWithText(alice.email).performTouchInput { click() }
        compose.runOnIdle {
            assertTrue(chatTargets.isEmpty())
            assertTrue(profileTargets.isEmpty())
            assertEquals(0, editProfileCount)
        }
        compose.onNodeWithContentDescription("Edit profile").performClick()
        compose.runOnIdle {
            assertEquals(1, editProfileCount)
            assertTrue(chatTargets.isEmpty())
            assertTrue(profileTargets.isEmpty())
        }
        compose.onNodeWithText(bob.email).performTouchInput { click() }
        compose.onNodeWithContentDescription("View Bob's profile").performClick()
        compose.runOnIdle {
            assertEquals(listOf(bob.id), chatTargets)
            assertEquals(listOf(bob.id), profileTargets)
            assertEquals(1, editProfileCount)
        }
    }

    @Test fun memberAccessLevelsRemainVisibleAndUpdateWithTheGroup() {
        val casey = Contact("casey@example.com", "Casey", "casey@example.com", Presence.Away)
        val currentGroup = mutableStateOf(group.copy(members = listOf(
            GroupMember(alice.email, role = GroupMemberRole.Owner),
            GroupMember(bob.email, role = GroupMemberRole.Administrator),
            GroupMember(casey.email, role = GroupMemberRole.User),
        )))
        compose.setContent {
            MsngrTheme { GroupDetailsTestPage(currentGroup.value, listOf(alice, bob, casey)) }
        }
        compose.onNodeWithTag("contact-row-${alice.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Owner")))
        compose.onNodeWithTag("contact-row-${bob.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Administrator")))
        compose.onNodeWithTag("contact-row-${casey.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("User")))
        compose.onNodeWithContentDescription("Search members").performTextReplacement("Alice")
        compose.onNodeWithText("Owner").assertIsDisplayed()
        compose.onNodeWithText(alice.email).assertIsDisplayed()
        compose.runOnIdle {
            currentGroup.value = currentGroup.value.copy(members = currentGroup.value.members.map {
                if (it.email == alice.email) it.copy(role = GroupMemberRole.User) else it
            })
        }
        compose.onNodeWithText("Owner").assertDoesNotExist()
        compose.onNodeWithText("User").assertIsDisplayed()
    }

    @Test fun removedGroupShowsUnavailableInsteadOfStaleMembers() {
        compose.setContent {
            MsngrTheme { GroupDetailsTestPage(null, listOf(alice)) }
        }
        compose.onNodeWithText("Group is no longer available.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search members").assertDoesNotExist()
        compose.onNodeWithText("alice@example.com").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Composable
    private fun GroupDetailsTestPage(
        group: Contact?,
        members: List<Contact>,
        onOpenChat: (Contact) -> Unit = {},
        onOpenProfile: (Contact) -> Unit = {},
        onBack: () -> Unit = {},
        ownEmail: String = "me@example.com",
        onEditProfile: () -> Unit = {},
    ) {
        SecondaryPageScaffold(title = "Group details", onBack = onBack) { padding, scroll ->
            GroupDetailsPage(group, members, padding, scroll, onOpenChat, onOpenProfile, ownEmail, onEditProfile)
        }
    }
}
