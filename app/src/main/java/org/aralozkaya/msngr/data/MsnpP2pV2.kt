package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Endpoint-aware MSNP18 P2Pv2 transport for MSNObject display pictures. */
internal class MsnpP2pV2Session(
    private val localEmail: String,
    localEndpointId: String,
    private val remoteEmail: String,
    remoteEndpointId: String,
    private val sendPacket: suspend (ByteArray) -> Unit,
    private val localPicture: () -> LocalDisplayPicture?,
    private val onDisplayPicture: (ByteArray) -> Unit,
    private val localVoiceClip: (String) -> LocalDisplayPicture? = { null },
    private val onVoiceClip: (MsnDisplayPicture, ByteArray) -> Unit = { _, _ -> },
    private val localWink: (String) -> LocalDisplayPicture? = { null },
    private val onWink: (MsnDisplayPicture, ByteArray) -> Unit = { _, _ -> },
) {
    val destination = "$remoteEmail;${remoteEndpointId.asP2pGuid()}"
    private val source = "$localEmail;${localEndpointId.asP2pGuid()}"
    private val identifiers = AtomicLong((System.nanoTime() and 0x3fff_ffffL).coerceAtLeast(1))
    private val sessionIds = AtomicInteger(2_000)
    private val packageNumbers = AtomicInteger(0)
    private val incoming = ConcurrentHashMap<Pair<Long, Int>, IncomingV2Message>()
    private val pendingCalls = ConcurrentHashMap<Long, PendingV2Picture>()

    suspend fun requestDisplayPicture(picture: MsnDisplayPicture, retry: Boolean = false) {
        val now = System.nanoTime()
        pendingCalls.entries
            .filter { now - it.value.createdAtNanos >= REQUEST_TIMEOUT_NANOS ||
                (retry && it.value.picture.sha1d == picture.sha1d) }
            .forEach { pendingCalls.remove(it.key, it.value) }
        if (pendingCalls.values.any { it.picture.sha1d == picture.sha1d }) return
        if (pendingCalls.size >= MAX_PENDING_CALLS) return
        val sessionId = nextSessionId()
        val callId = MsnpP2pUtil.randomGuid()
        pendingCalls[sessionId] = PendingV2Picture(callId, picture, now)
        val content = MsnpP2pUtil.displayPictureRequestContent(sessionId, picture)
        sendMessage(
            sessionId = 0,
            firstTf = TF_FIRST,
            operationCode = OP_SYN or OP_RAK,
            footer = 0,
            peerInfo = true,
            body = sipMessage(
                firstLine = "INVITE MSNMSGR:$destination MSNSLP/1.0",
                to = destination,
                from = source,
                branch = MsnpP2pUtil.randomGuid(),
                callId = callId,
                cseq = 0,
                contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                content = content,
            ),
        )
    }

    suspend fun handle(bytes: ByteArray) {
        val now = System.nanoTime()
        incoming.entries
            .filter { now - it.value.createdAtNanos >= REQUEST_TIMEOUT_NANOS }
            .forEach { incoming.remove(it.key, it.value) }
        val packet = P2pV2Packet.parse(bytes) ?: return
        if (packet.operationCode and OP_RAK != 0) sendAcknowledgement(packet)
        if (packet.body.isEmpty()) return

        val baseTf = packet.tfCombination and TF_FIRST.inv()
        val key = packet.sessionId to baseTf
        val message = if (packet.tfCombination and TF_FIRST != 0) {
            if (incoming.size >= MAX_INCOMING_MESSAGES) return
            val total = packet.body.size.toLong() + packet.dataRemaining
            val max = if (packet.sessionId == 0L) MAX_SLP_BYTES else pendingCalls[packet.sessionId]?.picture?.takeIf { it.objectType == 8 }?.size?.coerceAtMost(org.aralozkaya.msngr.protocol.MsnWink.MAX_CAB_BYTES) ?: MAX_PICTURE_BYTES
            if (total !in 0..max.toLong()) return
            IncomingV2Message(ByteArray(total.toInt())).also { incoming[key] = it }
        } else {
            incoming[key] ?: return
        }
        if (!message.add(packet.body, packet.dataRemaining)) {
            incoming.remove(key, message)
            return
        }
        if (!message.complete) return
        incoming.remove(key, message)
        when {
            packet.sessionId == 0L && baseTf == TF_NONE -> handleSlp(message.body)
            baseTf and TF_MSN_OBJECT != 0 -> {
                val pending = pendingCalls.remove(packet.sessionId)
                if (pending?.picture?.objectType in listOf(8, 11)) {
                    if (pending!!.picture.matches(message.body)) {
                        if (pending.picture.objectType == 8) onWink(pending.picture, message.body)
                        else onVoiceClip(pending.picture, message.body)
                        closeObjectSession(pending.callId, packet.sessionId)
                    }
                } else if (pending == null || pending.picture.matches(message.body)) {
                    onDisplayPicture(message.body)
                }
            }
        }
    }

    private suspend fun handleSlp(bytes: ByteArray) {
        val message = SlpMessage.parse(bytes) ?: return
        when {
            message.firstLine.startsWith("INVITE ", ignoreCase = true) -> handleInvite(message)
            message.firstLine.startsWith("MSNSLP/1.0 603", ignoreCase = true) -> {
                pendingCalls.entries
                    .filter { it.value.callId == message.callId }
                    .forEach { pendingCalls.remove(it.key, it.value) }
            }
        }
    }

    private suspend fun handleInvite(message: SlpMessage) {
        if (message.contentType.equals(
                MsnpP2pUtil.TRANSPORT_REQUEST_CONTENT_TYPE,
                ignoreCase = true,
            )
        ) {
            sendTransportFallback(message)
            return
        }
        if (!message.contentType.equals(
                MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                ignoreCase = true,
            )
        ) {
            return
        }
        val sessionId = message.body.field("SessionID")?.toLongOrNull() ?: return
        val picture = MsnpP2pUtil.matchingLocalPicture(message.body, localPicture())
            ?: message.body.field("Context")?.let { context ->
                org.aralozkaya.msngr.protocol.MsnBase64.decode(context)?.let {
                    MsnDisplayPicture.parseObject(String(it, Charsets.UTF_8).trimEnd('\u0000'), 11)
                }
            }?.let { MsnpP2pUtil.matchingLocalPicture(message.body, localVoiceClip(it.sha1d)) }
            ?: message.body.field("Context")?.let { context ->
                org.aralozkaya.msngr.protocol.MsnBase64.decode(context)?.let {
                    org.aralozkaya.msngr.protocol.MsnWink.parseOffer(String(it, Charsets.UTF_8).trimEnd('\u0000'))
                }
            }?.let { MsnpP2pUtil.matchingLocalPicture(message.body, localWink(it.sha1d)) }
        val accepted = picture != null
        sendSlpResponse(message, sessionId, accepted)
        if (!accepted) return
        sendMessage(
            sessionId = sessionId,
            firstTf = TF_FIRST,
            operationCode = OP_NONE,
            footer = 0,
            body = ByteArray(4),
        )
        sendMessage(
            sessionId = sessionId,
            firstTf = TF_MSN_OBJECT or TF_FIRST,
            operationCode = OP_NONE,
            // P2Pv2 keeps the bridge footer zero, including MSNObject data.
            // The application identifier in the footer belongs to P2Pv1 only.
            footer = 0,
            packageNumber = packageNumbers.incrementAndGet() and 0xffff,
            body = picture.bytes,
        )
        if (picture.metadata.objectType in listOf(8, 11)) closeObjectSession(message.callId, sessionId)
    }

    private suspend fun closeObjectSession(callId: String, sessionId: Long) {
        sendMessage(0, TF_FIRST, OP_NONE, 0, body = sipMessage(
            "BYE MSNMSGR:$destination MSNSLP/1.0", destination, source,
            MsnpP2pUtil.randomGuid(), callId, 0, "application/x-msnmsgr-sessionclosebody",
            "SessionID: $sessionId\r\n\r\n"))
    }

    private suspend fun sendTransportFallback(message: SlpMessage) {
        sendMessage(
            sessionId = 0,
            firstTf = TF_FIRST,
            operationCode = OP_NONE,
            footer = 0,
            body = sipMessage(
                firstLine = "MSNSLP/1.0 200 OK",
                to = destination,
                from = source,
                branch = message.branch,
                callId = message.callId,
                cseq = 1,
                contentType = MsnpP2pUtil.TRANSPORT_RESPONSE_CONTENT_TYPE,
                content = MsnpP2pUtil.transportResponseContent(message.body),
            ),
        )
    }

    private suspend fun sendSlpResponse(
        message: SlpMessage,
        sessionId: Long,
        accepted: Boolean,
    ) {
        sendMessage(
            sessionId = 0,
            firstTf = TF_FIRST,
            operationCode = OP_NONE,
            footer = 0,
            body = sipMessage(
                firstLine = if (accepted) "MSNSLP/1.0 200 OK" else "MSNSLP/1.0 603 Decline",
                to = destination,
                from = source,
                branch = message.branch,
                callId = message.callId,
                cseq = 1,
                contentType = message.contentType,
                content = "SessionID: $sessionId\r\n\r\n",
            ),
        )
    }

    private suspend fun sendMessage(
        sessionId: Long,
        firstTf: Int,
        operationCode: Int,
        footer: Int,
        peerInfo: Boolean = false,
        packageNumber: Int = 0,
        body: ByteArray,
    ) {
        var offset = 0
        do {
            val length = minOf(MAX_CHUNK_BYTES, body.size - offset)
            val chunk = body.copyOfRange(offset, offset + length)
            val remaining = (body.size - offset - length).toLong()
            val tf = if (offset == 0) firstTf else firstTf and TF_FIRST.inv()
            val packet = P2pV2Packet(
                operationCode = if (offset == 0) operationCode else OP_NONE,
                identifier = identifiers.get(),
                sessionId = sessionId,
                tfCombination = tf,
                packageNumber = packageNumber,
                dataRemaining = remaining,
                body = chunk,
                footer = footer,
                peerInfo = peerInfo && offset == 0,
            )
            val encoded = packet.encode()
            identifiers.addAndGet(packet.messageSize.toLong())
            sendPacket(encoded)
            offset += length
        } while (offset < body.size)
    }

    private suspend fun sendAcknowledgement(packet: P2pV2Packet) {
        // Only a data-bearing SYN copies peer info and requests the reciprocal sync ACK.
        // An ACK frame has MessageSize=0 and must not start an endless SYN/RAK exchange.
        val sync = packet.messageSize > 0 && packet.operationCode and OP_SYN != 0
        val acknowledgement = P2pV2Packet(
            operationCode = if (sync) OP_SYN or OP_RAK else OP_NONE,
            identifier = identifiers.get(),
            acknowledgementIdentifier = (packet.identifier + packet.messageSize) and UINT_MASK,
            peerInfo = sync,
        )
        sendPacket(acknowledgement.encode())
    }

    private fun nextSessionId(): Long = (sessionIds.incrementAndGet().toLong() and UINT_MASK)
    private data class PendingV2Picture(
        val callId: String,
        val picture: MsnDisplayPicture,
        val createdAtNanos: Long,
    )

    private companion object {
        const val OP_NONE = 0
        const val OP_SYN = 1
        const val OP_RAK = 2
        const val TF_NONE = 0
        const val TF_FIRST = 1
        const val TF_MSN_OBJECT = 4
        const val MAX_CHUNK_BYTES = 1_100
        const val MAX_SLP_BYTES = 16 * 1024
        const val MAX_PICTURE_BYTES = org.aralozkaya.msngr.protocol.DisplayPictureLimits.MAX_BYTES
        const val MAX_INCOMING_MESSAGES = 8
        const val MAX_PENDING_CALLS = 8
        const val REQUEST_TIMEOUT_NANOS = 30_000_000_000L
        const val UINT_MASK = 0xffff_ffffL
    }
}

