package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.MsnpOfflineMessages
import org.aralozkaya.msngr.data.decodeOfflineDisplayName
import org.aralozkaya.msngr.protocol.MsnpFrameCodec
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MsnpOfflineMessagesTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/offline/$name")).use { it.readBytes() }

    @Test fun capturedWlmFrameMatchesUtf8BuilderAndDecodesAtEverySplit() {
        val text = "Msngr offline protocol test 2026-10-11: Merhaba, çığ öşü (Y)"
        val frame = fixture("wlm-uum.txt")
        val expected = MsnpOfflineMessages.send(25, "peer@example.test", text)
        assertArrayEquals(frame, MsnpFrameCodec.encode(expected))
        for (split in 0..frame.size) {
            val decoder = MsnpFrameDecoder()
            val commands = decoder.feed(frame.copyOfRange(0, split)) + decoder.feed(frame.copyOfRange(split, frame.size))
            assertEquals(1, commands.size)
            assertEquals("UUM", commands.single().verb)
            assertArrayEquals(expected.payload, commands.single().payload)
        }
    }

    @Test fun capturedLoginMetadataAndSoapMessageRetainUnicodeTimeAndStableId() {
        val metadata = MsnpOfflineMessages.parseMetadata(fixture("wlm-metadata.xml").toString(Charsets.UTF_8)).single()
        assertEquals("11111111-2222-4333-8444-ABC000000004", metadata.id)
        assertTrue(MsnpOfflineMessages.buildRequest("GetMessage", "t=token&p=", metadata.id)
            .contains("<messageId>${metadata.id}</messageId>"))
        assertEquals("owner", metadata.senderName)
        assertEquals("owner@example.test", metadata.sender)
        assertEquals(Instant.parse("2026-10-11T13:06:59Z").toEpochMilli(), metadata.sentAtMillis)
        val xml = fixture("wlm-get-message.xml").toString(Charsets.UTF_8)
        val message = MsnpOfflineMessages.parseMessage(xml, metadata)
        assertEquals("Msngr offline protocol test 2026-10-11: Merhaba, çığ öşü (Y)", message.text)
        assertEquals(metadata.sentAtMillis, message.timestampMillis)
        assertTrue(message.id < 0)
        assertEquals(message.id, MsnpOfflineMessages.parseMessage(xml, metadata.copy(id = metadata.id.uppercase())).id)
    }

    @Test fun notificationsHandleHeaderAndBodyMailDataAndTooLarge() {
        val md = fixture("wlm-metadata.xml").toString(Charsets.UTF_8)
        for (type in listOf("text/x-msmsgsinitialmdatanotification", "text/x-msmsgsoimnotification")) {
            assertEquals(md, MsnpOfflineMessages.notification("Content-Type: $type\r\nMail-Data: $md\r\n\r\n".toByteArray()))
            assertEquals(md, MsnpOfflineMessages.notification("Content-Type: $type\r\n\r\nMail-Data: $md\r\n".toByteArray()))
        }
        assertEquals("too-large", MsnpOfflineMessages.notification("Content-Type: text/x-msmsgsinitialmdatanotification\r\n\r\nMail-Data: too-large\r\n".toByteArray()))
        assertNull(MsnpOfflineMessages.notification("Content-Type: text/plain\r\n\r\nhello".toByteArray()))
    }

    @Test fun rsiMetadataResponseUnwrapsEscapedMailData() {
        val md = fixture("wlm-metadata.xml").toString(Charsets.UTF_8)
        val expected = MsnpOfflineMessages.parseMetadata(md)
        val escaped = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        for (body in listOf(md, escaped, "<GetMetadataResult>$escaped</GetMetadataResult>")) {
            val response = "<soap:Envelope xmlns:soap='http://schemas.xmlsoap.org/soap/envelope/'><soap:Body>" +
                "<GetMetadataResponse xmlns='${MsnpOfflineMessages.NAMESPACE}'>$body</GetMetadataResponse></soap:Body></soap:Envelope>"
            assertEquals(expected, MsnpOfflineMessages.parseMetadata(response))
        }
        assertEquals(emptyList<Any>(), MsnpOfflineMessages.parseMetadata(
            "<GetMetadataResponse>&lt;MD&gt;&lt;/MD&gt;</GetMetadataResponse>",
        ))
        assertTrue(runCatching { MsnpOfflineMessages.parseMetadata("<GetMetadataResponse/>") }.isFailure)
    }

    @Test fun soapCredentialsAreSeparatedEscapedAndFetchDoesNotMarkRead() {
        val id = "11111111-2222-4333-8444-abc000000004"
        val request = MsnpOfflineMessages.buildRequest("GetMessage", "t=a%26b=c&p=<proof>&extra=ignored", id)
        assertTrue(request.contains("<t>a%26b=c</t><p>&lt;proof&gt;</p>"))
        assertTrue(request.contains("<alsoMarkAsRead>false</alsoMarkAsRead>"))
        assertFalse(request.contains("extra="))
        assertTrue(MsnpOfflineMessages.buildRequest("DeleteMessages", "t=token&p=", id).contains("<messageIds><messageId>$id</messageId></messageIds>"))
    }

    @Test fun invalidPayloadsAndSoapFaultsAreRejected() {
        assertTrue(runCatching { MsnpOfflineMessages.send(1, "contact@example.com", "ç".repeat(601)) }.isFailure)
        assertTrue(runCatching { MsnpOfflineMessages.send(1, "contact@example.com\r\nOUT", "hi") }.isFailure)
        assertTrue(runCatching { MsnpOfflineMessages.parseMetadata("<soap:Envelope xmlns:soap='http://schemas.xmlsoap.org/soap/envelope/'><soap:Fault/></soap:Envelope>") }.isFailure)
        val metadata = MsnpOfflineMessages.parseMetadata(fixture("wlm-metadata.xml").toString(Charsets.UTF_8)).single()
        val xml = fixture("wlm-get-message.xml").toString(Charsets.UTF_8)
        assertTrue(runCatching { MsnpOfflineMessages.parseMessage(xml.replace("base64", "binary"), metadata) }.isFailure)
        assertTrue(runCatching { MsnpOfflineMessages.parseMessage(xml, metadata.copy(sender="other@example.com")) }.isFailure)
        assertEquals("çığ öşü", decodeOfflineDisplayName("=?utf-8?Q?=C3=A7=C4=B1=C4=9F_=C3=B6=C5=9F=C3=BC?="))
    }
}
