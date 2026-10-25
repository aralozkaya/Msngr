package org.aralozkaya.msngr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aralozkaya.msngr.MsnEmoticonRange
import org.aralozkaya.msngr.NativeTextStyle
import org.aralozkaya.msngr.protocol.MsnInkCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class ConversationHistoryPageState(
    val isInitialPageLoaded: Boolean = false,
    val canLoadOlder: Boolean = false,
)

interface ConversationHistoryStore {
    /** Only the pages loaded for the selected account are retained in memory. */
    val messages: StateFlow<Map<String, List<ChatMessage>>>
    val pageStates: StateFlow<Map<String, ConversationHistoryPageState>>

    fun selectAccount(accountId: String)
    fun loadLatest(contactId: String)
    fun loadOlder(contactId: String)
    fun merge(messages: Map<String, List<ChatMessage>>)
    /** Returns true only for a new OIM; throws unless the message was durably saved. */
    fun persistOfflineMessage(message: ChatMessage): Boolean =
        error("This history store cannot persist offline messages.")
    fun clearContact(contactId: String)
    fun clearAll()
}

internal class InMemoryConversationHistoryStore : ConversationHistoryStore {
    private val lock = Any()
    private val accounts = mutableMapOf<String, Map<String, List<ChatMessage>>>()
    private val mutableMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val mutablePageStates =
        MutableStateFlow<Map<String, ConversationHistoryPageState>>(emptyMap())
    private var selectedAccount: String? = null

    override val messages = mutableMessages.asStateFlow()
    override val pageStates = mutablePageStates.asStateFlow()

    override fun selectAccount(accountId: String) = synchronized(lock) {
        val normalizedAccount = accountId.normalizedAccountId()
        if (selectedAccount == normalizedAccount) return
        selectedAccount = normalizedAccount
        mutableMessages.value = accounts[selectedAccount].orEmpty()
        mutablePageStates.value = mutableMessages.value.keys.associateWith {
            ConversationHistoryPageState(isInitialPageLoaded = true)
        }
    }

    override fun loadLatest(contactId: String) = synchronized(lock) {
        mutablePageStates.value = mutablePageStates.value +
            (contactId to ConversationHistoryPageState(isInitialPageLoaded = true))
    }

    override fun loadOlder(contactId: String) = Unit

    override fun merge(messages: Map<String, List<ChatMessage>>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            val account = selectedAccount ?: return
            val merged = mergeConversations(accounts[account].orEmpty(), messages)
            accounts[account] = merged
            mutableMessages.value = merged
            mutablePageStates.value = mutablePageStates.value + messages.keys.associateWith {
                ConversationHistoryPageState(isInitialPageLoaded = true)
            }
        }
    }

    override fun clearContact(contactId: String) = synchronized(lock) {
        val account = selectedAccount ?: return
        accounts[account] = accounts[account].orEmpty() - contactId
        mutableMessages.value = mutableMessages.value - contactId
        mutablePageStates.value = mutablePageStates.value +
            (contactId to ConversationHistoryPageState(isInitialPageLoaded = true))
    }

    override fun persistOfflineMessage(message: ChatMessage): Boolean = synchronized(lock) {
        checkNotNull(selectedAccount)
        if (accounts[selectedAccount].orEmpty()[message.contactId].orEmpty().any { it.id == message.id }) false
        else {
            merge(mapOf(message.contactId to listOf(message)))
            true
        }
    }

    override fun clearAll() = synchronized(lock) {
        val openedContacts = mutablePageStates.value.keys
        accounts.clear()
        mutableMessages.value = emptyMap()
        mutablePageStates.value = openedContacts.associateWith {
            ConversationHistoryPageState(isInitialPageLoaded = true)
        }
    }
}

/**
 * Stores each account/contact history as fixed-size chronological chunk files.
 * No disk work happens in the constructor, and selecting an account reads no message page.
 */
