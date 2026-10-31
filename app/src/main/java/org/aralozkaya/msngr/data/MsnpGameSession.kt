package org.aralozkaya.msngr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnGameInviteContext
import org.aralozkaya.msngr.protocol.MsnGameProtocol
import org.aralozkaya.msngr.protocol.P4Packet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class GameSessionStatus {
    Invited,
    Active,
    Declined,
    Stopped,
    Failed,
}

data class GameDataPacket(
    val sequence: Long,
    val payload: String,
)

data class GameSession(
    val id: String,
    val appId: Int,
    val appName: String,
    val entry: GameCatalogEntry,
    val outgoing: Boolean,
    val status: GameSessionStatus = GameSessionStatus.Invited,
    val localChannelInitialized: Boolean = false,
    val remoteAppLoaded: Boolean = false,
    val remoteAppClosed: Boolean = false,
    val receivedPackets: List<GameDataPacket> = emptyList(),
    val error: String? = null,
    val remoteStopped: Boolean = false,
)

/**
 * WLM 2009 Games & Activities (P4): MSNSLP EUF `{6A13AF9C-5308-4F35-923A-67E8DDA40C2F}`
 * plus binary `CP4Channel` control (`00 00 01 00`) and `CComVariant` (`80 00 00 00`) data packets.
 */
