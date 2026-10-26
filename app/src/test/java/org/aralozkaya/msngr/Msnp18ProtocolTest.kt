package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpFrameCodec
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class Msnp18ProtocolTest {
    @Test
    fun negotiatesMsnp18() {
        val encoded = String(
            MsnpFrameCodec.encode(Msnp18Protocol.version(transactionId = 1)),
            StandardCharsets.UTF_8,
        )

        assertEquals("VER 1 MSNP18 CVR0\r\n", encoded)
    }

    @Test
    fun notificationHeartbeatUsesTheTransactionlessPngCommand() {
        assertEquals("PNG\r\n", String(MsnpFrameCodec.encode(Msnp18Protocol.ping())))
    }

    @Test
    fun incrementalDecoderWaitsForTheCompletePayload() {
        val payload = "hello".toByteArray()
        val frame = MsnpFrameCodec.encode(
            MsnpCommand(
                verb = "MSG",
                parameters = listOf("friend@example.com", "Friend", payload.size.toString()),
                payload = payload,
            ),
        )
        val decoder = MsnpFrameDecoder()

        val first = decoder.feed(frame.copyOfRange(0, frame.size - 2))
        val second = decoder.feed(frame.copyOfRange(frame.size - 2, frame.size))

        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertArrayEquals(payload, second.single().payload)
    }

    @Test
    fun numericRngSessionIdIsNotMistakenForATransactionId() {
        val decoded = MsnpFrameCodec.decodeHeader(
            "RNG 4242 mock-switchboard.example:1864 CKI mock-token ${MockServerData.contacts().first().email} Mock",
        )

        assertEquals(null, decoded.transactionId)
        assertEquals("4242", decoded.parameters[0])
        assertEquals("mock-switchboard.example:1864", decoded.parameters[1])
        assertEquals("mock-token", decoded.parameters[3])
    }

    @Test
    fun switchboardMessageFrameCarriesAcknowledgementAndUtf8PayloadLength() {
        val payload = Msnp18Protocol.textPayload("mock café")
        val command = Msnp18Protocol.switchboardMessage(11, payload)
        val header = String(MsnpFrameCodec.encode(command), StandardCharsets.UTF_8)
            .substringBefore("\r\n")

        assertEquals(listOf("A", payload.size.toString()), command.parameters)
        assertEquals("MSG 11 A ${payload.size}", header)
    }

    @Test
    fun configurationFramesConsumeTheirPayloadBeforeTheNextCommand() {
        val policy = "<Policies><Policy>mock server fixture</Policy></Policies>".toByteArray()
        val configuration = MsnpFrameCodec.encode(
            MsnpCommand(
                verb = "GCF",
                transactionId = 0,
                parameters = listOf(policy.size.toString()),
                payload = policy,
            ),
        )
        val version = MsnpFrameCodec.encode(Msnp18Protocol.version(transactionId = 2))

        val decoded = MsnpFrameDecoder().feed(configuration + version)

        assertEquals(listOf("GCF", "VER"), decoded.map(MsnpCommand::verb))
        assertArrayEquals(policy, decoded.first().payload)
    }

    @Test
    fun notificationEventConsumesItsNumericLengthAsPayloadMetadata() {
        val payload = "<NOTIFICATION>mock data</NOTIFICATION>".toByteArray()
        val frame = "NOT ${payload.size}\r\n".toByteArray() + payload

        val decoded = MsnpFrameDecoder().feed(frame).single()

        assertEquals(null, decoded.transactionId)
        assertEquals(listOf(payload.size.toString()), decoded.parameters)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun presenceCodesFollowMsnpPriorityStates() {
        assertEquals("NLN", Presence.Online.msnpCode)
        assertEquals(Presence.Busy, Presence.fromMsnpCode("BSY"))
        assertEquals(Presence.Away, Presence.fromMsnpCode("IDL"))
        assertEquals(Presence.Offline, Presence.fromMsnpCode("FLN"))
    }

    @Test
    fun presencePublishesAValidDisplayPictureObjectAndClientCapabilities() {
        val bytes = mockDisplayPictureBytes()
        val picture = MsnDisplayPicture.create(MockServerData.ACCOUNT_EMAIL, bytes)
        val command = Msnp18Protocol.changePresence(12, Presence.Online, picture)

        assertEquals(Msnp18Protocol.CLIENT_IDENTITY, command.parameters[1])
        assertEquals(0x90L, (Msnp18Protocol.CLIENT_ID ushr 24) and 0xff)
        assertEquals(
            (0x90L shl 24) or Msnp18Protocol.BASE_CAPABILITIES.toLong(),
            Msnp18Protocol.CLIENT_ID,
        )
        assertEquals(
            "${Msnp18Protocol.CLIENT_ID}:${Msnp18Protocol.EXTENDED_CAPABILITIES}",
            Msnp18Protocol.CLIENT_IDENTITY,
        )
        assertEquals(0x4802C, Msnp18Protocol.BASE_CAPABILITIES)
        assertEquals(0x8000, Msnp18Protocol.BASE_CAPABILITIES and 0x8000)
        assertEquals(0x40000, Msnp18Protocol.BASE_CAPABILITIES and 0x40000)
        assertEquals(0x400, Msnp18Protocol.EXTENDED_CAPABILITIES)
        assertEquals(
            "Handwriting GIF and ISF capabilities must be advertised",
            0x0C,
            Msnp18Protocol.BASE_CAPABILITIES and (0x04 or 0x08),
        )
        assertEquals(
            "Handwriting GIF and ISF capabilities must be advertised",
            0,
            Msnp18Protocol.BASE_CAPABILITIES and 0,
        )
        assertEquals(
            "Activity and game invitations must not be advertised",
            0,
            Msnp18Protocol.BASE_CAPABILITIES and 0x100,
        )
        assertEquals(
            "Conversation-window file transfers must not be advertised",
            0,
            Msnp18Protocol.EXTENDED_CAPABILITIES and 0x20000,
        )
        assertEquals(picture, MsnDisplayPicture.parse(command.parameters[2]))
        assertTrue(picture.matches(bytes))
        assertFalse(picture.matches(bytes + 0))
    }

    @Test
    fun publishesPublicAndPrivateMsnp18EndpointCapabilities() {
        val publicCommand = Msnp18Protocol.endpointCapabilities(13)
        val privateCommand = Msnp18Protocol.privateEndpointData(
            transactionId = 14,
            presence = Presence.Busy,
            endpointName = "Aral's téléphone & <tablet>",
        )

        assertEquals(
            "<EndpointData><Capabilities>${Msnp18Protocol.ENDPOINT_CAPABILITIES}</Capabilities></EndpointData>",
            requireNotNull(publicCommand.payload).toString(StandardCharsets.UTF_8),
        )
        assertEquals(
            "<PrivateEndpointData><EpName>Aral&apos;s téléphone &amp; &lt;tablet&gt;</EpName><Idle>false</Idle>" +
                "<ClientType>1</ClientType><State>BSY</State></PrivateEndpointData>",
            requireNotNull(privateCommand.payload).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun nudgeUsesTheMsnpDatacastPayload() {
        val payload = String(Msnp18Protocol.nudgePayload(), StandardCharsets.UTF_8)

        assertTrue(payload.contains("Content-Type: text/x-msnmsgr-datacast"))
        assertTrue(payload.endsWith("ID: 1\r\n"))
    }

    @Test
    fun initialContactListGroupsMockContactsForPresenceSubscription() {
        val contacts = MockServerData.contacts().take(2)
        val command = Msnp18Protocol.initialContactList(
            transactionId = 9,
            emails = contacts.map { it.email },
        )
        val payload = requireNotNull(command.payload)
        val xml = String(payload, StandardCharsets.UTF_8)

        assertEquals("ADL", command.verb)
        assertEquals(listOf(payload.size.toString()), command.parameters)
        assertTrue(xml.startsWith("<ml l=\"1\"><d n=\"msngr.dev\">"))
        contacts.forEach { contact ->
            assertTrue(
                xml.contains(
                    "<c n=\"${contact.email.substringBefore('@')}\" l=\"3\" t=\"1\" />",
                ),
            )
        }
        assertTrue(xml.endsWith("</d></ml>"))
    }

    @Test
    fun emptyInitialContactListStillCompletesTheServerLoginSync() {
        val command = Msnp18Protocol.initialContactList(transactionId = 10, emails = emptyList())

        assertEquals("<ml l=\"1\"></ml>", requireNotNull(command.payload).toString(Charsets.UTF_8))
    }

    @Test
    fun blockAndUnblockUseIncrementalMsnpMembershipCommands() {
        val email = "friend@example.com"
        val removeAllow = Msnp18Protocol.removeFromContactList(
            transactionId = 20,
            email = email,
            list = Msnp18Protocol.ALLOW_LIST,
        )
        val addBlock = Msnp18Protocol.addToContactList(
            transactionId = 21,
            email = email,
            list = Msnp18Protocol.BLOCK_LIST,
        )

        assertEquals("RML", removeAllow.verb)
        assertEquals("<ml><d n=\"example.com\"><c n=\"friend\" l=\"2\" t=\"1\" /></d></ml>",
            requireNotNull(removeAllow.payload).toString(StandardCharsets.UTF_8))
        assertEquals("ADL", addBlock.verb)
        assertEquals("<ml><d n=\"example.com\"><c n=\"friend\" l=\"4\" t=\"1\" /></d></ml>",
            requireNotNull(addBlock.payload).toString(StandardCharsets.UTF_8))
    }

    @Test
    fun initialMembershipListKeepsBlockedContactsOutOfTheAllowList() {
        val command = Msnp18Protocol.initialContactMemberships(
            transactionId = 22,
            memberships = listOf("blocked@example.com" to
                (Msnp18Protocol.FORWARD_LIST or Msnp18Protocol.BLOCK_LIST)),
        )

        assertTrue(
            requireNotNull(command.payload).toString(StandardCharsets.UTF_8)
                .contains("<c n=\"blocked\" l=\"5\" t=\"1\" />"),
        )
    }

    @Test
    fun nicknameUsesPrpMfnAndEscapesWireSeparators() {
        val command = Msnp18Protocol.changeNickname(
            transactionId = 11,
            nickname = "  Mock 100%\nProfile  ",
        )

        assertEquals("PRP", command.verb)
        assertEquals(listOf("MFN", "Mock%20100%25%20Profile"), command.parameters)
        assertEquals(
            "PRP 11 MFN Mock%20100%25%20Profile\r\n",
            MsnpFrameCodec.encode(command).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun personalMessageUsesUuxWithUtf8ByteLengthAndEscapedXml() {
        val command = Msnp18Protocol.personalMessage(
            transactionId = 7,
            statusMessage = "Café & <friends>",
        )
        val payload = requireNotNull(command.payload)
        val encoded = MsnpFrameCodec.encode(command)

        assertEquals("UUX", command.verb)
        assertEquals(listOf(payload.size.toString()), command.parameters)
        assertTrue(
            String(payload, StandardCharsets.UTF_8)
                .contains("<PSM>Café &amp; &lt;friends&gt;</PSM>"),
        )
        assertTrue(
            String(encoded, StandardCharsets.UTF_8)
                .startsWith("UUX 7 ${payload.size}\r\n"),
        )
    }

    @Test
    fun parsesPersonalMessageFromUbxPayload() {
        val payload = """
            <Data><PSM>Ready &amp; waiting &lt;3</PSM><CurrentMedia></CurrentMedia></Data>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertEquals("Ready & waiting <3", Msnp18Protocol.parsePersonalMessage(payload))
        assertEquals(
            "",
            Msnp18Protocol.parsePersonalMessage("<Data><PSM/></Data>".toByteArray()),
        )
    }

    @Test
    fun numericEntitiesRestoreTheLiveContactGradient() {
        val payload = "<Data><PSM>[c&#x3D;04]Msngr - Coming to Android[/c&#x3D;12]</PSM></Data>"
            .toByteArray(StandardCharsets.UTF_8)
        val status = Msnp18Protocol.parsePersonalMessage(payload)

        assertEquals("[c=04]Msngr - Coming to Android[/c=12]", status)
        val parsed = MessengerPlusTextCodec.parse(status)
        assertEquals("Msngr - Coming to Android", parsed.text)
        assertEquals(MessengerPlusTextCodec.paletteColor(4)?.argb, parsed.glyphs.first().style.foreground?.argb)
        assertEquals(MessengerPlusTextCodec.paletteColor(12)?.argb, parsed.glyphs.last().style.foreground?.argb)
        assertTrue(parsed.glyphs.map { it.style.foreground?.argb }.distinct().size > 2)
    }

    @Test
    fun personalMessageDecodesDecimalHexAndSupplementaryUnicodeEntities() {
        val payload = "<Data><PSM>&#91;c&#61;&#35;FF0000&#93;Caf&#233; &#x1F600; &quot;hi&quot; &apos;bye&apos; &amp; &lt;3[/c]</PSM></Data>"
            .toByteArray(StandardCharsets.UTF_8)

        assertEquals("[c=#FF0000]Café 😀 \"hi\" 'bye' & <3[/c]", Msnp18Protocol.parsePersonalMessage(payload))
    }

    @Test
    fun personalMessageDecodesOnlyOneEntityLayer() {
        val payload = "<Data><PSM>&amp;#x3D; &#38;#61; &amp;lt; &#x26;lt; &amp;amp; &#38;amp;</PSM></Data>"
            .toByteArray(StandardCharsets.UTF_8)

        assertEquals("&#x3D; &#61; &lt; &lt; &amp; &amp;", Msnp18Protocol.parsePersonalMessage(payload))
        val literal = "[n][c&#x3D;04]literal[/c][/n] &lt;3"
        assertEquals(literal, Msnp18Protocol.parsePersonalMessage(
            requireNotNull(Msnp18Protocol.personalMessage(1, literal).payload),
        ))
    }

    @Test
    fun personalMessagePreservesMalformedAndInvalidNumericEntities() {
        val literal = "&#x110000; &#1114112; &#xD800; &#55296; &#999999999999999; &#xFFFFFFFF; &#xZZ; &#; &#-1; &#61 &unknown;"
        val payload = "<Data><PSM>$literal</PSM></Data>".toByteArray(StandardCharsets.UTF_8)

        assertEquals(literal, Msnp18Protocol.parsePersonalMessage(payload))
    }

    @Test
    fun numericEntitiesPreserveMessengerPlusBinaryControls() {
        val payload = "<Data><PSM>&#3;04Red&#x3;12Blue</PSM></Data>".toByteArray(StandardCharsets.UTF_8)
        val status = Msnp18Protocol.parsePersonalMessage(payload)

        assertEquals("\u000304Red\u000312Blue", status)
        assertEquals("RedBlue", MessengerPlusTextCodec.parse(status).text)
    }
}

internal fun mockDisplayPictureBytes(): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    0x4d, 0x53, 0x4e, 0x50, 0x2d, 0x4d, 0x4f, 0x43, 0x4b,
)