internal class FileConversationHistoryStore(
    private val directory: File,
    private val legacyFile: File? = null,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : ConversationHistoryStore {
    private data class LoadedConversation(
        val messages: List<ChatMessage>,
        val oldestChunkIndex: Int,
        val newestChunkIndex: Int,
    )

    private val lock = Any()
    private val loaded = mutableMapOf<String, LoadedConversation>()
    private val mutableMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val mutablePageStates =
        MutableStateFlow<Map<String, ConversationHistoryPageState>>(emptyMap())
    private var selectedAccount: String? = null
    private var storagePrepared = false

    init {
        require(pageSize > 0)
    }

    override val messages: StateFlow<Map<String, List<ChatMessage>>> =
        mutableMessages.asStateFlow()
    override val pageStates: StateFlow<Map<String, ConversationHistoryPageState>> =
        mutablePageStates.asStateFlow()

    override fun selectAccount(accountId: String) = synchronized(lock) {
        prepareStorage()
        val normalizedAccount = accountId.normalizedAccountId()
        if (selectedAccount == normalizedAccount) return
        selectedAccount = normalizedAccount
        loaded.clear()
        mutableMessages.value = emptyMap()
        mutablePageStates.value = emptyMap()
    }

    override fun loadLatest(contactId: String) = synchronized(lock) {
        if (selectedAccount == null) return
        val conversation = loaded[contactId] ?: readLatestConversation(contactId)
        loaded[contactId] = conversation
        publish(contactId, conversation, isInitialPageLoaded = true)
    }

    override fun loadOlder(contactId: String) = synchronized(lock) {
        val current = loaded[contactId] ?: return
        val olderIndex = chunkIndices(contactId)
            .lastOrNull { it < current.oldestChunkIndex }
            ?: run {
                publish(contactId, current, isInitialPageLoaded = true)
                return
            }
        val olderMessages = readChunk(contactId, olderIndex) ?: emptyList()
        val updated = current.copy(
            messages = mergeMessageLists(olderMessages, current.messages),
            oldestChunkIndex = olderIndex,
        )
        loaded[contactId] = updated
        publish(contactId, updated, isInitialPageLoaded = true)
    }

    override fun merge(messages: Map<String, List<ChatMessage>>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            if (selectedAccount == null) return
            messages.forEach { (contactId, incoming) ->
                if (incoming.isEmpty()) return@forEach
                val current = loaded[contactId] ?: readLatestConversation(contactId)
                val currentById = current.messages.associateBy(ChatMessage::id)
                val changes = incoming.filter { message ->
                    currentById[message.id]?.let { it != message } == true
                }.associateBy(ChatMessage::id)
                val knownIds = currentById.keys.toMutableSet()
                val lastPersisted = current.messages.lastOrNull()
                val additions = incoming
                    .filter { message ->
                        knownIds.add(message.id) &&
                            (lastPersisted == null ||
                                CHAT_MESSAGE_COMPARATOR.compare(message, lastPersisted) > 0)
                    }
                    .sortedWith(CHAT_MESSAGE_COMPARATOR)
                if (additions.isEmpty() && changes.isEmpty()) return@forEach

                val newestChunkIndex = runCatching {
                    if (changes.isNotEmpty()) {
                        // Only rewrite chunks containing a changed delivery status.
                        for (index in current.oldestChunkIndex..current.newestChunkIndex) {
                            val chunk = readChunk(contactId, index) ?: continue
                            if (chunk.any { it.id in changes }) {
                                writeChunk(contactId, index, chunk.map { changes[it.id] ?: it })
                            }
                        }
                    }
                    appendToLatestChunk(contactId, current.newestChunkIndex, additions)
                }.getOrDefault(current.newestChunkIndex)
                val updated = current.copy(
                    messages = mergeMessageLists(current.messages, changes.values.toList() + additions),
                    newestChunkIndex = newestChunkIndex,
                )
                loaded[contactId] = updated
                publish(contactId, updated, isInitialPageLoaded = true)
            }
        }
    }

    override fun persistOfflineMessage(message: ChatMessage): Boolean = synchronized(lock) {
        checkNotNull(selectedAccount)
        val contactId = message.contactId
        val destination = contactDirectory(contactId)
        val previous = chunkIndices(contactId).flatMap { index ->
            checkNotNull(readChunk(contactId, index)) { "Conversation history could not be read." }
        }
        if (previous.any { it.id == message.id }) return@synchronized false
        val combined = mergeMessageLists(previous, loaded[contactId]?.messages.orEmpty() + message)
        // An offline message can predate every loaded page. Replace the complete conversation
        // atomically so a crash while shifting pages cannot drop existing or incoming messages.
        val staging = File(destination.parentFile, "${destination.name}.offline-pending")
        val backup = File(destination.parentFile, "${destination.name}.offline-backup")
        check(!staging.exists() || staging.deleteRecursively()) { "Could not prepare offline message storage." }
        check(staging.mkdirs()) { "Could not create offline message storage." }
        try {
            combined.chunked(pageSize).forEachIndexed { index, messages ->
                writeHistoryChunk(File(staging, "chunk-${index.toString().padStart(8, '0')}.bin"), contactId, messages)
            }
            check(!backup.exists() || backup.deleteRecursively()) { "Could not prepare the history backup." }
            if (destination.exists()) check(destination.renameTo(backup)) { "Could not back up conversation history." }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) check(backup.renameTo(destination)) { "Could not restore conversation history." }
                error("Could not save the offline message.")
            }
            backup.deleteRecursively()
        } finally { staging.deleteRecursively() }
        val updated = LoadedConversation(combined, 0, (combined.size - 1) / pageSize)
        loaded[contactId] = updated
        publish(contactId, updated, isInitialPageLoaded = true)
        true
    }

    override fun clearContact(contactId: String) = synchronized(lock) {
        if (selectedAccount == null) return
        val contactDirectory = contactDirectory(contactId)
        check(!contactDirectory.exists() || contactDirectory.deleteRecursively()) {
            "Could not erase this contact's saved history. Please try again."
        }
        loaded.remove(contactId)
        mutableMessages.value = mutableMessages.value - contactId
        mutablePageStates.value = mutablePageStates.value +
            (contactId to ConversationHistoryPageState(isInitialPageLoaded = true))
    }

    override fun clearAll() = synchronized(lock) {
        val openedContacts = mutablePageStates.value.keys
        loaded.clear()
        mutableMessages.value = emptyMap()
        mutablePageStates.value = openedContacts.associateWith {
            ConversationHistoryPageState(isInitialPageLoaded = true)
        }
        runCatching { directory.deleteRecursively() }
        legacyFile?.let { file ->
            runCatching { file.delete() }
            runCatching { file.backupFile().delete() }
            runCatching { file.temporaryFile().delete() }
        }
        storagePrepared = false
        prepareStorage()
    }

    private fun readLatestConversation(contactId: String): LoadedConversation {
        val indices = chunkIndices(contactId)
        val latestPair = indices.asReversed()
            .firstNotNullOfOrNull { index -> readChunk(contactId, index)?.let { index to it } }
        val latestIndex = latestPair?.first ?: 0
        return LoadedConversation(
            messages = latestPair?.second.orEmpty(),
            oldestChunkIndex = latestIndex,
            newestChunkIndex = latestIndex,
        )
    }

    private fun appendToLatestChunk(
        contactId: String,
        currentLatestIndex: Int,
        additions: List<ChatMessage>,
    ): Int {
        if (additions.isEmpty()) return currentLatestIndex
        val indices = chunkIndices(contactId)
        var chunkIndex = indices.lastOrNull() ?: currentLatestIndex
        val existing = if (indices.isEmpty()) emptyList() else readChunk(contactId, chunkIndex).orEmpty()
        val combined = mergeMessageLists(existing, additions)
        combined.chunked(pageSize).forEachIndexed { offset, chunk ->
            writeChunk(contactId, chunkIndex + offset, chunk)
        }
        chunkIndex += (combined.size - 1).coerceAtLeast(0) / pageSize
        return chunkIndex
    }

    private fun publish(
        contactId: String,
        conversation: LoadedConversation,
        isInitialPageLoaded: Boolean,
    ) {
        mutableMessages.value = mutableMessages.value + (contactId to conversation.messages)
        val oldestAvailable = chunkIndices(contactId).firstOrNull()
        mutablePageStates.value = mutablePageStates.value +
            (contactId to ConversationHistoryPageState(
                isInitialPageLoaded = isInitialPageLoaded,
                canLoadOlder = oldestAvailable != null &&
                    oldestAvailable < conversation.oldestChunkIndex,
            ))
    }

    private fun chunkIndices(contactId: String): List<Int> =
        contactDirectory(contactId)
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                CHUNK_FILE_REGEX.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }
            .sorted()

    private fun readChunk(contactId: String, index: Int): List<ChatMessage>? {
        val file = chunkFile(contactId, index)
        return sequenceOf(file, file.backupFile())
            .filter(File::isFile)
            .mapNotNull { candidate ->
                runCatching { readChunkFile(candidate, contactId) }.getOrNull()
            }
            .firstOrNull()
    }

    private fun writeChunk(contactId: String, index: Int, messages: List<ChatMessage>) {
        val file = chunkFile(contactId, index)
        file.parentFile?.mkdirs()
        writeHistoryChunk(file, contactId, messages)
    }

    private fun chunkFile(contactId: String, index: Int): File =
        File(contactDirectory(contactId), "chunk-${index.toString().padStart(8, '0')}.bin")

    private fun contactDirectory(contactId: String): File {
        val account = checkNotNull(selectedAccount)
        val result = File(File(directory, account.sha256Hex()), contactId.sha256Hex())
        val backup = File(result.parentFile, "${result.name}.offline-backup")
        if (!result.exists() && backup.exists()) {
            check(backup.renameTo(result)) { "Could not restore interrupted offline message history." }
        }
        return result
    }

    private fun prepareStorage() {
        if (storagePrepared) return
        if (directory.isDirectory) {
            storagePrepared = true
            return
        }
        val legacyCandidates = legacyFile?.let { listOf(it, it.backupFile()) }.orEmpty()
            .filter(File::isFile)
        val migrated = legacyCandidates.any { candidate -> migrateLegacy(candidate) }
        if (!migrated) directory.mkdirs()
        if (directory.isDirectory) {
            legacyFile?.delete()
            legacyFile?.backupFile()?.delete()
            legacyFile?.temporaryFile()?.delete()
        }
        storagePrepared = directory.isDirectory
    }

    private fun migrateLegacy(source: File): Boolean {
        val staging = File(directory.parentFile, "${directory.name}.migration")
        staging.deleteRecursively()
        staging.mkdirs()
        val succeeded = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
                require(input.readInt() == LEGACY_FILE_MAGIC) {
                    "Not a Msngr conversation history file."
                }
                require(input.readInt() == LEGACY_FILE_VERSION) {
                    "Unsupported conversation history version."
                }
                repeat(input.readBoundedCount()) {
                    val accountId = input.readSizedString().normalizedAccountId()
                    repeat(input.readBoundedCount()) {
                        val contactId = input.readSizedString()
                        val messageCount = input.readBoundedCount()
                        val page = ArrayList<ChatMessage>(minOf(pageSize, messageCount))
                        var chunkIndex = 0
                        repeat(messageCount) {
                            page += input.readChatMessage(contactId)
                            if (page.size == pageSize) {
                                writeMigrationChunk(
                                    staging,
                                    accountId,
                                    contactId,
                                    chunkIndex++,
                                    page,
                                )
                                page.clear()
                            }
                        }
                        if (page.isNotEmpty()) {
                            writeMigrationChunk(staging, accountId, contactId, chunkIndex, page)
                        }
                    }
                }
            }
            directory.parentFile?.mkdirs()
            check(staging.renameTo(directory)) { "Could not install migrated history." }
        }.isSuccess
        if (!succeeded) staging.deleteRecursively()
        return succeeded
    }

    private fun writeMigrationChunk(
        root: File,
        accountId: String,
        contactId: String,
        index: Int,
        messages: List<ChatMessage>,
    ) {
        val file = File(
            File(File(root, accountId.sha256Hex()), contactId.sha256Hex()),
            "chunk-${index.toString().padStart(8, '0')}.bin",
        )
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(CHUNK_MAGIC)
            output.writeInt(CHUNK_VERSION)
            output.writeSizedString(contactId)
            output.writeInt(messages.size)
            messages.forEach(output::writeChatMessage)
        }
    }
}

