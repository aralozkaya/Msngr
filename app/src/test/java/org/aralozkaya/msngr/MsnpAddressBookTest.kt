package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.buildAddressBookRequest
import org.aralozkaya.msngr.data.buildMembershipRequest
import org.aralozkaya.msngr.data.parseAddressBook
import org.aralozkaya.msngr.data.parseBlockedMemberships
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

class MsnpAddressBookTest {
    @Test
    fun requestIsWellFormedAndEscapesTheMockTicket() {
        val request = buildAddressBookRequest("t=mock&ticket&p=")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(request.byteInputStream(StandardCharsets.UTF_8))

        assertTrue(request.startsWith("<?xml"))
        assertFalse(request.contains("t=mock&ticket"))
        assertEquals(
            "t=mock&ticket&p=",
            document.getElementsByTagNameNS("*", "TicketToken").item(0).textContent,
        )
        assertEquals(1, document.getElementsByTagNameNS("*", "ABFindContactsPaged").length)
    }

    @Test
    fun parsesForwardListContactsFromMockAddressBookData() {
        val fixtures = MockServerData.contacts().take(2)
        val response = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ABFindContactsPagedResponse xmlns="http://www.msn.com/webservices/AddressBook">
                  <ABFindContactsPagedResult>
                    <Contacts>
                      <Contact>
                        <contactId>11111111-2222-3333-4444-555555555555</contactId>
                        <contactInfo>
                          <annotations><Annotation><Name>AB.NickName</Name><Value>${fixtures[0].nickname}</Value></Annotation></annotations>
                          <contactType>Regular</contactType>
                          <passportName>${fixtures[0].email}</passportName>
                          <displayName>Server display name</displayName>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                      <Contact>
                        <contactInfo>
                          <contactType>Regular</contactType>
                          <passportName>${fixtures[1].email}</passportName>
                          <displayName>${fixtures[1].nickname}</displayName>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                      <Contact>
                        <contactInfo><contactType>Me</contactType><passportName>${MockServerData.ACCOUNT_EMAIL}</passportName><displayName>Mock Account</displayName></contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                      <Contact>
                        <contactInfo><contactType>Regular</contactType><passportName>deleted@example.com</passportName></contactInfo>
                        <fDeleted>true</fDeleted>
                      </Contact>
                      <Contact>
                        <contactInfo>
                          <contactType>Regular</contactType>
                          <passportName>mail-only@example.com</passportName>
                          <isMessengerUser>false</isMessengerUser>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                    </Contacts>
                  </ABFindContactsPagedResult>
                </ABFindContactsPagedResponse>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val addressBook = parseAddressBook(response, MockServerData.ACCOUNT_EMAIL)
        val contacts = addressBook.contacts

        assertEquals(fixtures.map { it.email }, contacts.map { it.email })
        assertEquals(fixtures.map { it.nickname }, contacts.map { it.nickname })
        assertTrue(contacts.all { it.presence == Presence.Offline })
        assertEquals("Mock Account", addressBook.ownerDisplayName)
        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            addressBook.contactStorageIds[fixtures[0].email.lowercase()],
        )
    }

    @Test
    fun requestsAndParsesBlockedMessengerMemberships() {
        val request = buildMembershipRequest("t=mock&ticket&p=")
        assertTrue(request.contains("<FindMembership"))
        assertTrue(request.contains("<ServiceType>Messenger</ServiceType>"))
        assertTrue(request.contains("t=mock&amp;ticket&amp;p="))

        val response = """
            <FindMembershipResponse xmlns="http://www.msn.com/webservices/AddressBook"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <FindMembershipResult><Services><Service><Memberships>
                <Membership><MemberRole>Allow</MemberRole><Members>
                  <Member xsi:type="PassportMember"><PassportName>allowed@example.com</PassportName></Member>
                </Members></Membership>
                <Membership><MemberRole>Block</MemberRole><Members>
                  <Member xsi:type="PassportMember"><DisplayName>Blocked Friend</DisplayName>
                    <Deleted>false</Deleted><PassportName>blocked@example.com</PassportName></Member>
                  <Member xsi:type="PassportMember"><Deleted>true</Deleted>
                    <PassportName>deleted@example.com</PassportName></Member>
                </Members></Membership>
              </Memberships></Service></Services></FindMembershipResult>
            </FindMembershipResponse>
        """.trimIndent()

        val contacts = parseBlockedMemberships(response, MockServerData.ACCOUNT_EMAIL)

        assertEquals(listOf("blocked@example.com"), contacts.map { it.email })
        assertEquals("Blocked Friend", contacts.single().nickname)
        assertTrue(contacts.single().isBlocked)
        assertFalse(contacts.single().isForwardListContact)
    }
}
