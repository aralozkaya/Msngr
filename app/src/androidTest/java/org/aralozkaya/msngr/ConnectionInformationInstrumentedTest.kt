package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import org.aralozkaya.msngr.pages.ConnectionInformationScreen
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ConnectionInformationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun restoreAccountPreferences() {
        SessionPrefs.logout()
        AccountPreferences.reload(compose.activity)
    }

    @Test fun customSoundUrlAndWinkCatalogAreVisibleAtLargeText() {
        showConnectionInformation(ServerOverrides(
            mainServer = "messenger.example",
            configServer = "config.example",
            emotionSoundServer = "https://sounds.example/custom",
        ))

        assertEndpointVisible("Winks", WinkCatalogActivity.CATALOG_URL)
        assertEndpointVisible("Emotion sounds", "https://sounds.example/custom/")
    }

    @Test fun missingSoundOverrideStillOpensWithTheDefaultUrl() {
        showConnectionInformation(ServerOverrides(
            mainServer = "messenger.example",
            configServer = "config.example",
            emotionSoundServer = "",
        ))

        assertEndpointVisible("Winks", WinkCatalogActivity.CATALOG_URL)
        assertEndpointVisible("Emotion sounds", MsnEmotionSound.DEFAULT_SERVER)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showConnectionInformation(servers: ServerOverrides) {
        val target = compose.activity
        val context = object : ContextWrapper(target.createDeviceProtectedStorageContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "msngr_session_secure_keyset") {
                    target.applicationContext.getSharedPreferences(name, mode)
                } else super.getSharedPreferences("connection_information_test_$name", mode)
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        SessionPrefs.logout()
        SessionPrefs.completeLogin(context, "connection-info@example.test", "test-password", false,
            ProviderOptions.CUSTOM, servers, useMockServer = true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context,
                LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    ConnectionInformationScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior())
                }
            }
        }
    }

    private fun assertEndpointVisible(label: String, url: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(url))
        compose.onNodeWithText(url).assertIsDisplayed()
    }
}
