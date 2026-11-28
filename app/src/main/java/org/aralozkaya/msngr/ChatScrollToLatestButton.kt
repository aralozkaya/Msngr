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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import kotlin.math.ln
import kotlin.math.roundToInt

internal const val CHAT_SCROLL_TO_LATEST_TAG = "chat_scroll_to_latest"
internal const val CHAT_SCROLL_TO_LATEST_BADGE_TAG = "chat_scroll_to_latest_badge"

/** How long the button stays after the list stops moving. */
internal const val CHAT_SCROLL_TO_LATEST_LINGER_MILLIS = 2_500L

private const val MAX_FRAME_SHARE = 0.35f
private val ScrollEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal data class ChatNewMessageScrollState(
    val lastKnownLatestMessageId: Long? = null,
    val unseenMessageIds: List<Long> = emptyList(),
)

internal data class ChatNewMessageUpdate(
    val state: ChatNewMessageScrollState,
    val shouldAutoScrollToLatest: Boolean,
)

private val ChatNewMessageScrollStateSaver = listSaver<ChatNewMessageScrollState, Any?>(
    save = { listOf(it.lastKnownLatestMessageId, it.unseenMessageIds.toLongArray()) },
    restore = { saved ->
        ChatNewMessageScrollState(
            lastKnownLatestMessageId = saved.getOrNull(0) as? Long,
            unseenMessageIds = (saved.getOrNull(1) as? LongArray)?.toList().orEmpty(),
        )
    },
)

internal fun ChatMessage.isOutgoingForScroll(): Boolean = when (author) {
    MessageAuthor.Me -> true
    MessageAuthor.Contact -> false
    MessageAuthor.System ->
        text == "You sent a nudge." ||
            text == "Photo sharing invitation sent." ||
            text == "You stopped sharing photos." ||
            text.startsWith("Game invitation sent:") ||
            text.startsWith("You closed ") ||
            text.endsWith("could not be sent.") ||
            text.contains(" could not be sent: ")
}

internal fun reduceChatNewMessages(
    current: ChatNewMessageScrollState,
    messages: List<ChatMessage>,
    isHistoryLoaded: Boolean,
    isScrolledAway: Boolean,
    isAutoScrollingToLatest: Boolean,
): ChatNewMessageUpdate {
    if (messages.isEmpty()) {
        return if (!isHistoryLoaded) {
            ChatNewMessageUpdate(current, shouldAutoScrollToLatest = false)
        } else {
            ChatNewMessageUpdate(ChatNewMessageScrollState(), shouldAutoScrollToLatest = false)
        }
    }
    val latestId = messages.last().id
    val previousLatestId = current.lastKnownLatestMessageId
        ?: return ChatNewMessageUpdate(
            ChatNewMessageScrollState(lastKnownLatestMessageId = latestId),
            shouldAutoScrollToLatest = false,
        )
    val presentIds = messages.asSequence().map(ChatMessage::id).toHashSet()
    val retainedUnseen = current.unseenMessageIds.filter { it in presentIds }
    val previousIndex = messages.indexOfLast { it.id == previousLatestId }
    if (previousIndex < 0 || previousIndex == messages.lastIndex) {
        return ChatNewMessageUpdate(
            ChatNewMessageScrollState(
                lastKnownLatestMessageId = latestId,
                unseenMessageIds = retainedUnseen,
            ),
            shouldAutoScrollToLatest = false,
        )
    }
    val newlyAppended = messages.subList(previousIndex + 1, messages.size)
    if (newlyAppended.any { it.isOutgoingForScroll() } || !isScrolledAway || isAutoScrollingToLatest) {
        return ChatNewMessageUpdate(
            ChatNewMessageScrollState(
                lastKnownLatestMessageId = latestId,
                unseenMessageIds = emptyList(),
            ),
            shouldAutoScrollToLatest = true,
        )
    }
    val updatedUnseen = (retainedUnseen + newlyAppended.map(ChatMessage::id)).distinct()
    return ChatNewMessageUpdate(
        ChatNewMessageScrollState(
            lastKnownLatestMessageId = latestId,
            unseenMessageIds = updatedUnseen,
        ),
        shouldAutoScrollToLatest = false,
    )
}

internal fun pruneVisibleUnseenMessages(
    unseenMessageIds: List<Long>,
    timeline: List<ChatTimelineItem>,
    firstVisibleTimelineIndex: Int,
    isScrolledAway: Boolean,
): List<Long> {
    if (unseenMessageIds.isEmpty() || !isScrolledAway) return emptyList()
    val timelineIndexByMessageId = buildMap(timeline.size) {
        timeline.forEachIndexed { index, item ->
            if (item is ChatTimelineItem.Message) {
                put(item.message.id, index)
            }
        }
    }
    return unseenMessageIds.filter { id ->
        val index = timelineIndexByMessageId[id]
        index != null && (index < firstVisibleTimelineIndex || index == 0)
    }
}

