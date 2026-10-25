package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

        // Switch from Type mode to Handwriting mode
        compose.onNodeWithTag(HANDWRITING_MODE_BUTTON_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(HANDWRITING_TOOLBAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsNotEnabled()

        // 1. Pen Size submenu: Fine -> Bold -> Normal
        compose.onNodeWithTag(HANDWRITING_PEN_BUTTON_TAG).performClick()
        compose.onNodeWithText("Fine").assertIsDisplayed().performClick()
        compose.onNodeWithTag(HANDWRITING_PEN_BUTTON_TAG).performClick()
        compose.onNodeWithText("Bold").assertIsDisplayed().performClick()

        // 2. Ink Color submenu: select Purple
        compose.onNodeWithTag(HANDWRITING_COLOR_BUTTON_TAG).performClick()
        compose.onNodeWithContentDescription("Ink color Purple").assertIsDisplayed().performClick()

        // 3. Grid submenu: Small grid -> Large grid -> Blank -> Medium grid
        compose.onNodeWithTag(HANDWRITING_GRID_BUTTON_TAG).performClick()
        compose.onNodeWithText("Small grid").assertIsDisplayed().performClick()
        compose.onNodeWithTag(HANDWRITING_GRID_BUTTON_TAG).performClick()
        compose.onNodeWithText("Blank").assertIsDisplayed().performClick()
        compose.onNodeWithTag(HANDWRITING_GRID_BUTTON_TAG).performClick()
        compose.onNodeWithText("Medium grid").assertIsDisplayed().performClick()

        // Draw a stroke on the canvas
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).performTouchInput {
            swipe(start = Offset(40f, 60f), end = Offset(240f, 110f), durationMillis = 120)
        }
        compose.onNodeWithTag(HANDWRITING_UNDO_BUTTON_TAG).assertIsEnabled()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsEnabled()

        // Undo & Redo
        compose.onNodeWithTag(HANDWRITING_UNDO_BUTTON_TAG).performClick()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(HANDWRITING_REDO_BUTTON_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsEnabled()

        // 4. Eraser submenu: Small, Big, Stroke, Clear all
        compose.onNodeWithTag(HANDWRITING_ERASER_BUTTON_TAG).performClick()
        compose.onNodeWithText("Small").assertIsDisplayed()
        compose.onNodeWithText("Big").assertIsDisplayed()
        compose.onNodeWithText("Stroke").assertIsDisplayed()
        compose.onNodeWithText("Clear all").assertIsDisplayed().performClick()
        compose.onNodeWithTag(HANDWRITING_SEND_BUTTON_TAG).assertIsNotEnabled()

        // Undo Clear all so the stroke returns, then send the handwriting message
        compose.onNodeWithTag(HANDWRITING_UNDO_BUTTON_TAG).performClick()
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

        // Switch back to Type mode
        compose.onNodeWithTag(HANDWRITING_TYPE_BUTTON_TAG).performClick()
        compose.onNodeWithTag(HANDWRITING_MODE_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun groupChatSupportsSendingAndReceivingHandwritingWithSenderName() {
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

        // Verify Handwriting button is enabled in group chat and can send a drawing
        compose.onNodeWithTag(HANDWRITING_MODE_BUTTON_TAG).assertIsDisplayed().assertIsEnabled().performClick()
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
}
