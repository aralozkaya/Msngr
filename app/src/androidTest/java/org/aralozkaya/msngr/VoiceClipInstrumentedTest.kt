package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.protocol.MsnVoiceClip
import org.aralozkaya.msngr.protocol.SirenCodec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class VoiceClipInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun nativeWave() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("voice-clips/native-siren.wav").use { it.readBytes() }

    @Test fun decodesCapturedNativeSirenAndEncodesTheFifteenSecondLimit() {
        val native = MsnVoiceClip.parse(nativeWave())!!
        val decoded = MsnVoiceClip.decode(native)
        assertEquals(86_400, decoded.size)
        assertTrue(decoded.any { it != 0.toByte() })
        val pcm = ByteArray(480_000)
        for (sample in 0 until pcm.size / 2) {
            val value = (sin(2 * Math.PI * 440 * sample / 16_000) * 8_000).toInt()
            pcm[sample * 2] = value.toByte(); pcm[sample * 2 + 1] = (value shr 8).toByte()
        }
        val clip = MsnVoiceClip.encode(pcm)
        assertEquals(15_000, clip.durationMillis)
        assertEquals(30_060, clip.wav.size)
        val decodedTone = MsnVoiceClip.decode(clip)
        assertEquals(pcm.size, decodedTone.size)
        assertTrue(decodedTone.any { it != 0.toByte() })
        // Compressed output must still contain the tone, independent of encoder delay/phase.
        var sine = 0.0; var cosine = 0.0
        for (sample in 1_000 until 16_000) {
            val value = ((decodedTone[sample * 2].toInt() and 255) or (decodedTone[sample * 2 + 1].toInt() shl 8)).toShort().toDouble()
            sine += value * sin(2 * Math.PI * 440 * sample / 16_000)
            cosine += value * kotlin.math.cos(2 * Math.PI * 440 * sample / 16_000)
        }
        assertTrue(abs(sine) + abs(cosine) > 10_000_000)
        for (seed in 0..100) {
            val bytes = java.util.Random(seed.toLong()).let { random -> ByteArray(40).also(random::nextBytes) }
            // Malformed frames may be rejected or concealed, but must never overrun native tables.
            SirenCodec.decode(bytes)?.let { assertEquals(640, it.size) }
        }
    }

    @Test fun rendersIncomingClipWithExplicitPlaybackAndDuration() {
        val clip = MsnVoiceClip.parse(nativeWave())!!
        compose.setContent { MaterialTheme { MessageBubble(ChatMessage(1, "peer", MessageAuthor.Contact,
            "Voice clip", 1, voiceClip = clip), false) } }
        compose.onNodeWithContentDescription("Play voice clip").assertIsDisplayed().performClick()
        compose.onNodeWithText("2.7 s").assertIsDisplayed()
    }

    @Test fun groupComposerHasNoMicrophone() {
        val group = org.aralozkaya.msngr.data.Contact("group", "Group", "group@circle.test",
            org.aralozkaya.msngr.enums.Presence.Online, isGroup = true)
        compose.setContent { MaterialTheme { ChatScreen("", {}, group, messages = emptyList(),
            isHistoryLoaded = true, canLoadOlderMessages = false, isTyping = false,
            onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {}, onSendTyping = {}, onSendNudge = {},
            onLoadOlderMessages = {}) } }
        compose.onNodeWithContentDescription("Record voice clip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    private fun grantMicrophone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.RECORD_AUDIO)
    }

    private fun composer(onSend: suspend (org.aralozkaya.msngr.protocol.VoiceClip) -> Result<Unit> = { Result.success(Unit) }) {
        grantMicrophone()
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                VoiceClipComposer(true, onSend = onSend, textComposer = { Text("Message editor") })
            }
        } }
    }

    private fun holdMicrophone() {
        compose.onNodeWithContentDescription("Record voice clip").performTouchInput { down(center) }
        compose.onNodeWithText("Release to review").assertIsDisplayed()
        compose.onNodeWithText("Message editor").assertDoesNotExist()
        Thread.sleep(500)
    }

    private fun awaitPreview() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Play voice clip"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Delete voice clip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send voice clip").assertIsDisplayed()
    }

    @Test fun holdReleasePreviewsAndSendsFromTheComposer() {
        var sent: org.aralozkaya.msngr.protocol.VoiceClip? = null
        composer { clip ->
            sent = clip; Result.success(Unit)
        }
        holdMicrophone()
        compose.onNodeWithContentDescription("Recording voice clip").performTouchInput { up() }
        awaitPreview()
        compose.onNodeWithContentDescription("Play voice clip").assertIsDisplayed()
        saveScreenshot("voice-clip-review-ui.png")
        compose.onNodeWithContentDescription("Send voice clip").performClick()
        compose.waitUntil(5_000) { sent != null }
        assertNotNull(MsnVoiceClip.parse(sent!!.bytes()))
        assertTrue(sent!!.durationMillis in 1..15_000)
        compose.onNodeWithText("Message editor").assertIsDisplayed()
    }

    @Test fun fingerJitterDuringLockEntryDoesNotLockAndReleaseStops() {
        composer()
        val microphone = compose.onNodeWithContentDescription("Record voice clip").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput { down(microphone.center) }
            // A real finger reports small moves while the lock slides in from behind the microphone.
            repeat(30) { frame ->
                compose.mainClock.advanceTimeBy(16)
                compose.onRoot().performTouchInput {
                    moveTo(Offset(microphone.center.x + (frame % 3 - 1) * 2f,
                        microphone.center.y + (frame % 5 - 2) * microphone.height / 6f))
                }
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription("Unlock recording").assertDoesNotExist()
            compose.onRoot().performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        awaitPreview()
    }

    @Test fun dragToLockSurvivesReleaseAndUnlockStopsWithoutAFinger() {
        composer()
        holdMicrophone()
        val microphone = compose.onNodeWithContentDescription("Recording voice clip")
        val lock = compose.onNodeWithContentDescription("Lock recording").fetchSemanticsNode().boundsInRoot.center
        val origin = microphone.fetchSemanticsNode().boundsInRoot.topLeft
        microphone.performTouchInput { moveTo(lock - origin); up() }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play voice clip").assertDoesNotExist()
        Thread.sleep(300)
        saveScreenshot("voice-clip-locked-ui.png")
        compose.onNodeWithContentDescription("Unlock recording").performClick()
        awaitPreview()
        compose.onNodeWithContentDescription("Delete voice clip").performClick()
        compose.onNodeWithText("Message editor").assertIsDisplayed()
    }

    @Test fun unlockWhileHeldKeepsRecordingUntilRelease() {
        composer()
        holdMicrophone()
        val microphone = compose.onNodeWithContentDescription("Recording voice clip")
        val lock = compose.onNodeWithContentDescription("Lock recording").fetchSemanticsNode().boundsInRoot.center
        val origin = microphone.fetchSemanticsNode().boundsInRoot.topLeft
        microphone.performTouchInput { moveTo(lock - origin); moveTo(center) }
        compose.onNodeWithContentDescription("Unlock recording").performTouchInput { down(1, center); up(1) }
        compose.onNodeWithText("Release to review").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play voice clip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Recording voice clip").performTouchInput { up() }
        awaitPreview()
    }

    @Test fun accessibleClickStartsHandsFreeAndStopPreviews() {
        composer()
        compose.onNodeWithContentDescription("Record voice clip").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        Thread.sleep(300)
        compose.onNodeWithContentDescription("Stop recording").performClick()
        awaitPreview()
    }

    @Test fun sendFailureRetainsPreviewForRetryOrDeletion() {
        composer { Result.failure(IllegalStateException("Connection lost. Try again.")) }
        holdMicrophone()
        compose.onNodeWithContentDescription("Recording voice clip").performTouchInput { up() }
        awaitPreview()
        compose.onNodeWithContentDescription("Send voice clip").performClick()
        compose.onNodeWithText("Connection lost. Try again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play voice clip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete voice clip").performClick()
        compose.onNodeWithText("Message editor").assertIsDisplayed()
    }

    @Test fun disablingVoiceCaptureDiscardsAndStopsRecording() {
        grantMicrophone()
        val enabled = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            VoiceClipComposer(enabled.value, onSend = { Result.success(Unit) },
                textComposer = { Text("Message editor") })
        } }
        compose.onNodeWithContentDescription("Record voice clip").performSemanticsAction(SemanticsActions.OnClick) { it() }
        Thread.sleep(300)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Message editor").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Play voice clip").assertDoesNotExist()
    }

    @Test fun voiceBubblesHaveDistinctPlayersInLightAndDarkThemes() {
        val dark = mutableStateOf(false)
        val clip = MsnVoiceClip.parse(nativeWave())!!
        compose.setContent { MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    MessageBubble(ChatMessage(1, "peer", MessageAuthor.Contact, "Voice clip", 1, voiceClip = clip), false)
                    MessageBubble(ChatMessage(2, "peer", MessageAuthor.Me, "Voice clip", 1, voiceClip = clip), false)
                }
            }
        } }
        saveScreenshot("voice-clip-bubbles-light.png")
        compose.runOnIdle { dark.value = true }
        saveScreenshot("voice-clip-bubbles-dark.png")
    }

    @Test fun chatComposerKeepsHeldMicrophoneAndKeyboardInPlaceUntilReview() {
        grantMicrophone()
        val draft = mutableStateOf("Message")
        val peer = org.aralozkaya.msngr.data.Contact("peer", "Alex", "alex@example.test",
            org.aralozkaya.msngr.enums.Presence.Online)
        val clip = MsnVoiceClip.parse(nativeWave())!!
        compose.setContent { MaterialTheme {
            ChatScreen(draft.value, { draft.value = it }, peer, messages = listOf(
                ChatMessage(1, "peer", MessageAuthor.Contact, "Voice clip", 1, voiceClip = clip),
                ChatMessage(2, "peer", MessageAuthor.Me, "Voice clip", 2, voiceClip = clip)),
                isHistoryLoaded = true, canLoadOlderMessages = false, isTyping = false,
                onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {}, onSendTyping = {}, onSendNudge = {},
                onSendVoiceClip = { Result.success(Unit) }, onLoadOlderMessages = {})
        } }
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        compose.onNodeWithContentDescription("Record voice clip").assertDoesNotExist()
        compose.runOnIdle { draft.value = "" }
        compose.onNodeWithContentDescription("Send message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Record voice clip").assertIsEnabled()
        compose.onNodeWithContentDescription("Message input").performClick()
        Thread.sleep(500)
        val before = compose.onNodeWithContentDescription("Record voice clip").fetchSemanticsNode().boundsInRoot.center
        holdMicrophone()
        val after = compose.onNodeWithContentDescription("Recording voice clip").fetchSemanticsNode().boundsInRoot.center
        assertEquals(before.y, after.y, 2f)
        saveScreenshot("voice-clip-chat-recording.png")
        val microphone = compose.onNodeWithContentDescription("Recording voice clip")
        val lock = compose.onNodeWithContentDescription("Lock recording").fetchSemanticsNode().boundsInRoot.center
        val origin = microphone.fetchSemanticsNode().boundsInRoot.topLeft
        microphone.performTouchInput { moveTo(lock - origin); up() }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unlock recording").performClick()
        awaitPreview()
        saveScreenshot("voice-clip-chat-review.png")
    }

    @Test fun backgroundingDiscardsAnActiveMicrophone() {
        composer()
        compose.onNodeWithContentDescription("Record voice clip").performSemanticsAction(SemanticsActions.OnClick) { it() }
        Thread.sleep(300)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText("Message editor").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play voice clip").assertDoesNotExist()
    }

    @Test fun reachingTheRecordingLimitOpensReviewWithoutSending() {
        var sent = false
        composer { sent = true; Result.success(Unit) }
        compose.onNodeWithContentDescription("Record voice clip").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(20_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Send voice clip"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        awaitPreview()
        assertFalse(sent)
        compose.onNodeWithContentDescription("Unlock recording").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete voice clip").performClick()
        compose.onNodeWithText("Message editor").assertIsDisplayed()
    }

    @Test fun releaseDuringRecordingEntryStillReachesReview() {
        composer()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Record voice clip").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("voice-recording-bar").assertExists()
            // Allow real AudioRecord data to arrive while the Compose entry animation is paused.
            Thread.sleep(200)
            compose.onNodeWithContentDescription("Recording voice clip").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        awaitPreview()
    }

    @Test fun deletingPreviewAnimatesOutAndRemovesItsActionsImmediately() {
        composer()
        holdMicrophone()
        compose.onNodeWithContentDescription("Recording voice clip").performTouchInput { up() }
        awaitPreview()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Delete voice clip").performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("voice-recording-bar").assertExists()
            compose.onNodeWithContentDescription("Delete voice clip").assertDoesNotExist()
            compose.onNodeWithContentDescription("Record voice clip").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithTag("voice-recording-bar").assertDoesNotExist()
            compose.onNodeWithText("Message editor").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun lockHoverAndLockingAnimateWhilePreservingTheHeldPointer() {
        composer()
        holdMicrophone()
        val microphone = compose.onNodeWithContentDescription("Recording voice clip")
        val origin = microphone.fetchSemanticsNode().boundsInRoot.topLeft
        val bounds = compose.onNodeWithContentDescription("Lock recording").fetchSemanticsNode().boundsInRoot
        val idlePixels = lockPixels("Lock recording")
        compose.mainClock.autoAdvance = false
        try {
            // Approach the lock without entering its activation bounds.
            microphone.performTouchInput { moveTo(Offset(bounds.center.x, bounds.bottom + bounds.height / 4) - origin) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription("Unlock recording").assertDoesNotExist()
            assertFalse("Approaching the target should animate its appearance",
                idlePixels.contentEquals(lockPixels("Lock recording")))
            microphone.performTouchInput { moveTo(bounds.center - origin) }
            compose.mainClock.advanceTimeBy(32)
            val lockingPixels = lockPixels("Unlock recording")
            compose.mainClock.advanceTimeBy(1_000)
            assertFalse("Locking should animate rather than swap instantly",
                lockingPixels.contentEquals(lockPixels("Unlock recording")))
            compose.onNodeWithContentDescription("Stop recording").performTouchInput { moveTo(center) }
            compose.onNodeWithContentDescription("Unlock recording").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithContentDescription("Recording voice clip").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription("Recording voice clip").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        awaitPreview()
    }

    private fun lockPixels(description: String): IntArray {
        val bitmap = compose.onNodeWithContentDescription(description).captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
