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

class FileTransferProtocolTest {
    @Test fun bothPeersCanOfferBeforeReceivingTheOtherInvitation() = runBlocking {
        val directory = createTestTempDir("msngr-crossed-offers")
        val firstInvites = mutableListOf<SlpMessage>()
        val secondInvites = mutableListOf<SlpMessage>()
        val first = MsnpFileSession("first", "second", { directory }, {},
            { firstInvites.add(requireNotNull(SlpMessage.parse(it))) }, { _, _, _, _ -> })
        val second = MsnpFileSession("second", "first", { directory }, {},
            { secondInvites.add(requireNotNull(SlpMessage.parse(it))) }, { _, _, _, _ -> })
        try {
            val file = java.io.File(directory, "shared.txt").apply { writeText("hello") }
            val a = first.offer(file, file.name)
            val b = second.offer(file, file.name)
            first.handle(secondInvites.single())
            second.handle(firstInvites.first())
            for (peer in listOf(first, second)) {
                assertTrue(peer.owns(a))
                assertTrue(peer.owns(b))
            }
            first.close()
            assertTrue(runCatching { first.offer(file, file.name) }.isFailure)
            assertFalse(first.owns(a))
            assertFalse(first.owns(b))
        } finally {
            first.close()
            second.close()
            directory.deleteRecursively()
        }
    }

