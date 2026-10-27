package org.aralozkaya.msngr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.*
import java.io.ByteArrayInputStream

internal fun Context.winkStore() = (applicationContext as MsngrApplication).winkStore
internal fun Context.winkAccount(): String? = SessionPrefs.preferenceAccount(this)?.storageId

internal fun winkThumbnail(bytes: ByteArray?): android.graphics.Bitmap? {
    if (bytes == null || bytes.size > MsnWink.MAX_CAB_BYTES) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 128 || bounds.outHeight / inSampleSize > 128) inSampleSize *= 2 }
    return runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

/** No JavaScript interface, device file access, navigation, or network fallback. */
@SuppressLint("SetJavaScriptEnabled")
internal class WinkWebView(context: Context, private val movie: ByteArray, muted: Boolean,
    private val ready: (Long) -> Unit, private val failed: () -> Unit) : WebView(context) {
    private val html = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}ruffle-player{position:absolute;inset:0;display:block;width:100vw;height:100vh}</style>
        <script>window.RufflePlayer={config:{publicPath:'/ruffle/',autoplay:'off',unmuteOverlay:'hidden',splashScreen:false,
        contextMenu:'off',allowScriptAccess:false,allowNetworking:'none',openUrlMode:'deny',wmode:'transparent',
        maxExecutionDuration:1,logLevel:'error',polyfills:false}};</script><script src="/ruffle/ruffle.js"></script>
        <script>addEventListener('DOMContentLoaded',async()=>{try{
          const p=window.RufflePlayer.newest().createPlayer();document.body.appendChild(p);window.winkPlayer=p;
          const api=p.ruffle();window.winkMuted=${if (muted) "true" else "false"};
          p.addEventListener('loadedmetadata',()=>{api.volume=window.winkMuted?0:1;api.resume();const m=api.metadata;
            const duration=m&&m.numFrames>1&&m.frameRate>0?Math.ceil(m.numFrames/m.frameRate*1000):12000;
            document.title='wink-ready:'+Math.max(500,Math.min(12000,duration));});
          await api.load({url:'/movie.swf',allowScriptAccess:false,allowNetworking:'none',openUrlMode:'deny'});
        }catch(e){document.title='wink-error';}});</script><body></body>""".trimIndent().toByteArray()
    var released = false
        private set
    init {
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = response(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { failed(); return true }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (title == "wink-error") failed()
                title?.removePrefix("wink-ready:")?.toLongOrNull()?.takeIf { title.startsWith("wink-ready:") }?.let { ready(it.coerceIn(500, MsnWink.MAX_PLAYBACK_MILLIS)) }
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        loadUrl(ORIGIN + "/player.html")
    }
    internal fun response(uri: Uri): WebResourceResponse {
        val allowed = uri.scheme == "https" && uri.host == "wink.msngr.invalid" && uri.port == -1 && uri.query == null
        val path = uri.path.orEmpty()
        val asset = path.removePrefix("/ruffle/")
        val (type, data) = when {
            allowed && path == "/player.html" -> "text/html" to html.inputStream()
            allowed && path == "/movie.swf" -> "application/x-shockwave-flash" to movie.inputStream()
            allowed && path.startsWith("/ruffle/") && asset.matches(Regex("[a-zA-Z0-9_.-]+\\.(js|wasm)")) -> {
                val stream = runCatching { context.assets.open("ruffle/$asset") }.getOrNull()
                (if (asset.endsWith(".wasm")) "application/wasm" else "application/javascript") to stream
            }
            else -> "text/plain" to null
        }
        return WebResourceResponse(type, "UTF-8", if (data == null) 403 else 200,
            if (data == null) "Blocked" else "OK", mapOf(
                "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'none'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; connect-src 'self'; style-src 'unsafe-inline'; img-src data: blob:; media-src blob:; worker-src blob:; frame-src 'none'"),
            data ?: ByteArrayInputStream(byteArrayOf()))
    }
    fun mute(muted: Boolean) { if (!released) evaluateJavascript("window.winkMuted=$muted;if(window.winkPlayer)window.winkPlayer.ruffle().volume=${if (muted) 0 else 1}", null) }
    fun release() {
        if (released) return
        released = true
        stopLoading(); onPause()
        (parent as? android.view.ViewGroup)?.removeView(this)
        removeAllViews(); destroy()
    }
    companion object { const val ORIGIN = "https://wink.msngr.invalid" }
}

@Composable
internal fun WinkOverlay(reference: WinkReference, onDismiss: () -> Unit, automatic: Boolean = false, onPlayerCreated: (WinkWebView) -> Unit = {}) {
    val context = LocalContext.current
    val account = context.winkAccount()
    val settings by remember(context) { WinkPrefs.state(context) }.collectAsState()
    var wink by remember(reference.metadata.sha1d) { mutableStateOf<WinkPackage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var readyDuration by remember { mutableStateOf<Long?>(null) }
    var player by remember { mutableStateOf<WinkWebView?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val motionAllowed = android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    val animate = motionAllowed && (!automatic || settings.playWinks)
    val muted = !settings.sounds
    LaunchedEffect(reference.metadata.sha1d, account) {
        wink = withContext(Dispatchers.IO) { account?.let { context.winkStore().bytes(it, reference.metadata) }?.let(MsnWinkPackage::parse) }
        if (wink == null) error = "This wink is no longer cached."
    }
    LaunchedEffect(Unit) { delay(18_000); dismiss() } // Also bounds a stalled WebAssembly startup.
    LaunchedEffect(readyDuration, animate) { readyDuration?.let { delay(it); dismiss() }; if (!animate) { delay(3_000); dismiss() } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { player?.release(); dismiss() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.release() }
    }
    DisposableEffect(readyDuration, muted) {
        // A short sound may duck other media. Losing focus immediately mutes this wink.
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            player?.mute(muted || change != AudioManager.AUDIOFOCUS_GAIN)
        }
        var request: android.media.AudioFocusRequest? = null
        var acquired = false
        if (readyDuration != null && !muted) {
            val result = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(listener).build().also { request = it }.let(audio::requestAudioFocus)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
            acquired = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            player?.mute(!acquired)
        }
        if (muted || readyDuration == null) player?.mute(true)
        onDispose {
            if (acquired) {
                if (android.os.Build.VERSION.SDK_INT >= 26 && request != null) audio.abandonAudioFocusRequest(request!!)
                else { @Suppress("DEPRECATION") audio.abandonAudioFocus(listener) }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val previousStream = window?.volumeControlStream
            window?.volumeControlStream = AudioManager.STREAM_MUSIC
            onDispose { if (previousStream != null) window?.volumeControlStream = previousStream }
        }
        Box(Modifier.fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
            val image = remember(wink) { winkThumbnail(wink?.thumbnail)?.asImageBitmap() }
            if (image != null && (readyDuration == null || !animate)) Image(image, reference.name, Modifier.size(160.dp))
            if (error != null) Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.padding(24.dp)) {
                Text(error!!, Modifier.padding(20.dp))
            }
            if (animate && wink != null && error == null) AndroidView(
                factory = { WinkWebView(it, wink!!.animation, true, { duration -> if (readyDuration == null) readyDuration = duration }, { error = "This wink could not be played."; player?.release() }).also { player = it; onPlayerCreated(it) } },
                modifier = Modifier.fillMaxWidth().aspectRatio(wink!!.width.toFloat() / wink!!.height),
                onRelease = { it.release() })
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraLarge)) {
                Icon(Icons.Default.Close, "Close wink")
            }
        }
    }
}
