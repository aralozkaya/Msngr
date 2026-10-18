package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.AccountSession
import org.aralozkaya.msngr.data.parseAccountSessions
import org.aralozkaya.msngr.data.remoteLogoutRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpFrameCodec
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.*
import org.junit.Test

class MsnpSessionsTest {
    private val own = "01234567-89ab-cdef-0123-456789abcdef"
    private val other = "12345678-90ab-cdef-0123-456789abcdef"
    private val current = AccountSession(own, "Phone", true, Presence.Online)

    @Test fun accountPublicationDecodesNamesAndReplacesDepartedSessions() {
        val xml = """<Data><EndpointData id="{$own}"/><EndpointData id="{$other}"/>
            <PrivateEndpointData id="{${other.uppercase()}}"><EpName>Aral&#x27;s téléphone &amp; PC</EpName>
            <State>BSY</State></PrivateEndpointData></Data>"""
        val sessions = requireNotNull(parseAccountSessions(xml.toByteArray(), current, emptyList()))
        assertEquals(2, sessions.size)
        assertEquals(current, sessions.first())
        assertEquals(AccountSession(other, "Aral's téléphone & PC", presence = Presence.Busy), sessions.last())
        assertEquals(listOf(current), parseAccountSessions("<Data/>".toByteArray(), current, sessions))
    }

    @Test fun incompleteMetadataKeepsKnownNamesAndInvalidPublicationsAreIgnored() {
        val previous = listOf(current, AccountSession(other, "Laptop"))
        val xml = """<Data><EndpointData id="{$other}"/><EndpointData id="bad"/></Data>"""
        assertEquals(previous, parseAccountSessions(xml.toByteArray(), current, previous))
        assertNull(parseAccountSessions("<PrivateEndpointData/>".toByteArray(), current, previous))
        assertNull(parseAccountSessions("<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///missing'>]><Data>&p;</Data>"
            .toByteArray(), current, previous))
    }

    @Test fun logoutTargetsOneGuidAndRejectsWireInjection() {
        val command = Msnp18Protocol.logOutSession(7, "me@example.com", "{${other.uppercase()}}")
        assertEquals("UUN 7 me@example.com;{$other} 4 14\r\ngoawyplzthxbye", MsnpFrameCodec.encode(command).toString(Charsets.UTF_8))
        assertTrue(runCatching { Msnp18Protocol.logOutSession(1, "me@example.com\r\nOUT", other) }.isFailure)
        assertTrue(runCatching { Msnp18Protocol.logOutSession(1, "me@example.com", "bad\r\nOUT") }.isFailure)
    }

    @Test fun fragmentedLogoutNotificationDoesNotCorruptFollowingFrames() {
        val decoder = MsnpFrameDecoder()
        assertTrue(decoder.feed("UBN me@example.com;{$other} 8 4\r\ngt".toByteArray()).isEmpty())
        val commands = decoder.feed("foOUT OTH\r\n".toByteArray())
        assertEquals(2, commands.size)
        assertTrue(remoteLogoutRequest(commands.first(), "me@example.com", own))
        assertEquals(MsnpCommand("OUT", parameters = listOf("OTH")), commands.last())
    }

    @Test fun otherAccountsAndOwnBroadcastsCannotRevokeThisSession() {
        fun notification(sender: String, type: String = "8", body: String = "gtfo") =
            MsnpCommand("UBN", parameters = listOf(sender, type, body.length.toString()), payload = body.toByteArray())
        assertTrue(remoteLogoutRequest(notification("1:ME@example.com;{$other}"), "me@example.com", own))
        assertTrue(remoteLogoutRequest(notification("me@example.com;{$other}", "4", "goawyplzthxbye"), "me@example.com", own))
        assertTrue(remoteLogoutRequest(notification("me@example.com", "4", "goawyplzthxbye-nomorempop"), "me@example.com", own))
        assertFalse(remoteLogoutRequest(notification("friend@example.com;{$other}"), "me@example.com", own))
        assertFalse(remoteLogoutRequest(notification("me@example.com;{$own}"), "me@example.com", own))
        assertFalse(remoteLogoutRequest(notification("me@example.com", "3"), "me@example.com", own))
        assertFalse(remoteLogoutRequest(notification("me@example.com", body = "other data"), "me@example.com", own))
    }
}
