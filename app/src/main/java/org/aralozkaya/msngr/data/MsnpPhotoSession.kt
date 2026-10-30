package org.aralozkaya.msngr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

/** WLM 2009 Photos: MSNSLP AppID 35 plus UTF-16LE MAP commands and type-15 MSNObjects. */
internal class MsnpPhotoSession(
    private val source: String,
    private val destination: String,
    private val directory: (() -> File)?,
    private val publish: (PhotoShare) -> Unit,
    private val sendControl: suspend (ByteArray) -> Unit,
    private val sendData: suspend (Long, ByteArray) -> Unit,
    private val requestObject: suspend (MsnDisplayPicture) -> Unit,
    private val publishObject: (LocalDisplayPicture) -> Unit = {},
    private val publishOriginal: (String, OriginalPhoto) -> Unit = { _, _ -> },
) {
    private val ids = AtomicInteger(500_000)
    private var session = 0L
    private var invite: SlpMessage? = null
    @Volatile private var share: PhotoShare? = null
    private val localObjects = ConcurrentHashMap<String, LocalDisplayPicture>()
    private val originals = ConcurrentHashMap<String, OriginalPhoto>()
    private var prepared = emptyList<PhotoToShare>()
    private var sequence = 0
    @Volatile private var remoteSequence = 0
    private var nextPhotoIndex = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewTimeout: Job? = null
    private var invitationTimeout: Job? = null
    @Volatile private var pendingPreview: String? = null
    fun owns(id: String): Boolean = share?.id == id
    fun owns(sessionId: Long): Boolean = session != 0L && sessionId == session
    fun localObject(hash: String): LocalDisplayPicture? = localObjects[hash]
    fun localOriginal(id: String): OriginalPhoto? = originals[id.uppercase()]

    suspend fun start(photos: List<PhotoToShare>): String {
        check(share?.status != PhotoShareStatus.Invited) { "Wait for the photo invitation to be accepted." }
        require(directory != null) { "Photo sharing is unavailable." }
        require(photos.size in 1..32)
        require(photos.sumOf { it.thumbnail.size.toLong() + it.preview.size } <= 32L * 1024 * 1024)
        require(photos.all { it.name.length in 1..260 && it.thumbnail.size in 1..MAX_OBJECT_BYTES && it.preview.size in 1..MAX_OBJECT_BYTES })
        val active = share?.takeIf { it.status == PhotoShareStatus.Active }
        if (active != null) {
            require(active.photos.size + photos.size <= 32) { "A photo share can contain up to 32 photos." }
            require(localObjects.values.sumOf { it.bytes.size.toLong() } + photos.sumOf { it.thumbnail.size.toLong() + it.preview.size } <= 32L * 1024 * 1024) {
                "These photos exceed the sharing session's preview limit."
            }
            for (photo in photos) add(photo)
            share?.photos?.lastOrNull()?.let { select(it.id) }
            return active.id
        }
        previewTimeout?.cancel(); pendingPreview = null
        originals.clear()
        localObjects.clear(); prepared = photos; sequence = 0; remoteSequence = 0; nextPhotoIndex = 0
        session = ids.incrementAndGet().toLong()
        val id = MsnpP2pUtil.randomGuid()
        val bytes = sipMessage("INVITE MSNMSGR:$destination MSNSLP/1.0", destination, source,
            MsnpP2pUtil.randomGuid(), id, 0, MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
            "EUF-GUID: {$GUID}\r\nSessionID: $session\r\nAppID: 35\r\nRequestFlags: 16\r\n\r\n")
        invite = requireNotNull(SlpMessage.parse(bytes))
        update(PhotoShare(id, true))
        startInvitationTimeout(id)
        try { sendControl(bytes) } catch (error: Exception) {
            update(requireNotNull(share).copy(status = PhotoShareStatus.Failed, error = "Photo invitation could not be sent."))
            throw error
        }
        return id
    }

    suspend fun handle(message: SlpMessage): Boolean {
        val incoming = message.firstLine.startsWith("INVITE ", true) &&
            message.contentType.equals(MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, true) &&
            message.body.field("EUF-GUID")?.trim('{', '}')?.equals(GUID, true) == true
        if (incoming) {
            if (message.callId.equals(invite?.callId, true)) return true
            val id = message.body.field("SessionID")?.toLongOrNull()?.takeIf { it in 1..0xffff_ffffL } ?: return true
            if (directory == null || message.body.field("AppID") != "35" ||
                share?.status in listOf(PhotoShareStatus.Invited, PhotoShareStatus.Active)) {
                respond(message, id, false); return true
            }
            previewTimeout?.cancel(); pendingPreview = null
            invite = message; session = id; sequence = 0; remoteSequence = 0; nextPhotoIndex = 0; localObjects.clear(); originals.clear(); prepared = emptyList()
            update(PhotoShare(message.callId, false))
            startInvitationTimeout(message.callId)
            return true
        }
        val current = share ?: return false
        if (!current.id.equals(message.callId, true)) return false
        when {
            message.firstLine.startsWith("BYE ", true) -> {
                invitationTimeout?.cancel()
                previewTimeout?.cancel(); pendingPreview = null
                change(current.id) { it.copy(status = PhotoShareStatus.Stopped, remoteStopped = true) }
            }
            message.firstLine.startsWith("MSNSLP/1.0 603", true) -> {
                invitationTimeout?.cancel()
                change(current.id) { it.copy(status = PhotoShareStatus.Declined) }
            }
            message.firstLine.startsWith("MSNSLP/1.0 200", true) &&
                message.contentType.equals(MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, true) -> {
                if (current.outgoing && current.status == PhotoShareStatus.Invited) {
                    invitationTimeout?.cancel()
                    change(current.id) { it.copy(status = PhotoShareStatus.Active) }
                    for (photo in prepared) add(photo)
                    share?.photos?.firstOrNull()?.let { select(it.id) }
                    prepared = emptyList()
                }
            }
            else -> return false
        }
        return true
    }

    suspend fun accept() {
        val current = requireNotNull(share)
        check(!current.outgoing && current.status == PhotoShareStatus.Invited)
        invitationTimeout?.cancel()
        change(current.id) { it.copy(status = PhotoShareStatus.Active) }
        respond(requireNotNull(invite), session, true)
    }

    suspend fun stop() {
        val current = share ?: return
        if (current.status !in listOf(PhotoShareStatus.Invited, PhotoShareStatus.Active)) return
        val declined = !current.outgoing && current.status == PhotoShareStatus.Invited
        invitationTimeout?.cancel()
        previewTimeout?.cancel(); pendingPreview = null
        change(current.id) { it.copy(status = if (declined) PhotoShareStatus.Declined else PhotoShareStatus.Stopped) }
        if (declined) respond(requireNotNull(invite), session, false)
        else sendControl(sipMessage("BYE MSNMSGR:$destination MSNSLP/1.0", destination, source,
            MsnpP2pUtil.randomGuid(), current.id, 0, "application/x-msnmsgr-sessionclosebody",
            "SessionID: $session\r\n\r\n"))
    }

    private suspend fun add(photo: PhotoToShare) {
        val current = requireNotNull(share)
        val creator = source.substringBefore(';')
        val friendly = MsnBase64.encode((photo.name.substringBeforeLast('.') + '\u0000').toByteArray(Charsets.UTF_16LE))
        val thumbnailBytes = scopedPreview(photo.thumbnail, current.id)
        val previewBytes = scopedPreview(photo.preview, current.id)
        val thumb = MsnDisplayPicture.create(creator, thumbnailBytes, 15, "0", friendly)
        val preview = MsnDisplayPicture.create(creator, previewBytes, 15, "0", friendly)
        localObjects[thumb.sha1d] = LocalDisplayPicture(thumb, thumbnailBytes)
        localObjects[preview.sha1d] = LocalDisplayPicture(preview, previewBytes)
        publishObject(requireNotNull(localObjects[thumb.sha1d])); publishObject(requireNotNull(localObjects[preview.sha1d]))
        val item = SharedPhoto(MsnpP2pUtil.randomGuid(), photo.name, photo.hash, thumb, preview,
            saveObject(preview, previewBytes), originalPath = photo.originalPath,
            sortIndex = if (current.outgoing) ++nextPhotoIndex else null)
        photo.originalPath?.let { path ->
            val original = OriginalPhoto(photo.name, path, photo.hash)
            originals[item.id.uppercase()] = original; publishOriginal(item.id, original)
        }
        change(current.id) {
            check(it.photos.size < 32) { "A photo share can contain up to 32 photos." }
            it.copy(photos = it.photos + item)
        }
        advertise(if (current.outgoing) "ADDH" else "ADDC", item)
    }

    private suspend fun advertise(op: String, item: SharedPhoto) = map(op,
        "guid=\"{${item.id}}\" hash=\"${escape(item.hash)}\" si=\"${item.sortIndex ?: -1}\" tospath=\"${escape(item.thumbnail.toXml())}\" mospath=\"${escape(item.preview.toXml())}\" dispn=\"${escape(item.name)}\"")

    suspend fun select(id: String) {
        val current = share ?: return
        if (current.photos.none { it.id.equals(id, true) }) return
        change(current.id) { it.copy(selectedId = id) }
        if (current.status == PhotoShareStatus.Active) {
            map("CHG", "guid=\"{${escape(id)}}\" seq=\"${++sequence}\" ack=\"$remoteSequence\"")
            if (pendingPreview == null) {
                change(current.id) { latest -> latest.copy(photos = latest.photos.map {
                    if (it.id.equals(id, true)) it.copy(previewError = null) else it
                }) }
                requestNextPreview()
            }
        }
    }

    suspend fun receive(bytes: ByteArray) {
        val current = share ?: return
        if (current.status != PhotoShareStatus.Active || bytes.size !in 2..MAX_MAP_BYTES || bytes.size % 2 != 0) return
        val text = bytes.toString(Charsets.UTF_16LE).trimEnd('\u0000', '\r', '\n')
        if (!text.startsWith("<map>") || text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) return
        val document = runCatching { DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newDocumentBuilder().parse(text.byteInputStream()) }.getOrNull() ?: return
        val header = document.getElementsByTagName("h").item(0) as? org.w3c.dom.Element ?: return
        if (header.getAttribute("aid") != "PS") return
        val item = document.getElementsByTagName("m").item(0) as? org.w3c.dom.Element ?: return
        val id = item.getAttribute("guid").trim('{', '}')
        if (runCatching { java.util.UUID.fromString(id) }.isFailure) return
        when (header.getAttribute("op")) {
            "ADDH", "ADDC" -> {
                val hostAnnouncement = header.getAttribute("op") == "ADDH"
                if (hostAnnouncement == current.outgoing) return
                val existing = current.photos.firstOrNull { it.id.equals(id, true) }
                if (existing != null) return
                val advertisedIndex = if (hostAnnouncement) item.getAttribute("si").toIntOrNull()?.takeIf { it > 0 } ?: return else null
                if (current.photos.size >= 32) return
                val thumb = MsnDisplayPicture.parseObject(item.getAttribute("tospath"), 15) ?: return
                val preview = MsnDisplayPicture.parseObject(item.getAttribute("mospath"), 15) ?: return
                if (listOf(thumb, preview).any { it.size !in 1..MAX_OBJECT_BYTES || !it.creator.equals(destination.substringBefore(';'), true) }) return
                val name = item.getAttribute("dispn").takeIf { it.length in 1..260 } ?: return
                if (!org.aralozkaya.msngr.protocol.MsnFileContext.validName(name) ||
                    org.aralozkaya.msngr.protocol.MsnBase64.decode(item.getAttribute("hash"))?.size != 20) return
                val index = advertisedIndex ?: ++nextPhotoIndex
                val received = SharedPhoto(id, name, item.getAttribute("hash"), thumb, preview, sortIndex = index)
                change(current.id) {
                    if (it.photos.size >= 32 || it.photos.any { photo -> photo.id.equals(id, true) }) it
                    else it.copy(photos = (it.photos + received).sortedBy { photo -> photo.sortIndex ?: Int.MAX_VALUE })
                }
                if (!hostAnnouncement) map("SINDEX", "guid=\"{$id}\" si=\"$index\"")
                requestNextPreview()
            }
            "SINDEX" -> {
                if (current.outgoing) return
                val index = item.getAttribute("si").toIntOrNull()?.takeIf { it > 0 } ?: return
                change(current.id) { latest -> latest.copy(photos = latest.photos.map {
                    if (it.id.equals(id, true)) it.copy(sortIndex = index) else it
                }.sortedBy { it.sortIndex ?: Int.MAX_VALUE }) }
            }
            "CHG" -> {
                if (current.photos.none { it.id.equals(id, true) }) return
                val receivedSequence = item.getAttribute("seq").toIntOrNull()?.takeIf { it >= 0 } ?: return
                if (receivedSequence < remoteSequence) return
                remoteSequence = receivedSequence
                change(current.id) { it.copy(selectedId = id) }
            }
            "DEL" -> change(current.id) { latest -> latest.copy(photos = latest.photos.filterNot { it.id.equals(id, true) }) }
            "PROGRESS" -> if (item.getAttribute("dp") == "1") change(current.id) { latest -> latest.copy(photos = latest.photos.map {
                if (it.id.equals(id, true)) it.copy(downloadedByPeer = true) else it
            }) }
        }
    }

    suspend fun receivedObject(metadata: MsnDisplayPicture, bytes: ByteArray) {
        if (!metadata.matches(bytes)) return
        val current = share ?: return
        val items = current.photos.filter { it.preview.sha1d == metadata.sha1d }
        if (items.isEmpty()) return
        val path = runCatching { saveObject(metadata, bytes) }.getOrNull() ?: return
        if (pendingPreview == metadata.sha1d) { previewTimeout?.cancel(); pendingPreview = null }
        change(current.id) { latest -> latest.copy(photos = latest.photos.map { if (it.preview.sha1d == metadata.sha1d) it.copy(localPath = path, previewError = null) else it }) }
        if (current.status == PhotoShareStatus.Active) {
            for (item in items) map("PROGRESS", "guid=\"{${item.id}}\" dp=\"1\"")
            requestNextPreview()
        }
    }

    /** Keep room for other MSNObjects instead of dropping photo 9 at the P2P call limit. */
    private suspend fun requestNextPreview() {
        val current = share?.takeIf { it.status == PhotoShareStatus.Active } ?: return
        if (pendingPreview != null) return
        val photo = current.photos.firstOrNull { it.localPath == null && it.previewError == null } ?: return
        val hash = photo.preview.sha1d
        pendingPreview = hash
        previewTimeout = scope.launch {
            delay(60_000)
            if (pendingPreview == hash) {
                pendingPreview = null
                change(current.id) { latest -> latest.copy(photos = latest.photos.map {
                    if (it.preview.sha1d == hash && it.localPath == null) it.copy(previewError = "The photo could not be received. Select it to retry.") else it
                }) }
                requestNextPreview()
            }
        }
        try { requestObject(photo.preview) } catch (_: Exception) {
            previewTimeout?.cancel(); pendingPreview = null
            change(current.id) { latest -> latest.copy(photos = latest.photos.map {
                if (it.id == photo.id) it.copy(previewError = "The photo could not be received. Select it to retry.") else it
            }) }
            requestNextPreview()
        }
    }

    private fun saveObject(metadata: MsnDisplayPicture, bytes: ByteArray): String {
        val area = requireNotNull(directory).invoke().apply { check(mkdirs() || isDirectory) }
        val file = File.createTempFile("photo-", ".image", area)
        try { file.outputStream().use { it.write(bytes) } } catch (error: Exception) { file.delete(); throw error }
        check(metadata.matches(bytes))
        return file.absolutePath
    }
    private suspend fun map(op: String, attributes: String) = sendData(session,
        ("<map><h aid=\"PS\" op=\"$op\" ver=\"1\"></h><m $attributes/></map>\r\n").toByteArray(Charsets.UTF_16LE))
    private suspend fun respond(invite: SlpMessage, id: Long, accepted: Boolean) = sendControl(sipMessage(
        if (accepted) "MSNSLP/1.0 200 OK" else "MSNSLP/1.0 603 Decline", destination, source,
        invite.branch, invite.callId, 1, MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, "SessionID: $id\r\n\r\n"))
    @Synchronized private fun update(value: PhotoShare) { share = value; publish(value) }
    @Synchronized private fun change(id: String, transform: (PhotoShare) -> PhotoShare) {
        share?.takeIf { it.id == id }?.let { update(transform(it)) }
    }
    private fun startInvitationTimeout(id: String) {
        invitationTimeout?.cancel()
        invitationTimeout = scope.launch {
            delay(600_000)
            val current = share?.takeIf { it.id == id && it.status == PhotoShareStatus.Invited } ?: return@launch
            change(current.id) { it.copy(status = PhotoShareStatus.Failed, error = "Photo sharing invitation timed out.") }
            runCatching { sendControl(sipMessage("BYE MSNMSGR:$destination MSNSLP/1.0", destination, source,
                MsnpP2pUtil.randomGuid(), id, 0, "application/x-msnmsgr-sessionclosebody", "SessionID: $session\r\n\r\n")) }
        }
    }
    fun disconnect() {
        previewTimeout?.cancel(); pendingPreview = null; scope.cancel()
        share?.takeIf { it.status in listOf(PhotoShareStatus.Active, PhotoShareStatus.Invited) }
            ?.let { update(it.copy(status = PhotoShareStatus.Stopped, remoteStopped = true)) }
        localObjects.clear(); originals.clear(); prepared = emptyList()
    }
    companion object {
        const val GUID = "41D3E74E-04A2-4B37-96F8-08ACDB610874"
        const val MAX_OBJECT_BYTES = 4 * 1024 * 1024
        const val MAX_MAP_BYTES = 64 * 1024
        /** Native WLM caches an object's serving endpoint by hash. A new sharing session
         * must not reuse a preview owned by an endpoint from a previous sign-in. JPEG COM
         * scopes that identity without changing any decoded pixels or the original file. */
        internal fun scopedPreview(bytes: ByteArray, sessionId: String): ByteArray {
            if (bytes.size < 2 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return bytes
            val comment = ("Msngr photo " + sessionId).toByteArray(Charsets.US_ASCII)
            val length = comment.size + 2
            val result = bytes.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), (length ushr 8).toByte(), length.toByte()) + comment + bytes.copyOfRange(2, bytes.size)
            require(result.size <= MAX_OBJECT_BYTES)
            return result
        }
        private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
}
