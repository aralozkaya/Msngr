package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ChatScrollToLatestTest {

    @Test
    fun initialPopulationDoesNotBadgeOrAutoscroll() {
        val update = reduceChatNewMessages(
            current = ChatNewMessageScrollState(),
            messages = listOf(message(1L), message(2L)),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertEquals(2L, update.state.lastKnownLatestMessageId)
        assertEquals(emptyList<Long>(), update.state.unseenMessageIds)
        assertFalse(update.shouldAutoScrollToLatest)
    }

    @Test
    fun singleMultipleAndBatchIncomingMessagesWhileScrolledUpIncrementWithoutAutoscrolling() {
        var state = ChatNewMessageScrollState(lastKnownLatestMessageId = 2L)

        val first = reduceChatNewMessages(
            current = state,
            messages = listOf(message(1L), message(2L), message(3L)),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(first.shouldAutoScrollToLatest)
        assertEquals(listOf(3L), first.state.unseenMessageIds)
        state = first.state

        val second = reduceChatNewMessages(
            current = state,
            messages = listOf(message(1L), message(2L), message(3L), message(4L)),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(second.shouldAutoScrollToLatest)
        assertEquals(listOf(3L, 4L), second.state.unseenMessageIds)
        state = second.state

        val batch = reduceChatNewMessages(
            current = state,
            messages = (1L..7L).map(::message),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(batch.shouldAutoScrollToLatest)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), batch.state.unseenMessageIds)
    }

    @Test
    fun nudgesAndSystemMessagesDistinguishIncomingFromOutgoing() {
        assertTrue(message(1L, author = MessageAuthor.Me).isOutgoingForScroll())
        assertFalse(message(2L, author = MessageAuthor.Contact).isOutgoingForScroll())
        assertTrue(message(3L, author = MessageAuthor.System, text = "You sent a nudge.").isOutgoingForScroll())
        assertTrue(message(4L, author = MessageAuthor.System, text = "Photo sharing invitation sent.").isOutgoingForScroll())
        assertTrue(message(5L, author = MessageAuthor.System, text = "You stopped sharing photos.").isOutgoingForScroll())
        assertTrue(message(6L, author = MessageAuthor.System, text = "Game invitation sent: Chess.").isOutgoingForScroll())
        assertTrue(message(7L, author = MessageAuthor.System, text = "You closed Chess.").isOutgoingForScroll())
        assertTrue(message(8L, author = MessageAuthor.System, text = "Nudge could not be sent.").isOutgoingForScroll())
        assertTrue(message(9L, author = MessageAuthor.System, text = "Message could not be sent: timeout").isOutgoingForScroll())

        assertFalse(message(10L, author = MessageAuthor.System, text = "You received a nudge!").isOutgoingForScroll())
        assertFalse(message(11L, author = MessageAuthor.System, text = "Alice sent a nudge!").isOutgoingForScroll())
        assertFalse(message(12L, author = MessageAuthor.System, text = "Photo sharing invitation received.").isOutgoingForScroll())
        assertFalse(message(13L, author = MessageAuthor.System, text = "Game invitation received: Chess.").isOutgoingForScroll())

        val afterIncomingNudge = reduceChatNewMessages(
            current = ChatNewMessageScrollState(lastKnownLatestMessageId = 1L),
            messages = listOf(
                message(1L),
                message(2L, author = MessageAuthor.System, text = "You received a nudge!"),
                message(3L, author = MessageAuthor.System, text = "Alice sent a nudge!"),
                message(4L, author = MessageAuthor.System, text = "Photo sharing invitation received."),
            ),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(afterIncomingNudge.shouldAutoScrollToLatest)
        assertEquals(listOf(2L, 3L, 4L), afterIncomingNudge.state.unseenMessageIds)

        val afterOutgoingNudge = reduceChatNewMessages(
            current = afterIncomingNudge.state,
            messages = listOf(
                message(1L),
                message(2L, author = MessageAuthor.System, text = "You received a nudge!"),
                message(3L, author = MessageAuthor.System, text = "Alice sent a nudge!"),
                message(4L, author = MessageAuthor.System, text = "Photo sharing invitation received."),
                message(5L, author = MessageAuthor.System, text = "You sent a nudge."),
            ),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertTrue(afterOutgoingNudge.shouldAutoScrollToLatest)
        assertEquals(emptyList<Long>(), afterOutgoingNudge.state.unseenMessageIds)

        val afterOutgoingMessage = reduceChatNewMessages(
            current = ChatNewMessageScrollState(lastKnownLatestMessageId = 2L, unseenMessageIds = listOf(2L)),
            messages = listOf(
                message(1L),
                message(2L),
                message(3L, author = MessageAuthor.Me, text = "Reply"),
            ),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertTrue(afterOutgoingMessage.shouldAutoScrollToLatest)
        assertEquals(emptyList<Long>(), afterOutgoingMessage.state.unseenMessageIds)
    }

    @Test
    fun incomingAtBottomOrDuringAutoscrollClearsUnseenAndAutoscrolls() {
        val atBottom = reduceChatNewMessages(
            current = ChatNewMessageScrollState(lastKnownLatestMessageId = 1L),
            messages = listOf(message(1L), message(2L)),
            isHistoryLoaded = true,
            isScrolledAway = false,
            isAutoScrollingToLatest = false,
        )
        assertTrue(atBottom.shouldAutoScrollToLatest)
        assertEquals(emptyList<Long>(), atBottom.state.unseenMessageIds)

        val duringAutoscroll = reduceChatNewMessages(
            current = ChatNewMessageScrollState(lastKnownLatestMessageId = 2L),
            messages = listOf(message(1L), message(2L), message(3L)),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = true,
        )
        assertTrue(duringAutoscroll.shouldAutoScrollToLatest)
        assertEquals(emptyList<Long>(), duringAutoscroll.state.unseenMessageIds)
    }

    @Test
    fun prependingOlderHistoryAndEditingInPlaceDoNotBadgeOrAutoscroll() {
        val initial = ChatNewMessageScrollState(lastKnownLatestMessageId = 10L, unseenMessageIds = listOf(10L))

        val prepended = reduceChatNewMessages(
            current = initial,
            messages = listOf(message(1L), message(2L), message(9L), message(10L)),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(prepended.shouldAutoScrollToLatest)
        assertEquals(10L, prepended.state.lastKnownLatestMessageId)
        assertEquals(listOf(10L), prepended.state.unseenMessageIds)

        val edited = reduceChatNewMessages(
            current = prepended.state,
            messages = listOf(
                message(1L),
                message(2L),
                message(9L),
                message(10L).copy(text = "Edited"),
            ),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(edited.shouldAutoScrollToLatest)
        assertEquals(10L, edited.state.lastKnownLatestMessageId)
        assertEquals(listOf(10L), edited.state.unseenMessageIds)
    }

    @Test
    fun transientEmptyPreservesStateLoadedEmptyResetsAndTruncationPrunesSafely() {
        val populated = ChatNewMessageScrollState(
            lastKnownLatestMessageId = 5L,
            unseenMessageIds = listOf(4L, 5L),
        )

        val transientEmpty = reduceChatNewMessages(
            current = populated,
            messages = emptyList(),
            isHistoryLoaded = false,
            isScrolledAway = false,
            isAutoScrollingToLatest = false,
        )
        assertFalse(transientEmpty.shouldAutoScrollToLatest)
        assertEquals(populated, transientEmpty.state)

        val loadedEmpty = reduceChatNewMessages(
            current = populated,
            messages = emptyList(),
            isHistoryLoaded = true,
            isScrolledAway = false,
            isAutoScrollingToLatest = false,
        )
        assertFalse(loadedEmpty.shouldAutoScrollToLatest)
        assertEquals(ChatNewMessageScrollState(), loadedEmpty.state)

        val truncated = reduceChatNewMessages(
            current = populated,
            messages = listOf(message(1L), message(4L)),
            isHistoryLoaded = true,
            isScrolledAway = true,
            isAutoScrollingToLatest = false,
        )
        assertFalse(truncated.shouldAutoScrollToLatest)
        assertEquals(4L, truncated.state.lastKnownLatestMessageId)
        assertEquals(listOf(4L), truncated.state.unseenMessageIds)
    }

    @Test
    fun pruneVisibleUnseenMessagesHandlesDayDividersTallLatestMessageAndReturnToBottom() {
        val dayMs = 86_400_000L
        val messages = listOf(
            message(1L, timestampMillis = 1_000L),
            message(2L, timestampMillis = dayMs + 1_000L),
            message(3L, timestampMillis = dayMs * 2 + 1_000L),
        )
        val timeline = chatTimeline(messages, TimeZone.getTimeZone("UTC"))
        // Reversed timeline indices:
        // 0: Msg 3, 1: DayDivider(day2), 2: Msg 2, 3: DayDivider(day1), 4: Msg 1, 5: DayDivider(day0)
        assertEquals(6, timeline.size)

        val unseen = listOf(2L, 3L)

        // Still scrolled at Msg 1 (index 4): neither Msg 2 nor Msg 3 is reached.
        assertEquals(
            listOf(2L, 3L),
            pruneVisibleUnseenMessages(unseen, timeline, firstVisibleTimelineIndex = 4, isScrolledAway = true),
        )

        // Scrolled down past DayDivider(day1) so Msg 2 (index 2) is now first visible: Msg 2 clears, Msg 3 stays.
        assertEquals(
            listOf(3L),
            pruneVisibleUnseenMessages(unseen, timeline, firstVisibleTimelineIndex = 2, isScrolledAway = true),
        )

        // Tall newest message at index 0 while still scrolled above its bottom (isScrolledAway = true).
        assertEquals(
            listOf(3L),
            pruneVisibleUnseenMessages(listOf(3L), timeline, firstVisibleTimelineIndex = 0, isScrolledAway = true),
        )

        // Settled at the bottom (isScrolledAway = false): everything clears.
        assertEquals(
            emptyList<Long>(),
            pruneVisibleUnseenMessages(unseen, timeline, firstVisibleTimelineIndex = 0, isScrolledAway = false),
        )
    }

    @Test
    fun wasScrolledAwayBeforeLatestUpdateIsOrderIndependentAcrossMeasureAndDayDividers() {
        val dayMs = 86_400_000L
        val messages = (97L..100L).map { id ->
            val ts = if (id == 100L) dayMs + 1_000L else id * 1_000L
            message(id, timestampMillis = ts)
        }
        // New timeline with 100L across midnight:
        // 0: Msg 100, 1: DayDivider(day1), 2: Msg 99, 3: Msg 98, 4: Msg 97, 5: DayDivider(day0)
        val timelineWithMidnight = chatTimeline(messages, TimeZone.getTimeZone("UTC"))
        val viewportHeight = 900 // threshold = 300

        // User was at bottom (previousLatestMessageId = 99L, off = 0):
        // Pre-measure (firstVisibleItemIndex = 0, key = 99L) -> false
        assertFalse(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 99L,
                timeline = timelineWithMidnight,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemKey = 99L,
                viewportHeight = viewportHeight,
            ),
        )
        // Post-measure across DayDivider (firstVisibleItemIndex = 2, key = 99L) -> false
        assertFalse(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 99L,
                timeline = timelineWithMidnight,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemKey = 99L,
                viewportHeight = viewportHeight,
            ),
        )
        // Batch arrival (previousLatestMessageId = 98L, post-measure index = 3, key = 98L) -> false
        assertFalse(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 98L,
                timeline = timelineWithMidnight,
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemKey = 98L,
                viewportHeight = viewportHeight,
            ),
        )
        // User scrolled up slightly within viewportHeight / 3 -> false
        assertFalse(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 99L,
                timeline = timelineWithMidnight,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 250,
                firstVisibleItemKey = 99L,
                viewportHeight = viewportHeight,
            ),
        )
        // User scrolled up > viewportHeight / 3 into tall 99L -> true
        assertTrue(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 99L,
                timeline = timelineWithMidnight,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 301,
                firstVisibleItemKey = 99L,
                viewportHeight = viewportHeight,
            ),
        )
        // User scrolled up 1+ messages (anchor is 98L at index 3 > index 2 of 99L) -> true
        assertTrue(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 99L,
                timeline = timelineWithMidnight,
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemKey = 98L,
                viewportHeight = viewportHeight,
            ),
        )
        // Pre-measure scrolled up 1+ messages (key = 98L) -> true
        assertTrue(
            wasScrolledAwayBeforeLatestUpdate(
                previousLatestMessageId = 99L,
                timeline = chatTimeline(messages.dropLast(1), TimeZone.getTimeZone("UTC")),
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                firstVisibleItemKey = 98L,
                viewportHeight = viewportHeight,
            ),
        )
    }

    private fun message(
        id: Long,
        author: MessageAuthor = MessageAuthor.Contact,
        text: String = "Message $id",
        timestampMillis: Long = id * 1_000L,
    ) = ChatMessage(
        id = id,
        contactId = "contact",
        author = author,
        text = text,
        timestampMillis = timestampMillis,
    )
}
