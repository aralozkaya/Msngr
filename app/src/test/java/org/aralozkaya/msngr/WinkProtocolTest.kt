package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WinkProtocolTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/winks/$name")!!.use { it.readBytes() }
    private fun cab() = fixture("screen-punch.cab")

    @Test fun readsOriginalsAndEscargotNestedInstaller() {
        File("src/main/assets/winks").listFiles()!!.filter { it.extension == "mct" }.forEach { file ->
            val wink = MsnWinkPackage.read(file.readBytes())
            assertNotNull(file.name, wink.thumbnail)
            assertTrue(wink.animation.size > 8)
            assertArrayEquals(file.readBytes(), wink.bytes())
        }
        val original = MsnWinkPackage.read(cab())
        val lzx = MsnWinkPackage.read(fixture("screen-punch-lzx.cab"))
        assertArrayEquals(original.animation, lzx.animation)
        assertArrayEquals(original.thumbnail, lzx.thumbnail)
        val winks = MsnWinkPackage.install(fixture("screen-punch.mco"), "Screen Punch")
        assertEquals(1, winks.size)
        val (reference, bytes) = winks.single()
        assertArrayEquals(cab(), bytes)
        assertEquals("Screen Punch", reference.name)
        assertTrue(reference.metadata.extraAttributes.getValue("stamp").length > 1000)
    }

    @Test fun chunksNativeSignaturesAndPreservesAllMetadata() {
        val (reference, _) = MsnWinkPackage.install(fixture("screen-punch.mco")).single()
        val metadata = reference.metadata.copy(extraAttributes = reference.metadata.extraAttributes + ("custom" to "a&b<foo>"))
        val payloads = MsnWink.payloads(metadata)
        assertTrue(payloads.size > 1)
        assertTrue(payloads.all { it.size <= 1664 })
        val assembler = SwitchboardChunkAssembler()
        val complete = payloads.mapNotNull { assembler.feed("sender@example.test", it) }.single()
        val parsed = parseSwitchboardMime(complete) as SwitchboardMimeMessage.WinkOffer
        assertEquals(metadata, parsed.metadata)
        assertEquals(metadata, MsnDisplayPicture.parseObject(metadata.toEncodedValue(), 8))
        val content = MsnpP2pUtil.displayPictureRequestContent(123, metadata)
        assertEquals("1", content.field("AppID"))
        assertEquals("{A4268EEC-FEC5-49E5-95C3-F126696BDBF6}", content.field("EUF-GUID"))
    }

    @Test fun rejectsBrokenChecksumsBombsBadXmlTraversalAndOversizeOffers() {
        val bytes = cab()
        for (length in listOf(0, 35, bytes.size - 1)) assertNull(MsnWinkPackage.parse(bytes.copyOf(length)))
        assertNull(MsnWinkPackage.parse(bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }))
        assertNull(MsnWinkPackage.parse(ByteArray(MsnWink.MAX_CAB_BYTES + 1)))
        val objectXml = MsnDisplayPicture.create("peer", bytes, 8).toXml()
        assertNull(MsnWink.parseOffer(objectXml.replace("Size=\"${bytes.size}\"", "Size=\"${Int.MAX_VALUE}\"")))
        assertNull(MsnWink.parseOffer(objectXml.replace("Type=\"8\"", "Type=\"3\"")))
        for (files in listOf(
            listOf("../content.xml" to byteArrayOf(1)),
            listOf("content.xml" to "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///secret'>]><package type='wink'>&a;</package>".toByteArray()),
            listOf("content.xml" to "<package type='wink'><item type='animation' file='../evil.swf'/></package>".toByteArray()))) {
            assertNull(MsnWinkPackage.parse(storedCab(files)))
        }
        val bomb = storedCab(listOf("content.xml" to byteArrayOf(1))).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(44, Int.MAX_VALUE)
        }
        assertNull(MsnWinkPackage.parse(bomb))
    }

    @Test fun transfersWinksOverBothP2pVersionsAndChecksHashes() = runBlocking {
        for (v2 in listOf(false, true)) {
            val bytes = ByteArray(300_000) { (it * 17).toByte() } // Larger than display picture limits.
            val metadata = MsnDisplayPicture.create("owner@example.test", bytes, 8)
            lateinit var requester: MsnpP2pSession
            lateinit var owner: MsnpP2pSession
            var received: ByteArray? = null
            requester = MsnpP2pSession("peer@example.test", metadata.creator, { owner.handle(it) }, { null }, { fail("Wink routed as display picture") },
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                onWink = { offered, data -> assertEquals(metadata, offered); received = data })
            owner = MsnpP2pSession(metadata.creator, "peer@example.test", { requester.handle(it) }, { null }, {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                localWink = { hash -> if (hash == metadata.sha1d) LocalDisplayPicture(metadata, bytes) else null })
            requester.requestDisplayPicture(metadata)
            assertArrayEquals("P2Pv2=$v2", bytes, received)
        }
    }

    @Test fun cacheIsAccountScopedDeduplicatedAndPreservesInstalledWinksWhenHistoryIsCleared() {
        val directory = Files.createTempDirectory("wink-store").toFile()
        try {
            val store = WinkStore(directory)
            val bytes = cab(); val reference = WinkReference(MsnDisplayPicture.create("peer", bytes, 8), "Screen Punch")
            store.receive("account", "peer1", reference, bytes)
            store.receive("account", "peer2", reference, bytes)
            assertEquals(1, directory.walkTopDown().count { it.extension == "cab" })
            assertNull(store.bytes("other-account", reference.metadata))
            store.clearReceived("account", "peer1")
            assertArrayEquals(bytes, store.bytes("account", reference.metadata))
            store.clearReceived("account", "peer2")
            assertNull(store.bytes("account", reference.metadata))
            store.install("account", reference, bytes); store.clearReceived("account")
            assertEquals(listOf(reference), store.catalog("account"))
            assertArrayEquals(bytes, store.bytes("account", reference.metadata))
        } finally { directory.deleteRecursively() }
    }

    @Test fun retryReplacesAStalledWinkRequestInBothP2pVersions() = runBlocking {
        for (v2 in listOf(false, true)) {
            val bytes = cab()
            val metadata = MsnDisplayPicture.create("owner@example.test", bytes, 8)
            lateinit var requester: MsnpP2pSession
            lateinit var owner: MsnpP2pSession
            var dropPackets = true
            var received: ByteArray? = null
            requester = MsnpP2pSession("peer@example.test", metadata.creator,
                { if (!dropPackets) owner.handle(it) }, { null }, { fail("Wink routed as display picture") },
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                onWink = { _, data -> received = data })
            owner = MsnpP2pSession(metadata.creator, "peer@example.test", { requester.handle(it) }, { null }, {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-111111111111" else null,
                localWink = { LocalDisplayPicture(metadata, bytes) })
            requester.requestDisplayPicture(metadata)
            dropPackets = false
            requester.requestDisplayPicture(metadata)
            assertNull("A normal duplicate must still be suppressed (P2Pv2=$v2)", received)
            requester.requestDisplayPicture(metadata, retry = true)
            assertArrayEquals("Retry must start a fresh transfer (P2Pv2=$v2)", bytes, received)
        }
    }

    @Test fun deletingCustomWinksPreservesDefaultsOtherAccountsAndReceivedCopies() {
        val directory = Files.createTempDirectory("wink-delete").toFile()
        try {
            val bundledBytes = cab()
            val bundled = WinkReference(MsnDisplayPicture.create("bundled", bundledBytes, 8), "Bundled")
            val store = WinkStore(directory) { listOf(bundled to bundledBytes) }
            val bytes = fixture("screen-punch-lzx.cab")
            val custom = WinkReference(MsnDisplayPicture.create("peer", bytes, 8), "Custom")
            store.receive("account", "peer", custom, bytes)
            store.install("account", custom, bytes)
            store.install("other", custom, bytes)
            val changes = store.libraryChanges.value
            assertTrue(store.isDefault(bundled.metadata.copy(creator = "peer")))
            assertFalse(store.isDefault(custom.metadata))
            assertTrue(runCatching { store.remove("account", bundled.metadata) }.isFailure)
            store.remove("account", custom.metadata)
            assertTrue(store.libraryChanges.value > changes)
            assertEquals(listOf(bundled), store.catalog("account"))
            assertFalse(store.isSaved("account", custom.metadata))
            assertTrue(store.isSaved("other", custom.metadata))
            assertArrayEquals(bytes, store.bytes("account", custom.metadata))
            store.clearReceived("account")
            assertNull(store.bytes("account", custom.metadata))
            assertArrayEquals(bundledBytes, store.bytes("account", bundled.metadata))
        } finally { directory.deleteRecursively() }
    }

    @Test fun savedWinksIncludeDefaultsAndInstalledPackagesButNotReceivedCache() {
        val directory = Files.createTempDirectory("wink-saved-state").toFile()
        try {
            val bundledBytes = cab()
            val bundled = WinkReference(MsnDisplayPicture.create("bundled", bundledBytes, 8), "Bundled")
            val store = WinkStore(directory) { listOf(bundled to bundledBytes) }
            assertTrue(store.isSaved("account", bundled.metadata.copy(creator = "peer")))
            val bytes = fixture("screen-punch-lzx.cab")
            val reference = WinkReference(MsnDisplayPicture.create("peer", bytes, 8), "Received")
            store.receive("account", "peer", reference, bytes)
            assertFalse(store.isSaved("account", reference.metadata))
            store.install("account", reference, bytes)
            assertTrue(store.isSaved("account", reference.metadata.copy(creator = "another-peer")))
            assertFalse(store.isSaved("other-account", reference.metadata))
            assertFalse(store.isSaved("account", reference.metadata.copy(size = bytes.size + 1)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun historyRoundTripsReferencesAndMarksInterruptedDownload() {
        val directory = Files.createTempDirectory("wink-history").toFile()
        try {
            val reference = WinkReference(MsnDisplayPicture.create("peer", cab(), 8).copy(extraAttributes = mapOf("stamp" to "original")), "Screen Punch")
            val history = FileConversationHistoryStore(directory)
            history.selectAccount("account")
            history.merge(mapOf("peer" to listOf(ChatMessage(1, "peer", MessageAuthor.Contact, "Wink", 1,
                wink = reference, isWinkPending = true, autoPlayWink = true))))
            val restored = FileConversationHistoryStore(directory)
            restored.selectAccount("account"); restored.loadLatest("peer")
            val message = restored.messages.value.getValue("peer").single()
            assertEquals(reference, message.wink)
            assertNotNull(message.winkTransferError)
            assertFalse(message.isWinkPending)
            assertFalse(message.autoPlayWink)
            assertTrue(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } < cab().size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun winkDownloadUrlsAreRestrictedAndUpgradedToHttps() {
        val path = "static.levelleap.com/sites/messaging/apps.escargot.nina.chat/winks/files/test.mco"
        assertEquals("https://$path", WinkDownloads.trustedPackageUrl("http://$path"))
        for (url in listOf("https://evil.test/test.mco", "file:///secret", "https://$path/../test.mco", "https://user@$path"))
            assertNull(url, WinkDownloads.trustedPackageUrl(url))
    }

    private fun storedCab(files: List<Pair<String, ByteArray>>): ByteArray {
        val entriesSize = files.sumOf { 16 + it.first.toByteArray().size + 1 }
        val payload = files.fold(byteArrayOf()) { bytes, file -> bytes + file.second }
        val offset = 44 + entriesSize
        val out = ByteBuffer.allocate(offset + 8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x4643534d).putInt(0).putInt(out.capacity()).putInt(0).putInt(44).putInt(0)
            .put(3).put(1).putShort(1).putShort(files.size.toShort()).putShort(0).putShort(0).putShort(0)
        out.putInt(offset).putShort(1).putShort(0)
        var position = 0
        for ((name, data) in files) { out.putInt(data.size).putInt(position).putShort(0).putShort(0).putShort(0).putShort(0).put(name.toByteArray()).put(0); position += data.size }
        out.putInt(0).putShort(payload.size.toShort()).putShort(payload.size.toShort()).put(payload)
        return out.array()
    }
}
