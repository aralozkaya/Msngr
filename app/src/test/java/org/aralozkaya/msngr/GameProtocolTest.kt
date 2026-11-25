package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnGameInviteContext
import org.aralozkaya.msngr.protocol.MsnGameProtocol
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.aralozkaya.msngr.protocol.P4Packet
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class GameProtocolTest {
    @Test
    fun inviteContextEncodesAndParsesUtf16Le() {
        val original = MsnGameInviteContext(
            entryId = 101000,
            kids = 1,
            appName = "Tic Tac Toe",
        )
        val encoded = original.encode()
        assertEquals(20 * 2, encoded.size)

        val parsed = MsnGameInviteContext.parse(encoded)
        assertEquals(original, parsed)
    }

    @Test
    fun p4RemoteAppLoadedPacketRoundTripsExactWireFormat() {
        val encoded = MsnGameProtocol.encodeRemoteAppLoaded()
        assertEquals(4, encoded.size)
        assertEquals(0, encoded[0].toInt() and 0xff)
        assertEquals(0, encoded[1].toInt() and 0xff)
        assertEquals(1, encoded[2].toInt() and 0xff)
        assertEquals(0, encoded[3].toInt() and 0xff)

        val decoded = MsnGameProtocol.decodePacket(encoded)
        assertEquals(P4Packet.RemoteAppLoaded, decoded)
    }

    @Test
    fun p4ComVariantPacketEncodesAndDecodesPayloads() {
        for (payload in listOf("0:0", "move:e2e4", "DRAW:120,240", "")) {
            val encoded = MsnGameProtocol.encodeData(payload)
            assertEquals(0x80, encoded[0].toInt() and 0xff)
            assertEquals(0x00, encoded[1].toInt() and 0xff)
            assertEquals(0x00, encoded[2].toInt() and 0xff)
            assertEquals(0x00, encoded[3].toInt() and 0xff)
            assertEquals(0x08, encoded[4].toInt() and 0xff)
            assertEquals(0x00, encoded[5].toInt() and 0xff)

            val decoded = MsnGameProtocol.decodePacket(encoded)
            assertEquals(P4Packet.Data(payload), decoded)
        }
        assertEquals(P4Packet.Data(42), MsnGameProtocol.decodePacket(MsnGameProtocol.encodeData(42)))
        assertEquals(P4Packet.Data(true), MsnGameProtocol.decodePacket(MsnGameProtocol.encodeData(true)))
        assertEquals(P4Packet.Data(3.5), MsnGameProtocol.decodePacket(MsnGameProtocol.encodeData(3.5)))
    }

    @Test
    fun catalogServiceResolvesEscargotAndCrossTalkCandidateEndpointsAndUpgradesHttps() {
        assertEquals(
            listOf(
                "https://apps.escargot.nina.chat/AppDirectory/AppDirectory.asmx",
                "https://apps.escargot.nina.chat/activities/service/",
            ),
            MsnpGameCatalogService.candidateServiceUrls("apps.escargot.nina.chat"),
        )
        assertEquals(
            listOf(
                "https://mactivities.msgrsvcs.ctsrv.xyz/AppDirectory/AppDirectory.asmx",
                "https://mactivities.msgrsvcs.ctsrv.xyz/activities/service/",
            ),
            MsnpGameCatalogService.candidateServiceUrls("mactivities.msgrsvcs.ctsrv.xyz"),
        )
        assertEquals(
            listOf(
                "https://127.0.0.1:18080/AppDirectory/AppDirectory.asmx",
                "https://127.0.0.1:18080/activities/service/",
                "http://127.0.0.1:18080/AppDirectory/AppDirectory.asmx",
                "http://127.0.0.1:18080/activities/service/",
            ),
            MsnpGameCatalogService.candidateServiceUrls("127.0.0.1:18080"),
        )
        assertEquals(
            listOf(
                "http://127.0.0.1:18080/AppDirectory/AppDirectory.asmx",
                "http://127.0.0.1:18080/activities/service/",
            ),
            MsnpGameCatalogService.candidateServiceUrls("http://127.0.0.1:18080"),
        )
        assertEquals(
            listOf("https://apps.example.org/AppDirectory/AppDirectory.asmx"),
            MsnpGameCatalogService.candidateServiceUrls("https://apps.example.org/AppDirectory/Directory.aspx"),
        )
        assertEquals(
            "https://static.levelleap.com/app-data/tic-tac-toe/tictactoe.htm",
            MsnpGameCatalogService.upgradeKnownHttpsUrl("http://static.levelleap.com/app-data/tic-tac-toe/tictactoe.htm"),
        )
        assertEquals(
            "https://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/reversi/reversi.htm",
            MsnpGameCatalogService.upgradeKnownHttpsUrl("http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/reversi/reversi.htm"),
        )
    }

    @Test
    fun catalogXmlParserSupportsBothEscargotAndCrossTalkSchemas() {
        val escargotXml = """
            <DataSet>
                <Category>
                    <CategoryID>101</CategoryID>
                    <Sequence>1</Sequence>
                    <Name>Escargot Games</Name>
                    <Description>Classic games</Description>
                    <IconURL>http://apps.escargot.nina.chat/icon.gif</IconURL>
                </Category>
                <Entry>
                    <EntryID>101000</EntryID>
                    <CategoryID>101</CategoryID>
                    <Sequence>1</Sequence>
                    <Name>Tic Tac Toe</Name>
                    <Description>Play Tic Tac Toe</Description>
                    <URL>http://static.levelleap.com/app-data/tic-tac-toe/tictactoe.htm</URL>
                    <IconURL>http://static.levelleap.com/app-data/tic-tac-toe/icon.png</IconURL>
                    <AppIconURL>http://static.levelleap.com/app-data/tic-tac-toe/icon.png</AppIconURL>
                    <AppType>0</AppType>
                    <Kids>1</Kids>
                    <MinUsers>2</MinUsers>
                    <MaxUsers>2</MaxUsers>
                    <Height>500</Height>
                    <Width>500</Width>
                </Entry>
            </DataSet>
        """.trimIndent()
        val escargotCatalog = MsnpGameCatalogService.parseDataSetXml(escargotXml, defaultAppType = 0)
        assertEquals(1, escargotCatalog.categories.size)
        assertEquals("Escargot Games", escargotCatalog.categories.single().name)
        assertEquals("https://apps.escargot.nina.chat/icon.gif", escargotCatalog.categories.single().iconUrl)
        assertEquals(1, escargotCatalog.entries.size)
        assertEquals(101000, escargotCatalog.entries.single().appId)
        assertEquals("https://static.levelleap.com/app-data/tic-tac-toe/tictactoe.htm", escargotCatalog.entries.single().url)

        val crossTalkXml = """
            <AppDir code="2" v="14.0.8117.416">
                <Entry>
                    <EntryID>10291359</EntryID>
                    <CategoryID>102</CategoryID>
                    <Sequence>0</Sequence>
                    <Name>Reversi</Name>
                    <Description>Also known as Othello.</Description>
                    <URL>http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/reversi/reversi.htm</URL>
                    <IconURL>http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/reversi/images/icon.png</IconURL>
                    <AppIconURL>http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/reversi/images/icon.png</AppIconURL>
                    <AppType>0</AppType>
                    <Hidden>false</Hidden>
                </Entry>
                <Category>
                    <CategoryID>102</CategoryID>
                    <CategoryName>Mess.be Games</CategoryName>
                    <CategoryDescription>Community games</CategoryDescription>
                    <CategoryIconURL>http://mactivities.msgrsvcs.ctsrv.xyz/static/cat.png</CategoryIconURL>
                </Category>
            </AppDir>
        """.trimIndent()
        val crossTalkCatalog = MsnpGameCatalogService.parseDataSetXml(crossTalkXml, defaultAppType = 0)
        assertEquals(1, crossTalkCatalog.categories.size)
        assertEquals("Mess.be Games", crossTalkCatalog.categories.single().name)
        assertEquals("Community games", crossTalkCatalog.categories.single().description)
        assertEquals("https://mactivities.msgrsvcs.ctsrv.xyz/static/cat.png", crossTalkCatalog.categories.single().iconUrl)
        assertEquals(10291359, crossTalkCatalog.entries.single().appId)
        assertEquals(
            "https://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/reversi/reversi.htm",
            crossTalkCatalog.entries.single().url,
        )
    }

    @Test
    fun closingGameIncludesNativeP4CloseReasonForEitherPlayer() = runBlocking {
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!
        for (hostCloses in listOf(true, false)) {
            val sent = CopyOnWriteArrayList<SlpMessage>()
            lateinit var host: MsnpGameSession
            lateinit var guest: MsnpGameSession
            host = MsnpGameSession(
                source = "alice@example.test",
                destination = "bob@example.test",
                publish = {},
                sendControl = { bytes ->
                    val message = requireNotNull(SlpMessage.parse(bytes))
                    sent.add(message)
                    guest.handle(message)
                },
                sendData = { _, _, _ -> },
            )
            guest = MsnpGameSession(
                source = "bob@example.test",
                destination = "alice@example.test",
                publish = {},
                sendControl = { bytes ->
                    val message = requireNotNull(SlpMessage.parse(bytes))
                    sent.add(message)
                    host.handle(message)
                },
                sendData = { _, _, _ -> },
                resolveCatalogEntry = { _, _ -> entry },
            )
            try {
                host.start(entry)
                guest.accept()
                (if (hostCloses) host else guest).stop()
                val invitation = sent.first()
                val close = sent.last()
                assertTrue(close.firstLine.startsWith("BYE "))
                assertEquals(invitation.callId, close.callId)
                assertEquals(invitation.body.field("SessionID"), close.body.field("SessionID"))
                assertEquals("application/x-msnmsgr-sessionclosebody", close.contentType)
                assertArrayEquals(
                    byteArrayOf(0x74, 0x03, 0x00, 0x81.toByte()),
                    MsnBase64.decode(requireNotNull(close.body.field("Context"))),
                )
            } finally {
                host.disconnect()
                guest.disconnect()
            }
        }
    }

    @Test
    fun p2pV1AndV2GameSessionsNegotiateHandshakeAndExchangeMoves() = runBlocking {
        for (v2 in listOf(false, true)) {
            val hostSessions = CopyOnWriteArrayList<GameSession>()
            val guestSessions = CopyOnWriteArrayList<GameSession>()
            lateinit var hostP2p: MsnpP2pSession
            lateinit var guestP2p: MsnpP2pSession

            hostP2p = MsnpP2pSession(
                "alice@example.test",
                "bob@example.test",
                { guestP2p.handle(it) },
                { null },
                {},
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                onGameSession = { hostSessions.add(it) },
            )
            guestP2p = MsnpP2pSession(
                "bob@example.test",
                "alice@example.test",
                { hostP2p.handle(it) },
                { null },
                {},
                localEndpointId = "22222222-2222-2222-2222-222222222222",
                remoteEndpointId = if (v2) "11111111-1111-1111-1111-1111-111111111111".take(36) else null,
                onGameSession = { guestSessions.add(it) },
            )

            try {
                val entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!
                val sessionId = hostP2p.startGame(entry)
                assertEquals(GameSessionStatus.Invited, hostSessions.last().status)
                assertTrue(hostSessions.last().outgoing)
                assertEquals(GameSessionStatus.Invited, guestSessions.last().status)
                assertFalse(guestSessions.last().outgoing)

                guestP2p.acceptGame(sessionId)
                assertEquals(GameSessionStatus.Active, hostSessions.last().status)
                assertEquals(GameSessionStatus.Active, guestSessions.last().status)

                hostP2p.initializeGameChannel(sessionId)
                guestP2p.initializeGameChannel(sessionId)
                withTimeout(3_000) {
                    while (!hostSessions.last().remoteAppLoaded || !guestSessions.last().remoteAppLoaded) {
                        delay(10)
                    }
                }

                hostP2p.sendGameData(sessionId, "0:0")
                guestP2p.sendGameData(sessionId, "1:1")
                withTimeout(3_000) {
                    while (
                        guestSessions.last().receivedPackets.lastOrNull()?.payload != "0:0" ||
                        hostSessions.last().receivedPackets.lastOrNull()?.payload != "1:1"
                    ) {
                        delay(10)
                    }
                }
                assertEquals(listOf("0:0"), guestSessions.last().receivedPackets.map { it.payload })
                assertEquals(listOf("1:1"), hostSessions.last().receivedPackets.map { it.payload })

                guestP2p.stopGame(sessionId)
                assertEquals(GameSessionStatus.Stopped, guestSessions.last().status)
                assertEquals(GameSessionStatus.Stopped, hostSessions.last().status)
                assertTrue(hostSessions.last().remoteStopped)
                assertTrue(hostSessions.last().remoteAppClosed)
            } finally {
                hostP2p.closeFileTransfers()
                guestP2p.closeFileTransfers()
            }
        }
    }

    @Test
    fun nativeGamePaneDimensionsEnforceNativeDefaultsAndWhiteboardCanvasBounds() {
        val catalog = MsnpGameCatalogService.defaultCatalog()
        val battleships = catalog.findEntry(102002)!!
        assertEquals(500 to 500, nativeGamePaneDimensions(battleships))
        assertEquals(500, battleships.width)
        assertEquals(500, battleships.height)

        val undersized = battleships.copy(width = 390, height = 350)
        assertEquals(500 to 500, nativeGamePaneDimensions(undersized))

        val zeroSized = battleships.copy(width = 0, height = 0)
        assertEquals(500 to 500, nativeGamePaneDimensions(zeroSized))

        val whiteboard = catalog.findEntry(100000)!!
        assertEquals(500 to WHITEBOARD_NATIVE_PANE_HEIGHT, nativeGamePaneDimensions(whiteboard))

        val wordle = catalog.findEntry(103000)!!
        assertEquals(500 to WORDLE_NATIVE_PANE_HEIGHT, nativeGamePaneDimensions(wordle))
    }

    @Test
    fun normalizeLegacyGameHtmlMergesDuplicateStylesAndFixesExpressionsWithoutTouchingScripts() {
        val raw = """
            <html>
            <head>
            <script language="javascript">
                if (i < 3 && j > 1) { var s = '<body style="a:1" style="b:2">'; }
            </script>
            </head>
            <body onload="OnLoad();" onunload="OnUnload();" style="margin:0" scroll="no" style="background-color:#516CA1">
                <span id="pnlGameover" style="display:none;position:absolute;left:expression(document.body.clientWidth/2 - this.offsetWidth/2);
                    top:expression(document.body.clientHeight/2 - this.offsetHeight)"></span>
            </body>
            </html>
        """.trimIndent()

        val normalized = normalizeLegacyGameHtml(raw)
        assertTrue(normalized.contains("""style="margin:0;background-color:#516CA1""""))
        assertFalse(normalized.substringAfter("</head>").contains("""style="background-color:#516CA1""""))
        assertTrue(normalized.contains("""var s = '<body style="a:1" style="b:2">';"""))
        assertTrue(normalized.contains("left:50%;top:50%;transform:translate(-50%,-100%)"))
    }

    @Test
    fun gameJsDialogRequestSettlesAtMostOnce() {
        var confirmed = 0
        var cancelled = 0
        var receivedPrompt: String? = null
        val confirmReq = GameJsDialogRequest(
            id = 1L,
            kind = GameJsDialogKind.Confirm,
            message = "Restart the game?",
            onConfirm = { confirmed++ },
            onCancel = { cancelled++ },
        )
        confirmReq.confirm()
        confirmReq.confirm()
        confirmReq.cancel()
        assertTrue(confirmReq.isSettled)
        assertEquals(1, confirmed)
        assertEquals(0, cancelled)

        val promptReq = GameJsDialogRequest(
            id = 2L,
            kind = GameJsDialogKind.Prompt("default"),
            message = "Enter name",
            onConfirm = { receivedPrompt = it; confirmed++ },
            onCancel = { cancelled++ },
        )
        promptReq.cancel()
        promptReq.confirm("Alice")
        assertTrue(promptReq.isSettled)
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
        assertEquals(null, receivedPrompt)
    }

    @Test
    fun p2pV2PacketRoundTripsNakSeqStartsWithSynOnReplyAndResyncsAfterNak() = runBlocking {
        val nakEncoded = P2pV2Packet(operationCode = 0, identifier = 100L, nakIdentifier = 777L).encode()
        assertTrue(P2pV2Packet.looksLike(nakEncoded))
        val nakParsed = requireNotNull(P2pV2Packet.parse(nakEncoded))
        assertEquals(777L, nakParsed.nakIdentifier)

        val outgoing = mutableListOf<P2pV2Packet>()
        val peer = MsnpP2pV2Session(
            localEmail = "mobile@example.test",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEmail = "pc@example.test",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = { outgoing.add(requireNotNull(P2pV2Packet.parse(it))) },
            localPicture = { null },
            onDisplayPicture = {},
        )
        // Remote speaks first without OP_SYN; our transresp reply must initiate OP_SYN | OP_RAK + peerInfo.
        val transReq = sipMessage(
            firstLine = "INVITE MSNMSGR:mobile@example.test MSNSLP/1.0",
            to = "mobile@example.test",
            from = "pc@example.test",
            branch = MsnpP2pUtil.randomGuid(),
            callId = MsnpP2pUtil.randomGuid(),
            cseq = 0,
            contentType = MsnpP2pUtil.TRANSPORT_REQUEST_CONTENT_TYPE,
            content = "Bridges: TCPv1 SBBridge\r\nHashed-Nonce: {55555555-5555-5555-5555-555555555555}\r\n\r\n",
        )
        peer.handle(P2pV2Packet(operationCode = 0, identifier = 10L, sessionId = 0L, tfCombination = 1, body = transReq).encode())

        assertEquals(1, outgoing.size)
        assertEquals(3, outgoing.single().operationCode and 3)
        assertTrue(outgoing.single().peerInfo)

        // Subsequent message reuses bridge without OP_SYN.
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!
        val gameId = peer.startGame(entry)
        val invitePacket = outgoing.last()
        assertEquals(0, invitePacket.operationCode and 1)

        // Transition session to Active so sendGameData emits a data frame.
        val inviteSlp = requireNotNull(SlpMessage.parse(invitePacket.body))
        val acceptBody = sipMessage(
            firstLine = "MSNSLP/1.0 200 OK",
            to = "mobile@example.test;{11111111-1111-1111-1111-111111111111}",
            from = "pc@example.test;{22222222-2222-2222-2222-222222222222}",
            branch = inviteSlp.branch,
            callId = inviteSlp.callId,
            cseq = 1,
            contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
            content = "SessionID: ${inviteSlp.body.field("SessionID")}\r\n\r\n",
        )
        peer.handle(P2pV2Packet(operationCode = 0, identifier = 50L, sessionId = 0L, tfCombination = 1, body = acceptBody).encode())

        // Incoming NAK_SEQ resets localSynSent and aligns next sequence identifier to nakIdentifier.
        val beforeNakCount = outgoing.size
        peer.handle(P2pV2Packet(operationCode = 0, identifier = 20L, nakIdentifier = 777L).encode())
        peer.sendGameData(gameId, "0")
        assertEquals(beforeNakCount + 1, outgoing.size)
        val afterNak = outgoing.last()
        assertEquals(3, afterNak.operationCode and 3)
        assertTrue(afterNak.peerInfo)
        assertEquals(777L, afterNak.identifier)
        peer.closeFileTransfers()
    }

    @Test
    fun p2pV2ConcurrentSendsProduceStrictlyContiguousIdentifiers() = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<P2pV2Packet>())
        val peer = MsnpP2pV2Session(
            localEmail = "mobile@example.test",
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEmail = "pc@example.test",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            sendPacket = { raw ->
                delay(2)
                sent.add(requireNotNull(P2pV2Packet.parse(raw)))
            },
            localPicture = { null },
            onDisplayPicture = {},
        )
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!
        val id = peer.startGame(entry)
        // Transition session to Active cleanly
        val invitePacket = sent.first()
        val inviteSlp = requireNotNull(SlpMessage.parse(invitePacket.body))
        val acceptBody = sipMessage(
            firstLine = "MSNSLP/1.0 200 OK",
            to = "mobile@example.test;{11111111-1111-1111-1111-111111111111}",
            from = "pc@example.test;{22222222-2222-2222-2222-222222222222}",
            branch = inviteSlp.branch,
            callId = inviteSlp.callId,
            cseq = 1,
            contentType = MsnpP2pUtil.SESSION_REQUEST_CONTENT_TYPE,
            content = "SessionID: ${inviteSlp.body.field("SessionID")}\r\n\r\n",
        )
        peer.handle(P2pV2Packet(operationCode = 0, identifier = 50L, sessionId = 0L, tfCombination = 1, body = acceptBody).encode())

        val jobs = (1..16).map { idx ->
            async(Dispatchers.Default) {
                peer.sendGameData(id, "move-$idx")
            }
        }
        jobs.awaitAll()

        val dataPackets = sent.filter { it.messageSize > 0 }
        assertTrue(dataPackets.size >= 17)
        for (i in 1 until dataPackets.size) {
            val prev = dataPackets[i - 1]
            val curr = dataPackets[i]
            assertEquals(
                "Packet $i identifier must equal previous identifier + previous messageSize",
                (prev.identifier + prev.messageSize) and 0xffff_ffffL,
                curr.identifier,
            )
        }
        peer.closeFileTransfers()
    }

    @Test
    fun bothP2pVersionsDeclineIncomingTransportResponseInvitesWith603() = runBlocking {
        for (v2 in listOf(false, true)) {
            val responses = mutableListOf<ByteArray>()
            val receiver = MsnpP2pSession(
                localEmail = "mobile@example.test",
                remoteEmail = "pc@example.test",
                localEndpointId = "11111111-1111-1111-1111-111111111111",
                remoteEndpointId = if (v2) "22222222-2222-2222-2222-222222222222" else null,
                sendPacket = responses::add,
                localPicture = { null },
                onDisplayPicture = {},
            )
            val callId = MsnpP2pUtil.randomGuid()
            val transRespInvite = sipMessage(
                firstLine = "INVITE MSNMSGR:mobile@example.test MSNSLP/1.0",
                to = "mobile@example.test",
                from = "pc@example.test",
                branch = MsnpP2pUtil.randomGuid(),
                callId = callId,
                cseq = 0,
                contentType = MsnpP2pUtil.TRANSPORT_RESPONSE_CONTENT_TYPE,
                content = "Bridge: TCPv1\r\nListening: true\r\nSessionID: 99\r\n\r\n",
            )
            val wirePacket = if (v2) {
                P2pV2Packet(operationCode = 3, identifier = 100L, sessionId = 0L, tfCombination = 1, body = transRespInvite, peerInfo = true).encode()
            } else {
                java.nio.ByteBuffer.allocate(48 + transRespInvite.size + 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(0) // sessionId
                    .putInt(500) // id
                    .putLong(0L) // offset
                    .putLong(transRespInvite.size.toLong()) // totalSize
                    .putInt(transRespInvite.size) // length
                    .putInt(0) // flags
                    .putInt(501) // ackId
                    .putInt(0) // ackSubId
                    .putLong(0L) // ackSize
                    .put(transRespInvite)
                    .putInt(0)
                    .array()
            }
            receiver.handle(wirePacket)

            val responseSlp = responses.mapNotNull { bytes ->
                val slpBytes = if (v2) P2pV2Packet.parse(bytes)?.body else bytes.copyOfRange(48, bytes.size - 4)
                slpBytes?.takeIf { it.isNotEmpty() }?.let(SlpMessage::parse)
            }.single()
            assertEquals("V2=$v2 must reply 603 Decline", "MSNSLP/1.0 603 Decline", responseSlp.firstLine)
            assertEquals(callId, responseSlp.callId)
            assertEquals(MsnpP2pUtil.TRANSPORT_RESPONSE_CONTENT_TYPE, responseSlp.contentType)
        }
    }

    @Test
    fun switchboardLearnsEndpointsTargetsLatestForStartGameAndRoutesCrossEndpointReplies() = runBlocking {
        val localEmail = "mobile@example.test"
        val peerEmail = "pc@example.test"
        val localGuid = "11111111-1111-1111-1111-111111111111"
        val staleEp = "22222222-2222-2222-2222-222222222222"
        val liveEp = "33333333-3333-3333-3333-333333333333"
        val thirdEp = "44444444-4444-4444-4444-444444444444"

        val events = CopyOnWriteArrayList<SwitchboardEvent>()
        val learnedEndpoints = CopyOnWriteArrayList<Pair<String, String>>()
        val sentP2pMessages = CopyOnWriteArrayList<SwitchboardMimeMessage.P2p>()
        val incomingCommands = Channel<MsnpCommand>(Channel.UNLIMITED)

        val transport = object : MsnpTransport {
            override suspend fun connect(host: String, port: Int, timeoutMillis: Int) = Unit
            override suspend fun send(command: MsnpCommand) {
                when (command.verb) {
                    "USR" -> incomingCommands.trySend(MsnpCommand("USR", command.transactionId, listOf("OK", localEmail, "Mobile")))
                    "CAL" -> {
                        val invitee = command.parameters.first()
                        incomingCommands.trySend(MsnpCommand("CAL", command.transactionId, listOf("RINGING", "1")))
                        incomingCommands.trySend(MsnpCommand("JOI", parameters = listOf("$invitee;{$staleEp}", "PC", "0:0")))
                    }
                    "MSG" -> {
                        val parsed = parseSwitchboardMime(requireNotNull(command.payload))
                        if (parsed is SwitchboardMimeMessage.P2p) sentP2pMessages.add(parsed)
                        if (command.parameters.firstOrNull() == "D") {
                            incomingCommands.trySend(MsnpCommand("ACK", command.transactionId))
                        }
                    }
                }
            }
            override suspend fun receive(): MsnpCommand = incomingCommands.receive()
            override fun setReadTimeout(timeoutMillis: Int) = Unit
            override fun close() { incomingCommands.close() }
        }

        val sb = MsnpSwitchboardSession(
            signedInEmail = localEmail,
            machineGuid = localGuid,
            contactEmail = peerEmail,
            invitation = SwitchboardInvitation("sb.example", 1864, "auth"),
            transport = transport,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            eventSink = events::add,
            remoteEndpointId = staleEp,
            handshakeTimeoutMillis = 1_000,
            onContactEndpoint = { contact, ep -> learnedEndpoints.add(contact to ep) },
        )
        try {
            sb.connect()
            incomingCommands.trySend(MsnpCommand("JOI", parameters = listOf("$peerEmail;{$liveEp}", "PC", "0:0")))
            withTimeout(2_000) {
                while (learnedEndpoints.none { it.first == peerEmail && it.second.equals(liveEp, ignoreCase = true) }) {
                    delay(5)
                }
            }

            val entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!
            val gameId = sb.startGame(entry)
            withTimeout(2_000) {
                while (sentP2pMessages.isEmpty()) delay(5)
            }
            val firstSent = sentP2pMessages.first()
            assertEquals("$peerEmail;{$liveEp}", firstSent.destination)

            // Remote accepts and sends Loaded + move from a third endpoint (thirdEp), testing cross-endpoint routing!
            val remoteReplies = CopyOnWriteArrayList<ByteArray>()
            val remoteP2p = MsnpP2pSession(
                localEmail = peerEmail,
                remoteEmail = localEmail,
                localEndpointId = thirdEp,
                remoteEndpointId = localGuid,
                sendPacket = { raw ->
                    remoteReplies.add(raw)
                    val p2pPayload = Msnp18Protocol.p2pPayload("$localEmail;{$localGuid}", raw, "$peerEmail;{$thirdEp}")
                    incomingCommands.trySend(
                        MsnpCommand("MSG", parameters = listOf("$peerEmail;{$thirdEp}", "PC", p2pPayload.size.toString()), payload = p2pPayload),
                    )
                },
                localPicture = { null },
                onDisplayPicture = {},
            )
            // Feed Android's outgoing INVITE bytes to remoteP2p (re-wrapping SLP for direct handoff)
            val invitePacket = requireNotNull(P2pV2Packet.parse(firstSent.bytes))
            remoteP2p.handle(invitePacket.encode())
            remoteP2p.acceptGame(gameId)
            remoteP2p.initializeGameChannel(gameId)
            withTimeout(3_000) {
                while (events.none { it is SwitchboardEvent.GameChanged && it.session.status == GameSessionStatus.Active && it.session.remoteAppLoaded }) {
                    delay(10)
                }
            }
            remoteP2p.sendGameData(gameId, "4")
            withTimeout(2_000) {
                while (events.none { it is SwitchboardEvent.GameChanged && it.session.receivedPackets.any { p -> p.payload == "4" } }) {
                    delay(10)
                }
            }
            sb.sendGameData(gameId, "2")
            withTimeout(2_000) {
                while (sentP2pMessages.none { msg ->
                        val pkt = P2pV2Packet.parse(msg.bytes)
                        pkt != null && pkt.sessionId != 0L &&
                            (MsnGameProtocol.decodePacket(pkt.body) as? P4Packet.Data)?.textValue == "2"
                    }) {
                    delay(10)
                }
            }
            val moveMsg = sentP2pMessages.last { msg ->
                val pkt = P2pV2Packet.parse(msg.bytes)
                pkt != null && pkt.sessionId != 0L &&
                    (MsnGameProtocol.decodePacket(pkt.body) as? P4Packet.Data)?.textValue == "2"
            }
            assertEquals("$peerEmail;{$thirdEp}", moveMsg.destination)
            remoteP2p.closeFileTransfers()
        } finally {
            sb.close()
        }
    }

    @Test
    fun gameSessionDefersLoadedControlDuringGraceWindowAndSendsImmediatelyWhenPeerAlreadyLoaded() = runBlocking {
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!

        // 1) Deferral + single-send guarantee
        val hostSentData = CopyOnWriteArrayList<ByteArray>()
        lateinit var host: MsnpGameSession
        lateinit var guest: MsnpGameSession
        val hostState = CopyOnWriteArrayList<GameSession>()
        val guestState = CopyOnWriteArrayList<GameSession>()
        host = MsnpGameSession(
            source = "alice@example.test",
            destination = "bob@example.test",
            publish = hostState::add,
            sendControl = { SlpMessage.parse(it)?.let { msg -> guest.handle(msg) } },
            sendData = { _, _, bytes -> hostSentData.add(bytes); guest.receive(bytes) },
            loadedHandshakeGraceMillis = 250L,
        )
        guest = MsnpGameSession(
            source = "bob@example.test",
            destination = "alice@example.test",
            publish = guestState::add,
            sendControl = { SlpMessage.parse(it)?.let { msg -> host.handle(msg) } },
            sendData = { _, _, bytes -> host.receive(bytes) },
            resolveCatalogEntry = { _, _ -> entry },
            loadedHandshakeGraceMillis = 5_000L,
        )
        try {
            host.start(entry)
            guest.accept()
            host.initializeChannel()
            host.initializeChannel() // duplicate call must not send twice
            delay(50)
            assertEquals("Loaded packet must be deferred during grace window", 0, hostSentData.size)

            withTimeout(2_000) {
                while (guestState.lastOrNull()?.remoteAppLoaded != true) delay(10)
            }
            assertEquals(1, hostSentData.count { MsnGameProtocol.decodePacket(it) is P4Packet.RemoteAppLoaded })
        } finally {
            host.disconnect()
            guest.disconnect()
        }

        // 2) Early RemoteAppLoaded while outgoing && Invited cancels 5s grace and sends immediately on initializeChannel
        val earlySentData = CopyOnWriteArrayList<ByteArray>()
        val earlyStates = CopyOnWriteArrayList<GameSession>()
        lateinit var earlyHost: MsnpGameSession
        lateinit var earlyGuest: MsnpGameSession
        earlyHost = MsnpGameSession(
            source = "alice@example.test",
            destination = "bob@example.test",
            publish = earlyStates::add,
            sendControl = { SlpMessage.parse(it)?.let { msg -> earlyGuest.handle(msg) } },
            sendData = { _, _, bytes -> earlySentData.add(bytes) },
            loadedHandshakeGraceMillis = 5_000L,
        )
        earlyGuest = MsnpGameSession(
            source = "bob@example.test",
            destination = "alice@example.test",
            publish = {},
            sendControl = { SlpMessage.parse(it)?.let { msg -> earlyHost.handle(msg) } },
            sendData = { _, _, _ -> },
            resolveCatalogEntry = { _, _ -> entry },
            loadedHandshakeGraceMillis = 5_000L,
        )
        try {
            earlyHost.start(entry)
            earlyHost.receive(MsnGameProtocol.encodeRemoteAppLoaded())
            assertTrue(
                "Outgoing Invited session must record early RemoteAppLoaded",
                earlyStates.last().remoteAppLoaded,
            )
            earlyGuest.accept()
            earlyHost.initializeChannel()
            withTimeout(500) {
                while (earlySentData.none { MsnGameProtocol.decodePacket(it) is P4Packet.RemoteAppLoaded }) {
                    delay(10)
                }
            }
            assertEquals(1, earlySentData.size)
        } finally {
            earlyHost.disconnect()
            earlyGuest.disconnect()
        }
    }

    @Test
    fun bridgeHtmlInjectionAndHostAllowlistCoverCrossTalkEscargotAndCustomServers() {
        val crossTalkTtt = "http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/tic-tac-toe/tictactoe.htm"
        val crossTalkBattleships = "http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/battleships/battleships.htm"
        assertTrue(isAllowedGameHost(crossTalkTtt, crossTalkTtt))
        assertTrue(shouldInjectGameBridgeHtml(crossTalkTtt, crossTalkTtt, isMainFrame = true))
        assertTrue(shouldInjectGameBridgeHtml(crossTalkBattleships, crossTalkBattleships, isMainFrame = false))
        assertTrue(isAllowedGameHost(crossTalkBattleships, "http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/common/common.js"))
        assertFalse(shouldInjectGameBridgeHtml(crossTalkBattleships, "http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/common/common.js", isMainFrame = false))
        assertTrue(isAllowedGameHost(crossTalkBattleships, "http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/battleships/help.htm"))
        assertFalse(shouldInjectGameBridgeHtml(crossTalkBattleships, "http://mactivities.msgrsvcs.ctsrv.xyz/static/app-data/messbe/battleships/help.htm", isMainFrame = true))

        val escargotTtt = "https://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/tic-tac-toe/tictactoe.htm"
        assertTrue(isAllowedGameHost(escargotTtt, escargotTtt))
        assertTrue(shouldInjectGameBridgeHtml(escargotTtt, escargotTtt, isMainFrame = true))
        assertTrue(shouldInjectGameBridgeHtml(escargotTtt, "https://apps.escargot.nina.chat/activities/play", isMainFrame = true))

        val customEntry = "http://games.custom.example:8080/play/index.htm"
        assertTrue(isAllowedGameHost(customEntry, "http://games.custom.example:8080/play/board.html"))
        assertTrue(shouldInjectGameBridgeHtml(customEntry, "http://games.custom.example:8080/play/board.html", isMainFrame = false))

        assertFalse(isAllowedGameHost(crossTalkTtt, "about:blank"))
        assertFalse(shouldInjectGameBridgeHtml(crossTalkTtt, "about:blank", isMainFrame = true))
        assertFalse(isAllowedGameHost(crossTalkTtt, "javascript:alert(1)"))
        assertFalse(isAllowedGameHost(crossTalkTtt, "file:///etc/passwd"))
        assertFalse(isAllowedGameHost(crossTalkTtt, "intent://scan/#Intent;end"))
        assertTrue(isAllowedGameHost(customEntry, "https://cdn.unrelated.example/index.html"))
        assertTrue(shouldInjectGameBridgeHtml(customEntry, "https://cdn.unrelated.example/index.html", isMainFrame = true))

        val crossTalkWordle = MsnpGameCatalogService.defaultCatalog().findEntry(103000)!!.copy(appId = 999999)
        assertEquals(500 to WORDLE_NATIVE_PANE_HEIGHT, nativeGamePaneDimensions(crossTalkWordle))
    }
}
