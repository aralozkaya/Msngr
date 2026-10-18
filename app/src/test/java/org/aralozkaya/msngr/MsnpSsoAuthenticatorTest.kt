package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.buildMsnpSsoRequest
import org.aralozkaya.msngr.data.parseMsnpSsoTickets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

class MsnpSsoAuthenticatorTest {
    @Test
    fun ssoRequestIsWellFormedAndEscapesCredentials() {
        val request = buildMsnpSsoRequest(
            email = "mock&user@example.com",
            password = "mock<password>",
            policy = "MBI_KEY_OLD",
        )
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(request.byteInputStream(StandardCharsets.UTF_8))

        assertTrue(request.startsWith("<?xml"))
        assertFalse(request.contains("mock&user"))
        assertFalse(request.contains("mock<password>"))
        assertEquals(
            "mock&user@example.com",
            document.getElementsByTagNameNS("*", "Username").item(0).textContent,
        )
        assertEquals(7, document.getElementsByTagNameNS("*", "RequestSecurityToken").length)
    }

    @Test
    fun extractsMessengerAndAddressBookTicketsFromMockSsoResponse() {
        val response = """
            <Envelope xmlns:wst="http://schemas.xmlsoap.org/ws/2004/04/trust"
              xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/03/addressing"
              xmlns:wsse="http://schemas.xmlsoap.org/ws/2003/06/secext">
              <wst:RequestSecurityTokenResponse>
                <wsa:Address>messengerclear.live.com</wsa:Address>
                <wsse:BinarySecurityToken>mock-messenger-ticket</wsse:BinarySecurityToken>
                <wst:BinarySecret>mock-secret</wst:BinarySecret>
              </wst:RequestSecurityTokenResponse>
              <wst:RequestSecurityTokenResponse>
                <wsa:Address>contacts.msn.com</wsa:Address>
                <wsse:BinarySecurityToken>t=mock-address-book-ticket&amp;p=</wsse:BinarySecurityToken>
              </wst:RequestSecurityTokenResponse>
              <wst:RequestSecurityTokenResponse>
                <wsa:Address>storage.live.com</wsa:Address>
                <wsse:BinarySecurityToken>t=mock-storage-ticket&amp;p=</wsse:BinarySecurityToken>
              </wst:RequestSecurityTokenResponse>
            </Envelope>
        """.trimIndent()

        val tickets = parseMsnpSsoTickets(response)

        assertEquals("mock-messenger-ticket", tickets["messengerclear.live.com"])
        assertEquals("t=mock-address-book-ticket&p=", tickets["contacts.msn.com"])
        assertEquals("t=mock-storage-ticket&p=", tickets["storage.live.com"])
    }
}
