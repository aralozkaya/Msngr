package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        assertTrue(older.bottom <= newer.top)
        assertTrue(older.top < with(compose.density) { 48.dp.toPx() })
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
