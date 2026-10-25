package org.aralozkaya.msngr.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

internal data class OfflineMessageMetadata(
    val id: String,
    val sender: String,
    val senderName: String?,
    val sentAtMillis: Long?,
)

internal interface OfflineMessageService {
    suspend fun metadata(host: String, ticket: String): List<OfflineMessageMetadata>
    suspend fun message(host: String, ticket: String, id: String): String
    suspend fun delete(host: String, ticket: String, id: String)
}

/** MSNP18 sends OIMs over the notification socket; RSI SOAP retrieves and acknowledges them. */
internal object MsnpOfflineMessages : OfflineMessageService {
    const val NAMESPACE = "http://www.hotmail.msn.com/ws/2004/09/oim/rsi"
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    fun send(transactionId: Int, email: String, text: String,
        style: NativeTextStyle = NativeTextStyle()): MsnpCommand {
        require(email.contains('@') && email.none { it.isWhitespace() || it == ';' }) {
            "The offline message recipient is invalid."
        }
        require(text.toByteArray(Charsets.UTF_8).size <= 1200) {
            "Offline message is too long (maximum 1,200 UTF-8 bytes)."
        }
        val format = if (style.copy(size = NativeTextStyle.DEFAULT_SIZE) == NativeTextStyle())
            "FN=Segoe%20UI; EF=; CO=0; CS=1; PF=0" else style.toFormatHeader()
        val payload = ("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n" +
            "X-MMS-IM-Format: $format\r\n" +
            "Dest-Agent: client\r\n\r\n$text").toByteArray(Charsets.UTF_8)
        return MsnpCommand("UUM", transactionId, listOf(email, "1", "1", payload.size.toString()), payload)
    }

    /** Null means this notification is unrelated; too-large requires a GetMetadata request. */
    fun notification(payload: ByteArray): String? {
        val mime = parseOfflineMime(payload.toString(Charsets.UTF_8))
        val type = mime.first["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        if (type !in setOf("text/x-msmsgsinitialmdatanotification", "text/x-msmsgsoimnotification")) return null
        val bodyHeaders = parseOfflineMime(mime.second).first
        return mime.first["mail-data"] ?: bodyHeaders["mail-data"]
    }

    fun parseMetadata(xml: String): List<OfflineMessageMetadata> {
        var document = offlineXml(xml)
        checkSoapFault(document.documentElement)
        // RSI returns Mail-Data as an XML-escaped string inside its SOAP response.
        // Notification-server Mail-Data is already XML, so accept both representations.
        val response = document.getElementsByTagNameNS("*", "GetMetadataResponse").item(0)
        if (response != null && document.getElementsByTagNameNS("*", "MD").length == 0) {
            val embedded = response.textContent.trim()
            require(embedded.startsWith("<")) { "The offline message metadata response is invalid." }
            document = offlineXml(embedded)
            checkSoapFault(document.documentElement)
        }
        require(document.getElementsByTagNameNS("*", "MD").length == 1) {
            "The offline message metadata response is invalid."
        }
        val entries = document.getElementsByTagNameNS("*", "M")
        return (0 until entries.length).mapNotNull { index ->
            val entry = entries.item(index) as? Element ?: return@mapNotNull null
            if (entry.offlineText("T") != "11") return@mapNotNull null
            val id = entry.offlineText("I").trim()
            val sender = entry.offlineText("E").trim().lowercase(Locale.ROOT)
            require(OFFLINE_UUID.matches(id) && sender.contains('@') && sender.none(Char::isWhitespace)) {
                "The offline message metadata is invalid."
            }
            // The RSI service uses the exact identifier as its storage key, including casing.
            OfflineMessageMetadata(id, sender,
                decodeOfflineDisplayName(entry.offlineText("N")).takeIf(String::isNotBlank),
                parseOfflineTimestamp(entry.offlineText("RT")))
        }.distinctBy { it.id.lowercase(Locale.ROOT) }
    }

    fun parseMessage(xml: String, metadata: OfflineMessageMetadata): ChatMessage {
        val document = offlineXml(xml)
        checkSoapFault(document.documentElement)
        val result = document.getElementsByTagNameNS("*", "GetMessageResult").item(0)
            ?: error("The offline message service returned an invalid message.")
        val (headers, body) = parseOfflineMime(result.textContent)
        require(headers["content-type"]?.substringBefore(';')?.trim()?.equals("text/plain", true) == true) {
            "The offline message has an unsupported content type."
        }
        val charsetName = Regex("charset\\s*=\\s*[\"']?([^;\"'\\s]+)", RegexOption.IGNORE_CASE)
            .find(headers["content-type"].orEmpty())?.groupValues?.get(1) ?: "UTF-8"
        val charset = Charset.forName(charsetName)
        val bytes = when (headers["content-transfer-encoding"]?.lowercase()) {
            "base64" -> checkNotNull(MsnBase64.decode(body.filterNot(Char::isWhitespace))) { "Invalid base64 offline message." }
            "quoted-printable" -> decodeOfflineQuotedPrintable(body)
            "7bit", "8bit", null -> body.toByteArray(charset)
            else -> error("The offline message has an unsupported transfer encoding.")
        }
        val sender = headers["from"]?.let { from ->
            Regex("<([^<>]+)>").find(from)?.groupValues?.get(1) ?: from.trim()
        }?.lowercase(Locale.ROOT)
        require(sender == null || sender == metadata.sender) { "The offline message sender does not match its metadata." }
        // A stable, negative ID survives redelivery after a failed delete or an interrupted session.
        val id = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
            .digest(metadata.id.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))).long or Long.MIN_VALUE
        return ChatMessage(id, metadata.sender, MessageAuthor.Contact, String(bytes, charset),
            metadata.sentAtMillis ?: parseOfflineTimestamp(headers["date"].orEmpty()) ?: System.currentTimeMillis(),
            senderEmail = metadata.sender, senderName = metadata.senderName,
            nativeStyle = NativeTextStyle.fromFormatHeader(headers["x-mms-im-format"]))
    }

    fun buildRequest(action: String, ticket: String, id: String? = null): String {
        require(action in setOf("GetMetadata", "GetMessage", "DeleteMessages"))
        val fields = ticket.split('&').mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator < 0) null else field.substring(0, separator) to field.substring(separator + 1)
        }.toMap()
        val token = fields["t"]?.takeIf(String::isNotBlank) ?: error("The offline message credential is missing.")
        val argument = when (action) {
            "GetMessage" -> "<messageId>${validOfflineId(id)}</messageId><alsoMarkAsRead>false</alsoMarkAsRead>"
            "DeleteMessages" -> "<messageIds><messageId>${validOfflineId(id)}</messageId></messageIds>"
            else -> ""
        }
        return """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Header><PassportCookie xmlns="$NAMESPACE"><t>${token.offlineEscape()}</t><p>${fields["p"].orEmpty().offlineEscape()}</p></PassportCookie></soap:Header><soap:Body><$action xmlns="$NAMESPACE">$argument</$action></soap:Body></soap:Envelope>"""
    }

    override suspend fun metadata(host: String, ticket: String) = parseMetadata(post(host, "GetMetadata", ticket))
    override suspend fun message(host: String, ticket: String, id: String) = post(host, "GetMessage", ticket, id)
    override suspend fun delete(host: String, ticket: String, id: String) {
        val document = offlineXml(post(host, "DeleteMessages", ticket, id))
        checkSoapFault(document.documentElement)
        check(document.getElementsByTagNameNS("*", "DeleteMessagesResponse").length == 1) {
            "The offline message acknowledgement is invalid."
        }
    }

    private suspend fun post(host: String, action: String, ticket: String, id: String? = null): String = withContext(Dispatchers.IO) {
        val authority = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val connection = URL("https://$authority/rsi/rsi.asmx").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "$NAMESPACE/$action")
            connection.setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
            connection.outputStream.use { it.write(buildRequest(action, ticket, id).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (BuildConfig.DEBUG) runCatching { Log.i("MsnpOffline", "$action HTTP $status") }
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= MAX_RESPONSE_BYTES) { "The offline message response is too large." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("The offline message service returned no response.")
            val response = bytes.toString(Charsets.UTF_8)
            checkSoapFault(offlineXml(response).documentElement)
            check(status in 200..299) { "The offline message service returned HTTP $status." }
            response
        } finally { connection.disconnect() }
    }
}

