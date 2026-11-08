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
import org.aralozkaya.msngr.data.GameSession
import org.aralozkaya.msngr.data.GameSessionStatus
import org.aralozkaya.msngr.data.MsnpGameCatalogService
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.ui.theme.DarkColorScheme
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                "\"rgb(255, 255, 255)\"",
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
            assertEquals("rgb(255, 255, 255)", metrics.getString("htmlBg"))
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
                "Whiteboard frame background must be pure white even in dark theme, was #${Integer.toHexString(pixel)}",
                Color.red(pixel) >= 250 && Color.green(pixel) >= 250 && Color.blue(pixel) >= 250,
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
                "\"rgb(255, 255, 255)\"",
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

    private fun evaluate(controller: GameWebViewController, script: String): String =
        evaluateOnWebView(controller.webView, script)
}
