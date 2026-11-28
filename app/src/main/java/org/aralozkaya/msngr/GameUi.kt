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
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
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
import androidx.compose.ui.res.stringResource
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
import java.net.URI
import java.net.URL
import java.util.UUID
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private fun Context.gameRepository() =
    (applicationContext as MsngrApplication).container.messengerRepository

private val gameIconCache = object : LruCache<String, Bitmap>(32) {}

private suspend fun fetchGameIconBitmap(url: String): Bitmap? {
    if (gameOrigin(url) == null) return null
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
                val bytes = connection.inputStream.use { it.readBytesBounded(2 * 1024 * 1024) { "Game icon is too large." } }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                options.inJustDecodeBounds = false
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize.coerceAtLeast(1) > 256) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also {
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
    val portalStateFlow = remember(context) {
        context.currentMessengerRepository?.portalState
    }
    val portalState by portalStateFlow?.collectAsState() ?: remember { mutableStateOf(PortalState()) }
    val discoveredActivitiesServer = portalState.config.appDirServiceUrl
    val activitiesServer = remember(context, discoveredActivitiesServer) {
        val configured = runCatching { SessionPrefs.readServerConfig(context) }.getOrNull()
        val rawActivities = configured?.activitiesServer?.takeIf { it.isNotBlank() }
        val rawMain = configured?.mainServer?.takeIf { it.isNotBlank() }
        when {
            !discoveredActivitiesServer.isNullOrBlank() &&
                (rawActivities == null || rawMain == null || rawActivities.equals(rawMain, ignoreCase = true)) ->
                discoveredActivitiesServer
            rawActivities != null -> rawActivities
            else -> MsnpGameCatalogService.currentActivitiesServer.ifBlank {
                MsnpGameCatalogService.DEFAULT_ACTIVITIES_SERVER
            }
        }
    }
    var catalog by remember(activitiesServer) {
        mutableStateOf(
            MsnpGameCatalogService.cachedCatalog(activitiesServer)
                ?: if (activitiesServer.equals(MsnpGameCatalogService.DEFAULT_ACTIVITIES_SERVER, ignoreCase = true)) {
                    MsnpGameCatalogService.defaultCatalog()
                } else {
                    GameCatalog(emptyList(), emptyList())
                },
        )
    }
    var loadingCatalog by remember(activitiesServer) {
        mutableStateOf(catalog.visibleEntries.isEmpty())
    }
    var startingAppId by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val backProgress = remember { Animatable(0f) }

    LaunchedEffect(activitiesServer) {
        loadingCatalog = catalog.visibleEntries.isEmpty()
        try {
            catalog = MsnpGameCatalogService.fetchCatalog(activitiesServer = activitiesServer)
        } finally {
            loadingCatalog = false
        }
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
                        title = { Text(stringResource(R.string.games_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                enabled = startingAppId == null,
                                modifier = Modifier.testTag("game_catalog_close"),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                GameCatalogContent(
                    catalog = catalog,
                    loading = loadingCatalog,
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
    loading: Boolean = false,
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
            text = stringResource(R.string.games_intro_you_singular),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                text = localizeUserMessage(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (loading && catalog.visibleEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                MsngrCircularWavyProgressIndicator()
            }
        }

        val entriesByCategory = remember(catalog) {
            catalog.visibleEntries.groupBy { it.categoryId }
        }
        for (category in catalog.categories) {
            val categoryEntries = entriesByCategory[category.categoryId].orEmpty()
            if (categoryEntries.isEmpty()) continue
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = localizedGameCategoryName(category.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (category.description.isNotBlank()) {
                    Text(
                        text = localizedGameCategoryDescription(category.description),
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
                                        text = localizedGameName(entry.name),
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
                                                entry.appType == 1 -> stringResource(R.string.games_activity_badge)
                                                entry.minUsers == 1 && entry.maxUsers > 1 -> twoNumbersSentenceResource(
                                                    R.string.game_players_range_plural_before_first_number,
                                                    1,
                                                    R.string.game_players_range_plural_between_numbers,
                                                    entry.maxUsers,
                                                    R.string.game_players_range_plural_after_second_number,
                                                )
                                                else -> quantityNumberSentenceResource(
                                                    count = entry.maxUsers,
                                                    oneBeforeRes = R.string.game_players_exact_singular_before_number,
                                                    oneAfterRes = R.string.game_players_exact_singular_after_number,
                                                    otherBeforeRes = R.string.game_players_exact_plural_before_number,
                                                    otherAfterRes = R.string.game_players_exact_plural_after_number,
                                                )
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (entry.description.isNotBlank()) {
                                    Text(
                                        text = localizedGameDescription(entry.description),
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
    val minHeight = when {
        entry.appId == 100000 || entry.name.contains("Whiteboard", ignoreCase = true) -> WHITEBOARD_NATIVE_PANE_HEIGHT
        entry.appId == 103000 || entry.name.contains("Wordle", ignoreCase = true) -> WORDLE_NATIVE_PANE_HEIGHT
        else -> DEFAULT_NATIVE_GAME_PANE_HEIGHT
    }
    val height = entry.height.takeIf { it > 0 }?.coerceAtLeast(minHeight) ?: minHeight
    return width to height
}

private fun gameOrigin(url: String?): String? {
    val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) return null
    val defaultPort = if (scheme == "https") 443 else 80
    val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
    return "$scheme://$host$port"
}

internal fun isAllowedGameHost(entryUrl: String, requestUrl: String): Boolean =
    gameOrigin(entryUrl) != null && gameOrigin(requestUrl) != null

internal fun shouldInjectGameBridgeHtml(
    entryUrl: String,
    requestUrl: String,
    isMainFrame: Boolean,
): Boolean {
    if (!isAllowedGameHost(entryUrl, requestUrl)) return false
    val path = URI(requestUrl).path.orEmpty().lowercase()
    if (path.contains("help")) return false
    if (isMainFrame) return true
    return gameOrigin(entryUrl) == gameOrigin(requestUrl) && (path.endsWith(".htm") || path.endsWith(".html"))
}

internal const val DEFAULT_GAME_PANE_BG_HEX = "#516CA1"
internal const val DEFAULT_GAME_PANE_BG_COLOR_INT = 0xFF516CA1.toInt()
internal val DefaultGamePaneBackgroundColor = Color(0xFF516CA1)

private const val P4_BASE_CSS = """
html {
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
        setBackgroundColor(DEFAULT_GAME_PANE_BG_COLOR_INT)
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

    private val channelId = UUID.randomUUID().toString()
    private var messagePort: WebMessagePort? = null
    private var navigation = 0L
    @Volatile private var topPageUrl = entry.url
    private val gameScript = buildP4PolyfillScript(paneWidth)
    private val hostScript = """
        if (window === window.top && !window.__p4Host) {
            (function () {
                var port = null, pending = [];
                var host = ${JSONObject().apply {
                    put("localName", localName.ifBlank { localEmail.substringBefore('@') })
                    put("localEmail", localEmail)
                    put("peerName", peerName.ifBlank { peerEmail.substringBefore('@') })
                    put("peerEmail", peerEmail)
                    put("isInviter", isInviter)
                }.toString().replace("<", "\\u003c")};
                host.send = function(action, value) {
                    var data = JSON.stringify([action, value || '']);
                    if (port) port.postMessage(data);
                    else if (pending.length < 128) pending.push(data);
                };
                window.addEventListener('message', function(event) {
                    if (event.data !== '$channelId' || event.ports.length !== 1) return;
                    port = event.ports[0];
                    port.onmessage = function(message) {
                        var data = JSON.parse(message.data);
                        if (data[0] === 'data') window.__p4OnDataReceived(data[1]);
                        if (data[0] === 'loaded') window.__p4OnRemoteAppLoaded();
                        if (data[0] === 'closed') window.__p4OnRemoteAppClosed();
                    };
                    pending.forEach(function(data) { port.postMessage(data); });
                    pending = [];
                });
                window.__p4Host = host;
            })();
        }
    """.trimIndent()
    private val mainFrameScript = "$hostScript\n$gameScript"

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    val webView: WebView = WebView(context.applicationContext).apply {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val initialWidthPx = (paneWidth * density).roundToInt()
        val initialHeightPx = (paneHeight * density).roundToInt()
        layoutParams = FrameLayout.LayoutParams(initialWidthPx, initialHeightPx)
        setBackgroundColor(DEFAULT_GAME_PANE_BG_COLOR_INT)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
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
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val rawTarget = request.url.toString()
                if (!isAllowedGameHost(entry.url, rawTarget)) return true
                val target = MsnpGameCatalogService.upgradeKnownHttpsUrl(rawTarget)
                if (request.isForMainFrame && target.contains("help", ignoreCase = true) &&
                    (target.endsWith(".htm", ignoreCase = true) || target.endsWith(".html", ignoreCase = true))
                ) {
                    mainHandler.post { onOpenHelpUrl(target) }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                navigation++
                pageFinished = false
                topPageUrl = url.orEmpty()
                messagePort?.close()
                messagePort = null
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val rawUrl = request.url.toString()
                val upgraded = MsnpGameCatalogService.upgradeKnownHttpsUrl(rawUrl)
                val mainFrame = request.isForMainFrame
                if (mainFrame) topPageUrl = rawUrl
                if (request.method == "GET" && shouldInjectGameBridgeHtml(topPageUrl, rawUrl, mainFrame)) {
                    return fetchGameResource(upgraded, if (mainFrame) mainFrameScript else gameScript)
                }
                if (request.method == "GET" && rawUrl != upgraded && gameOrigin(upgraded) != null) {
                    return fetchGameResource(upgraded, script = null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                val origin = gameOrigin(url) ?: return
                if (url != view.url || !shouldInjectGameBridgeHtml(entry.url, url, true)) return
                val generation = navigation
                val script = "if (window.location.origin === ${JSONObject.quote(origin)}) { $mainFrameScript }"
                view.evaluateJavascript(script) {
                    if (generation != navigation || url != view.url || messagePort != null) return@evaluateJavascript
                    val ports = view.createWebMessageChannel()
                    messagePort = ports[0]
                    ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(port: WebMessagePort, message: WebMessage) {
                            if (port === messagePort) handleGameAction(message.data)
                        }
                    })
                    view.postWebMessage(WebMessage(channelId, arrayOf(ports[1])), Uri.parse(origin))
                    pageFinished = true
                }
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
        if (gameOrigin(entry.url) != null) loadUrl(MsnpGameCatalogService.upgradeKnownHttpsUrl(entry.url))
    }

    fun dispatchRemoteAppLoaded() {
        if (remoteLoadedDispatched) return
        remoteLoadedDispatched = true
        postGameEvent("loaded")
    }

    fun dispatchDataReceived(payload: String) = postGameEvent("data", payload)

    fun dispatchRemoteAppClosed() {
        if (remoteClosedDispatched) return
        remoteClosedDispatched = true
        postGameEvent("closed")
    }

    private fun postGameEvent(action: String, value: String = "") {
        val data = org.json.JSONArray().put(action).put(value)
        messagePort?.postMessage(WebMessage(data.toString()))
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
        navigation++
        messagePort?.close()
        messagePort = null
        dismissAllJsDialogs()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun handleGameAction(data: String?) {
        if (data == null || data.length > 256 * 1024) return
        val request = runCatching { org.json.JSONArray(data) }.getOrNull() ?: return
        val value = request.opt(1) as? String ?: return
        when (request.opt(0)) {
            "initialize" -> onInitialize()
            "data" -> onSendData(value)
            "im" -> onSendImAsUser(value)
            "close" -> onCloseApp()
            "help" -> if (gameOrigin(value) != null) onOpenHelpUrl(MsnpGameCatalogService.upgradeKnownHttpsUrl(value))
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

private val headerCharsetRegex = Regex("""charset\s*=\s*["']?([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)

private fun resolveHtmlCharset(contentType: String?, bytes: ByteArray): Charset {
    val headerCharset = contentType?.let { headerCharsetRegex.find(it)?.groupValues?.getOrNull(1) }
    val metaCharset = headerCharset ?: String(
        bytes,
        0,
        bytes.size.coerceAtMost(2048),
        StandardCharsets.ISO_8859_1,
    ).let { head -> headerCharsetRegex.find(head)?.groupValues?.getOrNull(1) }
    return metaCharset?.let { name ->
        runCatching { Charset.forName(name) }.getOrNull()
    } ?: StandardCharsets.UTF_8
}

private val gameHeadTag = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)

private fun fetchGameResource(url: String, script: String?): WebResourceResponse? {
    val target = URL(url)
    val connection = (target.openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
    }
    try {
        val status = connection.responseCode
        if (status in 300..399) {
            val location = connection.getHeaderField("Location")
            val redirect = location?.let { URL(target, it).toString() }
            if (gameOrigin(redirect) == null) return unavailableGameResource()
            // Let WebView follow redirects so the final document gets its actual security origin.
            return null
        }
        if (status !in 200..299) return unavailableGameResource()
        val limit = if (script != null) 2 * 1024 * 1024 else 16 * 1024 * 1024
        val bytes = connection.inputStream.use { it.readBytesBounded(limit) { "Game resource is too large." } }
        val contentType = connection.contentType
        if (script == null) {
            val mime = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                ?: java.net.URLConnection.guessContentTypeFromName(target.path)
                ?: "application/octet-stream"
            return WebResourceResponse(mime, "utf-8", bytes.inputStream())
        }
        val html = normalizeLegacyGameHtml(String(bytes, resolveHtmlCharset(contentType, bytes)))
        val injection = "<style id=\"__p4_base_style\">$P4_BASE_CSS</style><script>$script</script>"
        val offset = gameHeadTag.find(html)?.range?.last?.plus(1) ?: 0
        val modified = StringBuilder(html).insert(offset, injection).toString()
        return WebResourceResponse("text/html", "utf-8", modified.byteInputStream())
    } catch (_: Exception) {
        return unavailableGameResource()
    } finally {
        connection.disconnect()
    }
}

private fun unavailableGameResource() = WebResourceResponse(
    "text/plain", "utf-8", 502, "Game resource unavailable", emptyMap(), ByteArrayInputStream(byteArrayOf()),
)

private fun buildP4PolyfillScript(targetWidth: Int): String {
    val baseCssLiteral = JSONObject.quote(P4_BASE_CSS)

    return """
(function() {
    var host;
    try { host = window.top.__p4Host; } catch (e) { return; }
    if (!host) return;
    if (window.__p4SyncDefaultBg) {
        try { window.__p4SyncDefaultBg(); } catch (e) {}
    }
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
    var syncDefaultBackground = function() {
        try {
            var existing = document.getElementById('__p4_default_bg');
            if (existing) existing.disabled = true;
            var hasCustomBg = function(el) {
                if (!el) return false;
                if (el.getAttribute && el.getAttribute('bgcolor')) return true;
                var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
                if (!cs) return false;
                var bgImg = cs.backgroundImage;
                if (bgImg && bgImg !== 'none') return true;
                var bgColor = cs.backgroundColor;
                return !!bgColor && bgColor !== 'transparent' && bgColor !== 'rgba(0, 0, 0, 0)';
            };
            var custom = hasCustomBg(document.documentElement) || hasCustomBg(document.body);
            if (custom) {
                if (existing && existing.parentNode) {
                    existing.parentNode.removeChild(existing);
                }
            } else if (existing) {
                existing.disabled = false;
            } else {
                var root = document.head || document.documentElement;
                if (!root) return;
                var bgStyle = document.createElement('style');
                bgStyle.id = '__p4_default_bg';
                bgStyle.textContent = 'html { background-color: $DEFAULT_GAME_PANE_BG_HEX; }';
                root.appendChild(bgStyle);
            }
        } catch (e) {}
    };
    window.__p4SyncDefaultBg = syncDefaultBackground;
    ensureBaseStyle();
    syncDefaultBackground();
    window.addEventListener('DOMContentLoaded', function() {
        ensureBaseStyle();
        syncDefaultBackground();
    });
    window.addEventListener('load', syncDefaultBackground);

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
        if (url) {
            try {
                host.send('help', new URL(url, window.location.href).href);
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
    var meUser = makeUser(host.localName, host.localEmail);
    var peerUser = makeUser(host.peerName, host.peerEmail);
    var isInviter = host.isInviter;
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
            host.send('initialize');
            fireRemoteLoadedIfReady();
        },
        initialize: function() { this.Initialize(); },
        SendData: function(v) {
            var payload = (v === null || v === undefined) ? '' : String(v);
            host.send('data', payload);
        },
        sendData: function(v) { this.SendData(v); },
        SendIM: function(msg) {
            host.send('im', String(msg || ''));
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
            host.send('close');
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
    var hasActivated by remember(session?.id) {
        mutableStateOf(session?.status == GameSessionStatus.Active)
    }
    LaunchedEffect(session?.id, session?.status) {
        if (session?.status == GameSessionStatus.Active) {
            hasActivated = true
        }
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

    val controller = remember(session.id, hasActivated) {
        if (hasActivated) {
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
        } else {
            null
        }
    }

    DisposableEffect(controller) {
        onDispose { controller?.destroy() }
    }

    // Keep delivering P4 events to the live WebView even while minimized into the FAB!
    LaunchedEffect(
        controller,
        controller?.pageFinished,
        session.status,
        session.remoteAppLoaded,
        session.remoteAppClosed,
        session.receivedPackets.size,
    ) {
        val activeController = controller ?: return@LaunchedEffect
        if (!activeController.pageFinished) return@LaunchedEffect
        if (session.remoteAppLoaded) {
            activeController.dispatchRemoteAppLoaded()
        }
        val undelivered = session.receivedPackets.filter { it.sequence > activeController.lastDeliveredSequence }
        for (packet in undelivered) {
            activeController.lastDeliveredSequence = packet.sequence
            activeController.dispatchDataReceived(packet.payload)
        }
        if (session.remoteAppClosed) {
            activeController.dispatchRemoteAppClosed()
        }
    }

    LaunchedEffect(controller, session.status) {
        if (session.status != GameSessionStatus.Active) {
            controller?.dismissInteractiveJsDialogs()
        }
    }

    // Surface the full-screen game dialog when a running game requests user confirmation/alert
    // while minimized into the corner button (for example, a peer requesting a game restart).
    LaunchedEffect(controller?.activeJsDialog?.id) {
        if (controller?.activeJsDialog != null && ongoing) {
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
        controller?.dismissAllJsDialogs()
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
                        Modifier.let { baseModifier ->
                            val gameDisplayName = localizedGameName(session.appName)
                            val reopenState = when (session.status) {
                                GameSessionStatus.Invited -> stringResource(R.string.game_state_invitation, gameDisplayName)
                                GameSessionStatus.Active -> stringResource(R.string.game_state_playing, gameDisplayName)
                                else -> stringResource(R.string.game_state_ended, gameDisplayName)
                            }
                            baseModifier.testTag("reopen_game_session").semantics {
                                stateDescription = reopenState
                            }
                        }
                    },
                ),
        ) {
            BadgedBox(
                badge = {
                    if (hasPendingInvite || controller?.activeJsDialog != null) {
                        Badge { Text(stringResource(R.string.game_attention_badge)) }
                    } else if (unreadMoves > 0) {
                        Badge { Text(unreadMoves.toString()) }
                    }
                },
            ) {
                Icon(
                    imageVector = if (session.entry.appType == 1) Icons.Default.Brush else Icons.Default.SportsEsports,
                    contentDescription = stringResource(R.string.game_open_desc, localizedGameName(session.appName)),
                )
            }
        }
    }

    if (!visible) return

    val currentMinimize by rememberUpdatedState(minimize)
    val backProgress = remember { Animatable(0f) }
    val statusSubtitle = when (session.status) {
        GameSessionStatus.Invited -> if (session.outgoing) {
            stringResource(R.string.game_waiting_accept, peerDisplayName)
        } else {
            stringResource(R.string.game_invited_you_singular, peerDisplayName)
        }
        GameSessionStatus.Active -> if (session.remoteAppLoaded) {
            stringResource(R.string.game_with_peer, peerDisplayName)
        } else {
            stringResource(R.string.game_loading_peer, peerDisplayName)
        }
        GameSessionStatus.Declined -> stringResource(R.string.game_invitation_declined)
        GameSessionStatus.Stopped -> if (session.remoteStopped) {
            stringResource(R.string.game_peer_left, peerDisplayName)
        } else {
            stringResource(R.string.game_ended)
        }
        GameSessionStatus.Failed -> session.error?.let { localizeUserMessage(it) } ?: stringResource(R.string.game_failed)
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
        PredictiveBackHandler(enabled = controller?.activeJsDialog == null && helpUrl == null) { events ->
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
                                    contentDescription = stringResource(R.string.game_minimize),
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
                                        text = localizedGameName(session.appName),
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
                                        stringResource(if (session.status == GameSessionStatus.Invited) R.string.common_cancel else R.string.game_end_action),
                                    )
                                }
                            } else if (!ongoing) {
                                TextButton(
                                    onClick = { visible = false; closed = true },
                                    modifier = Modifier.testTag("close_game_session"),
                                ) {
                                    Text(stringResource(R.string.common_close))
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
                                    val localizedAppName = localizedGameName(session.appName)
                                    Text(
                                        text = if (session.outgoing) {
                                            stringResource(R.string.game_invite_outgoing_body_you_singular, localizedAppName)
                                        } else {
                                            stringResource(R.string.game_invite_incoming_body_you_singular, peerDisplayName, localizedAppName)
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
                                                Text(stringResource(R.string.game_accept_and_play))
                                            }
                                            DestructiveOutlinedButton(
                                                onClick = stop,
                                                modifier = Modifier.testTag("stop_game_session"),
                                            ) {
                                                Text(stringResource(R.string.common_decline))
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
                            if (controller != null) {
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
                                        color = DefaultGamePaneBackgroundColor,
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

                                if ((session.appId == 103000 || session.entry.name.contains("Wordle", ignoreCase = true)) &&
                                    session.status == GameSessionStatus.Active
                                ) {
                                    WordleTouchKeyboard(
                                        onLetter = { ch -> controller.sendKey("keyup", ch.code, ch.toString()) },
                                        onBackspace = { controller.sendKey("keyup", 8, "Backspace") },
                                        onEnter = { controller.sendKey("keyup", 13, "Enter") },
                                    )
                                } else if ((session.appId == 102007 || session.appId == 20601359 ||
                                        session.entry.name.contains("Tetris", ignoreCase = true)) &&
                                    session.status == GameSessionStatus.Active
                                ) {
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
    }

    controller?.activeJsDialog?.let { jsDialog ->
        GameJsDialog(
            appName = session.appName,
            dialog = jsDialog,
        )
    }

    helpUrl?.let { targetHelpUrl ->
        AlertDialog(
            onDismissRequest = { helpUrl = null },
            title = { Text(stringResource(R.string.game_help_title, localizedGameName(session.appName))) },
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
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
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
                    Text(stringResource(R.string.common_close))
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
        title = { Text(localizedGameName(appName)) },
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
                        Text(stringResource(R.string.common_ok))
                    }
                }
                GameJsDialogKind.Confirm -> {
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { dialog.confirm() },
                        modifier = Modifier.testTag("game_js_dialog_confirm"),
                    ) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
                is GameJsDialogKind.Prompt -> {
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { dialog.confirm(promptText) },
                        modifier = Modifier.testTag("game_js_dialog_confirm"),
                    ) {
                        Text(stringResource(R.string.common_ok))
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
                        Text(stringResource(R.string.common_cancel))
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
                        Text(stringResource(R.string.game_key_enter), style = MaterialTheme.typography.labelSmall)
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
                        Text(stringResource(R.string.game_key_backspace_symbol), style = MaterialTheme.typography.labelMedium)
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
        ) { Text(stringResource(R.string.game_control_left)) }
        FilledTonalButton(
            onClick = { onKey(38, "ArrowUp") },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.game_control_rotate)) }
        FilledTonalButton(
            onClick = { onKey(40, "ArrowDown") },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.game_control_down)) }
        FilledTonalButton(
            onClick = { onKey(39, "ArrowRight") },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.game_control_right)) }
        Button(
            onClick = { onKey(32, " ") },
            modifier = Modifier.weight(1.2f),
        ) { Text(stringResource(R.string.game_control_drop)) }
    }
}
