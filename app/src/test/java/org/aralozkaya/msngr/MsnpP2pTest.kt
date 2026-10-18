package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsnpP2pTest {
    @Test
    fun exchangesTheMockContactDisplayPictureOverMsnSlpP2p() = runBlocking {
        val ownerEmail = MockServerData.contacts().first().email
        val bytes = mockDisplayPictureBytes()
        val picture = MsnDisplayPicture.create(ownerEmail, bytes)
        val received = mutableListOf<ByteArray>()
        var packetsFromRequester = 0
        var packetsFromOwner = 0
        lateinit var requester: MsnpP2pSession
        lateinit var owner: MsnpP2pSession
        requester = MsnpP2pSession(
            localEmail = MockServerData.ACCOUNT_EMAIL,
            remoteEmail = ownerEmail,
            sendPacket = { packet ->
                packetsFromRequester++
                owner.handle(packet)
            },
            localPicture = { null },
            onDisplayPicture =(received::add),
        )
        owner = MsnpP2pSession(
            localEmail = ownerEmail,
            remoteEmail = MockServerData.ACCOUNT_EMAIL,
            sendPacket = { packet ->
                packetsFromOwner++
                requester.handle(packet)
            },
            localPicture = { LocalDisplayPicture(picture, bytes) },
            onDisplayPicture = {},
        )

        requester.requestDisplayPicture(picture)

        assertArrayEquals(bytes, received.single())
        assertTrue(packetsFromRequester > 1)
        assertTrue(packetsFromOwner > 1)
    }
}
