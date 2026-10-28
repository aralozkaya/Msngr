package org.aralozkaya.msngr.pages

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Local, selectable notices remain available without a network connection. */
@Composable
fun OpenSourceLicensesScreen(innerPadding: PaddingValues) {
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.scheme == "file" && uri.path == "/android_asset/licenses/index.html") return false
                        if (uri.scheme == "https" && request.hasGesture()) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))) }
                        }
                        return true
                    }
                }
                loadUrl("file:///android_asset/licenses/index.html")
            }
        },
        onRelease = { it.destroy() },
    )
}
