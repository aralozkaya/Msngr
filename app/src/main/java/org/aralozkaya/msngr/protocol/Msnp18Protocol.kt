package org.aralozkaya.msngr.protocol

import org.aralozkaya.msngr.NativeTextStyle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.ServerConfig
import org.aralozkaya.msngr.enums.Presence
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.charset.StandardCharsets

data class MsnpCommand(
    val verb: String,
    val transactionId: Int? = null,
    val parameters: List<String> = emptyList(),
    val payload: ByteArray? = null,
)

/** Wire constants and payload builders taken from the MSNP18-era Pidgin implementation. */
object Msnp18Protocol {
    const val VERSION = "MSNP18"
    const val PRODUCT_ID = "PROD0120PW!CCV9@"
    const val PRODUCT_KEY = "C1BX{V4W}Q3*10SM"
    const val TYPING_CONTENT_TYPE = "text/x-msmsgscontrol"
    const val NUDGE_CONTENT_TYPE = "text/x-msnmsgr-datacast"
    const val P2P_CONTENT_TYPE = "application/x-msnmsgrp2p"
    const val FORWARD_LIST = 1
    const val ALLOW_LIST = 2
    const val BLOCK_LIST = 4

    fun version(transactionId: Int) = MsnpCommand(
        verb = "VER",
        transactionId = transactionId,
        parameters = listOf(VERSION, "CVR0"),
    )

    fun clientVersion(transactionId: Int, email: String) = MsnpCommand(
        verb = "CVR",
        transactionId = transactionId,
        parameters = listOf(
            "0x0409",
            "winnt",
            "6.1",
            "i386",
            "MSNMSGR",
            "14.0.8117.0416",
            "msmsgs",
            email,
        ),
    )

    fun beginSso(transactionId: Int, email: String) = MsnpCommand(
        verb = "USR",
        transactionId = transactionId,
        parameters = listOf("SSO", "I", email),
    )

    fun ping() = MsnpCommand(verb = "PNG")

    /** WLM's endpoint-specific sign-out notification (type 4, distinct from type 8 broadcast). */
    fun logOutSession(transactionId: Int, email: String, endpointId: String): MsnpCommand {
        require(email.isNotBlank() && email.none { it.isWhitespace() || it == ';' })
        val id = requireNotNull(org.aralozkaya.msngr.data.normalizeEndpointId(endpointId))
        val payload = "goawyplzthxbye".toByteArray(StandardCharsets.UTF_8)
        return MsnpCommand("UUN", transactionId, listOf("$email;{$id}", "4", payload.size.toString()), payload)
    }

    fun changePresence(
        transactionId: Int,
        presence: Presence,
        displayPicture: MsnDisplayPicture? = null,
    ) = MsnpCommand(
        verb = "CHG",
        transactionId = transactionId,
        parameters = listOf(
            presence.msnpCode,
            CLIENT_IDENTITY,
            displayPicture?.toEncodedValue() ?: "0",
        ),
    )

    /** MSNP18 publishes endpoint capabilities separately from the CHG client ID. */
    fun endpointCapabilities(transactionId: Int): MsnpCommand = uux(
        transactionId,
        "<EndpointData><Capabilities>$ENDPOINT_CAPABILITIES</Capabilities></EndpointData>",
    )

    fun privateEndpointData(
        transactionId: Int,
        presence: Presence,
        endpointName: String,
    ): MsnpCommand = uux(
        transactionId,
        buildString {
            append("<PrivateEndpointData><EpName>")
            append(endpointName.escapeXml())
            append("</EpName><Idle>false</Idle><ClientType>1</ClientType><State>")
            append(presence.msnpCode)
            append("</State></PrivateEndpointData>")
        },
    )

    /** Publishes the account's friendly name through the MSNP PRP/MFN property command. */
    fun changeNickname(transactionId: Int, nickname: String): MsnpCommand {
        val encodedNickname = nickname
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .replace("%", "%25")
            .replace(" ", "%20")
        return MsnpCommand(
            verb = "PRP",
            transactionId = transactionId,
            parameters = listOf("MFN", encodedNickname),
        )
    }

    /**
     * Publishes one batch of the initial contact membership list. MSNP13+ servers use the
     * root-level l="1" marker to distinguish the login-time list from incremental updates.
     * Contacts loaded from the address book are announced on the forward and allow lists.
     */
    fun initialContactList(transactionId: Int, emails: List<String>): MsnpCommand {
        return contactListCommand(
            verb = "ADL",
            transactionId = transactionId,
            memberships = emails.map { it to (FORWARD_LIST or ALLOW_LIST) },
            initial = true,
        )
    }

