package org.aralozkaya.msngr

import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class StoredAccountCodecTest {
    @Test
    fun newAccountsDefaultToTheRealServer() {
        val account = StoredAccountDto()

        assertFalse(account.useMockServer)
        assertFalse(account.automaticSignIn)
        assertEquals(Presence.Online, account.loginPresence)
    }

    @Test
    fun accountsFromBeforeTheMockOptionMigrateToTheRealServer() {
        val versionTwoBytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(2)
                output.writeUTF("mock-fixture@example.com")
                output.writeUTF("mock-password")
                output.writeBoolean(true)
                output.writeUTF(ProviderOptions.ESCARGOT)
                output.writeUTF("mock-notification.example")
                output.writeInt(1863)
                output.writeInt(1863)
                output.writeUTF("mock-config.example")
                repeat(7) {
                    output.writeBoolean(false)
                    output.writeUTF("")
                }
            }
            bytes.toByteArray()
        }

        val migrated = StoredAccountCodec.decode(versionTwoBytes)

        assertEquals("mock-fixture@example.com", migrated.email)
        assertFalse(migrated.useMockServer)
        assertFalse(migrated.automaticSignIn)
        assertEquals(Presence.Online, migrated.loginPresence)
    }

    @Test
    fun accountsFromBeforeAutomaticSignInKeepTheirMockChoiceAndDefaultOnline() {
        val versionThreeBytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(3)
                output.writeUTF("mock-fixture@example.com")
                output.writeUTF("mock-password")
                output.writeBoolean(true)
                output.writeUTF(ProviderOptions.ESCARGOT)
                output.writeUTF("mock-notification.example")
                output.writeInt(1863)
                output.writeInt(1863)
                output.writeUTF("mock-config.example")
                repeat(7) {
                    output.writeBoolean(false)
                    output.writeUTF("")
                }
                output.writeBoolean(true)
            }
            bytes.toByteArray()
        }

        val migrated = StoredAccountCodec.decode(versionThreeBytes)

        assertEquals("mock-fixture@example.com", migrated.email)
        assertEquals(true, migrated.useMockServer)
        assertFalse(migrated.automaticSignIn)
        assertEquals(Presence.Online, migrated.loginPresence)
    }

    @Test
    fun encryptedDtoCodecRoundTripsCredentialsAndEveryServerOverride() {
        val original = StoredAccountDto(
            email = "hello@example.com",
            password = "correct horse battery staple",
            remember = true,
            provider = ProviderOptions.CUSTOM,
            useMockServer = true,
            automaticSignIn = true,
            loginPresence = Presence.Away,
            customServers = ServerOverrides(
                mainServer = "ns.example.com",
                mainPort = 1864,
                switchboardPort = 1865,
                configServer = "config.example.com",
                authServerEnabled = true,
                authServer = "auth.example.com",
                addressBookServerEnabled = true,
                addressBookServer = "ab.example.com",
                adsServerEnabled = true,
                adsServer = "ads.example.com",
                storageServerEnabled = true,
                storageServer = "storage.example.com",
                socialServerEnabled = true,
                socialServer = "social.example.com",
                activitiesServerEnabled = true,
                activitiesServer = "apps.example.com",
                mailServerEnabled = true,
                mailServer = "mail.example.com",
                emotionSoundServer = "https://sounds.example.com/custom/snd/",
                winkCatalogServer = "https://winks.example.com/catalog/",
            ),
        )

        assertEquals(original, StoredAccountCodec.decode(StoredAccountCodec.encode(original)))
    }

    @Test fun versionFourAndFiveRetainPreviousFieldsWhenUpgraded() {
        val original = StoredAccountDto(
            email = "fixture@example.test",
            password = "test",
            remember = true,
            automaticSignIn = true,
            loginPresence = Presence.Away,
            customServers = ServerOverrides(emotionSoundServer = "https://sounds.example.com/snd/"),
        )
        val encoded = StoredAccountCodec.encode(original)
        val versionFiveLength = encoded.size - 2 - original.customServers.winkCatalogServer.toByteArray().size
        val versionFiveBytes = encoded.copyOf(versionFiveLength).apply { this[3] = 5 }
        assertEquals(original, StoredAccountCodec.decode(versionFiveBytes))

        val versionFourLength = versionFiveLength - 2 - original.customServers.emotionSoundServer.toByteArray().size
        val versionFourBytes = encoded.copyOf(versionFourLength).apply { this[3] = 4 }
        assertEquals(
            original.copy(customServers = original.customServers.copy(emotionSoundServer = "")),
            StoredAccountCodec.decode(versionFourBytes),
        )
    }
}
