@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.pages.components.DestructiveOutlinedButton
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private fun Context.gameRepository() =
    (applicationContext as MsngrApplication).container.messengerRepository

private val gameIconCache = object : LruCache<String, Bitmap>(32) {}

private suspend fun fetchGameIconBitmap(url: String): Bitmap? {
    if (url.isBlank()) return null
    val normalized = MsnpGameCatalogService.upgradeKnownHttpsUrl(url)
    gameIconCache.get(normalized)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(normalized).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val bytes = connection.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                    gameIconCache.put(normalized, it)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

@Composable
internal fun GameEntryIcon(
    entry: GameCatalogEntry,
    modifier: Modifier = Modifier.size(40.dp),
) {
    val iconUrl = entry.effectiveIconUrl
    val bitmap by produceState<Bitmap?>(initialValue = gameIconCache.get(iconUrl), key1 = iconUrl) {
        if (value == null && iconUrl.isNotBlank()) {
            value = fetchGameIconBitmap(iconUrl)
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if (loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = if (entry.appType == 1) Icons.Default.Brush else Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * A full-screen dialog listing the games and activities a contact can be invited to. Its top bar
 * only has a navigation icon: there is deliberately no action button. Back closes it with a
 * predictive back animation, unless a game invitation is being sent.
 */
@Composable
internal fun GameCatalogDialog(
    contactId: String,
    onDismiss: () -> Unit,
    onStartGame: (suspend (GameCatalogEntry) -> Result<Unit>)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activitiesServer = remember(context) {
        runCatching { SessionPrefs.readServerConfig(context).activitiesServer }
            .getOrDefault(MsnpGameCatalogService.DEFAULT_ACTIVITIES_SERVER)
    }
    var catalog by remember { mutableStateOf(MsnpGameCatalogService.defaultCatalog()) }
    var startingAppId by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val backProgress = remember { Animatable(0f) }

    LaunchedEffect(activitiesServer) {
        catalog = MsnpGameCatalogService.fetchCatalog(activitiesServer = activitiesServer)
    }

    Dialog(
        onDismissRequest = { if (startingAppId == null) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Registered after the dialog's own callback, so it takes precedence. While an invitation
        // is being sent the dialog's callback stays enabled and swallows Back.
        PredictiveBackHandler(enabled = startingAppId == null) { events ->
            try {
                events.collect { backProgress.snapTo(it.progress) }
                currentOnDismiss()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { backProgress.animateTo(0f) }
                throw cancelled
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = backProgress.value
                    val scale = 1f - 0.1f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.2f * progress
                    shape = RoundedCornerShape((28 * progress).dp)
                    clip = progress > 0f
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize().testTag("game_catalog_dialog"),
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text("Games & activities", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                enabled = startingAppId == null,
                                modifier = Modifier.testTag("game_catalog_close"),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                GameCatalogContent(
                    catalog = catalog,
                    startingAppId = startingAppId,
                    error = error,
                    onStart = { entry ->
                        scope.launch {
                            startingAppId = entry.appId
                            error = null
                            try {
                                val result = onStartGame?.invoke(entry)
                                    ?: context.gameRepository().startGame(contactId, entry)
                                error = result.exceptionOrNull()?.message
                                if (result.isSuccess) {
                                    onDismiss()
                                }
                            } finally {
                                startingAppId = null
                            }
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun GameCatalogContent(
    catalog: GameCatalog,
    startingAppId: Int?,
    error: String?,
    onStart: (GameCatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp)
            .testTag("game_catalog_sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Invite your contact to play a game or share an activity right inside the chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val entriesByCategory = remember(catalog) {
            catalog.visibleEntries.groupBy { it.categoryId }
        }
        for (category in catalog.categories) {
            val categoryEntries = entriesByCategory[category.categoryId].orEmpty()
            if (categoryEntries.isEmpty()) continue
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (category.description.isNotBlank()) {
                    Text(
                        text = category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (entry in categoryEntries) {
                    val isStarting = startingAppId == entry.appId
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = startingAppId == null) { onStart(entry) }
                            .testTag("game_catalog_entry_${entry.appId}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GameEntryIcon(entry = entry)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = when {
                                                entry.appType == 1 -> "Activity"
                                                entry.minUsers == 1 && entry.maxUsers == 1 -> "1 Player"
                                                entry.minUsers == 1 -> "1–${entry.maxUsers} Players"
                                                else -> "${entry.maxUsers} Players"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (entry.description.isNotBlank()) {
                                    Text(
                                        text = entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (isStarting) {
                                MsngrCircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal const val DEFAULT_NATIVE_GAME_PANE_WIDTH = 500
internal const val DEFAULT_NATIVE_GAME_PANE_HEIGHT = 500
internal const val WHITEBOARD_NATIVE_PANE_HEIGHT = 568
internal const val WORDLE_NATIVE_PANE_HEIGHT = 560

internal fun nativeGamePaneDimensions(entry: GameCatalogEntry): Pair<Int, Int> {
    val width = entry.width.takeIf { it > 0 }?.coerceAtLeast(DEFAULT_NATIVE_GAME_PANE_WIDTH)
        ?: DEFAULT_NATIVE_GAME_PANE_WIDTH
    val minHeight = when (entry.appId) {
        100000 -> WHITEBOARD_NATIVE_PANE_HEIGHT
        103000 -> WORDLE_NATIVE_PANE_HEIGHT
        else -> DEFAULT_NATIVE_GAME_PANE_HEIGHT
    }
    val height = entry.height.takeIf { it > 0 }?.coerceAtLeast(minHeight) ?: minHeight
    return width to height
}

private const val P4_BASE_CSS = """
html {
    background-color: #ffffff;
    height: 100%;
}
body[scroll="no"] {
    height: 100%;
    overflow: hidden;
    touch-action: none;
}
#divCanvas {
    touch-action: none;
}
td > img:only-child {
    vertical-align: top;
}
#myBoard:not(.board), #remoteBoard:not(.board) {
    width: 210px;
    height: 210px;
}
#myBoard:not(.board) tr, #remoteBoard:not(.board) tr {
    height: 21px;
}
#myBoard:not(.board) td, #remoteBoard:not(.board) td {
    width: 21px;
    height: 21px;
    padding: 0;
    line-height: 0;
    background-repeat: no-repeat;
}
#myBoard.board, #remoteBoard.board {
    width: 160px;
    height: 320px;
}
#myBoard.board tr, #remoteBoard.board tr {
    height: 16px;
}
#myBoard.board td, #remoteBoard.board td {
    width: 16px;
    height: 16px;
    padding: 0;
    line-height: 0;
}
#divLines {
    pointer-events: none;
    width: 450px;
    height: 450px;
    overflow: hidden;
}
#brushText {
    margin: 0;
    top: 58px !important;
    right: 39px !important;
    font-size: 11px !important;
}
"""

/**
 * Hosts the game [WebView] at its exact native Windows Live Messenger logical pane dimensions
 * (e.g. 500x500 CSS pixels) and uniformly scales the rendered surface to fit the container.
 * Android's [ViewGroup] touch dispatch automatically maps container touches through the child's
 * inverse scale matrix, preserving 1:1 mouse/touch coordinates inside the game document.
 */
internal class NativeGamePaneContainer(context: Context) : FrameLayout(context) {
    var paneWidthPx: Int = 0
    var paneHeightPx: Int = 0

    init {
        setBackgroundColor(AndroidColor.WHITE)
        clipChildren = true
        clipToPadding = true
    }

    fun updatePaneSize(widthPx: Int, heightPx: Int) {
        if (paneWidthPx != widthPx || paneHeightPx != heightPx) {
            paneWidthPx = widthPx
            paneHeightPx = heightPx
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerW = MeasureSpec.getSize(widthMeasureSpec)
        val containerH = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(containerW, containerH)
        val targetW = if (paneWidthPx > 0) paneWidthPx else containerW
        val targetH = if (paneHeightPx > 0) paneHeightPx else containerH
        val childWSpec = MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY)
        val childHSpec = MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWSpec, childHSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val containerW = right - left
        val containerH = bottom - top
        val targetW = if (paneWidthPx > 0) paneWidthPx else containerW
        val targetH = if (paneHeightPx > 0) paneHeightPx else containerH
        val sx = if (targetW > 0 && containerW > 0) containerW.toFloat() / targetW.toFloat() else 1f
        val sy = if (targetH > 0 && containerH > 0) containerH.toFloat() / targetH.toFloat() else 1f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = sx
            child.scaleY = sy
            child.layout(0, 0, targetW, targetH)
        }
    }
}

internal sealed class GameJsDialogKind {
    data object Alert : GameJsDialogKind()
    data object Confirm : GameJsDialogKind()
    data class Prompt(val defaultValue: String) : GameJsDialogKind()
}

internal class GameJsDialogRequest(
    val id: Long,
    val kind: GameJsDialogKind,
    val message: String,
    private val onConfirm: (String?) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val settled = AtomicBoolean(false)

    val isSettled: Boolean
        get() = settled.get()

    fun confirm(promptValue: String? = null) {
        if (settled.compareAndSet(false, true)) {
            onConfirm(promptValue)
        }
    }

    fun cancel() {
        if (settled.compareAndSet(false, true)) {
            onCancel()
        }
    }
}

internal class GameWebViewController(
    context: Context,
    val sessionId: String,
    val entry: GameCatalogEntry,
    val isInviter: Boolean,
    val localName: String,
    val localEmail: String,
    val peerName: String,
    val peerEmail: String,
    private val onInitialize: () -> Unit,
    private val onSendData: (String) -> Unit,
    private val onSendImAsUser: (String) -> Unit,
    private val onCloseApp: () -> Unit,
    private val onOpenHelpUrl: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val paneDimensions: Pair<Int, Int> = nativeGamePaneDimensions(entry)
    val paneWidth: Int get() = paneDimensions.first
    val paneHeight: Int get() = paneDimensions.second
    var pageFinished by mutableStateOf(false)
        private set
    private var nextJsDialogId: Long = 1L
    private val jsDialogQueue = ArrayDeque<GameJsDialogRequest>()
    var activeJsDialog by mutableStateOf<GameJsDialogRequest?>(null)
        private set
    var lastDeliveredSequence: Long = 0L
    var remoteLoadedDispatched: Boolean = false
    var remoteClosedDispatched: Boolean = false

    internal fun enqueueJsDialog(
        kind: GameJsDialogKind,
        message: String,
        onConfirm: (String?) -> Unit,
        onCancel: () -> Unit,
    ) {
        val id = nextJsDialogId++
        fun advance() {
            if (activeJsDialog?.id == id) {
                activeJsDialog = jsDialogQueue.removeFirstOrNull()
            } else {
                jsDialogQueue.removeAll { it.id == id }
            }
        }
        val request = GameJsDialogRequest(
            id = id,
            kind = kind,
            message = message,
            onConfirm = { value ->
                try {
                    onConfirm(value)
                } finally {
                    advance()
                }
            },
            onCancel = {
                try {
                    onCancel()
                } finally {
                    advance()
                }
            },
        )
        if (activeJsDialog == null) {
            activeJsDialog = request
        } else {
            jsDialogQueue.addLast(request)
        }
    }

    fun dismissInteractiveJsDialogs() {
        val remaining = jsDialogQueue.filter { it.kind is GameJsDialogKind.Alert }
        val toCancel = jsDialogQueue.filter { it.kind !is GameJsDialogKind.Alert }
        jsDialogQueue.clear()
        jsDialogQueue.addAll(remaining)
        toCancel.forEach { it.cancel() }
        val current = activeJsDialog
        if (current != null && current.kind !is GameJsDialogKind.Alert) {
            current.cancel()
        }
    }

    fun dismissAllJsDialogs() {
        val pending = jsDialogQueue.toList()
        jsDialogQueue.clear()
        pending.forEach { it.cancel() }
        activeJsDialog?.cancel()
        activeJsDialog = null
    }

    private val bridgeScript: String = buildP4PolyfillScript(
        isInviter = isInviter,
        localName = localName,
        localEmail = localEmail,
        peerName = peerName,
        peerEmail = peerEmail,
        targetWidth = paneWidth,
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    val webView: WebView = WebView(context.applicationContext).apply {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val initialWidthPx = (paneWidth * density).roundToInt()
        val initialHeightPx = (paneHeight * density).roundToInt()
        layoutParams = FrameLayout.LayoutParams(initialWidthPx, initialHeightPx)
        setBackgroundColor(AndroidColor.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.0; MSNMSGR 14.0.8117.0416)"
        }
        measure(
            View.MeasureSpec.makeMeasureSpec(initialWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(initialHeightPx, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, initialWidthPx, initialHeightPx)
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        addJavascriptInterface(BridgeInterface(), "AndroidP4Bridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = MsnpGameCatalogService.upgradeKnownHttpsUrl(request.url.toString())
                if (target.contains("help", ignoreCase = true) &&
                    (target.endsWith(".htm", ignoreCase = true) || target.endsWith(".html", ignoreCase = true))
                ) {
                    mainHandler.post { onOpenHelpUrl(target) }
                    return true
                }
                val entryHost = runCatching { URL(entry.url).host.lowercase() }.getOrDefault("")
                val reqHost = request.url.host?.lowercase().orEmpty()
                if (reqHost == entryHost || reqHost == "static.levelleap.com" || reqHost.endsWith(".nina.chat")) {
                    return false
                }
                // Never delegate to an external browser.
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val rawUrl = request.url.toString()
                val upgraded = MsnpGameCatalogService.upgradeKnownHttpsUrl(rawUrl)
                val path = request.url.path.orEmpty().lowercase()
                val isHtmlDocument = request.isForMainFrame ||
                    path.endsWith(".htm") ||
                    path.endsWith(".html")
                val isHelpPage = path.contains("help")
                if (isHtmlDocument && !isHelpPage &&
                    (upgraded.startsWith("https://static.levelleap.com/") ||
                        upgraded.startsWith("https://apps.escargot.nina.chat/"))
                ) {
                    return runCatching {
                        fetchAndInjectHtml(upgraded, bridgeScript)
                    }.getOrNull()
                }
                if (rawUrl.startsWith("http://", ignoreCase = true) && upgraded.startsWith("https://")) {
                    return runCatching { fetchSubresource(upgraded) }.getOrNull()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(bridgeScript, null)
                pageFinished = true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                enqueueJsDialog(
                    kind = GameJsDialogKind.Alert,
                    message = message.orEmpty(),
                    onConfirm = { result.confirm() },
                    onCancel = { result.cancel() },
                )
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                enqueueJsDialog(
                    kind = GameJsDialogKind.Confirm,
                    message = message.orEmpty(),
                    onConfirm = { result.confirm() },
                    onCancel = { result.cancel() },
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                if (result == null) return false
                enqueueJsDialog(
                    kind = GameJsDialogKind.Prompt(defaultValue.orEmpty()),
                    message = message.orEmpty(),
                    onConfirm = { value -> result.confirm(value.orEmpty()) },
                    onCancel = { result.cancel() },
                )
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                result?.confirm()
                return true
            }
        }
        loadUrl(MsnpGameCatalogService.upgradeKnownHttpsUrl(entry.url))
    }

    fun dispatchRemoteAppLoaded() {
        if (remoteLoadedDispatched) return
        remoteLoadedDispatched = true
        webView.evaluateJavascript(
            "if (window.__p4OnRemoteAppLoaded) window.__p4OnRemoteAppLoaded();",
            null,
        )
    }

    fun dispatchDataReceived(payload: String) {
        val escaped = JSONObject.quote(payload)
        webView.evaluateJavascript(
            "if (window.__p4OnDataReceived) window.__p4OnDataReceived($escaped);",
            null,
        )
    }

    fun dispatchRemoteAppClosed() {
        if (remoteClosedDispatched) return
        remoteClosedDispatched = true
        webView.evaluateJavascript(
            "if (window.__p4OnRemoteAppClosed) window.__p4OnRemoteAppClosed();",
            null,
        )
    }

    fun sendKey(eventType: String, keyCode: Int, keyChar: String = "") {
        val escapedKey = JSONObject.quote(keyChar)
        webView.evaluateJavascript(
            """
            (function() {
                var ev = new KeyboardEvent('$eventType', {
                    key: $escapedKey,
                    keyCode: $keyCode,
                    which: $keyCode,
                    bubbles: true,
                    cancelable: true
                });
                Object.defineProperty(ev, 'keyCode', { get: function() { return $keyCode; } });
                Object.defineProperty(ev, 'which', { get: function() { return $keyCode; } });
                window.event = ev;
                (document.body || document).dispatchEvent(ev);
            })();
            """.trimIndent(),
            null,
        )
    }

    fun destroy() {
        dismissAllJsDialogs()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private inner class BridgeInterface {
        // These JavaScript API names also name controller callbacks; qualify the
        // receiver so the posted work does not recursively call the bridge.
        @JavascriptInterface
        fun onInitialize() {
            mainHandler.post { this@GameWebViewController.onInitialize() }
        }

        @JavascriptInterface
        fun onSendData(payload: String) {
            mainHandler.post { this@GameWebViewController.onSendData(payload) }
        }

        @JavascriptInterface
        fun onSendImAsUser(message: String) {
            mainHandler.post { this@GameWebViewController.onSendImAsUser(message) }
        }

        @JavascriptInterface
        fun onCloseApp() {
            mainHandler.post { this@GameWebViewController.onCloseApp() }
        }

        @JavascriptInterface
        fun onOpenHelp(url: String) {
            mainHandler.post {
                onOpenHelpUrl(MsnpGameCatalogService.upgradeKnownHttpsUrl(url))
            }
        }
    }
}

private val scriptBlockRegex = Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
private val htmlStartTagRegex = Regex("""<[A-Za-z][A-Za-z0-9:-]*\b[^>]*>""")
private val styleAttrRegex = Regex("""\bstyle\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val ieCenterExpressionRegex = Regex(
    """left\s*:\s*expression\([^)]*\)\s*;\s*top\s*:\s*expression\([^)]*\)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Normalizes legacy IE7 HTML/CSS constructs used by Escargot and Mess.be games so they render
 * identically in Chromium:
 * - Merges duplicate `style="..."` attributes on a single HTML start tag (used on `<body>` by
 *   `battleships.htm`, `yahtzee.htm`, and `backgammon.htm`, where Chromium otherwise drops the
 *   second `style="background-color:#516CA1"` attribute).
 * - Replaces legacy IE CSS `expression(...)` centering on `#pnlGameover` / `#pnlPromote` with
 *   standard CSS percentage/transform centering.
 */
internal fun normalizeLegacyGameHtml(html: String): String {
    val result = StringBuilder(html.length + 64)
    var cursor = 0
    for (scriptMatch in scriptBlockRegex.findAll(html)) {
        if (scriptMatch.range.first > cursor) {
            result.append(normalizeNonScriptHtmlChunk(html.substring(cursor, scriptMatch.range.first)))
        }
        result.append(scriptMatch.value)
        cursor = scriptMatch.range.last + 1
    }
    if (cursor < html.length) {
        result.append(normalizeNonScriptHtmlChunk(html.substring(cursor)))
    }
    return result.toString()
}

private fun normalizeNonScriptHtmlChunk(chunk: String): String {
    val mergedTags = htmlStartTagRegex.replace(chunk) { tagMatch ->
        val tag = tagMatch.value
        val styleMatches = styleAttrRegex.findAll(tag).toList()
        if (styleMatches.size < 2) {
            tag
        } else {
            val combinedStyle = styleMatches
                .map { (it.groups[1]?.value ?: it.groups[2]?.value).orEmpty().trim().trimEnd(';') }
                .filter { it.isNotEmpty() }
                .joinToString(";")
            val sb = StringBuilder(tag.length)
            var lastIndex = 0
            styleMatches.forEachIndexed { index, match ->
                sb.append(tag, lastIndex, match.range.first)
                if (index == 0) {
                    sb.append("style=\"").append(combinedStyle).append('"')
                }
                lastIndex = match.range.last + 1
            }
            sb.append(tag, lastIndex, tag.length)
            sb.toString()
        }
    }
    return ieCenterExpressionRegex.replace(
        mergedTags,
        "left:50%;top:50%;transform:translate(-50%,-100%)",
    )
}

private fun fetchAndInjectHtml(url: String, script: String): WebResourceResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
    }
    try {
        val bytes = connection.inputStream.use { it.readBytes() }
        val html = normalizeLegacyGameHtml(String(bytes, StandardCharsets.UTF_8))
        val injection = "<style id=\"__p4_base_style\">$P4_BASE_CSS</style><script>$script</script>"
        val headRegex = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        val match = headRegex.find(html)
        val modified = if (match != null) {
            html.substring(0, match.range.last + 1) + injection + html.substring(match.range.last + 1)
        } else {
            injection + html
        }
        return WebResourceResponse(
            "text/html",
            "utf-8",
            ByteArrayInputStream(modified.toByteArray(StandardCharsets.UTF_8)),
        )
    } finally {
        connection.disconnect()
    }
}

private fun fetchSubresource(url: String): WebResourceResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
    }
    val contentType = connection.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank {
        when {
            url.endsWith(".js", true) -> "application/javascript"
            url.endsWith(".css", true) -> "text/css"
            url.endsWith(".xml", true) -> "application/xml"
            url.endsWith(".png", true) -> "image/png"
            url.endsWith(".gif", true) -> "image/gif"
            url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) -> "image/jpeg"
            url.endsWith(".wav", true) -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
    val bytes = try {
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
    return WebResourceResponse(contentType, "utf-8", ByteArrayInputStream(bytes))
}

internal fun buildP4PolyfillScript(
    isInviter: Boolean,
    localName: String,
    localEmail: String,
    peerName: String,
    peerEmail: String,
    targetWidth: Int,
): String {
    val safeLocalName = JSONObject.quote(localName.ifBlank { localEmail.substringBefore('@') })
    val safeLocalEmail = JSONObject.quote(localEmail)
    val safePeerName = JSONObject.quote(peerName.ifBlank { peerEmail.substringBefore('@') })
    val safePeerEmail = JSONObject.quote(peerEmail)
    val inviterLiteral = if (isInviter) "true" else "false"
    val baseCssLiteral = JSONObject.quote(P4_BASE_CSS)

    return """
(function() {
    if (window.__p4PolyfillInstalled) return;
    window.__p4PolyfillInstalled = true;
    window.__p4TargetWidth = $targetWidth;
    if (typeof window.Line === 'undefined') {
        window.Line = '';
    }

    var ensureBaseStyle = function() {
        try {
            if (document.getElementById('__p4_base_style')) return;
            var root = document.head || document.documentElement;
            if (!root) return;
            var styleEl = document.createElement('style');
            styleEl.id = '__p4_base_style';
            styleEl.textContent = $baseCssLiteral;
            root.insertBefore(styleEl, root.firstChild);
        } catch (e) {}
    };
    ensureBaseStyle();
    window.addEventListener('DOMContentLoaded', ensureBaseStyle);

    try {
        if (!navigator.browserLanguage) {
            Object.defineProperty(navigator, 'browserLanguage', {
                get: function() { return navigator.language || 'en-us'; },
                configurable: true
            });
        }
    } catch (e) {}

    try {
        if (typeof HTMLTableElement !== 'undefined' && !('cells' in HTMLTableElement.prototype)) {
            Object.defineProperty(HTMLTableElement.prototype, 'cells', {
                get: function() { return this.querySelectorAll('td, th'); },
                configurable: true
            });
        }
        if (typeof HTMLTableRowElement !== 'undefined' && !('height' in HTMLTableRowElement.prototype)) {
            Object.defineProperty(HTMLTableRowElement.prototype, 'height', {
                get: function() { return this.getAttribute('height') || ''; },
                set: function(val) {
                    var s = String(val);
                    this.setAttribute('height', s);
                    this.style.height = /^\d+$/.test(s) ? (s + 'px') : s;
                },
                configurable: true
            });
        }
    } catch (e) {}

    try {
        if (!EventTarget.prototype.attachEvent) {
            EventTarget.prototype.attachEvent = function(type, listener) {
                var evName = String(type).replace(/^on/i, '');
                var wrapped = function(e) {
                    window.event = e;
                    return listener.call(this, e);
                };
                listener.__p4Wrapped = wrapped;
                this.addEventListener(evName, wrapped, false);
                return true;
            };
            EventTarget.prototype.detachEvent = function(type, listener) {
                var evName = String(type).replace(/^on/i, '');
                this.removeEventListener(evName, (listener && listener.__p4Wrapped) || listener, false);
            };
        }
    } catch (e) {}

    ['click', 'mousedown', 'mousemove', 'mouseup', 'keydown', 'keyup', 'keypress'].forEach(function(evt) {
        window.addEventListener(evt, function(e) { window.event = e; }, true);
    });

    try {
        if (!('text' in Node.prototype)) {
            Object.defineProperty(Node.prototype, 'text', {
                get: function() { return this.textContent || ''; },
                set: function(v) { this.textContent = v; },
                configurable: true
            });
        }
        var selectSingle = function(xpath) {
            var doc = this.ownerDocument || this;
            var ctx = (this.nodeType === 9) ? this.documentElement : this;
            if (!ctx) return null;
            try {
                var res = doc.evaluate(xpath, ctx, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                if (res && res.singleNodeValue) return res.singleNodeValue;
            } catch (e) {}
            var parts = String(xpath).split('/').filter(Boolean);
            var lastTag = parts[parts.length - 1];
            if (lastTag && ctx.getElementsByTagName) {
                var list = ctx.getElementsByTagName(lastTag);
                if (list && list.length > 0) return list[0];
            }
            return null;
        };
        Node.prototype.selectSingleNode = selectSingle;
        if (typeof XMLDocument !== 'undefined') {
            XMLDocument.prototype.selectSingleNode = selectSingle;
        }
        Object.defineProperty(HTMLElement.prototype, 'XMLDocument', {
            get: function() {
                if (!this.__p4XmlObj) this.__p4XmlObj = { async: false };
                return this.__p4XmlObj;
            },
            configurable: true
        });
        HTMLElement.prototype.load = function(url) {
            try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', url, false);
                xhr.send(null);
                if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText) {
                    var parser = new DOMParser();
                    var parsed = parser.parseFromString(xhr.responseText, 'text/xml');
                    this.__p4ParsedXml = parsed;
                    this.selectSingleNode = function(xpath) {
                        return selectSingle.call(parsed, xpath);
                    };
                    return true;
                }
            } catch (e) {}
            return false;
        };
    } catch (e) {}

    try {
        var origSrcDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'src');
        Object.defineProperty(HTMLElement.prototype, 'src', {
            get: function() {
                if (this.tagName === 'BGSOUND') return this.getAttribute('src') || '';
                return origSrcDesc && origSrcDesc.get ? origSrcDesc.get.call(this) : this.getAttribute('src');
            },
            set: function(val) {
                this.setAttribute('src', val);
                if (this.tagName === 'BGSOUND' && val) {
                    try {
                        var audio = new Audio(new URL(val, window.location.href).href);
                        audio.play().catch(function() {});
                    } catch (e) {}
                } else if (origSrcDesc && origSrcDesc.set) {
                    origSrcDesc.set.call(this, val);
                }
            },
            configurable: true
        });
    } catch (e) {}

    try {
        var refreshVmlShape = function(el) {
            if (!el || !el.tagName || el.tagName.toLowerCase().indexOf('shape') === -1) return;
            var rawPath = (el.__p4PathObj && el.__p4PathObj.v) || el.getAttribute('path') || '';
            if (!rawPath) return;
            var d = String(rawPath)
                .replace(/m\s*([-\d.]+)\s*,\s*([-\d.]+)/gi, 'M $1 $2 ')
                .replace(/l\s*([-\d.,\s]+)/gi, function(_, coords) {
                    var nums = coords.trim().split(/[\s,]+/);
                    var out = [];
                    for (var i = 0; i + 1 < nums.length; i += 2) {
                        out.push('L ' + nums[i] + ' ' + nums[i + 1]);
                    }
                    return out.join(' ');
                })
                .replace(/\be\b/gi, '');
            var stroke = el.__p4StrokeColor || el.getAttribute('strokecolor') || '#000000';
            var weight = el.__p4StrokeWeight || el.getAttribute('strokeweight') || '3px';
            var sw = parseFloat(String(weight)) || 3;
            el.style.pointerEvents = 'none';
            var svg = el.querySelector('svg.__p4VmlSvg');
            if (!svg) {
                svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                svg.setAttribute('class', '__p4VmlSvg');
                svg.setAttribute('viewBox', '0 0 1000 1000');
                svg.setAttribute('preserveAspectRatio', 'none');
                svg.style.position = 'absolute';
                svg.style.left = '0';
                svg.style.top = '0';
                svg.style.width = '100%';
                svg.style.height = '100%';
                svg.style.pointerEvents = 'none';
                svg.style.overflow = 'visible';
                var pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                pathEl.setAttribute('fill', 'none');
                pathEl.setAttribute('stroke-linecap', 'round');
                pathEl.setAttribute('stroke-linejoin', 'round');
                svg.appendChild(pathEl);
                el.appendChild(svg);
            }
            var p = svg.firstChild;
            if (p) {
                p.setAttribute('d', d);
                p.setAttribute('stroke', stroke);
                p.setAttribute('stroke-width', String(sw));
            }
        };
        Object.defineProperty(HTMLElement.prototype, 'path', {
            get: function() {
                var self = this;
                if (!this.__p4PathObj) {
                    var vVal = this.getAttribute('path') || '';
                    this.__p4PathObj = {};
                    Object.defineProperty(this.__p4PathObj, 'v', {
                        get: function() { return vVal; },
                        set: function(nv) { vVal = String(nv); refreshVmlShape(self); }
                    });
                }
                return this.__p4PathObj;
            },
            set: function(val) {
                this.setAttribute('path', val);
                this.path.v = val;
            },
            configurable: true
        });
        Object.defineProperty(HTMLElement.prototype, 'strokecolor', {
            get: function() { return this.__p4StrokeColor || this.getAttribute('strokecolor') || '#000'; },
            set: function(val) { this.__p4StrokeColor = val; this.setAttribute('strokecolor', val); refreshVmlShape(this); },
            configurable: true
        });
        Object.defineProperty(HTMLElement.prototype, 'strokeweight', {
            get: function() { return this.__p4StrokeWeight || this.getAttribute('strokeweight') || '1px'; },
            set: function(val) { this.__p4StrokeWeight = val; this.setAttribute('strokeweight', val); refreshVmlShape(this); },
            configurable: true
        });
    } catch (e) {}

    window.open = function(url) {
        if (url && window.AndroidP4Bridge && window.AndroidP4Bridge.onOpenHelp) {
            try {
                window.AndroidP4Bridge.onOpenHelp(new URL(url, window.location.href).href);
            } catch (e) {}
        }
        return null;
    };

    var makeUser = function(name, email) {
        return {
            Name: name,
            name: name,
            EMail: email,
            Email: email,
            email: email,
            LocalIP: '127.0.0.1',
            GlobalIP: '127.0.0.1',
            PUID: '0',
            CID: '0'
        };
    };
    var meUser = makeUser($safeLocalName, $safeLocalEmail);
    var peerUser = makeUser($safePeerName, $safePeerEmail);
    var isInviter = $inviterLiteral;
    var inviterUser = isInviter ? meUser : peerUser;
    var user0 = isInviter ? meUser : peerUser;
    var user1 = isInviter ? peerUser : meUser;

    var usersObj = {
        0: user0,
        1: user1,
        Me: meUser,
        me: meUser,
        Inviter: inviterUser,
        inviter: inviterUser,
        Count: 2,
        count: 2,
        Item: function(i) { return i === 0 ? user0 : user1; },
        item: function(i) { return i === 0 ? user0 : user1; }
    };

    var localInitialized = false;
    var remoteLoaded = false;
    var remoteLoadedFired = false;

    var fireRemoteLoadedIfReady = function() {
        if (localInitialized && remoteLoaded && !remoteLoadedFired) {
            remoteLoadedFired = true;
            setTimeout(function() {
                if (typeof window.Channel_OnRemoteAppLoaded === 'function') {
                    window.Channel_OnRemoteAppLoaded();
                }
            }, 0);
        }
    };

    var channelObj = {
        Data: null,
        data: null,
        IM: '',
        im: '',
        Type: 1,
        type: 1,
        Error: { Type: 0, Data: null },
        error: { Type: 0, Data: null },
        Initialize: function() {
            localInitialized = true;
            if (window.AndroidP4Bridge && window.AndroidP4Bridge.onInitialize) {
                window.AndroidP4Bridge.onInitialize();
            }
            fireRemoteLoadedIfReady();
        },
        initialize: function() { this.Initialize(); },
        SendData: function(v) {
            var payload = (v === null || v === undefined) ? '' : String(v);
            if (window.AndroidP4Bridge && window.AndroidP4Bridge.onSendData) {
                window.AndroidP4Bridge.onSendData(payload);
            }
        },
        sendData: function(v) { this.SendData(v); },
        SendIM: function(msg) {
            if (window.AndroidP4Bridge && window.AndroidP4Bridge.onSendImAsUser) {
                window.AndroidP4Bridge.onSendImAsUser(String(msg || ''));
            }
        },
        EnterIM: function(msg) { this.SendIM(msg); },
        SendIMAsUser: function(msg) { this.SendIM(msg); }
    };

    var externalObj = {
        Users: usersObj,
        users: usersObj,
        Channel: channelObj,
        channel: channelObj,
        Messenger: { Options: {}, Phone: {} },
        messenger: { Options: {}, Phone: {} },
        CloseApp: function() {
            if (window.AndroidP4Bridge && window.AndroidP4Bridge.onCloseApp) {
                window.AndroidP4Bridge.onCloseApp();
            }
        },
        closeApp: function() { this.CloseApp(); }
    };

    try {
        Object.defineProperty(window, 'external', {
            get: function() { return externalObj; },
            configurable: true
        });
    } catch (e) {
        window.external = externalObj;
    }

    window.__p4OnRemoteAppLoaded = function() {
        remoteLoaded = true;
        fireRemoteLoadedIfReady();
    };
    window.__p4OnDataReceived = function(payload) {
        channelObj.Data = payload;
        channelObj.data = payload;
        if (typeof window.Channel_OnDataReceived === 'function') {
            window.Channel_OnDataReceived();
        }
    };
    window.__p4OnRemoteAppClosed = function() {
        if (typeof window.Channel_OnRemoteAppClosed === 'function') {
            window.Channel_OnRemoteAppClosed();
        }
    };

    var touchToMouse = function(type, touchEvent, buttonOverride) {
        var t = (touchEvent.touches && touchEvent.touches[0]) ||
                (touchEvent.changedTouches && touchEvent.changedTouches[0]);
        if (!t) return;
        var target = document.elementFromPoint(t.clientX, t.clientY) || touchEvent.target;
        if (!target) return;
        var btn = (typeof buttonOverride === 'number') ? buttonOverride : (type === 'mouseup' ? 0 : 1);
        var mouseEv = new MouseEvent(type, {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: t.clientX,
            clientY: t.clientY,
            screenX: t.screenX,
            screenY: t.screenY,
            button: 0,
            buttons: btn
        });
        try {
            Object.defineProperty(mouseEv, 'button', { get: function() { return btn; } });
            Object.defineProperty(mouseEv, 'offsetX', {
                get: function() {
                    var r = target.getBoundingClientRect();
                    var scale = r.width && target.offsetWidth ? (target.offsetWidth / r.width) : 1;
                    return Math.round((t.clientX - r.left) * scale);
                }
            });
            Object.defineProperty(mouseEv, 'offsetY', {
                get: function() {
                    var r = target.getBoundingClientRect();
                    var scale = r.height && target.offsetHeight ? (target.offsetHeight / r.height) : 1;
                    return Math.round((t.clientY - r.top) * scale);
                }
            });
        } catch (e) {}
        window.event = mouseEv;
        target.dispatchEvent(mouseEv);
    };
    document.addEventListener('touchstart', function(e) {
        if (document.getElementById('divCanvas') || document.getElementById('red1')) {
            touchToMouse('mousedown', e, 1);
            if (document.getElementById('divCanvas')) {
                touchToMouse('mousemove', e, 1);
            }
        }
    }, { passive: true });
    document.addEventListener('touchmove', function(e) {
        if (document.getElementById('divCanvas') || document.getElementById('red1')) {
            touchToMouse('mousemove', e, 1);
        }
    }, { passive: true });
    document.addEventListener('touchend', function(e) {
        if (document.getElementById('divCanvas') || document.getElementById('red1')) {
            if (document.getElementById('divCanvas')) {
                touchToMouse('mousemove', e, 0);
            }
            touchToMouse('mouseup', e, 0);
        }
    }, { passive: true });
})();
    """.trimIndent()
}

@Composable
internal fun GameHost(
    contactId: String,
    session: GameSession?,
    localDisplayName: String,
    localEmail: String,
    peerDisplayName: String,
    peerEmail: String,
    onAccept: (suspend () -> Result<Unit>)? = null,
    onStop: (suspend () -> Result<Unit>)? = null,
    onInitializeChannel: (suspend () -> Result<Unit>)? = null,
    onSendGameData: (suspend (String) -> Result<Unit>)? = null,
    onSendImAsUser: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ongoing = session?.status in listOf(GameSessionStatus.Invited, GameSessionStatus.Active)
    var visible by rememberSaveable(contactId, session?.id) {
        mutableStateOf(ongoing)
    }
    var closed by rememberSaveable(contactId, session?.id) {
        mutableStateOf(!ongoing)
    }
    var lastSeenPacketSeq by rememberSaveable(contactId, session?.id) {
        mutableLongStateOf(0L)
    }
    var error by remember(session?.id) { mutableStateOf<String?>(null) }
    var helpUrl by remember(session?.id) { mutableStateOf<String?>(null) }

    // Automatically surface the sheet when an invitation is accepted by the peer.
    var previousStatus by remember(session?.id) { mutableStateOf(session?.status) }
    LaunchedEffect(session?.id, session?.status) {
        if (previousStatus == GameSessionStatus.Invited && session?.status == GameSessionStatus.Active) {
            visible = true
            closed = false
        }
        previousStatus = session?.status
    }

    LaunchedEffect(visible, session?.receivedPackets?.size) {
        if (visible && session != null) {
            lastSeenPacketSeq = session.receivedPackets.lastOrNull()?.sequence ?: lastSeenPacketSeq
        }
    }

    if (session == null || closed) return

    val controller = remember(session.id) {
        GameWebViewController(
            context = context,
            sessionId = session.id,
            entry = session.entry,
            isInviter = session.outgoing,
            localName = localDisplayName,
            localEmail = localEmail,
            peerName = peerDisplayName,
            peerEmail = peerEmail,
            onInitialize = {
                scope.launch {
                    (onInitializeChannel?.invoke()
                        ?: context.gameRepository().initializeGameChannel(contactId, session.id))
                }
            },
            onSendData = { payload ->
                scope.launch {
                    (onSendGameData?.invoke(payload)
                        ?: context.gameRepository().sendGameData(contactId, session.id, payload))
                }
            },
            onSendImAsUser = { text ->
                if (text.isNotBlank()) {
                    if (onSendImAsUser != null) {
                        onSendImAsUser(text)
                    } else {
                        context.gameRepository().sendMessage(contactId, text)
                    }
                }
            },
            onCloseApp = {
                scope.launch {
                    (onStop?.invoke() ?: context.gameRepository().stopGame(contactId, session.id))
                    visible = false
                    closed = true
                }
            },
            onOpenHelpUrl = { url -> helpUrl = url },
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }

    // Keep delivering P4 events to the live WebView even while minimized into the FAB!
    LaunchedEffect(
        controller.pageFinished,
        session.status,
        session.remoteAppLoaded,
        session.remoteAppClosed,
        session.receivedPackets.size,
    ) {
        if (!controller.pageFinished) return@LaunchedEffect
        if (session.remoteAppLoaded) {
            controller.dispatchRemoteAppLoaded()
        }
        val undelivered = session.receivedPackets.filter { it.sequence > controller.lastDeliveredSequence }
        for (packet in undelivered) {
            controller.lastDeliveredSequence = packet.sequence
            controller.dispatchDataReceived(packet.payload)
        }
        if (session.remoteAppClosed) {
            controller.dispatchRemoteAppClosed()
        }
    }

    LaunchedEffect(session.status) {
        if (session.status != GameSessionStatus.Active) {
            controller.dismissInteractiveJsDialogs()
        }
    }

    // Surface the full-screen game dialog when a running game requests user confirmation/alert
    // while minimized into the corner button (for example, a peer requesting a game restart).
    LaunchedEffect(controller.activeJsDialog?.id) {
        if (controller.activeJsDialog != null && ongoing) {
            visible = true
            closed = false
        }
    }

    val unreadMoves = remember(visible, session.receivedPackets, lastSeenPacketSeq) {
        if (visible) 0 else session.receivedPackets.count { it.sequence > lastSeenPacketSeq }
    }
    val hasPendingInvite = !session.outgoing && session.status == GameSessionStatus.Invited

    val minimize = {
        visible = false
        if (!ongoing) closed = true
    }
    val stop: () -> Unit = {
        controller.dismissAllJsDialogs()
        scope.launch {
            error = (onStop?.invoke() ?: context.gameRepository().stopGame(contactId, session.id))
                .exceptionOrNull()?.message
            if (session.status == GameSessionStatus.Invited) {
                visible = false
                closed = true
            }
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(session.appName) } },
        state = rememberTooltipState(),
    ) {
        FloatingActionButton(
            onClick = { visible = true },
            shape = FloatingActionButtonDefaults.shape,
            modifier = Modifier
                .animateFloatingActionButton(
                    visible = !visible,
                    alignment = Alignment.BottomEnd,
                )
                .then(
                    if (visible) {
                        Modifier.clearAndSetSemantics {}
                    } else {
                        Modifier.testTag("reopen_game_session").semantics {
                            stateDescription = when (session.status) {
                                GameSessionStatus.Invited -> "${session.appName} invitation"
                                GameSessionStatus.Active -> "Playing ${session.appName}"
                                else -> "${session.appName} ended"
                            }
                        }
                    },
                ),
        ) {
            BadgedBox(
                badge = {
                    if (hasPendingInvite || controller.activeJsDialog != null) {
                        Badge { Text("!") }
                    } else if (unreadMoves > 0) {
                        Badge { Text(unreadMoves.toString()) }
                    }
                },
            ) {
                Icon(
                    imageVector = if (session.entry.appType == 1) Icons.Default.Brush else Icons.Default.SportsEsports,
                    contentDescription = "Open ${session.appName}",
                )
            }
        }
    }

    if (!visible) return

    val currentMinimize by rememberUpdatedState(minimize)
    val backProgress = remember { Animatable(0f) }
    val statusSubtitle = when (session.status) {
        GameSessionStatus.Invited -> if (session.outgoing) {
            "Waiting for $peerDisplayName to accept\u2026"
        } else {
            "$peerDisplayName invited you"
        }
        GameSessionStatus.Active -> if (session.remoteAppLoaded) {
            "With $peerDisplayName"
        } else {
            "Loading $peerDisplayName's game\u2026"
        }
        GameSessionStatus.Declined -> "Invitation declined"
        GameSessionStatus.Stopped -> if (session.remoteStopped) {
            "$peerDisplayName left the game"
        } else {
            "Game ended"
        }
        GameSessionStatus.Failed -> session.error ?: "Game failed"
    }

    Dialog(
        onDismissRequest = minimize,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PredictiveBackHandler(enabled = controller.activeJsDialog == null && helpUrl == null) { events ->
            try {
                events.collect { backProgress.snapTo(it.progress) }
                currentMinimize()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { backProgress.animateTo(0f) }
                throw cancelled
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = backProgress.value
                    val scale = 1f - 0.1f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.2f * progress
                    shape = RoundedCornerShape((28 * progress).dp)
                    clip = progress > 0f
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize().testTag("game_session_dialog"),
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = minimize,
                                modifier = Modifier.testTag("minimize_game_session"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Minimize",
                                )
                            }
                        },
                        title = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GameEntryIcon(entry = session.entry, modifier = Modifier.size(36.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = session.appName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = statusSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        actions = {
                            if (ongoing && !hasPendingInvite) {
                                TextButton(
                                    onClick = stop,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                    modifier = Modifier.testTag("stop_game_session"),
                                ) {
                                    Text(
                                        if (session.status == GameSessionStatus.Invited) "Cancel" else "End",
                                    )
                                }
                            } else if (!ongoing) {
                                TextButton(
                                    onClick = { visible = false; closed = true },
                                    modifier = Modifier.testTag("close_game_session"),
                                ) {
                                    Text("Close")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 20.dp)
                        .testTag("game_session_sheet"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    when (session.status) {
                        GameSessionStatus.Invited -> {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = if (session.outgoing) {
                                            "Your invitation to play ${session.appName} has been sent. You can minimize this panel into the corner button and keep chatting while you wait."
                                        } else {
                                            "$peerDisplayName wants to start ${session.appName} with you."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!session.outgoing) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                shapes = ButtonDefaults.shapes(),
                                                modifier = Modifier.testTag("accept_game_session"),
                                                onClick = {
                                                    scope.launch {
                                                        error = (onAccept?.invoke()
                                                            ?: context.gameRepository().acceptGame(contactId, session.id))
                                                            .exceptionOrNull()?.message
                                                    }
                                                },
                                            ) {
                                                Text("Accept & play")
                                            }
                                            DestructiveOutlinedButton(
                                                onClick = stop,
                                                modifier = Modifier.testTag("stop_game_session"),
                                            ) {
                                                Text("Decline")
                                            }
                                        }
                                    } else {
                                        MsngrLinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }

                        GameSessionStatus.Active,
                        GameSessionStatus.Stopped,
                        GameSessionStatus.Declined,
                        GameSessionStatus.Failed -> {
                            val paneWidthDp = controller.paneWidth.dp
                            val paneHeightDp = controller.paneHeight.dp
                            BoxWithConstraints(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                val fitScale = minOf(
                                    maxWidth / paneWidthDp,
                                    maxHeight / paneHeightDp,
                                ).coerceAtLeast(0.1f)
                                val scaledWidth = paneWidthDp * fitScale
                                val scaledHeight = paneHeightDp * fitScale
                                val paneShape = RoundedCornerShape(4.dp)
                                Surface(
                                    shape = paneShape,
                                    color = Color.White,
                                    contentColor = Color.Black,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 1.dp,
                                    modifier = Modifier
                                        .size(width = scaledWidth, height = scaledHeight)
                                        .clip(paneShape)
                                        .testTag("game_webview_container"),
                                ) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxSize(),
                                        factory = { ctx ->
                                            val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
                                            NativeGamePaneContainer(ctx).apply {
                                                updatePaneSize(
                                                    widthPx = (controller.paneWidth * density).roundToInt(),
                                                    heightPx = (controller.paneHeight * density).roundToInt(),
                                                )
                                                (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
                                                addView(controller.webView)
                                            }
                                        },
                                        update = { container ->
                                            val density = container.resources.displayMetrics.density.coerceAtLeast(1f)
                                            container.updatePaneSize(
                                                widthPx = (controller.paneWidth * density).roundToInt(),
                                                heightPx = (controller.paneHeight * density).roundToInt(),
                                            )
                                            if (controller.webView.parent !== container) {
                                                (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
                                                container.addView(controller.webView)
                                            }
                                        },
                                    )
                                }
                            }

                            if (session.appId == 103000 && session.status == GameSessionStatus.Active) {
                                WordleTouchKeyboard(
                                    onLetter = { ch -> controller.sendKey("keyup", ch.code, ch.toString()) },
                                    onBackspace = { controller.sendKey("keyup", 8, "Backspace") },
                                    onEnter = { controller.sendKey("keyup", 13, "Enter") },
                                )
                            } else if (session.appId == 102007 && session.status == GameSessionStatus.Active) {
                                TetrisTouchControls(
                                    onKey = { code, name -> controller.sendKey("keydown", code, name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    controller.activeJsDialog?.let { jsDialog ->
        GameJsDialog(
            appName = session.appName,
            dialog = jsDialog,
        )
    }

    helpUrl?.let { targetHelpUrl ->
        AlertDialog(
            onDismissRequest = { helpUrl = null },
            title = { Text("${session.appName} — Help") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.White),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(AndroidColor.WHITE)
                                settings.loadsImagesAutomatically = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean = true
                                }
                                loadUrl(targetHelpUrl)
                            }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { helpUrl = null }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun GameJsDialog(
    appName: String,
    dialog: GameJsDialogRequest,
) {
    var promptText by remember(dialog.id) {
        mutableStateOf((dialog.kind as? GameJsDialogKind.Prompt)?.defaultValue.orEmpty())
    }
    AlertDialog(
        modifier = Modifier.testTag("game_js_dialog"),
        onDismissRequest = {
            when (dialog.kind) {
                GameJsDialogKind.Alert -> dialog.confirm()
                GameJsDialogKind.Confirm,
                is GameJsDialogKind.Prompt -> dialog.cancel()
            }
        },
        title = { Text(appName) },
        text = {
            when (dialog.kind) {
                GameJsDialogKind.Alert,
                GameJsDialogKind.Confirm -> Text(dialog.message)
                is GameJsDialogKind.Prompt -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (dialog.message.isNotBlank()) {
                            Text(dialog.message)
                        }
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("game_js_prompt_input"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (dialog.kind) {
                GameJsDialogKind.Alert -> {
                    TextButton(
                        onClick = { dialog.confirm() },
                        modifier = Modifier.testTag("game_js_dialog_confirm"),
                    ) {
                        Text("OK")
                    }
                }
                GameJsDialogKind.Confirm -> {
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { dialog.confirm() },
                        modifier = Modifier.testTag("game_js_dialog_confirm"),
                    ) {
                        Text("OK")
                    }
                }
                is GameJsDialogKind.Prompt -> {
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { dialog.confirm(promptText) },
                        modifier = Modifier.testTag("game_js_dialog_confirm"),
                    ) {
                        Text("OK")
                    }
                }
            }
        },
        dismissButton = when (dialog.kind) {
            GameJsDialogKind.Alert -> null
            GameJsDialogKind.Confirm,
            is GameJsDialogKind.Prompt -> {
                {
                    TextButton(
                        onClick = { dialog.cancel() },
                        modifier = Modifier.testTag("game_js_dialog_dismiss"),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        },
    )
}

@Composable
private fun WordleTouchKeyboard(
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
) {
    val rows = remember {
        listOf(
            "QWERTYUIOP",
            "ASDFGHJKL",
            "ZXCVBNM",
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.medium,
            )
            .padding(8.dp)
            .testTag("wordle_touch_keyboard"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for ((rowIndex, row) in rows.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                if (rowIndex == 2) {
                    FilledTonalButton(
                        onClick = onEnter,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("ENTER", style = MaterialTheme.typography.labelSmall)
                    }
                }
                for (ch in row) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { onLetter(ch) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = ch.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (rowIndex == 2) {
                    FilledTonalButton(
                        onClick = onBackspace,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("⌫", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TetrisTouchControls(
    onKey: (Int, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.medium,
            )
            .padding(8.dp)
            .testTag("tetris_touch_controls"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { onKey(37, "ArrowLeft") },
            modifier = Modifier.weight(1f),
        ) { Text("◀") }
        FilledTonalButton(
            onClick = { onKey(38, "ArrowUp") },
            modifier = Modifier.weight(1f),
        ) { Text("⟳") }
        FilledTonalButton(
            onClick = { onKey(40, "ArrowDown") },
            modifier = Modifier.weight(1f),
        ) { Text("▼") }
        FilledTonalButton(
            onClick = { onKey(39, "ArrowRight") },
            modifier = Modifier.weight(1f),
        ) { Text("▶") }
        Button(
            onClick = { onKey(32, " ") },
            modifier = Modifier.weight(1.2f),
        ) { Text("Drop") }
    }
}
