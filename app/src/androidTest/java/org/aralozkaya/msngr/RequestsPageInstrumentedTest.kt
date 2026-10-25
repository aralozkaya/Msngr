package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.aralozkaya.msngr.data.ContactRequest
import org.aralozkaya.msngr.data.GroupInvitation
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.pages.ContactRequestDeclineDialog
import org.aralozkaya.msngr.pages.RequestsPage
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RequestsPageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stopRepositoryScope() { repositoryScope.cancel() }
    private val request = ContactRequest("peer@example.test", "Pending/test")

    @Test fun emptyMessageIsCenteredAndDecideLaterRetainsTheRequestWithoutSending() {
        val requests = mutableStateOf(emptyList<ContactRequest>())
        var calls = 0
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    requests.value, { _, _, _ -> calls++; Result.success(Unit) })
            }
        }
        val content = compose.onNodeWithTag("requests-content").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("requests-empty").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(content.center.x, text.center.x, 1f)
        assertEquals(content.center.y, text.center.y, 1f)
        compose.runOnIdle { requests.value = listOf(request) }
        compose.onNodeWithTag("decline-request-${request.id}").performClick()
        compose.onNodeWithText("Do you want to add this person to Messenger?").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-yes").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-no").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-block").assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("contact-request-set-default").assertIsOff().assertIsEnabled()
        compose.onNodeWithText("Decide later").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-${request.id}").assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test fun declineReplacesItsActionWhileFailureKeepsChoiceForRetry() {
        val response = CompletableDeferred<Result<Unit>>()
        val calls = mutableListOf<Boolean>()
        val savedDefaults = mutableListOf<Boolean>()
        var done = false
        compose.setContent {
            MsngrTheme {
                ContactRequestDeclineDialog(request, { block ->
                    calls += block
                    if (calls.size == 1) response.await() else Result.success(Unit)
                }, { done = true }, onSaveDeclineDefaults = { savedDefaults += it })
            }
        }
        compose.onNodeWithTag("contact-request-block").assertIsOff().assertIsEnabled().performClick()
        compose.onNodeWithTag("contact-request-set-default").assertIsOff().performClick()
        val before = compose.onNodeWithTag("contact-request-modal").fetchSemanticsNode().boundsInRoot
        val actionBefore = compose.onNodeWithTag("contact-request-submit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("contact-request-submit").performClick()
        compose.onNodeWithTag("contact-request-block").assertIsNotEnabled()
        compose.onNodeWithTag("contact-request-set-default").assertIsNotEnabled()
        compose.onNodeWithText("Decide later").assertIsNotEnabled()
        val modal = compose.onNodeWithTag("contact-request-modal").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("contact-request-loading-overlay").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-submit").assertIsEnabled()
        val spinner = compose.onNodeWithTag("contact-request-spinner", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, modal.height, 1f)
        assertEquals(before.width, modal.width, 1f)
        assertEquals(actionBefore.center.x, spinner.center.x, 1f)
        assertEquals(actionBefore.center.y, spinner.center.y, 1f)
        compose.onNodeWithText("Responding to request…").assertDoesNotExist()
        response.complete(Result.failure(IllegalStateException("Please retry.")))
        compose.onNodeWithText("Please retry.").assertIsDisplayed()
        compose.onNodeWithTag("contact-request-block").assertIsOn()
        compose.onNodeWithTag("contact-request-set-default").assertIsOn()
        compose.runOnIdle { assertTrue(savedDefaults.isEmpty()) }
        compose.onNodeWithTag("contact-request-submit").performClick()
        compose.waitUntil(5000) { done }
        assertEquals(listOf(true, true), calls)
        assertEquals(listOf(true), savedDefaults)
    }

    @Test fun enabledDeclineDefaultsBlockWithoutModalAndKeepFailureAvailableForRetry() {
        val response = CompletableDeferred<Result<Unit>>()
        val requests = mutableStateOf(listOf(request))
        val calls = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    requests.value, { id, accept, block ->
                        calls += Triple(id, accept, block)
                        if (calls.size == 1) response.await() else {
                            requests.value = emptyList()
                            Result.success(Unit)
                        }
                    }, invitationSettings = ContactInvitationSettings(true, true))
            }
        }
        compose.onNodeWithTag("decline-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-busy-${request.id}").assertDoesNotExist()
        assertLoadingButton("decline-request-${request.id}", "decline-request-loading-${request.id}")
        compose.onNodeWithTag("accept-request-loading-${request.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("decline-request-${request.id}").assertIsEnabled()
        compose.onNodeWithTag("accept-request-${request.id}").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls.size) }
        response.complete(Result.failure(IllegalStateException("Please retry the decline.")))
        compose.onNodeWithTag("contact-request-error-${request.id}").assertIsDisplayed()
        compose.onNodeWithTag("decline-request-loading-${request.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("decline-request-${request.id}").assertIsEnabled().performClick()
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        assertEquals(List(2) { Triple(request.id, false, true) }, calls)
    }

    @Test fun enabledDefaultsCanDeclineWithoutBlockingAndAcceptIsImmediate() {
        val requests = mutableStateOf(listOf(request))
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    requests.value, { _, accept, block ->
                        calls += accept to block
                        requests.value = emptyList()
                        Result.success(Unit)
                    }, invitationSettings = ContactInvitationSettings(useDeclineDefaults = true))
            }
        }
        compose.onNodeWithTag("decline-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(false to false), calls)
            requests.value = listOf(request)
        }
        compose.onNodeWithTag("accept-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        assertEquals(listOf(false to false, true to false), calls)
    }

    @Test fun acceptIsImmediateAndNeverBlocksWhileFailureKeepsRequestForRetry() {
        val response = CompletableDeferred<Result<Unit>>()
        val requests = mutableStateOf(listOf(request))
        val calls = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    requests.value, { id, accept, block ->
                        calls += Triple(id, accept, block)
                        if (calls.size == 1) response.await() else {
                            requests.value = emptyList()
                            Result.success(Unit)
                        }
                    }, invitationSettings = ContactInvitationSettings(blockDeclinedInvitations = true))
            }
        }
        compose.onNodeWithTag("accept-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-busy-${request.id}").assertDoesNotExist()
        assertLoadingButton("accept-request-${request.id}", "accept-request-loading-${request.id}")
        compose.onNodeWithTag("decline-request-loading-${request.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("decline-request-${request.id}").assertIsNotEnabled()
        compose.onNodeWithTag("accept-request-${request.id}").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf(Triple(request.id, true, false)), calls) }
        response.complete(Result.failure(IllegalStateException("Please retry the acceptance.")))
        compose.onNodeWithTag("contact-request-error-${request.id}").assertIsDisplayed()
        compose.onNodeWithTag("accept-request-loading-${request.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("accept-request-${request.id}").assertIsEnabled().performClick()
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        assertEquals(List(2) { Triple(request.id, true, false) }, calls)
    }

    @Test fun choosingDefaultThenDecidingLaterOrAcceptingDoesNotSaveDeclineDefaults() {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        val savedDefaults = mutableListOf<Boolean>()
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    listOf(request), { _, accept, block ->
                        calls += accept to block
                        Result.success(Unit)
                    }, onSaveDeclineDefaults = { savedDefaults += it })
            }
        }
        compose.onNodeWithTag("decline-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-set-default").performClick()
        compose.onNodeWithText("Decide later").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertTrue(savedDefaults.isEmpty()) }
        compose.onNodeWithTag("decline-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-set-default").assertIsOff().performClick()
        compose.onNodeWithText("Decide later").performClick()
        compose.onNodeWithTag("accept-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(true to false), calls)
            assertTrue(savedDefaults.isEmpty())
        }
    }

    @Test fun aRequestResolvedElsewhereClosesItsDecisionDialog() {
        val requests = mutableStateOf(listOf(request))
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    requests.value, { _, _, _ -> error("Must not send") })
            }
        }
        compose.onNodeWithTag("decline-request-${request.id}").performClick()
        compose.onNodeWithTag("contact-request-modal").assertIsDisplayed()
        compose.runOnIdle { requests.value = emptyList() }
        compose.onNodeWithTag("contact-request-modal").assertDoesNotExist()
        compose.onNodeWithText("No pending requests or invitations").assertIsDisplayed()
    }

    @Test fun mixedInboxKeepsContactAndGroupDecisionsSeparateEvenWithMatchingIds() {
        val invitations = mutableStateOf(listOf(GroupInvitation(request.id, "Weekend plans",
            "friend@example.com", "Alex", "Join us for the weekend.")))
        var contactCalls = 0
        val groupReplies = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MsngrTheme {
                SecondaryPageScaffold("Requests & invitations", {}) { padding, scroll ->
                    RequestsPage(padding, scroll, listOf(request.copy(displayName = "Sam")),
                        { _, _, _ -> contactCalls++; Result.success(Unit) },
                        invitations = invitations.value, onRespondToGroup = { id, accept ->
                            groupReplies += id to accept
                            invitations.value = emptyList()
                            Result.success(Unit)
                        })
                }
            }
        }
        compose.onNodeWithTag("contact-request-${request.id}").assertIsDisplayed()
        compose.onNodeWithTag("group-invitation-${request.id}").performScrollTo().assertIsDisplayed()
        saveScreen("requests-invitations-mixed")
        compose.onNodeWithTag("decline-request-${request.id}").performScrollTo().performClick()
        compose.onNodeWithText("Decide later").performClick()
        compose.onNodeWithTag("decline-group-${request.id}").performScrollTo().performClick()
        compose.onNodeWithTag("group-invitation-${request.id}").assertDoesNotExist()
        compose.onNodeWithTag("contact-request-${request.id}").assertIsDisplayed()
        compose.onNodeWithTag("requests-empty").assertDoesNotExist()
        assertEquals(0, contactCalls)
        assertEquals(listOf(request.id to false), groupReplies)
    }

    @Test fun refreshUsesTheAppBarAndCentersLoadingWhileHidingCachedItems() {
        val refreshing = mutableStateOf(true)
        var refreshes = 0
        val repository = requestsRepository(listOf(request), listOf(GroupInvitation("test-group", "Weekend plans")))
        compose.setContent {
            MsngrTheme {
                RequestsRoute(repository, {}, refreshing.value, null,
                    onRefresh = { refreshes++; refreshing.value = true }, onJoined = {})
            }
        }
        assertRefreshCentered()
        compose.onNodeWithTag("requests-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("requests-list").assertDoesNotExist()
        compose.runOnIdle { refreshing.value = false }
        compose.onNodeWithTag("contact-request-${request.id}").assertIsDisplayed()
        compose.onNodeWithTag("requests-content").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("requests-refresh").assertIsEnabled().performClick()
        assertRefreshCentered()
        compose.onNodeWithTag("requests-refresh").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); refreshing.value = false }
        compose.onNodeWithTag("requests-list").assertIsDisplayed()
        compose.onNodeWithTag("requests-refresh-indicator").assertDoesNotExist()
    }

    @Test fun emptyInboxHasNoPullRefreshAndRefreshErrorsOfferRetry() {
        val error = mutableStateOf<String?>(null)
        var refreshes = 0
        val repository = requestsRepository()
        compose.setContent {
            MsngrTheme {
                RequestsRoute(repository, {}, false, error.value,
                    onRefresh = { refreshes++; error.value = "Could not load invitations." }, onJoined = {})
            }
        }
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
        compose.onNodeWithTag("requests-content").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("requests-refresh").performClick()
        compose.onNodeWithText("Could not load invitations.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    private fun requestsRepository(requests: List<ContactRequest> = emptyList(),
                                   invitations: List<GroupInvitation> = emptyList()): MessengerRepository {
        val mock = MockMsnp18Client(signInDelayMillis = 0)
        val client = object : MsnpClient by mock {
            override val contactRequests = MutableStateFlow(requests)
            override val groupInvitations = MutableStateFlow(invitations)
        }
        return MessengerRepository(client, repositoryScope, useMockServer = true)
    }

    private fun assertRefreshCentered() {
        val bounds = compose.onNodeWithTag("requests-content").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("requests-refresh-indicator").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.center.x, indicator.center.x, 1f)
        assertEquals(bounds.center.y, indicator.center.y, 1f)
    }

    @Test fun failedGroupDeclineRetainsInvitationAndJoinRetryLoadsOnlyItsOwnButton() {
        val invitation = GroupInvitation("test-group", "Weekend plans", "friend@example.com")
        val invitations = mutableStateOf(listOf(invitation))
        val response = CompletableDeferred<Result<Unit>>()
        val retryResponse = CompletableDeferred<Result<Unit>>()
        val replies = mutableListOf<Pair<String, Boolean>>()
        var joined: String? = null
        compose.setContent {
            MsngrTheme {
                RequestsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    emptyList(), { _, _, _ -> error("Must not send") }, invitations = invitations.value,
                    onRespondToGroup = { id, accept ->
                        replies += id to accept
                        val result = if (replies.size == 1) response.await() else retryResponse.await()
                        if (result.isSuccess) invitations.value = emptyList()
                        result
                    }, onJoined = { joined = it })
            }
        }
        compose.onNodeWithTag("decline-group-${invitation.id}").performClick()
        compose.onNodeWithTag("accept-group-${invitation.id}").assertIsNotEnabled()
        assertLoadingButton("decline-group-${invitation.id}", "decline-group-loading-${invitation.id}")
        compose.onNodeWithTag("accept-group-loading-${invitation.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("group-invitation-busy-${invitation.id}").assertDoesNotExist()
        compose.runOnIdle { assertNull(joined); assertEquals(1, replies.size) }
        response.complete(Result.failure(IllegalStateException("Please retry the invitation.")))
        compose.onNodeWithTag("group-invitation-error-${invitation.id}").assertIsDisplayed()
        compose.onNodeWithTag("group-invitation-${invitation.id}").assertIsDisplayed()
        compose.onNodeWithTag("decline-group-loading-${invitation.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("accept-group-${invitation.id}").assertIsEnabled().performClick()
        assertLoadingButton("accept-group-${invitation.id}", "accept-group-loading-${invitation.id}")
        compose.onNodeWithTag("decline-group-loading-${invitation.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertNull(joined); assertEquals(2, replies.size) }
        retryResponse.complete(Result.success(Unit))
        compose.waitUntil(5000) { joined != null }
        assertEquals(invitation.id, joined)
        assertEquals(listOf(invitation.id to false, invitation.id to true), replies)
        compose.onNodeWithTag("requests-empty").assertIsDisplayed()
    }

    private fun assertLoadingButton(buttonTag: String, indicatorTag: String) {
        compose.onNodeWithTag(buttonTag).assertIsEnabled().performClick()
        compose.onNodeWithTag(indicatorTag, useUnmergedTree = true).assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag(buttonTag)))
    }

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
