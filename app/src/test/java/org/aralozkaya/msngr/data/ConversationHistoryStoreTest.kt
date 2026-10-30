package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.MsnEmoticonRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ConversationHistoryStoreTest {
    @Test fun fileHistoryRetainsCompletedFilesAndFailsInterruptedOffersOnRestart() {
        withHistoryDirectory("msngr-history-files") { directory ->
            val complete = message(1).copy(fileTransfer = FileTransfer("complete", "photo.png", 42, false,
                FileTransferStatus.Complete, 42, File(directory, "received.bin").absolutePath))
            val pending = message(2).copy(fileTransfer = FileTransfer("pending", "document.pdf", 1000, true,
                FileTransferStatus.Transferring, 100))
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT); merge(mapOf(CONTACT to listOf(complete, pending)))
            }
            val restored = FileConversationHistoryStore(directory).apply { selectAccount(ACCOUNT); loadLatest(CONTACT) }
                .messages.value.getValue(CONTACT)
            assertEquals(complete.fileTransfer, restored.first().fileTransfer)
            assertEquals(FileTransferStatus.Failed, restored.last().fileTransfer?.status)
            assertEquals(100L, restored.last().fileTransfer?.transferred)
            assertTrue(restored.last().fileTransfer?.error.orEmpty().contains("interrupted"))
        }
    }
    @Test fun nativeMessageStyleSurvivesRestart() {
        withHistoryDirectory("msngr-history-native-style") { directory ->
            val styled = message(1).copy(nativeStyle = org.aralozkaya.msngr.NativeTextStyle(
                strikeout = true, underline = true, colorRgb = 0x123456, size = 24)
                .withFontStyle(org.aralozkaya.msngr.NativeFontStyle.BlackItalic))
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to listOf(styled)))
            }
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT)
                loadLatest(CONTACT)
                assertEquals(styled, messages.value.getValue(CONTACT).single())
            }
        }
    }
    @Test
    fun offlineMessagesOlderThanLoadedPagesAreDurableAndDeduplicatedAcrossRestart() {
        withHistoryDirectory("msngr-history-offline") { directory ->
            val original = (10L..16L).map(::message)
            FileConversationHistoryStore(directory, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to original))
            }
            val offline = message(-50).copy(timestampMillis = 1L, senderEmail = CONTACT)
            val store = FileConversationHistoryStore(directory, pageSize = 2)
            store.selectAccount(ACCOUNT)
            store.loadLatest(CONTACT)
            assertEquals(listOf(16L), store.loadedIds())
            assertTrue(store.persistOfflineMessage(offline))
            assertFalse(store.persistOfflineMessage(offline))
            assertEquals(listOf(offline) + original, store.messages.value.getValue(CONTACT))
            val restarted = FileConversationHistoryStore(directory, pageSize = 2)
            restarted.selectAccount(ACCOUNT)
            restarted.loadLatest(CONTACT)
            assertFalse(restarted.persistOfflineMessage(offline))
            while (restarted.pageState().canLoadOlder) restarted.loadOlder(CONTACT)
            assertEquals(listOf(offline) + original, restarted.messages.value.getValue(CONTACT))
            restarted.selectAccount("another-account")
            assertTrue(restarted.persistOfflineMessage(offline))
        }
    }

    @Test
    fun offlineHistoryReplacementRecoversTheBackupAfterAnInterruptedRename() {
        withHistoryDirectory("msngr-history-offline-recovery") { directory ->
            val store = FileConversationHistoryStore(directory, pageSize = 2)
            store.selectAccount(ACCOUNT)
            store.merge(mapOf(CONTACT to listOf(message(10))))
            val contactDirectory = directory.walkTopDown().first { it.isDirectory && it.listFiles().orEmpty().any { child -> child.name.startsWith("chunk-") } }
            assertTrue(contactDirectory.renameTo(File(contactDirectory.parentFile, "${contactDirectory.name}.offline-backup")))
            val restarted = FileConversationHistoryStore(directory, pageSize = 2)
            restarted.selectAccount(ACCOUNT)
            assertTrue(restarted.persistOfflineMessage(message(-1).copy(timestampMillis = 1L)))
            assertEquals(listOf(-1L, 10L), restarted.loadedIds())
        }
    }

    @Test
    fun offlinePersistencePropagatesDiskFailuresInsteadOfAcknowledgingUnsavedMessages() {
        withHistoryDirectory("msngr-history-offline-failure") { directory ->
            val store = FileConversationHistoryStore(File(directory, "history"))
            store.selectAccount(ACCOUNT)
            File(directory, "history").deleteRecursively()
            File(directory, "history").writeText("not a directory")
            assertTrue(runCatching { store.persistOfflineMessage(message(-1)) }.isFailure)
            assertTrue(store.messages.value.isEmpty())
        }
    }
    @Test
    fun groupSendersSurviveRestartAndSameNamedGroupsKeepSeparateHistories() {
        withHistoryDirectory("msngr-group-history-test") { directory ->
            val first = message(1).copy(contactId = "circle-one@live.com", senderEmail = "alice@example.com", senderName = "Alice")
            val second = message(2).copy(contactId = "circle-two@live.com", senderEmail = "bob@example.com", senderName = "Bob")
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(first.contactId to listOf(first), second.contactId to listOf(second)))
            }
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT)
                loadLatest(first.contactId)
                loadLatest(second.contactId)
                assertEquals(listOf(first), messages.value[first.contactId])
                assertEquals(listOf(second), messages.value[second.contactId])
            }
        }
    }

    @Test
    fun deliveryUpdatesReplaceBubblesAcrossChunksAndSurviveRestart() {
        withHistoryDirectory("msngr-history-delivery-test") { directory ->
            val store = FileConversationHistoryStore(directory, pageSize = 2)
            store.selectAccount(ACCOUNT)
            val pending = (0L..4L).map { message(it).copy(author = MessageAuthor.Me, isSending = true) }
            store.merge(mapOf(CONTACT to pending))
            assertEquals(pending, store.messages.value.getValue(CONTACT))
            val completed = pending.map {
                it.copy(isSending = false, deliveryError = if (it.id == 0L) "Rejected" else null)
            }
            store.merge(mapOf(CONTACT to completed))
            assertEquals(completed, store.messages.value.getValue(CONTACT))

            val restored = FileConversationHistoryStore(directory, pageSize = 2)
            restored.selectAccount(ACCOUNT)
            restored.loadLatest(CONTACT)
            restored.loadOlder(CONTACT)
            restored.loadOlder(CONTACT)
            assertEquals(completed, restored.messages.value.getValue(CONTACT))
            assertEquals(3, directory.historyChunkCount())
        }
    }

    @Test
    fun interruptedSendRestoresWithUnconfirmedDeliveryError() {
        withHistoryDirectory("msngr-history-interrupted-send-test") { directory ->
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to listOf(message(0).copy(isSending = true))))
            }
            FileConversationHistoryStore(directory).apply {
                selectAccount(ACCOUNT)
                loadLatest(CONTACT)
                val restored = messages.value.getValue(CONTACT).single()
                assertFalse(restored.isSending)
                assertTrue(restored.deliveryError.orEmpty().contains("could not be confirmed"))
            }
        }
    }

    @Test
    fun inMemoryHistoryUpdatesDeliveryWithoutDuplicatingMessages() {
        val store = InMemoryConversationHistoryStore()
        store.selectAccount(ACCOUNT)
        val pending = message(0).copy(isSending = true)
        store.merge(mapOf(CONTACT to listOf(pending)))
        val sent = pending.copy(isSending = false)
        store.merge(mapOf(CONTACT to listOf(sent)))
        assertEquals(listOf(sent), store.messages.value.getValue(CONTACT))
    }

    @Test
    fun fileStoreLoadsExactlyOneSplitFileAtATime() {
        withHistoryDirectory("msngr-history-paging-test") { directory ->
            val messages = (0L..7L).map(::message)
            FileConversationHistoryStore(directory, pageSize = 3).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to messages))
            }

            assertEquals(3, directory.historyChunkCount())
            val restored = FileConversationHistoryStore(directory, pageSize = 3)
            restored.selectAccount(ACCOUNT.uppercase())

            assertTrue(restored.messages.value.isEmpty())
            assertTrue(restored.pageStates.value.isEmpty())

            restored.loadLatest(CONTACT)
            assertEquals(listOf(6L, 7L), restored.loadedIds())
            assertTrue(restored.pageState().canLoadOlder)

            restored.loadOlder(CONTACT)
            assertEquals(listOf(3L, 4L, 5L, 6L, 7L), restored.loadedIds())
            assertTrue(restored.pageState().canLoadOlder)

            restored.loadOlder(CONTACT)
            assertEquals((0L..7L).toList(), restored.loadedIds())
            assertFalse(restored.pageState().canLoadOlder)
        }
    }

    @Test
    fun appendingAfterRestartReadsAndRewritesOnlyTheLatestSplit() {
        withHistoryDirectory("msngr-history-append-test") { directory ->
            FileConversationHistoryStore(directory, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to (0L..4L).map(::message)))
            }

            FileConversationHistoryStore(directory, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to listOf(message(0), message(5))))
                assertEquals(listOf(4L, 5L), loadedIds())
                assertTrue(pageState().canLoadOlder)
            }

            FileConversationHistoryStore(directory, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                loadLatest(CONTACT)
                assertEquals(listOf(4L, 5L), loadedIds())
            }
            assertEquals(3, directory.historyChunkCount())
        }
    }

    @Test
    fun splitHistoryKeepsAccountsSeparate() {
        withHistoryDirectory("msngr-history-accounts-test") { directory ->
            FileConversationHistoryStore(directory, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                merge(mapOf(CONTACT to listOf(message(1))))
                selectAccount("second@example.com")
                merge(mapOf(CONTACT to listOf(message(2))))
            }

            FileConversationHistoryStore(directory, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                loadLatest(CONTACT)
                assertEquals(listOf(1L), loadedIds())
                selectAccount("SECOND@example.com")
                loadLatest(CONTACT)
                assertEquals(listOf(2L), loadedIds())
            }
        }
    }

    @Test
    fun legacyFileIsStreamedIntoSplitFilesWithoutEagerlyPublishingMessages() {
        withHistoryDirectory("msngr-history-migration-test") { directory ->
            val legacyFile = File(directory.parentFile, "conversation_history.bin")
            writeLegacyHistory(legacyFile, (0L..4L).map(::message))

            val store = FileConversationHistoryStore(
                directory = directory,
                legacyFile = legacyFile,
                pageSize = 2,
            )
            store.selectAccount(ACCOUNT.uppercase())

            assertFalse(legacyFile.exists())
            assertEquals(3, directory.historyChunkCount())
            assertTrue(store.messages.value.isEmpty())
            store.loadLatest(CONTACT)
            assertEquals(listOf(4L), store.loadedIds())
            store.loadOlder(CONTACT)
            assertEquals(listOf(2L, 3L, 4L), store.loadedIds())
            store.loadOlder(CONTACT)
            assertEquals((0L..4L).map(::message), store.messages.value.getValue(CONTACT))
        }
    }

    @Test
    fun clearAllRemovesEveryAccountsSplitHistory() {
        withHistoryDirectory("msngr-history-clear-test") { directory ->
            val store = FileConversationHistoryStore(directory, pageSize = 2)
            listOf(ACCOUNT, "second@example.com").forEachIndexed { index, account ->
                store.selectAccount(account)
                store.merge(mapOf(CONTACT to listOf(message(index.toLong()))))
            }

            store.clearAll()

            assertEquals(0, directory.historyChunkCount())
            val restored = FileConversationHistoryStore(directory, pageSize = 2)
            listOf(ACCOUNT, "second@example.com").forEach { account ->
                restored.selectAccount(account)
                restored.loadLatest(CONTACT)
                assertTrue(restored.messages.value.getValue(CONTACT).isEmpty())
            }
        }
    }

    @Test
    fun clearContactRemovesUnloadedChunksAndPreservesOtherContactsAndAccounts() {
        withHistoryDirectory("msngr-history-clear-contact-test") { directory ->
            val other = "other@example.com"
            val otherMessage = message(20).copy(contactId = other)
            val store = FileConversationHistoryStore(directory, pageSize = 2)
            store.selectAccount(ACCOUNT)
            store.merge(mapOf(CONTACT to (0L..6L).map(::message), other to listOf(otherMessage)))
            store.selectAccount("second@example.com")
            store.merge(mapOf(CONTACT to listOf(message(30))))
            store.selectAccount(ACCOUNT)
            store.loadLatest(CONTACT)
            assertTrue(store.pageState().canLoadOlder)

            store.clearContact(CONTACT)
            store.loadOlder(CONTACT)
            assertTrue(store.messages.value[CONTACT].isNullOrEmpty())
            assertTrue(store.pageState().isInitialPageLoaded)
            assertFalse(store.pageState().canLoadOlder)
            assertEquals(2, directory.historyChunkCount())

            val restored = FileConversationHistoryStore(directory, pageSize = 2)
            restored.selectAccount(ACCOUNT)
            restored.loadLatest(CONTACT)
            assertTrue(restored.loadedIds().isEmpty())
            restored.loadLatest(other)
            assertEquals(listOf(otherMessage), restored.messages.value[other])
            restored.selectAccount("second@example.com")
            restored.loadLatest(CONTACT)
            assertEquals(listOf(30L), restored.loadedIds())

            store.merge(mapOf(CONTACT to listOf(message(40))))
            store.loadOlder(CONTACT)
            assertEquals(listOf(40L), store.loadedIds())
            assertFalse(store.pageState().canLoadOlder)
        }
    }

    @Test
    fun clearContactBeforeLoadingAlsoWorksAfterLegacyMigration() {
        withHistoryDirectory("msngr-history-clear-migrated-test") { directory ->
            val legacy = File(directory.parentFile, "conversation_history.bin")
            writeLegacyHistory(legacy, (0L..6L).map(::message))
            val store = FileConversationHistoryStore(directory, legacy, pageSize = 2)
            store.selectAccount(ACCOUNT)
            store.clearContact(CONTACT)
            store.clearContact(CONTACT)
            assertEquals(0, directory.historyChunkCount())
            FileConversationHistoryStore(directory, legacy, pageSize = 2).apply {
                selectAccount(ACCOUNT)
                loadLatest(CONTACT)
                assertTrue(loadedIds().isEmpty())
            }
        }
    }

    @Test
    fun inMemoryClearContactPreservesOtherContactsAndAccounts() {
        val store = InMemoryConversationHistoryStore()
        val other = "other@example.com"
        val otherMessage = message(2).copy(contactId = other)
        store.selectAccount("second@example.com")
        store.merge(mapOf(CONTACT to listOf(message(3))))
        store.selectAccount(ACCOUNT)
        store.merge(mapOf(CONTACT to listOf(message(1)), other to listOf(otherMessage)))
        store.clearContact(CONTACT)
        assertTrue(store.messages.value[CONTACT].isNullOrEmpty())
        assertEquals(listOf(otherMessage), store.messages.value[other])
        assertEquals(
            ConversationHistoryPageState(isInitialPageLoaded = true),
            store.pageStates.value[CONTACT],
        )
        store.selectAccount("second@example.com")
        assertEquals(listOf(message(3)), store.messages.value[CONTACT])
        store.selectAccount(ACCOUNT)
        assertTrue(store.messages.value[CONTACT].isNullOrEmpty())
    }

    private fun message(id: Long) = ChatMessage(
        id = id,
        contactId = CONTACT,
        author = if (id % 2L == 0L) MessageAuthor.Me else MessageAuthor.Contact,
        text = "Message $id",
        timestampMillis = id * 1_000L,
        renderedEmoticonRanges = if (id == 0L) listOf(MsnEmoticonRange(0, 1)) else null,
    )

    private fun FileConversationHistoryStore.loadedIds(): List<Long> =
        messages.value.getValue(CONTACT).map(ChatMessage::id)

    private fun FileConversationHistoryStore.pageState(): ConversationHistoryPageState =
        pageStates.value.getValue(CONTACT)

    private fun File.historyChunkCount(): Int = walkTopDown().count { file ->
        file.isFile && file.name.matches(Regex("chunk-\\d{8}\\.bin"))
    }

    private fun withHistoryDirectory(name: String, block: (File) -> Unit) {
        val parent = Files.createTempDirectory(name).toFile()
        try {
            block(parent.resolve("conversation_history"))
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun writeLegacyHistory(file: File, messages: List<ChatMessage>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(0x4D534748)
            output.writeInt(1)
            output.writeInt(1)
            output.writeSizedString(ACCOUNT)
            output.writeInt(1)
            output.writeSizedString(CONTACT)
            output.writeInt(messages.size)
            messages.forEach { message ->
                output.writeLong(message.id)
                output.writeSizedString(message.author.name)
                output.writeSizedString(message.text)
                output.writeLong(message.timestampMillis)
                output.writeBoolean(message.isNudge)
                output.writeInt(message.renderedEmoticonRanges?.size ?: -1)
                message.renderedEmoticonRanges?.forEach { range ->
                    output.writeInt(range.start)
                    output.writeInt(range.endExclusive)
                }
            }
        }
    }

    private fun DataOutputStream.writeSizedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        const val ACCOUNT = "owner@example.com"
        const val CONTACT = "friend@example.com"
    }
}