private class IncomingV2Message(val body: ByteArray) {
    val createdAtNanos: Long = System.nanoTime()
    private var received = 0
    val complete get() = received == body.size

    fun add(chunk: ByteArray, remaining: Long): Boolean {
        if (received + chunk.size > body.size) return false
        if ((body.size - received - chunk.size).toLong() != remaining) return false
        chunk.copyInto(body, received)
        received += chunk.size
        return true
    }
}

internal data class P2pV2Packet(
    val operationCode: Int = 0,
    val identifier: Long,
    val acknowledgementIdentifier: Long? = null,
    val sessionId: Long = 0,
    val tfCombination: Int = 0,
    val packageNumber: Int = 0,
    val dataRemaining: Long = 0,
    val body: ByteArray = byteArrayOf(),
    val footer: Int = 0,
    val peerInfo: Boolean = false,
) {
    val messageSize: Int get() = if (body.isEmpty()) 0 else dataHeaderLength + body.size
    private val mainHeaderLength: Int
        get() = padded(8 + (if (peerInfo) 14 else 0) + (if (acknowledgementIdentifier != null) 6 else 0))
    private val dataHeaderLength: Int
        get() = if (body.isEmpty()) 0 else padded(8 + if (dataRemaining > 0) 10 else 0)

    fun encode(): ByteArray {
        require(messageSize <= 0xffff)
        val buffer = ByteBuffer.allocate(mainHeaderLength + messageSize + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.put(mainHeaderLength.toByte())
        buffer.put(operationCode.toByte())
        buffer.putShort(messageSize.toShort())
        buffer.putInt(identifier.toInt())
        if (peerInfo) {
            buffer.put(1).put(12)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
                .putShort(512).putShort(0).putShort(3584).putShort(0).putInt(271)
                .order(ByteOrder.BIG_ENDIAN)
        }
        acknowledgementIdentifier?.let {
            buffer.put(2).put(4).putInt(it.toInt())
        }
        while (buffer.position() < mainHeaderLength) buffer.put(0)
        if (body.isNotEmpty()) {
            buffer.put(dataHeaderLength.toByte())
            buffer.put(tfCombination.toByte())
            buffer.putShort(packageNumber.toShort())
            buffer.putInt(sessionId.toInt())
            if (dataRemaining > 0) buffer.put(1).put(8).putLong(dataRemaining)
            while (buffer.position() < mainHeaderLength + dataHeaderLength) buffer.put(0)
            buffer.put(body)
        }
        buffer.putInt(footer)
        return buffer.array()
    }

    companion object {
        fun looksLike(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val headerLength = bytes[0].toInt() and 0xff
            if (headerLength !in 8..252 || headerLength % 4 != 0) return false
            val messageSize = ByteBuffer.wrap(bytes, 2, 2).order(ByteOrder.BIG_ENDIAN)
                .short.toInt() and 0xffff
            return headerLength + messageSize + 4 == bytes.size
        }

        fun parse(bytes: ByteArray): P2pV2Packet? = runCatching {
            if (!looksLike(bytes)) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val mainHeaderLength = buffer.get().toInt() and 0xff
            val operationCode = buffer.get().toInt() and 0xff
            val messageSize = buffer.short.toInt() and 0xffff
            val identifier = buffer.int.toLong() and 0xffff_ffffL
            var acknowledgementIdentifier: Long? = null
            var peerInfo = false
            parseTlvs(buffer, mainHeaderLength) { type, value ->
                when {
                    type == 1 && value.size == 12 -> peerInfo = true
                    type == 2 && value.size == 4 -> acknowledgementIdentifier =
                        ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
                }
            }
            buffer.position(mainHeaderLength)
            var sessionId = 0L
            var tf = 0
            var packageNumber = 0
            var dataRemaining = 0L
            var body = byteArrayOf()
            if (messageSize > 0) {
                val dataHeaderLength = buffer.get().toInt() and 0xff
                if (dataHeaderLength !in 8..messageSize || dataHeaderLength % 4 != 0) return null
                tf = buffer.get().toInt() and 0xff
                packageNumber = buffer.short.toInt() and 0xffff
                sessionId = buffer.int.toLong() and 0xffff_ffffL
                parseTlvs(buffer, mainHeaderLength + dataHeaderLength) { type, value ->
                    if (type == 1 && value.size == 8) {
                        dataRemaining = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).long
                    }
                }
                buffer.position(mainHeaderLength + dataHeaderLength)
                body = ByteArray(messageSize - dataHeaderLength).also(buffer::get)
            }
            buffer.position(mainHeaderLength + messageSize)
            val footer = buffer.int
            P2pV2Packet(
                operationCode = operationCode,
                identifier = identifier,
                acknowledgementIdentifier = acknowledgementIdentifier,
                sessionId = sessionId,
                tfCombination = tf,
                packageNumber = packageNumber,
                dataRemaining = dataRemaining,
                body = body,
                footer = footer,
                peerInfo = peerInfo,
            )
        }.getOrNull()

        private fun parseTlvs(
            buffer: ByteBuffer,
            end: Int,
            consume: (Int, ByteArray) -> Unit,
        ) {
            while (buffer.position() + 2 <= end) {
                val type = buffer.get().toInt() and 0xff
                if (type == 0) break
                val length = buffer.get().toInt() and 0xff
                if (buffer.position() + length > end) break
                val value = ByteArray(length).also(buffer::get)
                consume(type, value)
            }
        }

        private fun padded(value: Int): Int = (value + 3) and 3.inv()
    }
}

private fun String.asP2pGuid(): String = "{${trim().trim('{', '}')}}"
