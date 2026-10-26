package org.aralozkaya.msngr

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnWinkPackage
import org.aralozkaya.msngr.pages.components.KeyboardSheetScaffold
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class WinkInstrumentedTest {
    @org.junit.After fun clearTransientTestSession() { SessionPrefs.logout() }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun movie() = MsnWinkPackage.read(instrumentation.context.assets.open("winks/screen-punch.cab").use { it.readBytes() })

    @Test fun ruffleActuallyLoadsTheOriginalSwfMutesAndDestroys() {
        val wink = movie()
        val ready = AtomicLong(0)
        val failure = AtomicReference<String?>(null)
        val web = AtomicReference<WinkWebView>()
        val visible = mutableStateOf(true)
        compose.setContent { if (visible.value) AndroidView(factory = {
            WinkWebView(it, wink.animation, true, ready::set, { failure.set("Ruffle failed") }).also(web::set)
        }, modifier = Modifier.fillMaxSize(), onRelease = { it.release() }) }
        compose.waitUntil(45_000) { ready.get() > 0 || failure.get() != null }
        assertNull(failure.get())
        compose.runOnIdle { assertTrue("WebView height=${web.get().height} measured=${web.get().measuredHeight}", web.get().height > 0) }
        assertTrue(ready.get() in 500..12_000)
        val volume = AtomicReference<String?>()
        compose.runOnIdle { web.get().evaluateJavascript("window.winkPlayer.ruffle().volume.toString()", volume::set) }
        compose.waitUntil(5_000) { volume.get() != null }
        assertEquals("\"0\"", volume.get())
        compose.runOnIdle {
            for (uri in listOf("https://example.com/", "file:///data/local/tmp/test", "content://private", WinkWebView.ORIGIN + "/../other"))
                assertEquals(403, web.get().response(Uri.parse(uri)).statusCode)
            assertEquals(200, web.get().response(Uri.parse(WinkWebView.ORIGIN + "/ruffle/ruffle.js")).statusCode)
            web.get().mute(false)
        }
        // Save a screenshot only of this isolated fixture, never the login/account UI.
        var painted = false
        repeat(20) {
            if (!painted) {
                Thread.sleep(200)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                var marks = 0
                for (y in screenshot.height / 5 until screenshot.height * 4 / 5 step 8)
                    for (x in screenshot.width / 6 until screenshot.width * 5 / 6 step 8) {
                        val pixel = screenshot.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) < 200 || android.graphics.Color.green(pixel) < 200 || android.graphics.Color.blue(pixel) < 200) marks++
                    }
                if (marks > 80) {
                    painted = true
                    java.io.File(context.getExternalFilesDir(null), "wink-playback.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                screenshot.recycle()
            }
        }
        assertTrue("Ruffle must paint the animation, not only load metadata", painted)
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5_000) { web.get().released }
        assertTrue(web.get().released)
    }

    private fun installedFixture(): org.aralozkaya.msngr.protocol.WinkReference {
        SessionPrefs.completeLogin(context, "wink-lifecycle@example.test", "mock-password", false,
            ProviderOptions.ESCARGOT, ServerOverrides(), true)
        val account = context.winkAccount()!!
        val bytes = instrumentation.context.assets.open("winks/screen-punch.cab").use { it.readBytes() }
        val reference = org.aralozkaya.msngr.protocol.WinkReference(
            org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("wink-lifecycle@example.test", bytes, 8), "Screen Punch")
        context.winkStore().install(account, reference, bytes)
        return reference
    }

    @Test fun overlayStopsAndReleasesAtThePlaybackLimit() {
        val reference = installedFixture()
        val web = AtomicReference<WinkWebView>()
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible.value) WinkOverlay(reference, { visible.value = false }, onPlayerCreated = web::set) } }
        compose.waitUntil(10_000) { web.get() != null }
        compose.waitUntil(20_000) { web.get().released }
        assertFalse(visible.value)
    }

    @Test fun leavingTheActivityDestroysTheOverlayPlayer() {
        val reference = installedFixture()
        val web = AtomicReference<WinkWebView>()
        val dismissed = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent { MaterialTheme { WinkOverlay(reference, { dismissed.set(true) }, onPlayerCreated = web::set) } }
        compose.waitUntil(10_000) { web.get() != null }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(dismissed.get())
        assertTrue(web.get().released)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun silentVibrateAndTheUserSoundSwitchMuteWinks() {
        assertTrue(shouldMuteWink(true, android.media.AudioManager.RINGER_MODE_SILENT))
        assertTrue(shouldMuteWink(true, android.media.AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(shouldMuteWink(false, android.media.AudioManager.RINGER_MODE_NORMAL))
        assertFalse(shouldMuteWink(true, android.media.AudioManager.RINGER_MODE_NORMAL))
    }

    @Test fun composerOpensWinksFromTheHandwritingMenuAndUsesTheSharedPicker() {
        installedFixture()
        val peer = Contact("selin", "Selin", "selin@msngr.dev", Presence.Offline)
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            ChatScreen("", {}, peer, messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {})
        } } }
        compose.onNodeWithContentDescription("Show emoticons").performTouchInput { longClick() }
        compose.onNodeWithText("Winks").performClick()
        compose.onNodeWithTag("wink_picker").assertIsDisplayed()
        compose.onNodeWithText("Get more winks").assertIsDisplayed()
        compose.onNodeWithText("Import a file").assertIsDisplayed()
        compose.onNodeWithText("Auto-play").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bouncy Ball").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bouncy Ball").assertIsNotEnabled()
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            java.io.File(context.getExternalFilesDir(null), "wink-picker.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; image.recycle()
        }
    }
}
