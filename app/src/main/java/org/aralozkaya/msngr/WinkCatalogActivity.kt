@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.protocol.*
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import java.net.HttpURLConnection
import java.net.URI

/** Escargot's site uses InstallContent/ActiveX, not a browser URL protocol. */
class WinkCatalogActivity : MsngrActivity() {
    private var pending by mutableStateOf<List<Pair<WinkReference, ByteArray>>?>(null)
    private var status by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)
    private var browser: WebView? = null
    private var importJob: kotlinx.coroutines.Job? = null
    private var importAccount: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MsngrTheme { Catalog() } }
        val uri = intent.data
        if (uri != null) {
            when {
                uri.scheme == "msngr" && uri.host == "winks" -> uri.getQueryParameter("url")?.let { prepareImport(Uri.parse(it), getString(R.string.wink_fallback_name_singular)) }
                else -> prepareImport(uri, getString(R.string.wink_fallback_name_singular))
            }
        }
    }

    private fun prepareImport(uri: Uri, name: String) {
        if (loading || pending != null) return
        val account = winkAccount()
        if (account == null) { status = getString(R.string.wink_sign_in_required); return }
        val catalogUrl = SessionPrefs.readServerConfig(this).winkCatalogServer
        if (WinkDownloads.catalogUrl(catalogUrl) == null) {
            status = getString(R.string.wink_not_configured)
            return
        }
        importAccount = account
        loading = true; status = null
        importJob = lifecycleScope.launch {
            try {
                pending = withContext(Dispatchers.IO) {
                    val bytes = WinkDownloads.download(uri.toString(), catalogUrl)
                    MsnWinkPackage.install(bytes, name)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { status = e.message?.let(::localizeUserMessage) ?: getString(R.string.wink_download_failed_generic) }
            finally { loading = false }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable private fun Catalog() {
        val catalogUrl = WinkDownloads.catalogUrl(SessionPrefs.readServerConfig(this).winkCatalogServer)
        val catalogUri = remember(catalogUrl) { catalogUrl?.let(Uri::parse) }
        BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else finish() }
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.wink_get_more)) }, navigationIcon = {
            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                status?.let { Text(localizeUserMessage(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary) }
                if (catalogUrl == null || catalogUri == null) {
                    if (status == null) {
                        Text(
                            stringResource(R.string.wink_not_configured),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }
                val catalogHost = catalogUri.host.orEmpty()
                val catalogPath = catalogUri.path.orEmpty().ifEmpty { "/" }
                val catalogBasePath = if (catalogPath.endsWith('/')) catalogPath else catalogPath.substringBeforeLast('/') + "/"
                if (loading) MsngrLinearWavyProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(factory = { context -> WebView(context).apply {
                    browser = this
                    settings.apply { javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
                        domStorageEnabled = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            val packageUrl = if (url.scheme in listOf("https", catalogUri.scheme) && url.host == catalogHost &&
                                url.path.orEmpty().startsWith(catalogBasePath)) url.getQueryParameter("msngr-install") else null
                            if (packageUrl != null) {
                                view.post { prepareImport(Uri.parse(packageUrl), url.getQueryParameter("name") ?: getString(R.string.wink_fallback_name_singular)) }
                                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                            }
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.scheme in listOf("https", catalogUri.scheme) && url.host == catalogHost && url.getQueryParameter("msngr-install") != null) {
                                url.getQueryParameter("msngr-install")?.let { prepareImport(Uri.parse(it), url.getQueryParameter("name") ?: getString(R.string.wink_fallback_name_singular)) }; return true
                            }
                            val trusted = WinkDownloads.trustedPackageUrl(url.toString(), catalogUrl)
                            if (trusted != null) { prepareImport(Uri.parse(trusted), view.title?.removePrefix("Wink - ") ?: getString(R.string.wink_fallback_name_singular)); return true }
                            if (url.host == catalogHost && url.path.orEmpty().startsWith(catalogBasePath)) {
                                if (url.scheme == "http" && catalogUri.scheme == "https") {
                                    view.loadUrl(url.buildUpon().scheme("https").build().toString()); return true
                                }
                                return url.scheme !in listOf("https", catalogUri.scheme)
                            }
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            if (Uri.parse(url).host != catalogHost) return
                            // Replace the page's legacy COM call. No JavaScript-to-Java bridge is exposed.
                            val installBase = "${catalogUri.scheme}://${catalogUri.authority}$catalogBasePath"
                            val quotedBase = org.json.JSONObject.quote(installBase)
                            view.evaluateJavascript("window.InstallContent=function(u){fetch($quotedBase+'?msngr-install='+encodeURIComponent(u)+'&name='+encodeURIComponent(document.title.replace(/^Wink - /,'')),{cache:'no-store'});};", null)
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            status = getString(R.string.wink_catalog_stopped); view.destroy(); browser = null; return true
                        }
                    }
                    setDownloadListener { url, _, _, _, _ -> prepareImport(Uri.parse(url), title?.removePrefix("Wink - ") ?: getString(R.string.wink_fallback_name_singular)) }
                    loadUrl(catalogUrl)
                } }, modifier = Modifier.fillMaxSize(), onRelease = { it.stopLoading(); it.destroy(); browser = null })
            }
        }
        pending?.let { items ->
            AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.wink_add_dialog_title)) },
                text = { Text(items.joinToString("\n") { localizeWinkName(it.first.name) }) },
                confirmButton = { Button(shapes = ButtonDefaults.shapes(), onClick = {
                    pending = null
                    lifecycleScope.launch {
                        status = withContext(Dispatchers.IO) { runCatching {
                            val account = importAccount ?: error(getString(R.string.wink_sign_in_required))
                            check(winkAccount() == account) { getString(R.string.wink_account_changed) }
                            for ((reference, cab) in items) winkStore().install(account, reference, cab)
                            getQuantityNumberSentence(
                                count = items.size,
                                oneBeforeRes = R.string.wink_added_count_singular_you_singular_before_number,
                                oneAfterRes = R.string.wink_added_count_singular_you_singular_after_number,
                                otherBeforeRes = R.string.wink_added_count_plural_you_singular_before_number,
                                otherAfterRes = R.string.wink_added_count_plural_you_singular_after_number,
                            )
                        }.getOrElse { it.message?.let(::localizeUserMessage) ?: getString(R.string.wink_add_failed) } }
                    }
                }) { Text(stringResource(R.string.wink_add_to_library)) } },
                dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
    override fun onDestroy() { importJob?.cancel(); browser?.stopLoading(); super.onDestroy() }
    companion object { const val CATALOG_URL = "https://apps.escargot.nina.chat/content/winks/" }
}

