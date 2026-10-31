package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.Assert.*
import org.junit.Test

class TransferAutomationTest {
    private class Client(scope: CoroutineScope) : MsnpClient by MockMsnp18Client(scope, signInDelayMillis = 0) {
        override val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
        override val photoShares = MutableStateFlow<Map<String, PhotoShare>>(emptyMap())
        val acceptedFiles = mutableListOf<String>()
        val acceptedShares = mutableListOf<String>()
        val requestedPhotos = mutableListOf<String>()
        override suspend fun acceptFile(contactId: String, id: String): Result<Unit> {
            acceptedFiles += id
            return Result.success(Unit)
        }
        override suspend fun acceptPhotos(contactId: String, id: String): Result<Unit> {
            acceptedShares += id
            return Result.success(Unit)
        }
        override suspend fun requestPhotoOriginal(contactId: String, shareId: String, photoId: String): Result<Unit> {
            requestedPhotos += photoId
            photoShares.update { all -> all + (contactId to all.getValue(contactId).let { share ->
                share.copy(photos = share.photos.map { if (it.id == photoId) it.copy(originalPending = true) else it })
            }) }
            return Result.success(Unit)
        }
        fun file(value: FileTransfer) {
            messages.value = mapOf("peer" to listOf(ChatMessage(1, "peer", MessageAuthor.Contact, "File", 1, fileTransfer = value)))
        }
        fun completePhoto(id: String) {
            photoShares.update { all -> all + ("peer" to all.getValue("peer").let { share ->
                share.copy(photos = share.photos.map { if (it.id == id) it.copy(originalPending = false, originalPath = "/verified/$id") else it })
            }) }
        }
    }

    private class Fixture(var policy: TransferSettings = TransferSettings()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = Client(scope)
        val history = InMemoryConversationHistoryStore()
        val repository = MessengerRepository(client, scope, historyStore = history, useMockServer = true)
        val savedFiles = mutableListOf<Pair<AccountIdentity, FileTransfer>>()
        val savedPhotos = mutableListOf<SharedPhoto>()
        val automation = TransferAutomation({ policy }, { account, file -> savedFiles += account to file },
            { _, _, photo -> savedPhotos += photo }, scope)
        suspend fun start() {
            signIn("owner@example.test")
            automation.attach(repository)
        }
        suspend fun signIn(email: String) {
            repository.signIn(SignInRequest(email, "test", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
        }
        fun close() { repository.logout(); scope.cancel() }
    }

    @Test fun defaultsRequireAcceptanceAndDoNotSaveFilesPhotosOrRestoredHistory() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.history.merge(mapOf("old" to listOf(ChatMessage(2, "old", MessageAuthor.Contact, "Old", 0,
                fileTransfer = FileTransfer("old", "old.txt", 1, false, FileTransferStatus.Complete, localPath = "/old")))))
            f.client.file(FileTransfer("file", "document.txt", 1, false))
            f.client.photoShares.value = mapOf("peer" to PhotoShare("share", false))
            f.client.file(FileTransfer("file", "document.txt", 1, false, FileTransferStatus.Complete, localPath = "/verified"))
            assertTrue(f.client.acceptedFiles.isEmpty())
            assertTrue(f.client.acceptedShares.isEmpty())
            assertTrue(f.client.requestedPhotos.isEmpty())
            assertTrue(f.savedFiles.isEmpty())
            assertTrue(f.savedPhotos.isEmpty())
        } finally { f.close() }
    }

