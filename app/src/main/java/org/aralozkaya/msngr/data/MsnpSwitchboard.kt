package org.aralozkaya.msngr.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.MessengerPlusTextCodec
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.MsnInkCodec
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.aralozkaya.msngr.trimMessengerPlusWhitespace
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class SwitchboardInvitation(
    val host: String,
    val port: Int,
    val authKey: String,
    val sessionId: String? = null,
    val inviterEmail: String? = null,
)

internal sealed interface SwitchboardEvent {
    data object ContactResolved : SwitchboardEvent
    data class Text(val senderEmail: String, val text: String, val style: NativeTextStyle = NativeTextStyle()) : SwitchboardEvent
    data class Ink(val senderEmail: String, val drawing: InkDrawing) : SwitchboardEvent
    data class VoiceClipOffered(val senderEmail: String, val metadata: MsnDisplayPicture) : SwitchboardEvent
    data class VoiceClipReceived(val senderEmail: String, val metadata: MsnDisplayPicture, val bytes: ByteArray) : SwitchboardEvent
    data class WinkOffered(val senderEmail: String, val metadata: MsnDisplayPicture) : SwitchboardEvent
    data class WinkReceived(val senderEmail: String, val metadata: MsnDisplayPicture, val bytes: ByteArray) : SwitchboardEvent
    data class Typing(val senderEmail: String) : SwitchboardEvent
    data class Nudge(val senderEmail: String) : SwitchboardEvent
    data class DisplayPicture(val senderEmail: String, val bytes: ByteArray) : SwitchboardEvent
    data object Closed : SwitchboardEvent
}

