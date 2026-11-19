package org.aralozkaya.msngr

import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.CurrentMediaType
import org.aralozkaya.msngr.protocol.MsnCurrentMedia
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class MsnCurrentMediaTest {
    @Test
    fun encodesMusicWithTitleArtistAlbumAndAppName() {
        val media = MsnCurrentMedia.music(
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            appName = "Spotify",
        )
        assertNotNull(media)
        assertEquals("Bohemian Rhapsody - Queen", media!!.displayText)
        assertEquals(
            "Spotify\\0Music\\01\\0{0} - {1}\\0Bohemian Rhapsody\\0Queen\\0A Night at the Opera\\0\\0",
            MsnCurrentMedia.encode(media),
        )
    }

    @Test
    fun encodesTitleOnlyWithoutDanglingDashAndSuppressesDuplicateArtist() {
        val titleOnly = MsnCurrentMedia.music(title = "Lo-Fi Beats")
        assertNotNull(titleOnly)
        assertEquals("Lo-Fi Beats", titleOnly!!.displayText)
        assertEquals(
            "\\0Music\\01\\0{0}\\0Lo-Fi Beats\\0\\0\\0\\0",
            MsnCurrentMedia.encode(titleOnly),
        )

        val duplicateArtist = MsnCurrentMedia.music(title = "Podcast Ep 1", artist = "podcast ep 1")
        assertNotNull(duplicateArtist)
        assertEquals("", duplicateArtist!!.artist)
        assertEquals("Podcast Ep 1", duplicateArtist.displayText)
    }

    @Test
    fun sanitizesNullDelimitersNewlinesAndBlankTitles() {
        assertNull(MsnCurrentMedia.music(title = "   \r\n\t  "))
        assertEquals("", MsnCurrentMedia.encode(null))
        assertEquals(
            "",
            MsnCurrentMedia.encode(CurrentMedia(title = "  ", artist = "  ", format = "")),
        )

        val dirty = MsnCurrentMedia.music(
            title = "Track\\0One\r\nLive",
            artist = "Band\u0000Name\t!",
            album = "Album\\0Two",
        )
        assertNotNull(dirty)
        assertEquals("Track One Live", dirty!!.title)
        assertEquals("Band Name !", dirty.artist)
        assertEquals("Album Two", dirty.album)
        assertEquals(
            "\\0Music\\01\\0{0} - {1}\\0Track One Live\\0Band Name !\\0Album Two\\0\\0",
            MsnCurrentMedia.encode(dirty),
        )
    }

    @Test
    fun parsesWmpWinampCustomAndNonMusicPayloads() {
        val wmp = MsnCurrentMedia.parse(
            "\\0Music\\01\\0{0} - {1}\\0Call Me When You're Sober\\0Evanescence\\0The Open Door\\0{GUID}\\0",
        )
        assertNotNull(wmp)
        assertEquals(CurrentMediaType.Music, wmp!!.type)
        assertEquals("Call Me When You're Sober - Evanescence", wmp.displayText)
        assertEquals("The Open Door", wmp.album)

        val winampSwapped = MsnCurrentMedia.parse(
            "Winamp\\0Music\\01\\0{1} - {0}\\0Track Name\\0Artist Name\\0Album\\0\\0",
        )
        assertNotNull(winampSwapped)
        assertEquals("Winamp", winampSwapped!!.appName)
        assertEquals("Artist Name - Track Name", winampSwapped.displayText)

        val custom = MsnCurrentMedia.parse(
            "iTunes\\0Music\\01\\0Listening to {0} by {1}\\0Track\\0Artist\\0\\0",
        )
        assertEquals("Listening to Track by Artist", custom!!.displayText)

        val missingArtistWithStandardFormat = MsnCurrentMedia.parse(
            "\\0Music\\01\\0{0} - {1}\\0Only Track\\0\\0\\0\\0",
        )
        assertEquals("Only Track", missingArtistWithStandardFormat!!.displayText)

        val game = MsnCurrentMedia.parse(
            "\\0Games\\01\\0{0}\\0Solitaire\\0\\0\\0\\0",
        )
        assertEquals(CurrentMediaType.Games, game!!.type)
        assertEquals("Solitaire", game.displayText)

        val office = MsnCurrentMedia.parse(
            "\\0Office\\01\\0{0}\\0Quarterly Report.docx\\0\\0\\0\\0",
        )
        assertEquals(CurrentMediaType.Office, office!!.type)

        val other = MsnCurrentMedia.parse(
            "\\0Podcast\\01\\0{0}\\0Episode 42\\0\\0\\0\\0",
        )
        assertEquals(CurrentMediaType.Other, other!!.type)
    }

    @Test
    fun returnsNullForDisabledEmptyOrMalformedPayloads() {
        assertNull(MsnCurrentMedia.parse(""))
        assertNull(MsnCurrentMedia.parse("   "))
        assertNull(MsnCurrentMedia.parse("incomplete\\0Music\\01"))
        assertNull(MsnCurrentMedia.parse("\\0Music\\00\\0{0} - {1}\\0Title\\0Artist\\0\\0"))
        assertNull(MsnCurrentMedia.parse("\\0\\01\\0{0}\\0Title\\0\\0"))
        assertNull(MsnCurrentMedia.parse("\\0Music\\01\\0{0} - {1}\\0\\0\\0\\0"))
    }

    @Test
    fun protocolRoundTripsCurrentMediaAndXmlEscapingInUuxPayload() {
        val media = MsnCurrentMedia.music(
            title = "Rock & Roll <Live>",
            artist = "Şebnem Ferah \"Akustik\"",
            album = "O'nun Şarkısı",
        )!!
        val command = Msnp18Protocol.personalMessage(
            transactionId = 42,
            statusMessage = "Working on Msngr",
            machineGuid = "{11111111-1111-1111-1111-111111111111}",
            currentMedia = media,
        )
        val payload = requireNotNull(command.payload)
        val xml = String(payload, StandardCharsets.UTF_8)

        assertTrue(xml.contains("<PSM>Working on Msngr</PSM>"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;Live&gt;"))
        assertTrue(Msnp18Protocol.hasPersonalMessageElement(payload))
        assertTrue(Msnp18Protocol.hasCurrentMediaElement(payload))

        val parsed = Msnp18Protocol.parseCurrentMedia(payload)
        assertEquals(media, parsed)

        val clearedPayload = Msnp18Protocol.personalMessage(
            transactionId = 43,
            statusMessage = "Working on Msngr",
            currentMedia = null,
        ).payload!!
        assertTrue(Msnp18Protocol.hasCurrentMediaElement(clearedPayload))
        assertNull(Msnp18Protocol.parseCurrentMedia(clearedPayload))

        val selfClosing = "<Data><PSM>Hi</PSM><CurrentMedia /></Data>".toByteArray(StandardCharsets.UTF_8)
        assertTrue(Msnp18Protocol.hasCurrentMediaElement(selfClosing))
        assertNull(Msnp18Protocol.parseCurrentMedia(selfClosing))

        val psmOnly = "<Data><PSM>Only status</PSM></Data>".toByteArray(StandardCharsets.UTF_8)
        assertTrue(Msnp18Protocol.hasPersonalMessageElement(psmOnly))
        assertFalse(Msnp18Protocol.hasCurrentMediaElement(psmOnly))
        assertNull(Msnp18Protocol.parseCurrentMedia(psmOnly))
    }
}
