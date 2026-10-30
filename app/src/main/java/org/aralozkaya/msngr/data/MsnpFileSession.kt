package org.aralozkaya.msngr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnFileContext
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** File application shared by both P2P framings. Data is streamed only after acceptance. */
internal class MsnpFileSession(
    private val source: String,
    private val destination: String,
    private val directory: (() -> File)?,
    private val publish: (FileTransfer) -> Unit,
    private val sendControl: suspend (ByteArray) -> Unit,
    private val sendFile: suspend (Long, File, () -> Boolean, (Long) -> Unit) -> Unit,
    private val originalPhoto: (String) -> OriginalPhoto? = { null },
    private val offerTimeoutMillis: Long = 600_000,
    private val idleTimeoutMillis: Long = 120_000,
    private val expiryCheckMillis: Long = 10_000,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicInteger(100_000)
    private val calls = ConcurrentHashMap<Long, Call>()
    private class Call(val session: Long, val invite: SlpMessage, @Volatile var transfer: FileTransfer,
        val file: File? = null, var unknownSize: Boolean = false, val expectedHash: String? = null) {
        var output: OutputStream? = null
        var partial: File? = null
        var job: Job? = null
        @Volatile var lastActivity = System.nanoTime()
        @Volatile var completionAck: Long? = null
        var lastPublished = 0L
        val digest = if (expectedHash != null) java.security.MessageDigest.getInstance("SHA-1") else null
    }

    init {
        scope.launch {
            while (true) {
                delay(expiryCheckMillis)
                calls.values.filter { System.nanoTime() - it.lastActivity >
                    (if (it.transfer.status == FileTransferStatus.Offered) offerTimeoutMillis else idleTimeoutMillis) * 1_000_000L }
                    .forEach {
                        finish(it, FileTransferStatus.Failed, "File transfer timed out.")
                        runCatching { bye(it) }
                    }
            }
        }
    }

    suspend fun offer(file: File, name: String): String {
        require(file.isFile) { "The selected file is unavailable." }
        require(!MsnFileContext.isExecutable(name)) { "Executable files cannot be shared." }
        val context = MsnFileContext(name, file.length())
        val encoded = context.encode()
        check(calls.size < 8) { "Too many active file transfers." }
        val session = ids.incrementAndGet().toLong()
        val id = MsnpP2pUtil.randomGuid()
        val content = "EUF-GUID: {${MsnFileContext.GUID}}\r\nSessionID: $session\r\nAppID: 2\r\nRequestFlags: 16\r\nContext: ${MsnBase64.encode(encoded)}\r\n\r\n"
        val bytes = sipMessage("INVITE MSNMSGR:$destination MSNSLP/1.0", destination, source,
            MsnpP2pUtil.randomGuid(), id, 0, MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, content)
        val call = Call(session, requireNotNull(SlpMessage.parse(bytes)), FileTransfer(id, name,
            file.length(), true, localPath = file.absolutePath), file)
        calls[session] = call
        publish(call.transfer)
        try { sendControl(bytes) } catch (error: Exception) {
            finish(call, FileTransferStatus.Failed, "File invitation could not be sent.")
            throw error
        }
        return id
    }

    suspend fun handle(message: SlpMessage): Boolean {
        if (message.firstLine.startsWith("INVITE ", true) && message.body.field("EUF-GUID")?.trim('{', '}')
                ?.equals(ORIGINAL_GUID, true) == true) {
            val session = message.body.field("SessionID")?.toLongOrNull()?.takeIf { it in 1..0xffff_ffffL } ?: return true
            val id = originalId(message.body.field("Context"))
            val original = id?.let(originalPhoto)
            val file = original?.let { File(it.path) }
            if (message.body.field("AppID") != "36" || id == null || original == null || file?.isFile != true ||
                file.length() !in 1..MsnFileContext.MAX_FILE_BYTES || calls.size >= 8 || calls.containsKey(session)) {
                respond(message, session, "603 Decline"); return true
            }
            val call = Call(session, message, FileTransfer(message.callId, original.name, file.length(), true,
                FileTransferStatus.Transferring, localPath = file.absolutePath, photoId = id), file)
            calls[session] = call; publish(call.transfer)
            respond(message, session, "200 OK")
            startSending(call)
            return true
        }
        val isOffer = message.firstLine.startsWith("INVITE ", true) &&
            message.contentType.equals(MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, true) &&
            message.body.field("EUF-GUID")?.trim('{', '}')?.equals(MsnFileContext.GUID, true) == true
        if (isOffer) {
            val session = message.body.field("SessionID")?.toLongOrNull()?.takeIf { it in 1..0xffff_ffffL }
                ?: return true
            if (calls.values.any { it.invite.callId.equals(message.callId, true) }) return true
            val context = message.body.field("Context")?.let(MsnBase64::decode)?.let(MsnFileContext::parse)
            if (context == null || message.body.field("AppID") != "2" || directory == null ||
                MsnFileContext.isExecutable(context.name) || calls.size >= 8 || calls.containsKey(session)) {
                respond(message, session, "603 Decline")
                return true
            }
            val call = Call(session, message, FileTransfer(message.callId, context.name, context.size, false))
            calls[session] = call
            publish(call.transfer)
            return true
        }
        val call = calls.values.firstOrNull { it.invite.callId.equals(message.callId, true) } ?: return false
        call.lastActivity = System.nanoTime()
        when {
            message.firstLine.startsWith("BYE ", true) -> {
                val complete = call.transfer.outgoing && call.transfer.transferred == call.transfer.size &&
                    call.transfer.status == FileTransferStatus.Transferring
                finish(call, if (complete) FileTransferStatus.Complete else FileTransferStatus.Cancelled)
            }
            message.firstLine.startsWith("MSNSLP/1.0 200", true) &&
                message.contentType.equals(MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, true) -> {
                if (call.transfer.outgoing && call.transfer.status == FileTransferStatus.Offered) {
                    update(call, call.transfer.copy(status = FileTransferStatus.Transferring))
                    startSending(call)
                }
            }
            message.firstLine.startsWith("MSNSLP/1.0 603", true) -> finish(call, FileTransferStatus.Declined)
            message.firstLine.startsWith("MSNSLP/1.0 ", true) &&
                !message.firstLine.startsWith("MSNSLP/1.0 200", true) ->
                finish(call, FileTransferStatus.Failed, "The other client could not receive this file.")
            else -> return false // Let the framing layer answer transport negotiation.
        }
        return true
    }

    fun owns(id: String): Boolean = calls.values.any { it.transfer.id == id }
    fun owns(session: Long): Boolean = calls.containsKey(session)
    fun applicationId(session: Long): Int = if (calls[session]?.transfer?.photoId != null) 36 else 2

    suspend fun requestOriginal(photo: SharedPhoto): String {
        check(calls.size < 8)
        require(MsnFileContext.validName(photo.name) && !MsnFileContext.isExecutable(photo.name)) { "This original photo cannot be saved." }
        require(MsnBase64.decode(photo.hash)?.size == 20) { "Invalid original photo hash." }
        val session = ids.incrementAndGet().toLong()
        val id = MsnpP2pUtil.randomGuid()
        val context = "<map><h aid=\"PS\" op=\"REQUESTORIGINAL\" ver=\"1\"></h><m guid=\"{${java.util.UUID.fromString(photo.id)}}\"/></map>\r\n\u0000"
        val body = "EUF-GUID: {$ORIGINAL_GUID}\r\nSessionID: $session\r\nAppID: 36\r\nRequestFlags: 16\r\nContext: ${MsnBase64.encode(context.toByteArray(Charsets.UTF_16LE))}\r\n\r\n"
        val bytes = sipMessage("INVITE MSNMSGR:$destination MSNSLP/1.0", destination, source,
            MsnpP2pUtil.randomGuid(), id, 0, MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, body)
        val call = Call(session, requireNotNull(SlpMessage.parse(bytes)), FileTransfer(id, photo.name, 0, false,
            FileTransferStatus.Transferring, photoId = photo.id), unknownSize = true, expectedHash = photo.hash)
        prepareOutput(call)
        calls[session] = call; publish(call.transfer)
        try { sendControl(bytes) } catch (error: Exception) { finish(call, FileTransferStatus.Failed, "The original photo could not be requested."); throw error }
        return id
    }

    private fun startSending(call: Call) {
        call.job = scope.launch {
            try {
                sendFile(call.session, requireNotNull(call.file), { calls[call.session] === call }) {
                    call.lastActivity = System.nanoTime(); update(call, call.transfer.copy(transferred = it))
                }
            } catch (_: Exception) { finish(call, FileTransferStatus.Failed, "File could not be sent.") }
        }
    }
    private fun prepareOutput(call: Call) {
        val area = requireNotNull(directory).invoke().apply { check(mkdirs() || isDirectory) }
        check(area.usableSpace > call.transfer.size + 1024 * 1024) { "Not enough storage space." }
        call.partial = File.createTempFile("transfer-", ".part", area)
        call.output = requireNotNull(call.partial).outputStream().buffered()
    }

    fun expectAcknowledgement(session: Long, identifier: Long) { calls[session]?.completionAck = identifier }
    suspend fun acknowledged(identifier: Long) {
        val call = calls.values.firstOrNull { it.completionAck == identifier && it.transfer.outgoing &&
            it.transfer.status == FileTransferStatus.Transferring && it.transfer.transferred == it.transfer.size } ?: return
        finish(call, FileTransferStatus.Complete)
        bye(call)
    }

    suspend fun accept(id: String) {
        val call = calls.values.firstOrNull { it.transfer.id == id } ?: error("This file offer has ended.")
        synchronized(call) {
            check(!call.transfer.outgoing && call.transfer.status == FileTransferStatus.Offered)
            prepareOutput(call)
            call.lastActivity = System.nanoTime()
            update(call, call.transfer.copy(status = FileTransferStatus.Transferring))
        }
        try {
            respond(call.invite, call.session, "200 OK")
            if (call.transfer.size == 0L) receive(call.session, 0, 0, byteArrayOf())
        } catch (error: Exception) {
            finish(call, FileTransferStatus.Failed, "File could not be received.")
            throw error
        }
    }

    suspend fun cancel(id: String) {
        val call = calls.values.firstOrNull { it.transfer.id == id } ?: return
        val declined = !call.transfer.outgoing && call.transfer.status == FileTransferStatus.Offered
        finish(call, if (declined) FileTransferStatus.Declined else FileTransferStatus.Cancelled)
        if (declined) respond(call.invite, call.session, "603 Decline") else bye(call)
    }

    /** Offset and total are validated before any bytes reach app-private storage. */
    suspend fun receive(session: Long, offset: Long, total: Long, bytes: ByteArray) {
        val call = calls[session] ?: return
        var completed = false
        try {
            synchronized(call) {
                if (call.transfer.outgoing || call.transfer.status != FileTransferStatus.Transferring) return
                if (call.unknownSize) {
                    check(offset == 0L && total in 1..MsnFileContext.MAX_FILE_BYTES)
                    call.unknownSize = false; update(call, call.transfer.copy(size = total))
                }
                check(total == call.transfer.size && offset == call.transfer.transferred &&
                    bytes.size.toLong() <= total - offset) { "Invalid file data." }
                call.output!!.write(bytes)
                call.digest?.update(bytes)
                call.lastActivity = System.nanoTime()
                val received = offset + bytes.size
                update(call, call.transfer.copy(transferred = received))
                if (received == total) {
                    call.output!!.close(); call.output = null
                    check(call.digest == null || MsnBase64.encode(call.digest.digest()) == call.expectedHash) { "Original photo hash mismatch." }
                    val partial = requireNotNull(call.partial)
                    val saved = File(partial.parentFile, partial.name.removeSuffix(".part") + ".bin")
                    check(partial.renameTo(saved))
                    call.partial = null
                    update(call, call.transfer.copy(localPath = saved.absolutePath))
                    completed = true
                }
            }
            if (completed) { finish(call, FileTransferStatus.Complete); bye(call) }
        } catch (_: Exception) {
            finish(call, FileTransferStatus.Failed, "The received file was incomplete or invalid.")
            runCatching { bye(call) }
        }
    }

    fun received(session: Long): Long = calls[session]?.transfer?.transferred ?: 0
    fun expected(session: Long): Long = calls[session]?.transfer?.size ?: -1
    private fun update(call: Call, transfer: FileTransfer) {
        val previous = call.transfer
        call.transfer = transfer
        val now = System.nanoTime()
        if (previous.status != transfer.status || previous.localPath != transfer.localPath ||
            previous.error != transfer.error || transfer.transferred == transfer.size || now - call.lastPublished >= 250_000_000L) {
            call.lastPublished = now
            publish(transfer)
        }
    }
    private fun finish(call: Call, status: FileTransferStatus, error: String? = null) {
        if (!calls.remove(call.session, call)) return
        synchronized(call) {
            if (status != FileTransferStatus.Complete) call.job?.cancel()
            runCatching { call.output?.close() }; call.output = null
            call.partial?.delete(); call.partial = null
            update(call, call.transfer.copy(status = status, error = error))
        }
    }
    private suspend fun respond(invite: SlpMessage, session: Long, status: String) = sendControl(sipMessage(
        "MSNSLP/1.0 $status", destination, source, invite.branch, invite.callId, 1,
        MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, "SessionID: $session\r\n\r\n"))
    private suspend fun bye(call: Call) = sendControl(sipMessage("BYE MSNMSGR:$destination MSNSLP/1.0",
        destination, source, MsnpP2pUtil.randomGuid(), call.invite.callId, 0,
        "application/x-msnmsgr-sessionclosebody", "SessionID: ${call.session}\r\n\r\n"))

    override fun close() {
        calls.values.toList().forEach { finish(it, FileTransferStatus.Failed, "The conversation disconnected.") }
        scope.cancel()
    }
    companion object {
        const val ORIGINAL_GUID = "758E0488-2F05-411D-8849-CA06BBBAEB5F"
        private fun originalId(context: String?): String? = runCatching {
            val bytes = context?.let(MsnBase64::decode) ?: return null
            require(bytes.size <= 4096 && bytes.size % 2 == 0)
            val xml = bytes.toString(Charsets.UTF_16LE).trimEnd('\u0000', '\r', '\n')
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            val h = doc.getElementsByTagName("h").item(0) as org.w3c.dom.Element
            require(h.getAttribute("aid") == "PS" && h.getAttribute("op") == "REQUESTORIGINAL")
            java.util.UUID.fromString((doc.getElementsByTagName("m").item(0) as org.w3c.dom.Element).getAttribute("guid").trim('{', '}')).toString().uppercase()
        }.getOrNull()
    }
}
