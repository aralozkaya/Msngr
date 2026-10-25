package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnInkCodec
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** MSNP18 circles use the notification connection, not a switchboard. */
internal object MsnpCircles {
    /** Maximum SDG frame payload size in WLM 2009 `PresenceIM.dll` (`0x1c00`). */
    const val MAX_SDG_PAYLOAD_BYTES = 7_168

    fun authenticate(transactionId: Int, ticket: String) = MsnpCommand(
        "USR", transactionId,
        listOf("SHA", "A", MsnBase64.encode(ticket.toByteArray(Charsets.UTF_8))),
    )

    fun subscribe(transactionId: Int, groups: List<Contact>, initial: Boolean = true) = payloadCommand(
        "ADL", transactionId,
        buildString {
            append(if (initial) "<ml l=\"1\">" else "<ml>")
            groups.groupBy { it.email.substringAfter('@') }.forEach { (domain, contacts) ->
                append("<d n=\"").append(domain.circleXml()).append("\">")
                contacts.forEach {
                    append("<c n=\"").append(it.email.substringBefore('@').circleXml())
                    append("\" t=\"9\" l=\"").append(if (it.isBlocked) 5 else 3).append("\" />")
                }
                append("</d>")
            }
            append("</ml>")
        }.toByteArray(Charsets.UTF_8),
    )

