package org.aralozkaya.msngr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.core.text.util.LinkifyCompat

internal data class ChatMessageLink(val start: Int, val end: Int, val url: String)

internal fun findChatMessageLinks(text: String): List<ChatMessageLink> {
    val spannable = SpannableString(text)
    LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS)
    return spannable.getSpans(0, spannable.length, URLSpan::class.java).mapNotNull { span ->
        span.url.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }?.let { url ->
            ChatMessageLink(spannable.getSpanStart(span), spannable.getSpanEnd(span), url)
        }
    }
}

internal fun Modifier.copyMessageOnLongPress(
    label: String,
    interactionSource: MutableInteractionSource,
    onCopy: () -> Unit,
): Modifier =
    semantics {
        onLongClick(label) {
            onCopy()
            true
        }
    }.pointerInput(interactionSource, onCopy) {
        awaitEachGesture {
            // Observe even presses consumed by inline links, without intercepting taps or scrolling.
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            var released = false
            try {
                if (awaitLongPressOrCancellation(down.id) != null) {
                    onCopy()
                    // Consume release before the link sees it, so holding a URL only copies.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
                released = currentEvent.changes.none { it.pressed }
            } finally {
                // Clear the ripple on release, scrolling, or removal of the bubble.
                interactionSource.tryEmit(
                    if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                )
            }
        }
    }

internal fun copyChatMessage(context: Context, text: String) {
    val plainText = MessengerPlusTextCodec.plainText(text).withExpandedMsnMenuTokens()
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.chat_message_clip_label), plainText),
    )
    // Android 13 and later show their own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.chat_message_copied, Toast.LENGTH_SHORT).show()
    }
}
