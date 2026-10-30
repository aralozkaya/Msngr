package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Minimal MSNSLP/P2Pv1 user-tile transport used by MSNP switchboards. */
internal class MsnpP2pSession(
    private val localEmail: String,
    private val remoteEmail: String,
    @Volatile private var sendPacket: suspend (ByteArray) -> Unit,
    private val localPicture: () -> LocalDisplayPicture?,
    private val onDisplayPicture: (ByteArray) -> Unit,
    localEndpointId: String = "",
    remoteEndpointId: String? = null,
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
) {
    private val v2 = remoteEndpointId?.takeIf(String::isNotBlank)?.let {
        MsnpP2pV2Session(
            localEmail = localEmail,
            localEndpointId = localEndpointId,
            remoteEmail = remoteEmail,
            remoteEndpointId = it,
            sendPacket = { packet -> sendPacket(packet) },
            localPicture = localPicture,
            onDisplayPicture = onDisplayPicture,
            localVoiceClip = localVoiceClip,
            onVoiceClip = onVoiceClip,
            localWink = localWink, onWink = onWink,
            fileDirectory = fileDirectory, onFileTransfer = onFileTransfer, onPhotoShare = onPhotoShare, localPhoto = localPhoto, publishPhoto = publishPhoto, localOriginal = localOriginal, publishOriginal = publishOriginal,
        )
    }
    val destination: String get() = v2?.destination ?: remoteEmail

    fun bindPacketSender(sender: suspend (ByteArray) -> Unit) {
        sendPacket = sender
    }
    private val ids = AtomicInteger(1_000)
    private val incoming = ConcurrentHashMap<Pair<Int, Int>, IncomingP2pMessage>()
    private val pendingCalls = ConcurrentHashMap<Int, PendingDisplayPicture>()

    private val photoApplicationLazy = lazy {
        MsnpPhotoSession(localEmail, remoteEmail, fileDirectory, onPhotoShare,
            sendControl = { sendMessage(0, P2P_NO_FLAG, 0, it) },
            sendData = { session, bytes -> sendMessage(session.toInt(), P2P_NO_FLAG, 35, bytes) }, requestObject = { requestDisplayPicture(it) }, publishObject = publishPhoto, publishOriginal = publishOriginal)
    }
    private val photos get() = photoApplicationLazy.value
    suspend fun startPhotos(items: List<PhotoToShare>): String = v2?.startPhotos(items) ?: photos.start(items)
    fun hasPhotoShare(id: String): Boolean = v2?.hasPhotoShare(id) == true || (photoApplicationLazy.isInitialized() && photos.owns(id))
    suspend fun acceptPhotos(id: String) { if (v2?.hasPhotoShare(id) == true) v2.acceptPhotos(id) else { check(photos.owns(id)); photos.accept() } }
    suspend fun stopPhotos(id: String) { if (v2?.hasPhotoShare(id) == true) v2.stopPhotos(id) else if (photos.owns(id)) photos.stop() }
    suspend fun selectPhoto(id: String, photoId: String) { if (v2?.hasPhotoShare(id) == true) v2.selectPhoto(id, photoId) else if (photos.owns(id)) photos.select(photoId) }

    private val fileApplicationLazy = lazy {
        MsnpFileSession(localEmail, remoteEmail, fileDirectory, onFileTransfer,
            sendControl = { sendMessage(0, P2P_NO_FLAG, 0, it) }, sendFile = ::sendFileData, originalPhoto = { id ->
                (if (photoApplicationLazy.isInitialized()) photos.localOriginal(id) else null) ?: localOriginal(id) })
    }
    private val files get() = fileApplicationLazy.value
    suspend fun requestPhotoOriginal(photo: SharedPhoto): String = v2?.requestPhotoOriginal(photo) ?: files.requestOriginal(photo)
    suspend fun offerFile(file: java.io.File, name: String): String = v2?.offerFile(file, name) ?: files.offer(file, name)
    fun hasFileTransfer(id: String): Boolean = v2?.hasFileTransfer(id) == true || (fileApplicationLazy.isInitialized() && files.owns(id))
    suspend fun acceptFile(id: String) { if (v2?.hasFileTransfer(id) == true) v2.acceptFile(id) else files.accept(id) }
    suspend fun cancelFile(id: String) { if (v2?.hasFileTransfer(id) == true) v2.cancelFile(id) else files.cancel(id) }
    fun closeFileTransfers() { if (photoApplicationLazy.isInitialized()) photos.disconnect(); v2?.closeFileTransfers(); if (fileApplicationLazy.isInitialized()) files.close() }

    suspend fun requestDisplayPicture(picture: MsnDisplayPicture, retry: Boolean = false) {
        v2?.let {
            it.requestDisplayPicture(picture, retry)
            return
        }
        val now = System.nanoTime()
        pendingCalls.entries
            .filter { now - it.value.createdAtNanos >= REQUEST_TIMEOUT_NANOS ||
                (retry && it.value.picture.sha1d == picture.sha1d) }
            .forEach { pendingCalls.remove(it.key, it.value) }
        if (pendingCalls.values.any { it.picture.sha1d == picture.sha1d }) return
        if (pendingCalls.size >= MAX_PENDING_CALLS) return
        val sessionId = nextId()
        val callId = MsnpP2pUtil.randomGuid()
        val branch = MsnpP2pUtil.randomGuid()
        pendingCalls[sessionId] = PendingDisplayPicture(callId, picture, now)
        val content = MsnpP2pUtil.displayPictureRequestContent(sessionId, picture)
        sendMessage(
            sessionId = 0,
            flags = P2P_NO_FLAG,
            appId = 0,
            body = sipMessage(
                firstLine = "INVITE MSNMSGR:$remoteEmail MSNSLP/1.0",
                to = remoteEmail,
                from = localEmail,
                branch = branch,
                callId = callId,
                cseq = 0,
                contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                content = content,
            ),
        )
    }

    suspend fun requestVoiceClip(clip: MsnDisplayPicture) {
        require(clip.objectType == 11 && clip.size in 1..org.aralozkaya.msngr.protocol.MsnVoiceClip.MAX_WAV_BYTES)
        requestDisplayPicture(clip)
    }

    suspend fun handle(packetBytes: ByteArray) {
        if (P2pV2Packet.looksLike(packetBytes)) {
            v2?.handle(packetBytes)
            return
        }
        val now = System.nanoTime()
        incoming.entries
            .filter { now - it.value.createdAtNanos >= REQUEST_TIMEOUT_NANOS }
            .forEach { incoming.remove(it.key, it.value) }
        val packet = P2pPacket.parse(packetBytes) ?: return
        if (packet.header.flags == P2P_ACK) {
            if (fileApplicationLazy.isInitialized()) files.acknowledged(packet.header.acknowledgementId.toLong() and 0xffff_ffffL)
            return
        }
        if (packet.header.flags and 0x10 != 0) {
            if (fileApplicationLazy.isInitialized() && files.owns(packet.header.sessionId.toLong() and 0xffff_ffffL)) {
                files.receive(packet.header.sessionId.toLong() and 0xffff_ffffL,
                    packet.header.offset, packet.header.totalSize, packet.body)
                if (packet.header.offset + packet.body.size == packet.header.totalSize) sendAcknowledgement(packet.header)
            }
            return
        }
        val key = packet.header.sessionId to packet.header.id
        val message = if (packet.header.offset == 0L) {
            if (incoming.size >= MAX_INCOMING_MESSAGES) return
            val maxSize = if (packet.header.sessionId == 0) MAX_SLP_BYTES else MsnpP2pUtil.objectByteLimit(pendingCalls[packet.header.sessionId]?.picture)
            if (packet.header.totalSize !in 0..maxSize.toLong()) return
            val totalSize = packet.header.totalSize.toInt()
            IncomingP2pMessage(packet.header, ByteArray(totalSize)).also { incoming[key] = it }
        } else {
            incoming[key] ?: return
        }
        if (!message.add(packet)) {
            incoming.remove(key)
            return
        }
        if (!message.complete) return
        incoming.remove(key)
        sendAcknowledgement(message.header)
        when {
            message.header.sessionId == 0 && message.header.flags == P2P_NO_FLAG -> {
                handleSlp(message.body)
            }
            message.header.sessionId != 0 && photoApplicationLazy.isInitialized() && photos.owns(message.header.sessionId.toLong() and 0xffff_ffffL) -> photos.receive(message.body)
            message.header.flags and P2P_MSN_OBJECT_DATA != 0 -> {
                // P2P sessions can migrate to a different switchboard. A newly accepted
                // switchboard therefore may not own the original pending call. The live client
                // validates the completed bytes against the contact's current MSNObject hash.
                val pending = pendingCalls.remove(message.header.sessionId)
                if (pending?.picture?.objectType in listOf(8, 11, 15)) {
                    if (pending!!.picture.matches(message.body)) {
                        if (pending.picture.objectType == 15) photos.receivedObject(pending.picture, message.body)
                        else if (pending.picture.objectType == 8) onWink(pending.picture, message.body)
                        else onVoiceClip(pending.picture, message.body)
                        closeObjectSession(pending.callId, message.header.sessionId)
                    }
                } else if (pending == null || pending.picture.matches(message.body)) {
                    onDisplayPicture(message.body)
                }
            }
        }
    }

    private suspend fun handleSlp(bytes: ByteArray) {
        val message = SlpMessage.parse(bytes) ?: return
        if ((photoApplicationLazy.isInitialized() || message.body.field("EUF-GUID")?.trim('{', '}')
                ?.equals(MsnpPhotoSession.GUID, true) == true) && photos.handle(message)) return
        if ((fileApplicationLazy.isInitialized() || message.body.field("EUF-GUID")?.trim('{', '}')
                ?.uppercase() in setOf(org.aralozkaya.msngr.protocol.MsnFileContext.GUID, MsnpFileSession.ORIGINAL_GUID)) && files.handle(message)) return
        when {
            message.firstLine.startsWith("INVITE ", ignoreCase = true) -> handleInvite(message)
            message.firstLine.equals("MSNSLP/1.0 200 OK", ignoreCase = true) -> Unit
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
        ) return
        val sessionId = message.body.field("SessionID")?.toIntOrNull() ?: return
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

            ?: message.body.field("Context")?.let { context ->
                org.aralozkaya.msngr.protocol.MsnBase64.decode(context)?.let {
                    MsnDisplayPicture.parseObject(String(it, Charsets.UTF_8).trimEnd('\u0000'), 15)
                }
            }?.let { requested ->
                MsnpP2pUtil.matchingLocalPicture(message.body,
                    (if (photoApplicationLazy.isInitialized()) photos.localObject(requested.sha1d) else null) ?: localPhoto(requested.sha1d))
            }
        val accepted = picture != null
        val responseContent = "SessionID: $sessionId\r\n\r\n"
        sendMessage(
            sessionId = 0,
            flags = P2P_NO_FLAG,
            appId = 0,
            body = sipMessage(
                firstLine = if (accepted) "MSNSLP/1.0 200 OK" else "MSNSLP/1.0 603 Decline",
                to = remoteEmail,
                from = localEmail,
                branch = message.branch,
                callId = message.callId,
                cseq = 1,
                contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                content = responseContent,
            ),
        )
        if (!accepted) return
        sendMessage(
            sessionId = sessionId,
            flags = P2P_NO_FLAG,
            appId = 0,
            body = ByteArray(4),
        )
        sendMessage(
            sessionId = sessionId,
            flags = P2P_MSN_OBJECT_DATA,
            appId = MsnpP2pUtil.objectAppId(picture.metadata.objectType),
            body = picture.bytes,
        )
        if (picture.metadata.objectType in listOf(8, 11, 15)) closeObjectSession(message.callId, sessionId)
    }

    private suspend fun closeObjectSession(callId: String, sessionId: Int) {
        sendMessage(0, P2P_NO_FLAG, 0, sipMessage(
            "BYE MSNMSGR:$remoteEmail MSNSLP/1.0", remoteEmail, localEmail,
            MsnpP2pUtil.randomGuid(), callId, 0, "application/x-msnmsgr-sessionclosebody",
            "SessionID: $sessionId\r\n\r\n"))
    }

    private suspend fun sendFileData(session: Long, file: java.io.File, active: () -> Boolean, progress: (Long) -> Unit) {
        val size = file.length()
        val id = nextId()
        val acknowledgement = nextId()
        files.expectAcknowledgement(session, id.toLong() and 0xffff_ffffL)
        var offset = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(MAX_CHUNK_BYTES)
            while (offset < size) {
                check(active()) { "File transfer was cancelled." }
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                check(count > 0) { "The file changed during transfer." }
                val packet = P2pPacket(P2pHeader(session.toInt(), id, offset, size, count,
                    0x01000030, acknowledgement, 0, 0), buffer.copyOf(count), files.applicationId(session))
                offset += count; progress(offset)
                sendPacket(packet.encode())
            }
        }
    }

    private suspend fun sendTransportFallback(message: SlpMessage) {
        sendMessage(
            sessionId = 0,
            flags = P2P_NO_FLAG,
            appId = 0,
            body = sipMessage(
                firstLine = "MSNSLP/1.0 200 OK",
                to = remoteEmail,
                from = localEmail,
                branch = message.branch,
                callId = message.callId,
                cseq = 1,
                contentType = MsnpP2pUtil.TRANSPORT_RESPONSE_CONTENT_TYPE,
                content = MsnpP2pUtil.transportResponseContent(message.body),
            ),
        )
    }

    private suspend fun sendMessage(
        sessionId: Int,
        flags: Int,
        appId: Int,
        body: ByteArray,
    ) {
        val id = nextId()
        val acknowledgementId = nextId()
        if (body.isEmpty()) {
            sendPacket(
                P2pPacket(
                    P2pHeader(sessionId, id, 0, 0, 0, flags, acknowledgementId, 0, 0),
                    byteArrayOf(),
                    appId,
                ).encode(),
            )
            return
        }
        var offset = 0
        while (offset < body.size) {
            val length = minOf(MAX_CHUNK_BYTES, body.size - offset)
            val chunk = body.copyOfRange(offset, offset + length)
            sendPacket(
                P2pPacket(
                    header = P2pHeader(
                        sessionId = sessionId,
                        id = id,
                        offset = offset.toLong(),
                        totalSize = body.size.toLong(),
                        length = length,
                        flags = flags,
                        acknowledgementId = acknowledgementId,
                        acknowledgementSubId = 0,
                        acknowledgementSize = 0,
                    ),
                    body = chunk,
                    appId = appId,
                ).encode(),
            )
            offset += length
        }
    }

    private suspend fun sendAcknowledgement(header: P2pHeader) {
        sendPacket(
            P2pPacket(
                header = P2pHeader(
                    sessionId = header.sessionId,
                    id = nextId(),
                    offset = 0,
                    totalSize = header.totalSize,
                    length = 0,
                    flags = P2P_ACK,
                    acknowledgementId = header.id,
                    acknowledgementSubId = header.acknowledgementId,
                    acknowledgementSize = header.totalSize,
                ),
                body = byteArrayOf(),
                appId = 0,
            ).encode(),
        )
    }

    private fun nextId(): Int = ids.incrementAndGet()

    private data class PendingDisplayPicture(
        val callId: String,
        val picture: MsnDisplayPicture,
        val createdAtNanos: Long,
    )

    private companion object {
        const val P2P_NO_FLAG = 0
        const val P2P_ACK = 0x2
        const val P2P_MSN_OBJECT_DATA = 0x20
        const val MAX_CHUNK_BYTES = 1_202
        const val MAX_SLP_BYTES = 16 * 1024
        const val MAX_PICTURE_BYTES = org.aralozkaya.msngr.protocol.DisplayPictureLimits.MAX_BYTES
        const val MAX_INCOMING_MESSAGES = 8
        const val MAX_PENDING_CALLS = 8
        const val REQUEST_TIMEOUT_NANOS = 30_000_000_000L
    }
}

