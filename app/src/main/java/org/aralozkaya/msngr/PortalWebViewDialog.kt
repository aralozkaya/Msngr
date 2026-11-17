@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Message
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.MsnpClientConfigService
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.protocol.GifDisplayPicture
import java.net.URL
import kotlin.math.abs

data class PortalWebTarget(
    val title: String,
    val url: String,
)

private val remoteImageCache = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
}

@Composable
internal fun rememberRemoteImagePainter(url: String?): Painter? = key(url) {
    val normalized = remember(url) { url?.let(MsnpClientConfigService::normalizeWebUrl) }
    val painter by produceState<Painter?>(initialValue = null, key1 = normalized) {
        if (normalized == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val bytes = remoteImageCache.get(normalized)
                ?: runCatching { MsnpClientConfigService.fetchImageBytes(normalized) }.getOrNull()
                    ?.takeIf(ByteArray::isNotEmpty)
                    ?.also { remoteImageCache.put(normalized, it) }
                ?: return@withContext null
            val gifInfo = GifDisplayPicture.parse(bytes)
            if (gifInfo != null && gifInfo.frameDelaysMillis.size > 1) {
                runCatching { AnimatedAvatarPainter.decode(bytes, gifInfo) }.getOrNull()
            } else {
                null
            } ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapPainter(it.asImageBitmap()) }
        }
    }
    val animation = painter as? AnimatedAvatarPainter
    DisposableEffect(animation) {
        animation?.attach()
        onDispose { animation?.dispose() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animation, lifecycle) {
        val animated = animation ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            animated.play()
        }
    }
    painter
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalWebViewDialog(
    target: PortalWebTarget,
    onDismiss: () -> Unit,
    onOpenInBrowser: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val initialUrl = remember(target.url) {
        MsnpClientConfigService.normalizeWebUrl(target.url) ?: target.url
    }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var currentTitle by remember(target.title) { mutableStateOf(target.title) }
    var canGoBack by remember(initialUrl) { mutableStateOf(false) }
    var isLoading by remember(initialUrl) { mutableStateOf(true) }
    var isPullRefreshing by remember(initialUrl) { mutableStateOf(false) }
    var progress by remember(initialUrl) { mutableIntStateOf(0) }
    var loadError by remember(initialUrl) { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val pullRefreshState = rememberPullToRefreshState()
    val nestedScrollDispatcher = remember { NestedScrollDispatcher() }
    val noopNestedConnection = remember { object : NestedScrollConnection {} }
    val scope = rememberCoroutineScope()
    val externalUrl = remember(currentUrl, initialUrl) {
        MsnpClientConfigService.normalizeWebUrl(currentUrl)
            ?: MsnpClientConfigService.normalizeWebUrl(initialUrl)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f
        SideEffect {
            window?.let { dialogWindow ->
                WindowCompat.getInsetsController(dialogWindow, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }

        BackHandler {
            val active = webView
            if (active != null && active.canGoBack()) {
                active.goBack()
            } else {
                onDismiss()
            }
        }

        val displayHost = remember(currentUrl) {
            runCatching { URL(currentUrl).host }.getOrDefault(currentUrl)
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("portal_webview_dialog"),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(
                                onClick = { webView?.goBack() },
                                modifier = Modifier.testTag("portal_webview_back"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.portal_webview_back),
                                )
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = currentTitle.ifBlank { target.title },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = displayHost,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = externalUrl != null,
                            onClick = {
                                val safeUrl = externalUrl ?: return@IconButton
                                if (onOpenInBrowser != null) {
                                    onOpenInBrowser(safeUrl)
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, safeUrl.toUri())
                                                .addCategory(Intent.CATEGORY_BROWSABLE),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.testTag("portal_webview_open_browser"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.portal_webview_open_in_browser),
                            )
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("portal_webview_close"),
                        ) {
                            Text(stringResource(R.string.portal_webview_close))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (isLoading && !isPullRefreshing) {
                    if (progress in 1..99) {
                        MsngrLinearWavyProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MsngrLinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                loadError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                PullToRefreshBox(
                    isRefreshing = isPullRefreshing,
                    onRefresh = {
                        val active = webView ?: return@PullToRefreshBox
                        isPullRefreshing = true
                        loadError = null
                        active.reload()
                    },
                    state = pullRefreshState,
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullRefreshState,
                            isRefreshing = isPullRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("portal_webview_pull_refresh"),
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(noopNestedConnection, dispatcher = nestedScrollDispatcher)
                            .testTag("portal_webview"),
                        factory = { context ->
                            PullToRefreshWebView(
                                context = context,
                                nestedScrollDispatcher = nestedScrollDispatcher,
                                onPullReleased = {
                                    scope.launch {
                                        val consumed = nestedScrollDispatcher.dispatchPreFling(Velocity.Zero)
                                        nestedScrollDispatcher.dispatchPostFling(consumed, Velocity.Zero - consumed)
                                    }
                                },
                            ).apply {
                                webView = this
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setBackgroundColor(AndroidColor.WHITE)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    loadsImagesAutomatically = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    setSupportMultipleWindows(true)
                                    userAgentString =
                                        "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.0; MSNMSGR 14.0.8117.0416)"
                                }
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun openWithAuth(url: String) {
                                            val safe = MsnpClientConfigService.normalizeWebUrl(url) ?: return
                                            post { loadUrl(safe) }
                                        }
                                    },
                                    "AndroidMsgrHost",
                                )
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                        isLoading = newProgress < 100
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        currentTitle = resolveDisplayTitle(title, currentUrl, target.title)
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?,
                                    ): Boolean {
                                        val parent = view ?: return false
                                        val hit = parent.hitTestResult.extra
                                        val safeHit = hit?.let(MsnpClientConfigService::normalizeWebUrl)
                                        if (safeHit != null) {
                                            parent.loadUrl(safeHit)
                                            return false
                                        }
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                        val temp = WebView(parent.context).apply {
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    v: WebView,
                                                    request: WebResourceRequest,
                                                ): Boolean {
                                                    val safe = MsnpClientConfigService.normalizeWebUrl(request.url.toString())
                                                    if (safe != null) parent.loadUrl(safe)
                                                    v.destroy()
                                                    return true
                                                }
                                            }
                                        }
                                        transport.webView = temp
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val raw = request.url.toString()
                                        val safe = MsnpClientConfigService.normalizeWebUrl(raw) ?: return true
                                        if (safe != raw) {
                                            view.loadUrl(safe)
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        if (!url.isNullOrBlank()) {
                                            if (url != currentUrl) {
                                                currentTitle = target.title
                                            }
                                            currentUrl = url
                                        }
                                        canGoBack = view.canGoBack()
                                        isLoading = true
                                        loadError = null
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView,
                                        url: String?,
                                        isReload: Boolean,
                                    ) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: currentUrl
                                        currentUrl = resolvedUrl
                                        currentTitle = resolveDisplayTitle(view.title, resolvedUrl, target.title)
                                        canGoBack = view.canGoBack()
                                    }

                                    override fun onPageCommitVisible(view: WebView, url: String?) {
                                        super.onPageCommitVisible(view, url)
                                        val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: currentUrl
                                        currentTitle = resolveDisplayTitle(view.title, resolvedUrl, target.title)
                                        canGoBack = view.canGoBack()
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) {
                                            val msg = error.description?.toString()?.ifBlank { null }
                                                ?: "Could not load page."
                                            if (BuildConfig.DEBUG) {
                                                Log.w("PortalWebView", "Main-frame error (${error.errorCode}): $msg for ${request.url}")
                                            }
                                            loadError = msg
                                            isLoading = false
                                            isPullRefreshing = false
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        errorResponse: WebResourceResponse,
                                    ) {
                                        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                            if (BuildConfig.DEBUG) {
                                                Log.w("PortalWebView", "HTTP ${errorResponse.statusCode} for ${request.url}")
                                            }
                                            loadError = "HTTP ${errorResponse.statusCode}"
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        super.onPageFinished(view, url)
                                        val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: currentUrl
                                        currentUrl = resolvedUrl
                                        currentTitle = resolveDisplayTitle(view.title, resolvedUrl, target.title)
                                        canGoBack = view.canGoBack()
                                        isLoading = false
                                        isPullRefreshing = false
                                        view.evaluateJavascript(MSGR_HOST_POLYFILL_JS, null)
                                    }
                                }
                                loadUrl(initialUrl)
                            }
                        },
                        onRelease = { view ->
                            view.stopLoading()
                            view.destroy()
                        },
                    )
                }
            }
        }
    }
}