private fun mergeConversations(
    current: Map<String, List<ChatMessage>>,
    incoming: Map<String, List<ChatMessage>>,
): Map<String, List<ChatMessage>> {
    val merged = current.toMutableMap()
    incoming.forEach { (contactId, newMessages) ->
        if (newMessages.isEmpty()) return@forEach
        val combined = mergeMessageLists(merged[contactId].orEmpty(), newMessages)
        if (combined != merged[contactId]) merged[contactId] = combined
    }
    return merged.toMap()
}

private fun mergeMessageLists(
    current: List<ChatMessage>,
    incoming: List<ChatMessage>,
): List<ChatMessage> {
    if (incoming.isEmpty()) return current
    val messages = current.associateByTo(linkedMapOf(), ChatMessage::id)
    incoming.forEach { messages[it.id] = it }
    return messages.values.sortedWith(CHAT_MESSAGE_COMPARATOR)
}

private fun readChunkFile(file: File, expectedContactId: String): List<ChatMessage> =
    DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
        require(input.readInt() == CHUNK_MAGIC) { "Not a Msngr history chunk." }
        val version = input.readInt()
        require(version in 1..CHUNK_VERSION) { "Unsupported history chunk version." }
        val contactId = input.readSizedString()
        require(contactId == expectedContactId) { "History chunk belongs to another contact." }
        List(input.readBoundedCount()) { input.readChatMessage(contactId, version) }
    }

