package org.aralozkaya.msngr

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

        assertEquals("ms.msgrsvcs.ctsrv.gay", resolved.mainServer)
        assertEquals("conf.msgrsvcs.ctsrv.gay", resolved.configServer)
        assertEquals("ctas.login.ugnet.gay", resolved.authServer)
        assertEquals("ctsvcs.addressbook.ugnet.gay", resolved.addressBookServer)
        assertEquals("ctsvcs.advertising.ugnet.gay", resolved.adsServer)
        assertEquals("cts.storage.ugnet.gay", resolved.storageServer)
        assertEquals("social.ugnet.gay", resolved.socialServer)
        assertEquals("mactivities.msgrsvcs.ctsrv.gay", resolved.activitiesServer)
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
