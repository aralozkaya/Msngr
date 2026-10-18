package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.FileContactDisplayPictureCache
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ContactDisplayPictureCacheTest {
    @Test
    fun storesOriginalLargerThanThePeerTransferLimitAcrossCacheInstances() = runBlocking {
        val root = Files.createTempDirectory("msngr-original-picture").toFile()
        try {
            val original = mockDisplayPictureBytes() + ByteArray(240 * 1024) { 42 }
            val picture = MsnDisplayPicture.create("contact@example.com", original)
            FileContactDisplayPictureCache(root).store(
                "owner@example.com", "contact@example.com", picture, original,
            )
            val restored = FileContactDisplayPictureCache(root)
                .load("owner@example.com", listOf("contact@example.com"))
                .getValue("contact@example.com")
            assertArrayEquals(original, restored.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storesValidatedPicturesSeparatelyForEachSignedInAccount() = runBlocking {
        val root = Files.createTempDirectory("msngr-dp-cache").toFile()
        try {
            val cache = FileContactDisplayPictureCache(root)
            val contact = "contact@example.com"
            val firstBytes = mockDisplayPictureBytes()
            val secondBytes = firstBytes + byteArrayOf(1, 2, 3)
            val firstPicture = MsnDisplayPicture.create(contact, firstBytes)
            val secondPicture = MsnDisplayPicture.create(contact, secondBytes)

            cache.store("first@example.com", contact, firstPicture, firstBytes)
            cache.store("first@example.com", contact, secondPicture, secondBytes)
            cache.store("second@example.com", contact, firstPicture, firstBytes)
            val removedContact = "removed@example.com"
            val removedPicture = MsnDisplayPicture.create(removedContact, firstBytes)
            cache.store("first@example.com", removedContact, removedPicture, firstBytes)

            val first = cache.load("first@example.com", listOf(contact)).getValue(contact)
            val second = cache.load("second@example.com", listOf(contact)).getValue(contact)
            assertEquals(secondPicture.sha1d, first.picture.sha1d)
            assertArrayEquals(secondBytes, first.bytes)
            assertEquals(firstPicture.sha1d, second.picture.sha1d)
            assertArrayEquals(firstBytes, second.bytes)
            assertEquals(2, root.walkTopDown().count { it.extension == "dp" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMismatchedAndCorruptRecords() = runBlocking {
        val root = Files.createTempDirectory("msngr-dp-cache-corrupt").toFile()
        try {
            val cache = FileContactDisplayPictureCache(root)
            val contact = "contact@example.com"
            val bytes = mockDisplayPictureBytes()
            val wrongCreator = MsnDisplayPicture.create("other@example.com", bytes)
            cache.store("owner@example.com", contact, wrongCreator, bytes)
            assertTrue(cache.load("owner@example.com", listOf(contact)).isEmpty())

            val picture = MsnDisplayPicture.create(contact, bytes)
            cache.store("owner@example.com", contact, picture, bytes)
            val record = root.walkTopDown().first { it.extension == "dp" }
            record.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(cache.load("owner@example.com", listOf(contact)).isEmpty())
            assertTrue(!record.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesOnlyTheRequestedAccountsContactPicture() = runBlocking {
        val root = Files.createTempDirectory("msngr-dp-cache-remove").toFile()
        try {
            val cache = FileContactDisplayPictureCache(root)
            val contact = "contact@example.com"
            val bytes = mockDisplayPictureBytes()
            val picture = MsnDisplayPicture.create(contact, bytes)
            cache.store("first@example.com", contact, picture, bytes)
            cache.store("second@example.com", contact, picture, bytes)

            cache.remove("first@example.com", "1:CONTACT@example.com;endpoint")

            assertTrue(cache.load("first@example.com", listOf(contact)).isEmpty())
            assertEquals(
                picture.sha1d,
                cache.load("second@example.com", listOf(contact)).getValue(contact).picture.sha1d,
            )
            assertEquals(1, root.walkTopDown().count { it.extension == "dp" })
        } finally {
            root.deleteRecursively()
        }
    }
}
