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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
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

    private fun message(id: Long) = ChatMessage(
        id = id,
        contactId = "contact",
        author = MessageAuthor.Contact,
        text = "Message $id",
        timestampMillis = id * 1_000L,
    )
}
