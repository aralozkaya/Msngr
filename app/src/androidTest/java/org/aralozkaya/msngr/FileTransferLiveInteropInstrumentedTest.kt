package org.aralozkaya.msngr

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.data.field
import org.aralozkaya.msngr.enums.Presence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt in with private stdin-provisioned credentials and the user-supplied test image. */
class FileTransferLiveInteropInstrumentedTest {
    @Test fun exchangesFilesAndPhotoSessionsWithNativeMessenger() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fileInterop") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "file-interop.credentials")
        val config = try { JSONObject(input.readText()) } finally { input.delete() }
        val peer = config.getString("peer").lowercase()
        val fixture = File(context.filesDir, "file-interop.png")
        assertTrue("The complete attached PNG must be provisioned", fixture.isFile && fixture.length() > 8)
        val phase = File(context.getExternalFilesDir(null), "file-interop-phase.txt")
        val trace = File(context.getExternalFilesDir(null), "file-interop-wire.txt").apply { writeText("") }
        fun record(value: String) = synchronized(trace) { trace.appendText(value + "\n") }
        val client = LiveMsnp18Client(fileTransferDirectory = File(context.filesDir, "file-interop-transfers"),
            transport = SharingTraceTransport(::record, "NS"),
            endpointId = { deviceEndpointId(context) },
            // This opt-in transport probe subscribes only the known reciprocal test peer.
            // Authentication, presence, switchboards and every transfer use the real service.
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(Contact(peer, "Native transfer peer", peer, Presence.Offline))) },
            switchboardTransportFactory = { SharingTraceTransport(::record) })
        val acceptedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val collector = launch(Dispatchers.IO) {
            client.messages.collect { all -> all[peer].orEmpty().mapNotNull { it.fileTransfer }.forEach {
                record("STATE id=${it.id} status=${it.status} transferred=${it.transferred} size=${it.size}")
                if (!it.outgoing && it.status == FileTransferStatus.Offered && acceptedFiles.add(it.id))
                    client.acceptFile(peer, it.id)
            } }
        }
        val photoCollector = launch(Dispatchers.IO) {
            client.photoShares.collect { all -> all[peer]?.takeIf { !it.outgoing && it.status == PhotoShareStatus.Invited }
                ?.let { client.acceptPhotos(peer, it.id) } }
        }
        try {
            val result = client.signIn(SignInRequest(config.getString("email"), config.getString("password"),
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()), Presence.Busy))
            result.exceptionOrNull()?.let { failure ->
                val message = failure.message.orEmpty().replace(config.getString("email"), "[account]", true)
                    .replace(config.getString("password"), "[secret]")
                    .replace(Regex("[A-Za-z0-9_.+%-]+@[A-Za-z0-9_.-]+"), "[account]")
                record("SIGNIN-ERROR ${failure.javaClass.simpleName}: $message")
            }
            assertTrue("Real-service sign-in must succeed", result.isSuccess)
            withTimeout(60_000) { client.contacts.first { list -> list.any { it.email.equals(peer, true) && it.presence.isOnline } } }
            if (InstrumentationRegistry.getArguments().getString("photosOnly") != "true") {
                phase.writeText("offering-file")
                assertTrue("Native file invitation must send", client.sendFile(peer, fixture, "msngr-transfer-check.png").isSuccess)
                phase.writeText("waiting-native-file-acceptance")
                withTimeout(600_000) { client.messages.first { all -> all[peer].orEmpty().any {
                    it.fileTransfer?.outgoing == true && it.fileTransfer.status == FileTransferStatus.Complete
                } } }
            }
            phase.writeText("waiting-native-file")
            val saved = withTimeout(1_800_000) { client.messages.first { all -> all[peer].orEmpty().any {
                it.fileTransfer?.outgoing == false && it.fileTransfer.status == FileTransferStatus.Complete
            } } }.getValue(peer).last { it.fileTransfer?.outgoing == false && it.fileTransfer.status == FileTransferStatus.Complete }.fileTransfer!!
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            assertArrayEquals("Incoming file must match the attached image byte for byte", hash(fixture), hash(File(saved.localPath!!)))
            phase.writeText("native-file-complete-offering-photos")
            val prepared = prepareSharedPhoto(context, Uri.fromFile(fixture))
            assertTrue("Native Photos invitation must send", client.sharePhotos(peer, listOf(prepared)).isSuccess)
            phase.writeText("waiting-native-photo-acceptance")
            val shared = withTimeout(600_000) { client.photoShares.first { all ->
                all[peer]?.photos?.any { it.downloadedByPeer } == true
            } }.getValue(peer)
            assertEquals(PhotoShareStatus.Active, shared.status)
            phase.writeText("photo-preview-ready-waiting-native-save")
            withTimeout(600_000) { client.photoShares.first { all -> all[peer]?.photos?.any { it.originalSentToPeer } == true } }
            phase.writeText("native-file-received-waiting-native-photo-addition")
            val receivedShare = withTimeout(600_000) { client.photoShares.first { all -> all[peer]?.photos?.any {
                it.preview.creator.equals(peer, true) && it.localPath != null
            } == true } }.getValue(peer)
            val receivedPhoto = receivedShare.photos.first { it.preview.creator.equals(peer, true) && it.localPath != null }
            assertTrue(client.requestPhotoOriginal(peer, receivedShare.id, receivedPhoto.id).isSuccess)
            val original = withTimeout(600_000) { client.photoShares.first { all ->
                all[peer]?.photos?.any { it.id == receivedPhoto.id && it.originalPath != null } == true
            } }.getValue(peer).photos.first { it.id == receivedPhoto.id }
            assertArrayEquals("Native original must match the attached image byte for byte", hash(fixture), hash(File(original.originalPath!!)))
            assertTrue("Android must add photos to an accepted native session", client.sharePhotos(peer, listOf(prepared.copy(name = "another-photo.png"))).isSuccess)
            val addedId = client.photoShares.value.getValue(peer).photos.last().id
            phase.writeText("android-added-photo-waiting-preview")
            withTimeout(600_000) { client.photoShares.first { all -> all[peer]?.photos?.any { it.id == addedId && it.downloadedByPeer } == true } }
            phase.writeText("android-added-photo-waiting-native-stop")
            val stopped = withTimeout(600_000) { client.photoShares.first { all ->
                all[peer]?.let { it.status == PhotoShareStatus.Stopped && it.remoteStopped } == true
            } }.getValue(peer)
            assertEquals("Both participants' additions must survive stopping", 3, stopped.photos.size)
            assertTrue("Stopping must retain the slideshow photos", stopped.photos.any { it.localPath != null })
            phase.writeText("waiting-native-new-photo-invitation")
            val incoming = withTimeout(600_000) { client.photoShares.first { all -> all[peer]?.let {
                !it.outgoing && it.status == PhotoShareStatus.Active && it.photos.any { photo -> photo.localPath != null }
            } == true } }.getValue(peer)
            val incomingPhoto = incoming.photos.first { it.localPath != null }
            assertTrue(client.requestPhotoOriginal(peer, incoming.id, incomingPhoto.id).isSuccess)
            val incomingOriginal = withTimeout(600_000) { client.photoShares.first { all -> all[peer]?.photos?.any { it.originalPath != null } == true } }
                .getValue(peer).photos.first { it.originalPath != null }
            assertArrayEquals("An original from a native-initiated share must preserve the PNG", hash(fixture), hash(File(incomingOriginal.originalPath!!)))
            phase.writeText("native-new-photo-received-waiting-native-stop")
            withTimeout(600_000) { client.photoShares.first { all -> all[peer]?.let { it.status == PhotoShareStatus.Stopped && it.remoteStopped } == true } }
            phase.writeText("complete")
        } finally { collector.cancel(); photoCollector.cancel(); client.close(); fixture.delete() }
    }
}

