package org.aralozkaya.msngr.updates

import android.os.Build
import androidx.test.filters.SdkSuppress
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.pages.UpdateSettingsScreen
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
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
            "### What's new\n\n- **Connection fixes**\n- New colors\n\n[Full changelog]($RELEASES_PAGE)", false,
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
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("What's new").assertExists() }.isSuccess }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("release-notes"))
        compose.onNodeWithText("Connection fixes").assertExists()
        compose.onNodeWithText("Full changelog").assertExists()
        File(target.getExternalFilesDir(null), "release-notes-large-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("update-split-button"))
        compose.onNodeWithTag("download-update").assertExists().assertIsEnabled()
        compose.onNodeWithTag("update-more-options").assertExists().assertIsEnabled()
        val leadingBounds = compose.onNodeWithTag("download-update").fetchSemanticsNode().boundsInRoot
        val trailingBounds = compose.onNodeWithTag("update-more-options").fetchSemanticsNode().boundsInRoot
        assertTrue(trailingBounds.left >= leadingBounds.right)
        assertEquals(leadingBounds.top, trailingBounds.top, 1f)
        assertEquals(leadingBounds.height, trailingBounds.height, 1f)
        File(target.getExternalFilesDir(null), "release-split-button-large-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("skip-update").assertDoesNotExist()
        compose.onNodeWithTag("view-release-on-github").assertDoesNotExist()
        compose.onNodeWithTag("download-update").performClick()
        compose.onNodeWithText("How would you like to update?").assertExists()
        compose.onNodeWithTag("download-choice-split-button").assertExists()
        compose.onNodeWithTag("download-choice-install").assertExists()
        compose.onNodeWithText("Download and install").assertExists()
        compose.onNodeWithTag("download-choice-more-options").assertExists()
        compose.onNodeWithTag("download-choice-save").assertDoesNotExist()
        val dialogLeadingBounds = compose.onNodeWithTag("download-choice-install").fetchSemanticsNode().boundsInRoot
        val dialogTrailingBounds = compose.onNodeWithTag("download-choice-more-options").fetchSemanticsNode().boundsInRoot
        assertTrue(dialogTrailingBounds.left >= dialogLeadingBounds.right)
        assertEquals(dialogLeadingBounds.top, dialogTrailingBounds.top, 1f)
        assertEquals(dialogLeadingBounds.height, dialogTrailingBounds.height, 1f)
        File(target.getExternalFilesDir(null), "update-download-choice-large-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("download-choice-more-options").performClick()
        compose.onNodeWithTag("download-choice-save").assertExists()
        compose.onNodeWithText("Save APK to a folder").assertExists()
        compose.onNodeWithTag("download-choice-more-options").performClick()
        compose.onNodeWithTag("download-choice-save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("update-more-options").performClick()
        compose.onNodeWithTag("skip-update").assertExists()
        compose.onNodeWithText("Skip this version").assertExists()
        compose.onNodeWithTag("view-release-on-github").assertExists()
        compose.onNodeWithText("View release on GitHub").assertExists()
        compose.onNodeWithTag("skip-update").performClick()
        compose.runOnIdle { assertEquals("v2.0.0", manager.state.value.preferences.skippedTag) }
        compose.onNodeWithTag("skip-update").assertDoesNotExist()
        compose.onNodeWithTag("update-more-options").performClick()
        compose.onNodeWithText("Stop skipping this version").assertExists()
        compose.onNodeWithTag("skip-update").performClick()
        compose.runOnIdle { assertEquals(null, manager.state.value.preferences.skippedTag) }
        compose.onNodeWithTag("skip-update").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("All releases on GitHub"))
        compose.onNodeWithText("All releases on GitHub").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("automatic-updates"))
        compose.onNodeWithTag("automatic-updates").assertIsOff()
        compose.onNodeWithTag("update-interval").assertDoesNotExist()
        compose.onNodeWithTag("automatic-updates").performClick().assertIsOn()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("update-interval"))
        compose.onNodeWithTag("update-interval").assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("include-prereleases"))
        val expandedGap = compose.onNodeWithTag("include-prereleases").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithTag("update-interval").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(with(compose.density) { 12.dp.toPx() }, expandedGap, 1f)
        File(target.getExternalFilesDir(null), "update-settings-expanded.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("automatic-updates"))
        compose.onNodeWithTag("automatic-updates").performClick().assertIsOff()
        compose.onNodeWithTag("update-interval").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("include-prereleases"))
        compose.onNodeWithTag("include-prereleases").assertIsEnabled()
        context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun debugBuildExplainsWhyTheUpdaterIsDisabled() {
        compose.setContent { MsngrTheme { UpdateSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior()) } }
        compose.onNodeWithText("Signed release required").assertExists()
        compose.onNodeWithTag("check-for-updates").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("automatic-updates"))
        compose.onNodeWithTag("automatic-updates").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("include-prereleases"))
        val collapsedGap = compose.onNodeWithTag("include-prereleases").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithTag("automatic-updates").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(with(compose.density) { 12.dp.toPx() }, collapsedGap, 1f)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun missingApkDisablesLeadingActionWhileTrailingOptionsRemainAvailable() {
        val target = compose.activity
        val prefix = "update_screen_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val release = AppRelease(checkNotNull(ReleaseVersion.parse("v2.0.0")), 1_792_337_215_000L,
            "Release notes", false, RELEASES_PAGE, apkUrl = null, apkSize = 0)
        val manager = UpdateManager(context, supported = true, installedCode = 1, releaseSource = { listOf(release) })
        manager.configure(UpdatePreferences(enabled = false))
        runBlocking { manager.check(manual = true) }
        compose.setContent {
            MsngrTheme {
                UpdateSettingsScreen(PaddingValues(), TopAppBarDefaults.pinnedScrollBehavior(), manager)
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("update-split-button"))
        compose.onNodeWithTag("download-update").assertIsNotEnabled()
        compose.onNodeWithTag("update-more-options").assertIsEnabled().performClick()
        compose.onNodeWithTag("skip-update").assertExists().assertIsEnabled()
        compose.onNodeWithTag("view-release-on-github").assertExists().assertIsEnabled()
        compose.onNodeWithTag("skip-update").performClick()
        compose.runOnIdle { assertEquals("v2.0.0", manager.state.value.preferences.skippedTag) }
        context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
