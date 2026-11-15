package org.aralozkaya.msngr.pages

import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.ServerPresets
import org.aralozkaya.msngr.WinkCatalogActivity
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionInformationTest {
    @Test fun messengerPlusDetailsUseTheActiveServiceUrls() {
        val custom = ServerOverrides(
            mainServer = "messenger.example",
            configServer = "config.example",
            emotionSoundServer = "https://sounds.example/custom",
            winkCatalogServer = "https://winks.example/catalog/",
        )
        val servers = ServerPresets.resolveForProvider(ProviderOptions.CUSTOM, custom)

        assertEquals(
            listOf(
                "Winks" to "https://winks.example/catalog/",
                "Emotion sounds" to "https://sounds.example/custom/",
            ),
            messengerPlusConnectionDetails(servers.winkCatalogServer, servers.emotionSoundServer),
        )
    }

    @Test fun absentCustomUrlsDisplayNotConfiguredWithoutEscargotFallbacks() {
        val servers = ServerPresets.resolveForProvider(ProviderOptions.CUSTOM, ServerOverrides(
            mainServer = "messenger.example",
            configServer = "config.example",
            emotionSoundServer = "",
            winkCatalogServer = "",
        ))

        assertEquals(
            listOf(
                "Winks" to "Not configured",
                "Emotion sounds" to "Not configured",
            ),
            messengerPlusConnectionDetails(servers.winkCatalogServer, servers.emotionSoundServer),
        )
    }

    @Test fun crosstalkDetailsUseCrosstalkSoundServerAndDisableThirdPartyWinks() {
        val servers = ServerPresets.resolveForProvider(ProviderOptions.CROSSTALK, ServerOverrides())

        assertEquals(
            listOf(
                "Winks" to "Not configured",
                "Emotion sounds" to MsnEmotionSound.CROSSTALK_SERVER,
            ),
            messengerPlusConnectionDetails(servers.winkCatalogServer, servers.emotionSoundServer),
        )
    }

    @Test fun missingAndBlankUrlsHaveAReadableFallback() {
        for (missing in listOf(null, "", " \t\n")) {
            assertEquals(
                listOf("Winks" to "Not configured", "Emotion sounds" to "Not configured"),
                messengerPlusConnectionDetails(missing, missing),
            )
        }
    }
}
