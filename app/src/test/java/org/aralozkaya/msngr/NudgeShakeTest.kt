package org.aralozkaya.msngr

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeShakeTest {
    @Test fun wlmDeltaTableMatchesDisassembled13StepTrajectoryAndReturnsToOriginEachCycle() {
        assertEquals(
            listOf(
                NudgeShakePointDp(4, 3),
                NudgeShakePointDp(-3, -6),
                NudgeShakePointDp(-3, 4),
                NudgeShakePointDp(2, 2),
                NudgeShakePointDp(-3, -5),
                NudgeShakePointDp(5, 5),
                NudgeShakePointDp(-3, -6),
                NudgeShakePointDp(-3, 6),
                NudgeShakePointDp(-1, -3),
                NudgeShakePointDp(6, -1),
                NudgeShakePointDp(-3, 4),
                NudgeShakePointDp(6, -6),
                NudgeShakePointDp(-4, 3),
            ),
            WLM_NUDGE_DELTAS_DP,
        )
        assertEquals(
            listOf(
                NudgeShakePointDp(4, 3),
                NudgeShakePointDp(1, -3),
                NudgeShakePointDp(-2, 1),
                NudgeShakePointDp(0, 3),
                NudgeShakePointDp(-3, -2),
                NudgeShakePointDp(2, 3),
                NudgeShakePointDp(-1, -3),
                NudgeShakePointDp(-4, 3),
                NudgeShakePointDp(-5, 0),
                NudgeShakePointDp(1, -1),
                NudgeShakePointDp(-2, 3),
                NudgeShakePointDp(4, -3),
                NudgeShakePointDp(0, 0),
            ),
            WLM_NUDGE_OFFSETS_DP,
        )
        assertEquals(0, WLM_NUDGE_DELTAS_DP.sumOf { it.x })
        assertEquals(0, WLM_NUDGE_DELTAS_DP.sumOf { it.y })
        assertEquals(NudgeShakePointDp(0, 0), WLM_NUDGE_OFFSETS_DP.last())
        assertEquals(20L, WLM_NUDGE_STEP_INTERVAL_MILLIS)
        assertEquals(52, WLM_NUDGE_TOTAL_STEPS_PER_NUDGE)
        assertEquals(1_040L, WLM_NUDGE_TOTAL_DURATION_MILLIS)
    }

    @Test fun shakeStateStepsThroughFourFullCyclesAndStopsAtOrigin() {
        val state = NudgeShakeState()
        assertFalse(state.isShaking)
        assertEquals(NudgeShakePointDp(0, 0), state.offsetDp)

        state.trigger()
        assertTrue(state.isShaking)
        val visited = mutableListOf(state.offsetDp)
        while (state.isShaking) {
            state.advanceOneStep()
            visited += state.offsetDp
        }

        assertEquals(WLM_NUDGE_TOTAL_STEPS_PER_NUDGE, visited.size)
        val expected = buildList {
            repeat(WLM_NUDGE_CYCLES_PER_NUDGE) {
                addAll(WLM_NUDGE_OFFSETS_DP)
            }
        }
        assertEquals(expected, visited)
        assertEquals(NudgeShakePointDp(0, 0), state.offsetDp)
        assertEquals(1, state.completedShakeCount)
    }

    @Test fun retriggerMidCycleExtendsShakeWithoutDriftAndStopsAtCycleBoundary() = runBlocking {
        val state = NudgeShakeState()
        val runner = launch { state.runLoop() }
        try {
            state.trigger()
            assertEquals(WLM_NUDGE_OFFSETS_DP[0], state.offsetDp)
            repeat(5) {
                assertTrue(state.advanceOneStep())
            }
            assertEquals(WLM_NUDGE_OFFSETS_DP[5], state.offsetDp)

            // Re-trigger mid-cycle (like WLM 2009 resetting startTick at [this + 0x368]):
            // stepIndex and offset stay continuous and finish on a completed 13-step cycle.
            state.trigger()
            assertEquals(WLM_NUDGE_OFFSETS_DP[5], state.offsetDp)
            var remainingSteps = 0
            while (state.isShaking) {
                state.advanceOneStep()
                remainingSteps++
            }
            // Remaining 7 steps of cycle 1 (140 ms <= 1000 ms) + 4 full cycles (52 steps = 1040 ms) = 59 steps.
            assertEquals(7 + WLM_NUDGE_TOTAL_STEPS_PER_NUDGE, remainingSteps)
            assertEquals(NudgeShakePointDp(0, 0), state.offsetDp)
            assertEquals(1, state.completedShakeCount)
        } finally {
            runner.cancel()
        }
    }

    @Test fun repositoryCoalescesMultipleUnseenNudgesPerConversationAndCleansUpState() = runBlocking {
        val client = FakeNudgeMsnpClient()
        val repository = MessengerRepository(client)
        val escargot = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
        try {
            repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD, escargot)).getOrThrow()

            // Multiple nudges for the same conversation coalesce into a single pending shake.
            client.events.tryEmit(MessengerEvent.IncomingNudge("Selin@Msngr.dev"))
            client.events.tryEmit(MessengerEvent.IncomingNudge("selin@msngr.dev"))
            client.events.tryEmit(MessengerEvent.IncomingNudge("SELIN@MSNGR.DEV"))
            client.events.tryEmit(MessengerEvent.IncomingNudge("mert@msngr.dev"))

            assertEquals(setOf("selin@msngr.dev", "mert@msngr.dev"), repository.pendingNudgeShakes.value)
            assertTrue(repository.consumePendingNudgeShake("SELIN@msngr.dev"))
            assertFalse(repository.consumePendingNudgeShake("selin@msngr.dev"))
            assertEquals(setOf("mert@msngr.dev"), repository.pendingNudgeShakes.value)

            // Clearing a single conversation's history removes only its pending shake.
            client.events.tryEmit(MessengerEvent.IncomingNudge("selin@msngr.dev"))
            repository.clearConversationHistory("selin@msngr.dev")
            assertFalse(repository.consumePendingNudgeShake("selin@msngr.dev"))
            assertTrue(repository.consumePendingNudgeShake("mert@msngr.dev"))

            // Blocking a contact clears its pending shake.
            client.events.tryEmit(MessengerEvent.IncomingNudge("selin@msngr.dev"))
            repository.blockContact("selin@msngr.dev")
            assertFalse(repository.consumePendingNudgeShake("selin@msngr.dev"))

            // Logout clears all pending shakes.
            client.events.tryEmit(MessengerEvent.IncomingNudge("selin@msngr.dev"))
            repository.logout()
            assertTrue(repository.pendingNudgeShakes.value.isEmpty())
        } finally {
            repository.logout()
        }
    }
}

private class FakeNudgeMsnpClient : MsnpClient {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    override val typing = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val events = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 16)

    override suspend fun signIn(request: SignInRequest) =
        Result.success(UserProfileSnapshot("You", request.email, Presence.Online))
    override suspend fun changePresence(presence: Presence) = Unit
    override suspend fun changeNickname(nickname: String) = Unit
    override suspend fun changePersonalMessage(statusMessage: String) = Unit
    override suspend fun changeDisplayPicture(image: ByteArray?) = Unit
    override suspend fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ) = Unit
    override suspend fun sendTyping(contactId: String) = Unit
    override suspend fun sendNudge(contactId: String) = Unit
    override suspend fun blockContact(contactId: String) = Unit
    override suspend fun unblockContact(contactId: String) = Unit
    override fun markRead(contactId: String) = Unit
    override fun markUnread(contactId: String) = Unit
    override fun clearMessages() = Unit
    override fun clearMessages(contactId: String) = Unit
    override fun close() = Unit
}
