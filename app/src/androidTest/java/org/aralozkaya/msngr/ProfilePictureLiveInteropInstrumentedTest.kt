package org.aralozkaya.msngr

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.aralozkaya.msngr.protocol.Msnp18SocketTransport
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in private-account probe. Publishes only the explicitly supplied profile-picture fixtures. */
class ProfilePictureLiveInteropInstrumentedTest {
    @Test fun publishesFullQualityPicturesAndDownloadsTheNativeOriginal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("pictureInterop") == "true")
        val context = instrumentation.targetContext
        val folder = File(context.filesDir, "profile-picture-validation")
        val config = JSONObject(File(folder, "live.credentials").readText())
        val peer = config.getString("peer")
        val phase = File(context.getExternalFilesDir(null), "picture-interop-phase.txt")
        val report = JSONObject()
        val trace = File(context.getExternalFilesDir(null), "picture-p2p-trace.txt").apply { writeText("") }
        fun tracedTransport(): MsnpTransport {
            val delegate = Msnp18SocketTransport()
            fun record(direction: String, command: MsnpCommand) {
                val mime = command.payload?.let(::parseSwitchboardMime)
                if (mime is SwitchboardMimeMessage.P2p) {
                    trace.appendText("$direction ${android.util.Base64.encodeToString(mime.bytes, android.util.Base64.NO_WRAP)}\n")
                }
            }
            return object : MsnpTransport by delegate {
                override suspend fun send(command: MsnpCommand) { record("SEND", command); delegate.send(command) }
                override suspend fun receive(): MsnpCommand = delegate.receive().also { record("RECV", it) }
            }
        }
        val client = LiveMsnp18Client(switchboardTransportFactory = ::tracedTransport)
        val profile = client.signIn(SignInRequest(config.getString("email"), config.getString("password"),
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()), Presence.Busy)).getOrThrow()
        val previous = profile.avatarUri?.let { context.readProfilePictureBytes(it) }
        try {
            val incoming = withTimeout(60_000) {
                client.contacts.first { list -> list.any { it.email.equals(peer, true) && it.avatarUri != null } }
                    .first { it.email.equals(peer, true) }
            }
            val incomingBytes = context.readProfilePictureBytes(requireNotNull(incoming.avatarUri))
            File(context.getExternalFilesDir(null), "native-original-download.bin").writeBytes(incomingBytes)
            report.put("receivedBytes", incomingBytes.size)
            val fixtures = InstrumentationRegistry.getArguments().getString("pictureFixtures")
                ?.split(',') ?: listOf("static-original.png", "static-original.jpg", "knock-resized.gif")
            for (filename in fixtures) {
                val input = File(folder, filename)
                val selection = context.inspectDisplayPicture(Uri.fromFile(input).toString())
                assertFalse(selection.requiresResize)
                client.changeDisplayPicture(selection.bytes)
                phase.writeText(filename)
                report.put(filename, JSONObject().put("bytes", selection.bytes.size)
                    .put("width", selection.width).put("height", selection.height))
                File(context.getExternalFilesDir(null), "picture-interop-report.json").writeText(report.toString(2))
                delay(InstrumentationRegistry.getArguments().getString("picturePhaseMillis")
                    ?.toLongOrNull() ?: 60_000) // Keep the object available through native P2P retries.
            }
            phase.writeText("waiting-for-native-gif")
            client.refreshContactPicture(peer)
            val animated = withTimeout(150_000) {
                client.contacts.first { list -> list.any { it.email.equals(peer, true) &&
                    it.avatarUri?.startsWith("data:image/gif;") == true } }.first { it.email.equals(peer, true) }
            }
            val bytes = context.readProfilePictureBytes(requireNotNull(animated.avatarUri))
            assertNotNull(GifDisplayPicture.parse(bytes))
            val destination = File(context.getExternalFilesDir(null), "native-original.gif")
            assertTrue(context.saveContactPicture(animated.avatarUri!!, Uri.fromFile(destination)))
            assertArrayEquals(bytes, destination.readBytes())
            phase.writeText("complete")
        } finally {
            try { client.changeDisplayPicture(previous) } finally {
                client.close()
                File(folder, "live.credentials").delete()
            }
        }
    }
}
