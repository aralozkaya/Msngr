package org.aralozkaya.msngr

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageInteractionsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outgoingStatusChangesAndErrorOpensOnTap() {
        val message = mutableStateOf(
            ChatMessage(1L, "contact", MessageAuthor.Me, "Hello", 0L, isSending = true),
        )
        var retryCount = 0
        compose.setContent {
            MaterialTheme {
                MessageBubble(message.value, useOriginalEmoticons = true, onRetry = {
                    retryCount++
                    message.value = message.value.copy(isSending = true, deliveryError = null)
                })
            }
        }
        compose.onNodeWithContentDescription("Sending").assertIsDisplayed()
        compose.runOnIdle { message.value = message.value.copy(isSending = false) }
        compose.onNodeWithContentDescription("Sending").assertDoesNotExist()
        compose.onNodeWithContentDescription("Message not sent").assertDoesNotExist()
        compose.runOnIdle { message.value = message.value.copy(deliveryError = "Server rejected the message") }
        compose.onNodeWithText("Hello").assertIsDisplayed()
        compose.onNodeWithText("Server rejected the message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Message not sent").performClick()
        compose.onNodeWithText("Server rejected the message").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Server rejected the message").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, retryCount) }
        compose.onNodeWithContentDescription("Message not sent").performClick()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Server rejected the message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sending").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message not sent").assertDoesNotExist()
        compose.onNodeWithText("Hello").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retryCount) }
    }

    private val openedUrls = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { openedUrls += uri }
    }

    @Test
    fun detectsMultipleWebLinksWithoutSurroundingPunctuationOrEmailAddresses() {
        val text = "See https://example.com/path?q=1&v=2, then www.example.org. me@example.net"
        val links = findChatMessageLinks(text)
        assertEquals(listOf("https://example.com/path?q=1&v=2", "http://www.example.org"),
            links.map { it.url })
        assertEquals(listOf("https://example.com/path?q=1&v=2", "www.example.org"),
            links.map { text.substring(it.start, it.end) })
    }

    @Test
    fun linksPreserveTheirTextAndOffsetsAfterFormattingAndUnicodeEmoticons() {
        val url = "https://example.com/:D?q=1"
        compose.setContent {
            MaterialTheme {
                MsnEmoticonText(
                    text = ":-) ${MessengerPlusTextCodec.BOLD_CONTROL}$url${MessengerPlusTextCodec.BOLD_CONTROL}",
                    useOriginalEmoticons = false,
                    messengerPlusEnabled = true,
                    linkify = true,
                )
            }
        }
        val node = compose.onNodeWithText(url, substring = true)
        val annotated = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(url, annotated.text.substring(link.start, link.end))
        assertEquals(url, (link.item as LinkAnnotation.Url).url)
        assertEquals(TextDecoration.Underline, link.item.styles?.style?.textDecoration)
        assertTrue(link.start < ":-) ".length)
    }

    @Test
    fun tappingLinkOpensItAndHoldingSameLinkCopiesWholeMessage() {
        val url = "https://example.com"
        val text = "Read $url now"
        showMessages(listOf(MessageAuthor.Contact to text))
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val linkPosition = results.single().getBoundingBox(text.indexOf(url) + 2).center
        node.performTouchInput { click(linkPosition) }
        compose.runOnIdle { assertEquals(listOf(url), openedUrls) }
        node.performTouchInput { longClick(linkPosition) }
        compose.runOnIdle {
            assertEquals(text, clipboardText())
            assertEquals(listOf(url), openedUrls)
        }
    }

    @Test
    fun holdingIncomingAndOutgoingBubblesCopiesTextWithoutTimestampOrFormatting() {
        val bold = MessengerPlusTextCodec.BOLD_CONTROL
        showMessages(listOf(
            MessageAuthor.Me to "${bold}Sent message${bold}",
            MessageAuthor.Contact to "Received message :-)",
        ))
        for (expected in listOf("Sent message", "Received message :-)")) {
            compose.onNodeWithText(expected, useUnmergedTree = true)
                .performTouchInput { longClick() }
            compose.runOnIdle { assertEquals(expected, clipboardText()) }
        }
    }

    @Test
    fun copyIsAvailableAsAnAccessibilityAction() {
        showMessages(listOf(MessageAuthor.Me to "Copy this message"))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { assertEquals("Copy this message", clipboardText()) }
    }

    private fun showMessages(messages: List<Pair<MessageAuthor, String>>) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Column {
                        messages.forEachIndexed { index, (author, text) ->
                            MessageBubble(
                                ChatMessage(index.toLong(), "contact", author, text, 0L),
                                useOriginalEmoticons = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun clipboardText(): String? = compose.activity
        .getSystemService(ClipboardManager::class.java)
        .primaryClip?.getItemAt(0)?.text?.toString()
}