/** A single MSNP18 one-to-one switchboard connection. */
internal class MsnpSwitchboardSession(
    private val signedInEmail: String,
    private val machineGuid: String,
    contactEmail: String,
    private val invitation: SwitchboardInvitation,
    private val transport: MsnpTransport,
    private val scope: CoroutineScope,
    private val eventSink: (SwitchboardEvent) -> Unit,
    private val localPicture: () -> LocalDisplayPicture? = { null },
    private val remoteEndpointId: String? = null,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    sharedPictureSession: MsnpP2pSession? = null,
    private val localVoiceClip: (String) -> LocalDisplayPicture? = { null },
    private val localWink: (String) -> LocalDisplayPicture? = { null },
    private val cachedWink: (MsnDisplayPicture) -> ByteArray? = { null },
    private val acceptWink: () -> Boolean = { true },
) : AutoCloseable {
    // A sibling endpoint can invite us before it calls the conversation's contact.
    @Volatile var contactEmail: String = contactEmail.normalizedSwitchboardIdentity()
        private set
    internal val isClosed: Boolean get() = closed.get()
    private val transactionIds = AtomicInteger(0)
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val participants = mutableSetOf<String>()
    private data class OutgoingPicturePacket(val destination: String, val bytes: ByteArray)
    private val outgoingP2pPackets = Channel<OutgoingPicturePacket>(Channel.UNLIMITED)
    private val otherPicturePeers = ConcurrentHashMap<String, MsnpP2pSession>()
    private val winkPeers = ConcurrentHashMap<String, MsnpP2pSession>()
    private val pendingOwnMessages = ArrayDeque<MsnpCommand>()
    private val chunkAssembler = SwitchboardChunkAssembler()
    private val pendingMessageAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    internal val pictureSession by lazy {
        check(this.contactEmail.isNotEmpty()) { "The switchboard has no conversation contact." }
        sharedPictureSession ?: MsnpP2pSession(
            localEmail = signedInEmail,
            remoteEmail = this.contactEmail,
            sendPacket = { packet ->
                outgoingP2pPackets.send(OutgoingPicturePacket(remoteEndpointId?.let { "${this.contactEmail};{$it}" } ?: this.contactEmail, packet))
            },
            localPicture = localPicture,
            onDisplayPicture = { bytes ->
                eventSink(SwitchboardEvent.DisplayPicture(this.contactEmail, bytes))
            },
            localEndpointId = machineGuid,
            remoteEndpointId = remoteEndpointId,
            localVoiceClip = localVoiceClip,
            localWink = localWink,
            onWink = { metadata, bytes -> eventSink(SwitchboardEvent.WinkReceived(this.contactEmail, metadata, bytes)) },
            onVoiceClip = { metadata, bytes -> eventSink(SwitchboardEvent.VoiceClipReceived(this.contactEmail, metadata, bytes)) },
        )
    }

    init { sharedPictureSession?.bindPacketSender { packet ->
        outgoingP2pPackets.send(OutgoingPicturePacket(sharedPictureSession.destination, packet))
    } }

    internal fun resumePictureTransfers() {
        pictureSession.bindPacketSender { packet -> outgoingP2pPackets.send(OutgoingPicturePacket(pictureSession.destination, packet)) }
    }
    private var receiverJob: Job? = null
    private var p2pSenderJob: Job? = null

    suspend fun connect() {
        transport.connect(invitation.host, invitation.port)
        if (invitation.sessionId == null) connectOutgoing() else connectIncoming()
        check(!closed.get()) { "The switchboard is closed." }
        transport.setReadTimeout(0)
        receiverJob = scope.launch {
            try {
                while (isActive && !closed.get()) handle(transport.receive())
            } catch (_: Exception) {
                // A closed switchboard is normal after the remote participant leaves.
            } finally {
                close()
            }
        }
        p2pSenderJob = scope.launch {
            try {
                for (packet in outgoingP2pPackets) sendP2pPacket(packet)
            } catch (_: Exception) {
                close()
            }
        }
    }

    suspend fun sendText(text: String, style: NativeTextStyle = NativeTextStyle()) {
        val encoded = MessengerPlusTextCodec.normalizeProfileMarkupForChat(text)
        val normalized = encoded.trimMessengerPlusWhitespace().text
        if (normalized.isEmpty()) return
        sendPayload(Msnp18Protocol.textPayload(normalized, style), acknowledgement = "A")
    }

    suspend fun sendTyping() {
        sendPayload(Msnp18Protocol.typingPayload(signedInEmail), acknowledgement = "U")
    }

    suspend fun sendNudge() {
        sendPayload(Msnp18Protocol.nudgePayload(), acknowledgement = "N")
    }

    suspend fun sendInk(drawing: InkDrawing, useGif: Boolean = false) {
        val payloads = Msnp18Protocol.inkPayloads(drawing = drawing, useGif = useGif)
        for (payload in payloads) {
            sendPayload(payload, acknowledgement = "A")
        }
    }

    suspend fun sendWink(metadata: MsnDisplayPicture) {
        for (payload in org.aralozkaya.msngr.protocol.MsnWink.payloads(metadata)) sendPayload(payload, "A")
    }

    suspend fun retryWink(metadata: MsnDisplayPicture) {
        check(!closed.get()) { "The conversation is disconnected. Try again." }
        val sender = metadata.creator.normalizedSwitchboardIdentity()
        check(sender == contactEmail) { "The wink sender does not match this conversation." }
        val cached = cachedWink(metadata)
        if (cached != null) eventSink(SwitchboardEvent.WinkReceived(sender, metadata, cached))
        else (winkPeers[sender] ?: pictureSession).requestDisplayPicture(metadata, retry = true)
    }

    suspend fun sendVoiceClip(metadata: MsnDisplayPicture) {
        sendPayload(Msnp18Protocol.voiceClipPayload(metadata), acknowledgement = "A")
    }
    private fun unusedSendInkMarker() {
    }

    suspend fun requestDisplayPicture(picture: MsnDisplayPicture) {
        pictureSession.requestDisplayPicture(picture)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        receiverJob?.cancel()
        receiverJob = null
        p2pSenderJob?.cancel()
        p2pSenderJob = null
        outgoingP2pPackets.close()
        pendingMessageAcks.values.forEach { it.cancel() }
        pendingMessageAcks.clear()
        transport.close()
        eventSink(SwitchboardEvent.Closed)
    }

    private suspend fun connectOutgoing() {
        val authTransaction = nextTransactionId()
        send(
            Msnp18Protocol.switchboardAuthenticate(
                transactionId = authTransaction,
                identity = switchboardIdentity(),
                authKey = invitation.authKey,
            ),
        )
        await(authTransaction, "USR")

        // WLM 2009/MSNP18 calls its own account first so the user's other endpoints join.
        val selfInviteTransaction = nextTransactionId()
        send(Msnp18Protocol.switchboardInvite(selfInviteTransaction, signedInEmail))
        await(selfInviteTransaction, "CAL")

        if (!contactEmail.equals(signedInEmail, true)) {
            val contactInviteTransaction = nextTransactionId()
            send(Msnp18Protocol.switchboardInvite(contactInviteTransaction, contactEmail))
            await(contactInviteTransaction, "CAL")
        }
        awaitParticipant(contactEmail)
    }

    private suspend fun connectIncoming() {
        val answerTransaction = nextTransactionId()
        send(
            Msnp18Protocol.switchboardAnswer(
                transactionId = answerTransaction,
                identity = switchboardIdentity(),
                authKey = invitation.authKey,
                sessionId = checkNotNull(invitation.sessionId),
            ),
        )
        await(answerTransaction, "ANS")
    }

    private suspend fun sendPayload(payload: ByteArray, acknowledgement: String) {
        check(!closed.get()) { "The switchboard is closed." }
        require(payload.size <= MAX_SWITCHBOARD_PAYLOAD_BYTES) {
            "The message is too long for an MSNP switchboard."
        }
        send(
            Msnp18Protocol.switchboardMessage(
                transactionId = nextTransactionId(),
                payload = payload,
                acknowledgement = acknowledgement,
            ),
        )
    }

    private suspend fun await(transactionId: Int, expectedVerb: String): MsnpCommand =
        withTimeout(handshakeTimeoutMillis) {
            while (true) {
                val command = transport.receive()
                if (command.transactionId == transactionId) {
                    if (command.verb.firstOrNull()?.isDigit() == true) {
                        throw SwitchboardException("The switchboard returned MSNP error ${command.verb}.", command.verb.toIntOrNull())
                    }
                    if (command.verb == expectedVerb) return@withTimeout command
                }
                handle(command)
            }
            error("Unreachable")
        }

    private suspend fun awaitParticipant(email: String) = withTimeout(handshakeTimeoutMillis) {
        val normalized = email.normalizedSwitchboardIdentity()
        while (normalized !in participants) handle(transport.receive())
    }

    private suspend fun handle(command: MsnpCommand) {
        when (command.verb) {
            "ACK" -> command.transactionId?.let { transactionId ->
                pendingMessageAcks.remove(transactionId)?.complete(Unit)
            }
            "NAK" -> command.transactionId?.let { transactionId ->
                pendingMessageAcks.remove(transactionId)?.completeExceptionally(
                    SwitchboardException("The switchboard rejected a P2P packet."),
                )
            }
            "IRO" -> command.parameters.getOrNull(2)?.let { addParticipant(it) }
            "JOI" -> command.parameters.firstOrNull()?.let { addParticipant(it) }
            "BYE" -> {
                val departed = command.parameters.firstOrNull()?.normalizedSwitchboardIdentity()
                if (departed == contactEmail.normalizedSwitchboardIdentity()) close()
            }
            "MSG" -> handleMessage(command)
            else -> if (command.verb.firstOrNull()?.isDigit() == true) {
                command.transactionId?.let { transactionId ->
                    pendingMessageAcks.remove(transactionId)?.completeExceptionally(
                        SwitchboardException("The switchboard returned MSNP error ${command.verb}."),
                    )
                }
            }
        }
    }

    private suspend fun handleMessage(command: MsnpCommand) {
        val rawSender = command.parameters.firstOrNull().orEmpty()
        val sender = rawSender.normalizedSwitchboardIdentity()
        if (sender.isEmpty()) return
        val mine = sender == signedInEmail.normalizedSwitchboardIdentity()
        if (mine && normalizeEndpointId(rawSender.substringAfter(';', "")) == normalizeEndpointId(machineGuid)) return
        val reassembledPayload = chunkAssembler.feed(rawSender, command.payload ?: return) ?: return
        val message = parseSwitchboardMime(reassembledPayload)
        if (mine && normalizeEndpointId(rawSender.substringAfter(';', "")) == normalizeEndpointId(machineGuid)) return
        if (mine && contactEmail == sender && message !is SwitchboardMimeMessage.P2p) return
        // Keep sibling picture controls routable without treating them as conversation history.
        if (mine && message !is SwitchboardMimeMessage.Text && message !is SwitchboardMimeMessage.Ink &&
            message !is SwitchboardMimeMessage.VoiceClipOffer && message !is SwitchboardMimeMessage.WinkOffer &&
            message != SwitchboardMimeMessage.Nudge &&
            message !is SwitchboardMimeMessage.P2p) return
        if (contactEmail.isEmpty() && message !is SwitchboardMimeMessage.P2p) {
            if (mine) {
                check(pendingOwnMessages.size < MAX_PENDING_OWN_MESSAGES) { "The switchboard has no conversation contact." }
                pendingOwnMessages.addLast(command.copy(payload = reassembledPayload))
                return
            }
            addParticipant(sender)
        }
        if (!mine && sender != contactEmail && message !is SwitchboardMimeMessage.P2p) return
        when (message) {
            is SwitchboardMimeMessage.Text -> eventSink(SwitchboardEvent.Text(sender, message.value, message.style))
            is SwitchboardMimeMessage.Ink -> eventSink(SwitchboardEvent.Ink(sender, message.drawing))
            is SwitchboardMimeMessage.VoiceClipOffer -> {
                if (!message.metadata.creator.equals(sender, true)) return
                eventSink(SwitchboardEvent.VoiceClipOffered(sender, message.metadata))
                if (!closed.get()) p2pPeer(sender, normalizeEndpointId(rawSender.substringAfter(';', "")))
                    ?.requestVoiceClip(message.metadata)
            }
            is SwitchboardMimeMessage.WinkOffer -> {
                if (!message.metadata.creator.equals(sender, true) || !acceptWink()) return
                eventSink(SwitchboardEvent.WinkOffered(sender, message.metadata))
                val cached = cachedWink(message.metadata)
                if (cached != null) eventSink(SwitchboardEvent.WinkReceived(sender, message.metadata, cached))
                else if (!closed.get()) p2pPeer(sender, normalizeEndpointId(rawSender.substringAfter(';', "")))
                    ?.also { winkPeers[sender] = it }?.requestDisplayPicture(message.metadata)
            }
            SwitchboardMimeMessage.Typing -> if (!mine) eventSink(SwitchboardEvent.Typing(sender))
            SwitchboardMimeMessage.Nudge -> eventSink(SwitchboardEvent.Nudge(sender))
            is SwitchboardMimeMessage.P2p -> {
                val destination = message.destination
                val endpoint = destination?.substringAfter(';', "")?.takeIf(String::isNotBlank)
                if ((destination == null || destination.normalizedSwitchboardIdentity() == signedInEmail.normalizedSwitchboardIdentity()) &&
                    (endpoint == null || normalizeEndpointId(endpoint) == normalizeEndpointId(machineGuid))) {
                    val source = message.source ?: rawSender
                    if (source.normalizedSwitchboardIdentity() != sender) return
                    val sourceEndpoint = normalizeEndpointId(source.substringAfter(';', ""))
                    if (mine && sourceEndpoint == normalizeEndpointId(machineGuid)) return
                    val peer = p2pPeer(sender, sourceEndpoint) ?: return
                    peer.handle(message.bytes)
                }
            }
            SwitchboardMimeMessage.Unsupported -> Unit
        }
    }

    private fun p2pPeer(sender: String, sourceEndpoint: String?): MsnpP2pSession? {
        if (sender == contactEmail.normalizedSwitchboardIdentity() &&
            (sourceEndpoint == null || sourceEndpoint == normalizeEndpointId(pictureSession.destination.substringAfter(';', ""))))
            return pictureSession
        val identity = sourceEndpoint?.let { "$sender;{$it}" } ?: sender
        if (!otherPicturePeers.containsKey(identity) && otherPicturePeers.size >= 16) return null
        return otherPicturePeers.computeIfAbsent(identity) {
            MsnpP2pSession(signedInEmail, sender,
                sendPacket = { outgoingP2pPackets.send(OutgoingPicturePacket(identity, it)) },
                localPicture = localPicture,
                onDisplayPicture = { eventSink(SwitchboardEvent.DisplayPicture(sender, it)) },
                localEndpointId = machineGuid, remoteEndpointId = sourceEndpoint,
                localVoiceClip = { hash ->
                    if (sender == contactEmail || sender.equals(signedInEmail, true)) localVoiceClip(hash) else null
                },
                localWink = { hash -> if (sender == contactEmail || sender.equals(signedInEmail, true)) localWink(hash) else null },
                onWink = { metadata, bytes -> eventSink(SwitchboardEvent.WinkReceived(sender, metadata, bytes)) },
                onVoiceClip = { metadata, bytes -> eventSink(SwitchboardEvent.VoiceClipReceived(sender, metadata, bytes)) })
        }
    }

    private suspend fun addParticipant(rawIdentity: String) {
        val identity = rawIdentity.normalizedSwitchboardIdentity()
        participants += identity
        if (contactEmail.isEmpty() && identity.isNotEmpty() &&
            identity != signedInEmail.normalizedSwitchboardIdentity()
        ) {
            contactEmail = identity
            eventSink(SwitchboardEvent.ContactResolved)
            while (pendingOwnMessages.isNotEmpty()) handleMessage(pendingOwnMessages.removeFirst())
        }
    }

    private suspend fun send(command: MsnpCommand) {
        sendMutex.withLock { transport.send(command) }
    }

    private suspend fun sendP2pPacket(packet: OutgoingPicturePacket) {
        val payload = Msnp18Protocol.p2pPayload(packet.destination, packet.bytes, switchboardIdentity())
        val transactionId = nextTransactionId()
        val acknowledgement = CompletableDeferred<Unit>()
        check(pendingMessageAcks.putIfAbsent(transactionId, acknowledgement) == null)
        try {
            send(
                Msnp18Protocol.switchboardMessage(
                    transactionId = transactionId,
                    payload = payload,
                    acknowledgement = "D",
                ),
            )
            withTimeout(P2P_MESSAGE_ACK_TIMEOUT_MILLIS) { acknowledgement.await() }
        } finally {
            pendingMessageAcks.remove(transactionId, acknowledgement)
        }
    }

    private fun switchboardIdentity(): String =
        "$signedInEmail;{${machineGuid.trim('{', '}')}}"

    private fun nextTransactionId(): Int = transactionIds.incrementAndGet()

    private companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 20_000L
        const val P2P_MESSAGE_ACK_TIMEOUT_MILLIS = 20_000L
        const val MAX_SWITCHBOARD_PAYLOAD_BYTES = 1_664
        const val MAX_PENDING_OWN_MESSAGES = 64
    }
}

