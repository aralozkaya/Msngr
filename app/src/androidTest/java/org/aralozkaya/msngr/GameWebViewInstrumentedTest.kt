package org.aralozkaya.msngr

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.MsnpGameCatalogService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GameWebViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun javascriptGameActionsReachTheControllerOnTheMainThread() {
        val actions = CopyOnWriteArrayList<String>()
        val delivered = CountDownLatch(4)
        fun record(action: String) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            actions.add(action)
            delivered.countDown()
        }
        val controller = createController(
            onInitialize = { record("initialize") },
            onSendData = { record("move:$it") },
            onSendImAsUser = { record("im:$it") },
            onCloseApp = { record("close") },
        )
        try {
            awaitPage(controller)
            evaluate(controller, """
                window.external.Channel.Initialize();
                window.external.Channel.SendData('4');
                window.external.Channel.SendIMAsUser('Good game!');
                window.external.CloseApp();
            """.trimIndent())
            assertTrue("Game callbacks must reach the controller", delivered.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(listOf("initialize", "move:4", "im:Good game!", "close"), actions.toList())
        } finally {
            instrumentation.runOnMainSync { controller.destroy() }
        }
    }

    @Test fun peerEventsReachTheInitializedPageOnce() {
        val initialized = CountDownLatch(1)
        val controller = createController(onInitialize = { initialized.countDown() })
        try {
            awaitPage(controller)
            evaluate(controller, """
                window.gameEvents = [];
                window.Channel_OnRemoteAppLoaded = function() { gameEvents.push('joined'); };
                window.Channel_OnDataReceived = function() { gameEvents.push('move:' + external.Channel.Data); };
                window.Channel_OnRemoteAppClosed = function() { gameEvents.push('closed'); };
                external.Channel.Initialize();
            """.trimIndent())
            assertTrue(initialized.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                controller.dispatchRemoteAppLoaded()
                controller.dispatchRemoteAppLoaded()
            }
            // The joined callback is asynchronous, as it is in the native P4 host.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (evaluate(controller, "gameEvents.length") == "0" && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals("1", evaluate(controller, "gameEvents.length"))
            instrumentation.runOnMainSync {
                controller.dispatchDataReceived("4")
                controller.dispatchRemoteAppClosed()
                controller.dispatchRemoteAppClosed()
            }
            assertEquals("[\"joined\",\"move:4\",\"closed\"]", evaluate(controller, "JSON.stringify(gameEvents)").let {
                org.json.JSONTokener(it).nextValue()
            })
        } finally {
            instrumentation.runOnMainSync { controller.destroy() }
        }
    }

    private fun createController(
        onInitialize: () -> Unit = {},
        onSendData: (String) -> Unit = {},
        onSendImAsUser: (String) -> Unit = {},
        onCloseApp: () -> Unit = {},
    ): GameWebViewController {
        lateinit var controller: GameWebViewController
        instrumentation.runOnMainSync {
            controller = GameWebViewController(
                context = instrumentation.targetContext,
                sessionId = "webview-test",
                entry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!.copy(url = "about:blank"),
                isInviter = true,
                localName = "Alice",
                localEmail = "alice@example.test",
                peerName = "Bob",
                peerEmail = "bob@example.test",
                onInitialize = onInitialize,
                onSendData = onSendData,
                onSendImAsUser = onSendImAsUser,
                onCloseApp = onCloseApp,
                onOpenHelpUrl = {},
            )
        }
        return controller
    }

    private fun awaitPage(controller: GameWebViewController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var loaded = false
        while (!loaded && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { loaded = controller.pageFinished }
            if (!loaded) Thread.sleep(20)
        }
        assertTrue("The game page must load", loaded)
        assertEquals("true", evaluate(controller, "!!window.__p4PolyfillInstalled"))
    }

    private fun evaluate(controller: GameWebViewController, script: String): String {
        val evaluated = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            controller.webView.evaluateJavascript(script) {
                result = it
                evaluated.countDown()
            }
        }
        assertTrue(evaluated.await(5, TimeUnit.SECONDS))
        return result
    }
}
