package org.aralozkaya.msngr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.EmotionSoundService
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in exchange with a signed-in native test peer. Credentials enter only through a private file. */
class EmotionSoundLiveInteropInstrumentedTest {
    @Test fun sendsNativeStyledAndCustomSoundsAndReceivesTheDesktopReply() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("emotionSoundInterop") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "emotion-sound-interop.credentials")
        val config = try { JSONObject(file.readText()) } finally { file.delete() }
        val client = LiveMsnp18Client()
        try {
            val server = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
            client.signIn(SignInRequest(config.getString("email"), config.getString("password"), server, Presence.Busy)).getOrThrow()
            val peer = config.getString("peer")
            val style = NativeTextStyle(italic = true, underline = true, colorRgb = 0x336699)
            client.sendStyledMessage(peer, "\u0004#[b][c=#FF6600]EMOTION-LIVE-ANDROID[/c][/b]", null, style).getOrThrow()
            val (custom, _) = EmotionSoundService(server.emotionSoundServer).download(org.aralozkaya.msngr.protocol.EmotionSound("3224D7DF910D", "CHEN"))
            client.sendStyledMessage(peer, MsnEmotionSound.message(custom), null, style).getOrThrow()
            println("EMOTION-LIVE-READY")
            val reply = withTimeout(180_000) { client.messages.first { messages ->
                messages[peer]?.any { it.author == MessageAuthor.Contact && it.text.contains("EMOTION-LIVE-DESKTOP") } == true
            }.getValue(peer).first { it.author == MessageAuthor.Contact && it.text.contains("EMOTION-LIVE-DESKTOP") } }
            assertEquals("builtin-35", reply.emotionSound!!.sound.id)
            val parsed = MessengerPlusTextCodec.parse(reply.emotionSound!!.displayText)
            assertEquals("EMOTION-LIVE-DESKTOP", parsed.text)
            assertTrue(parsed.styleSpans.any { it.style.bold && it.style.foreground?.argb == 0xff00aa44.toInt() })
            assertTrue(reply.autoPlayEmotionSound)
            println("EMOTION-LIVE-VERIFIED")
        } finally { client.close() }
    }
}