internal fun parseOfflineMime(text: String): Pair<Map<String, String>, String> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val end = normalized.indexOf("\n\n")
    val headerText = if (end < 0) normalized else normalized.substring(0, end)
    val unfolded = headerText.replace(Regex("\n[ \\t]+"), " ")
    val headers = unfolded.lineSequence().mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon < 1) null else line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
    }.toMap()
    return headers to if (end < 0) "" else normalized.substring(end + 2)
}

private fun offlineXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isExpandEntityReferences = false
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
}.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))

private fun checkSoapFault(root: Element) {
    check(root.getElementsByTagNameNS("*", "Fault").length == 0) {
        "The offline message service rejected the request."
    }
}

private fun Element.offlineText(name: String) = getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty()
private val OFFLINE_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private fun validOfflineId(id: String?): String = checkNotNull(id).also { require(OFFLINE_UUID.matches(it)) }
private fun String.offlineEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun decodeOfflineDisplayName(text: String): String = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
    .replace(text.replace(Regex("(?<=\\?=)\\s+(?==\\?)"), "")) { match ->
        runCatching {
            val bytes = if (match.groupValues[2].equals("B", true)) checkNotNull(MsnBase64.decode(match.groupValues[3].trim()))
            else decodeOfflineQuotedPrintable(match.groupValues[3].replace('_', ' '))
            String(bytes, Charset.forName(match.groupValues[1]))
        }.getOrDefault(match.value)
    }

private fun decodeOfflineQuotedPrintable(text: String): ByteArray {
    val source = text.replace("=\r\n", "").replace("=\n", "").toByteArray(Charsets.UTF_8)
    val output = java.io.ByteArrayOutputStream()
    var index = 0
    while (index < source.size) {
        if (source[index] == '='.code.toByte()) {
            require(index + 2 < source.size) { "Invalid quoted-printable offline message." }
            val hex = String(source, index + 1, 2, Charsets.US_ASCII).toIntOrNull(16)
                ?: error("Invalid quoted-printable offline message.")
            output.write(hex)
            index += 3
        } else output.write(source[index++].toInt())
    }
    return output.toByteArray()
}

private fun parseOfflineTimestamp(text: String): Long? {
    for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z")) {
        val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(text.trim())?.time }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}
