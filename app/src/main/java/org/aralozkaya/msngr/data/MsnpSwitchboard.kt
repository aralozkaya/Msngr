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
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
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
    private val pendingOwnMessages = ArrayDeque<MsnpCommand>()
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
        val message = parseSwitchboardMime(command.payload ?: return)
        if (mine && normalizeEndpointId(rawSender.substringAfter(';', "")) == normalizeEndpointId(machineGuid)) return
        if (mine && contactEmail == sender && message !is SwitchboardMimeMessage.P2p) return
        // Keep sibling picture controls routable without treating them as conversation history.
        if (mine && message !is SwitchboardMimeMessage.Text && message != SwitchboardMimeMessage.Nudge &&
            message !is SwitchboardMimeMessage.P2p) return
        if (contactEmail.isEmpty() && message !is SwitchboardMimeMessage.P2p) {
            if (mine) {
                check(pendingOwnMessages.size < MAX_PENDING_OWN_MESSAGES) { "The switchboard has no conversation contact." }
                pendingOwnMessages.addLast(command)
                return
            }
            addParticipant(sender)
        }
        if (!mine && sender != contactEmail && message !is SwitchboardMimeMessage.P2p) return
        when (message) {
            is SwitchboardMimeMessage.Text -> eventSink(SwitchboardEvent.Text(sender, message.value, message.style))
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
                    val peer = if (sender == contactEmail.normalizedSwitchboardIdentity() &&
                        (sourceEndpoint == null || sourceEndpoint == normalizeEndpointId(pictureSession.destination.substringAfter(';', "")))) {
                        pictureSession
                    } else {
                        val identity = sourceEndpoint?.let { "$sender;{$it}" } ?: sender
                        if (!otherPicturePeers.containsKey(identity) && otherPicturePeers.size >= 16) return
                        otherPicturePeers.computeIfAbsent(identity) {
                            MsnpP2pSession(signedInEmail, sender,
                                sendPacket = { outgoingP2pPackets.send(OutgoingPicturePacket(identity, it)) },
                                localPicture = localPicture,
                                onDisplayPicture = { eventSink(SwitchboardEvent.DisplayPicture(sender, it)) },
                                localEndpointId = machineGuid, remoteEndpointId = sourceEndpoint)
                        }
                    }
                    peer.handle(message.bytes)
                }
            }
            SwitchboardMimeMessage.Unsupported -> Unit
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
        Msnp18Protocol.NUDGE_CONTENT_TYPE -> if (
            String(body, StandardCharsets.UTF_8)
                .lineSequence()
                .any { it.trim().equals("ID: 1", ignoreCase = true) }
        ) {
            SwitchboardMimeMessage.Nudge
        } else {
            SwitchboardMimeMessage.Unsupported
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
