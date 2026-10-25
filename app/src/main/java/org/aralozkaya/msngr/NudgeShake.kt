package org.aralozkaya.msngr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class NudgeShakePointDp(
    val x: Int,
    val y: Int,
) {
    fun toIntOffset(density: Density): IntOffset = with(density) {
        IntOffset(
            x = x.dp.toPx().roundToInt(),
            y = y.dp.toPx().roundToInt(),
        )
    }
}

/**
 * Exact 13-step per-tick window displacement deltas from Windows Live Messenger 2009
 * (`msnmsgr.exe` 14.0.8117.0416, `POINT[13]` table at virtual address `0x00430DA0`,
 * stepped by `0x0055D317` on timer `0x12C` every `20 ms`).
 */
internal val WLM_NUDGE_DELTAS_DP: List<NudgeShakePointDp> = listOf(
    NudgeShakePointDp(x = 4, y = 3),
    NudgeShakePointDp(x = -3, y = -6),
    NudgeShakePointDp(x = -3, y = 4),
    NudgeShakePointDp(x = 2, y = 2),
    NudgeShakePointDp(x = -3, y = -5),
    NudgeShakePointDp(x = 5, y = 5),
    NudgeShakePointDp(x = -3, y = -6),
    NudgeShakePointDp(x = -3, y = 6),
    NudgeShakePointDp(x = -1, y = -3),
    NudgeShakePointDp(x = 6, y = -1),
    NudgeShakePointDp(x = -3, y = 4),
    NudgeShakePointDp(x = 6, y = -6),
    NudgeShakePointDp(x = -4, y = 3),
)

/**
 * Cumulative screen offsets from `(0, 0)` across one 13-step cycle (`260 ms`).
 * Step 12 returns the window to `(0, 0)`.
 */
internal val WLM_NUDGE_OFFSETS_DP: List<NudgeShakePointDp> = buildList(WLM_NUDGE_DELTAS_DP.size) {
    var x = 0
    var y = 0
    for (delta in WLM_NUDGE_DELTAS_DP) {
        x += delta.x
        y += delta.y
        add(NudgeShakePointDp(x = x, y = y))
    }
}

internal const val WLM_NUDGE_STEP_INTERVAL_MILLIS = 20L
internal const val WLM_NUDGE_MIN_DURATION_MILLIS = 1_000L
internal const val WLM_NUDGE_CYCLES_PER_NUDGE = 4
internal const val WLM_NUDGE_TOTAL_STEPS_PER_NUDGE = 13 * WLM_NUDGE_CYCLES_PER_NUDGE
internal const val WLM_NUDGE_TOTAL_DURATION_MILLIS =
    WLM_NUDGE_TOTAL_STEPS_PER_NUDGE * WLM_NUDGE_STEP_INTERVAL_MILLIS
internal const val CHAT_SCREEN_SHAKE_CONTAINER_TAG = "chat_screen_shake_container"

@Stable
internal class NudgeShakeState {
    var offsetXDp by mutableIntStateOf(0)
        private set
    var offsetYDp by mutableIntStateOf(0)
        private set
    var completedShakeCount by mutableIntStateOf(0)
        private set

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var stepIndex = 0
    private var elapsedMillis = 0L
    var isShaking: Boolean = false
        private set

    val offsetDp: NudgeShakePointDp
        get() = NudgeShakePointDp(offsetXDp, offsetYDp)

    fun trigger() {
        elapsedMillis = 0L
        if (!isShaking) {
            isShaking = true
            advanceOneStep()
            requests.trySend(Unit)
        }
    }

    internal fun advanceOneStep(): Boolean {
        val delta = WLM_NUDGE_DELTAS_DP[stepIndex]
        offsetXDp += delta.x
        offsetYDp += delta.y
        stepIndex = (stepIndex + 1) % WLM_NUDGE_DELTAS_DP.size
        elapsedMillis += WLM_NUDGE_STEP_INTERVAL_MILLIS
        val finished = stepIndex == 0 && elapsedMillis > WLM_NUDGE_MIN_DURATION_MILLIS
        if (finished) {
            offsetXDp = 0
            offsetYDp = 0
            isShaking = false
            completedShakeCount++
        }
        return !finished
    }

    internal suspend fun runLoop() {
        try {
            for (unit in requests) {
                while (isShaking) {
                    delay(WLM_NUDGE_STEP_INTERVAL_MILLIS)
                    if (!advanceOneStep()) break
                }
            }
        } finally {
            reset()
        }
    }

    internal fun reset() {
        offsetXDp = 0
        offsetYDp = 0
        stepIndex = 0
        elapsedMillis = 0L
        isShaking = false
    }
}

@Composable
internal fun rememberNudgeShakeState(): NudgeShakeState {
    val state = remember { NudgeShakeState() }
    LaunchedEffect(state) {
        state.runLoop()
    }
    return state
}