private data class P2pHeader(
    val sessionId: Int,
    val id: Int,
    val offset: Long,
    val totalSize: Long,
    val length: Int,
    val flags: Int,
    val acknowledgementId: Int,
    val acknowledgementSubId: Int,
    val acknowledgementSize: Long,
)

private data class P2pPacket(
    val header: P2pHeader,
    val body: ByteArray,
    val appId: Int,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES + body.size + FOOTER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(header.sessionId)
        .putInt(header.id)
        .putLong(header.offset)
        .putLong(header.totalSize)
        .putInt(header.length)
        .putInt(header.flags)
        .putInt(header.acknowledgementId)
        .putInt(header.acknowledgementSubId)
        .putLong(header.acknowledgementSize)
        .put(body)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(appId)
        .array()

    companion object {
        private const val HEADER_BYTES = 48
        private const val FOOTER_BYTES = 4

        fun parse(bytes: ByteArray): P2pPacket? = runCatching {
            if (bytes.size < HEADER_BYTES + FOOTER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val header = P2pHeader(
                sessionId = buffer.int,
                id = buffer.int,
                offset = buffer.long,
                totalSize = buffer.long,
                length = buffer.int,
                flags = buffer.int,
                acknowledgementId = buffer.int,
                acknowledgementSubId = buffer.int,
                acknowledgementSize = buffer.long,
            )
            if (header.length < 0 || HEADER_BYTES + header.length + FOOTER_BYTES != bytes.size) {
                return null
            }
            val body = ByteArray(header.length).also(buffer::get)
            buffer.order(ByteOrder.BIG_ENDIAN)
            P2pPacket(header, body, buffer.int)
        }.getOrNull()
    }
}

private class IncomingP2pMessage(
    val header: P2pHeader,
    val body: ByteArray,
) {
    val createdAtNanos: Long = System.nanoTime()
    private var received = 0
    val complete: Boolean get() = received == body.size

    fun add(packet: P2pPacket): Boolean {
        if (packet.header.totalSize != body.size.toLong() ||
            packet.header.flags != header.flags
        ) {
            return false
        }
        if (packet.header.offset != received.toLong()) return false
        if (received + packet.body.size > body.size) return false
        packet.body.copyInto(body, received)
        received += packet.body.size
        return true
    }
}
