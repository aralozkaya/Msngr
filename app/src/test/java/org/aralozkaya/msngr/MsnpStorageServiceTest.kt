package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.MsnpStorageService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

class MsnpStorageServiceTest {
    @Test
    fun getProfileRequestUsesStorageTicketAndMsnp18Namespace() {
        val xml = MsnpStorageService.buildGetProfileRequest("t=secret&value=p")
        val document = parse(xml)

        assertEquals(
            "t=secret&value=p",
            document.getElementsByTagNameNS("*", "TicketToken").item(0).textContent,
        )
        assertEquals(1, document.getElementsByTagNameNS("*", "GetProfile").length)
        assertFalse(xml.contains("t=secret&value=p"))
    }

    @Test
    fun parsesOnlyTheFullSizeUserTileStream() {
        val profile = MsnpStorageService.parseProfile(
            """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body><GetProfileResponse xmlns="http://www.msn.com/webservices/storage/2008">
                <GetProfileResult><ResourceID>cid!106</ResourceID><ExpressionProfile>
                  <ResourceID>cid!118</ResourceID><DisplayName>Alice</DisplayName>
                  <PersonalStatus>Hello</PersonalStatus><Photo><ResourceID>cid!205</ResourceID>
                    <DocumentStreams>
                      <DocumentStream><DocumentStreamName>UserTileSmall</DocumentStreamName>
                        <DocumentStreamType>Named</DocumentStreamType><PreAuthURL>https://small</PreAuthURL>
                      </DocumentStream>
                      <DocumentStream><DocumentStreamName>UserTileStatic</DocumentStreamName>
                        <DocumentStreamType>UserTileStatic</DocumentStreamType><PreAuthURL>https://full</PreAuthURL>
                      </DocumentStream>
                    </DocumentStreams>
                  </Photo>
                </ExpressionProfile></GetProfileResult>
              </GetProfileResponse></soap:Body>
            </soap:Envelope>
            """.trimIndent(),
        )

        assertEquals("cid!106", profile.profileResourceId)
        assertEquals("cid!118", profile.expressionProfileResourceId)
        assertEquals("cid!205", profile.photoResourceId)
        assertEquals("Alice", profile.displayName)
        assertEquals("Hello", profile.personalStatus)
        assertEquals("https://full", profile.displayPictureUrl)
    }

    @Test
    fun updateDocumentCarriesExactImageBytesAsUserTileStatic() {
        val image = byteArrayOf(0, 1, 2, 3, 0x7f)
        val xml = MsnpStorageService.buildUpdateDocumentRequest(
            "t=storage&p=",
            "cid!205",
            image,
        )
        val document = parse(xml)

        assertEquals("cid!205", document.getElementsByTagNameNS("*", "ResourceID").item(0).textContent)
        assertEquals(
            "UserTileStatic",
            document.getElementsByTagNameNS("*", "DocumentStreamType").item(0).textContent,
        )
        assertArrayEquals(
            image,
            java.util.Base64.getDecoder().decode(
                document.getElementsByTagNameNS("*", "Data").item(0).textContent,
            ),
        )
        assertTrue(xml.contains("xsi:type=\"Photo\""))
    }

    @Test
    fun createDocumentLinksAUserTileToTheExpressionProfile() {
        val image = byteArrayOf(4, 3, 2, 1)
        val xml = MsnpStorageService.buildCreateDocumentRequest(
            "t=storage&p=",
            "12345!106",
            image,
        )
        val document = parse(xml)

        assertEquals(
            "12345",
            document.getElementsByTagNameNS("*", "Name").item(0).textContent,
        )
        assertEquals(
            "/MyProfile/ExpressionProfile",
            document.getElementsByTagNameNS("*", "RelationshipName").item(0).textContent,
        )
        assertEquals(
            "ProfilePhoto",
            document.getElementsByTagNameNS("*", "relationshipName").item(0).textContent,
        )
        assertArrayEquals(
            image,
            java.util.Base64.getDecoder().decode(
                document.getElementsByTagNameNS("*", "Data").item(0).textContent,
            ),
        )
    }

    @Test
    fun deleteRelationshipsRemovesPhotoFromExpressionProfile() {
        val xml = MsnpStorageService.buildDeletePhotoRelationshipRequest(
            "t=storage&p=",
            "12345!118",
            "12345!205",
        )
        val document = parse(xml)

        assertEquals(
            "12345!118",
            document.getElementsByTagNameNS("*", "sourceHandle")
                .item(0)
                .childNodes
                .let { nodes ->
                    (0 until nodes.length)
                        .map { nodes.item(it) }
                        .first { it.localName == "ResourceID" }
                        .textContent
                },
        )
        assertEquals(
            "12345!205",
            document.getElementsByTagNameNS("*", "ObjectHandle")
                .item(0)
                .textContent
                .trim(),
        )
    }

    @Test
    fun parsesCreatedPhotoResourceId() {
        val resourceId = MsnpStorageService.parseCreatedDocumentResourceId(
            """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <CreateDocumentResponse xmlns="http://www.msn.com/webservices/storage/2008">
                  <CreateDocumentResult>12345!205</CreateDocumentResult>
                </CreateDocumentResponse>
              </soap:Body>
            </soap:Envelope>
            """.trimIndent(),
        )

        assertEquals("12345!205", resourceId)
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
}
