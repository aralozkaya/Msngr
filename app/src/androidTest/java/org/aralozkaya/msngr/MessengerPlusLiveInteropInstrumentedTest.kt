package org.aralozkaya.msngr

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.SignInRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: exchanges synthetic messages with the configured native Plus! client. */
@RunWith(AndroidJUnit4::class)
class MessengerPlusLiveInteropInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun allSubsetsThroughNativeClientAndActualChatComposer() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("plusInterop") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credentialFile = File(context.filesDir, "plus-interop.credentials")
        val config = JSONObject(credentialFile.readText())
        val peer = config.getString("peer").lowercase()
        val email = config.getString("email")
        require(!peer.equals(email, true)) { "The native and Android test accounts must differ." }
        val password = config.getString("password")
        val server = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
        val repository = context.appContainer.repositoryFor(useMockServer = false)
        val profile = repository.signIn(SignInRequest(email, password, server)).getOrThrow()
        SessionPrefs.completeLogin(context, email, password, savePassword = false,
            provider = ProviderOptions.ESCARGOT, customServers = ServerOverrides(),
            useMockServer = false, automaticSignIn = false)
        repository.setLocalProfile(SessionPrefs.mergeServerProfile(context, profile))
        MessengerPlusPrefs.setEnabled(context, true)
        repository.loadConversation(peer)
        val report = JSONArray()
        val reportFile = File(context.getExternalFilesDir(null), "messenger-plus-interop.json")
        val scenario = ActivityScenario.launch<ChatActivity>(ChatActivity.createIntent(context, peer))
        try {
            compose.waitForIdle()
            val startTime = System.currentTimeMillis()
            repository.sendMessageNow(peer, "PLUS-BATCH-READY").getOrThrow()
            val batches = withTimeout(600_000) {
                repository.conversation(peer).first { conversation ->
                    MessengerPlusCombinationCases.all.all { case ->
                        conversation.messages.any { it.author == MessageAuthor.Contact && it.timestampMillis >= startTime &&
                            it.text.contains("PLUS-RX-${case.id} ") }
                    }
                }.messages.filter { it.author == MessageAuthor.Contact && it.timestampMillis >= startTime }
            }
            MessengerPlusCombinationCases.all.forEach { case ->
                val prefix = "PLUS-RX-${case.id} "
                val message = batches.last { it.text.contains(prefix) }
                val raw = case.source()
                assertTrue(case.id, message.text.contains(prefix + raw))
                case.assertStyles(raw)
                report.put(JSONObject().put("id", case.id).put("receiving", true).put("receivedSource", raw))
                reportFile.writeText(report.toString(2))
                val receivedPlain = MessengerPlusTextCodec.plainText(message.text)
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(receivedPlain))
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(receivedPlain, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val receivedText = compose.onAllNodesWithText(receivedPlain, useUnmergedTree = true)[0]
                    .fetchSemanticsNode().config[SemanticsProperties.Text].single()
                val bodyStart = receivedPlain.indexOf(prefix) + prefix.length
                assertPlusBubbleStyles(case, raw, receivedText.subSequence(bodyStart, bodyStart + 4))
                val outgoingRaw = "PLUS-TX-${case.id} " + case.source()
                onView(withContentDescription("Message input")).perform(object : androidx.test.espresso.ViewAction {
                    override fun getDescription() = "Compose synthetic Plus combination ${case.id}"
                    override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(MsnEditText::class.java)
                    override fun perform(ui: androidx.test.espresso.UiController, view: android.view.View) {
                        (view as MsnEditText).replaceAllText(outgoingRaw, outgoingRaw.length, outgoingRaw.length)
                        assertPlusEditorStyles(case, outgoingRaw, view.text)
                    }
                })
                val beforeSend = repository.conversation(peer).first().messages.map { it.id }.toSet()
                compose.onNodeWithContentDescription("Send message").performClick()
                val sent = withTimeout(30_000) {
                    repository.conversation(peer).first { conversation ->
                        conversation.messages.any { it.id !in beforeSend && it.author == MessageAuthor.Me && it.text == outgoingRaw && !it.isSending }
                    }.messages.last { it.id !in beforeSend && it.author == MessageAuthor.Me && it.text == outgoingRaw }
                }
                assertTrue("${case.id}: ${sent.deliveryError}", sent.deliveryError == null)
                val plain = "PLUS-TX-${case.id} AbçZ"
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(plain, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val annotated = compose.onAllNodesWithText(plain, useUnmergedTree = true)[0]
                    .fetchSemanticsNode().config[SemanticsProperties.Text].single()
                assertPlusBubbleStyles(case, outgoingRaw, annotated)
                report.getJSONObject(report.length() - 1).put("composing", true).put("sentBubble", true)
                reportFile.writeText(report.toString(2))
                delay(400)
            }
            repository.sendMessageNow(peer, "PLUS-PROBE-DONE").getOrThrow()
        } catch (error: Throwable) {
            File(context.getExternalFilesDir(null), "messenger-plus-interop-ui.txt")
                .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw error
        } finally {
            scenario.close()
            repository.logout()
            credentialFile.delete()
        }
    }
}
