package org.aralozkaya.msngr

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NudgeCooldownInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingAndCoolingNudgesDisableTheButtonWhileTextRemainsAvailable() {
        val wait = mutableStateOf(Long.MAX_VALUE)
        val draft = mutableStateOf("")
        var nudges = 0
        compose.setContent {
            MsngrTheme {
                ChatScreen(draft = draft.value, onDraftChange = { draft.value = it },
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                    isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                    onSendTyping = {}, onSendNudge = { nudges++ }, onLoadOlderMessages = {},
                    nudgeCooldownMillis = wait.value)
            }
        }
        compose.onNodeWithText("Sending nudge…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.runOnIdle { wait.value = 11_000 }
        compose.onNodeWithText("You can send another nudge in 11 seconds.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.runOnIdle { draft.value = "Text during cooldown" }
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        compose.runOnIdle { draft.value = ""; wait.value = 0 }
        compose.onNodeWithText("You can send another nudge in 11 seconds.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, nudges) }
    }

    @Test fun sendingAndReceivingNudgesShakeTheEntireChatScreenFollowingWlmTrajectory() {
        val incomingShakeCount = mutableIntStateOf(0)
        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            MsngrTheme {
                ChatScreen(
                    draft = "",
                    onDraftChange = {},
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = emptyList(),
                    isHistoryLoaded = true,
                    canLoadOlderMessages = false,
                    isTyping = false,
                    onBack = {},
                    onSendMessage = { _, _ -> },
                    onRetryMessage = {},
                    onSendTyping = {},
                    onSendNudge = {},
                    onLoadOlderMessages = {},
                    nudgeShakeCount = incomingShakeCount.intValue,
                )
            }
        }

        fun currentContainerOffset(): IntOffset {
            val pos = compose.onNodeWithTag(CHAT_SCREEN_SHAKE_CONTAINER_TAG)
                .fetchSemanticsNode()
                .positionInRoot
            return IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
        }

        assertEquals(IntOffset.Zero, currentContainerOffset())

        compose.mainClock.autoAdvance = false
        try {
            // Outgoing nudge shakes across all 4 WLM 13-step cycles (52 steps @ 20 ms = 1,040 ms) and returns to (0, 0).
            compose.onNodeWithContentDescription("Send nudge").performClick()

            for (cycle in 0 until WLM_NUDGE_CYCLES_PER_NUDGE) {
                for (step in WLM_NUDGE_OFFSETS_DP.indices) {
                    assertEquals(
                        "Cycle $cycle step $step",
                        WLM_NUDGE_OFFSETS_DP[step].toIntOffset(density),
                        currentContainerOffset(),
                    )
                    compose.mainClock.advanceTimeBy(
                        milliseconds = WLM_NUDGE_STEP_INTERVAL_MILLIS,
                        ignoreFrameDuration = true,
                    )
                }
            }
            assertEquals(IntOffset.Zero, currentContainerOffset())

            // Incoming/queued nudge shake follows the same trajectory and returns to (0, 0).
            compose.runOnIdle { incomingShakeCount.intValue = 1 }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(WLM_NUDGE_OFFSETS_DP[0].toIntOffset(density), currentContainerOffset())
            compose.mainClock.advanceTimeBy(
                milliseconds = WLM_NUDGE_TOTAL_DURATION_MILLIS,
                ignoreFrameDuration = true,
            )
            assertEquals(IntOffset.Zero, currentContainerOffset())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
