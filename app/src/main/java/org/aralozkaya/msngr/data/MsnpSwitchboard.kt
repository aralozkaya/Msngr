package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.NativeTextStyle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.MessengerPlusTextCodec
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
    val contactEmail: String,
    private val invitation: SwitchboardInvitation,
    private val transport: MsnpTransport,
    private val scope: CoroutineScope,
    private val eventSink: (SwitchboardEvent) -> Unit,
    private val localPicture: () -> LocalDisplayPicture? = { null },
    private val remoteEndpointId: String? = null,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    sharedPictureSession: MsnpP2pSession? = null,
) : AutoCloseable {
    private val transactionIds = AtomicInteger(0)
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val participants = mutableSetOf<String>()
    private val outgoingP2pPackets = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingMessageAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    internal val pictureSession = sharedPictureSession ?: MsnpP2pSession(
            localEmail = signedInEmail,
            remoteEmail = contactEmail,
            sendPacket = { packet ->
                outgoingP2pPackets.send(packet)
            },
            localPicture = localPicture,
            onDisplayPicture = { bytes ->
                eventSink(SwitchboardEvent.DisplayPicture(contactEmail, bytes))
            },
            localEndpointId = machineGuid,
            remoteEndpointId = remoteEndpointId,
        )

    init { resumePictureTransfers() }

    internal fun resumePictureTransfers() {
        pictureSession.bindPacketSender { packet -> outgoingP2pPackets.send(packet) }
    }
    private var receiverJob: Job? = null
    private var p2pSenderJob: Job? = null

    suspend fun connect() {
        transport.connect(invitation.host, invitation.port)
        if (invitation.sessionId == null) connectOutgoing() else connectIncoming()
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

        val contactInviteTransaction = nextTransactionId()
        send(Msnp18Protocol.switchboardInvite(contactInviteTransaction, contactEmail))
        await(contactInviteTransaction, "CAL")
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
            "IRO" -> command.parameters.getOrNull(2)?.let(::addParticipant)
            "JOI" -> command.parameters.firstOrNull()?.let(::addParticipant)
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
        val sender = command.parameters.firstOrNull()?.normalizedSwitchboardIdentity().orEmpty()
        if (sender.isEmpty() || sender == signedInEmail.normalizedSwitchboardIdentity()) return
        when (val message = parseSwitchboardMime(command.payload ?: return)) {
            is SwitchboardMimeMessage.Text -> eventSink(SwitchboardEvent.Text(sender, message.value, message.style))
            SwitchboardMimeMessage.Typing -> eventSink(SwitchboardEvent.Typing(sender))
            SwitchboardMimeMessage.Nudge -> eventSink(SwitchboardEvent.Nudge(sender))
            is SwitchboardMimeMessage.P2p -> {
                if (message.destination == null ||
                    message.destination.normalizedSwitchboardIdentity() ==
                    signedInEmail.normalizedSwitchboardIdentity()
                ) {
                    pictureSession.handle(message.bytes)
                }
            }
            SwitchboardMimeMessage.Unsupported -> Unit
        }
    }

    private fun addParticipant(rawIdentity: String) {
        participants += rawIdentity.normalizedSwitchboardIdentity()
    }

    private suspend fun send(command: MsnpCommand) {
        sendMutex.withLock { transport.send(command) }
    }

    private suspend fun sendP2pPacket(packet: ByteArray) {
        val payload = Msnp18Protocol.p2pPayload(pictureSession.destination, packet)
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
    }
}

internal class SwitchboardException(message: String, val protocolCode: Int? = null) : Exception(message)

internal sealed interface SwitchboardMimeMessage {
    data class Text(val value: String, val style: NativeTextStyle = NativeTextStyle()) : SwitchboardMimeMessage
    data class P2p(val destination: String?, val bytes: ByteArray) : SwitchboardMimeMessage
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
