package org.aralozkaya.msngr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.aralozkaya.msngr.data.ChatMessage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val CHAT_STICKY_DAY_TAG = "chat_sticky_day"
internal const val CHAT_STICKY_DAY_TIMEOUT_MILLIS = 2_000L
internal val ChatDayChipSpacing = 8.dp

internal sealed interface ChatTimelineItem {
    val dayMillis: Long
    val key: Any

    data class Message(val message: ChatMessage, override val dayMillis: Long) : ChatTimelineItem {
        override val key: Any get() = message.id
    }

    data class DayDivider(override val dayMillis: Long, val firstMessageId: Long) : ChatTimelineItem {
        override val key: Any get() = "day:$firstMessageId"
    }
}

internal fun chatDayStart(timestampMillis: Long, timeZone: TimeZone): Long =
    Calendar.getInstance(timeZone).apply {
        timeInMillis = timestampMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Newest first, with each divider above its day's oldest message in the reversed list. */
internal fun chatTimeline(messages: List<ChatMessage>, timeZone: TimeZone): List<ChatTimelineItem> {
    val datedMessages = messages.map {
        ChatTimelineItem.Message(it, chatDayStart(it.timestampMillis, timeZone))
    }
    return buildList {
        for (index in datedMessages.indices.reversed()) {
            val item = datedMessages[index]
            add(item)
            if (datedMessages.getOrNull(index - 1)?.dayMillis != item.dayMillis) {
                add(ChatTimelineItem.DayDivider(item.dayMillis, item.message.id))
            }
        }
    }
}

internal class ChatDayLabels(
    nowMillis: Long,
    val timeZone: TimeZone,
    locale: Locale,
    private val todayLabel: String,
) {
    private val today = chatDayStart(nowMillis, timeZone)
    private val recentStart = chatDayStart(
        Calendar.getInstance(timeZone).apply {
            timeInMillis = today
            add(Calendar.DATE, -6)
        }.timeInMillis,
        timeZone,
    )
    private val weekday = SimpleDateFormat("EEEE", locale).apply { this.timeZone = timeZone }
    private val fullDate = DateFormat.getDateInstance(DateFormat.LONG, locale).apply { this.timeZone = timeZone }

    fun label(dayMillis: Long): String = when {
        dayMillis == today -> todayLabel
        dayMillis in recentStart until today -> weekday.format(Date(dayMillis))
        else -> fullDate.format(Date(dayMillis))
    }
}

@Composable
internal fun rememberChatDayLabels(): ChatDayLabels {
    // Refresh at midnight, and also notice clock/time-zone changes while a chat stays open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val currentTime = System.currentTimeMillis()
            value = currentTime
            val calendar = Calendar.getInstance().apply {
                timeInMillis = currentTime
                add(Calendar.DATE, 1)
            }
            val nextDay = chatDayStart(calendar.timeInMillis, calendar.timeZone)
            delay((nextDay - currentTime).coerceIn(1L, 60_000L))
        }
    }
    val timeZone = TimeZone.getDefault()
    val today = chatDayStart(now, timeZone)
    val locale = LocalConfiguration.current.locales[0]
    val todayLabel = stringResource(R.string.chat_day_today)
    return remember(today, timeZone, locale, todayLabel) {
        ChatDayLabels(now, timeZone, locale, todayLabel)
    }
}

@Composable
internal fun ChatDayChip(label: String, modifier: Modifier = Modifier, translucent: Boolean = false) {
    // Keep the floating label transparent to touch so a drag can start over the chip.
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (translucent) 0.85f else 1f),
                CircleShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

internal data class PinnedChatDay(val dayMillis: Long, val pushOffset: Int)

internal fun pinnedChatDay(
    timeline: List<ChatTimelineItem>,
    layout: LazyListLayoutInfo,
    spacing: Int,
    chipHeight: Int,
): PinnedChatDay? {
    // Item offsets run from the bottom in reverseLayout. Convert to visual top coordinates.
    fun top(offset: Int, size: Int) = layout.viewportEndOffset - offset - size
    val first = layout.visibleItemsInfo
        .filter { it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
        .maxByOrNull { it.index } ?: return null
    val item = timeline.getOrNull(first.index)?.takeIf { it.key == first.key } ?: return null
    // Let an inline chip occupy its normal position until it reaches the pin position.
    if (item is ChatTimelineItem.DayDivider && top(first.offset, first.size) >= 0) return null

    val nextDividerTop = layout.visibleItemsInfo.mapNotNull { visible ->
        val divider = timeline.getOrNull(visible.index) as? ChatTimelineItem.DayDivider
        if (divider == null || divider.key != visible.key || visible.index >= first.index) null
        else top(visible.offset, visible.size) + spacing
    }.minOrNull()
    val pushOffset = nextDividerTop?.let { (it - spacing - chipHeight - spacing).coerceAtMost(0) } ?: 0
    return PinnedChatDay(item.dayMillis, pushOffset)
}

@Composable
internal fun rememberChatStickyDayAlpha(listState: LazyListState): State<Float> {
    var visible by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                visible = true
            } else {
                delay(CHAT_STICKY_DAY_TIMEOUT_MILLIS)
                visible = false
            }
        }
    }
    return animateFloatAsState(if (visible) 1f else 0f, label = "Sticky chat day")
}

@Composable
internal fun ChatStickyDay(
    timeline: List<ChatTimelineItem>,
    listState: LazyListState,
    labels: ChatDayLabels,
    alpha: State<Float>,
) {
    val spacing = with(LocalDensity.current) { ChatDayChipSpacing.roundToPx() }
    var chipHeight by remember { mutableIntStateOf(0) }
    val pinned by remember(timeline, listState, spacing) {
        derivedStateOf { pinnedChatDay(timeline, listState.layoutInfo, spacing, chipHeight) }
    }
    val day = pinned
    if (day != null && alpha.value > 0f) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            ChatDayChip(
                label = labels.label(day.dayMillis),
                translucent = true,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, spacing + day.pushOffset) }
                    .graphicsLayer { this.alpha = alpha.value }
                    .onSizeChanged { chipHeight = it.height }
                    .testTag(CHAT_STICKY_DAY_TAG),
            )
        }
    }
}