internal fun wasScrolledAwayBeforeLatestUpdate(
    previousLatestMessageId: Long?,
    timeline: List<ChatTimelineItem>,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstVisibleItemKey: Any?,
    viewportHeight: Int,
): Boolean {
    val scrolledAwayThreshold = viewportHeight / 3
    if (previousLatestMessageId == null) {
        return firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > scrolledAwayThreshold
    }
    val previousLatestTimelineIndex = timeline.indexOfFirst { it.key == previousLatestMessageId }
    if (previousLatestTimelineIndex < 0) {
        return firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > scrolledAwayThreshold
    }
    val anchorTimelineIndex = firstVisibleItemKey
        ?.let { key -> timeline.indexOfFirst { it.key == key } }
        ?.takeIf { it >= 0 }
        ?: firstVisibleItemIndex
    return anchorTimelineIndex > previousLatestTimelineIndex ||
        (anchorTimelineIndex == previousLatestTimelineIndex &&
            firstVisibleItemScrollOffset > scrolledAwayThreshold)
}

internal class ChatNewMessageScrollController(
    val unseenCount: Int,
    val isAutoScrolling: () -> Boolean,
)

@Composable
internal fun rememberChatNewMessageScrollController(
    messages: List<ChatMessage>,
    timeline: List<ChatTimelineItem>,
    listState: LazyListState,
    isHistoryLoaded: Boolean,
): ChatNewMessageScrollController {
    var scrollState by rememberSaveable(stateSaver = ChatNewMessageScrollStateSaver) {
        mutableStateOf(ChatNewMessageScrollState())
    }
    var activeAutoScroll by remember(listState) { mutableStateOf(false) }
    val currentMessages by rememberUpdatedState(messages)
    val currentTimeline by rememberUpdatedState(timeline)
    val latestMessageId = messages.lastOrNull()?.id
    val isAutoScrolling: () -> Boolean = {
        if (activeAutoScroll) {
            true
        } else {
            val previousLatestId = scrollState.lastKnownLatestMessageId
            val latestId = currentMessages.lastOrNull()?.id
            if (previousLatestId != null && latestId != null && previousLatestId != latestId) {
                val firstVisibleIndex = listState.firstVisibleItemIndex
                val anchorKey = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == firstVisibleIndex }
                    ?.key
                !wasScrolledAwayBeforeLatestUpdate(
                    previousLatestMessageId = previousLatestId,
                    timeline = currentTimeline,
                    firstVisibleItemIndex = firstVisibleIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    firstVisibleItemKey = anchorKey,
                    viewportHeight = listState.layoutInfo.viewportSize.height,
                )
            } else {
                false
            }
        }
    }

    LaunchedEffect(listState, latestMessageId, isHistoryLoaded) {
        val firstVisibleIndex = listState.firstVisibleItemIndex
        val anchorKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == firstVisibleIndex }
            ?.key
        val wasScrolledAway = wasScrolledAwayBeforeLatestUpdate(
            previousLatestMessageId = scrollState.lastKnownLatestMessageId,
            timeline = currentTimeline,
            firstVisibleItemIndex = firstVisibleIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            firstVisibleItemKey = anchorKey,
            viewportHeight = listState.layoutInfo.viewportSize.height,
        )
        val update = reduceChatNewMessages(
            current = scrollState,
            messages = currentMessages,
            isHistoryLoaded = isHistoryLoaded,
            isScrolledAway = wasScrolledAway,
            isAutoScrollingToLatest = activeAutoScroll,
        )
        if (update.shouldAutoScrollToLatest) {
            activeAutoScroll = true
            if (update.state != scrollState) {
                scrollState = update.state
            }
            try {
                listState.animateScrollToItem(0)
            } finally {
                if (currentMessages.lastOrNull()?.id == latestMessageId) {
                    activeAutoScroll = false
                }
            }
        } else if (update.state != scrollState) {
            scrollState = update.state
        }
    }

    LaunchedEffect(listState, timeline, isHistoryLoaded) {
        if (!isHistoryLoaded || timeline.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val scrolledAway = listState.isScrolledAwayFromLatest()
            val anchorKey = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == firstVisibleIndex }
                ?.key
            Triple(firstVisibleIndex, scrolledAway, anchorKey)
        }.collect { (firstVisibleIndex, scrolledAway, anchorKey) ->
            val effectiveFirstIndex = anchorKey
                ?.let { key -> timeline.indexOfFirst { it.key == key } }
                ?.takeIf { it >= 0 }
                ?: firstVisibleIndex
            val pruned = pruneVisibleUnseenMessages(
                unseenMessageIds = scrollState.unseenMessageIds,
                timeline = timeline,
                firstVisibleTimelineIndex = effectiveFirstIndex,
                isScrolledAway = scrolledAway,
            )
            if (pruned != scrollState.unseenMessageIds) {
                scrollState = scrollState.copy(unseenMessageIds = pruned)
            }
        }
    }

    return ChatNewMessageScrollController(
        unseenCount = scrollState.unseenMessageIds.size,
        isAutoScrolling = isAutoScrolling,
    )
}

