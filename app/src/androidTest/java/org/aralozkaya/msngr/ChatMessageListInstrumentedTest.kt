package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class ChatMessageListInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longHistoryStartsAtNewestMessageOnFirstLayout() {
        assertHistoryStartsAtBottom(delayed = false)
    }

    @Test
    fun historyArrivingAfterEmptyCompositionStartsAtNewestMessageOnFirstLayout() {
        assertHistoryStartsAtBottom(delayed = true)
    }

    @Test
    fun timelineTracksInPlaceMessageAdditionsEditsAndRemoval() {
        val messages = mutableStateListOf<ChatMessage>()
        val now = System.currentTimeMillis()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = messages,
                        listState = rememberLazyListState(),
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(),
                    )
                }
            }
        }

        compose.runOnIdle { messages += message(1).copy(timestampMillis = now) }
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()

        // Same-size edits must refresh both the bubble and its day divider.
        compose.runOnIdle { messages[0] = messages[0].copy(text = "Edited message", timestampMillis = 0L) }
        compose.onNodeWithText("Edited message").assertIsDisplayed()
        compose.onNodeWithText("Message 1").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()

        compose.runOnIdle { messages += message(2).copy(timestampMillis = now) }
        compose.onNodeWithText("Message 2").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()

        compose.runOnIdle { messages.removeAt(0) }
        compose.onNodeWithText("Edited message").assertDoesNotExist()
        compose.onNodeWithText("Message 2").assertIsDisplayed()

        compose.runOnIdle { messages.clear() }
        compose.onNodeWithText("Message 2").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.empty_conversation_warning)).assertIsDisplayed()
    }

    @Test
    fun scrollToLatestButtonShowsWhileScrollingThenGlidesToNewestMessage() {
        val history = (0L..999L).map { index ->
            if (index >= 990L) {
                message(index).copy(text = "Message $index\nLine 2\nLine 3\nLine 4")
            } else {
                message(index)
            }
        }
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = history,
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Message 999", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()

        compose.runOnIdle { scope.launch { state.scrollToItem(400) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // Scrolling away shows the button, and it lingers briefly once the list stops.
        compose.runOnIdle { scope.launch { state.scrollBy(10f) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(CHAT_SCROLL_TO_LATEST_LINGER_MILLIS / 2)
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(CHAT_SCROLL_TO_LATEST_LINGER_MILLIS)
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()
        compose.runOnIdle { assertTrue(state.firstVisibleItemIndex > 300) }

        compose.runOnIdle { scope.launch { state.scrollBy(-10f) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            // Still gliding: neither at the start position nor already at the newest message.
            assertTrue(state.firstVisibleItemIndex in 1 until 400)
        }
        // Step frame by frame: once the newest message is in view the glide must keep shrinking
        // the remaining distance in small steps and land exactly, without a final correction.
        var previousOffset = Int.MAX_VALUE
        var lastStep = 0
        var frames = 0
        while (frames++ < 600) {
            compose.mainClock.advanceTimeByFrame()
            val (index, offset) = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            if (index == 0) {
                assertTrue("Offset grew from $previousOffset to $offset", offset <= previousOffset)
                if (previousOffset != Int.MAX_VALUE) lastStep = previousOffset - offset
                previousOffset = offset
                if (offset == 0) break
            }
        }
        assertEquals(0, compose.runOnIdle { state.firstVisibleItemScrollOffset })
        val viewportHeight = compose.runOnIdle { state.layoutInfo.viewportSize.height }
        assertTrue("Final step jumped by $lastStep px", lastStep in 0..viewportHeight / 8)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }
        compose.onNodeWithText("Message 999", substring = true).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
    private fun assertHistoryStartsAtBottom(delayed: Boolean) {
        val history = (0L..999L).map { message(it) }
        val messages = mutableStateOf(if (delayed) emptyList() else history)
        val layouts = mutableListOf<List<Any>>()
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = messages.value,
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                        modifier = Modifier.onGloballyPositioned {
                            val keys = state.layoutInfo.visibleItemsInfo.map { it.key }
                            if (keys.any { it is Long }) layouts += keys
                        },
                    )
                }
            }
        }
        if (delayed) compose.runOnIdle { messages.value = history }
        compose.onNodeWithText("Message 999").assertIsDisplayed()
        compose.runOnIdle {
            // Inspect the first populated layout, not just the eventually settled UI.
            assertTrue(layouts.isNotEmpty())
            assertTrue(layouts.first().contains(999L))
            assertTrue(!layouts.first().contains(0L))
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun tallLatestMessageStaysAtBottomWhenViewportShrinks() {
        val height = mutableStateOf(400.dp)
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.height(height.value)) {
                    ChatMessageList(
                        messages = listOf(message(0), message(1).copy(text = "Tall message\n".repeat(80))),
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        compose.runOnIdle { height.value = 220.dp }
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals(1L, state.layoutInfo.visibleItemsInfo.first().key)
        }
    }

    @Test
    fun shortConversationKeepsChronologicalOrderAtTop() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(500.dp)) {
                    ChatMessageList(
                        messages = listOf(message(0), message(1)),
                        listState = rememberLazyListState(),
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        val older = compose.onNodeWithText("Message 0").fetchSemanticsNode().boundsInRoot
        val newer = compose.onNodeWithText("Message 1").fetchSemanticsNode().boundsInRoot
        val dateLabel = DateFormat.getDateInstance(DateFormat.LONG, compose.activity.resources.configuration.locales[0])
            .format(Date(0L))
        val divider = compose.onNodeWithText(dateLabel).fetchSemanticsNode().boundsInRoot
        assertTrue(divider.bottom <= older.top)
        assertTrue(older.bottom <= newer.top)
        assertTrue(divider.top < with(compose.density) { 48.dp.toPx() })
    }

    @Test fun regularChatSeparatesMessagesAcrossMidnight() = assertDayDividers(isGroup = false)

    @Test fun groupChatSeparatesMessagesAndSenderGroupsAcrossMidnight() = assertDayDividers(isGroup = true)

    private fun assertDayDividers(isGroup: Boolean) {
        val zone = TimeZone.getDefault()
        val midnight = chatDayStart(System.currentTimeMillis(), zone)
        val yesterday = ChatDayLabels(
            midnight, zone, compose.activity.resources.configuration.locales[0], "Today",
        ).label(chatDayStart(midnight - 60_000, zone))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(500.dp)) {
                    ChatMessageList(
                        messages = listOf(
                            message(1).copy(timestampMillis = midnight - 60_000, senderName = "Alice"),
                            message(2).copy(timestampMillis = midnight + 60_000, senderName = "Alice"),
                        ),
                        listState = rememberLazyListState(),
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(),
                        isGroup = isGroup,
                    )
                }
            }
        }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText(yesterday).assertIsDisplayed()
        compose.onAllNodesWithText("Alice").assertCountEquals(if (isGroup) 2 else 0)
        val firstDivider = compose.onNodeWithText(yesterday).fetchSemanticsNode().boundsInRoot
        val firstMessage = compose.onNodeWithText("Message 1").fetchSemanticsNode().boundsInRoot
        val secondDivider = compose.onNodeWithText("Today").fetchSemanticsNode().boundsInRoot
        val secondMessage = compose.onNodeWithText("Message 2").fetchSemanticsNode().boundsInRoot
        assertTrue(firstDivider.bottom <= firstMessage.top)
        assertTrue(firstMessage.bottom <= secondDivider.top)
        assertTrue(secondDivider.bottom <= secondMessage.top)
    }

    @Test fun regularChatStickyDayHidesAfterIdleAndReturnsOnScroll() = assertStickyDayTimeout(isGroup = false)

    @Test fun groupChatStickyDayHidesAfterIdleAndReturnsOnScroll() = assertStickyDayTimeout(isGroup = true)

    private fun assertStickyDayTimeout(isGroup: Boolean) {
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        val now = Calendar.getInstance().timeInMillis
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = (0L..99L).map { message(it).copy(timestampMillis = now) },
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(top = 64.dp, bottom = 64.dp),
                        isGroup = isGroup,
                    )
                }
            }
        }
        compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        var scroll: Job? = null
        fun startScroll() {
            compose.runOnIdle {
                scroll = scope.launch {
                    // Keep an actual list scroll active so the idle timer cannot start early.
                    state.scroll {
                        scrollBy(80f)
                        awaitCancellation()
                    }
                }
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).assertIsDisplayed()
        }
        startScroll()
        val chip = compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 72.dp.toPx() }, chip.top, 1f)
        compose.runOnIdle { scroll?.cancel() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).assertIsDisplayed()
        startScroll()
        compose.runOnIdle { scroll?.cancel() }
        compose.mainClock.advanceTimeBy(1_300)
        compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(CHAT_STICKY_DAY_TIMEOUT_MILLIS)
        compose.waitForIdle()
        compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).assertDoesNotExist()
        startScroll()
        compose.runOnIdle { scroll?.cancel() }
        compose.mainClock.advanceTimeByFrame()
        val beforeDrag = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        compose.onNodeWithTag(CHAT_STICKY_DAY_TAG).performTouchInput {
            swipe(center, center + Offset(0f, 200f), durationMillis = 200)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertTrue(beforeDrag != (state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset))
        }
    }

    @Test
    fun topBarStaysDistinctAtBottomAndOnlyBlendsAtVisualTop() {
        val messages = mutableStateOf((0L..99L).map { message(it) })
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        var barColor = Color.Unspecified
        var surface = Color.Unspecified
        var scrolledSurface = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                surface = MaterialTheme.colorScheme.surface
                scrolledSurface = MaterialTheme.colorScheme.surfaceContainer
                barColor = chatTopBarContainerColor(state)
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = messages.value,
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(scrolledSurface, barColor) }
        // Repeat small direction changes and include an overscroll at the bottom.
        repeat(3) {
            compose.runOnIdle { scope.launch { state.scrollBy(40f) } }
            compose.runOnIdle { assertEquals(scrolledSurface, barColor) }
            compose.runOnIdle { scope.launch { state.scrollBy(-80f) } }
            compose.runOnIdle { assertEquals(scrolledSurface, barColor) }
        }
        compose.runOnIdle { scope.launch { state.scrollBy(1_000_000f) } }
        compose.runOnIdle {
            assertTrue(!state.canScrollForward)
            assertEquals(surface, barColor)
        }
        compose.runOnIdle { scope.launch { state.scrollBy(-40f) } }
        compose.runOnIdle { assertEquals(scrolledSurface, barColor) }
        // A conversation that fits entirely in the viewport is also at its top.
        compose.runOnIdle { messages.value = listOf(message(0)) }
        compose.runOnIdle { assertEquals(surface, barColor) }
        compose.runOnIdle { messages.value = emptyList() }
        compose.runOnIdle { assertEquals(surface, barColor) }
    }

    @Test
    fun incomingMessageWhileScrolledUpShowsBadgeOnScrollButtonWithoutAutoscrolling() {
        val messages = mutableStateListOf<ChatMessage>().apply { addAll((0L..999L).map(::message)) }
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = messages,
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        compose.runOnIdle { scope.launch { state.scrollToItem(400) } }
        compose.waitForIdle()

        try {
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(CHAT_SCROLL_TO_LATEST_LINGER_MILLIS + 500L)
            compose.waitForIdle()
            compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()

            val (anchorKeyBefore, indexBefore) = compose.runOnIdle {
                state.layoutInfo.visibleItemsInfo.first().key to state.firstVisibleItemIndex
            }
            compose.runOnIdle { messages += message(1000L) }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(anchorKeyBefore, state.layoutInfo.visibleItemsInfo.first().key)
                assertTrue(state.firstVisibleItemIndex >= indexBefore)
            }
            compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertIsDisplayed()
            assertScrollBadgeText("1")

            compose.mainClock.advanceTimeBy(CHAT_SCROLL_TO_LATEST_LINGER_MILLIS * 2)
            compose.waitForIdle()
            compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertIsDisplayed()
            assertScrollBadgeText("1")

            compose.runOnIdle { messages.addAll(listOf(message(1001L), message(1002L))) }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(anchorKeyBefore, state.layoutInfo.visibleItemsInfo.first().key)
                assertTrue(state.firstVisibleItemIndex >= indexBefore)
            }
            assertScrollBadgeText("3")

            compose.runOnIdle { messages.add(0, message(-1L)) }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(anchorKeyBefore, state.layoutInfo.visibleItemsInfo.first().key)
            }
            assertScrollBadgeText("3")

            compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).performClick()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5_000) {
                state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
            }
            compose.onNodeWithText("Message 1002").assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag(CHAT_SCROLL_TO_LATEST_TAG)).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()
            compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun incomingMessagesAtBottomAutoscrollWithoutShowingBadgeOrButton() {
        val messages = mutableStateListOf<ChatMessage>().apply { addAll((0L..99L).map(::message)) }
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = messages,
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Message 99").assertIsDisplayed()

        compose.runOnIdle { messages += message(100L) }
        compose.waitUntil(5_000) {
            state.firstVisibleItemIndex == 0 &&
                state.firstVisibleItemScrollOffset == 0 &&
                state.layoutInfo.visibleItemsInfo.firstOrNull()?.key == 100L
        }
        compose.onNodeWithText("Message 100").assertIsDisplayed()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { messages += message(101L) }
        compose.waitUntil(5_000) {
            state.firstVisibleItemIndex == 0 &&
                state.firstVisibleItemScrollOffset == 0 &&
                state.layoutInfo.visibleItemsInfo.firstOrNull()?.key == 101L
        }
        compose.onNodeWithText("Message 101").assertIsDisplayed()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun manualScrollDownDecrementsAndClearsBadgeAndOutgoingMessageAutoscrolls() {
        val messages = mutableStateListOf<ChatMessage>().apply { addAll((0L..99L).map(::message)) }
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                Box(Modifier.height(400.dp)) {
                    ChatMessageList(
                        messages = messages,
                        listState = state,
                        emoticonSettings = EmoticonSettings(),
                        innerPadding = PaddingValues(bottom = 64.dp),
                    )
                }
            }
        }
        compose.runOnIdle { scope.launch { state.scrollToItem(40) } }
        compose.waitForIdle()

        compose.runOnIdle { messages.addAll(listOf(message(100L), message(101L))) }
        compose.waitForIdle()
        assertScrollBadgeText("2")

        compose.runOnIdle { scope.launch { state.scrollToItem(1) } }
        compose.waitForIdle()
        compose.onNodeWithText("Message 100").assertIsDisplayed()
        assertScrollBadgeText("1")

        compose.runOnIdle { scope.launch { state.scrollToItem(0, 0) } }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(CHAT_SCROLL_TO_LATEST_TAG)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { scope.launch { state.scrollToItem(40) } }
        compose.waitForIdle()
        compose.runOnIdle { messages += message(102L) }
        compose.waitForIdle()
        assertScrollBadgeText("1")

        compose.runOnIdle {
            messages += ChatMessage(
                id = 103L,
                contactId = "contact",
                author = MessageAuthor.Me,
                text = "Outgoing 103",
                timestampMillis = 103_000L,
            )
        }
        compose.waitUntil(5_000) {
            state.firstVisibleItemIndex == 0 &&
                state.firstVisibleItemScrollOffset == 0 &&
                state.layoutInfo.visibleItemsInfo.firstOrNull()?.key == 103L
        }
        compose.onNodeWithText("Outgoing 103").assertIsDisplayed()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun chatScreenTypingDoesNotAutoscrollWhileScrolledUpAndBadgeCapsAt99Plus() {
        val messages = mutableStateListOf<ChatMessage>().apply { addAll((0L..99L).map(::message)) }
        val isTyping = mutableStateOf(false)
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            MsngrTheme {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                ChatScreen(
                    draft = "",
                    onDraftChange = {},
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = messages,
                    isHistoryLoaded = true,
                    canLoadOlderMessages = false,
                    isTyping = isTyping.value,
                    onBack = {},
                    onSendMessage = { _, _ -> },
                    onRetryMessage = {},
                    onSendTyping = {},
                    onSendNudge = {},
                    onLoadOlderMessages = {},
                    listState = state,
                )
            }
        }
        compose.runOnIdle { scope.launch { state.scrollToItem(40) } }
        compose.waitForIdle()
        val anchorKeyBefore = compose.runOnIdle { state.layoutInfo.visibleItemsInfo.first().key }

        compose.runOnIdle { isTyping.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_typing_status)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(40, state.firstVisibleItemIndex) }

        compose.runOnIdle { isTyping.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(40, state.firstVisibleItemIndex) }

        compose.runOnIdle { messages.addAll((100L..199L).map(::message)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchorKeyBefore, state.layoutInfo.visibleItemsInfo.first().key)
            assertTrue(state.firstVisibleItemIndex >= 40)
        }
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TAG).assertIsDisplayed()
        assertScrollBadgeText("99+")

        val rootBounds = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        val badgeBounds = compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(badgeBounds.left >= rootBounds.left)
        assertTrue(badgeBounds.right <= rootBounds.right)
        assertTrue(badgeBounds.top >= rootBounds.top)
        assertTrue(badgeBounds.bottom <= rootBounds.bottom)
    }

    private fun assertScrollBadgeText(expected: String) {
        compose.onNodeWithTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(
            hasText(expected) and hasAnyAncestor(hasTestTag(CHAT_SCROLL_TO_LATEST_BADGE_TAG)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private fun message(id: Long) = ChatMessage(
        id = id,
        contactId = "contact",
        author = MessageAuthor.Contact,
        text = "Message $id",
        timestampMillis = id * 1_000L,
    )
}
