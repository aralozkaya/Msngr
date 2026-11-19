package org.aralozkaya.msngr.protocol

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.ServerConfig
import org.aralozkaya.msngr.enums.Presence
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

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
    const val INK_ISF_CONTENT_TYPE = "application/x-ms-ink"
    const val INK_GIF_CONTENT_TYPE = "image/gif"
    const val MAX_INK_CHUNK_BODY_BYTES = 1_202
    const val MAX_INK_CHUNKS = 60
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

    /** Builds the MSNP11+ QRY response for a server CHL challenge. */
    fun challengeResponse(
        transactionId: Int,
        challenge: String,
        productId: String = PRODUCT_ID,
        productKey: String = PRODUCT_KEY,
    ): MsnpCommand {
        val digest = computeChallengeHash(challenge, productId, productKey)
            .toByteArray(StandardCharsets.US_ASCII)
        return MsnpCommand(
            verb = "QRY",
            transactionId = transactionId,
            parameters = listOf(productId, digest.size.toString()),
            payload = digest,
        )
    }

    /** Computes the 32-character lowercase hex digest required by MSNP11+ CHL/QRY. */
    fun computeChallengeHash(
        challenge: String,
        productId: String = PRODUCT_ID,
        productKey: String = PRODUCT_KEY,
    ): String {
        require(challenge.isNotEmpty()) { "MSNP challenge cannot be empty." }
        val keyDigest = MessageDigest.getInstance("MD5")
            .digest((challenge + productKey).toByteArray(StandardCharsets.US_ASCII))
        val privateSeed = IntArray(4) { index ->
            littleEndianInt(keyDigest, index * 4) and 0x7FFF_FFFF
        }

        val rawPublic = (challenge + productId).toByteArray(StandardCharsets.US_ASCII)
        val paddedPublic = rawPublic + ByteArray(8 - (rawPublic.size % 8)) { '0'.code.toByte() }
        val publicSeed = IntArray(paddedPublic.size / 4) { index ->
            littleEndianInt(paddedPublic, index * 4) and 0x7FFF_FFFF
        }

        var high = 0L
        var low = 0L
        for (index in publicSeed.indices step 2) {
            var temp = (publicSeed[index].toLong() * CHALLENGE_MAGIC) % CHALLENGE_MODULUS
            temp = (temp + high) % CHALLENGE_MODULUS
            temp = (temp * privateSeed[0] + privateSeed[1]) % CHALLENGE_MODULUS

            high = (publicSeed[index + 1].toLong() + temp) % CHALLENGE_MODULUS
            high = (high * privateSeed[2] + privateSeed[3]) % CHALLENGE_MODULUS

            low += high + temp
        }

        val finalHigh = ((high + privateSeed[1]) % CHALLENGE_MODULUS).toInt()
        val finalLow = ((low + privateSeed[3]) % CHALLENGE_MODULUS).toInt()
        val key = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(finalHigh)
            .putInt(finalLow)
            .array()

        return buildString(32) {
            for (index in keyDigest.indices) {
                val xorByte = (keyDigest[index].toInt() xor key[index % 8].toInt()) and 0xFF
                append(CHALLENGE_HEX_DIGITS[xorByte ushr 4])
                append(CHALLENGE_HEX_DIGITS[xorByte and 0x0F])
            }
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private const val CHALLENGE_MAGIC = 0x0E79_A9C1L
    private const val CHALLENGE_MODULUS = 0x7FFF_FFFFL
    private const val CHALLENGE_HEX_DIGITS = "0123456789abcdef"

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

    /** Refresh routing without changing the account's roaming personal message. */
    fun endpointMachineGuid(transactionId: Int, machineGuid: String): MsnpCommand = uux(
        transactionId,
        "<Data><MachineGuid>${machineGuid.escapeXml()}</MachineGuid></Data>",
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
     * Publishes the user's personal status message and optional current media through the MSNP18 UUX command.
     * The payload shape mirrors the protocol 16+ Pidgin implementation.
     */
    fun personalMessage(
        transactionId: Int,
        statusMessage: String,
        machineGuid: String = "",
        currentMedia: CurrentMedia? = null,
    ): MsnpCommand {
        val encodedMedia = MsnCurrentMedia.encode(currentMedia)
        return uux(transactionId, buildString {
            append("<Data><PSM>")
            append(statusMessage.trim().escapeXml())
            append("</PSM><CurrentMedia>")
            append(encodedMedia.escapeXml())
            append("</CurrentMedia><MachineGuid>")
            append(machineGuid.escapeXml())
            append("</MachineGuid><DDP></DDP></Data>")
        })
    }

    /** Returns true if an incoming UBX XML payload explicitly includes a `<PSM>` element. */
    fun hasPersonalMessageElement(payload: ByteArray): Boolean =
        PSM_TAG.containsMatchIn(String(payload, StandardCharsets.UTF_8))

    /** Extracts a contact's personal message from an incoming UBX XML payload. */
    fun parsePersonalMessage(payload: ByteArray): String {
        val xml = String(payload, StandardCharsets.UTF_8)
        if (SELF_CLOSING_PSM.containsMatchIn(xml)) return ""
        return PSM_VALUE.find(xml)?.groupValues?.get(1)?.unescapeXml().orEmpty()
    }

    /** Returns true if an incoming UBX XML payload explicitly includes a `<CurrentMedia>` element. */
    fun hasCurrentMediaElement(payload: ByteArray): Boolean =
        CURRENT_MEDIA_TAG.containsMatchIn(String(payload, StandardCharsets.UTF_8))

    /** Extracts a contact's "What I'm listening to?" metadata from an incoming UBX XML payload. */
    fun parseCurrentMedia(payload: ByteArray): CurrentMedia? {
        val xml = String(payload, StandardCharsets.UTF_8)
        if (SELF_CLOSING_CURRENT_MEDIA.containsMatchIn(xml)) return null
        val raw = CURRENT_MEDIA_VALUE.find(xml)?.groupValues?.get(1)?.unescapeXml() ?: return null
        return MsnCurrentMedia.parse(raw)
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

    fun inkPayloads(
        drawing: InkDrawing,
        messageId: String = UUID.randomUUID().toString().uppercase(Locale.US),
        useGif: Boolean = false,
    ): List<ByteArray> {
        val contentType = if (useGif) INK_GIF_CONTENT_TYPE else INK_ISF_CONTENT_TYPE
        val encodedBody = if (useGif) {
            MsnInkCodec.encodeBase64GifPayload(drawing)
        } else {
            MsnInkCodec.encodeBase64IsfPayload(drawing)
        }
        return inkPayloadsFromEncodedBody(
            contentType = contentType,
            encodedBody = encodedBody,
            messageId = messageId,
        )
    }

    fun inkPayloadsFromEncodedBody(
        contentType: String,
        encodedBody: String,
        messageId: String = UUID.randomUUID().toString().uppercase(Locale.US),
    ): List<ByteArray> {
        val bodyBytes = encodedBody.toByteArray(StandardCharsets.UTF_8)
        if (bodyBytes.size <= MAX_INK_CHUNK_BODY_BYTES) {
            return listOf(
                mimePayload(
                    contentType = contentType,
                    body = bodyBytes,
                ),
            )
        }
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bodyBytes.size) {
            val end = minOf(offset + MAX_INK_CHUNK_BODY_BYTES, bodyBytes.size)
            chunks += bodyBytes.copyOfRange(offset, end)
            offset = end
        }
        require(chunks.size <= MAX_INK_CHUNKS) {
            "Handwriting drawing exceeds the MSNP maximum of $MAX_INK_CHUNKS chunks."
        }
        val normalizedId = "{${messageId.trim().trim('{', '}')}}"
        return chunks.mapIndexed { index, chunkBody ->
            if (index == 0) {
                mimePayload(
                    contentType = contentType,
                    extraHeaders = linkedMapOf(
                        "Message-ID" to normalizedId,
                        "Chunks" to chunks.size.toString(),
                    ),
                    body = chunkBody,
                )
            } else {
                val headers = buildString {
                    append("Message-ID: $normalizedId\r\n")
                    append("Chunk: $index\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.UTF_8)
                headers + chunkBody
            }
        }
    }

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

    private val PSM_TAG = Regex(
        pattern = "<PSM(?:>|\\s|/)",
        option = RegexOption.IGNORE_CASE,
    )
    private val PSM_VALUE = Regex(
        pattern = "<PSM(?:\\s[^>]*)?>(.*?)</PSM>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SELF_CLOSING_PSM = Regex(
        pattern = "<PSM(?:\\s[^>]*)?\\s*/>",
        option = RegexOption.IGNORE_CASE,
    )
    private val CURRENT_MEDIA_TAG = Regex(
        pattern = "<CurrentMedia(?:>|\\s|/)",
        option = RegexOption.IGNORE_CASE,
    )
    private val CURRENT_MEDIA_VALUE = Regex(
        pattern = "<CurrentMedia(?:\\s[^>]*)?>(.*?)</CurrentMedia>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SELF_CLOSING_CURRENT_MEDIA = Regex(
        pattern = "<CurrentMedia(?:\\s[^>]*)?\\s*/>",
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

    // MSNC10, chunking, handwriting, voice clips, and winks.
    // Endpoint capabilities include P2Pv2 (0x20), nudges (0x400), Photos (0x1000),
    // and conversation-window file transfers (0x20000).
    const val CLIENT_ID = 2_684_649_516L
    const val BASE_CAPABILITIES = 294_956
    const val EXTENDED_CAPABILITIES = 136_224
    const val CLIENT_IDENTITY = "2684649516:136224"
    const val ENDPOINT_CAPABILITIES = "294956:136224"

    fun voiceClipPayload(clip: MsnDisplayPicture): ByteArray {
        require(clip.objectType == MsnVoiceClip.OBJECT_TYPE)
        return ("MIME-Version: 1.0\r\nContent-Type: $NUDGE_CONTENT_TYPE\r\n\r\n" +
            "ID: 3\r\nData: ${clip.toXml()}\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
    }
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
    private val traceId = nextTraceId.incrementAndGet()
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
        decodedQueue.removeFirstOrNull()?.let { queued ->
            trace("RECV ${queued.verb} payload=${queued.payload?.size ?: 0}")
            return@withContext queued
        }
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
        // Unblock a concurrent receive before closing its buffered stream.
        runCatching { socket?.close() }
        runCatching { input?.close() }
        runCatching { output?.close() }
        input = null
        output = null
        socket = null
        decoder = MsnpFrameDecoder()
        decodedQueue.clear()
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.i("MsnpWire", "$traceId $message")
    }

    private companion object { val nextTraceId = java.util.concurrent.atomic.AtomicInteger() }
}
