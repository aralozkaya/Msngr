package org.aralozkaya.msngr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import org.aralozkaya.msngr.data.GameCatalogEntry
import org.aralozkaya.msngr.data.GameSession
import org.aralozkaya.msngr.data.GameSessionStatus
import org.aralozkaya.msngr.data.MsnpClientConfigService
import org.aralozkaya.msngr.data.MsnpGameCatalogService
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.junit.Assert.assertFalse
import org.aralozkaya.msngr.ui.theme.DarkColorScheme
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GameWebViewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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
            assertEquals("500", evaluate(controller, "window.innerWidth"))
            assertEquals("500", evaluate(controller, "window.innerHeight"))
            assertEquals(
                "\"rgb(81, 108, 161)\"",
                evaluate(controller, "getComputedStyle(document.documentElement).backgroundColor"),
            )
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

    @Test fun opaqueChildFramesHaveNoGameBridgeOrPlayerIdentity() {
        val controller = createController()
        try {
            awaitPage(controller)
            evaluate(controller, """
                window.addEventListener('message', function(event) {
                    if (event.data === 'isolated-game-frame') window.frameIsIsolated = true;
                });
                var frame = document.createElement('iframe');
                frame.setAttribute('sandbox', 'allow-scripts');
                frame.srcdoc = "<script>var parentHost; try { parentHost = parent.__p4Host; } catch (ignored) {}" +
                    "if (!parentHost && !window.__p4Host && !window.AndroidP4Bridge && !window.external.Channel)" +
                    " parent.postMessage('isolated-game-frame', '*');<" + "/script>";
                document.body.appendChild(frame);
            """.trimIndent())
            compose.waitUntil(5_000) { evaluate(controller, "!!window.frameIsIsolated") == "true" }
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
                JSONTokener(it).nextValue()
            })
        } finally {
            instrumentation.runOnMainSync { controller.destroy() }
        }
    }

    @Test fun fullScreenWhiteboardPopupHasMinimizeIconPureWhiteBackgroundAndDrawsBidirectionally() {
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(100000)!!
        val mobileSessionState = mutableStateOf<GameSession?>(null)
        val pcSessionRef = AtomicReference<GameSession?>(null)
        lateinit var mobileP2p: MsnpP2pSession
        lateinit var pcP2p: MsnpP2pSession

        mobileP2p = MsnpP2pSession(
            "mobile@example.test",
            "pc@example.test",
            { pcP2p.handle(it) },
            { null },
            {},
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            onGameSession = { session ->
                instrumentation.runOnMainSync { mobileSessionState.value = session }
            },
        )
        pcP2p = MsnpP2pSession(
            "pc@example.test",
            "mobile@example.test",
            { mobileP2p.handle(it) },
            { null },
            {},
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111",
            onGameSession = { pcSessionRef.set(it) },
        )

        try {
            val sessionId = runBlocking {
                val id = mobileP2p.startGame(entry)
                pcP2p.acceptGame(id)
                pcP2p.initializeGameChannel(id)
                id
            }

            compose.setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Box(Modifier.fillMaxSize()) {
                        GameHost(
                            contactId = "pc-peer",
                            session = mobileSessionState.value,
                            localDisplayName = "Mobile User",
                            localEmail = "mobile@example.test",
                            peerDisplayName = "PC User",
                            peerEmail = "pc@example.test",
                            onAccept = { runCatching { mobileP2p.acceptGame(sessionId) } },
                            onStop = { runCatching { mobileP2p.stopGame(sessionId) } },
                            onInitializeChannel = { runCatching { mobileP2p.initializeGameChannel(sessionId) } },
                            onSendGameData = { payload -> runCatching { mobileP2p.sendGameData(sessionId, payload) } },
                        )
                    }
                }
            }

            compose.onNodeWithTag("game_session_dialog").assertIsDisplayed()
            compose.onNodeWithTag("minimize_game_session")
                .assertIsDisplayed()
                .assertContentDescriptionEquals("Minimize")

            val dialogBounds = compose.onNodeWithTag("game_session_dialog").fetchSemanticsNode().boundsInRoot
            val minimizeBounds = compose.onNodeWithTag("minimize_game_session").fetchSemanticsNode().boundsInRoot
            assertTrue("Minimize icon must be in the top-left of the full-screen popup", minimizeBounds.left < dialogBounds.width * 0.25f)
            assertTrue("Minimize icon must be in the top bar", minimizeBounds.top < dialogBounds.height * 0.2f)

            val webView = awaitMountedGameWebView()
            compose.waitUntil(15_000) {
                evaluateOnWebView(webView, "document.getElementById('text') && document.getElementById('text').innerText")
                    .contains("Hold the left mouse button to draw")
            }

            val metrics = JSONObject(
                JSONTokener(
                    evaluateOnWebView(
                        webView,
                        """
                        JSON.stringify({
                            iw: window.innerWidth,
                            ih: window.innerHeight,
                            htmlBg: getComputedStyle(document.documentElement).backgroundColor,
                            textColor: getComputedStyle(document.getElementById('text')).color,
                            canvasBottom: document.getElementById('divCanvas').getBoundingClientRect().bottom,
                            canvasWidth: document.getElementById('divCanvas').offsetWidth,
                            canvasHeight: document.getElementById('divCanvas').offsetHeight
                        })
                        """.trimIndent(),
                    ),
                ).nextValue() as String,
            )
            assertEquals(500, metrics.getInt("iw"))
            assertEquals(WHITEBOARD_NATIVE_PANE_HEIGHT, metrics.getInt("ih"))
            assertEquals("rgb(81, 108, 161)", metrics.getString("htmlBg"))
            assertTrue("Entire Whiteboard canvas must fit vertically without clipping", metrics.getDouble("canvasBottom") <= WHITEBOARD_NATIVE_PANE_HEIGHT)
            assertEquals(458, metrics.getInt("canvasWidth"))
            assertEquals(458, metrics.getInt("canvasHeight"))

            // Draw a stroke via real touch input on the scaled mobile container
            compose.onNodeWithTag("game_webview_container").performTouchInput {
                val start = Offset(width * 0.25f, height * 0.40f)
                val mid = Offset(width * 0.55f, height * 0.55f)
                val end = Offset(width * 0.75f, height * 0.70f)
                down(start)
                moveTo(mid)
                moveTo(end)
                up()
            }

            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload.startsWith("U:1:") }
            }
            assertEquals(
                "1",
                evaluateOnWebView(webView, "document.querySelectorAll('#divLines svg.__p4VmlSvg').length"),
            )

            // Send a stroke from the PC peer and verify it renders on Android
            runBlocking {
                pcP2p.sendGameData(
                    sessionId,
                    "U:1:60:80:5:blue|U:1:220:260:5:blue|U:1:380:140:5:blue|U:0:380:140:5:blue",
                )
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "document.querySelectorAll('#divLines svg.__p4VmlSvg').length") == "2"
            }
            // Allow WebView's async compositor a vsync to swap the newly added stroke to SurfaceFlinger
            Thread.sleep(200)

            val screenshot = capture("whiteboard-dark-fullscreen.png")
            // Sample a pixel near the top-left of the Whiteboard header (above the canvas, away from text)
            val containerBounds = compose.onNodeWithTag("game_webview_container").fetchSemanticsNode().boundsInWindow
            val sampleX = (containerBounds.left + containerBounds.width * 0.50f).toInt().coerceIn(0, screenshot.width - 1)
            val sampleY = (containerBounds.top + containerBounds.height * 0.04f).toInt().coerceIn(0, screenshot.height - 1)
            val pixel = screenshot.getPixel(sampleX, sampleY)
            screenshot.recycle()
            assertTrue(
                "Unstyled Whiteboard frame background must be #516CA1 even in dark theme, was #${Integer.toHexString(pixel)}",
                kotlin.math.abs(Color.red(pixel) - 0x51) <= 16 &&
                    kotlin.math.abs(Color.green(pixel) - 0x6C) <= 16 &&
                    kotlin.math.abs(Color.blue(pixel) - 0xA1) <= 16,
            )

            // Minimize via top-left bar icon, receive a packet while minimized, and reopen
            compose.onNodeWithTag("minimize_game_session").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("game_session_dialog").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("reopen_game_session").assertIsDisplayed()

            runBlocking {
                pcP2p.sendGameData(sessionId, "U:1:30:300:4:red|U:1:300:300:4:red|U:0:300:300:4:red")
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reopen_game_session").performClick()
            compose.onNodeWithTag("game_session_dialog").assertIsDisplayed()
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "document.querySelectorAll('#divLines svg.__p4VmlSvg').length") == "3"
            }
        } finally {
            mobileP2p.closeFileTransfers()
            pcP2p.closeFileTransfers()
        }
    }

    @Test fun battleshipsRendersAtNativePaneSizeWithMergedBackgroundAndPlaysMoveFlow() {
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(102002)!!
        val mobileSessionState = mutableStateOf<GameSession?>(null)
        val pcSessionRef = AtomicReference<GameSession?>(null)
        lateinit var mobileP2p: MsnpP2pSession
        lateinit var pcP2p: MsnpP2pSession

        mobileP2p = MsnpP2pSession(
            "mobile@example.test",
            "pc@example.test",
            { pcP2p.handle(it) },
            { null },
            {},
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            onGameSession = { session ->
                instrumentation.runOnMainSync { mobileSessionState.value = session }
            },
        )
        pcP2p = MsnpP2pSession(
            "pc@example.test",
            "mobile@example.test",
            { mobileP2p.handle(it) },
            { null },
            {},
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111",
            onGameSession = { pcSessionRef.set(it) },
        )

        try {
            val sessionId = runBlocking {
                val id = mobileP2p.startGame(entry)
                pcP2p.acceptGame(id)
                pcP2p.initializeGameChannel(id)
                id
            }

            compose.setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Box(Modifier.fillMaxSize()) {
                        GameHost(
                            contactId = "pc-peer",
                            session = mobileSessionState.value,
                            localDisplayName = "Mobile User",
                            localEmail = "mobile@example.test",
                            peerDisplayName = "PC User",
                            peerEmail = "pc@example.test",
                            onAccept = { runCatching { mobileP2p.acceptGame(sessionId) } },
                            onStop = { runCatching { mobileP2p.stopGame(sessionId) } },
                            onInitializeChannel = { runCatching { mobileP2p.initializeGameChannel(sessionId) } },
                            onSendGameData = { payload -> runCatching { mobileP2p.sendGameData(sessionId, payload) } },
                        )
                    }
                }
            }

            val containerBounds = compose.onNodeWithTag("game_webview_container").fetchSemanticsNode().boundsInRoot
            assertEquals(
                "Battleships container must preserve 1:1 native 500x500 aspect ratio",
                1f,
                containerBounds.width / containerBounds.height,
                0.02f,
            )

            val webView = awaitMountedGameWebView()
            compose.waitUntil(20_000) {
                evaluateOnWebView(
                    webView,
                    "String(Boolean(document.getElementById('tblGame') && getComputedStyle(document.getElementById('tblGame')).display !== 'none' && (!document.getElementById('pnlSplash') || getComputedStyle(document.getElementById('pnlSplash')).display === 'none')))",
                ) == "\"true\""
            }
            Thread.sleep(200)

            val initialMetrics = JSONObject(
                JSONTokener(
                    evaluateOnWebView(
                        webView,
                        """
                        JSON.stringify({
                            iw: window.innerWidth,
                            ih: window.innerHeight,
                            bodyBg: getComputedStyle(document.body).backgroundColor,
                            myBoardW: document.getElementById('myBoard').offsetWidth,
                            myBoardH: document.getElementById('myBoard').offsetHeight,
                            remoteBoardW: document.getElementById('remoteBoard').offsetWidth,
                            remoteBoardH: document.getElementById('remoteBoard').offsetHeight
                        })
                        """.trimIndent(),
                    ),
                ).nextValue() as String,
            )
            assertEquals(500, initialMetrics.getInt("iw"))
            assertEquals(500, initialMetrics.getInt("ih"))
            assertEquals("rgb(81, 108, 161)", initialMetrics.getString("bodyBg"))
            assertEquals(210, initialMetrics.getInt("myBoardW"))
            assertEquals(210, initialMetrics.getInt("myBoardH"))
            assertEquals(210, initialMetrics.getInt("remoteBoardW"))
            assertEquals(210, initialMetrics.getInt("remoteBoardH"))

            capture("battleships-placing-ships.png").recycle()

            // Place ships on mobile via JS button click and send PC fleet
            evaluateOnWebView(webView, "document.getElementById('btnPlaceRnd').click(); document.getElementById('btnDone').click();")
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload.startsWith("done") }
            }
            runBlocking {
                pcP2p.sendGameData(
                    sessionId,
                    "done0,0|1,0|2,0|3,0|4,0$0,2|1,2|2,2|3,2$0,4|1,4|2,4$0,6|1,6|2,6$0,8|1,8",
                )
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "String(window.m_gameState)") == "\"4\""
            }

            // Fire on enemy cell (2,0) via real touch tap on the scaled remoteBoard!
            val targetCellCenter = JSONObject(
                JSONTokener(
                    evaluateOnWebView(
                        webView,
                        """
                        (function() {
                            var r = document.getElementById('remoteBoard').rows[0].cells[2].getBoundingClientRect();
                            return JSON.stringify({ cx: (r.left + r.right) / 2, cy: (r.top + r.bottom) / 2 });
                        })()
                        """.trimIndent(),
                    ),
                ).nextValue() as String,
            )
            val normX = (targetCellCenter.getDouble("cx") / 500.0).toFloat()
            val normY = (targetCellCenter.getDouble("cy") / 500.0).toFloat()
            compose.onNodeWithTag("game_webview_container").performTouchInput {
                click(Offset(width * normX, height * normY))
            }

            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == "2,0" }
            }

            // PC fires back at (5,5)
            runBlocking {
                pcP2p.sendGameData(sessionId, "5,5")
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "String(window.m_gameState)") == "\"4\""
            }

            // Verify boards stay strictly 210x210 and every single row stays 21px tall after hit.gif / miss.gif load
            Thread.sleep(250)
            val afterMoveMetrics = JSONObject(
                JSONTokener(
                    evaluateOnWebView(
                        webView,
                        """
                        JSON.stringify({
                            myBoardW: document.getElementById('myBoard').offsetWidth,
                            myBoardH: document.getElementById('myBoard').offsetHeight,
                            remoteBoardW: document.getElementById('remoteBoard').offsetWidth,
                            remoteBoardH: document.getElementById('remoteBoard').offsetHeight,
                            non21Rows: Array.from(document.getElementById('myBoard').rows).filter(function(r) { return r.offsetHeight !== 21; }).length
                        })
                        """.trimIndent(),
                    ),
                ).nextValue() as String,
            )
            assertEquals(210, afterMoveMetrics.getInt("myBoardW"))
            assertEquals(210, afterMoveMetrics.getInt("myBoardH"))
            assertEquals(210, afterMoveMetrics.getInt("remoteBoardW"))
            assertEquals(210, afterMoveMetrics.getInt("remoteBoardH"))
            assertEquals(0, afterMoveMetrics.getInt("non21Rows"))

            capture("battleships-active-turn.png").recycle()

            // 1. PC peer requests a game reset ("res") -> confirm() dialog appears -> Cancel declines ("nor")
            runBlocking {
                pcP2p.sendGameData(sessionId, "res")
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("game_js_dialog").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game_js_dialog").assertIsDisplayed()
            compose.onNodeWithTag("game_js_dialog_confirm").assertIsDisplayed()
            compose.onNodeWithTag("game_js_dialog_dismiss").assertIsDisplayed()
            Thread.sleep(150)
            capture("battleships-restart-confirm.png").recycle()

            compose.onNodeWithTag("game_js_dialog_dismiss").performClick()
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == "nor" }
            }
            assertEquals("4", evaluateOnWebView(webView, "window.m_gameState"))

            // 2. Mobile clicks Restart -> PC peer declines ("nor") -> alert() dialog appears -> OK dismisses
            evaluateOnWebView(webView, "document.getElementById('btnRestart').click();")
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == "res" }
            }
            runBlocking {
                pcP2p.sendGameData(sessionId, "nor")
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("game_js_dialog").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game_js_dialog_confirm").assertIsDisplayed()
            assertTrue(
                "Alert dialog must not show a Cancel button",
                compose.onAllNodesWithTag("game_js_dialog_dismiss").fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithTag("game_js_dialog_confirm").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("game_js_dialog").fetchSemanticsNodes().isEmpty()
            }

            // 3. Minimize game, then PC peer requests reset ("res") -> game surfaces with confirm() -> OK resets ("rok")
            compose.onNodeWithTag("minimize_game_session").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("game_session_dialog").fetchSemanticsNodes().isEmpty()
            }
            runBlocking {
                pcP2p.sendGameData(sessionId, "res")
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("game_session_dialog").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("game_js_dialog").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game_js_dialog_confirm").performClick()
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == "rok" }
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "window.m_gameState") == "3"
            }
        } finally {
            mobileP2p.closeFileTransfers()
            pcP2p.closeFileTransfers()
        }
    }

    @Test fun wordleRendersWithPureWhiteBackgroundAndTouchKeyboardFitsCleanly() {
        val entry = MsnpGameCatalogService.defaultCatalog().findEntry(103000)!!
        val mobileSessionState = mutableStateOf<GameSession?>(null)
        val pcSessionRef = AtomicReference<GameSession?>(null)
        lateinit var mobileP2p: MsnpP2pSession
        lateinit var pcP2p: MsnpP2pSession

        mobileP2p = MsnpP2pSession(
            "mobile@example.test",
            "pc@example.test",
            { pcP2p.handle(it) },
            { null },
            {},
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            onGameSession = { session ->
                instrumentation.runOnMainSync { mobileSessionState.value = session }
            },
        )
        pcP2p = MsnpP2pSession(
            "pc@example.test",
            "mobile@example.test",
            { mobileP2p.handle(it) },
            { null },
            {},
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111",
            onGameSession = { pcSessionRef.set(it) },
        )

        try {
            val sessionId = runBlocking {
                val id = mobileP2p.startGame(entry)
                pcP2p.acceptGame(id)
                pcP2p.initializeGameChannel(id)
                id
            }

            compose.setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Box(Modifier.fillMaxSize()) {
                        GameHost(
                            contactId = "pc-peer",
                            session = mobileSessionState.value,
                            localDisplayName = "Mobile User",
                            localEmail = "mobile@example.test",
                            peerDisplayName = "PC User",
                            peerEmail = "pc@example.test",
                            onAccept = { runCatching { mobileP2p.acceptGame(sessionId) } },
                            onStop = { runCatching { mobileP2p.stopGame(sessionId) } },
                            onInitializeChannel = { runCatching { mobileP2p.initializeGameChannel(sessionId) } },
                            onSendGameData = { payload -> runCatching { mobileP2p.sendGameData(sessionId, payload) } },
                        )
                    }
                }
            }

            val webView = awaitMountedGameWebView()
            compose.waitUntil(20_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload.startsWith("WRD ") }
            }
            // Complete Wordle's handshake from the PC peer so the two 5x6 grids appear
            runBlocking {
                pcP2p.sendGameData(sessionId, "WRD OK")
            }
            compose.waitUntil(10_000) {
                evaluateOnWebView(
                    webView,
                    "Boolean(document.getElementById('gt') && getComputedStyle(document.getElementById('gt')).display === 'table')",
                ) == "true"
            }

            val containerBounds = compose.onNodeWithTag("game_webview_container").fetchSemanticsNode().boundsInRoot
            assertEquals(
                "Wordle container must preserve native 500x560 aspect ratio above touch keyboard",
                500f / WORDLE_NATIVE_PANE_HEIGHT.toFloat(),
                containerBounds.width / containerBounds.height,
                0.02f,
            )
            assertEquals("500", evaluateOnWebView(webView, "window.innerWidth"))
            assertEquals(WORDLE_NATIVE_PANE_HEIGHT.toString(), evaluateOnWebView(webView, "window.innerHeight"))
            assertEquals(
                "\"rgb(81, 108, 161)\"",
                evaluateOnWebView(webView, "getComputedStyle(document.documentElement).backgroundColor"),
            )

            // Type "WATER" + ENTER using the on-screen WordleTouchKeyboard and verify zero duplicate key events
            for (ch in listOf("W", "A", "T", "E", "R", "ENTER")) {
                compose.onAllNodesWithText(ch)[0].performClick()
            }
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == "KEY ENTER" }
            }
            val typedWord = evaluateOnWebView(
                webView,
                "[0,1,2,3,4].map(function(i){ return document.getElementById('self-' + i + '-0').innerText.trim(); }).join('')",
            )
            assertEquals("\"WATER\"", typedWord)
            // Also send a remote guess from PC peer
            runBlocking {
                for (ch in listOf("S", "O", "U", "N", "D", "ENTER")) {
                    pcP2p.sendGameData(sessionId, "KEY $ch")
                }
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "document.getElementById('other-4-0').innerText.trim()") == "\"D\""
            }
            Thread.sleep(200)

            capture("wordle-dark-fullscreen.png").recycle()
        } finally {
            mobileP2p.closeFileTransfers()
            pcP2p.closeFileTransfers()
        }
    }

    @Test fun ticTacToePhoneInitiatedInvitedToActiveAndPlaysToWin() {
        playTicTacToeEndToEnd(phoneInitiates = true)
    }

    @Test fun ticTacToePcInitiatedInvitedToActiveAndPlaysToWin() {
        playTicTacToeEndToEnd(phoneInitiates = false)
    }

    @Test fun battleshipsPhoneInitiatedInvitedToActiveAndPlaysToGameOver() {
        playBattleshipsEndToEnd(phoneInitiates = true)
    }

    @Test fun battleshipsPcInitiatedInvitedToActiveAndPlaysToGameOver() {
        playBattleshipsEndToEnd(phoneInitiates = false)
    }

    @Test fun crossTalkTicTacToeLoadsPlayerHeaderInitializesChannelAndPlaysToWin() {
        val previousServer = MsnpGameCatalogService.currentActivitiesServer
        try {
            val entry = runBlocking {
                MsnpGameCatalogService.fetchCatalog(activitiesServer = "mactivities.msgrsvcs.ctsrv.xyz")
                    .findEntry(101)!!
            }
            assertEquals(101, entry.appId)
            assertTrue(entry.url.contains("mactivities.msgrsvcs.ctsrv.xyz", ignoreCase = true))
            playTicTacToeEndToEnd(entry = entry, phoneInitiates = true, verifyPlayerHeaders = true)
        } finally {
            MsnpGameCatalogService.currentActivitiesServer = previousServer
        }
    }

    @Test fun crossTalkBattleshipsLoadsCommonJsWithoutErrorAndPlaysToGameOver() {
        val previousServer = MsnpGameCatalogService.currentActivitiesServer
        try {
            val entry = runBlocking {
                MsnpGameCatalogService.fetchCatalog(activitiesServer = "mactivities.msgrsvcs.ctsrv.xyz")
                    .findEntry(10311359)!!
            }
            assertEquals(10311359, entry.appId)
            assertTrue(entry.url.contains("mactivities.msgrsvcs.ctsrv.xyz", ignoreCase = true))
            playBattleshipsEndToEnd(entry = entry, phoneInitiates = true)
        } finally {
            MsnpGameCatalogService.currentActivitiesServer = previousServer
        }
    }

    private fun playTicTacToeEndToEnd(
        phoneInitiates: Boolean,
        entry: GameCatalogEntry = MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!,
        verifyPlayerHeaders: Boolean = false,
    ) {
        withLiveP2pGameHost(entry, phoneInitiates) { webView, pcP2p, mobileSessionState, pcSessionRef, sessionId ->
            compose.waitUntil(20_000) {
                mobileSessionState.value?.remoteAppLoaded == true &&
                    pcSessionRef.get()?.remoteAppLoaded == true &&
                    evaluateOnWebView(
                        webView,
                        "String(Boolean(window.fRemoteAppLoaded === true && window.GameState === 1 && document.getElementById('cell0')))",
                    ) == "\"true\""
            }
            if (verifyPlayerHeaders) {
                assertEquals(
                    "\"Mobile User\"",
                    evaluateOnWebView(webView, "document.getElementById('hPlayer1').innerText.trim()"),
                )
                assertEquals(
                    "\"PC User\"",
                    evaluateOnWebView(webView, "document.getElementById('hPlayer2').innerText.trim()"),
                )
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "getComputedStyle(document.body).backgroundColor") == "\"rgb(255, 255, 255)\""
            }
            assertEquals(
                "Tic Tac Toe sets its own white body background and must not keep #__p4_default_bg",
                "false",
                evaluateOnWebView(webView, "!!document.getElementById('__p4_default_bg')"),
            )
            Thread.sleep(200)

            if (!phoneInitiates) {
                runBlocking { pcP2p.sendGameData(sessionId, "3") }
            }
            compose.waitUntil(5_000) {
                evaluateOnWebView(webView, "String(window.giTurn === window.MyTurn && window.GameState === 1)") == "\"true\""
            }

            // Mobile move 1: cell 0
            val sentBeforeMove1 = pcSessionRef.get()?.receivedPackets.orEmpty().size
            clickTicTacToeSquare(webView, 0)
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().size > sentBeforeMove1
            }
            assertEquals("0", pcSessionRef.get()!!.receivedPackets[sentBeforeMove1].payload)

            // PC move 1 (non-blocking against row 0)
            val pcMove1 = if (phoneInitiates) "3" else "4"
            runBlocking { pcP2p.sendGameData(sessionId, pcMove1) }
            compose.waitUntil(5_000) {
                mobileSessionState.value?.receivedPackets.orEmpty().any { it.payload == pcMove1 } &&
                    evaluateOnWebView(webView, "String(window.giTurn === window.MyTurn && window.GameState === 1)") == "\"true\""
            }

            // Mobile move 2: cell 1
            val sentBeforeMove2 = pcSessionRef.get()?.receivedPackets.orEmpty().size
            clickTicTacToeSquare(webView, 1)
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().size > sentBeforeMove2
            }
            assertEquals("1", pcSessionRef.get()!!.receivedPackets[sentBeforeMove2].payload)

            // PC move 2 (still avoids cell 2)
            val pcMove2 = if (phoneInitiates) "4" else "6"
            runBlocking { pcP2p.sendGameData(sessionId, pcMove2) }
            compose.waitUntil(5_000) {
                mobileSessionState.value?.receivedPackets.orEmpty().any { it.payload == pcMove2 } &&
                    evaluateOnWebView(webView, "String(window.giTurn === window.MyTurn && window.GameState === 1)") == "\"true\""
            }

            // Mobile move 3: cell 2 completes top row (0, 1, 2) -> win!
            val sentBeforeMove3 = pcSessionRef.get()?.receivedPackets.orEmpty().size
            clickTicTacToeSquare(webView, 2)
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().size > sentBeforeMove3
            }
            assertEquals("2", pcSessionRef.get()!!.receivedPackets[sentBeforeMove3].payload)

            compose.waitUntil(10_000) {
                evaluateOnWebView(
                    webView,
                    "String(window.GameState === 0 && (document.getElementById('txtBanner').innerText || '').indexOf('Wins!') >= 0)",
                ) == "\"true\""
            }
            val scoreElementId = if (phoneInitiates) "pointsPlayer1" else "pointsPlayer2"
            assertEquals(
                "\"1\"",
                evaluateOnWebView(webView, "document.getElementById('$scoreElementId').innerText.trim()"),
            )
        }
    }

    private fun playBattleshipsEndToEnd(
        phoneInitiates: Boolean,
        entry: GameCatalogEntry = MsnpGameCatalogService.defaultCatalog().findEntry(102002)!!,
    ) {
        withLiveP2pGameHost(entry, phoneInitiates) { webView, pcP2p, mobileSessionState, pcSessionRef, sessionId ->
            compose.waitUntil(20_000) {
                mobileSessionState.value?.remoteAppLoaded == true &&
                    pcSessionRef.get()?.remoteAppLoaded == true &&
                    evaluateOnWebView(
                        webView,
                        "String(Boolean(document.getElementById('tblGame') && getComputedStyle(document.getElementById('tblGame')).display !== 'none' && (!document.getElementById('pnlSplash') || getComputedStyle(document.getElementById('pnlSplash')).display === 'none')))",
                    ) == "\"true\""
            }
            Thread.sleep(200)

            evaluateOnWebView(webView, "document.getElementById('btnPlaceRnd').click(); document.getElementById('btnDone').click();")
            compose.waitUntil(5_000) {
                pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload.startsWith("done") }
            }
            val donePayload = pcSessionRef.get()!!.receivedPackets.first { it.payload.startsWith("done") }.payload
            val mobileShipCells = donePayload.removePrefix("done")
                .split("$", "|")
                .mapNotNull { token ->
                    val parts = token.split(",")
                    val x = parts.getOrNull(0)?.toIntOrNull()
                    val y = parts.getOrNull(1)?.toIntOrNull()
                    if (x != null && y != null) x to y else null
                }
                .toSet()
            val safeMissShots = (0..9).flatMap { x -> (0..9).map { y -> x to y } }
                .filter { it !in mobileShipCells }
            assertTrue("Must have enough safe miss shots", safeMissShots.size >= 17)

            val pcFleetCells = listOf(
                0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0,
                0 to 2, 1 to 2, 2 to 2, 3 to 2,
                0 to 4, 1 to 4, 2 to 4,
                0 to 6, 1 to 6, 2 to 6,
                0 to 8, 1 to 8,
            )
            runBlocking {
                pcP2p.sendGameData(
                    sessionId,
                    "done0,0|1,0|2,0|3,0|4,0$0,2|1,2|2,2|3,2$0,4|1,4|2,4$0,6|1,6|2,6$0,8|1,8",
                )
            }

            if (!phoneInitiates) {
                // PC is the inviter and moves first in Battleships; wait for PC turn state then fire a miss so Mobile plays.
                compose.waitUntil(5_000) {
                    evaluateOnWebView(webView, "String(window.m_gameState)") == "\"5\""
                }
                val openingMiss = safeMissShots.last()
                runBlocking { pcP2p.sendGameData(sessionId, "${openingMiss.first},${openingMiss.second}") }
            }

            for ((index, target) in pcFleetCells.withIndex()) {
                compose.waitUntil(5_000) {
                    evaluateOnWebView(webView, "String(window.m_gameState)") == "\"4\""
                }
                val cellCenter = JSONObject(
                    JSONTokener(
                        evaluateOnWebView(
                            webView,
                            """
                            (function() {
                                var r = document.getElementById('remoteBoard').rows[${target.second}].cells[${target.first}].getBoundingClientRect();
                                return JSON.stringify({ cx: (r.left + r.right) / 2, cy: (r.top + r.bottom) / 2 });
                            })()
                            """.trimIndent(),
                        ),
                    ).nextValue() as String,
                )
                val normX = (cellCenter.getDouble("cx") / 500.0).toFloat()
                val normY = (cellCenter.getDouble("cy") / 500.0).toFloat()
                val shotPayload = "${target.first},${target.second}"
                compose.onNodeWithTag("game_webview_container").performTouchInput {
                    click(Offset(width * normX, height * normY))
                }
                compose.waitUntil(5_000) {
                    pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == shotPayload }
                }
                if (index < pcFleetCells.lastIndex) {
                    val miss = safeMissShots[index]
                    runBlocking { pcP2p.sendGameData(sessionId, "${miss.first},${miss.second}") }
                }
            }

            compose.waitUntil(10_000) {
                evaluateOnWebView(webView, "String(window.m_gameState)") == "\"6\""
            }
            if (compose.onAllNodesWithTag("game_js_dialog").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("game_js_dialog_confirm").performClick()
            }
        }
    }

    private fun withLiveP2pGameHost(
        entry: GameCatalogEntry,
        phoneInitiates: Boolean,
        play: (
            webView: WebView,
            pcP2p: MsnpP2pSession,
            mobileSessionState: androidx.compose.runtime.MutableState<GameSession?>,
            pcSessionRef: AtomicReference<GameSession?>,
            sessionId: String,
        ) -> Unit,
    ) {
        val mobileSessionState = mutableStateOf<GameSession?>(null)
        val pcSessionRef = AtomicReference<GameSession?>(null)
        lateinit var mobileP2p: MsnpP2pSession
        lateinit var pcP2p: MsnpP2pSession

        mobileP2p = MsnpP2pSession(
            "mobile@example.test",
            "pc@example.test",
            { pcP2p.handle(it) },
            { null },
            {},
            localEndpointId = "11111111-1111-1111-1111-111111111111",
            remoteEndpointId = "22222222-2222-2222-2222-222222222222",
            onGameSession = { session ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    mobileSessionState.value = session
                } else {
                    instrumentation.runOnMainSync { mobileSessionState.value = session }
                }
            },
        )
        pcP2p = MsnpP2pSession(
            "pc@example.test",
            "mobile@example.test",
            { mobileP2p.handle(it) },
            { null },
            {},
            localEndpointId = "22222222-2222-2222-2222-222222222222",
            remoteEndpointId = "11111111-1111-1111-1111-111111111111",
            onGameSession = { pcSessionRef.set(it) },
        )

        try {
            val sessionId = runBlocking {
                if (phoneInitiates) mobileP2p.startGame(entry) else pcP2p.startGame(entry)
            }
            compose.waitUntil(5_000) {
                mobileSessionState.value?.status == GameSessionStatus.Invited
            }
            assertEquals(phoneInitiates, mobileSessionState.value?.outgoing)

            compose.setContent {
                MaterialTheme(colorScheme = DarkColorScheme) {
                    Box(Modifier.fillMaxSize()) {
                        GameHost(
                            contactId = "pc-peer",
                            session = mobileSessionState.value,
                            localDisplayName = "Mobile User",
                            localEmail = "mobile@example.test",
                            peerDisplayName = "PC User",
                            peerEmail = "pc@example.test",
                            onAccept = {
                                runCatching { mobileP2p.acceptGame(sessionId) }
                                    .also { it.exceptionOrNull()?.printStackTrace() }
                            },
                            onStop = {
                                runCatching { mobileP2p.stopGame(sessionId) }
                                    .also { it.exceptionOrNull()?.printStackTrace() }
                            },
                            onInitializeChannel = {
                                runCatching { mobileP2p.initializeGameChannel(sessionId) }
                                    .also { it.exceptionOrNull()?.printStackTrace() }
                            },
                            onSendGameData = { payload ->
                                runCatching { mobileP2p.sendGameData(sessionId, payload) }
                                    .also { it.exceptionOrNull()?.printStackTrace() }
                            },
                        )
                    }
                }
            }

            compose.onNodeWithTag("game_session_dialog").assertIsDisplayed()
            compose.onAllNodesWithTag("game_webview_container").assertCountEquals(0)
            assertFalse(
                "WebView channel must not initialize while session is still Invited",
                mobileSessionState.value?.localChannelInitialized == true,
            )

            if (phoneInitiates) {
                runBlocking {
                    pcP2p.acceptGame(sessionId)
                    pcP2p.initializeGameChannel(sessionId)
                }
            } else {
                compose.onNodeWithTag("accept_game_session").assertIsDisplayed().performClick()
                compose.waitUntil(5_000) {
                    pcSessionRef.get()?.status == GameSessionStatus.Active
                }
                runBlocking {
                    pcP2p.initializeGameChannel(sessionId)
                }
            }

            compose.waitUntil(5_000) {
                mobileSessionState.value?.status == GameSessionStatus.Active &&
                    compose.onAllNodesWithTag("game_webview_container").fetchSemanticsNodes().isNotEmpty()
            }
            val webView = awaitMountedGameWebView()
            play(webView, pcP2p, mobileSessionState, pcSessionRef, sessionId)
        } finally {
            mobileP2p.closeFileTransfers()
            pcP2p.closeFileTransfers()
        }
    }

    private fun clickTicTacToeSquare(webView: WebView, index: Int) {
        val metrics = JSONObject(
            JSONTokener(
                evaluateOnWebView(
                    webView,
                    "JSON.stringify({ w: window.innerWidth, h: window.innerHeight })",
                ),
            ).nextValue() as String,
        )
        val rawCenter = evaluateOnWebView(
            webView,
            """
            (function(idx) {
                var byId = ['cell' + idx, 'c' + idx, 'sq' + idx, 'img' + idx, 'b' + idx, 'btn' + idx, 'p' + idx, String(idx)]
                    .map(function(id) { return document.getElementById(id); })
                    .filter(Boolean);
                if (byId.length > 0) {
                    var r = byId[0].getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) {
                        return JSON.stringify({ cx: (r.left + r.right) / 2, cy: (r.top + r.bottom) / 2 });
                    }
                }
                var clickables = Array.from(document.querySelectorAll('td[onclick], div[onclick], img[onclick], button, a'))
                    .filter(function(el) {
                        var oc = (el.getAttribute('onclick') || '').toLowerCase();
                        var r = el.getBoundingClientRect();
                        return r.width >= 20 && r.height >= 20 && r.width <= 200 && r.height <= 200 &&
                            oc.indexOf('restart') < 0 && oc.indexOf('reset') < 0 && oc.indexOf('help') < 0;
                    });
                if (clickables.length >= 9) {
                    var rc = clickables[idx].getBoundingClientRect();
                    return JSON.stringify({ cx: (rc.left + rc.right) / 2, cy: (rc.top + rc.bottom) / 2 });
                }
                var tables = Array.from(document.querySelectorAll('table'));
                for (var t = 0; t < tables.length; t++) {
                    var tds = Array.from(tables[t].querySelectorAll('td')).filter(function(td) {
                        var r = td.getBoundingClientRect();
                        return r.width >= 20 && r.height >= 20 && r.width <= 200 && r.height <= 200;
                    });
                    if (tds.length === 9) {
                        var rt = tds[idx].getBoundingClientRect();
                        return JSON.stringify({ cx: (rt.left + rt.right) / 2, cy: (rt.top + rt.bottom) / 2 });
                    }
                }
                return '';
            })($index)
            """.trimIndent(),
        )
        val parsed = JSONObject(JSONTokener(rawCenter).nextValue() as String)
        val normX = (parsed.getDouble("cx") / metrics.getDouble("w")).toFloat()
        val normY = (parsed.getDouble("cy") / metrics.getDouble("h")).toFloat()
        compose.onNodeWithTag("game_webview_container").performTouchInput {
            click(Offset(width * normX, height * normY))
        }
    }

    @SuppressLint("NewApi")
    private fun awaitMountedGameWebView(): WebView {
        val ref = AtomicReference<WebView?>(null)
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                for (root in WindowInspector.getGlobalWindowViews()) {
                    val found = findWebView(root)
                    if (found != null) {
                        ref.set(found)
                        break
                    }
                }
            }
            ref.get() != null
        }
        return requireNotNull(ref.get())
    }

    private fun findWebView(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun evaluateOnWebView(webView: WebView, script: String): String {
        val evaluated = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                result = it ?: ""
                evaluated.countDown()
            }
        }
        assertTrue(evaluated.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun capture(name: String): Bitmap {
        val area = File(instrumentation.targetContext.getExternalFilesDir(null), "game-qa").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Screenshot must be captured", image)
        File(area, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    @Test fun arbitraryPlainHttpMsnpServerLoadsCatalogDefaultBlueAndCustomPageBackgroundsAndPlaysEndToEnd() {
        val closed = AtomicBoolean(false)
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = Thread {
            while (!closed.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                try {
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val requestHeaderBytes = java.io.ByteArrayOutputStream()
                    var state = 0
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        requestHeaderBytes.write(b)
                        state = when {
                            b == '\r'.code && (state == 0 || state == 2) -> state + 1
                            b == '\n'.code && (state == 1 || state == 3) -> state + 1
                            else -> 0
                        }
                        if (state == 4) break
                    }
                    val headerText = requestHeaderBytes.toString(StandardCharsets.ISO_8859_1.name())
                    val requestLine = headerText.lineSequence().firstOrNull().orEmpty()
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    val (contentType, body) = when {
                        path.startsWith("/Config/MsgrConfig.asmx") -> "text/xml; charset=utf-8" to """
                            <?xml version="1.0" encoding="utf-8"?>
                            <MsgrConfig>
                                <AbchCfg>
                                    <abchconfig>
                                        <url>http://127.0.0.1:$port/abservice/abservice.asmx</url>
                                    </abchconfig>
                                </AbchCfg>
                                <AppDirConfig>
                                    <AppDirPageURL>http://127.0.0.1:$port/AppDirectory/Directory.aspx</AppDirPageURL>
                                </AppDirConfig>
                                <ContactCard>
                                    <GetItemVerUrl>http://127.0.0.1:$port/storageservice/SchematizedStore.asmx</GetItemVerUrl>
                                </ContactCard>
                            </MsgrConfig>
                        """.trimIndent()

                        path.startsWith("/AppDirectory/AppDirectory.asmx") && path.contains("AppType=0") -> "text/xml; charset=utf-8" to """
                            <DataSet>
                                <Category>
                                    <CategoryID>700</CategoryID>
                                    <Sequence>1</Sequence>
                                    <Name>Custom Games</Name>
                                </Category>
                                <Entry>
                                    <EntryID>700001</EntryID>
                                    <CategoryID>700</CategoryID>
                                    <Sequence>1</Sequence>
                                    <Name>Plain Blue Game</Name>
                                    <URL>http://127.0.0.1:$port/games/unstyled.htm</URL>
                                    <Width>500</Width>
                                    <Height>500</Height>
                                    <MinUsers>2</MinUsers>
                                    <MaxUsers>2</MaxUsers>
                                </Entry>
                                <Entry>
                                    <EntryID>700002</EntryID>
                                    <CategoryID>700</CategoryID>
                                    <Sequence>2</Sequence>
                                    <Name>Custom BG Game</Name>
                                    <URL>http://127.0.0.1:$port/games/custombg.htm</URL>
                                    <Width>500</Width>
                                    <Height>500</Height>
                                    <MinUsers>2</MinUsers>
                                    <MaxUsers>2</MaxUsers>
                                </Entry>
                            </DataSet>
                        """.trimIndent()

                        path.startsWith("/AppDirectory/AppDirectory.asmx") -> "text/xml; charset=utf-8" to "<DataSet />"

                        path.startsWith("/games/unstyled.htm") -> "text/html; charset=utf-8" to """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta charset="utf-8">
                                <script>
                                    function Channel_OnRemoteAppLoaded() {
                                        window.__remoteReady = true;
                                    }
                                    function Channel_OnDataReceived() {
                                        var data = window.external.Channel.Data;
                                        window.__lastReceived = data;
                                        if (data === 'PING') {
                                            window.external.Channel.SendData('PONG');
                                        }
                                    }
                                </script>
                            </head>
                            <body onload="window.external.Channel.Initialize()">
                                <div id="label">Unstyled Custom Game</div>
                            </body>
                            </html>
                        """.trimIndent()

                        path.startsWith("/games/custombg.htm") -> "text/html; charset=utf-8" to """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta charset="utf-8">
                                <style>
                                    body { background-color: rgb(34, 139, 34); }
                                </style>
                            </head>
                            <body>
                                <div id="label">Custom Background Game</div>
                            </body>
                            </html>
                        """.trimIndent()

                        else -> "text/plain; charset=utf-8" to "Not Found"
                    }
                    val statusLine = if (body == "Not Found") "HTTP/1.1 404 Not Found" else "HTTP/1.1 200 OK"
                    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                    val responseHeaders = (
                        "$statusLine\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    socket.getOutputStream().use { out ->
                        out.write(responseHeaders)
                        out.write(bodyBytes)
                        out.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val previousServer = MsnpGameCatalogService.currentActivitiesServer
        try {
            val serverConfig = ServerOverrides(
                mainServer = "127.0.0.1:$port",
                configServer = "127.0.0.1:$port",
            ).toResolved()
            val config = runBlocking { MsnpClientConfigService.fetchConfig(serverConfig) }
            assertEquals("http://127.0.0.1:$port/AppDirectory/AppDirectory.asmx", config.appDirServiceUrl)
            assertEquals("http://127.0.0.1:$port/abservice/abservice.asmx", config.addressBookUrl)
            assertEquals("http://127.0.0.1:$port/storageservice/SchematizedStore.asmx", config.storageServiceUrl)

            val catalog = runBlocking {
                MsnpGameCatalogService.fetchCatalog(
                    forceRefresh = true,
                    activitiesServer = config.appDirServiceUrl!!,
                )
            }
            val unstyledEntry = catalog.findEntry(700001)!!
            val customBgEntry = catalog.findEntry(700002)!!

            withLiveP2pGameHost(unstyledEntry, phoneInitiates = true) { webView, pcP2p, _, pcSessionRef, sessionId ->
                compose.waitUntil(10_000) {
                    evaluateOnWebView(webView, "String(window.__remoteReady === true)") == "\"true\""
                }
                compose.waitUntil(5_000) {
                    evaluateOnWebView(webView, "getComputedStyle(document.documentElement).backgroundColor") == "\"rgb(81, 108, 161)\""
                }
                runBlocking { pcP2p.sendGameData(sessionId, "PING") }
                compose.waitUntil(5_000) {
                    evaluateOnWebView(webView, "window.__lastReceived || ''") == "\"PING\"" &&
                        pcSessionRef.get()?.receivedPackets.orEmpty().any { it.payload == "PONG" }
                }
            }

            val customController = createController(entry = customBgEntry)
            try {
                awaitPage(customController)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var bodyBg = ""
                var hasDefaultStyle = "true"
                while (System.nanoTime() < deadline) {
                    bodyBg = evaluate(customController, "getComputedStyle(document.body).backgroundColor")
                    hasDefaultStyle = evaluate(customController, "String(!!document.getElementById('__p4_default_bg'))")
                    if (bodyBg == "\"rgb(34, 139, 34)\"" && hasDefaultStyle == "\"false\"") break
                    Thread.sleep(25)
                }
                assertEquals("\"rgb(34, 139, 34)\"", bodyBg)
                assertEquals("\"false\"", hasDefaultStyle)
            } finally {
                instrumentation.runOnMainSync { customController.destroy() }
            }
        } finally {
            closed.set(true)
            runCatching { serverSocket.close() }
            MsnpGameCatalogService.currentActivitiesServer = previousServer
        }
    }

    private fun createController(
        entry: GameCatalogEntry? = null,
        onInitialize: () -> Unit = {},
        onSendData: (String) -> Unit = {},
        onSendImAsUser: (String) -> Unit = {},
        onCloseApp: () -> Unit = {},
    ): GameWebViewController {
        lateinit var controller: GameWebViewController
        val localPage = "http://127.0.0.1:1/game.htm"
        instrumentation.runOnMainSync {
            controller = GameWebViewController(
                context = instrumentation.targetContext,
                sessionId = "webview-test",
                entry = entry ?: MsnpGameCatalogService.defaultCatalog().findEntry(101000)!!.copy(url = localPage),
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
            if (entry == null) {
                controller.webView.stopLoading()
                controller.webView.loadDataWithBaseURL(localPage, "<html><body></body></html>", "text/html", "utf-8", localPage)
            }
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

    private fun evaluate(controller: GameWebViewController, script: String): String =
        evaluateOnWebView(controller.webView, script)
}