    @Test fun autoSaveFilesIsIndependentOfAcceptanceAndSkipsOutgoingFailedAndRepeatedUpdates() = runBlocking {
        val f = Fixture(TransferSettings(autoSaveFiles = true))
        try {
            f.start()
            val file = FileTransfer("file", "document.txt", 10, false)
            f.client.file(file)
            assertTrue(f.client.acceptedFiles.isEmpty())
            assertTrue(f.savedFiles.isEmpty())
            f.client.file(file.copy(status = FileTransferStatus.Failed, localPath = "/partial"))
            f.client.file(file.copy(outgoing = true, status = FileTransferStatus.Complete, localPath = "/outgoing"))
            assertTrue(f.savedFiles.isEmpty())
            f.client.file(file.copy(status = FileTransferStatus.Complete, localPath = "/verified"))
            f.client.file(file.copy(status = FileTransferStatus.Complete, localPath = "/verified", transferred = 10))
            assertEquals(listOf("/verified"), f.savedFiles.map { it.second.localPath })
            f.client.photoShares.value = mapOf("peer" to PhotoShare("photos", false, PhotoShareStatus.Active, listOf(photo("remote"))))
            assertTrue(f.client.requestedPhotos.isEmpty())
            assertTrue(f.savedPhotos.isEmpty())
        } finally { f.close() }
    }

    @Test fun autoAcceptanceHandlesIncomingFilesAndPhotoInvitationsOnceWithoutAnOpenChat() = runBlocking {
        val f = Fixture(TransferSettings(autoAccept = true))
        try {
            f.start()
            f.client.file(FileTransfer("incoming", "document.txt", 1, false))
            f.client.file(FileTransfer("incoming", "renamed.txt", 1, false))
            f.client.file(FileTransfer("outgoing", "out.txt", 1, true))
            f.client.photoShares.value = mapOf("peer" to PhotoShare("incoming", false))
            f.client.photoShares.value = mapOf("peer" to PhotoShare("incoming", false, photos = listOf(photo("preview"))))
            f.client.photoShares.value = mapOf("peer" to PhotoShare("outgoing", true))
            assertEquals(listOf("incoming"), f.client.acceptedFiles)
            assertEquals(listOf("incoming"), f.client.acceptedShares)
            assertTrue(f.savedFiles.isEmpty())
        } finally { f.close() }
    }

    @Test fun optionalPhotoAutosaveQueues32OriginalsAndNeverExportsLocalPhotos() = runBlocking {
        val f = Fixture(TransferSettings(autoSaveFiles = true, autoSavePhotos = true))
        try {
            f.start()
            val photos = (1..31).map { photo("remote-$it") } + photo("mine").copy(outgoing = true, originalPath = "/mine")
            f.client.photoShares.value = mapOf("peer" to PhotoShare("share", true, PhotoShareStatus.Active, photos))
            for (i in 1..31) {
                assertEquals(i, f.client.requestedPhotos.size)
                assertEquals("remote-$i", f.client.requestedPhotos.last())
                f.client.completePhoto("remote-$i")
            }
            assertEquals(31, f.savedPhotos.size)
            assertTrue(f.savedPhotos.all { !it.outgoing && it.originalPath == "/verified/${it.id}" })
            f.client.photoShares.update { all -> all.mapValues { it.value.copy(status = PhotoShareStatus.Stopped) } }
            assertEquals(31, f.savedPhotos.size)
            f.policy = TransferSettings(autoSavePhotos = true)
            f.client.photoShares.value = mapOf("peer" to PhotoShare("new", false, PhotoShareStatus.Active, listOf(photo("not-enabled"))))
            assertEquals(31, f.client.requestedPhotos.size)
        } finally { f.close() }
    }

    @Test fun savingUsesTheSignedInAccountAndRepeatedIdsAreIndependentAcrossAccounts() = runBlocking {
        val f = Fixture(TransferSettings(autoSaveFiles = true))
        try {
            f.start()
            val file = FileTransfer("same", "document.txt", 1, false, FileTransferStatus.Complete, localPath = "/verified")
            f.client.file(file)
            f.client.messages.value = emptyMap()
            f.signIn("second@example.test")
            f.client.file(file)
            assertEquals(listOf("owner@example.test", "second@example.test"), f.savedFiles.map { it.first.email })
        } finally { f.close() }
    }

    companion object {
        private fun photo(id: String): SharedPhoto {
            val preview = MsnDisplayPicture.create("peer@example.test", byteArrayOf(1), 15)
            return SharedPhoto(id, "$id.png", "hash", preview, preview)
        }
    }
}