private fun DataInputStream.readChatMessage(contactId: String, version: Int = 1): ChatMessage {
    val id = readLong()
    val author = MessageAuthor.valueOf(readSizedString())
    val text = readSizedString()
    val timestampMillis = readLong()
    val isNudge = readBoolean()
    val rangeCount = readInt()
    require(rangeCount in -1..MAX_ENTRY_COUNT)
    val ranges = if (rangeCount < 0) {
        null
    } else {
        List(rangeCount) { MsnEmoticonRange(readInt(), readInt()) }
    }
    return ChatMessage(
        id = id,
        contactId = contactId,
        author = author,
        text = text,
        timestampMillis = timestampMillis,
        isNudge = isNudge,
        renderedEmoticonRanges = ranges,
        deliveryError = if (version >= 2 && readBoolean()) readSizedString() else null,
        senderEmail = if (version >= 3 && readBoolean()) readSizedString() else null,
        senderName = if (version >= 3 && readBoolean()) readSizedString() else null,
        nativeStyle = if (version >= 4) NativeTextStyle.fromFormatHeader(readSizedString())
            .copy(size = readInt()) else NativeTextStyle(),
        inkDrawing = if (version >= 5 && readBoolean()) {
            val byteCount = readInt()
            require(byteCount in 1..MAX_STRING_BYTES)
            val isfBytes = ByteArray(byteCount).also(::readFully)
            MsnInkCodec.decodeIsf(isfBytes)
        } else null,
    )
}

