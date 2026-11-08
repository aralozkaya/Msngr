@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

internal const val CHAT_SCROLL_TO_LATEST_TAG = "chat_scroll_to_latest"

/** How long the button stays after the list stops moving. */
internal const val CHAT_SCROLL_TO_LATEST_LINGER_MILLIS = 2_500L

private const val MAX_FRAME_SHARE = 0.35f
private val ScrollEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * A small floating button that appears while a reversed conversation list is scrolled away from
 * its newest message and is being scrolled (or just was). It scrolls back down in one quick,
 * decelerating motion.
 */
@Composable
internal fun ChatScrollToLatestButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scrolledAway by remember(listState) {
        derivedStateOf { listState.isScrolledAwayFromLatest() }
    }
    // Shown while the list moves, and for a moment after it stops so it can still be tapped.
    var recentlyScrolled by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        // Any movement counts, however brief; the linger restarts with each change.
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.drop(1).collectLatest { (scrolling, _, _) ->
            recentlyScrolled = true
            if (!scrolling) {
                delay(CHAT_SCROLL_TO_LATEST_LINGER_MILLIS)
                recentlyScrolled = false
            }
        }
    }
    val visible = scrolledAway && recentlyScrolled
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(motion.fastEffectsSpec()) +
            scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f) +
            slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
        exit = fadeOut(motion.fastEffectsSpec()) +
            scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) +
            slideOutVertically(motion.fastSpatialSpec()) { it / 2 },
    ) {
        FilledIconButton(
            onClick = { scope.launch { listState.animateScrollToLatest() } },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            modifier = Modifier.testTag(CHAT_SCROLL_TO_LATEST_TAG),
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to latest message")
        }
    }
}

/** Index zero is the newest message in the reversed conversation list. */
private fun LazyListState.isScrolledAwayFromLatest(): Boolean =
    firstVisibleItemIndex > 0 ||
        firstVisibleItemScrollOffset > layoutInfo.viewportSize.height / 3

/**
 * Scrolls to the newest message as one continuous, fast glide instead of a jump.
 *
 * Item sizes are only known for the visible rows, so the remaining distance is re-estimated on
 * every frame instead of animating toward a fixed target. The easing curve is applied to
 * whatever distance is left, and the estimate becomes exact once the newest item is on screen,
 * so the glide always ends precisely at the bottom with no leftover correction. The duration
 * grows with the distance, from 220 ms to 750 ms. A touch on the list interrupts the glide.
 */
internal suspend fun LazyListState.animateScrollToLatest() {
    val initialDistance = estimatedDistanceToLatest()
    if (initialDistance <= 0f) return
    val screens = initialDistance / layoutInfo.viewportSize.height.coerceAtLeast(1)
    val durationNanos = (220 + 120 * ln(1f + screens)).roundToInt().coerceIn(220, 750) * 1_000_000L
    scroll {
        val start = withFrameNanos { it }
        var previousProgress = 0f
        while (canScrollBackward) {
            val frame = withFrameNanos { it }
            val progress = ScrollEasing.transform(((frame - start).toFloat() / durationNanos).coerceIn(0f, 1f))
            val remaining = estimatedDistanceToLatest()
            // Cover this frame's share of the curve out of what is left, capped so that any
            // underestimated distance near the end still glides in instead of snapping on the
            // final frame.
            val rawShare = if (previousProgress >= 1f) 1f else (progress - previousProgress) / (1f - previousProgress)
            val share = rawShare.coerceAtMost(MAX_FRAME_SHARE)
            previousProgress = progress
            // Negative moves toward index zero.
            val consumed = -scrollBy(-(remaining * share).coerceAtLeast(1f).coerceAtMost(remaining))
            if (consumed <= 0f) break
        }
    }
    // Only reachable if the list stopped moving before the bottom, e.g. when its content changed.
    if (canScrollBackward) scrollToItem(0)
}

private fun LazyListState.estimatedDistanceToLatest(): Float {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    val averageSize = visible.sumOf { it.size } / visible.size.toFloat()
    return firstVisibleItemIndex * averageSize + firstVisibleItemScrollOffset
}
