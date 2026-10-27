package org.aralozkaya.msngr

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
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

    @Test fun winkDialogUsesMediaVolumeAndHonorsTheUserSoundSwitch() {
        val reference = installedFixture()
        WinkPrefs.update(context, WinkSettings(sounds = false))
        val web = AtomicReference<WinkWebView>()
        compose.setContent { MaterialTheme { WinkOverlay(reference, {}, onPlayerCreated = web::set) } }
        compose.waitUntil(10_000) { web.get() != null }
        compose.runOnIdle {
            val window = generateSequence(web.get().parent) { it.parent }
                .filterIsInstance<androidx.compose.ui.window.DialogWindowProvider>().first().window
            assertEquals(android.media.AudioManager.STREAM_MUSIC, window.volumeControlStream)
        }
        val volume = AtomicReference<String?>()
        compose.waitUntil(10_000) {
            compose.runOnIdle { web.get().evaluateJavascript("window.winkPlayer?.ruffle().volume?.toString()", volume::set) }
            volume.get() == "\"0\""
        }
    }

    @Test fun openingTheEmoticonMenuKeepsTheEditorAndKeyboardFocused() {
        installedFixture()
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            ChatScreen("", {}, Contact("peer", "Peer", "peer@example.test", Presence.Online),
                messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {})
        } } }
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Message input"))
            .perform(androidx.test.espresso.action.ViewActions.click())
        var editor: android.view.View? = null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Message input"))
            .check { view, error -> if (error != null) throw error; editor = view }
        fun keyboardVisible(): Boolean {
            var visible = false
            compose.runOnIdle {
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(editor!!)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            return visible
        }
        compose.waitUntil(5_000) { keyboardVisible() }
        compose.onNodeWithContentDescription("Show emoticons").performTouchInput { longClick() }
        compose.onNodeWithText("Winks").assertIsDisplayed()
        compose.runOnIdle { assertTrue(editor!!.hasFocus()) }
        assertTrue("The menu must leave the keyboard visible", keyboardVisible())
    }

    @Test fun onlyCustomWinksOfferDeleteAndDeletingRefreshesThePicker() {
        installedFixture()
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            WinkPicker(true, true, {}) { _, _ -> Result.success(Unit) }
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bouncy Ball").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bouncy Ball").performTouchInput { longClick() }
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Screen Punch").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Screen Punch").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Bouncy Ball").assertIsDisplayed()
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
        compose.onNodeWithText("Import a file").assertDoesNotExist()
        compose.onNodeWithText("Auto-play").assertDoesNotExist()
        compose.onNodeWithText("Sound").assertDoesNotExist()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bouncy Ball").fetchSemanticsNodes().isNotEmpty() }
        // Offline contacts can still preview, while tapping never sends a wink.
        compose.onNodeWithText("Bouncy Ball").performTouchInput { longClick() }
        compose.onNodeWithText("Preview").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            java.io.File(context.getExternalFilesDir(null), "wink-picker.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; image.recycle()
        }
    }

    @Test fun holdingAWinkPreviewsWithoutSendingAndTapStillSends() {
        installedFixture()
        WinkPrefs.update(context, WinkSettings(playWinks = false, sounds = false))
        val visible = mutableStateOf(true)
        val sends = java.util.concurrent.atomic.AtomicInteger(0)
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            Box(Modifier.fillMaxSize()) {
                WinkPicker(visible.value, true, { visible.value = false }) { _, _ ->
                    sends.incrementAndGet(); Result.success(Unit)
                }
            }
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bouncy Ball").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bouncy Ball").performTouchInput { longClick() }
        compose.onNodeWithText("Preview").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close wink").assertIsDisplayed()
        assertEquals(0, sends.get())
        compose.onNodeWithContentDescription("Close wink").performClick()
        compose.onNodeWithTag("wink_picker").assertIsDisplayed()
        compose.onNodeWithText("Bouncy Ball").performClick()
        compose.waitUntil(10_000) { sends.get() == 1 && !visible.value }
        assertEquals(1, sends.get())
    }

    @Test fun winkSheetTracksSavesAcrossSendersAndManualImportsAndProtectsDefaults() {
        SessionPrefs.completeLogin(context, "wink-save-${System.nanoTime()}@example.test", "mock-password", false,
            ProviderOptions.ESCARGOT, ServerOverrides(), true)
        val account = context.winkAccount()!!
        val bytes = instrumentation.context.assets.open("winks/screen-punch.cab").use { it.readBytes() }
        val reference = org.aralozkaya.msngr.protocol.WinkReference(
            org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("peer@example.test", bytes, 8), "Screen Punch")
        context.winkStore().receive(account, "peer", reference, bytes)
        val bundled = context.winkStore().catalog(account).first { it.name == "Bouncy Ball" }
        val otherSender = reference.copy(metadata = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("other@example.test", bytes, 8))
        val manualImport = reference.copy(metadata = org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("catalog@example.test", bytes, 8,
            location = "manual.cab"), name = "Imported wink")
        val messages = listOf(reference, otherSender, bundled)
            .mapIndexed { index, wink -> org.aralozkaya.msngr.data.ChatMessage(index.toLong(), "peer",
                org.aralozkaya.msngr.data.MessageAuthor.Contact, "Wink", 1, wink = wink) }
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(16.dp)) {
                messages.forEach { MessageBubble(it, useOriginalEmoticons = true) }
            }
        } } }
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Add to saved winks").assertDoesNotExist()
        compose.onNodeWithTag("wink_message_0").performClick()
        compose.onNodeWithTag("wink_message_sheet").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add to saved winks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to saved winks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove from saved winks").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(context.winkStore().isSaved(account, reference.metadata))
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("wink_message_sheet").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithTag("wink_message_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove from saved winks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove from saved winks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add to saved winks").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(context.winkStore().isSaved(account, reference.metadata))

        // A catalog import changes the open sheet even with different sender/name metadata.
        context.winkStore().install(account, manualImport, bytes)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove from saved winks").fetchSemanticsNodes().isNotEmpty() }
        context.winkStore().clearReceived(account)
        compose.onNodeWithText("Remove from saved winks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add to saved winks").fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(bytes, context.winkStore().bytes(account, reference.metadata))
        compose.onNodeWithText("Play").assertIsDisplayed()

        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("wink_message_sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("wink_message_2").performClick()
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText("Add to saved winks").assertDoesNotExist()
        compose.onNodeWithText("Remove from saved winks").assertDoesNotExist()
    }

    @Test fun failedWinkIconOpensTheSheetAndRetriesWithOtherActionsDisabled() {
        SessionPrefs.completeLogin(context, "wink-retry-${System.nanoTime()}@example.test", "mock-password", false,
            ProviderOptions.ESCARGOT, ServerOverrides(), true)
        val bytes = instrumentation.context.assets.open("winks/screen-punch.cab").use { it.readBytes() }
        val reference = org.aralozkaya.msngr.protocol.WinkReference(
            org.aralozkaya.msngr.protocol.MsnDisplayPicture.create("peer@example.test", bytes, 8), "Screen Punch")
        var message by mutableStateOf(org.aralozkaya.msngr.data.ChatMessage(1, "peer",
            org.aralozkaya.msngr.data.MessageAuthor.Contact, "Wink", 1, wink = reference,
            winkTransferError = "Wink download timed out."))
        compose.setContent { MaterialTheme { Surface {
            MessageBubble(message, useOriginalEmoticons = true, onRetry = {
                message = message.copy(isWinkPending = true, winkTransferError = null)
            })
        } } }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Wink download timed out.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wink download failed").assertIsDisplayed().performClick()
        compose.onNodeWithTag("wink_message_sheet").assertIsDisplayed()
        compose.onNodeWithText("Wink download timed out.").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed().assertIsNotEnabled()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add to saved winks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to saved winks").assertIsNotEnabled()
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wink download failed").assertDoesNotExist()
        compose.onNodeWithTag("wink_message_sheet").assertIsDisplayed()
        assertEquals(1L, message.id)
        assertTrue(message.isWinkPending)
        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add to saved winks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to saved winks").assertIsNotEnabled()

        context.winkStore().receive(context.winkAccount()!!, message.contactId, reference, bytes)
        compose.runOnIdle { message = message.copy(isWinkPending = false) }
        compose.onNodeWithText("Play").assertIsEnabled()
        compose.onNodeWithText("Add to saved winks").assertIsEnabled()
        compose.onNodeWithContentDescription("Wink download failed").assertDoesNotExist()
    }

    @Test fun matchingSavedAndDefaultWinksDoNotBypassDownloadFailure() {
        val reference = installedFixture()
        val account = context.winkAccount()!!
        val bundled = context.winkStore().catalog(account).first { it.name == "Bouncy Ball" }
        var message by mutableStateOf(org.aralozkaya.msngr.data.ChatMessage(1, "peer",
            org.aralozkaya.msngr.data.MessageAuthor.Contact, "Wink", 1, wink = reference,
            winkTransferError = "Wink download timed out."))
        compose.setContent { MaterialTheme { Surface {
            MessageBubble(message, useOriginalEmoticons = true, onRetry = {})
        } } }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wink download failed").assertIsDisplayed().performClick()
        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove from saved winks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove from saved winks").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled()
        assertTrue(context.winkStore().isSaved(account, reference.metadata))
        compose.onNodeWithContentDescription("Close wink").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("wink_message_sheet").fetchSemanticsNodes().isEmpty() }

        compose.runOnIdle { message = message.copy(wink = bundled) }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wink download failed").assertIsDisplayed()
        compose.onNodeWithTag("wink_message_1").performClick()
        compose.onNodeWithText("Play").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Add to saved winks").assertDoesNotExist()
        compose.onNodeWithText("Remove from saved winks").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close wink").assertDoesNotExist()
    }

    @Test fun autoplayLivesInEmoticonsAndWinksSettingsAndPersists() {
        installedFixture()
        WinkPrefs.update(context, WinkSettings(playWinks = true, sounds = false))
        compose.setContent { MaterialTheme {
            org.aralozkaya.msngr.pages.components.SecondaryPageScaffold(
                org.aralozkaya.msngr.pages.SettingsDestination.Emoticons.title, {}) { padding, scroll ->
                org.aralozkaya.msngr.pages.EmoticonSettingsScreen(padding, scroll)
            }
        } }
        compose.onAllNodesWithText("Emoticons & Winks").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Play winks automatically").performScrollTo().performClick()
        assertFalse(WinkPrefs.state(context).value.playWinks)
        WinkPrefs.reload(context)
        assertFalse(WinkPrefs.state(context).value.playWinks)
    }
}
