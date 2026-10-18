package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class LocalDisplayPicture(
    val metadata: MsnDisplayPicture,
    val bytes: ByteArray,
)

/** Shared MSNSLP behavior; binary P2Pv1 and P2Pv2 framing stays in its versioned session. */
internal object MsnpP2pUtil {
    const val SESSION_REQUEST_CONTENT_TYPE = "application/x-msnmsgr-sessionreqbody"
    const val TRANSPORT_REQUEST_CONTENT_TYPE = "application/x-msnmsgr-transreqbody"
    const val TRANSPORT_RESPONSE_CONTENT_TYPE = "application/x-msnmsgr-transrespbody"

    private const val DISPLAY_PICTURE_GUID = "A4268EEC-FEC5-49E5-95C3-F126696BDBF6"
    private const val ZERO_GUID = "00000000-0000-0000-0000-000000000000"

    fun randomGuid(): String = UUID.randomUUID().toString().uppercase()

    fun displayPictureRequestContent(
        sessionId: Number,
        picture: MsnDisplayPicture,
    ): String = buildString {
        append("EUF-GUID: {$DISPLAY_PICTURE_GUID}\r\n")
        append("SessionID: $sessionId\r\n")
        append("AppID: 1\r\n")
        append("RequestFlags: 18\r\n")
        append("Context: ")
        append(MsnBase64.encode(picture.toXml().toByteArray(StandardCharsets.UTF_8)))
        append("\r\n\r\n")
    }

    fun matchingLocalPicture(
        requestBody: String,
        localPicture: LocalDisplayPicture?,
    ): LocalDisplayPicture? {
        val context = requestBody.field("Context") ?: return null
        val requestedObject = runCatching {
            val xml = String(MsnBase64.decode(context) ?: return null, StandardCharsets.UTF_8)
            MsnDisplayPicture.parse(xml)
        }.getOrNull() ?: return null
        return localPicture?.takeIf { picture ->
            picture.metadata.sha1d == requestedObject.sha1d &&
                picture.metadata.matches(picture.bytes)
        }
    }

    fun transportResponseContent(requestBody: String): String {
        val nonceField = if (requestBody.field("Hashed-Nonce") != null) {
            "Hashed-Nonce"
        } else {
            "Nonce"
        }
        return buildString {
            append("Bridge: TCPv1\r\n")
            append("Listening: false\r\n")
            append("$nonceField: {$ZERO_GUID}\r\n\r\n")
        }
    }
}

internal data class SlpMessage(
    val firstLine: String,
    val branch: String,
    val callId: String,
    val contentType: String,
    val body: String,
) {
    companion object {
        fun parse(bytes: ByteArray): SlpMessage? {
            val text = String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000')
            val delimiter = text.indexOf("\r\n\r\n")
            if (delimiter < 0) return null
            val lines = text.substring(0, delimiter).split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to
                    line.substring(separator + 1).trim()
            }.toMap()
            return SlpMessage(
                firstLine = lines.firstOrNull() ?: return null,
                branch = Regex("branch=\\{([^}]+)\\}", RegexOption.IGNORE_CASE)
                    .find(headers["via"].orEmpty())?.groupValues?.get(1).orEmpty(),
                callId = headers["call-id"]?.trim('{', '}').orEmpty(),
                contentType = headers["content-type"].orEmpty(),
                body = text.substring(delimiter + 4),
            )
        }
    }
}

internal fun sipMessage(
    firstLine: String,
    to: String,
    from: String,
    branch: String,
    callId: String,
    cseq: Int,
    contentType: String,
    content: String,
): ByteArray {
    val contentBytes = (content + '\u0000').toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
        append(firstLine).append("\r\n")
        append("To: <msnmsgr:$to>\r\n")
        append("From: <msnmsgr:$from>\r\n")
        append("Via: MSNSLP/1.0/TLP ;branch={$branch}\r\n")
        append("CSeq: $cseq\r\n")
        append("Call-ID: {$callId}\r\n")
        append("Max-Forwards: 0\r\n")
        append("Content-Type: $contentType\r\n")
        append("Content-Length: ${contentBytes.size}\r\n\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    return header + contentBytes
}

internal fun String.field(name: String): String? = lineSequence()
    .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
    ?.substringAfter(':')
    ?.trim()
