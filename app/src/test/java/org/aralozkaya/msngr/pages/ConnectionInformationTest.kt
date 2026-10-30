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
        )
        val servers = ServerPresets.resolveForProvider(ProviderOptions.CUSTOM, custom)

        assertEquals(
            listOf(
                "Winks" to WinkCatalogActivity.CATALOG_URL,
                "Emotion sounds" to "https://sounds.example/custom/",
            ),
            messengerPlusConnectionDetails(WinkCatalogActivity.CATALOG_URL, servers.emotionSoundServer),
        )
    }

    @Test fun absentSoundOverrideDisplaysTheResolvedDefault() {
        val servers = ServerPresets.resolveForProvider(ProviderOptions.CUSTOM, ServerOverrides(
            mainServer = "messenger.example",
            configServer = "config.example",
            emotionSoundServer = "",
        ))

        assertEquals(
            "Emotion sounds" to MsnEmotionSound.DEFAULT_SERVER,
            messengerPlusConnectionDetails(null, servers.emotionSoundServer).last(),
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
