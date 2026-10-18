package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** MSNP18 circles use the notification connection, not a switchboard. */
internal object MsnpCircles {
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
            if (type == "Text") append("X-MMS-IM-Format: FN=Segoe%20UI; EF=; CO=0; CS=1; PF=0\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return payloadCommand("SDG", transactionId, headers + body)
    }

    private fun payloadCommand(verb: String, id: Int, body: ByteArray) =
        MsnpCommand(verb, id, listOf(body.size.toString()), body)
}

internal data class CircleEnvelope(
    val routing: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val groupId: String?
        get() = listOf(routing["to"], routing["from"]).firstOrNull { it?.startsWith("9:") == true }
            ?.substringAfter("9:")?.substringBefore(';')?.lowercase()
    val senderEmail: String?
        get() = routing["from"]?.takeIf { it.startsWith("1:") }
            ?.substringAfter("1:")?.substringBefore(';')?.lowercase()
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
    block() ?: return null
    val headers = block() ?: return null
    // Captured WLM 2009 messages can declare one more byte than they transmit here.
    // The enclosing MSNP frame is authoritative; keep its complete UTF-8 body.
    return CircleEnvelope(routing, headers, payload.copyOfRange(offset, payload.size))
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
