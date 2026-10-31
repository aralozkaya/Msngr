package org.aralozkaya.msngr

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.MsnGameInviteContext
import org.aralozkaya.msngr.protocol.MsnGameProtocol
import org.aralozkaya.msngr.protocol.P4Packet
import org.junit.Assert.*
import org.junit.Test
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
                "https://apps.escargot.nina.chat/activities/service/",
                "https://apps.escargot.nina.chat/AppDirectory/AppDirectory.asmx",
            ),
            MsnpGameCatalogService.candidateServiceUrls("apps.escargot.nina.chat"),
        )
        assertEquals(
            listOf(
                "https://mactivities.msgrsvcs.ctsrv.gay/AppDirectory/AppDirectory.asmx",
                "https://mactivities.msgrsvcs.ctsrv.gay/activities/service/",
            ),
            MsnpGameCatalogService.candidateServiceUrls("mactivities.msgrsvcs.ctsrv.gay"),
        )
        assertEquals(
            "https://static.levelleap.com/app-data/tic-tac-toe/tictactoe.htm",
            MsnpGameCatalogService.upgradeKnownHttpsUrl("http://static.levelleap.com/app-data/tic-tac-toe/tictactoe.htm"),
        )
        assertEquals(
            "https://mactivities.msgrsvcs.ctsrv.gay/static/app-data/messbe/reversi/reversi.htm",
            MsnpGameCatalogService.upgradeKnownHttpsUrl("http://mactivities.msgrsvcs.ctsrv.gay/static/app-data/messbe/reversi/reversi.htm"),
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
                    <URL>http://mactivities.msgrsvcs.ctsrv.gay/static/app-data/messbe/reversi/reversi.htm</URL>
                    <IconURL>http://mactivities.msgrsvcs.ctsrv.gay/static/app-data/messbe/reversi/images/icon.png</IconURL>
                    <AppIconURL>http://mactivities.msgrsvcs.ctsrv.gay/static/app-data/messbe/reversi/images/icon.png</AppIconURL>
                    <AppType>0</AppType>
                    <Hidden>false</Hidden>
                </Entry>
                <Category>
                    <CategoryID>102</CategoryID>
                    <CategoryName>Mess.be Games</CategoryName>
                    <CategoryDescription>Community games</CategoryDescription>
                    <CategoryIconURL>http://mactivities.msgrsvcs.ctsrv.gay/static/cat.png</CategoryIconURL>
                </Category>
            </AppDir>
        """.trimIndent()
        val crossTalkCatalog = MsnpGameCatalogService.parseDataSetXml(crossTalkXml, defaultAppType = 0)
        assertEquals(1, crossTalkCatalog.categories.size)
        assertEquals("Mess.be Games", crossTalkCatalog.categories.single().name)
        assertEquals("Community games", crossTalkCatalog.categories.single().description)
        assertEquals("https://mactivities.msgrsvcs.ctsrv.gay/static/cat.png", crossTalkCatalog.categories.single().iconUrl)
        assertEquals(10291359, crossTalkCatalog.entries.single().appId)
        assertEquals(
            "https://mactivities.msgrsvcs.ctsrv.gay/static/app-data/messbe/reversi/reversi.htm",
            crossTalkCatalog.entries.single().url,
        )
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
}
