@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.annotation.SuppressLint
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
import kotlinx.coroutines.CancellationException
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
    private data class PendingImport(val account: String, val items: List<Pair<WinkReference, ByteArray>>)

    private var pending by mutableStateOf<PendingImport?>(null)
    private var status by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)
    private var browser: WebView? = null

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
        loading = true
        status = null
        lifecycleScope.launch {
            try {
                pending = withContext(Dispatchers.IO) {
                    val bytes = WinkDownloads.download(uri.toString(), catalogUrl)
                    PendingImport(account, MsnWinkPackage.install(bytes, name))
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { status = e.message?.let(::localizeUserMessage) ?: getString(R.string.wink_download_failed_generic) }
            finally { loading = false }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable private fun Catalog() {
        val catalogUrl = WinkDownloads.catalogUrl(SessionPrefs.readServerConfig(this).winkCatalogServer)
        val catalogUri = remember(catalogUrl) { catalogUrl?.let(::URI) }
        var rendererStopped by remember { mutableStateOf(false) }
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
                if (rendererStopped) return@Column
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
                            val packageUrl = if (url.encodedQuery != null && WinkDownloads.catalogPageUrl(url.toString(), catalogUri) == url.toString()) {
                                url.getQueryParameter("msngr-install")
                            } else null
                            if (packageUrl != null) {
                                view.post { prepareImport(Uri.parse(packageUrl), url.getQueryParameter("name") ?: getString(R.string.wink_fallback_name_singular)) }
                                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                            }
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            val page = WinkDownloads.catalogPageUrl(url.toString(), catalogUri)
                            if (page != null && page != url.toString()) {
                                view.loadUrl(page)
                                return true
                            }
                            if (page != null) url.getQueryParameter("msngr-install")?.let {
                                prepareImport(Uri.parse(it), url.getQueryParameter("name") ?: getString(R.string.wink_fallback_name_singular))
                                return true
                            }
                            val trusted = WinkDownloads.trustedPackageUrl(url.toString(), catalogUrl)
                            if (trusted != null) { prepareImport(Uri.parse(trusted), view.title?.removePrefix("Wink - ") ?: getString(R.string.wink_fallback_name_singular)); return true }
                            return page == null
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            if (WinkDownloads.catalogPageUrl(url, catalogUri) != url) return
                            // Replace the page's legacy COM call. No JavaScript-to-Java bridge is exposed.
                            val quotedBase = org.json.JSONObject.quote(catalogUri.resolve(".").toASCIIString())
                            view.evaluateJavascript("window.InstallContent=function(u){fetch($quotedBase+'?msngr-install='+encodeURIComponent(u)+'&name='+encodeURIComponent(document.title.replace(/^Wink - /,'')),{cache:'no-store'});};", null)
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            status = getString(R.string.wink_catalog_stopped)
                            rendererStopped = true
                            browser = null
                            return true
                        }
                    }
                    setDownloadListener { url, _, _, _, _ -> prepareImport(Uri.parse(url), title?.removePrefix("Wink - ") ?: getString(R.string.wink_fallback_name_singular)) }
                    loadUrl(catalogUrl)
                } }, modifier = Modifier.fillMaxSize(), onRelease = {
                    browser = null
                    it.destroy()
                })
            }
        }
        pending?.let { selection ->
            val items = selection.items
            AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.wink_add_dialog_title)) },
                text = { Text(items.joinToString("\n") { localizeWinkName(it.first.name) }) },
                confirmButton = { Button(shapes = ButtonDefaults.shapes(), onClick = {
                    pending = null
                    loading = true
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                check(winkAccount() == selection.account) { getString(R.string.wink_account_changed) }
                                for ((reference, cab) in items) winkStore().install(selection.account, reference, cab)
                            }
                            status = getQuantityNumberSentence(
                                count = items.size,
                                oneBeforeRes = R.string.wink_added_count_singular_you_singular_before_number,
                                oneAfterRes = R.string.wink_added_count_singular_you_singular_after_number,
                                otherBeforeRes = R.string.wink_added_count_plural_you_singular_before_number,
                                otherAfterRes = R.string.wink_added_count_plural_you_singular_after_number,
                            )
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            status = failure.message?.let(::localizeUserMessage) ?: getString(R.string.wink_add_failed)
                        } finally {
                            loading = false
                        }
                    }
                }) { Text(stringResource(R.string.wink_add_to_library)) } },
                dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
    companion object { const val CATALOG_URL = "https://apps.escargot.nina.chat/content/winks/" }
}

internal object WinkDownloads {
    private val escargotFiles = URI("https://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/winks/files/")

    private fun webUri(value: String): URI? = try {
        URI(value).takeIf { uri ->
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535) && '\\' !in uri.path &&
                uri.path.split('/').none { it == ".." || it == "." }
        }
    } catch (_: java.net.URISyntaxException) {
        null
    }

    fun catalogUrl(value: String): String? = webUri(value.trim())?.takeIf { it.fragment == null }?.toASCIIString()

    private fun URI.onCatalogOrigin(catalog: URI): String? {
        if (!host.equals(catalog.host, ignoreCase = true)) return null
        val transport = if (catalog.scheme == "https") "https" else scheme
        val defaultPort = if (transport == "https") 443 else 80
        val catalogDefault = if (catalog.scheme == "https") 443 else 80
        val expectedPort = if (catalog.port == -1 || catalog.port == catalogDefault) defaultPort else catalog.port
        if ((if (port == -1) defaultPort else port) != expectedPort) return null
        return transport + toASCIIString().substringAfter(scheme)
    }

    fun catalogPageUrl(value: String, catalog: URI): String? {
        val uri = webUri(value) ?: return null
        if (!uri.path.ifEmpty { "/" }.startsWith(catalog.resolve(".").path)) return null
        return uri.onCatalogOrigin(catalog)
    }

    fun trustedPackageUrl(value: String, catalogUrl: String = WinkCatalogActivity.CATALOG_URL): String? {
        val catalog = webUri(catalogUrl.trim())?.takeIf { it.fragment == null } ?: return null
        val uri = webUri(value) ?: return null
        val extension = uri.path.substringAfterLast('.', "").lowercase()
        if (uri.fragment != null || extension !in setOf("mco", "cab", "mct")) return null
        if (catalog.toASCIIString() == WinkCatalogActivity.CATALOG_URL && uri.path.startsWith(escargotFiles.path)) {
            uri.onCatalogOrigin(escargotFiles)?.let { return it }
        }
        return uri.onCatalogOrigin(catalog)
    }

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
                    return connection.inputStream.use { it.readWinkPackageBytes() }
                }
            } finally { connection.disconnect() }
        }
        error("Too many download redirects.")
    }
}