internal object WinkDownloads {
    fun catalogUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        uri.toASCIIString()
    }.getOrNull()

    fun trustedPackageUrl(value: String, catalogUrl: String = WinkCatalogActivity.CATALOG_URL): String? = runCatching {
        val catalog = catalogUrl(catalogUrl)?.let(::URI) ?: return@runCatching null
        val uri = URI(value)
        val escargotHost = catalog.toASCIIString() == WinkCatalogActivity.CATALOG_URL &&
            uri.host == "static.levelleap.com" &&
            uri.path.startsWith("/sites/messaging/apps.escargot.nina.chat/winks/files/")
        val sameCatalogOrigin = uri.host == catalog.host && (uri.port == -1 || uri.port == catalog.port)
        require(uri.scheme in listOf("https", "http") && (escargotHost || sameCatalogOrigin) &&
            uri.rawUserInfo == null && (escargotHost && uri.port == -1 || sameCatalogOrigin) && uri.fragment == null &&
            uri.path.lowercase().let { it.endsWith(".mco") || it.endsWith(".cab") || it.endsWith(".mct") } &&
            uri.path.split('/').none { it == ".." || it == "." })
        val resolvedScheme = when {
            escargotHost -> "https"
            sameCatalogOrigin && catalog.scheme.equals("https", ignoreCase = true) && uri.port == -1 -> "https"
            else -> uri.scheme
        }
        URI(resolvedScheme, null, uri.host, uri.port, uri.path, uri.query, null).toASCIIString()
    }.getOrNull()

    fun download(value: String, catalogUrl: String = WinkCatalogActivity.CATALOG_URL): ByteArray {
        var url = trustedPackageUrl(value, catalogUrl) ?: error("This wink download link is not from the configured catalog.")
        repeat(4) {
            val connection = java.net.URL(url).openConnection() as HttpURLConnection
            connection.apply { connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location") ?: error("Invalid download redirect.")
                    url = trustedPackageUrl(URI(url).resolve(next).toString(), catalogUrl) ?: error("Untrusted wink download redirect.")
                } else {
                    check(code == 200) { "Wink download failed (HTTP $code)." }
                    require(connection.contentLengthLong <= MsnWink.MAX_CAB_BYTES) { "Wink package is too large." }
                    return connection.inputStream.use { it.readBytesBounded(MsnWink.MAX_CAB_BYTES) }
                }
            } finally { connection.disconnect() }
        }
        error("Too many download redirects.")
    }
}
