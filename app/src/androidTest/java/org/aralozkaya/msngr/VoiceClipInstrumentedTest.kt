package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    }

    @Test fun recordsPreviewsAndSendsFromTheComposer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.RECORD_AUDIO)
        var sent: org.aralozkaya.msngr.protocol.VoiceClip? = null
        compose.setContent { MaterialTheme { VoiceClipComposerButton(true) { clip ->
            sent = clip; Result.success(Unit)
        } } }
        compose.onNodeWithContentDescription("Record voice clip").performClick()
        compose.onNodeWithText("Start recording").performClick()
        Thread.sleep(500)
        compose.onNodeWithText("Stop recording").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Play voice clip"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play voice clip").assertIsDisplayed()
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "voice-clip-recorder-ui.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithText("Send voice clip").performClick()
        compose.waitUntil(5_000) { sent != null }
        assertNotNull(MsnVoiceClip.parse(sent!!.bytes()))
        assertTrue(sent!!.durationMillis in 1..15_000)
    }
}
