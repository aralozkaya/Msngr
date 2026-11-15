package org.aralozkaya.msngr

import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    @Test
    fun optionalServersFallbackToMainServerWhenDisabled() {
        val resolved = ServerOverrides(
            mainServer = "main.example",
            configServer = "config.example",
            authServerEnabled = false,
            authServer = "auth.example",
            addressBookServerEnabled = false,
            addressBookServer = "address.example",
            adsServerEnabled = false,
            adsServer = "ads.example",
            storageServerEnabled = false,
            storageServer = "storage.example",
            socialServerEnabled = false,
            socialServer = "social.example",
            activitiesServerEnabled = false,
            activitiesServer = "apps.example",
        ).toResolved()

        assertEquals("main.example", resolved.authServer)
        assertEquals("main.example", resolved.addressBookServer)
        assertEquals("main.example", resolved.adsServer)
        assertEquals("main.example", resolved.storageServer)
        assertEquals("main.example", resolved.socialServer)
        assertEquals("main.example", resolved.activitiesServer)
        assertEquals(1864, resolved.switchboardPort)
    }

    @Test
    fun crosstalkPresetResolvesItsExplicitOverrides() {
        val resolved = ServerPresets.resolveForProvider(
            provider = ProviderOptions.CROSSTALK,
            customServers = ServerOverrides(),
        )

        assertEquals("ms.msgrsvcs.ctsrv.xyz", resolved.mainServer)
        assertEquals("conf.msgrsvcs.ctsrv.xyz", resolved.configServer)
        assertEquals("ctas.login.ugnet.xyz", resolved.authServer)
        assertEquals("ctsvcs.addressbook.ugnet.xyz", resolved.addressBookServer)
        assertEquals("ctsvcs.advertising.ugnet.xyz", resolved.adsServer)
        assertEquals("cts.storage.ugnet.xyz", resolved.storageServer)
        assertEquals("social.ugnet.xyz", resolved.socialServer)
        assertEquals("mactivities.msgrsvcs.ctsrv.xyz", resolved.activitiesServer)
        assertEquals("mail.ugnet.xyz", resolved.mailServer)
        assertEquals(MsnEmotionSound.CROSSTALK_SERVER, resolved.emotionSoundServer)
        assertEquals("", resolved.winkCatalogServer)
        val allEndpoints = listOf(
            resolved.mainServer,
            resolved.configServer,
            resolved.authServer,
            resolved.addressBookServer,
            resolved.adsServer,
            resolved.storageServer,
            resolved.socialServer,
            resolved.activitiesServer,
            resolved.mailServer,
            resolved.emotionSoundServer,
            resolved.winkCatalogServer,
        )
        assertTrue(allEndpoints.none { "escargot" in it.lowercase() || "nina" in it.lowercase() })
    }

    @Test
    fun escargotPresetResolvesItsActivitiesServer() {
        val resolved = ServerPresets.resolveForProvider(
            provider = ProviderOptions.ESCARGOT,
            customServers = ServerOverrides(),
        )

        assertEquals("ds.escargot.nina.chat", resolved.mainServer)
        assertEquals("mconf.escargot.nina.chat", resolved.configServer)
        assertEquals("apps.escargot.nina.chat", resolved.activitiesServer)
        assertEquals(MsnEmotionSound.DEFAULT_SERVER, resolved.emotionSoundServer)
        assertEquals(WinkCatalogActivity.CATALOG_URL, resolved.winkCatalogServer)
    }

    @Test
    fun customServersLeaveOptionalMediaServicesUnconfiguredUnlessSet() {
        val unconfigured = ServerOverrides(
            mainServer = "main.example",
            configServer = "config.example",
        )
        assertTrue(unconfigured.isValid())
        assertEquals("", unconfigured.toResolved().emotionSoundServer)
        assertEquals("", unconfigured.toResolved().winkCatalogServer)

        assertFalse(unconfigured.copy(emotionSoundServer = "not-a-url").isValid())
        assertFalse(unconfigured.copy(winkCatalogServer = "not-a-url").isValid())
        assertTrue(
            unconfigured.copy(
                emotionSoundServer = "https://sounds.example/esnd/snd",
                winkCatalogServer = "https://winks.example/catalog/",
            ).isValid(),
        )
    }

    @Test
    fun customServersRequireMainAndConfig() {
        val invalid = ServerOverrides(
            mainServer = "",
            configServer = "config.example",
        )
        val valid = ServerOverrides(
            mainServer = "main.example",
            configServer = "config.example",
        )

        assertFalse(invalid.hasRequiredServers())
        assertFalse(invalid.isValid())
        assertTrue(valid.hasRequiredServers())
        assertTrue(valid.isValid())
    }
}