internal class MsnpGameSession(
    private val source: String,
    private val destination: String,
    private val publish: (GameSession) -> Unit,
    private val sendControl: suspend (ByteArray) -> Unit,
    private val sendData: suspend (Long, Int, ByteArray) -> Unit,
    private val resolveCatalogEntry: suspend (Int, String) -> GameCatalogEntry? = { appId, fallbackName ->
        MsnpGameCatalogService.resolveEntry(appId, fallbackName)
    },
) {
    private val ids = AtomicInteger(700_000)
    private val packetSequence = AtomicLong(0L)
    private var session = 0L
    private var invite: SlpMessage? = null
    @Volatile
    private var currentGame: GameSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var invitationTimeout: Job? = null

    fun owns(id: String): Boolean = currentGame?.id?.equals(id, ignoreCase = true) == true
    fun owns(sessionId: Long): Boolean = session != 0L && sessionId == session

    suspend fun start(entry: GameCatalogEntry): String {
        check(currentGame?.status != GameSessionStatus.Invited) {
            "Wait for the current game invitation to be answered."
        }
        if (currentGame?.status == GameSessionStatus.Active) {
            runCatching { stop() }
        }
        invitationTimeout?.cancel()
        packetSequence.set(0L)

        val id = MsnpP2pUtil.randomGuid()
        if (entry.minUsers == 1 && entry.maxUsers == 1) {
            session = 0L
            invite = null
            update(
                GameSession(
                    id = id,
                    appId = entry.appId,
                    appName = entry.name,
                    entry = entry,
                    outgoing = true,
                    status = GameSessionStatus.Active,
                    remoteAppLoaded = true,
                ),
            )
            return id
        }

        session = ids.incrementAndGet().toLong()
        val appId = MsnGameProtocol.appIdForEntry(entry.appId)
        val contextBytes = entry.toInviteContext().encode()
        val bytes = sipMessage(
            firstLine = "INVITE MSNMSGR:$destination MSNSLP/1.0",
            to = destination,
            from = source,
            branch = MsnpP2pUtil.randomGuid(),
            callId = id,
            cseq = 0,
            contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
            content = "EUF-GUID: {${MsnGameProtocol.EUF_GUID}}\r\n" +
                "SessionID: $session\r\n" +
                "AppID: $appId\r\n" +
                "RequestFlags: 16\r\n" +
                "Context: ${MsnBase64.encode(contextBytes)}\r\n\r\n",
        )
        invite = requireNotNull(SlpMessage.parse(bytes))
        update(
            GameSession(
                id = id,
                appId = entry.appId,
                appName = entry.name,
                entry = entry,
                outgoing = true,
                status = GameSessionStatus.Invited,
            ),
        )
        startInvitationTimeout(id)
        try {
            sendControl(bytes)
        } catch (error: Exception) {
            update(
                requireNotNull(currentGame).copy(
                    status = GameSessionStatus.Failed,
                    error = "Game invitation could not be sent.",
                ),
            )
            throw error
        }
        return id
    }

    suspend fun handle(message: SlpMessage): Boolean {
        val incomingInvite = message.firstLine.startsWith("INVITE ", ignoreCase = true) &&
            message.contentType.equals(MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, ignoreCase = true) &&
            message.body.field("EUF-GUID")?.trim('{', '}')
                ?.equals(MsnGameProtocol.EUF_GUID, ignoreCase = true) == true
        if (incomingInvite) {
            if (message.callId.equals(invite?.callId, ignoreCase = true)) return true
            val incomingSessionId = message.body.field("SessionID")?.toLongOrNull()
                ?.takeIf { it in 1..0xffff_ffffL } ?: return true
            if (currentGame?.status in listOf(GameSessionStatus.Invited, GameSessionStatus.Active)) {
                respond(message, incomingSessionId, accepted = false)
                return true
            }
            val contextRaw = message.body.field("Context")?.let(MsnBase64::decode)
            val parsedContext = contextRaw?.let(MsnGameInviteContext::parse)
            if (parsedContext == null) {
                respond(message, incomingSessionId, accepted = false)
                return true
            }
            val entry = resolveCatalogEntry(parsedContext.entryId, parsedContext.appName)
            if (entry == null || entry.url.isBlank() || entry.error.isNotBlank()) {
                respond(message, incomingSessionId, accepted = false)
                return true
            }
            invitationTimeout?.cancel()
            packetSequence.set(0L)
            invite = message
            session = incomingSessionId
            update(
                GameSession(
                    id = message.callId,
                    appId = entry.appId,
                    appName = parsedContext.appName.ifBlank { entry.name },
                    entry = entry,
                    outgoing = false,
                    status = GameSessionStatus.Invited,
                ),
            )
            startInvitationTimeout(message.callId)
            return true
        }

        val current = currentGame ?: return false
        if (!current.id.equals(message.callId, ignoreCase = true)) return false
        when {
            message.firstLine.startsWith("BYE ", ignoreCase = true) -> {
                invitationTimeout?.cancel()
                change(current.id) {
                    it.copy(
                        status = GameSessionStatus.Stopped,
                        remoteStopped = true,
                        remoteAppClosed = true,
                    )
                }
            }
            message.firstLine.startsWith("MSNSLP/1.0 603", ignoreCase = true) -> {
                invitationTimeout?.cancel()
                change(current.id) {
                    it.copy(
                        status = GameSessionStatus.Declined,
                        remoteStopped = true,
                    )
                }
            }
            message.firstLine.startsWith("MSNSLP/1.0 200", ignoreCase = true) &&
                message.contentType.equals(MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE, ignoreCase = true) -> {
                if (current.outgoing && current.status == GameSessionStatus.Invited) {
                    invitationTimeout?.cancel()
                    var shouldSendLoaded = false
                    change(current.id) {
                        shouldSendLoaded = it.localChannelInitialized
                        it.copy(status = GameSessionStatus.Active)
                    }
                    if (shouldSendLoaded && session != 0L) {
                        sendData(
                            session,
                            MsnGameProtocol.appIdForEntry(current.appId),
                            MsnGameProtocol.encodeRemoteAppLoaded(),
                        )
                    }
                }
            }
            else -> return false
        }
        return true
    }

    suspend fun accept() {
        val current = requireNotNull(currentGame)
        check(!current.outgoing && current.status == GameSessionStatus.Invited)
        invitationTimeout?.cancel()
        var shouldSendLoaded = false
        change(current.id) {
            shouldSendLoaded = it.localChannelInitialized
            it.copy(status = GameSessionStatus.Active)
        }
        respond(requireNotNull(invite), session, accepted = true)
        if (shouldSendLoaded && session != 0L) {
            sendData(
                session,
                MsnGameProtocol.appIdForEntry(current.appId),
                MsnGameProtocol.encodeRemoteAppLoaded(),
            )
        }
    }

    suspend fun initializeChannel() {
        val current = currentGame ?: return
        if (current.entry.minUsers == 1 && current.entry.maxUsers == 1) {
            change(current.id) {
                it.copy(localChannelInitialized = true, remoteAppLoaded = true)
            }
            return
        }
        val wasInitialized = current.localChannelInitialized
        change(current.id) {
            it.copy(localChannelInitialized = true)
        }
        val updated = currentGame ?: return
        if (!wasInitialized && updated.status == GameSessionStatus.Active && session != 0L) {
            sendData(
                session,
                MsnGameProtocol.appIdForEntry(updated.appId),
                MsnGameProtocol.encodeRemoteAppLoaded(),
            )
        }
    }

    suspend fun sendGameData(payload: String) {
        val current = currentGame ?: return
        if (current.status != GameSessionStatus.Active || session == 0L) return
        val bytes = MsnGameProtocol.encodeData(payload)
        sendData(session, MsnGameProtocol.appIdForEntry(current.appId), bytes)
    }

    fun receive(bytes: ByteArray) {
        val current = currentGame ?: return
        if (current.status != GameSessionStatus.Active) return
        when (val packet = MsnGameProtocol.decodePacket(bytes)) {
            is P4Packet.RemoteAppLoaded -> {
                change(current.id) { it.copy(remoteAppLoaded = true) }
            }
            is P4Packet.Data -> {
                val item = GameDataPacket(
                    sequence = packetSequence.incrementAndGet(),
                    payload = packet.textValue,
                )
                change(current.id) {
                    val updatedList = (it.receivedPackets + item).takeLast(MAX_BUFFERED_PACKETS)
                    it.copy(receivedPackets = updatedList)
                }
            }
            is P4Packet.RemoteCancelFile,
            is P4Packet.FileCookie,
            null -> Unit
        }
    }

    suspend fun stop() {
        val current = currentGame ?: return
        if (current.status !in listOf(GameSessionStatus.Invited, GameSessionStatus.Active)) return
        val declined = !current.outgoing && current.status == GameSessionStatus.Invited
        invitationTimeout?.cancel()
        change(current.id) {
            it.copy(
                status = if (declined) GameSessionStatus.Declined else GameSessionStatus.Stopped,
            )
        }
        if (session == 0L) return
        if (declined) {
            respond(requireNotNull(invite), session, accepted = false)
        } else {
            sendControl(
                sipMessage(
                    firstLine = "BYE MSNMSGR:$destination MSNSLP/1.0",
                    to = destination,
                    from = source,
                    branch = MsnpP2pUtil.randomGuid(),
                    callId = current.id,
                    cseq = 0,
                    contentType = "application/x-msnmsgr-sessionclosebody",
                    content = "SessionID: $session\r\n\r\n",
                ),
            )
        }
    }

    fun disconnect() {
        invitationTimeout?.cancel()
        scope.cancel()
        currentGame?.takeIf {
            it.status in listOf(GameSessionStatus.Active, GameSessionStatus.Invited)
        }?.let {
            update(
                it.copy(
                    status = GameSessionStatus.Stopped,
                    remoteStopped = true,
                    remoteAppClosed = true,
                ),
            )
        }
    }

    private suspend fun respond(invite: SlpMessage, id: Long, accepted: Boolean) = sendControl(
        sipMessage(
            firstLine = if (accepted) "MSNSLP/1.0 200 OK" else "MSNSLP/1.0 603 Decline",
            to = destination,
            from = source,
            branch = invite.branch,
            callId = invite.callId,
            cseq = 1,
            contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
            content = "SessionID: $id\r\n\r\n",
        ),
    )

    @Synchronized
    private fun update(value: GameSession) {
        currentGame = value
        publish(value)
    }

    @Synchronized
    private fun change(id: String, transform: (GameSession) -> GameSession) {
        currentGame?.takeIf { it.id.equals(id, ignoreCase = true) }?.let { update(transform(it)) }
    }

    private fun startInvitationTimeout(id: String) {
        invitationTimeout?.cancel()
        invitationTimeout = scope.launch {
            delay(INVITATION_TIMEOUT_MILLIS)
            val current = currentGame?.takeIf {
                it.id.equals(id, ignoreCase = true) && it.status == GameSessionStatus.Invited
            } ?: return@launch
            change(current.id) {
                it.copy(
                    status = GameSessionStatus.Failed,
                    error = "Game invitation timed out.",
                )
            }
            if (session != 0L) {
                runCatching {
                    sendControl(
                        sipMessage(
                            firstLine = "BYE MSNMSGR:$destination MSNSLP/1.0",
                            to = destination,
                            from = source,
                            branch = MsnpP2pUtil.randomGuid(),
                            callId = id,
                            cseq = 0,
                            contentType = "application/x-msnmsgr-sessionclosebody",
                            content = "SessionID: $session\r\n\r\n",
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val INVITATION_TIMEOUT_MILLIS = 600_000L
        private const val MAX_BUFFERED_PACKETS = 512
    }
}
