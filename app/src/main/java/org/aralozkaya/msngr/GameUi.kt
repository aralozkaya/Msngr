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
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.pages.components.DestructiveOutlinedButton
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

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

@Composable
internal fun GameCatalogSheet(
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

    LaunchedEffect(activitiesServer) {
        catalog = MsnpGameCatalogService.fetchCatalog(activitiesServer = activitiesServer)
    }

    ModalBottomSheet(
        onDismissRequest = { if (startingAppId == null) onDismiss() },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
                .testTag("game_catalog_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Games & activities",
                style = MaterialTheme.typography.headlineSmall,
            )
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
                                .clickable(enabled = startingAppId == null) {
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
                                }
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
                                    CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class GameWebViewController(
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
    var pageFinished by mutableStateOf(false)
        private set
    var lastDeliveredSequence: Long = 0L
    var remoteLoadedDispatched: Boolean = false
    var remoteClosedDispatched: Boolean = false

    private val bridgeScript: String = buildP4PolyfillScript(
        isInviter = isInviter,
        localName = localName,
        localEmail = localEmail,
        peerName = peerName,
        peerEmail = peerEmail,
        targetWidth = entry.width.coerceAtLeast(320),
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    val webView: WebView = WebView(context.applicationContext).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(AndroidColor.TRANSPARENT)
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
                document.dispatchEvent(ev);
                if (document.body) document.body.dispatchEvent(ev);
            })();
            """.trimIndent(),
            null,
        )
    }

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private inner class BridgeInterface {
        @JavascriptInterface
        fun onInitialize() {
            mainHandler.post { onInitialize() }
        }

        @JavascriptInterface
        fun onSendData(payload: String) {
            mainHandler.post { onSendData(payload) }
        }

        @JavascriptInterface
        fun onSendImAsUser(message: String) {
            mainHandler.post { onSendImAsUser(message) }
        }

        @JavascriptInterface
        fun onCloseApp() {
            mainHandler.post { onCloseApp() }
        }

        @JavascriptInterface
        fun onOpenHelp(url: String) {
            mainHandler.post {
                onOpenHelpUrl(MsnpGameCatalogService.upgradeKnownHttpsUrl(url))
            }
        }
    }
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
        val html = String(bytes, StandardCharsets.UTF_8)
        val injection = "<script>$script</script>"
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

    return """
(function() {
    if (window.__p4PolyfillInstalled) return;
    window.__p4PolyfillInstalled = true;

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
                p.setAttribute('stroke-width', String(sw * 2));
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

    var applyResponsiveScale = function() {
        if (!document.body) return;
        var vw = window.innerWidth || document.documentElement.clientWidth || $targetWidth;
        var targetW = $targetWidth;
        if (vw > 0 && targetW > 0 && vw < targetW) {
            var scale = vw / targetW;
            document.documentElement.style.overflowX = 'hidden';
            document.body.style.transformOrigin = 'top left';
            document.body.style.transform = 'scale(' + scale.toFixed(4) + ')';
            document.body.style.width = targetW + 'px';
        } else {
            document.body.style.transform = '';
            document.body.style.width = '';
        }
    };
    window.addEventListener('DOMContentLoaded', applyResponsiveScale);
    window.addEventListener('load', applyResponsiveScale);
    window.addEventListener('resize', applyResponsiveScale);

    var touchToMouse = function(type, touchEvent) {
        var t = (touchEvent.touches && touchEvent.touches[0]) ||
                (touchEvent.changedTouches && touchEvent.changedTouches[0]);
        if (!t) return;
        var target = document.elementFromPoint(t.clientX, t.clientY) || touchEvent.target;
        if (!target) return;
        var mouseEv = new MouseEvent(type, {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: t.clientX,
            clientY: t.clientY,
            screenX: t.screenX,
            screenY: t.screenY,
            button: 0,
            buttons: type === 'mouseup' ? 0 : 1
        });
        try {
            Object.defineProperty(mouseEv, 'button', { get: function() { return type === 'mouseup' ? 0 : 1; } });
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
            touchToMouse('mousedown', e);
        }
    }, { passive: true });
    document.addEventListener('touchmove', function(e) {
        if (document.getElementById('divCanvas') || document.getElementById('red1')) {
            touchToMouse('mousemove', e);
        }
    }, { passive: true });
    document.addEventListener('touchend', function(e) {
        if (document.getElementById('divCanvas') || document.getElementById('red1')) {
            touchToMouse('mouseup', e);
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

    val unreadMoves = remember(visible, session.receivedPackets, lastSeenPacketSeq) {
        if (visible) 0 else session.receivedPackets.count { it.sequence > lastSeenPacketSeq }
    }
    val hasPendingInvite = !session.outgoing && session.status == GameSessionStatus.Invited

    val minimize = {
        visible = false
        if (!ongoing) closed = true
    }
    val stop: () -> Unit = {
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
                    if (hasPendingInvite) {
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

    ModalBottomSheet(
        onDismissRequest = minimize,
        sheetGesturesEnabled = session.status == GameSessionStatus.Invited,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .testTag("game_session_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameEntryIcon(entry = session.entry, modifier = Modifier.size(36.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.appName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (session.status) {
                            GameSessionStatus.Invited -> if (session.outgoing) {
                                "Waiting for $peerDisplayName to accept…"
                            } else {
                                "$peerDisplayName invited you"
                            }
                            GameSessionStatus.Active -> if (session.remoteAppLoaded) {
                                "With $peerDisplayName"
                            } else {
                                "Loading $peerDisplayName's game…"
                            }
                            GameSessionStatus.Declined -> "Invitation declined"
                            GameSessionStatus.Stopped -> if (session.remoteStopped) {
                                "$peerDisplayName left the game"
                            } else {
                                "Game ended"
                            }
                            GameSessionStatus.Failed -> session.error ?: "Game failed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = minimize,
                    modifier = Modifier.testTag("minimize_game_session"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Minimize")
                }
                if (ongoing) {
                    DestructiveOutlinedButton(
                        onClick = stop,
                        modifier = Modifier.testTag("stop_game_session"),
                    ) {
                        Text(
                            when {
                                session.status == GameSessionStatus.Invited && session.outgoing -> "Cancel"
                                session.status == GameSessionStatus.Invited -> "Decline"
                                else -> "End"
                            },
                        )
                    }
                } else {
                    OutlinedButton(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { visible = false; closed = true },
                    ) {
                        Text("Close")
                    }
                }
            }

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
                                    DestructiveOutlinedButton(onClick = stop) {
                                        Text("Decline")
                                    }
                                }
                            } else {
                                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                GameSessionStatus.Active,
                GameSessionStatus.Stopped,
                GameSessionStatus.Declined,
                GameSessionStatus.Failed -> {
                    val webViewHeight = remember(session.entry.height) {
                        session.entry.height.coerceIn(360, 520).dp
                    }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(webViewHeight)
                            .clip(MaterialTheme.shapes.medium)
                            .testTag("game_webview_container"),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
                                    addView(controller.webView)
                                }
                            },
                            update = { container ->
                                if (controller.webView.parent !== container) {
                                    (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
                                    container.addView(controller.webView)
                                }
                            },
                        )
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

    helpUrl?.let { targetHelpUrl ->
        AlertDialog(
            onDismissRequest = { helpUrl = null },
            title = { Text("${session.appName} — Help") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(MaterialTheme.shapes.small),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
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