    @Test fun inviterAssignsOneOrderingToPhotosAddedByEitherParticipant() = runBlocking {
        val directory = createTestTempDir("msngr-photo-order")
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
        val directory = createTestTempDir("msngr-delayed-accept")
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
            val directory = createTestTempDir("msngr-photo-original")
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
        val directory = createTestTempDir("msngr-photo-endpoint")
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
            val directory = createTestTempDir("msngr-photos")
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
        val directory = createTestTempDir("msngr-invalid-file")
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
    @Test fun storageFailureEndsTheOfferAndNotifiesTheSender() = runBlocking {
        val directory = createTestTempDir("msngr-file-storage-error")
        val unavailable = java.io.File(directory, "not-a-directory").apply { createNewFile() }
        val changes = mutableListOf<FileTransfer>()
        val responses = mutableListOf<SlpMessage>()
        val engine = MsnpFileSession("a", "b", { unavailable }, changes::add,
            { responses.add(requireNotNull(SlpMessage.parse(it))) }, { _, _, _, _ -> })
        try {
            val context = org.aralozkaya.msngr.protocol.MsnBase64.encode(MsnFileContext("note.txt", 3).encode())
            engine.handle(SlpMessage("INVITE MSNMSGR:a MSNSLP/1.0", "branch", "offer",
                MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
                "EUF-GUID: {${MsnFileContext.GUID}}\r\nSessionID: 11\r\nAppID: 2\r\nContext: $context\r\n"))
            assertTrue(runCatching { engine.accept("offer") }.isFailure)
            assertFalse(engine.owns("offer"))
            assertEquals(FileTransferStatus.Failed, changes.last().status)
            assertEquals("MSNSLP/1.0 603 Decline", responses.single().firstLine)
        } finally {
            engine.close()
            directory.deleteRecursively()
        }
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
            val directory = createTestTempDir("msngr-file")
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

    @Test fun photoShareOutgoingBytesTrackPendingPreviewAndOriginalProgress() {
        val hashA = "AAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val hashB = "BBBBBBBBBBBBBBBBBBBBBBBBBBB="
        val thumb = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("a@example.test", byteArrayOf(1, 2, 3), 15)
        val previewBytes = ByteArray(1_000) { it.toByte() }
        val preview = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("a@example.test", previewBytes, 15)

        val invited = PhotoShare(
            id = "invite-1",
            outgoing = true,
            status = PhotoShareStatus.Invited,
            pendingBytes = 2_400L,
        )
        assertEquals(2_400L, invited.totalOutgoingBytes)
        assertEquals(0L, invited.sharedOutgoingBytes)
        assertEquals(2_400L, invited.remainingOutgoingBytes)

        val active = PhotoShare(
            id = "invite-1",
            outgoing = true,
            status = PhotoShareStatus.Active,
            photos = listOf(
                SharedPhoto(
                    id = "p1",
                    name = "one.jpg",
                    hash = hashA,
                    thumbnail = thumb,
                    preview = preview,
                    outgoing = true,
                    previewSentBytes = 400L,
                ),
                SharedPhoto(
                    id = "p2",
                    name = "two.jpg",
                    hash = hashB,
                    thumbnail = thumb,
                    preview = preview,
                    outgoing = true,
                    downloadedByPeer = true,
                    originalSize = 2_000L,
                    originalTransferredBytes = 500L,
                ),
                SharedPhoto(
                    id = "p3",
                    name = "peer.jpg",
                    hash = "CCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                    thumbnail = thumb,
                    preview = preview,
                    outgoing = false,
                    previewSentBytes = 999L,
                ),
            ),
        )
        assertEquals(4_000L, active.totalOutgoingBytes)
        assertEquals(1_900L, active.sharedOutgoingBytes)
        assertEquals(2_100L, active.remainingOutgoingBytes)
    }

    @Test fun photoSessionRecordsOutgoingPreviewBytesAcrossP2pTransports() = runBlocking {
        for (v2 in listOf(false, true)) {
            val directory = createTestTempDir("msngr-photo-progress")
            val hostChanges = java.util.concurrent.CopyOnWriteArrayList<PhotoShare>()
            val guestChanges = java.util.concurrent.CopyOnWriteArrayList<PhotoShare>()
            lateinit var hostP2p: MsnpP2pSession
            lateinit var guestP2p: MsnpP2pSession
            val hostPhotos = java.util.concurrent.ConcurrentHashMap<String, LocalDisplayPicture>()
            hostP2p = MsnpP2pSession(
                localEmail = "a@example.test",
                remoteEmail = "b@example.test",
                sendPacket = { guestP2p.handle(it) },
                localPicture = { null },
                onDisplayPicture = {},
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                fileDirectory = { java.io.File(directory, "a") },
                onPhotoShare = { hostChanges.add(it) },
                publishPhoto = { pic -> hostPhotos[pic.metadata.sha1d] = pic },
                localPhoto = { hash -> hostPhotos[hash] },
            )
            guestP2p = MsnpP2pSession(
                localEmail = "b@example.test",
                remoteEmail = "a@example.test",
                sendPacket = { hostP2p.handle(it) },
                localPicture = { null },
                onDisplayPicture = {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                fileDirectory = { java.io.File(directory, "b") },
                onPhotoShare = { guestChanges.add(it) },
            )

            try {
                val previewPayload = ByteArray(140_000) { (it * 13).toByte() }
                val photo = PhotoToShare("landscape.jpg", "DDDDDDDDDDDDDDDDDDDDDDDDDDD=", byteArrayOf(7, 8, 9), previewPayload)
                val shareId = hostP2p.startPhotos(listOf(photo))
                val initial = hostChanges.last()
                assertEquals(PhotoShareStatus.Invited, initial.status)
                assertTrue(initial.pendingBytes > 0L)
                assertTrue(initial.totalOutgoingBytes > 0L)

                guestP2p.acceptPhotos(shareId)
                withTimeout(5_000) {
                    while (hostChanges.last().sharedOutgoingBytes < hostChanges.last().totalOutgoingBytes ||
                        hostChanges.last().totalOutgoingBytes == 0L
                    ) {
                        delay(15)
                    }
                }
                val latest = hostChanges.last()
                assertEquals(0L, latest.pendingBytes)
                assertEquals(latest.totalOutgoingBytes, latest.sharedOutgoingBytes)
                assertEquals(0L, latest.remainingOutgoingBytes)
                assertTrue(hostChanges.any { it.photos.any { p -> p.previewSentBytes > 0L } })
            } finally {
                hostP2p.closeFileTransfers()
                guestP2p.closeFileTransfers()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun mockClientTracksAndCancelsOutgoingFileAndPhotoShares() = runBlocking {
        val dir = createTestTempDir("msngr-mock-transfers")
        val file = java.io.File(dir, "sample.bin").apply { writeBytes(ByteArray(4_096) { it.toByte() }) }
        val client = MockMsnp18Client(
            photoCacheDirectory = java.io.File(dir, "photos"),
            transferStepDelayMillis = 40L,
        )
        try {
            val signIn = client.signIn(
                SignInRequest(
                    email = MockServerData.ACCOUNT_EMAIL,
                    password = MockServerData.ACCOUNT_PASSWORD,
                    serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
                ),
            )
            assertTrue(signIn.isSuccess)

            client.sendFile("selin", file, "sample.bin").getOrThrow()
            withTimeout(2_000) {
                while (client.messages.value["selin"].orEmpty().none { it.fileTransfer?.status == FileTransferStatus.Complete }) {
                    delay(15)
                }
            }
            val completed = client.messages.value["selin"].orEmpty().last().fileTransfer!!
            assertEquals(4_096L, completed.transferred)

            client.sendFile("mert", file, "abort.bin").getOrThrow()
            val pendingId = client.messages.value["mert"].orEmpty().last().fileTransfer!!.id
            client.cancelFile("mert", pendingId).getOrThrow()
            assertEquals(
                FileTransferStatus.Cancelled,
                client.messages.value["mert"].orEmpty().last { it.fileTransfer?.id == pendingId }.fileTransfer?.status,
            )

            val photos = (1..2).map { index ->
                PhotoToShare("p$index.png", "HASH$index", byteArrayOf(index.toByte()), ByteArray(512) { index.toByte() })
            }
            client.sharePhotos("selin", photos).getOrThrow()
            withTimeout(2_000) {
                val share = client.photoShares.value["selin"]
                while (share == null || client.photoShares.value["selin"]?.sharedOutgoingBytes != client.photoShares.value["selin"]?.totalOutgoingBytes ||
                    client.photoShares.value["selin"]?.totalOutgoingBytes == 0L
                ) {
                    delay(15)
                }
            }
            val shareId = client.photoShares.value["selin"]!!.id
            client.stopPhotos("selin", shareId).getOrThrow()
            assertEquals(PhotoShareStatus.Stopped, client.photoShares.value["selin"]?.status)
        } finally {
            client.close()
            dir.deleteRecursively()
        }
    }

    @Test fun recipientFeaturesDifferentiateFileAndPhotoSupport() {
        val full = Contact(
            id = "full",
            nickname = "Full",
            email = "full@example.test",
            presence = org.aralozkaya.msngr.enums.Presence.Online,
            clientCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = org.aralozkaya.msngr.protocol.Msnp18Protocol.EXTENDED_CAPABILITIES.toLong(),
        )
        val fullFeatures = full.recipientFeatures("me@example.test")
        assertTrue(fullFeatures.files)
        assertTrue(fullFeatures.photos)

        val msnc1FilesOnly = Contact(
            id = "msnc1",
            nickname = "Legacy",
            email = "legacy@example.test",
            presence = org.aralozkaya.msngr.enums.Presence.Online,
            // Chunking (0x20) + MSNC1 (0x10000000) without MSNC10 / P2Pv2.
            clientCapabilities = 0x1000_0020L,
            extendedCapabilities = 0L,
        )
        val legacyFeatures = msnc1FilesOnly.recipientFeatures("me@example.test")
        assertTrue(legacyFeatures.files)
        assertFalse(legacyFeatures.photos)

        val offline = full.copy(presence = org.aralozkaya.msngr.enums.Presence.Offline)
        val offlineFeatures = offline.recipientFeatures("me@example.test")
        assertFalse(offlineFeatures.files)
        assertFalse(offlineFeatures.photos)
    }
}