private fun DataOutputStream.writeChatMessage(message: ChatMessage) {
    writeLong(message.id)
    writeSizedString(message.author.name)
    writeSizedString(message.text)
    writeLong(message.timestampMillis)
    writeBoolean(message.isNudge)
    val ranges = message.renderedEmoticonRanges
    writeInt(ranges?.size ?: -1)
    ranges?.forEach { range ->
        writeInt(range.start)
        writeInt(range.endExclusive)
    }
    // An interrupted app session must not restore a permanently spinning clock.
    val error = if (message.isSending) {
        "Sending was interrupted. Delivery could not be confirmed."
    } else message.deliveryError
    writeBoolean(error != null)
    error?.let(::writeSizedString)
    writeBoolean(message.senderEmail != null)
    message.senderEmail?.let(::writeSizedString)
    writeBoolean(message.senderName != null)
    message.senderName?.let(::writeSizedString)
    writeSizedString(message.nativeStyle.toFormatHeader())
    writeInt(message.nativeStyle.size)
    val isfBytes = message.inkDrawing?.let { drawing ->
        runCatching { MsnInkCodec.encodeIsf(drawing) }.getOrNull()
    }
    writeBoolean(isfBytes != null)
    if (isfBytes != null) {
        require(isfBytes.size <= MAX_STRING_BYTES)
        writeInt(isfBytes.size)
        write(isfBytes)
    }
}

