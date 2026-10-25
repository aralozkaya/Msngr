package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HandwritingComposerInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun vibrantHorizontalFloatingToolbarSupportsAllSubmenusDrawingUndoRedoAndSending() {
        val draft = mutableStateOf("")
        val messages = mutableStateListOf<ChatMessage>()
        val sentDrawings = mutableListOf<InkDrawing>()

        compose.setContent {
            MsngrTheme {
                ChatScreen(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    contact = Contact("peer", "Peer", "peer@example.com", Presence.Online),
                    messages = messages,
                    isHistoryLoaded = true,
                    canLoadOlderMessages = false,
                    isTyping = false,
                    onBack = {},
                    onSendMessage = { _, _ -> },
                    onRetryMessage = {},
                    onSendTyping = {},
                    onSendNudge = {},
                    onSendInk = { drawing ->
                        sentDrawings += drawing
                        messages += ChatMessage(
                            id = (messages.size + 1).toLong(),
                            contactId = "peer",
                            author = MessageAuthor.Me,
                            text = "Handwriting",
                            timestampMillis = System.currentTimeMillis(),
                            inkDrawing = drawing,
                        )
                    },
                    onLoadOlderMessages = {},
                )
            }
        }

        // Dedicated handwriting button is removed; emoticon button has the 3-dot long-press indicator
        compose.onNodeWithTag(HANDWRITING_MODE_BUTTON_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).assertIsDisplayed()
        compose.onNodeWithTag(EMOTICON_LONG_PRESS_DOTS_TAG, useUnmergedTree = true).assertIsDisplayed()

        // Method 1: Long-press and release on the emoticon button -> menu stays open, then tap Handwriting
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput {
            longClick()
        }
        compose.onNodeWithTag(EMOTICON_MENU_EMOTICONS_ITEM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsDisplayed().performClick()

        compose.onNodeWithTag(HANDWRITING_TOOLBAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsDisplayed().assertIsNotEnabled()

        // Helper to click a toolbar item whether it fits directly in the toolbar or overflowed into the menu
        fun clickToolbarItem(tag: String) {
            val directNodes = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes()
            if (directNodes.isEmpty()) {
                compose.onNodeWithTag(HANDWRITING_OVERFLOW_BUTTON_TAG).assertIsDisplayed().performClick()
            }
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        }

        // Verify default grid is Blank (empty) in the canvas semantics
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertExists()

        // 1. Pen Size submenu: Fine -> Bold -> Normal
        clickToolbarItem(HANDWRITING_PEN_BUTTON_TAG)
        compose.onNodeWithText("Fine").assertIsDisplayed().performClick()
        clickToolbarItem(HANDWRITING_PEN_BUTTON_TAG)
        compose.onNodeWithText("Bold").assertIsDisplayed().performClick()

        // 2. Ink Color submenu: select Purple (overflowed on portrait phone width into the menu)
        clickToolbarItem(HANDWRITING_COLOR_BUTTON_TAG)
        compose.onNodeWithContentDescription("Ink color Purple").assertIsDisplayed().performClick()

        // 3. Grid submenu: verify default is Blank, then Small grid -> Blank -> Medium grid
        compose.onNodeWithTag(HANDWRITING_OVERFLOW_BUTTON_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText("Grid (Blank)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Small grid").assertIsDisplayed().performClick()
        clickToolbarItem(HANDWRITING_GRID_BUTTON_TAG)
        compose.onNodeWithText("Blank").assertIsDisplayed().performClick()
        clickToolbarItem(HANDWRITING_GRID_BUTTON_TAG)
        compose.onNodeWithText("Medium grid").assertIsDisplayed().performClick()

        // Draw a stroke on the canvas
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).performTouchInput {
            swipe(start = Offset(40f, 60f), end = Offset(240f, 110f), durationMillis = 120)
        }
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsEnabled()

        // Undo & Redo
        clickToolbarItem(HANDWRITING_UNDO_BUTTON_TAG)
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsNotEnabled()
        clickToolbarItem(HANDWRITING_REDO_BUTTON_TAG)
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsEnabled()

        // 4. Eraser submenu: Small, Big, Stroke, Clear all
        clickToolbarItem(HANDWRITING_ERASER_BUTTON_TAG)
        compose.onNodeWithText("Small").assertIsDisplayed()
        compose.onNodeWithText("Big").assertIsDisplayed()
        compose.onNodeWithText("Stroke").assertIsDisplayed()
        compose.onNodeWithText("Clear all").assertIsDisplayed().performClick()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsNotEnabled()

        // Undo Clear all so the stroke returns, then send the handwriting message via the paired FAB
        clickToolbarItem(HANDWRITING_UNDO_BUTTON_TAG)
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(1, sentDrawings.size)
            assertEquals(1, sentDrawings.single().strokes.size)
            assertEquals(0x800080, sentDrawings.single().strokes.single().colorRgb)
            assertEquals(200, sentDrawings.single().strokes.single().widthHimetric)
            assertTrue(messages.single().isInk)
        }

        // Verify the rendered ink message canvas is displayed in the chat list
        compose.onNodeWithTag(INK_MESSAGE_CANVAS_TAG).assertIsDisplayed()

        // Switch back to Type mode and verify toolbar animates closed
        compose.onNodeWithTag(HANDWRITING_TYPE_BUTTON_TAG).performClick()
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun groupChatSupportsHoldAndDragEmoticonMenuAndSendingAndReceivingHandwriting() {
        val groupId = "00000000-0000-0000-0009-3ffc6bdd2e50@live.com"
        val draft = mutableStateOf("")
        val messages = mutableStateListOf(
            ChatMessage(
                id = 1L,
                contactId = groupId,
                author = MessageAuthor.Contact,
                text = "Handwriting",
                timestampMillis = System.currentTimeMillis() - 5_000L,
                senderEmail = "mrlistenerr@escargot.chat",
                senderName = "Mr Listener",
                inkDrawing = InkDrawing(
                    canvasWidthPx = 220f,
                    canvasHeightPx = 90f,
                    strokes = listOf(
                        InkStroke(
                            points = listOf(InkPoint(18f, 24f), InkPoint(160f, 68f)),
                            colorRgb = 0x008000,
                            widthHimetric = 100,
                        ),
                    ),
                ),
            ),
        )
        val sentDrawings = mutableListOf<InkDrawing>()

        compose.setContent {
            MsngrTheme {
                ChatScreen(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    contact = Contact(
                        id = groupId,
                        nickname = "test",
                        email = groupId,
                        presence = Presence.Online,
                        isGroup = true,
                        members = listOf(
                            GroupMember("aralozkaya@escargot.chat", "Aral", Presence.Online),
                            GroupMember("mrlistenerr@escargot.chat", "Mr Listener", Presence.Online),
                        ),
                    ),
                    messages = messages,
                    isHistoryLoaded = true,
                    canLoadOlderMessages = false,
                    isTyping = false,
                    onBack = {},
                    onSendMessage = { _, _ -> },
                    onRetryMessage = {},
                    onSendTyping = {},
                    onSendNudge = {},
                    onSendInk = { drawing ->
                        sentDrawings += drawing
                        messages += ChatMessage(
                            id = (messages.size + 1).toLong(),
                            contactId = groupId,
                            author = MessageAuthor.Me,
                            text = "Handwriting",
                            timestampMillis = System.currentTimeMillis(),
                            inkDrawing = drawing,
                        )
                    },
                    onLoadOlderMessages = {},
                )
            }
        }

        // Verify incoming group handwriting displays the sender's name and canvas
        compose.onNodeWithText("Mr Listener").assertIsDisplayed()
        compose.onNodeWithTag(INK_MESSAGE_CANVAS_TAG).assertIsDisplayed()

        // Test hold-and-drag in one continuous touch gesture:
        // 1. Touch down on EMOTICON_MODE_BUTTON_TAG and hold past longPressTimeoutMillis
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, 1f))
        }
        compose.waitForIdle()
        // Menu is now open while finger is still held down! Find the Handwriting item's screen position relative to the button
        val buttonNode = compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).fetchSemanticsNode()
        val buttonScreenCenter = buttonNode.layoutInfo.coordinates.localToScreen(
            Offset(buttonNode.size.width / 2f, buttonNode.size.height / 2f),
        )
        val handwritingItemNode = compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).fetchSemanticsNode()
        val handwritingItemScreenCenter = handwritingItemNode.layoutInfo.coordinates.localToScreen(
            Offset(handwritingItemNode.size.width / 2f, handwritingItemNode.size.height / 2f),
        )
        val delta = handwritingItemScreenCenter - buttonScreenCenter

        // 2. While still holding, drag finger to the Handwriting option and release!
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput {
            moveBy(Offset(delta.x, delta.y - 1f))
            advanceEventTime(50L)
            up()
        }

        // Handwriting toolbar & canvas open automatically from the hold-and-drag release!
        compose.onNodeWithTag(HANDWRITING_TOOLBAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).performTouchInput {
            swipe(start = Offset(30f, 50f), end = Offset(200f, 95f), durationMillis = 120)
        }
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(1, sentDrawings.size)
            assertEquals(2, messages.size)
            assertTrue(messages.last().isInk)
        }
    }

    @Test
    fun narrowWidthAutomaticallyPutsOverflowToolbarItemsIntoMenuWhileKeepingFabOutside() {
        val state = HandwritingCanvasState()
        val sentDrawings = mutableListOf<InkDrawing>()

        compose.setContent {
            MsngrTheme {
                Box(modifier = Modifier.width(260.dp)) {
                    HandwritingComposerPanel(
                        state = state,
                        enabled = true,
                        onSendInk = { sentDrawings += it },
                        onSwitchToTypeMode = {},
                    )
                }
            }
        }

        // The paired Send FAB stays outside the overflow menu even on a narrow width
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsDisplayed()
        // The overflow button appears in the toolbar because all 7 items do not fit in 260dp
        compose.onNodeWithTag(HANDWRITING_OVERFLOW_BUTTON_TAG).assertIsDisplayed()
        // Grid button is in the overflow menu, not directly in the toolbar
        compose.onNodeWithTag(HANDWRITING_GRID_BUTTON_TAG).assertDoesNotExist()

        // Tapping the overflow button opens the Material 3 Expressive menu with the overflowed items
        compose.onNodeWithTag(HANDWRITING_OVERFLOW_BUTTON_TAG).performClick()
        compose.onNodeWithTag(HANDWRITING_GRID_BUTTON_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText("Small grid").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(WlmGridStyle.SmallGrid, state.gridStyle)
        }
    }
}
