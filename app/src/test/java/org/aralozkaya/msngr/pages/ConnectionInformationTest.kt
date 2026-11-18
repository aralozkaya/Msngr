package org.aralozkaya.msngr.pages

import org.aralozkaya.msngr.ProviderOptions
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.ServerPresets
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
                R.string.connection_winks_plural to "https://winks.example/catalog/",
                R.string.connection_emotion_sounds_plural to "https://sounds.example/custom/",
            ),
            messengerPlusConnectionDetailItems(servers.winkCatalogServer, servers.emotionSoundServer),
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
                R.string.connection_winks_plural to null,
                R.string.connection_emotion_sounds_plural to null,
            ),
            messengerPlusConnectionDetailItems(servers.winkCatalogServer, servers.emotionSoundServer),
        )
    }

    @Test fun crosstalkDetailsUseCrosstalkSoundServerAndDisableThirdPartyWinks() {
        val servers = ServerPresets.resolveForProvider(ProviderOptions.CROSSTALK, ServerOverrides())

        assertEquals(
            listOf(
                R.string.connection_winks_plural to null,
                R.string.connection_emotion_sounds_plural to MsnEmotionSound.CROSSTALK_SERVER,
            ),
            messengerPlusConnectionDetailItems(servers.winkCatalogServer, servers.emotionSoundServer),
        )
    }

    @Test fun missingAndBlankUrlsHaveAReadableFallback() {
        for (missing in listOf(null, "", " \t\n")) {
            assertEquals(
                listOf(R.string.connection_winks_plural to null, R.string.connection_emotion_sounds_plural to null),
                messengerPlusConnectionDetailItems(missing, missing),
            )
        }
    }
}