private fun writeHistoryChunk(file: File, contactId: String, messages: List<ChatMessage>) =
    writeAtomically(file) { output ->
        output.writeInt(CHUNK_MAGIC)
        output.writeInt(CHUNK_VERSION)
        output.writeSizedString(contactId)
        output.writeInt(messages.size)
        messages.forEach(output::writeChatMessage)
    }

private fun writeAtomically(file: File, write: (DataOutputStream) -> Unit) {
    val temporary = file.temporaryFile()
    val backup = file.backupFile()
    temporary.delete()
    FileOutputStream(temporary).use { fileOutput ->
        val output = DataOutputStream(BufferedOutputStream(fileOutput))
        write(output)
        output.flush()
        fileOutput.fd.sync()
    }
    backup.delete()
    if (file.exists() && !file.renameTo(backup)) {
        temporary.delete()
        error("Could not prepare the existing history chunk for replacement.")
    }
    if (!temporary.renameTo(file)) {
        backup.renameTo(file)
        error("Could not replace the history chunk.")
    }
    backup.delete()
}

private fun DataInputStream.readBoundedCount(): Int =
    readInt().also { require(it in 0..MAX_ENTRY_COUNT) }

private fun DataInputStream.readSizedString(): String {
    val byteCount = readInt()
    require(byteCount in 0..MAX_STRING_BYTES)
    return ByteArray(byteCount).also(::readFully).toString(Charsets.UTF_8)
}

private fun DataOutputStream.writeSizedString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_STRING_BYTES)
    writeInt(bytes.size)
    write(bytes)
}

private fun String.normalizedAccountId(): String = trim().lowercase(Locale.ROOT)

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun File.temporaryFile() = File(parentFile, "$name.tmp")
private fun File.backupFile() = File(parentFile, "$name.bak")

private val CHAT_MESSAGE_COMPARATOR = compareBy(ChatMessage::timestampMillis, ChatMessage::id)
private val CHUNK_FILE_REGEX = Regex("chunk-(\\d{8})\\.bin")
private const val DEFAULT_PAGE_SIZE = 200
private const val CHUNK_MAGIC = 0x4D534743
private const val CHUNK_VERSION = 5
private const val LEGACY_FILE_MAGIC = 0x4D534748
private const val LEGACY_FILE_VERSION = 1
private const val MAX_ENTRY_COUNT = 1_000_000
private const val MAX_STRING_BYTES = 16 * 1024 * 1024