/**
 * A small floating button that appears while a reversed conversation list is scrolled away from
 * its newest message and is being scrolled (or just was), or while unread incoming messages
 * await below. It scrolls back down in one quick, decelerating motion.
 */
@Composable
internal fun ChatScrollToLatestButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    newMessageCount: Int = 0,
    isAutoScrolling: () -> Boolean = { false },
) {
    val scope = rememberCoroutineScope()
    val currentNewMessageCount by rememberUpdatedState(newMessageCount)
    val currentIsAutoScrolling by rememberUpdatedState(isAutoScrolling)
    // Shown while the list moves, for a moment after it stops, or while new messages wait below.
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
            if (currentIsAutoScrolling()) {
                recentlyScrolled = false
                return@collectLatest
            }
            recentlyScrolled = true
            if (!scrolling) {
                delay(CHAT_SCROLL_TO_LATEST_LINGER_MILLIS)
                recentlyScrolled = false
            }
        }
    }
    val visible by remember(listState) {
        derivedStateOf {
            !currentIsAutoScrolling() &&
                listState.isScrolledAwayFromLatest() &&
                (recentlyScrolled || currentNewMessageCount > 0)
        }
    }
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
        val newMessagesDescription = if (newMessageCount > 0) {
            quantityNumberSentenceResource(
                count = newMessageCount,
                oneBeforeRes = R.string.chat_scroll_to_latest_new_messages_singular_before_number,
                oneAfterRes = R.string.chat_scroll_to_latest_new_messages_singular_after_number,
                otherBeforeRes = R.string.chat_scroll_to_latest_new_messages_plural_before_number,
                otherAfterRes = R.string.chat_scroll_to_latest_new_messages_plural_after_number,
            )
        } else {
            null
        }
        BadgedBox(
            badge = {
                if (newMessageCount > 0) {
                    val badgeText = if (newMessageCount > 99) {
                        numberSentenceResource(
                            R.string.main_count_badge_cap_before_number,
                            formatLocalizedInt(99),
                            R.string.main_count_badge_cap_after_number,
                        )
                    } else {
                        formatLocalizedInt(newMessageCount)
                    }
                    Badge(Modifier.testTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG)) {
                        Text(badgeText)
                    }
                }
            },
        ) {
            FilledIconButton(
                onClick = { scope.launch { listState.animateScrollToLatest() } },
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
                modifier = Modifier
                    .testTag(CHAT_SCROLL_TO_LATEST_TAG)
                    .semantics {
                        if (newMessagesDescription != null) {
                            stateDescription = newMessagesDescription
                        }
                    },
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_latest),
                )
            }
        }
    }
}

/** Index zero is the newest message in the reversed conversation list. */
internal fun LazyListState.isScrolledAwayFromLatest(): Boolean =
    firstVisibleItemIndex > 0 ||
        firstVisibleItemScrollOffset > layoutInfo.viewportSize.height / 3

/** Only visible rows have measured heights. Recalculate the distance as new rows enter the viewport. */
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
            // Limit the last step when newly measured rows change the estimate.
            val rawShare = if (previousProgress >= 1f) 1f else (progress - previousProgress) / (1f - previousProgress)
            val share = rawShare.coerceAtMost(MAX_FRAME_SHARE)
            previousProgress = progress
            val consumed = -scrollBy(-(remaining * share).coerceAtLeast(1f).coerceAtMost(remaining))
            if (consumed <= 0f) break
        }
    }
    if (canScrollBackward) scrollToItem(0)
}

private fun LazyListState.estimatedDistanceToLatest(): Float {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    val averageSize = visible.sumOf { it.size } / visible.size.toFloat()
    return firstVisibleItemIndex * averageSize + firstVisibleItemScrollOffset
}
