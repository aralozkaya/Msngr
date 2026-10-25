package org.aralozkaya.msngr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnVoiceClip
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

/** Opt-in: sends synthetic audio to the explicitly configured desktop test account. */
class VoiceClipLiveInteropInstrumentedTest {
    @Test fun exchangesSirenClipsWithNativeMessenger() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("voiceInterop") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "voice-interop.credentials")
        val config = try { JSONObject(input.readText()) } finally { input.delete() }
        val peer = config.getString("peer")
        val client = LiveMsnp18Client()
        val phase = File(context.getExternalFilesDir(null), "voice-interop-phase.txt")
        try {
            val result = client.signIn(SignInRequest(config.getString("email"), config.getString("password"),
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()), Presence.Busy))
            assertTrue("Real-service sign-in must succeed", result.isSuccess)
            withTimeout(60_000) { client.contacts.first { list -> list.any { it.email.equals(peer, true) && it.presence.isOnline } } }
            assertTrue(client.sendMessageWithResult(peer, "VOICE-PROBE-READY").isSuccess)
            phase.writeText("ready-for-native-offer")
            val pcm = ByteArray(64_000)
            for (sample in 0 until pcm.size / 2) {
                val value = (sin(2 * Math.PI * 660 * sample / 16_000) * 6_000).toInt()
                pcm[sample * 2] = value.toByte(); pcm[sample * 2 + 1] = (value shr 8).toByte()
            }
            val outgoing = MsnVoiceClip.encode(pcm)
            assertTrue("Clip announcement must reach the switchboard", client.sendVoiceClip(peer, outgoing).isSuccess)
            phase.writeText("sent-android-clip-waiting-for-native")
            val messages = withTimeout(120_000) { client.messages.first { conversations ->
                conversations[peer.lowercase()].orEmpty().any {
                    it.author == org.aralozkaya.msngr.data.MessageAuthor.Contact && it.voiceClip != null
                }
            } }
            val incoming = messages.getValue(peer.lowercase()).last {
                it.author == org.aralozkaya.msngr.data.MessageAuthor.Contact && it.voiceClip != null
            }.voiceClip!!
            assertTrue(incoming.durationMillis in 1..15_000)
            assertTrue(MsnVoiceClip.decode(incoming).isNotEmpty())
            File(context.getExternalFilesDir(null), "voice-interop-received.wav").writeBytes(incoming.bytes())
            File(context.getExternalFilesDir(null), "voice-interop-sent.wav").writeBytes(outgoing.bytes())
            phase.writeText("received-and-decoded-native-clip")
            delay(15_000) // Keep the offered object available for every native endpoint's request.
            phase.writeText("complete")
        } finally { client.close() }
    }
}