    fun initialContactMemberships(
        transactionId: Int,
        memberships: List<Pair<String, Int>>,
    ): MsnpCommand = contactListCommand(
        verb = "ADL",
        transactionId = transactionId,
        memberships = memberships,
        initial = true,
    )

    fun addToContactList(transactionId: Int, email: String, list: Int): MsnpCommand =
        contactListCommand("ADL", transactionId, listOf(email to list), initial = false)

    fun removeFromContactList(transactionId: Int, email: String, list: Int): MsnpCommand =
        contactListCommand("RML", transactionId, listOf(email to list), initial = false)

    private fun contactListCommand(
        verb: String,
        transactionId: Int,
        memberships: List<Pair<String, Int>>,
        initial: Boolean,
    ): MsnpCommand {
        val contactsByDomain = linkedMapOf<String, MutableList<String>>()
        val listsByIdentity = linkedMapOf<String, Int>()
        memberships.forEach { (rawEmail, list) ->
            val email = rawEmail.trim()
            val separator = email.lastIndexOf('@')
            if (separator <= 0 || separator == email.lastIndex) return@forEach
            val localPart = email.substring(0, separator)
            val domain = email.substring(separator + 1).lowercase()
            contactsByDomain.getOrPut(domain) { mutableListOf() }.add(localPart)
            listsByIdentity["$localPart@$domain"] = list
        }
        val payload = buildString {
            append(if (initial) "<ml l=\"1\">" else "<ml>")
            contactsByDomain.forEach { (domain, localParts) ->
                append("<d n=\"").append(domain.escapeXml()).append("\">")
                localParts.forEach { localPart ->
                    append("<c n=\"").append(localPart.escapeXml())
                    append("\" l=\"").append(listsByIdentity.getValue("$localPart@$domain"))
                    append("\" t=\"1\" />")
                }
                append("</d>")
            }
            append("</ml>")
        }.toByteArray(StandardCharsets.UTF_8)
        return MsnpCommand(
            verb = verb,
            transactionId = transactionId,
            parameters = listOf(payload.size.toString()),
            payload = payload,
        )
    }

    /**
     * Publishes the user's personal status message through the MSNP18 UUX command.
     * The payload shape mirrors the protocol 16+ Pidgin implementation.
     */
    fun personalMessage(
        transactionId: Int,
        statusMessage: String,
        machineGuid: String = "",
    ): MsnpCommand {
        return uux(transactionId, buildString {
            append("<Data><PSM>")
            append(statusMessage.trim().escapeXml())
            append("</PSM><CurrentMedia></CurrentMedia><MachineGuid>")
            append(machineGuid.escapeXml())
            append("</MachineGuid><DDP></DDP></Data>")
        })
    }

    /** Extracts a contact's personal message from an incoming UBX XML payload. */
    fun parsePersonalMessage(payload: ByteArray): String {
        val xml = String(payload, StandardCharsets.UTF_8)
        if (SELF_CLOSING_PSM.containsMatchIn(xml)) return ""
        return PSM_VALUE.find(xml)?.groupValues?.get(1)?.unescapeXml().orEmpty()
    }

    fun typingPayload(email: String): ByteArray = mimePayload(
        contentType = TYPING_CONTENT_TYPE,
        extraHeaders = mapOf("TypingUser" to email),
        body = byteArrayOf(),
    )

    fun nudgePayload(): ByteArray = mimePayload(
        contentType = NUDGE_CONTENT_TYPE,
        body = "ID: 1\r\n".toByteArray(StandardCharsets.UTF_8),
    )

    fun textPayload(message: String, style: NativeTextStyle = NativeTextStyle()): ByteArray = mimePayload(
        contentType = "text/plain; charset=UTF-8",
        extraHeaders = mapOf(
            "X-MMS-IM-Format" to style.toFormatHeader(),
        ),
        body = message.toByteArray(StandardCharsets.UTF_8),
    )

    fun p2pPayload(destination: String, packet: ByteArray, source: String? = null): ByteArray = mimePayload(
        contentType = P2P_CONTENT_TYPE,
        extraHeaders = buildMap {
            put("P2P-Dest", destination.lowercase())
            if (source != null) put("P2P-Src", source.lowercase())
        },
        body = packet,
    )

    fun switchboardTransfer(transactionId: Int) = MsnpCommand(
        verb = "XFR",
        transactionId = transactionId,
        parameters = listOf("SB"),
    )