internal class SwitchboardException(message: String, val protocolCode: Int? = null) : Exception(message)

internal sealed interface SwitchboardMimeMessage {
    data class Text(val value: String, val style: NativeTextStyle = NativeTextStyle()) : SwitchboardMimeMessage
    data class Ink(val drawing: InkDrawing) : SwitchboardMimeMessage
    data class WinkOffer(val metadata: MsnDisplayPicture) : SwitchboardMimeMessage
    data class VoiceClipOffer(val metadata: MsnDisplayPicture) : SwitchboardMimeMessage
    data class P2p(val destination: String?, val bytes: ByteArray, val source: String? = null) : SwitchboardMimeMessage
    data object Typing : SwitchboardMimeMessage
    data object Nudge : SwitchboardMimeMessage
    data object Unsupported : SwitchboardMimeMessage
}

internal fun parseSwitchboardMime(payload: ByteArray): SwitchboardMimeMessage {
    val delimiter = payload.indexOfMimeDelimiter()
    if (delimiter < 0) return SwitchboardMimeMessage.Unsupported
    val headerText = String(payload, 0, delimiter, StandardCharsets.UTF_8)
    val headers = headerText.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null
            else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }
        .toMap()
    val contentType = headers["content-type"]
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return SwitchboardMimeMessage.Unsupported
    val bodyStart = delimiter + MIME_DELIMITER.size
    val body = payload.copyOfRange(bodyStart, payload.size)

    return when (contentType) {
        "text/plain" -> SwitchboardMimeMessage.Text(String(body, StandardCharsets.UTF_8),
            NativeTextStyle.fromFormatHeader(headers["x-mms-im-format"]))
        Msnp18Protocol.TYPING_CONTENT_TYPE -> SwitchboardMimeMessage.Typing
        Msnp18Protocol.INK_ISF_CONTENT_TYPE,
        Msnp18Protocol.INK_GIF_CONTENT_TYPE -> {
            MsnInkCodec.decodeMimeBody(contentType, body)?.let(SwitchboardMimeMessage::Ink)
                ?: SwitchboardMimeMessage.Unsupported
        }
        Msnp18Protocol.NUDGE_CONTENT_TYPE -> {
            val cast = String(body, StandardCharsets.UTF_8)
            when (cast.field("ID")) {
                "1" -> SwitchboardMimeMessage.Nudge
                "2" -> org.aralozkaya.msngr.protocol.MsnWink.parseOffer(cast.field("Data"))?.let(SwitchboardMimeMessage::WinkOffer) ?: SwitchboardMimeMessage.Unsupported
                "3" -> MsnDisplayPicture.parseObject(cast.field("Data"), 11)?.takeIf {
                    it.size in 60..org.aralozkaya.msngr.protocol.MsnVoiceClip.MAX_WAV_BYTES &&
                        org.aralozkaya.msngr.protocol.MsnBase64.decode(it.sha1d)?.size == 20
                }?.let(SwitchboardMimeMessage::VoiceClipOffer) ?: SwitchboardMimeMessage.Unsupported
                else -> SwitchboardMimeMessage.Unsupported
            }
        }
        Msnp18Protocol.P2P_CONTENT_TYPE -> SwitchboardMimeMessage.P2p(
            destination = headers["p2p-dest"],
            bytes = body,
            source = headers["p2p-src"],
        )
        else -> SwitchboardMimeMessage.Unsupported
    }
}