private fun resolveDisplayTitle(rawTitle: String?, url: String?, fallbackTitle: String): String {
    val clean = rawTitle?.trim().orEmpty()
    val candidateUrl = url.orEmpty()
    val withoutScheme = candidateUrl
        .removePrefix("https://")
        .removePrefix("http://")
    return if (
        clean.isNotEmpty() &&
        !clean.equals(candidateUrl, ignoreCase = true) &&
        !clean.equals(withoutScheme, ignoreCase = true)
    ) {
        clean
    } else {
        fallbackTitle
    }
}

/**
 * WebView that bridges top-of-page downward drags to Compose's [NestedScrollDispatcher]
 * so Material 3 [PullToRefreshBox] works smoothly without an external SwipeRefreshLayout dependency.
 */
@SuppressLint("ViewConstructor")
private class PullToRefreshWebView(
    context: Context,
    private val nestedScrollDispatcher: NestedScrollDispatcher,
    private val onPullReleased: () -> Unit,
) : WebView(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var isPulling = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                isPulling = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.y - lastY
                lastY = event.y
                if (!isPulling) {
                    val totalDx = event.x - downX
                    val totalDy = event.y - downY
                    if (scrollY == 0 && !canScrollVertically(-1) && totalDy > touchSlop && abs(totalDy) > abs(totalDx)) {
                        isPulling = true
                        val cancel = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        nestedScrollDispatcher.dispatchPostScroll(
                            consumed = Offset.Zero,
                            available = Offset(0f, deltaY),
                            source = NestedScrollSource.UserInput,
                        )
                        return true
                    }
                } else {
                    if (deltaY < 0f) {
                        nestedScrollDispatcher.dispatchPreScroll(
                            available = Offset(0f, deltaY),
                            source = NestedScrollSource.UserInput,
                        )
                    } else if (deltaY > 0f) {
                        nestedScrollDispatcher.dispatchPostScroll(
                            consumed = Offset.Zero,
                            available = Offset(0f, deltaY),
                            source = NestedScrollSource.UserInput,
                        )
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isPulling) {
                    isPulling = false
                    onPullReleased()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}

private const val MSGR_HOST_POLYFILL_JS = """
(function() {
  if (!window.external) { window.external = {}; }
  if (!window.external.MsgrHost) {
    window.external.MsgrHost = {
      OpenWithAuth: function(url, siteId) {
        if (url && window.AndroidMsgrHost) { window.AndroidMsgrHost.openWithAuth(String(url)); }
      },
      ShowMessengerHostWnd: function() {}
    };
  }
})();
"""