    fun switchboardAuthenticate(
        transactionId: Int,
        identity: String,
        authKey: String,
    ) = MsnpCommand(
        verb = "USR",
        transactionId = transactionId,
        parameters = listOf(identity, authKey),
    )

    fun switchboardAnswer(
        transactionId: Int,
        identity: String,
        authKey: String,
        sessionId: String,
    ) = MsnpCommand(
        verb = "ANS",
        transactionId = transactionId,
        parameters = listOf(identity, authKey, sessionId),
    )

    fun switchboardInvite(transactionId: Int, email: String) = MsnpCommand(
        verb = "CAL",
        transactionId = transactionId,
        parameters = listOf(email),
    )

    fun switchboardMessage(
        transactionId: Int,
        payload: ByteArray,
        acknowledgement: String = "A",
    ) = MsnpCommand(
        verb = "MSG",
        transactionId = transactionId,
        parameters = listOf(acknowledgement, payload.size.toString()),
        payload = payload,
    )

    private fun mimePayload(
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray,
    ): ByteArray {
        val headers = buildString {
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: $contentType\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        return headers + body
    }

    private fun String.escapeXml(): String = buildString(length) {
        this@escapeXml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    // Servers also escape punctuation numerically (for example [c&#x3D;04]). Decode
    // one XML layer only, so escaped literal entity text stays literal.
    private fun String.unescapeXml(): String = XML_ENTITY.replace(this) { match ->
        when (val entity = match.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            else -> {
                val codePoint = if (entity.startsWith("#x")) {
                    entity.substring(2).toIntOrNull(16)
                } else {
                    entity.substring(1).toIntOrNull()
                }
                if (codePoint != null && Character.isValidCodePoint(codePoint) &&
                    codePoint !in 0xD800..0xDFFF
                ) String(Character.toChars(codePoint)) else match.value
            }
        }
    }

    private val XML_ENTITY = Regex("&(lt|gt|quot|apos|amp|#[0-9]+|#x[0-9a-fA-F]+);")

    private val PSM_VALUE = Regex(
        pattern = "<PSM(?:\\s[^>]*)?>(.*?)</PSM>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SELF_CLOSING_PSM = Regex(
        pattern = "<PSM(?:\\s[^>]*)?\\s*/>",
        option = RegexOption.IGNORE_CASE,
    )

    private fun uux(transactionId: Int, xml: String): MsnpCommand {
        val payload = xml.toByteArray(StandardCharsets.UTF_8)
        return MsnpCommand(
            verb = "UUX",
            transactionId = transactionId,
            parameters = listOf(payload.size.toString()),
            payload = payload,
        )
    }

    // MSNC9 (0x90) in the high byte, with only message chunking (0x20) advertised.
    // The extended mask advertises only nudges (0x400). In particular, it leaves out
    // handwriting (0x4/0x8), activities and games (0x100), and file transfer (0x20000).
    const val CLIENT_ID = 2_415_919_136L
    const val BASE_CAPABILITIES = 32
    const val EXTENDED_CAPABILITIES = 1_024
    const val CLIENT_IDENTITY = "2415919136:1024"
    const val ENDPOINT_CAPABILITIES = "32:1024"
}

object MsnpFrameCodec {
    fun encode(command: MsnpCommand): ByteArray {
        val line = buildString {
            append(command.verb.uppercase())
            command.transactionId?.let { append(' ').append(it) }
            command.parameters.forEach { append(' ').append(it) }
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        return command.payload?.let(line::plus) ?: line
    }

    fun decodeHeader(line: String): MsnpCommand {
        val tokens = line.trimEnd('\r', '\n').split(' ').filter(String::isNotEmpty)
        require(tokens.isNotEmpty()) { "Empty MSNP command" }
        // These server events begin with numeric data rather than a transaction id.
        val transactionId = if (tokens.first().uppercase() in NON_TRANSACTIONAL_NUMERIC_VERBS) {
            null
        } else {
            tokens.getOrNull(1)?.toIntOrNull()
        }
        val parameterStart = if (transactionId == null) 1 else 2
        return MsnpCommand(
            verb = tokens.first().uppercase(),
            transactionId = transactionId,
            parameters = tokens.drop(parameterStart),
        )
    }

    private val NON_TRANSACTIONAL_NUMERIC_VERBS = setOf("RNG", "NOT", "QNG")

    fun expectedPayloadLength(command: MsnpCommand): Int =
        if (command.verb in setOf(
                "MSG",
                "UUM",
                "UBM",
                "UUX",
                "UBX",
                "UBN",
                "ADL",
                "RML",
                "NOT",
                "GCF",
                "SDG",
                "NFY",
                "PUT",
            )
        ) {
            command.parameters.lastOrNull()?.toIntOrNull() ?: 0
        } else {
            0
        }
}

/** Injectable MSNP command transport used by notification and switchboard connections. */
interface MsnpTransport : Closeable {
    suspend fun connect(host: String, port: Int, timeoutMillis: Int = 10_000)
    suspend fun send(command: MsnpCommand)
    suspend fun receive(): MsnpCommand
    fun setReadTimeout(timeoutMillis: Int) = Unit
}

/** Incremental decoder for TCP reads where headers and payloads can be split arbitrarily. */
class MsnpFrameDecoder {
    private var buffer = byteArrayOf()
    private var pending: Pair<MsnpCommand, Int>? = null

    fun feed(bytes: ByteArray): List<MsnpCommand> {
        buffer += bytes
        val result = mutableListOf<MsnpCommand>()
        while (true) {
            val waiting = pending
            if (waiting != null) {
                if (buffer.size < waiting.second) break
                result += waiting.first.copy(payload = buffer.copyOfRange(0, waiting.second))
                buffer = buffer.copyOfRange(waiting.second, buffer.size)
                pending = null
                continue
            }

            val lineEnd = buffer.indexOfCrlf()
            if (lineEnd < 0) break
            val line = String(buffer.copyOfRange(0, lineEnd), StandardCharsets.UTF_8)
            buffer = buffer.copyOfRange(lineEnd + 2, buffer.size)
            val command = MsnpFrameCodec.decodeHeader(line)
            val payloadLength = MsnpFrameCodec.expectedPayloadLength(command)
            if (payloadLength == 0) result += command
            else pending = command to payloadLength
        }
        return result
    }

    private fun ByteArray.indexOfCrlf(): Int {
        for (index in 0 until size - 1) {
            if (this[index] == '\r'.code.toByte() && this[index + 1] == '\n'.code.toByte()) {
                return index
            }
        }
        return -1
    }
}

/**
 * Direct TCP transport for MSNP notification and switchboard traffic.
 *
 * Authentication remains in the client layer because SSO uses separate HTTP services. MSNP wire
 * traffic never uses those services as a tunnel and explicitly bypasses configured socket proxies.
 */
class Msnp18SocketTransport : MsnpTransport {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var decoder = MsnpFrameDecoder()
    private val decodedQueue = ArrayDeque<MsnpCommand>()

    suspend fun connect(config: ServerConfig, timeoutMillis: Int = 10_000) =
        connect(config.mainServer, config.mainPort, timeoutMillis)

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) = withContext(Dispatchers.IO) {
        close()
        trace("CONNECT start port=$port")
        val newSocket = Socket(Proxy.NO_PROXY).apply {
            tcpNoDelay = true
            keepAlive = true
        }
        newSocket.connect(InetSocketAddress(host, port), timeoutMillis)
        newSocket.soTimeout = timeoutMillis
        socket = newSocket
        input = BufferedInputStream(newSocket.getInputStream())
        output = BufferedOutputStream(newSocket.getOutputStream())
        trace("CONNECT ready port=$port")
    }

    override suspend fun send(command: MsnpCommand) = withContext(Dispatchers.IO) {
        val stream = checkNotNull(output) { "MSNP socket is not connected" }
        trace("SEND ${command.verb} payload=${command.payload?.size ?: 0}")
        stream.write(MsnpFrameCodec.encode(command))
        stream.flush()
    }

    override suspend fun receive(): MsnpCommand = withContext(Dispatchers.IO) {
        decodedQueue.removeFirstOrNull()?.let { return@withContext it }
        val stream = checkNotNull(input) { "MSNP socket is not connected" }
        val readBuffer = ByteArray(8 * 1024)
        while (true) {
            val count = stream.read(readBuffer)
            check(count >= 0) { "MSNP socket closed" }
            val decoded = decoder.feed(readBuffer.copyOf(count))
            if (decoded.isNotEmpty()) {
                decodedQueue.addAll(decoded.drop(1))
                trace("RECV ${decoded.first().verb} payload=${decoded.first().payload?.size ?: 0}")
                return@withContext decoded.first()
            }
        }
        error("Unreachable")
    }

    override fun setReadTimeout(timeoutMillis: Int) {
        socket?.soTimeout = timeoutMillis
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        decoder = MsnpFrameDecoder()
        decodedQueue.clear()
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.i("MsnpWire", message)
    }
}
