package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.CircleSegmentAssembler
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.data.MsnpCircles
import org.aralozkaya.msngr.data.parseAddressBook
import org.aralozkaya.msngr.data.parseCircleEnvelope
import org.aralozkaya.msngr.data.parseCircleMembers
import org.aralozkaya.msngr.data.parseCirclePublication
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnInkCodec
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpFrameCodec
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsnpCirclesTest {
    private val group = Contact("00000000-0000-0000-0009-123456789abc@live.com", "Test",
        "00000000-0000-0000-0009-123456789abc@live.com", Presence.Online, isGroup = true)

    @Test fun longWinkSignaturesSurviveCircleSegmentationAndKeepOriginalMetadata() {
        val bytes = javaClass.getResourceAsStream("/winks/screen-punch.cab")!!.use { it.readBytes() }
        val metadata = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("sender@example.com", bytes, 8)
            .copy(extraAttributes = mapOf("stamp" to "signature".repeat(1500)))
        var transaction = 0
        val commands = MsnpCircles.winkMessages(group.id, metadata.creator, "endpoint", metadata) { ++transaction }
        assertTrue(commands.size > 1)
        assertTrue(commands.all { it.payload!!.size <= MsnpCircles.MAX_SDG_PAYLOAD_BYTES })
        val assembler = CircleSegmentAssembler()
        val envelope = commands.mapNotNull { assembler.accept(parseCircleEnvelope(it.payload!!)!!) }.single()
        assertEquals("Wink", envelope.headers["message-type"])
        val offer = org.aralozkaya.msngr.data.parseSwitchboardMime(
            "Content-Type: ${Msnp18Protocol.NUDGE_CONTENT_TYPE}\r\n\r\n".toByteArray() + envelope.body,
        ) as org.aralozkaya.msngr.data.SwitchboardMimeMessage.WinkOffer
        assertEquals(metadata, offer.metadata)
    }

    @Test fun unicodeMessagesUseUtf8LengthsAndSurviveEveryTcpBoundary() {
        val message = MsnpCircles.message(11, group.id, "member@example.com", "endpoint", "Merhaba, dünya 日本語")
        val wire = MsnpFrameCodec.encode(message)
        for (boundary in 1 until wire.size) {
            val decoder = MsnpFrameDecoder()
            val frames = decoder.feed(wire.copyOfRange(0, boundary)) + decoder.feed(wire.copyOfRange(boundary, wire.size))
            assertEquals(1, frames.size)
            val envelope = requireNotNull(parseCircleEnvelope(frames.single().payload!!))
            assertEquals(group.id, envelope.groupId)
            assertEquals("member@example.com", envelope.senderEmail)
            assertEquals("Merhaba, dünya 日本語", envelope.body.toString(Charsets.UTF_8))
            assertEquals(envelope.body.size.toString(), envelope.headers["content-length"])
        }
    }

    @Test fun publicationFramesDoNotConsumeTheNextCommand() {
        val payload = "Routing: 1.0\r\nFrom: 9:${group.id}\r\nTo: 1:me@example.com\r\n\r\nReliability: 1.0\r\n\r\nPublication: 1.0\r\n\r\n<circle><roster><id>IM</id><user><id>1:member@example.com</id></user></roster></circle>".toByteArray()
        val wire = "NFY PUT ${payload.size}\r\n".toByteArray() + payload + "QNG 40\r\n".toByteArray()
        val frames = MsnpFrameDecoder().feed(wire)
        assertEquals(listOf("NFY", "QNG"), frames.map { it.verb })
        val publication = parseCirclePublication(parseCircleEnvelope(frames[0].payload!!)!!.body)!!
        assertEquals(listOf("member@example.com"), publication.roster)
        assertNull(parseCircleEnvelope("broken".toByteArray()))
    }

    @Test fun subscriptionUsesCircleNetworkAndPreservesBlocking() {
        val payload = MsnpCircles.subscribe(1, listOf(group.copy(isBlocked = true))).payload!!.toString(Charsets.UTF_8)
        assertTrue(payload.contains("t=\"9\" l=\"5\""))
        assertTrue(payload.contains("<ml l=\"1\">"))
        val ticket = "<SignedTicket>\n  unchanged\n</SignedTicket>"
        assertEquals(ticket, MsnBase64.decode(MsnpCircles.authenticate(2, ticket).parameters[2])!!.toString(Charsets.UTF_8))
    }

    @Test fun addressBookSeparatesCirclesFromContactCategoriesAndPendingInvitations() {
        fun circle(id: String, state: String, deleted: Boolean = false) = """
            <CircleInverseInfo><Content><Handle><Id>$id</Id></Handle><Info><HostedDomain>live.com</HostedDomain>
            <DisplayName>Test &amp; friends</DisplayName></Info></Content><PersonalInfo><CirclePersonalMembership>
            <State>$state</State></CirclePersonalMembership></PersonalInfo><Deleted>$deleted</Deleted></CircleInverseInfo>
        """
        val xml = """<ABFindContactsPagedResult><Groups><Group><name>Contact category</name></Group></Groups>
            <CircleResult><Circles>${circle(group.id.substringBefore('@'), "Accepted")}
            ${circle("00000000-0000-0000-0009-123456789abd", "WaitingResponse")}
            ${circle("00000000-0000-0000-0009-123456789abe", "Accepted", true)}</Circles>
            <CircleTicket>&lt;SignedTicket&gt; value &lt;/SignedTicket&gt;</CircleTicket></CircleResult></ABFindContactsPagedResult>"""
        val snapshot = parseAddressBook(xml, "me@example.com")
        assertEquals(1, snapshot.contacts.size)
        assertEquals(group.id, snapshot.contacts.single().id)
        assertEquals("Test & friends", snapshot.contacts.single().nickname)
        assertTrue(snapshot.contacts.single().isGroup)
        assertEquals("<SignedTicket> value </SignedTicket>", snapshot.circleTicket)
    }

    @Test fun memberAddressBookRetainsThePictureStorageId() {
        val uuid = "00000000-0000-0000-0000-123456789abc"
        val members = parseCircleMembers("""<Contacts><Contact><contactId>$uuid</contactId>
            <contactInfo><contactType>Live</contactType><RelationshipState>3</RelationshipState>
            <passportName>Member@example.com</passportName><displayName>Member</displayName></contactInfo>
            </Contact></Contacts>""")
        assertEquals(uuid, members.single().storageId)
        assertEquals("member@example.com", members.single().email)
    }

    @Test fun memberAddressBookParsesCircleAccessLevelsWithoutInventingMissingRoles() {
        fun member(email: String, role: String?, state: Int = 3, deleted: Boolean = false) = """
            <Contact><contactInfo><contactType>Live</contactType><passportName>$email</passportName>
            <NetworkInfoList>
              <NetworkInfo><RelationshipType>1</RelationshipType><RelationshipState>0</RelationshipState>
                <RelationshipRole>1</RelationshipRole></NetworkInfo>
              <NetworkInfo><RelationshipType>5</RelationshipType><RelationshipState>$state</RelationshipState>
                ${role?.let { "<RelationshipRole>$it</RelationshipRole>" }.orEmpty()}
              </NetworkInfo>
            </NetworkInfoList></contactInfo><fDeleted>$deleted</fDeleted></Contact>
        """
        val members = parseCircleMembers("""<Contacts xmlns="http://www.msn.com/webservices/AddressBook">
            ${member("Owner@example.com", "1")}
            ${member("admin@example.com", "2")}
            ${member("user@example.com", "3")}
            ${member("unknown@example.com", "99")}
            ${member("missing@example.com", null)}
            ${member("pending@example.com", "4", state = 2)}
            ${member("deleted@example.com", "1", deleted = true)}
            </Contacts>""")
        assertEquals(listOf("owner@example.com", "admin@example.com", "user@example.com",
            "unknown@example.com", "missing@example.com"), members.map { it.email })
        assertEquals(listOf(GroupMemberRole.Owner, GroupMemberRole.Administrator, GroupMemberRole.User,
            GroupMemberRole.Unknown, GroupMemberRole.Unknown), members.map { it.role })
    }

    @Test fun capturedWlm2009GroupInkSdgPacketDecodesAndRoundTrips() {
        val capturedPayload = (
            "Routing: 1.0\r\n" +
                "To: 9:00000000-0000-0000-0009-3ffc6bdd2e50@live.com;path=IM\r\n" +
                "From: 1:mrlistenerr@escargot.chat;epid={b343262e-a9e9-4335-a76e-d1e6df5923f2}\r\n\r\n" +
                "Reliability: 1.0\r\n" +
                "Stream: 0\r\n" +
                "Segment: 7\r\n\r\n" +
                "Messaging: 1.0\r\n" +
                "Content-Length: 131\r\n" +
                "Content-Type: application/x-ms-ink\r\n" +
                "Content-Transfer-Encoding: 7bit\r\n" +
                "Message-Type: Ink\r\n" +
                "MIME-Version: 1.0\r\n\r\n" +
                "base64:AFscA4CABB0DmgJSAwlIEUSAgAJFyAEZFDIIAIAoAjiGK0IzCADAFgK3bStCFauq00GrqtNBAHeWxYB7QcYKHQ2D+HvT8WSakmiHmEFt+BsD4GwPgbA+BsD4GwOA"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(476, capturedPayload.size)

        val wire = "SDG 80 ${capturedPayload.size}\r\n".toByteArray(Charsets.UTF_8) + capturedPayload
        val command = MsnpFrameDecoder().feed(wire).single()
        assertEquals("SDG", command.verb)
        assertEquals(Integer.valueOf(80), command.transactionId)

        val envelope = requireNotNull(parseCircleEnvelope(command.payload!!))
        assertEquals("00000000-0000-0000-0009-3ffc6bdd2e50@live.com", envelope.groupId)
        assertEquals("mrlistenerr@escargot.chat", envelope.senderEmail)
        assertEquals("7", envelope.reliability["segment"])
        assertEquals("Ink", envelope.headers["message-type"])
        assertEquals(Msnp18Protocol.INK_ISF_CONTENT_TYPE, envelope.headers["content-type"])
        assertEquals("131", envelope.headers["content-length"])

        val drawing = requireNotNull(MsnInkCodec.decodeMimeBody(envelope.headers["content-type"]!!, envelope.body))
        assertEquals(1, drawing.strokes.size)
        assertEquals(0x008000, drawing.strokes.single().colorRgb)
        assertEquals(200, drawing.strokes.single().widthHimetric)

        val outgoing = MsnpCircles.inkMessage(
            transactionId = 81,
            groupEmail = envelope.groupId!!,
            senderEmail = envelope.senderEmail!!,
            endpointId = "b343262e-a9e9-4335-a76e-d1e6df5923f2",
            drawing = drawing,
            segment = 7,
        )
        val outgoingEnvelope = requireNotNull(parseCircleEnvelope(outgoing.payload!!))
        assertEquals("Ink", outgoingEnvelope.headers["message-type"])
        assertEquals(Msnp18Protocol.INK_ISF_CONTENT_TYPE, outgoingEnvelope.headers["content-type"])
        assertEquals("7bit", outgoingEnvelope.headers["content-transfer-encoding"])
        assertEquals("1.0", outgoingEnvelope.headers["mime-version"])
        assertEquals(outgoingEnvelope.body.size.toString(), outgoingEnvelope.headers["content-length"])
        val roundTripped = requireNotNull(MsnInkCodec.decodeMimeBody(outgoingEnvelope.headers["content-type"]!!, outgoingEnvelope.body))
        assertEquals(1, roundTripped.strokes.size)
        assertEquals(0x008000, roundTripped.strokes.single().colorRgb)
    }

    @Test fun multiSegmentCircleInkMessagesReassembleAcrossSequentialReliabilitySegments() {
        val strokes = (0 until 85).map { idx ->
            InkStroke(
                points = (0 until 25).map { pt ->
                    InkPoint(
                        x = 12f + ((idx * 17 + pt * 11) % 320),
                        y = 10f + ((idx * 23 + pt * 7) % 110),
                    )
                },
                colorRgb = if (idx % 2 == 0) 0xFF0000 else 0x0000FF,
                widthHimetric = if (idx % 3 == 0) 200 else 53,
            )
        }
        val largeDrawing = InkDrawing(strokes = strokes)
        var nextTrid = 100
        val commands = MsnpCircles.inkMessages(
            groupEmail = group.id,
            senderEmail = "member@example.com",
            endpointId = "b343262e-a9e9-4335-a76e-d1e6df5923f2",
            drawing = largeDrawing,
            useGif = true,
            startSegment = 10,
            nextTransactionId = { nextTrid++ },
        )
        assertTrue(commands.size >= 2)
        assertTrue(commands.all { it.payload!!.size <= MsnpCircles.MAX_SDG_PAYLOAD_BYTES })

        val assembler = CircleSegmentAssembler()
        var assembledEnvelope: org.aralozkaya.msngr.data.CircleEnvelope? = null
        commands.forEachIndexed { index, cmd ->
            val parsed = requireNotNull(parseCircleEnvelope(cmd.payload!!))
            assertEquals((10 + index).toString(), parsed.reliability["segment"])
            val result = assembler.accept(parsed)
            if (index < commands.lastIndex) {
                assertNull(result)
            } else {
                assembledEnvelope = result
            }
        }
        val finalEnvelope = requireNotNull(assembledEnvelope)
        assertEquals("Ink", finalEnvelope.headers["message-type"])
        assertEquals(Msnp18Protocol.INK_GIF_CONTENT_TYPE, finalEnvelope.headers["content-type"])
        val decoded = requireNotNull(MsnInkCodec.decodeMimeBody(finalEnvelope.headers["content-type"]!!, finalEnvelope.body))
        assertEquals(strokes.size, decoded.strokes.size)
        assertNotNull(decoded.fallbackGifBytes)
    }
}
