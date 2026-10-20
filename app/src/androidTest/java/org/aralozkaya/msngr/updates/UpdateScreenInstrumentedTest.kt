package org.aralozkaya.msngr.updates

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.pages.UpdateSettingsScreen
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

class UpdateScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun releaseDetailsSkipAndDownloadChoicesWorkAtLargeFontSize() {
        val target = compose.activity
        val prefix = "update_screen_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val release = AppRelease(checkNotNull(ReleaseVersion.parse("v2.0.0")), 1_792_337_215_000L,
            "A faster, friendlier Messenger.\n• Connection fixes\n• New colors", false,
            RELEASES_PAGE, "$RELEASES_PAGE/download/v2.0.0/Msngr-release.apk", 12345)
        val manager = UpdateManager(context, supported = true, installedCode = 1, releaseSource = { listOf(release) })
        manager.configure(UpdatePreferences(enabled = false))
        runBlocking { manager.check(manual = true) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MsngrTheme {
                    UpdateSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior(), manager)
                }
            }
        }
        compose.onNodeWithTag("updates-page-badge").assertExists()
        File(target.getExternalFilesDir(null), "updates-large-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("download-update"))
        compose.onNodeWithTag("download-update").performClick()
        compose.onNodeWithText("How would you like to update?").assertExists()
        compose.onNodeWithText("Download and install").assertExists()
        compose.onNodeWithText("Save APK to a folder").assertExists()
        File(target.getExternalFilesDir(null), "update-download-choice-large-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("skip-update"))
        compose.onNodeWithTag("skip-update").performClick()
        compose.runOnIdle { assertEquals("v2.0.0", manager.state.value.preferences.skippedTag) }
        compose.onNodeWithTag("skip-update").performClick()
        compose.runOnIdle { assertEquals(null, manager.state.value.preferences.skippedTag) }
        context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun debugBuildExplainsWhyTheUpdaterIsDisabled() {
        compose.setContent { MsngrTheme { UpdateSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) } }
        compose.onNodeWithText("Signed release required").assertExists()
        compose.onNodeWithTag("check-for-updates").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("automatic-updates"))
        compose.onNodeWithTag("automatic-updates").assertIsNotEnabled()
    }
}
