package org.aralozkaya.msngr

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ChatMessageDaysTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun todayAndExactlyThePreviousSixCalendarDaysUseRelativeLabels() {
        val labels = ChatDayLabels(at(2026, 10, 26), utc, Locale.US, "Today")
        assertEquals("Today", labels.label(at(2026, 10, 26, 0)))
        listOf("Sunday", "Saturday", "Friday", "Thursday", "Wednesday", "Tuesday").forEachIndexed { index, weekday ->
            assertEquals(weekday, labels.label(at(2026, 10, 25 - index, 0)))
        }
        assertEquals("October 19, 2026", labels.label(at(2026, 10, 19, 0)))
        assertEquals("October 27, 2026", labels.label(at(2026, 10, 27, 0)))
    }

    @Test fun fullDateIncludesTheYearAndWeekdaysFollowTheLocale() {
        val labels = ChatDayLabels(at(2026, 1, 2), utc, Locale.US, "Today")
        assertEquals("Wednesday", labels.label(at(2025, 12, 31, 0)))
        assertEquals("December 26, 2025", labels.label(at(2025, 12, 26, 0)))
        val french = ChatDayLabels(at(2026, 10, 26), utc, Locale.FRANCE, "Aujourd’hui")
        assertEquals("Aujourd’hui", french.label(at(2026, 10, 26, 0)))
        assertEquals("dimanche", french.label(at(2026, 10, 25, 0)))
        assertEquals("19 octobre 2026", french.label(at(2026, 10, 19, 0)))
    }

    @Test fun sixDayWindowUsesCalendarDaysAcrossBothDaylightSavingChanges() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val spring = ChatDayLabels(at(2026, 3, 11, zone = zone), zone, Locale.US, "Today")
        assertEquals("Thursday", spring.label(at(2026, 3, 5, 0, zone = zone)))
        assertEquals("March 4, 2026", spring.label(at(2026, 3, 4, 0, zone = zone)))
        val autumn = ChatDayLabels(at(2026, 11, 4, zone = zone), zone, Locale.US, "Today")
        assertEquals("Thursday", autumn.label(at(2026, 10, 29, 0, zone = zone)))
        assertEquals("October 28, 2026", autumn.label(at(2026, 10, 28, 0, zone = zone)))
    }

    @Test fun sixDayWindowIncludesItsOldestDayWhenTodayHasNoMidnight() {
        val zone = TimeZone.getTimeZone("America/Sao_Paulo")
        val labels = ChatDayLabels(at(2018, 11, 4, zone = zone), zone, Locale.US, "Today")
        assertEquals("Today", labels.label(chatDayStart(at(2018, 11, 4, zone = zone), zone)))
        assertEquals("Monday", labels.label(at(2018, 10, 29, 0, zone = zone)))
        assertEquals("October 28, 2018", labels.label(at(2018, 10, 28, 0, zone = zone)))
    }

    @Test fun timelineUsesLocalMidnightAndKeepsNewestMessageAtIndexZero() {
        val zone = TimeZone.getTimeZone("Europe/Istanbul")
        val messages = listOf(
            message(1, at(2026, 10, 25, 20, 59)),
            message(2, at(2026, 10, 25, 21, 1)),
            message(3, at(2026, 10, 25, 22)),
        )
        val timeline = chatTimeline(messages, zone)
        assertEquals(listOf(3L, 2L, "day:2", 1L, "day:1"), timeline.map { it.key })
        assertEquals(
            listOf(at(2026, 10, 26, 0, zone = zone), at(2026, 10, 25, 0, zone = zone)),
            timeline.filterIsInstance<ChatTimelineItem.DayDivider>().map { it.dayMillis },
        )
        assertTrue(chatTimeline(emptyList(), zone).isEmpty())
    }

    @Test fun paginationKeepsMessageKeysStableAndDoesNotAddAnotherDividerForTheSameDay() {
        val first = listOf(message(2, at(2026, 10, 26, 12)), message(3, at(2026, 10, 26, 13)))
        val timeline = chatTimeline(listOf(message(1, at(2026, 10, 26, 11))) + first, utc)
        assertEquals(listOf(3L, 2L, 1L), timeline.filterIsInstance<ChatTimelineItem.Message>().map { it.key })
        assertEquals(1, timeline.filterIsInstance<ChatTimelineItem.DayDivider>().size)
    }

    @Test fun inlineDayChipRemainsInPlaceUntilItPassesTheTop() {
        val timeline = fixtureTimeline()
        assertNull(pin(timeline, row(timeline, 5, top = 14, height = 44), row(timeline, 4, top = 58, height = 100)))
        assertNull(pin(timeline, row(timeline, 5, top = 0, height = 44), row(timeline, 4, top = 44, height = 100)))
        assertEquals(PinnedChatDay(10, 0), pin(timeline, row(timeline, 5, top = -1, height = 44), row(timeline, 4, top = 43, height = 100)))
    }

    @Test fun topVisibleDayPinsAndTheNextChipPushesItOffBeforeReplacingIt() {
        val timeline = fixtureTimeline()
        assertEquals(PinnedChatDay(10, 0), pin(timeline, row(timeline, 4, -20, 100), row(timeline, 3, 80, 44)))
        assertEquals(PinnedChatDay(10, -16), pin(timeline, row(timeline, 4, -80, 100), row(timeline, 3, 20, 44)))
        assertEquals(PinnedChatDay(20, 0), pin(timeline, row(timeline, 4, -101, 100), row(timeline, 3, -1, 44), row(timeline, 2, 43, 100)))
        assertEquals(PinnedChatDay(30, 0), pin(timeline, row(timeline, 0, -100, 800)))
    }

    @Test fun emptyOrStaleLayoutsCannotPinAnIncorrectDay() {
        val timeline = fixtureTimeline()
        assertNull(pin(timeline))
        assertNull(pin(timeline, row(timeline, 4, -20, 100).copy(key = "old-conversation")))
        assertNull(pin(emptyList(), row(timeline, 4, -20, 100)))
    }

    private fun fixtureTimeline(): List<ChatTimelineItem> = listOf(
        ChatTimelineItem.Message(message(3, 30), 30), ChatTimelineItem.DayDivider(30, 3),
        ChatTimelineItem.Message(message(2, 20), 20), ChatTimelineItem.DayDivider(20, 2),
        ChatTimelineItem.Message(message(1, 10), 10), ChatTimelineItem.DayDivider(10, 1),
    )

    // The viewport has 78 px of bottom padding, like a chat with a composer.
    private fun row(timeline: List<ChatTimelineItem>, index: Int, top: Int, height: Int) =
        VisibleRow(index, timeline[index].key, offset = 322 - top - height, size = height)

    private data class VisibleRow(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo

    private fun pin(timeline: List<ChatTimelineItem>, vararg rows: VisibleRow): PinnedChatDay? =
        pinnedChatDay(timeline, object : LazyListLayoutInfo {
            override val visibleItemsInfo = rows.toList()
            override val viewportStartOffset = -78
            override val viewportEndOffset = 322
            override val totalItemsCount = timeline.size
        }, spacing = 8, chipHeight = 28)

    private fun message(id: Long, timestamp: Long) = ChatMessage(id, "chat", MessageAuthor.Contact, "Message $id", timestamp)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0, zone: TimeZone = utc): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis
}
