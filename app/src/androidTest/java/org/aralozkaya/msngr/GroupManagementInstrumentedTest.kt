@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.CompletableDeferred
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.GroupDetailsPage
import org.aralozkaya.msngr.pages.RequestsPage
import org.aralozkaya.msngr.pages.components.ContactsActionMenu
import org.aralozkaya.msngr.pages.components.GroupEditorDialog
import org.aralozkaya.msngr.pages.components.GroupDetailsMenu
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class GroupManagementInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val id = "00000000-0000-0000-0009-123456789abc@live.com"

    @Test fun existingFabCreatesAnEmptyGroup() {
        val show = mutableStateOf(false)
        val bounds = mutableStateOf<Rect?>(null)
        var created = ""
        var finished = ""
        var invites = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ContactsActionMenu(Modifier.align(Alignment.BottomEnd),
                        onCreateGroup = { show.value = true }, groupCreationVisible = show.value,
                        onCreateGroupBoundsChanged = { bounds.value = it })
                }
                if (show.value) GroupEditorDialog(owner, emptyList(), onCreate = { created = it; Result.success(id) },
                    sourceBounds = bounds.value,
                    onInvite = { _, _, _ -> invites++; Result.success(Unit) }, onFinished = { groupId, invitationsSent ->
                        assertFalse(invitationsSent)
                        finished = groupId; show.value = false
                    },
                    onDismiss = { show.value = false })
            }
        }
        compose.onNodeWithContentDescription("Open contact actions").performClick()
        compose.onNodeWithText("Create a group").performClick()
        val modal = compose.onNodeWithTag("group-editor-modal").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertNotNull(bounds.value)
            assertEquals(compose.activity.window.decorView.width.toFloat(), modal.width, 1f)
            assertEquals(compose.activity.window.decorView.height.toFloat(), modal.height, 1f)
        }
        saveCreationScreenshot("group-creation.png")
        compose.onNodeWithTag("group-name").performTextInput("New empty group")
        compose.onNodeWithTag("group-editor-submit").performClick()
        compose.waitUntil(5000) { finished == id }
        assertEquals("New empty group", created)
        assertEquals(0, invites)
    }

    @Test fun creationDialogReturnsToItsLaunchActionOnCloseAndBack() {
        val show = mutableStateOf(false)
        val bounds = mutableStateOf<Rect?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ContactsActionMenu(Modifier.align(Alignment.BottomEnd),
                        onCreateGroup = { show.value = true }, groupCreationVisible = show.value,
                        onCreateGroupBoundsChanged = { bounds.value = it })
                }
                if (show.value) GroupEditorDialog(owner, emptyList(), sourceBounds = bounds.value,
                    onCreate = { error("Closing must not create a group") },
                    onInvite = { _, _, _ -> error("Closing must not send invitations") },
                    onFinished = { _, _ -> }, onDismiss = { show.value = false })
            }
        }
        compose.onNodeWithContentDescription("Open contact actions").performClick()
        compose.onNodeWithText("Create a group").performClick()
        compose.onNodeWithTag("group-editor-dismiss").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("group-editor-dismiss").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("group-editor-modal").assertExists()
        compose.onNodeWithTag("group-editor-dismiss").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("group-editor-modal").assertExists()
        saveCreationScreenshot("group-creation-return.png")
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("group-editor-modal").assertDoesNotExist()
        compose.onNodeWithText("Create a group").assertIsDisplayed().performClick()
        pressBack()
        compose.onNodeWithTag("group-editor-modal").assertDoesNotExist()
        compose.onNodeWithText("Create a group").assertIsDisplayed()
    }

    private fun saveCreationScreenshot(name: String) {
        File(compose.activity.filesDir, name).outputStream().use {
            compose.onNodeWithTag("group-editor-modal").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun memberInvitationOpensFromGroupOptionsAndClosesOnCloseBackAndSuccess() {
        val show = mutableStateOf(false)
        val group = Contact(id, "Test group", id, Presence.Offline, isGroup = true,
            members = listOf(GroupMember(owner, role = GroupMemberRole.Owner)))
        var finished = false
        var invites = 0
        compose.setContent {
            MaterialTheme {
                SecondaryPageScaffold("Group details", {}, actions = {
                    GroupDetailsMenu(group, owner,
                        onInviteMembers = { show.value = true },
                        onLeaveGroup = { error("Owners cannot leave their group") })
                }) { padding, scroll ->
                    GroupDetailsPage(group, emptyList(), padding, scroll, {}, {}, owner, {})
                }
                if (show.value) GroupEditorDialog(owner, emptyList(), group = group,
                    onCreate = { error("Inviting members must reuse the existing group") },
                    onInvite = { target, emails, message ->
                        assertEquals(id, target)
                        assertEquals(listOf(peer), emails)
                        assertEquals("Join us", message)
                        invites++
                        Result.success(Unit)
                    }, onFinished = { _, invitationsSent ->
                        assertTrue(invitationsSent)
                        finished = true; show.value = false
                    },
                    onDismiss = { show.value = false })
            }
        }
        fun openInvitation() {
            compose.onNodeWithContentDescription("Group options").performClick()
            compose.onNodeWithTag("group-invite").assertIsDisplayed().performClick()
            compose.onNodeWithTag("group-editor-modal").assertIsDisplayed()
            compose.onNodeWithTag("group-invite").assertDoesNotExist()
        }
        openInvitation()
        val modal = compose.onNodeWithTag("group-editor-modal").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertEquals(compose.activity.window.decorView.width.toFloat(), modal.width, 1f)
            assertEquals(compose.activity.window.decorView.height.toFloat(), modal.height, 1f)
        }
        compose.onNodeWithText("Invite people to \"Test group\"").assertIsDisplayed()
        compose.onNodeWithTag("group-name").assertDoesNotExist()
        compose.onNodeWithTag("group-editor-submit").assertTextEquals("Send").assertIsNotEnabled()
        saveCreationScreenshot("group-member-invitation.png")
        compose.onNodeWithTag("group-editor-dismiss").performClick()
        compose.onNodeWithTag("group-editor-modal").assertDoesNotExist()
        compose.onNodeWithTag("group-invite").assertDoesNotExist()
        openInvitation()
        pressBack()
        compose.onNodeWithTag("group-editor-modal").assertDoesNotExist()
        compose.onNodeWithTag("group-invite").assertDoesNotExist()
        openInvitation()
        compose.onNodeWithTag("group-invite-emails").performTextInput(peer)
        compose.onNodeWithTag("group-invitation-message").performTextInput("Join us")
        compose.onNodeWithTag("group-editor-submit").assertIsEnabled().performClick()
        compose.waitUntil(5000) { finished }
        compose.onNodeWithTag("group-editor-modal").assertDoesNotExist()
        compose.onNodeWithTag("group-invite").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, invites) }
    }

    @Test fun choosingInitialMemberRetriesInvitationWithoutCreatingASecondGroup() {
        val createResponse = CompletableDeferred<Result<String>>()
        val inviteResponse = CompletableDeferred<Result<Unit>>()
        var creates = 0
        var invites = 0
        var finished = false
        val selected = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { GroupEditorDialog(owner, listOf(Contact(peer, "peer", peer, Presence.Offline)),
                onCreate = { creates++; createResponse.await() },
                onInvite = { group, addresses, message ->
                    assertEquals("Initial invitation & hello", message)
                    assertEquals(id, group); selected += addresses; invites++
                    if (invites == 1) inviteResponse.await() else Result.success(Unit)
                }, onFinished = { _, invitationsSent -> assertTrue(invitationsSent); finished = true }, onDismiss = {}) }
        }
        compose.onNodeWithTag("group-name").performTextInput("Group with member")
        compose.onNodeWithTag("group-select-$peer").assertDoesNotExist()
        compose.onNodeWithTag("group-invitation-message").performScrollTo().performTextInput("Initial invitation & hello")
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("group-select-$peer").performClick()
        compose.onNodeWithTag("group-contact-picker-done").performClick()
        compose.onNodeWithTag("group-selected-$peer").performScrollTo().assertIsDisplayed()
        val actionBefore = compose.onNodeWithTag("group-editor-submit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("group-editor-submit").performClick()
        compose.waitUntil(5000) { creates == 1 }
        assertBusyIndicator(actionBefore)
        compose.onNodeWithTag("group-name").assertIsNotEnabled()
        createResponse.complete(Result.success(id))
        compose.waitUntil(5000) { invites == 1 }
        assertBusyIndicator(actionBefore)
        inviteResponse.complete(Result.failure(IllegalStateException("Offline retry test")))
        compose.onNodeWithTag("action-error-snackbar").assertIsDisplayed()
            .assert(hasAnyDescendant(hasText("Your group was created.", substring = true)))
        compose.onNodeWithTag("group-editor-error").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithTag("group-editor-loading-overlay").assertDoesNotExist()
        compose.onNodeWithTag("group-editor-submit").assertTextEquals("Send").assertIsEnabled()
        compose.onNodeWithTag("group-invitation-message").performScrollTo().assertTextContains("Initial invitation & hello")
        compose.onNodeWithTag("group-name").assertIsNotEnabled()
        compose.onNodeWithTag("group-editor-submit").performClick()
        compose.waitUntil(5000) { finished }
        assertEquals(1, creates)
        assertEquals(listOf(peer, peer), selected)
    }

    @Test fun existingGroupInvitationReplacesItsActionAndRestoresFormAfterFailure() {
        val response = CompletableDeferred<Result<Unit>>()
        var calls = 0
        var finished = false
        compose.setContent {
            MaterialTheme {
                GroupEditorDialog(owner, listOf(Contact(peer, "peer", peer, Presence.Offline)),
                    group = Contact(id, "Test group", id, Presence.Offline, isGroup = true,
                        members = listOf(GroupMember(owner))),
                    onCreate = { error("An existing group must be reused") },
                    onInvite = { _, addresses, message ->
                        assertEquals("Later invitation ğüşi", message)
                        assertEquals(listOf(peer), addresses)
                        calls++
                        if (calls == 1) response.await() else Result.success(Unit)
                    }, onFinished = { _, invitationsSent -> assertTrue(invitationsSent); finished = true }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("group-invite-emails").performTextInput(peer)
        compose.onNodeWithTag("group-invite-emails").performImeAction()
        compose.onNodeWithTag("group-invitation-message").performScrollTo().performTextInput("Later invitation ğüşi")
        val actionBefore = compose.onNodeWithTag("group-editor-submit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("group-editor-submit").performClick()
        compose.waitUntil(5000) { calls == 1 }
        assertBusyIndicator(actionBefore)
        pressBack()
        compose.onNodeWithTag("group-editor-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("group-remove-$peer").assertIsNotEnabled()
        compose.onNodeWithTag("group-editor-loading", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, calls) }
        response.complete(Result.failure(IllegalStateException("Invitation failed")))
        compose.onNodeWithTag("action-error-snackbar").assertIsDisplayed()
        compose.onNodeWithText("Invitation failed").assertIsDisplayed()
        compose.onNodeWithTag("group-editor-error").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithTag("group-editor-loading-overlay").assertDoesNotExist()
        compose.onNodeWithTag("group-invitation-message").performScrollTo().assertTextContains("Later invitation ğüşi")
        compose.onNodeWithTag("group-editor-submit").assertTextEquals("Send").assertIsEnabled().performClick()
        compose.waitUntil(5000) { finished }
        assertEquals(2, calls)
    }

    private fun assertBusyIndicator(actionBefore: androidx.compose.ui.geometry.Rect) {
        compose.onNodeWithTag("group-editor-submit").assertIsEnabled()
        compose.onNodeWithTag("group-editor-dismiss").assertIsNotEnabled()
        compose.onNodeWithTag("group-invite-emails").assertIsNotEnabled()
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("group-invitation-message").assertIsNotEnabled()
        compose.onNodeWithTag("group-editor-loading-overlay").assertDoesNotExist()
        val indicator = compose.onNodeWithTag("group-editor-loading", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(actionBefore.center.x, indicator.center.x, 1f)
        assertEquals(actionBefore.center.y, indicator.center.y, 1f)
        compose.onNodeWithText("Creating group…").assertDoesNotExist()
        compose.onNodeWithText("Sending invitations…").assertDoesNotExist()
    }

    @Test fun invitationEditorCombinesManualAndPickedMembersAndKeepsRemovalsInSync() {
        val other = "other@example.com"
        val manual = "manual@example.com"
        val member = "member@example.com"
        val blocked = "blocked@example.com"
        val nonContact = "non-contact@example.com"
        val contacts = listOf(
            Contact(peer, "peer", peer, Presence.Offline),
            Contact(other, "Other", other, Presence.Offline),
            Contact(member, "Member", member, Presence.Offline),
            Contact(owner, "Owner", owner, Presence.Online),
            Contact(blocked, "Blocked", blocked, Presence.Offline, isBlocked = true),
            Contact(nonContact, "Non-contact", nonContact, Presence.Offline, isForwardListContact = false),
            Contact(id, "Test group", id, Presence.Offline, isGroup = true),
        )
        var invited: List<String>? = null
        compose.setContent {
            MaterialTheme {
                GroupEditorDialog(owner, contacts,
                    group = contacts.last().copy(members = listOf(GroupMember(owner), GroupMember(member))),
                    onCreate = { error("An existing group must be reused") },
                    onInvite = { group, addresses, _ -> assertEquals(id, group); invited = addresses; Result.success(Unit) },
                    onFinished = { _, _ -> }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("group-invite-emails").performTextInput(peer.uppercase())
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("group-invite-emails").performTextInput(peer)
        compose.onNodeWithTag("group-invite-emails").performImeAction()
        compose.onAllNodesWithTag("group-selected-$peer").assertCountEquals(1)
        compose.onNodeWithTag("group-invite-emails").performTextInput(manual)
        compose.onNodeWithTag("group-invite-emails").performImeAction()
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("group-select-$peer").assertIsOn()
        listOf(owner, member, blocked, nonContact, id).forEach {
            compose.onNodeWithTag("group-select-$it").assertDoesNotExist()
        }
        compose.onNodeWithTag("group-select-$other").performClick()
        compose.onNodeWithTag("group-contact-picker-done").performClick()
        compose.onNodeWithTag("group-selected-$other").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("group-remove-$peer").performScrollTo().performClick()
        compose.onNodeWithTag("group-selected-$peer").assertDoesNotExist()
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("group-select-$peer").assertIsOff()
        compose.onNodeWithTag("group-select-$other").assertIsOn().performClick()
        compose.onNodeWithTag("group-contact-picker-done").performClick()
        compose.onNodeWithTag("group-selected-$other").assertDoesNotExist()
        compose.onNodeWithTag("group-editor-submit").performClick()
        compose.waitUntil(5000) { invited != null }
        assertEquals(listOf(manual), invited)
    }

    @Test fun invalidManualEmailStaysInTheFieldAndEmptyPickerCanBeDismissed() {
        compose.setContent {
            MaterialTheme { GroupEditorDialog(owner, emptyList(), onCreate = { Result.success(id) },
                onInvite = { _, _, _ -> Result.success(Unit) }, onFinished = { _, _ -> }, onDismiss = {}) }
        }
        compose.onNodeWithTag("group-invite-emails").performTextInput("invalid")
        compose.onNodeWithTag("group-invite-emails").performImeAction()
        compose.onNodeWithTag("group-editor-error").performScrollTo().assertTextContains("Enter a valid", substring = true)
        compose.onNodeWithTag("group-invite-emails").assertTextContains("invalid")
        compose.onNodeWithTag("group-invite-emails").performScrollTo().performTextClearance()
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("No contacts available to invite.").assertIsDisplayed()
        compose.onNodeWithTag("group-contact-picker-done").performClick()
        compose.onNodeWithTag("group-invite-emails").assertTextContains("Email address")
    }

    @Test fun contactPickerScrollsToContactsBeyondTheFirstScreen() {
        val contacts = List(40) { index ->
            val email = "contact${index.toString().padStart(2, '0')}@example.com"
            Contact(email, email, email, Presence.Offline)
        }
        val last = contacts.last().email
        compose.setContent {
            MaterialTheme { GroupEditorDialog(owner, contacts, onCreate = { Result.success(id) },
                onInvite = { _, _, _ -> Result.success(Unit) }, onFinished = { _, _ -> }, onDismiss = {}) }
        }
        compose.onNodeWithTag("group-add-email", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("group-contact-picker").performScrollToNode(hasTestTag("group-select-$last"))
        compose.onNodeWithTag("group-select-$last").performClick()
        compose.onNodeWithTag("group-contact-picker-done").performClick()
        compose.onNodeWithTag("group-selected-$last").performScrollTo().assertIsDisplayed()
    }

    @Test fun groupOptionsFollowChangingPrivilegesAndOmitEmptyGroups() {
        val role = mutableStateOf(GroupMemberRole.Owner)
        val groupAvailable = mutableStateOf(true)
        var leaveCount = 0
        compose.setContent {
            MaterialTheme {
                val group = if (groupAvailable.value) Contact(id, "Test", id, Presence.Offline, isGroup = true,
                    members = listOf(GroupMember(owner, role = role.value))) else null
                SecondaryPageScaffold("Group details", {}, actions = {
                    GroupDetailsMenu(group, owner, onInviteMembers = {}, onLeaveGroup = { leaveCount++ })
                }) { padding, scroll ->
                    GroupDetailsPage(group, emptyList(), padding, scroll, {}, {}, owner, {})
                }
            }
        }
        fun saveMenuScreenshot(name: String) {
            File(compose.activity.filesDir, name).outputStream().use {
                compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("group-invite").assertDoesNotExist()
        compose.onNodeWithTag("group-leave").assertDoesNotExist()
        compose.onNodeWithContentDescription("Group options").performClick()
        compose.onNodeWithTag("group-invite").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("group-invite-menu-group").assertIsDisplayed()
        compose.onNodeWithTag("group-leave-menu-group").assertDoesNotExist()
        saveMenuScreenshot("group-options-owner.png")
        compose.runOnIdle { role.value = GroupMemberRole.Administrator }
        compose.onNodeWithTag("group-invite").assertIsDisplayed()
        compose.onNodeWithTag("group-leave").assertIsDisplayed().assertIsEnabled()
        val inviteGroup = compose.onNodeWithTag("group-invite-menu-group").fetchSemanticsNode().boundsInWindow
        val leaveGroup = compose.onNodeWithTag("group-leave-menu-group").fetchSemanticsNode().boundsInWindow
        assertTrue("Leave group must occupy a separate menu group", leaveGroup.top > inviteGroup.bottom)
        saveMenuScreenshot("group-options-administrator.png")
        compose.runOnIdle { role.value = GroupMemberRole.User }
        compose.onNodeWithTag("group-leave").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("group-invite-menu-group").assertDoesNotExist()
        saveMenuScreenshot("group-options-member.png")
        compose.onNodeWithTag("group-leave").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, leaveCount) }
        compose.onNodeWithContentDescription("Group options").performClick()
        compose.runOnIdle { role.value = GroupMemberRole.Unknown }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Group options").assertDoesNotExist()
        compose.runOnIdle { role.value = GroupMemberRole.Owner }
        compose.onNodeWithContentDescription("Group options").performClick()
        compose.onNodeWithTag("group-invite").assertIsDisplayed()
        compose.runOnIdle { groupAvailable.value = false }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Group options").assertDoesNotExist()
    }

    @Test fun invitationPageJoinsAndDeclinesDistinctGroupIds() {
        val second = "00000000-0000-0000-0009-123456789abd@live.com"
        val invitations = mutableStateOf(listOf(GroupInvitation(id, "Same name", owner), GroupInvitation(second, "Same name", owner)))
        val replies = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MaterialTheme { RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                emptyList(), { _, _, _ -> error("No contact request") }, invitations = invitations.value,
                onRespondToGroup = { group, accept -> replies += group to accept
                    invitations.value = invitations.value.filterNot { it.id == group }; Result.success(Unit) }) }
        }
        compose.onNodeWithTag("accept-group-$id").performScrollTo().performClick()
        compose.onNodeWithTag("group-invitation-$id").assertDoesNotExist()
        compose.onNodeWithTag("decline-group-$second").performScrollTo().performClick()
        compose.onNodeWithText("No pending requests or invitations").assertIsDisplayed()
        assertEquals(listOf(id to true, second to false), replies)
    }
}
