package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupInvitation
import org.aralozkaya.msngr.data.buildAddContactRequest
import org.aralozkaya.msngr.data.buildCreateGroupRequest
import org.aralozkaya.msngr.data.buildGroupConnectionRequest
import org.aralozkaya.msngr.data.mergeMessengerMemberships
import org.aralozkaya.msngr.data.parseAddressBook
import org.aralozkaya.msngr.data.parseMessengerMemberships
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class RequestMessagesTest {
    private val owner = "owner@example.test"
    private val peer = "peer@example.test"
    private val groupA = "00000000-0000-0000-0009-abc000000001"
    private val groupB = "00000000-0000-0000-0009-abc000000002"
    private val groupMessage = "Group-A: Merhaba & <hello> \"friends\" — ğüşi 🙂"
    private val contactMessage = "Contact-C: Merhaba & <contact> \"hello\" — ğüşi 🙂"
    private fun fixture(name: String) = javaClass.getResource("/request-messages/$name.xml")!!.readText()
    private fun document(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream())
    private fun operation(xml: String, action: String) = document(xml).getElementsByTagNameNS("*", action).item(0) as Element
    private fun shape(element: Element): String = buildString {
        append(element.localName)
        append('[')
        for (i in 0 until element.childNodes.length) {
            val child = element.childNodes.item(i)
            if (child is Element) append(shape(child))
            else if (!child.textContent.isNullOrBlank()) append(child.textContent)
        }
        append(']')
    }

    @Test fun outgoingMessagesMatchCapturedNativeSoapLocations() {
        val contact = buildAddContactRequest("ticket", peer, "owner", message = contactMessage)
        assertEquals(shape(operation(fixture("contact-add"), "ABContactAdd")), shape(operation(contact, "ABContactAdd")))
        val group = buildGroupConnectionRequest("ticket", groupA, "11111111-2222-4333-8444-abc000000017", 3, message = groupMessage)
        assertEquals(shape(operation(fixture("group-invite"), "ManageWLConnection")), shape(operation(group, "ManageWLConnection")))
        assertFalse(buildAddContactRequest("ticket", peer, "Owner").contains("PendingAnnotations"))
        assertFalse(buildGroupConnectionRequest("ticket", groupA, groupB, 3, message = " \n ").contains("<annotations>"))
        assertFalse(buildCreateGroupRequest("ticket", "Group", owner).contains("InviteMessage"))
    }

    @Test fun messageTextPreservesWhitespaceNewlinesXmlCharactersAndUnicode() {
        val text = "  Hello & <friends> \"' ğüşi 🙂\n            Indented second line  "
        for (xml in listOf(buildAddContactRequest("ticket", peer, "Owner", message = text),
            buildGroupConnectionRequest("ticket", groupA, groupB, 3, message = text))) {
            assertEquals(text, document(xml).getElementsByTagNameNS("*", "Value").item(0).textContent)
        }
    }

    @Test fun concurrentCapturedGroupsReadTheirOwnInviterMessageRatherThanTheOutboundAnnotation() {
        val snapshot = parseAddressBook(fixture("two-pending-groups"), peer)
        assertTrue(snapshot.contacts.none(Contact::isGroup))
        val invitations = snapshot.groupInvitations.associateBy(GroupInvitation::id)
        assertEquals(2, invitations.size)
        assertEquals(groupMessage, invitations.getValue("$groupA@live.com").message)
        assertEquals("Group-B: concurrent second invitation", invitations.getValue("$groupB@live.com").message)
        invitations.values.forEach {
            assertEquals(owner, it.inviterEmail)
            assertEquals("owner", it.inviterName)
        }
    }

    @Test fun unrelatedNetworkRelationshipsCannotSupplyCircleMessages() {
        val xml = fixture("two-pending-groups").replace("<ns1:NetworkInfo>", """<ns1:NetworkInfo>
            <ns1:DomainId>1</ns1:DomainId><ns1:RelationshipType>3</ns1:RelationshipType>
            <ns1:InviterMessage>Unrelated contact message</ns1:InviterMessage></ns1:NetworkInfo><ns1:NetworkInfo>""")
        assertEquals(groupMessage, parseAddressBook(xml, peer).groupInvitations.first { it.id == "$groupA@live.com" }.message)
        val wrongCircle = xml.replace("<ns1:DomainTag>${groupA.uppercase()}</ns1:DomainTag>",
            "<ns1:DomainTag>${groupB.uppercase()}</ns1:DomainTag>")
        assertNull(parseAddressBook(wrongCircle, peer).groupInvitations.first { it.id == "$groupA@live.com" }.message)
    }

    @Test fun contactAnnotationIsScopedToThePendingPassportMember() {
        val xml = """<Envelope><FindMembershipResult><Memberships>
            <Membership><MemberRole>Reverse</MemberRole><Members><Member>
              <Type>Passport</Type><State>Accepted</State><MembershipId>reverse</MembershipId><PassportName>$peer</PassportName>
              <Annotations><Annotation><Name>MSN.IM.InviteMessage</Name><Value>Old unrelated request</Value></Annotation></Annotations>
            </Member></Members></Membership>
            <Membership><MemberRole>Pending</MemberRole><Members><Member>
              <Type>Passport</Type><State>Accepted</State><MembershipId>pending</MembershipId><PassportName>$peer</PassportName>
              <Annotations><Annotation><Name>Other</Name><Value>Wrong field</Value></Annotation>
                <Annotation><Name>MSN.IM.InviteMessage</Name><Value>  Hello &amp; &lt;friend&gt; 🙂
Second line  </Value></Annotation></Annotations>
            </Member></Members></Membership></Memberships></FindMembershipResult></Envelope>"""
        assertEquals("  Hello & <friend> 🙂\nSecond line  ", parseMessengerMemberships(xml, owner).requests.single().message)
    }

    @Test fun capturedContactRequestsWithoutServerTextNeverBorrowConcurrentGroupMessages() {
        for (name in listOf("overlap-memberships", "isolated-contact-memberships")) {
            val memberships = parseMessengerMemberships(fixture(name), peer)
            assertEquals(owner, memberships.requests.single().email)
            assertNull(memberships.requests.single().message)
            val snapshot = mergeMessengerMemberships(parseAddressBook(fixture("two-pending-groups"), peer), memberships)
            assertNull(snapshot.contactRequests.single().message)
            assertEquals(2, snapshot.groupInvitations.size)
            assertEquals(groupMessage, snapshot.groupInvitations.first { it.id == "$groupA@live.com" }.message)
        }
    }
}
