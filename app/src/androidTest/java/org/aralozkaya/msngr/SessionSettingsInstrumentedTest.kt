package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import org.aralozkaya.msngr.pages.ConnectionSettingsScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SessionSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun sessionToggleIsAccessibleAtLargeTextAndSurvivesReopeningSettings() {
        val target = compose.activity
        val context = object : ContextWrapper(target.createDeviceProtectedStorageContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "msngr_session_secure_keyset") {
                    target.applicationContext.getSharedPreferences(name, mode)
                } else super.getSharedPreferences("session_settings_test_$name", mode)
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        val version = mutableIntStateOf(0)
        val tag = "sign_out_other_sessions_on_sign_in"
        SessionPrefs.logout()
        SessionPrefs.rememberedProfiles(context).forEach {
            SessionPrefs.disableRememberedCredentials(context, account = it.accountIdentity)
        }
        SessionPrefs.completeLogin(context, "sessions-settings@example.com", "test-password", true,
            ProviderOptions.ESCARGOT, ServerOverrides(), useMockServer = true)
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides context,
                    LocalDensity provides Density(density.density, 2f)) {
                    MaterialTheme {
                        key(version.intValue) {
                            ConnectionSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior(), {})
                        }
                    }
                }
            }
            fun scrollToToggle() = compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
            scrollToToggle()
            compose.onNodeWithText("Sign me out of all other places when I sign in to Msngr").assertExists()
            compose.onNodeWithTag(tag).assertIsOff().performClick().assertIsOn()
            compose.runOnIdle {
                assertTrue(SessionPrefs.signsOutOtherSessionsOnSignIn(context))
                version.intValue++
            }
            scrollToToggle()
            compose.onNodeWithTag(tag).assertIsOn()
            File(target.getExternalFilesDir(null), "session-settings.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag(tag).performClick().assertIsOff()
            compose.runOnIdle { assertFalse(SessionPrefs.signsOutOtherSessionsOnSignIn(context)) }
        } finally {
            SessionPrefs.disableRememberedCredentials(context)
            SessionPrefs.logout()
            AccountPreferences.reload(target)
        }
    }
}