private class SharingTraceTransport(private val record: (String) -> Unit, private val label: String = "SB") : org.aralozkaya.msngr.protocol.MsnpTransport {
    private val delegate = org.aralozkaya.msngr.protocol.Msnp18SocketTransport()
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) = delegate.connect(host, port, timeoutMillis)
    override fun setReadTimeout(timeoutMillis: Int) = delegate.setReadTimeout(timeoutMillis)
    override suspend fun send(command: org.aralozkaya.msngr.protocol.MsnpCommand) { trace("OUT", command); delegate.send(command) }
    override suspend fun receive(): org.aralozkaya.msngr.protocol.MsnpCommand = delegate.receive().also { trace("IN", it) }
    override fun close() = delegate.close()
    private fun trace(direction: String, command: org.aralozkaya.msngr.protocol.MsnpCommand) {
        val mime = command.payload?.let(::parseSwitchboardMime) as? SwitchboardMimeMessage.P2p
        val packet = mime?.bytes?.let(P2pV2Packet::parse)
        if (packet != null) {
            record("$direction session=${packet.sessionId} id=${packet.identifier} ack=${packet.acknowledgementIdentifier} op=${packet.operationCode} tf=${packet.tfCombination} bytes=${packet.body.size} remaining=${packet.dataRemaining}")
            if (packet.sessionId == 0L && packet.body.isNotEmpty()) org.aralozkaya.msngr.data.SlpMessage.parse(packet.body)?.let {
                val verb = it.firstLine.substringBefore(" MSNMSGR")
                record("$direction SLP=$verb session=${it.body.field("SessionID")?.toLongOrNull()} app=${it.body.field("AppID")?.toIntOrNull()} call=${it.callId}")
                val objectMeta = it.body.field("Context")?.let(org.aralozkaya.msngr.protocol.MsnBase64::decode)
                    ?.toString(Charsets.UTF_8)?.trimEnd('\u0000')?.let { xml -> org.aralozkaya.msngr.protocol.MsnDisplayPicture.parseObject(xml, 15) }
                if (objectMeta != null) record("$direction PHOTO-OBJECT size=${objectMeta.size} hash=${objectMeta.sha1d}")
            }
        }
        else record("$label $direction ${command.verb} transaction=${command.transactionId}" +
            if (command.verb == "OUT") " reason=" + command.parameters.firstOrNull()?.takeIf { it.matches(Regex("[A-Z]{1,8}")) } else "")
    }
}
