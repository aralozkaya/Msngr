package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.MsnpWhatsNewService
import org.aralozkaya.msngr.data.WhatsNewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsnpWhatsNewTest {
    @Test
    fun buildsGetContactsRecentActivityRequestWithEscapedTicket() {
        val xml = MsnpWhatsNewService.buildGetContactsRecentActivityRequest(
            ticket = "t=abc&def<1>",
            count = 15,
            locale = "en-us",
        )
        assertTrue(xml.contains("<TicketToken>t=abc&amp;def&lt;1&gt;</TicketToken>"))
        assertTrue(xml.contains("<GetContactsRecentActivity xmlns=\"http://www.msn.com/webservices/AddressBook\">"))
        assertTrue(xml.contains("<count>15</count>"))
    }

    @Test
    fun parsesStatusPhotoDisplayPictureAndAnnouncementActivities() {
        val responseXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <soap:Body>
                <GetContactsRecentActivityResponse xmlns="http://www.msn.com/webservices/AddressBook">
                  <GetContactsRecentActivityResult>
                    <Activities>
                      <ActivityDetails>
                        <ObjectId>msgr_status_10</ObjectId>
                        <ApplicationId>1275182653</ApplicationId>
                        <ChangeType>1</ChangeType>
                        <PublishDate>2026-11-17T18:00:00Z</PublishDate>
                        <TemplateVariables>
                          <TemplateVariable xsi:type="PublisherIdTemplateVariable">
                            <Name>publisher</Name>
                            <Id>12345</Id>
                            <NameHint>Alice</NameHint>
                            <ProfilePictureUrl>http://cts.storage.ugnet.xyz/avatar/u1/im</ProfilePictureUrl>
                            <ProfileUrlHint>https://crosstalk.im/profile/+alice</ProfileUrlHint>
                            <IsFavorite>true</IsFavorite>
                          </TemplateVariable>
                          <TemplateVariable xsi:type="TextTemplateVariable">
                            <Name>CanonicalStatusText</Name>
                            <Value>Listening to retro synthwave</Value>
                          </TemplateVariable>
                        </TemplateVariables>
                      </ActivityDetails>
                      <ActivityDetails>
                        <ObjectId>msgr_photo_11</ObjectId>
                        <ApplicationId>1275182653</ApplicationId>
                        <ChangeType>2</ChangeType>
                        <PublishDate>2026-11-17T18:30:00Z</PublishDate>
                        <TemplateVariables>
                          <TemplateVariable xsi:type="PublisherIdTemplateVariable">
                            <Name>publisher</Name>
                            <Id>67890</Id>
                            <NameHint>Bob</NameHint>
                          </TemplateVariable>
                          <TemplateVariable xsi:type="TextTemplateVariable">
                            <Name>CanonicalStatusText</Name>
                            <Value>Look at this setup</Value>
                          </TemplateVariable>
                          <TemplateVariable xsi:type="ImageTemplateVariable">
                            <Name>Photo</Name>
                            <Value>http://cts.storage.ugnet.xyz/photos/u2/abcd.png</Value>
                            <Href>http://cts.storage.ugnet.xyz/photos/u2/abcd.png</Href>
                          </TemplateVariable>
                        </TemplateVariables>
                      </ActivityDetails>
                      <ActivityDetails>
                        <ObjectId>announcement_20261117190000_1</ObjectId>
                        <ApplicationId>420</ApplicationId>
                        <ChangeType>20</ChangeType>
                        <PublishDate>2026-11-17T19:00:00Z</PublishDate>
                        <TemplateVariables>
                          <TemplateVariable xsi:type="PublisherIdTemplateVariable">
                            <Name>publisher</Name>
                            <Id>0</Id>
                            <NameHint>CrossTalk 0.6</NameHint>
                            <ProfileUrlHint>https://crosstalk.im/blog</ProfileUrlHint>
                          </TemplateVariable>
                          <TemplateVariable xsi:type="HlinkTemplateVariable">
                            <Name>AppName</Name>
                            <Value>https://crosstalk.im/blog</Value>
                            <Text>Announcements</Text>
                          </TemplateVariable>
                          <TemplateVariable xsi:type="TextTemplateVariable">
                            <Name>CanonicalStatusText</Name>
                            <Value>Welcome to CrossTalk!</Value>
                          </TemplateVariable>
                        </TemplateVariables>
                      </ActivityDetails>
                    </Activities>
                  </GetContactsRecentActivityResult>
                </GetContactsRecentActivityResponse>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val parsed = MsnpWhatsNewService.parseActivitiesResponse(responseXml)
        assertEquals(3, parsed.size)

        // Sorted newest-first.
        val announcement = parsed[0]
        assertEquals(WhatsNewKind.Announcement, announcement.kind)
        assertEquals("CrossTalk 0.6", announcement.publisherName)
        assertEquals("Welcome to CrossTalk!", announcement.text)
        assertEquals("Announcements", announcement.sourceLabel)
        assertEquals("https://crosstalk.im/blog", announcement.targetUrl)

        val photo = parsed[1]
        assertEquals(WhatsNewKind.Photo, photo.kind)
        assertEquals("Bob", photo.publisherName)
        assertEquals("https://cts.storage.ugnet.xyz/photos/u2/abcd.png", photo.mediaUrl)
        assertEquals("https://cts.storage.ugnet.xyz/photos/u2/abcd.png", photo.targetUrl)

        val status = parsed[2]
        assertEquals(WhatsNewKind.Status, status.kind)
        assertEquals("Alice", status.publisherName)
        assertTrue(status.isFavorite)
        assertEquals("https://cts.storage.ugnet.xyz/avatar/u1/im", status.publisherAvatarUrl)
        assertEquals("Listening to retro synthwave", status.text)
    }
}
