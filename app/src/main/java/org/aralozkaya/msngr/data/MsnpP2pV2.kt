package org.aralozkaya.msngr.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
    private val fileDirectory: (() -> java.io.File)? = null,
    private val onFileTransfer: (FileTransfer) -> Unit = {},
    private val onPhotoShare: (PhotoShare) -> Unit = {},
    private val localPhoto: (String) -> LocalDisplayPicture? = { null },
    private val publishPhoto: (LocalDisplayPicture) -> Unit = {},
    private val localOriginal: (String) -> OriginalPhoto? = { null },
    private val publishOriginal: (String, OriginalPhoto) -> Unit = { _, _ -> },
    private val onGameSession: (GameSession) -> Unit = {},
    private val onPhotoObjectProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
) {
    val destination = "$remoteEmail;${remoteEndpointId.asP2pGuid()}"
    private val source = "$localEmail;${localEndpointId.asP2pGuid()}"
    private val identifiers = AtomicLong((System.nanoTime() and 0x3fff_ffffL).coerceAtLeast(1))
    private val sessionIds = AtomicInteger(2_000)
    private val packageNumbers = AtomicInteger(0)
    private val localSynSent = AtomicBoolean(false)
    private val sendMutex = Mutex()
    private val incoming = V2MessageAssembler()
    private val pendingCalls = ConcurrentHashMap<Long, PendingObjectRequest>()

    private val photoApplicationLazy = lazy {
        MsnpPhotoSession(source, destination, fileDirectory, onPhotoShare,
            sendControl = { sendApplicationControl(it) },
            sendData = { session, bytes ->
                sendMessage(
                    session,
                    TF_FIRST,
                    OP_NONE,
                    0,
                    packageNumber = packageNumbers.incrementAndGet() and 0xffff,
                    body = bytes,
                )
            }, requestObject = { requestDisplayPicture(it) }, publishObject = publishPhoto, publishOriginal = publishOriginal)
    }
    private val photos get() = photoApplicationLazy.value
    suspend fun startPhotos(items: List<PhotoToShare>): String = photos.start(items)
    fun hasPhotoShare(id: String): Boolean = photoApplicationLazy.isInitialized() && photos.owns(id)
    suspend fun acceptPhotos(id: String) { check(photos.owns(id)); photos.accept() }
    suspend fun stopPhotos(id: String) { if (photos.owns(id)) photos.stop() }
    suspend fun selectPhoto(id: String, photoId: String) { if (photos.owns(id)) photos.select(photoId) }

    private val gameApplicationLazy = lazy {
        MsnpGameSession(
            source = source,
            destination = destination,
            publish = onGameSession,
            sendControl = { sendApplicationControl(it) },
            sendData = { session, _, bytes ->
                sendMessage(
                    session,
                    TF_FIRST,
                    OP_NONE,
                    0,
                    packageNumber = packageNumbers.incrementAndGet() and 0xffff,
                    body = bytes,
                )
            },
        )
    }
    private val games get() = gameApplicationLazy.value
    suspend fun startGame(entry: GameCatalogEntry): String = games.start(entry)
    fun hasGameSession(id: String): Boolean = gameApplicationLazy.isInitialized() && games.owns(id)
    fun hasActiveSessionId(sessionId: Long): Boolean = sessionId != 0L && (
        pendingCalls.containsKey(sessionId) ||
            (photoApplicationLazy.isInitialized() && photos.owns(sessionId)) ||
            (gameApplicationLazy.isInitialized() && games.owns(sessionId)) ||
            (fileApplicationLazy.isInitialized() && files.owns(sessionId))
        )
    fun hasCallId(callId: String): Boolean {
        val normalized = callId.trim('{', '}')
        if (normalized.isEmpty()) return false
        return pendingCalls.values.any { it.callId.trim('{', '}').equals(normalized, ignoreCase = true) } ||
            (photoApplicationLazy.isInitialized() && photos.owns(normalized)) ||
            (gameApplicationLazy.isInitialized() && games.owns(normalized)) ||
            (fileApplicationLazy.isInitialized() && files.owns(normalized))
    }
    suspend fun acceptGame(id: String) { check(games.owns(id)); games.accept() }
    suspend fun stopGame(id: String) { if (games.owns(id)) games.stop() }
    suspend fun initializeGameChannel(id: String) { if (games.owns(id)) games.initializeChannel() }
    suspend fun sendGameData(id: String, payload: String) { if (games.owns(id)) games.sendGameData(payload) }

    private val fileApplicationLazy = lazy {
        MsnpFileSession(source, destination, fileDirectory, onFileTransfer,
            sendControl = { sendApplicationControl(it) }, sendFile = ::sendFileData, originalPhoto = { id ->
                (if (photoApplicationLazy.isInitialized()) photos.localOriginal(id) else null) ?: localOriginal(id) })
    }
    private val files get() = fileApplicationLazy.value
    suspend fun requestPhotoOriginal(photo: SharedPhoto): String = files.requestOriginal(photo)
    suspend fun offerFile(file: java.io.File, name: String): String = files.offer(file, name)
    fun hasFileTransfer(id: String): Boolean = fileApplicationLazy.isInitialized() && files.owns(id)
    suspend fun acceptFile(id: String) { files.accept(id) }
    suspend fun cancelFile(id: String) { files.cancel(id) }
    fun closeFileTransfers() { if (photoApplicationLazy.isInitialized()) photos.disconnect(); if (gameApplicationLazy.isInitialized()) games.disconnect(); if (fileApplicationLazy.isInitialized()) files.close() }

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
        pendingCalls[sessionId] = PendingObjectRequest(callId, picture, now)
        val content = MsnpP2pUtil.displayPictureRequestContent(sessionId, picture)
        sendApplicationControl(sipMessage(
                firstLine = "INVITE MSNMSGR:$destination MSNSLP/1.0",
                to = destination,
                from = source,
                branch = MsnpP2pUtil.randomGuid(),
                callId = callId,
                cseq = 0,
                contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                content = content,
            ))
    }

    suspend fun handle(bytes: ByteArray) {
        incoming.expire()
        val packet = P2pV2Packet.parse(bytes) ?: return
        if (packet.nakIdentifier != null) {
            runCatching {
                if (org.aralozkaya.msngr.BuildConfig.DEBUG) android.util.Log.i(
                    "MsnpP2p",
                    "V2 rx NAK_SEQ nak=${packet.nakIdentifier} localSeq=${identifiers.get()} localSynSent=${localSynSent.get()}",
                )
            }
            withSendLock {
                localSynSent.set(false)
                if (packet.nakIdentifier > 0L) identifiers.set(packet.nakIdentifier and UINT_MASK)
            }
        } else if (packet.operationCode and (OP_SYN or OP_RAK) == (OP_SYN or OP_RAK)) {
            localSynSent.set(true)
        }
        val ownedFileData = packet.sessionId != 0L && (packet.tfCombination and 6 == 6) &&
            fileApplicationLazy.isInitialized() && files.owns(packet.sessionId)
        if (packet.operationCode and OP_RAK != 0 && !ownedFileData) sendAcknowledgement(packet)
        if (packet.acknowledgementIdentifier != null && fileApplicationLazy.isInitialized())
            files.acknowledged(packet.acknowledgementIdentifier)
        if (packet.body.isEmpty()) return

        val baseTf = packet.tfCombination and TF_FIRST.inv()
        if (baseTf == 6 && fileApplicationLazy.isInitialized() && files.owns(packet.sessionId)) {
            val offset = files.received(packet.sessionId)
            val actualOffset = if (packet.tfCombination and TF_FIRST != 0) 0L else offset
            files.receive(packet.sessionId, actualOffset, offset + packet.body.size + packet.dataRemaining, packet.body)
            if (packet.operationCode and OP_RAK != 0) sendAcknowledgement(packet)
            return
        }
        val limit = if (packet.sessionId == 0L) MAX_SLP_BYTES else MsnpP2pUtil.objectByteLimit(pendingCalls[packet.sessionId]?.picture)
        val message = incoming.add(packet, limit) ?: return
        when {
            packet.sessionId == 0L && baseTf == TF_NONE -> handleSlp(message.body)
            packet.sessionId != 0L && photoApplicationLazy.isInitialized() && photos.owns(packet.sessionId) -> photos.receive(message.body)
            packet.sessionId != 0L && gameApplicationLazy.isInitialized() && games.owns(packet.sessionId) -> games.receive(message.body)
            baseTf and TF_MSN_OBJECT != 0 && (baseTf != 6 || pendingCalls.containsKey(packet.sessionId)) -> {
                val pending = pendingCalls.remove(packet.sessionId)
                if (pending?.picture?.objectType in listOf(8, 11, 15)) {
                    if (pending!!.picture.matches(message.body)) {
                        if (pending.picture.objectType == 15) photos.receivedObject(pending.picture, message.body)
                        else if (pending.picture.objectType == 8) onWink(pending.picture, message.body)
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
        runCatching { if (org.aralozkaya.msngr.BuildConfig.DEBUG) android.util.Log.i("MsnpP2p",
            "V2 SLP app=${message.body.field("AppID")?.toIntOrNull()} session=${message.body.field("SessionID")?.toLongOrNull()} type=${when(message.contentType) { MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE -> "session"; MsnpP2pUtil.TRANSPORT_REQUEST_CONTENT_TYPE -> "transport-request"; MsnpP2pUtil.TRANSPORT_RESPONSE_CONTENT_TYPE -> "transport-response"; else -> "other" }} response=${message.firstLine.startsWith("MSNSLP/1.0 200")}") }
        MsnpP2pUtil.dispatchSlp(message, photoApplicationLazy, gameApplicationLazy, fileApplicationLazy, pendingCalls, ::handleInvite)
    }

    private suspend fun handleInvite(message: SlpMessage) {
        if (message.contentType.equals(
                MsnpP2pUtil.TRANSPORT_REQUEST_CONTENT_TYPE,
                ignoreCase = true,
            )
        ) {
            runCatching {
                if (org.aralozkaya.msngr.BuildConfig.DEBUG) android.util.Log.i("MsnpP2p", "V2 rx transreq -> SB fallback")
            }
            sendTransportFallback(message)
            return
        }
        if (message.contentType.equals(
                MsnpP2pUtil.TRANSPORT_RESPONSE_CONTENT_TYPE,
                ignoreCase = true,
            )
        ) {
            runCatching {
                if (org.aralozkaya.msngr.BuildConfig.DEBUG) android.util.Log.i("MsnpP2p", "V2 rx transresp -> 603 Decline")
            }
            sendTransportDecline(message)
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
        val picture = MsnpP2pUtil.matchingLocalPicture(message.body, localPicture(), localVoiceClip, localWink) { hash ->
            (if (photoApplicationLazy.isInitialized()) photos.localObject(hash) else null) ?: localPhoto(hash)
        }
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
        val progressCallback: ((Int) -> Unit)? = if (picture.metadata.objectType == 15) {
            { sent ->
                if (photoApplicationLazy.isInitialized()) {
                    photos.recordObjectSentProgress(
                        picture.metadata.sha1d,
                        sent.toLong(),
                        picture.bytes.size.toLong(),
                    )
                }
                onPhotoObjectProgress(picture.metadata.sha1d, sent.toLong(), picture.bytes.size.toLong())
            }
        } else {
            null
        }
        sendMessage(
            sessionId = sessionId,
            firstTf = TF_MSN_OBJECT or TF_FIRST,
            operationCode = OP_NONE,
            // P2Pv2 keeps the bridge footer zero, including MSNObject data.
            // The application identifier in the footer belongs to P2Pv1 only.
            footer = 0,
            packageNumber = packageNumbers.incrementAndGet() and 0xffff,
            body = picture.bytes,
            onProgress = progressCallback,
        )
        if (picture.metadata.objectType in listOf(8, 11, 15)) closeObjectSession(message.callId, sessionId)
    }

    private suspend fun closeObjectSession(callId: String, sessionId: Long) {
        sendMessage(0, TF_FIRST, OP_NONE, 0, body = sipMessage(
            "BYE MSNMSGR:$destination MSNSLP/1.0", destination, source,
            MsnpP2pUtil.randomGuid(), callId, 0, "application/x-msnmsgr-sessionclosebody",
            "SessionID: $sessionId\r\n\r\n"))
    }

    private suspend fun sendFileData(session: Long, file: java.io.File, active: () -> Boolean, progress: (Long) -> Unit) {
        val size = file.length()
        var offset = 0L
        val packageNumber = packageNumbers.incrementAndGet() and 0xffff
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(MAX_CHUNK_BYTES)
            while (offset < size) {
                check(active()) { "File transfer was cancelled." }
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                check(count > 0) { "The file changed during transfer." }
                val remaining = size - offset - count
                withSendLock {
                    val firstOnBridge = localSynSent.compareAndSet(false, true)
                    val op = (if (remaining == 0L) OP_RAK else OP_NONE) or
                        (if (firstOnBridge) OP_SYN or OP_RAK else OP_NONE)
                    val packet = P2pV2Packet(
                        operationCode = op,
                        identifier = identifiers.getAndAdd(8L + count + if (remaining > 0L) 12L else 0L) and UINT_MASK,
                        sessionId = session,
                        tfCombination = 6 or if (offset == 0L) TF_FIRST else 0,
                        packageNumber = packageNumber,
                        dataRemaining = remaining,
                        body = buffer.copyOf(count),
                        peerInfo = firstOnBridge,
                    )
                    if (remaining == 0L) files.expectAcknowledgement(session, (packet.identifier + packet.messageSize) and UINT_MASK)
                    // Publish before sending: an in-memory or fast peer can acknowledge synchronously.
                    offset += count; progress(offset)
                    sendPacket(packet.encode())
                }
            }
        }
    }

    private suspend fun sendApplicationControl(bytes: ByteArray) {
        sendMessage(0, TF_FIRST, OP_NONE, 0, body = bytes)
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

    private suspend fun sendTransportDecline(message: SlpMessage) {
        val sessionId = message.body.field("SessionID")?.toLongOrNull()
        val content = if (sessionId != null) "SessionID: $sessionId\r\n\r\n" else "\r\n"
        sendMessage(
            sessionId = 0,
            firstTf = TF_FIRST,
            operationCode = OP_NONE,
            footer = 0,
            body = sipMessage(
                firstLine = "MSNSLP/1.0 603 Decline",
                to = destination,
                from = source,
                branch = message.branch,
                callId = message.callId,
                cseq = 1,
                contentType = message.contentType,
                content = content,
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

    private suspend fun <T> withSendLock(block: suspend () -> T): T {
        val current = coroutineContext[SendLockElement]
        if (current != null && this in current.sessions) return block()
        return sendMutex.withLock {
            withContext(SendLockElement((current?.sessions ?: emptySet()) + this)) { block() }
        }
    }

    private suspend fun sendMessage(
        sessionId: Long,
        firstTf: Int,
        operationCode: Int,
        footer: Int,
        peerInfo: Boolean = false,
        packageNumber: Int = 0,
        body: ByteArray,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        withSendLock {
            var offset = 0
            do {
                val length = minOf(MAX_CHUNK_BYTES, body.size - offset)
                val chunk = body.copyOfRange(offset, offset + length)
                val remaining = (body.size - offset - length).toLong()
                val tf = if (offset == 0) firstTf else firstTf and TF_FIRST.inv()
                val firstOnBridge = offset == 0 && chunk.isNotEmpty() && localSynSent.compareAndSet(false, true)
                val op = if (offset == 0) operationCode or (if (firstOnBridge) OP_SYN or OP_RAK else OP_NONE) else OP_NONE
                val packet = P2pV2Packet(
                    operationCode = op,
                    identifier = 0,
                    sessionId = sessionId,
                    tfCombination = tf,
                    packageNumber = packageNumber,
                    dataRemaining = remaining,
                    body = chunk,
                    footer = footer,
                    peerInfo = (peerInfo && offset == 0) || firstOnBridge,
                )
                val assigned = packet.copy(identifier = identifiers.getAndAdd(packet.messageSize.toLong()) and UINT_MASK)
                if (firstOnBridge) {
                    runCatching {
                        if (org.aralozkaya.msngr.BuildConfig.DEBUG) android.util.Log.i(
                            "MsnpP2p",
                            "V2 tx SYN seq=${assigned.identifier} session=$sessionId len=${chunk.size}",
                        )
                    }
                }
                sendPacket(assigned.encode())
                offset += length
                onProgress?.invoke(offset)
            } while (offset < body.size)
        }
    }

    private suspend fun sendAcknowledgement(packet: P2pV2Packet) {
        withSendLock {
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
    }

    private class SendLockElement(val sessions: Set<MsnpP2pV2Session>) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SendLockElement>
    }

    private fun nextSessionId(): Long = (sessionIds.incrementAndGet().toLong() and UINT_MASK)

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
        const val MAX_PENDING_CALLS = 8
        const val REQUEST_TIMEOUT_NANOS = 30_000_000_000L
        const val UINT_MASK = 0xffff_ffffL
    }
}

internal class V2MessageAssembler {
    private val incoming = mutableMapOf<Pair<Long, Int>, IncomingV2Message>()

    @Synchronized fun expire() {
        val now = System.nanoTime()
        incoming.values.removeAll { now - it.createdAtNanos >= 30_000_000_000L }
    }

    @Synchronized fun add(packet: P2pV2Packet, limit: Int): IncomingV2Message? {
        val key = packet.sessionId to (packet.tfCombination and 1.inv())
        val message = if (packet.tfCombination and 1 != 0) {
            val total = packet.body.size.toLong() + packet.dataRemaining
            if (incoming.size >= 8 || total !in 0..limit.toLong()) return null
            IncomingV2Message(ByteArray(total.toInt())).also { incoming[key] = it }
        } else {
            incoming[key] ?: return null
        }
        if (!message.add(packet.body, packet.dataRemaining)) {
            incoming.remove(key)
            return null
        }
        if (!message.complete) return null
        return incoming.remove(key)
    }
}

internal class IncomingV2Message(val body: ByteArray) {
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
    val nakIdentifier: Long? = null,
) {
    val messageSize: Int get() = if (body.isEmpty()) 0 else dataHeaderLength + body.size
    private val mainHeaderLength: Int
        get() = padded(
            8 + (if (peerInfo) 14 else 0) +
                (if (acknowledgementIdentifier != null) 6 else 0) +
                (if (nakIdentifier != null) 6 else 0),
        )
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
        nakIdentifier?.let {
            buffer.put(3).put(4).putInt(it.toInt())
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
            var nakIdentifier: Long? = null
            var peerInfo = false
            parseTlvs(buffer, mainHeaderLength) { type, value ->
                when {
                    type == 1 && value.size == 12 -> peerInfo = true
                    type == 2 && value.size == 4 -> acknowledgementIdentifier =
                        ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
                    type == 3 && value.size == 4 -> nakIdentifier =
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
                nakIdentifier = nakIdentifier,
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
