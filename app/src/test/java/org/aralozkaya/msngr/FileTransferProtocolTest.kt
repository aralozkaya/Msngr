package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.MsnFileContext
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class FileTransferProtocolTest {
    @Test fun inviterAssignsOneOrderingToPhotosAddedByEitherParticipant() = runBlocking {
        val directory = Files.createTempDirectory("msngr-photo-order").toFile()
        val hostChanges = mutableListOf<PhotoShare>()
        val guestChanges = mutableListOf<PhotoShare>()
        val announcements = mutableListOf<String>()
        lateinit var host: MsnpPhotoSession
        lateinit var guest: MsnpPhotoSession
        host = MsnpPhotoSession("a@example.test", "b@example.test", { directory }, { hostChanges.add(it) },
            { guest.handle(requireNotNull(SlpMessage.parse(it))) }, { _, bytes ->
                announcements.add(bytes.toString(Charsets.UTF_16LE)); guest.receive(bytes)
            }, {})
        guest = MsnpPhotoSession("b@example.test", "a@example.test", { directory }, { guestChanges.add(it) },
            { host.handle(requireNotNull(SlpMessage.parse(it))) }, { _, bytes -> host.receive(bytes) }, {})
        fun photo(name: String) = PhotoToShare(name, "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", byteArrayOf(1), byteArrayOf(2))
        try {
            host.start(listOf(photo("first.png"), photo("second.png")))
            guest.accept()
            guest.start(listOf(photo("guest.png")))
            host.start(listOf(photo("fourth.png")))
            val order = hostChanges.last().photos.map { it.id }
            assertEquals(order, guestChanges.last().photos.map { it.id })
            assertEquals(listOf(1, 2, 3, 4), hostChanges.last().photos.map { it.sortIndex })
            assertEquals(listOf(1, 2, 3, 4), guestChanges.last().photos.map { it.sortIndex })
            val assigned = announcements.filter { it.contains("op=\"ADDH\"") || it.contains("op=\"SINDEX\"") }
            assertEquals(4, assigned.size)
            assertTrue(assigned[2].contains("op=\"SINDEX\""))
            assertTrue(assigned[2].contains("si=\"3\""))
            assertEquals(4, guestChanges.last().photos.size)
            assertEquals(hostChanges.last().selectedId, guestChanges.last().selectedId)
            assertTrue(announcements.last { it.contains("op=\"CHG\"") }.contains("seq=\"2\" ack=\"1\""))
        } finally { host.disconnect(); guest.disconnect(); directory.deleteRecursively() }
    }
    @Test fun v2PhotoRequestsReuseTheEstablishedBridgeWithoutAnotherSyn() = runBlocking {
        for (remoteStarts in listOf(false, true)) {
            val packets = mutableListOf<P2pV2Packet>()
            val peer = MsnpP2pV2Session("a@example.test", "11111111-1111-1111-1111-111111111111",
                "b@example.test", "22222222-2222-2222-2222-222222222222",
                { packets.add(requireNotNull(P2pV2Packet.parse(it))) }, { null }, {})
            if (remoteStarts) peer.handle(P2pV2Packet(operationCode = 3, identifier = 42, peerInfo = true).encode())
            peer.requestDisplayPicture(org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("b@example.test", byteArrayOf(1), 15))
            peer.requestDisplayPicture(org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("b@example.test", byteArrayOf(2), 15))
            val requests = packets.filter { it.body.isNotEmpty() }
            assertEquals(2, requests.size)
            assertEquals(if (remoteStarts) 0 else 1, requests.count { it.operationCode and 1 != 0 })
            assertEquals(0, requests.last().operationCode)
            peer.closeFileTransfers()
        }
    }
    @Test fun acceptingAnOldOfferStartsAFreshTransferTimeout() = runBlocking {
        val directory = Files.createTempDirectory("msngr-delayed-accept").toFile()
        val changes = java.util.concurrent.CopyOnWriteArrayList<FileTransfer>()
        val engine = MsnpFileSession("a@example.test", "b@example.test", { directory }, { changes.add(it) },
            {}, { _, _, _, _ -> }, offerTimeoutMillis = 5_000, idleTimeoutMillis = 400, expiryCheckMillis = 20)
        try {
            engine.handle(SlpMessage("INVITE MSNMSGR:a@example.test MSNSLP/1.0", "branch", "old-offer",
                MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                "EUF-GUID: {${MsnFileContext.GUID}}\r\nSessionID: 1\r\nAppID: 2\r\nContext: ${org.aralozkaya.msngr.protocol.MsnBase64.encode(MsnFileContext("late.bin", 20).encode())}\r\n"))
            delay(600)
            assertEquals(FileTransferStatus.Offered, changes.last().status)
            engine.accept("old-offer")
            delay(100)
            assertEquals(FileTransferStatus.Transferring, changes.last().status)
            engine.receive(1, 0, 20, ByteArray(20))
            assertEquals(FileTransferStatus.Complete, changes.last().status)
        } finally { engine.close(); directory.deleteRecursively() }
    }
    @Test fun photoOriginalRequestsTransferTheUnchangedFileWithHashValidation() = runBlocking {
        for (v2 in listOf(false, true)) {
            val directory = Files.createTempDirectory("msngr-photo-original").toFile()
            val bytes = ByteArray(170_000) { (it * 43).toByte() }
            val file = java.io.File(directory, "original.png").apply { writeBytes(bytes) }
            val hash = org.aralozkaya.msngr.protocol.MsnBase64.encode(java.security.MessageDigest.getInstance("SHA-1").digest(bytes))
            val receivedShares = java.util.concurrent.CopyOnWriteArrayList<PhotoShare>()
            val transfers = java.util.concurrent.CopyOnWriteArrayList<FileTransfer>()
            lateinit var a: MsnpP2pSession
            lateinit var b: MsnpP2pSession
            a = MsnpP2pSession("a@example.test", "b@example.test", { b.handle(it) }, { null }, {},
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                fileDirectory = { java.io.File(directory, "a") })
            b = MsnpP2pSession("b@example.test", "a@example.test", { a.handle(it) }, { null }, {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                fileDirectory = { java.io.File(directory, "b") }, onPhotoShare = { receivedShares.add(it) }, onFileTransfer = { transfers.add(it) })
            try {
                val id = a.startPhotos(listOf(PhotoToShare("original.png", hash, byteArrayOf(1), byteArrayOf(2), file.absolutePath)))
                b.acceptPhotos(id)
                b.requestPhotoOriginal(receivedShares.last().photos.single())
                withTimeout(5_000) { while (transfers.lastOrNull()?.status != FileTransferStatus.Complete) delay(5) }
                assertArrayEquals(bytes, java.io.File(transfers.last().localPath!!).readBytes())
                assertEquals(receivedShares.last().photos.single().id, transfers.last().photoId)
            } finally { a.closeFileTransfers(); b.closeFileTransfers(); directory.deleteRecursively() }
        }
    }
    @Test fun aSecondEndpointCanRetrieveThePublishedPhotoPreviewWithNativeApplication33() = runBlocking {
        val directory = Files.createTempDirectory("msngr-photo-endpoint").toFile()
        val bytes = ByteArray(6500) { (it * 19).toByte() }
        val metadata = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("a@example.test", bytes, 15)
        val packets = mutableListOf<ByteArray>()
        lateinit var sender: MsnpP2pSession
        lateinit var requester: MsnpP2pSession
        sender = MsnpP2pSession("a@example.test", "b@example.test", { packets.add(it); requester.handle(it) }, { null }, {},
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            localPhoto = { hash -> if (hash == metadata.sha1d) LocalDisplayPicture(metadata, bytes) else null })
        requester = MsnpP2pSession("b@example.test", "a@example.test", { sender.handle(it) }, { null }, {},
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111")
        try {
            assertEquals(33, MsnpP2pUtil.objectAppId(15))
            requester.requestDisplayPicture(metadata)
            val data = packets.mapNotNull(P2pV2Packet::parse).filter { it.sessionId != 0L && it.tfCombination and 4 != 0 }
                .flatMap { it.body.asIterable() }.toByteArray()
            assertArrayEquals(bytes, data)
        } finally { sender.closeFileTransfers(); requester.closeFileTransfers(); directory.deleteRecursively() }
    }
    @Test fun photoInvitationPreviewSelectionAndRemoteStopKeepTheSlideshow() = runBlocking {
        for (v2 in listOf(false, true)) {
            val directory = Files.createTempDirectory("msngr-photos").toFile()
            val received = java.util.concurrent.CopyOnWriteArrayList<PhotoShare>()
            val sent = java.util.concurrent.CopyOnWriteArrayList<PhotoShare>()
            lateinit var a: MsnpP2pSession
            lateinit var b: MsnpP2pSession
            a = MsnpP2pSession("a@example.test", "b@example.test", { b.handle(it) }, { null }, {},
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                fileDirectory = { java.io.File(directory, "a") }, onPhotoShare = { sent.add(it) })
            b = MsnpP2pSession("b@example.test", "a@example.test", { a.handle(it) }, { null }, {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                fileDirectory = { java.io.File(directory, "b") }, onPhotoShare = { received.add(it) })
            try {
                val preview = ByteArray(250_000) { it.toByte() }
                val id = a.startPhotos(listOf(PhotoToShare("one.png", "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", byteArrayOf(1,2,3), preview),
                    PhotoToShare("two.png", "BBBBBBBBBBBBBBBBBBBBBBBBBBB=", byteArrayOf(4,5,6), preview + byteArrayOf(1))))
                assertEquals(PhotoShareStatus.Invited, received.last().status)
                b.acceptPhotos(id)
                assertEquals(2, received.last().photos.size)
                assertArrayEquals(preview, java.io.File(received.last().photos.first().localPath!!).readBytes())
                val second = received.last().photos.last().id
                b.selectPhoto(id, second)
                assertEquals(second, sent.last().selectedId)
                // Either participant can add photos to the same accepted slideshow.
                assertEquals(id, b.startPhotos(listOf(PhotoToShare("from-recipient.png", "CCCCCCCCCCCCCCCCCCCCCCCCCCC=", byteArrayOf(7), preview + byteArrayOf(2)))))
                assertEquals(3, sent.last().photos.size)
                assertEquals(3, received.last().photos.size)
                assertTrue(java.io.File(sent.last().photos.last().localPath!!).isFile)
                a.stopPhotos(id)
                assertEquals(PhotoShareStatus.Stopped, received.last().status)
                assertTrue(received.last().remoteStopped)
                assertEquals(3, received.last().photos.size)
                assertTrue(java.io.File(received.last().photos.last().localPath!!).isFile)
            } finally { a.closeFileTransfers(); b.closeFileTransfers(); directory.deleteRecursively() }
        }
    }

    @Test fun invalidFileOffsetsAndCancellationRemovePartialFiles() = runBlocking {
        val directory = Files.createTempDirectory("msngr-invalid-file").toFile()
        val changes = mutableListOf<FileTransfer>()
        val replies = mutableListOf<SlpMessage>()
        val engine = MsnpFileSession("a@example.test", "b@example.test", { directory }, { changes.add(it) },
            { replies.add(requireNotNull(SlpMessage.parse(it))) }, { _, _, _, _ -> })
        fun offer(session: Long, name: String) = SlpMessage("INVITE MSNMSGR:a@example.test MSNSLP/1.0", "branch", "call-$session",
            MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
            "EUF-GUID: {${MsnFileContext.GUID}}\r\nSessionID: $session\r\nAppID: 2\r\nContext: ${org.aralozkaya.msngr.protocol.MsnBase64.encode(MsnFileContext(name, 20).encode())}\r\n")
        try {
            engine.handle(offer(1, "safe.bin")); engine.accept("call-1")
            engine.receive(1, 1, 20, byteArrayOf(1))
            assertEquals(FileTransferStatus.Failed, changes.last().status)
            assertTrue(directory.listFiles()!!.isEmpty())
            engine.handle(offer(2, "cancel.bin")); engine.accept("call-2")
            engine.receive(2, 0, 20, byteArrayOf(1, 2))
            assertTrue(directory.listFiles()!!.any { it.extension == "part" })
            engine.cancel("call-2")
            assertEquals(FileTransferStatus.Cancelled, changes.last().status)
            assertTrue(directory.listFiles()!!.isEmpty())
            val count = changes.size
            engine.handle(offer(3, "blocked.EXE"))
            assertEquals(count, changes.size)
            assertTrue(replies.last().firstLine.contains("603"))
        } finally { engine.close(); directory.deleteRecursively() }
    }
    @Test fun nativeVersionTwoPreviewContextAndUnsafeNames() {
        val context = MsnFileContext("holiday photo.png", 220_754).encode()
        val native = context.copyOf(700)
        ByteBuffer.wrap(native).order(ByteOrder.LITTLE_ENDIAN).putInt(574).putInt(2)
        assertEquals(MsnFileContext("holiday photo.png", 220_754), MsnFileContext.parse(native))
        for (name in listOf("../a.png", "..", "", "a/b", "a\\b", "a\u0000.png", "bad.", "bad "))
            assertFalse(name, MsnFileContext.validName(name))
        assertTrue(MsnFileContext.isExecutable("installer.EXE"))
        assertFalse(MsnFileContext.isExecutable("picture.exe.png"))
        ByteBuffer.wrap(native).order(ByteOrder.LITTLE_ENDIAN).putLong(8, -1)
        assertNull(MsnFileContext.parse(native))
    }

    @Test fun filesStreamOnlyAfterAcceptanceAndCompleteAcrossBothFramings() = runBlocking {
        for (v2 in listOf(false, true)) {
            val directory = Files.createTempDirectory("msngr-file").toFile()
            val bytes = ByteArray(150_123) { (it * 29).toByte() }
            val file = java.io.File(directory, "source.bin").apply { writeBytes(bytes) }
            val sending = java.util.concurrent.CopyOnWriteArrayList<FileTransfer>()
            val receiving = java.util.concurrent.CopyOnWriteArrayList<FileTransfer>()
            lateinit var a: MsnpP2pSession
            lateinit var b: MsnpP2pSession
            a = MsnpP2pSession("a@example.test", "b@example.test", { b.handle(it) }, { null }, {},
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                fileDirectory = { java.io.File(directory, "a") }, onFileTransfer = { sending.add(it) })
            b = MsnpP2pSession("b@example.test", "a@example.test", { a.handle(it) }, { null }, {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                fileDirectory = { java.io.File(directory, "b") }, onFileTransfer = { receiving.add(it) })
            try {
                val id = a.offerFile(file, "payload.bin")
                assertEquals(FileTransferStatus.Offered, receiving.last().status)
                assertEquals(0, receiving.last().transferred)
                assertNull(receiving.last().localPath)
                b.acceptFile(id)
                withTimeout(5_000) { while (sending.last().status != FileTransferStatus.Complete) delay(10) }
                assertEquals(FileTransferStatus.Complete, receiving.last().status)
                assertArrayEquals(bytes, java.io.File(receiving.last().localPath!!).readBytes())
                val rejected = a.offerFile(file, "reject.bin")
                b.cancelFile(rejected)
                assertEquals(FileTransferStatus.Declined, sending.last().status)
                assertEquals(FileTransferStatus.Declined, receiving.last().status)
                val cancelled = a.offerFile(file, "cancel.bin")
                a.cancelFile(cancelled)
                assertEquals(FileTransferStatus.Cancelled, receiving.last().status)
            } finally { a.closeFileTransfers(); b.closeFileTransfers(); directory.deleteRecursively() }
        }
    }
}
