package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.CompletableDeferred
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.GroupBlockingDialog
import org.aralozkaya.msngr.pages.components.GroupDetailsMenu
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GroupBlockingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = Contact("00000000-0000-0000-0009-123456789abc@live.com", "Test group",
        "00000000-0000-0000-0009-123456789abc@live.com", Presence.Offline, isGroup = true)

    @Test fun blockingIsAvailableForEveryRoleAndChangesToUnblock() {
        val role = mutableStateOf(GroupMemberRole.Owner)
        val blocked = mutableStateOf(false)
        val available = mutableStateOf(true)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                val current = if (available.value) group.copy(isBlocked = blocked.value,
                    members = listOf(GroupMember("me@example.test", role = role.value))) else null
                SecondaryPageScaffold("Group details", {}, actions = {
                    GroupDetailsMenu(current, "me@example.test", {}, {}, onChangeBlocking = { calls++ })
                }) { _, _ -> }
            }
        }
        for (value in GroupMemberRole.entries) {
            compose.runOnIdle { role.value = value }
            compose.onNodeWithContentDescription("Group options").performClick()
            compose.onNodeWithText("Block group").assertIsDisplayed().performClick()
        }
        assertEquals(GroupMemberRole.entries.size, calls)
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithContentDescription("Group options").performClick()
        compose.onNodeWithText("Unblock group").assertIsDisplayed().performClick()
        compose.runOnIdle { available.value = false }
        compose.onNodeWithContentDescription("Group options").assertDoesNotExist()
    }

    @Test fun blockWaitsForConfirmationPreventsDuplicatesAndKeepsFailureRetryable() {
        val response = CompletableDeferred<Result<Unit>>()
        var calls = 0
        var changed = false
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                GroupBlockingDialog(group, true,
                    onSetBlocked = { calls++; if (calls == 1) response.await() else Result.success(Unit) },
                    onChanged = { changed = true }, onDismiss = { dismissed = true })
            }
        }
        assertEquals(0, calls)
        compose.onNodeWithTag("group-block-submit").performClick()
        compose.onNodeWithTag("group-block-spinner", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithTag("group-block-submit").performClick()
        pressBack()
        compose.runOnIdle { assertEquals(1, calls); assertFalse(dismissed); assertFalse(changed) }
        response.complete(Result.failure(IllegalStateException("Service unavailable")))
        compose.onNodeWithTag("group-block-error").assertIsDisplayed()
        compose.onNodeWithTag("group-block-submit").performClick()
        compose.waitUntil(5000) { changed }
        assertEquals(2, calls)
    }

    @Test fun cancelDoesNotSubmitOrChangeGroupMembership() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                GroupBlockingDialog(group, false, onSetBlocked = { fail("Cancel must not unblock"); Result.success(Unit) },
                    onChanged = { fail("Cancel must not change membership") }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("Unblock group?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