private fun ByteArray.indexOfMimeDelimiter(): Int {
    for (index in 0..size - MIME_DELIMITER.size) {
        if (MIME_DELIMITER.indices.all { offset -> this[index + offset] == MIME_DELIMITER[offset] }) {
            return index
        }
    }
    return -1
}

private fun String.normalizedSwitchboardIdentity(): String =
    substringBefore(';').substringAfter(':').trim().lowercase()

private val MIME_DELIMITER = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

internal class SwitchboardChunkAssembler {
    private data class PartialChunkedMessage(
        val totalChunks: Int,
        val headerBlock: ByteArray,
        val chunks: Array<ByteArray?>,
    )

    private val pending = LinkedHashMap<String, PartialChunkedMessage>()

    fun feed(rawSender: String, payload: ByteArray): ByteArray? {
        val delimiter = payload.indexOfMimeDelimiter()
        if (delimiter < 0) return payload
        val headerText = String(payload, 0, delimiter, StandardCharsets.UTF_8)
        val headers = headerText.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()
        val messageId = headers["message-id"]?.trim()?.lowercase()
        val totalChunksText = headers["chunks"]?.trim()
        val chunkIndexText = headers["chunk"]?.trim()
        if (messageId.isNullOrEmpty() || (totalChunksText == null && chunkIndexText == null)) {
            return payload
        }
        val body = payload.copyOfRange(delimiter + MIME_DELIMITER.size, payload.size)
        val key = "${rawSender.trim().lowercase()}|$messageId"

        if (totalChunksText != null) {
            val totalChunks = totalChunksText.toIntOrNull() ?: return null
            if (totalChunks <= 1) return payload
            if (totalChunks > Msnp18Protocol.MAX_INK_CHUNKS) return null
            val existing = pending[key]
            val state = if (existing != null && existing.totalChunks == totalChunks) {
                existing.copy(headerBlock = payload.copyOfRange(0, delimiter + MIME_DELIMITER.size))
            } else {
                PartialChunkedMessage(
                    totalChunks = totalChunks,
                    headerBlock = payload.copyOfRange(0, delimiter + MIME_DELIMITER.size),
                    chunks = arrayOfNulls(totalChunks),
                )
            }
            state.chunks[0] = body
            pending[key] = state
            while (pending.size > MAX_PARTIAL_CHUNKED_MESSAGES) {
                val eldest = pending.keys.firstOrNull() ?: break
                pending.remove(eldest)
            }
            return tryFinish(key, state)
        }

        val chunkIndex = chunkIndexText?.toIntOrNull() ?: return null
        val state = pending[key] ?: return null
        if (chunkIndex !in 1 until state.totalChunks) return null
        state.chunks[chunkIndex] = body
        return tryFinish(key, state)
    }

    private fun tryFinish(key: String, state: PartialChunkedMessage): ByteArray? {
        if (state.chunks.any { it == null } || state.headerBlock.isEmpty()) return null
        pending.remove(key)
        val totalBodySize = state.chunks.sumOf { it!!.size }
        val result = ByteArray(state.headerBlock.size + totalBodySize)
        System.arraycopy(state.headerBlock, 0, result, 0, state.headerBlock.size)
        var offset = state.headerBlock.size
        for (chunk in state.chunks) {
            val bytes = chunk!!
            System.arraycopy(bytes, 0, result, offset, bytes.size)
            offset += bytes.size
        }
        return result
    }

    private companion object {
        const val MAX_PARTIAL_CHUNKED_MESSAGES = 16
    }
}