    fun message(
        transactionId: Int, groupEmail: String, senderEmail: String, endpointId: String,
        text: String = "", type: String = "Text",
        style: NativeTextStyle = NativeTextStyle(),
    ): MsnpCommand {
        require(listOf(groupEmail, senderEmail, endpointId).none { '\r' in it || '\n' in it })
        val body = when (type) {
            "Nudge" -> "ID: 1\r\n\r\n"
            "Control" -> "\r\n"
            else -> text
        }.toByteArray(Charsets.UTF_8)
        require(body.size <= 1200) { "Group message is too long (maximum 1,200 UTF-8 bytes)." }
        val headers = buildString {
            append("Routing: 1.0\r\nTo: 9:$groupEmail;path=IM\r\n")
            append("From: 1:$senderEmail;epid={${endpointId.trim('{', '}')}}\r\n\r\n")
            append("Reliability: 1.0\r\nStream: 0\r\nSegment: 0\r\n\r\n")
            append("Messaging: 1.0\r\nContent-Length: ${body.size}\r\n")
            append("Content-Type: ${if (type == "Control") "text/x-msmsgscontrol" else "text/plain; charset=UTF-8"}\r\n")
            append("Content-Transfer-Encoding: 7bit\r\nMessage-Type: $type\r\nMIME-Version: 1.0\r\n")
            if (type == "Control") append("Message-Subtype: Typing\r\nTypingUser: $senderEmail\r\n")
            if (type == "Text") append("X-MMS-IM-Format: ${style.toFormatHeader()}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return payloadCommand("SDG", transactionId, headers + body)
    }

    fun inkMessage(
        transactionId: Int,
        groupEmail: String,
        senderEmail: String,
        endpointId: String,
        drawing: InkDrawing,
        useGif: Boolean = false,
        segment: Int = 0,
    ): MsnpCommand = inkMessages(
        groupEmail = groupEmail,
        senderEmail = senderEmail,
        endpointId = endpointId,
        drawing = drawing,
        useGif = useGif,
        startSegment = segment,
        nextTransactionId = { transactionId },
    ).first()

    fun inkMessages(
        groupEmail: String,
        senderEmail: String,
        endpointId: String,
        drawing: InkDrawing,
        useGif: Boolean = false,
        startSegment: Int = 0,
        nextTransactionId: () -> Int,
    ): List<MsnpCommand> {
        require(listOf(groupEmail, senderEmail, endpointId).none { '\r' in it || '\n' in it })
        val contentType = if (useGif) {
            Msnp18Protocol.INK_GIF_CONTENT_TYPE
        } else {
            Msnp18Protocol.INK_ISF_CONTENT_TYPE
        }
        val encodedBody = if (useGif) {
            MsnInkCodec.encodeBase64GifPayload(drawing)
        } else {
            MsnInkCodec.encodeBase64IsfPayload(drawing)
        }
        val body = encodedBody.toByteArray(Charsets.UTF_8)
        val normalizedEpid = endpointId.trim('{', '}')

        fun buildHeaders(segment: Int, firstSegment: Boolean): ByteArray = buildString {
            append("Routing: 1.0\r\nTo: 9:$groupEmail;path=IM\r\n")
            append("From: 1:$senderEmail;epid={$normalizedEpid}\r\n\r\n")
            append("Reliability: 1.0\r\nStream: 0\r\nSegment: $segment\r\n\r\n")
            append("Messaging: 1.0\r\n")
            if (firstSegment) {
                append("Content-Length: ${body.size}\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Transfer-Encoding: 7bit\r\n")
                append("Message-Type: Ink\r\n")
                append("MIME-Version: 1.0\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val firstHeaders = buildHeaders(startSegment, firstSegment = true)
        if (firstHeaders.size + body.size <= MAX_SDG_PAYLOAD_BYTES) {
            return listOf(payloadCommand("SDG", nextTransactionId(), firstHeaders + body))
        }

        val commands = mutableListOf<MsnpCommand>()
        var offset = 0
        var segmentOffset = 0
        while (offset < body.size) {
            val isFirst = segmentOffset == 0
            val headers = if (isFirst) firstHeaders else buildHeaders(startSegment + segmentOffset, firstSegment = false)
            val maxSlice = (MAX_SDG_PAYLOAD_BYTES - 1 - headers.size).coerceAtLeast(1)
            val end = minOf(offset + maxSlice, body.size)
            val slice = body.copyOfRange(offset, end)
            commands += payloadCommand("SDG", nextTransactionId(), headers + slice)
            offset = end
            segmentOffset++
        }
        return commands
    }

    private fun payloadCommand(verb: String, id: Int, body: ByteArray) =
        MsnpCommand(verb, id, listOf(body.size.toString()), body)
}

internal data class CircleEnvelope(
    val routing: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val reliability: Map<String, String> = emptyMap(),
) {
    val groupId: String?
        get() = listOf(routing["to"], routing["from"]).firstOrNull { it?.startsWith("9:") == true }
            ?.substringAfter("9:")?.substringBefore(';')?.lowercase()
    val senderEmail: String?
        get() = routing["from"]?.takeIf { it.startsWith("1:") }
            ?.substringAfter("1:")?.substringBefore(';')?.lowercase()
}

/**
 * Reassembles multi-segment `SDG` messages when `PresenceIM.dll` splits a large payload
 * across sequential `Reliability: 1.0` `Segment` frames.
 */
internal class CircleSegmentAssembler {
    private data class StreamKey(
        val groupId: String,
        val senderFrom: String,
        val stream: String,
    )

    private class PendingMessage(
        val routing: Map<String, String>,
        val reliability: Map<String, String>,
        val headers: Map<String, String>,
        val expectedLength: Int,
        val startSegment: Int,
    ) {
        val segments = sortedMapOf<Int, ByteArray>()

        fun tryAssemble(): CircleEnvelope? {
            var nextExpectedSegment = startSegment
            var accumulated = 0
            for ((seg, bytes) in segments) {
                if (seg != nextExpectedSegment) return null
                accumulated += bytes.size
                nextExpectedSegment++
                if (accumulated >= expectedLength) {
                    val combined = ByteArray(accumulated)
                    var writeOffset = 0
                    for (s in startSegment until nextExpectedSegment) {
                        val part = segments.getValue(s)
                        part.copyInto(combined, destinationOffset = writeOffset)
                        writeOffset += part.size
                    }
                    return CircleEnvelope(
                        routing = routing,
                        headers = headers,
                        body = if (combined.size > expectedLength) combined.copyOfRange(0, expectedLength) else combined,
                        reliability = reliability,
                    )
                }
            }
            return null
        }
    }

    private val pendingByStream = LinkedHashMap<StreamKey, PendingMessage>()

    @Synchronized
    fun accept(envelope: CircleEnvelope): CircleEnvelope? {
        val groupId = envelope.groupId ?: return envelope
        val from = envelope.routing["from"]?.lowercase() ?: return envelope
        val stream = envelope.reliability["stream"] ?: "0"
        val segment = envelope.reliability["segment"]?.toIntOrNull()
        val declaredLength = envelope.headers["content-length"]?.toIntOrNull()
        val hasMessageType = envelope.headers["message-type"] != null
        val key = StreamKey(groupId, from, stream)

        if (hasMessageType) {
            // Single-segment messages in WLM 2009 can declare up to 1 byte more than transmitted (trailing NUL).
            if (declaredLength == null || envelope.body.size + 1 >= declaredLength || segment == null) {
                pendingByStream.remove(key)
                return envelope
            }
            val pending = PendingMessage(
                routing = envelope.routing,
                reliability = envelope.reliability,
                headers = envelope.headers,
                expectedLength = declaredLength,
                startSegment = segment,
            )
            pending.segments[segment] = envelope.body
            if (pendingByStream.size >= 32) {
                pendingByStream.remove(pendingByStream.keys.first())
            }
            pendingByStream[key] = pending
            return null
        }

        // Continuation segment: Messaging: 1.0 with no Message-Type / Content-Length.
        if (declaredLength == null && segment != null) {
            val pending = pendingByStream[key] ?: return null
            pending.segments[segment] = envelope.body
            val assembled = pending.tryAssemble()
            if (assembled != null) {
                pendingByStream.remove(key)
                return assembled
            }
            return null
        }

        return envelope
    }

    @Synchronized
    fun clear() {
        pendingByStream.clear()
    }
}

/** Three MIME-like header blocks, with a byte-framed UTF-8 body. */
internal fun parseCircleEnvelope(payload: ByteArray): CircleEnvelope? {
    var offset = 0
    fun block(): Map<String, String>? {
        val end = (offset..payload.size - 4).firstOrNull { i ->
            payload[i] == 13.toByte() && payload[i + 1] == 10.toByte() &&
                payload[i + 2] == 13.toByte() && payload[i + 3] == 10.toByte()
        } ?: return null
        val text = String(payload, offset, end - offset, Charsets.UTF_8)
        offset = end + 4
        return text.split("\r\n").mapNotNull {
            val colon = it.indexOf(':')
            if (colon < 1) null else it.substring(0, colon).lowercase() to it.substring(colon + 1).trim()
        }.toMap()
    }
    val routing = block() ?: return null
    if (routing["routing"] != "1.0") return null
    val reliability = block() ?: return null
    val headers = block() ?: return null
    // Captured WLM 2009 messages can declare one more byte than they transmit here.
    // The enclosing MSNP frame is authoritative; keep its complete UTF-8 body.
    return CircleEnvelope(routing, headers, payload.copyOfRange(offset, payload.size), reliability)
}

internal data class CirclePublication(val name: String?, val roster: List<String>?)

internal fun parseCirclePublication(body: ByteArray): CirclePublication? = runCatching {
    if (body.isEmpty()) return null
    val document = circleDocumentFactory().newDocumentBuilder().parse(body.inputStream())
    val root = document.documentElement
    if (root.tagName != "circle") return null
    val name = root.getElementsByTagName("MFN").item(0)?.textContent?.takeIf(String::isNotBlank)
    val roster = (root.getElementsByTagName("roster").item(0) as? Element)?.let { element ->
        val ids = element.getElementsByTagName("id")
        (0 until ids.length).mapNotNull { index ->
            ids.item(index).textContent.takeIf { it.startsWith("1:") }
                ?.substringAfter("1:")?.substringBefore(';')?.lowercase()
        }.distinct()
    }
    CirclePublication(name, roster)
}.getOrNull()

internal fun circleDocumentFactory() = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isExpandEntityReferences = false
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
}

private fun String.circleXml() = replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
