package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.data.MsnpCircles
import org.aralozkaya.msngr.data.parseAddressBook
import org.aralozkaya.msngr.data.parseCircleEnvelope
import org.aralozkaya.msngr.data.parseCircleMembers
import org.aralozkaya.msngr.data.parseCirclePublication
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnpFrameCodec
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsnpCirclesTest {
    private val group = Contact("00000000-0000-0000-0009-123456789abc@live.com", "Test",
        "00000000-0000-0000-0009-123456789abc@live.com", Presence.Online, isGroup = true)

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
}
