package org.aralozkaya.msngr

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.protocol.*
import org.aralozkaya.msngr.pages.components.KeyboardPickerSheet

@Composable
internal fun WinkPicker(visible: Boolean, enabled: Boolean, onDismiss: () -> Unit,
    onSend: suspend (WinkReference, ByteArray) -> Result<Unit>) {
    val context = LocalContext.current
    val account = context.winkAccount()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<WinkReference>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val settings by remember(context) { WinkPrefs.state(context) }.collectAsState()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible, account, generation) {
        if (visible && account != null) catalog = withContext(Dispatchers.IO) { context.winkStore().catalog(account) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && account != null) scope.launch {
            error = runCatching { withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(MsnWink.MAX_CAB_BYTES) } ?: error("Could not read wink.")
                val winks = MsnWinkPackage.install(bytes)
                for ((reference, cab) in winks) context.winkStore().install(account, reference, cab)
            }; generation++ }.exceptionOrNull()?.message
        }
    }
    KeyboardPickerSheet(visible, onDismiss, "Winks", "wink_picker", expandable = true,
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Winks", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.playWinks, { WinkPrefs.update(context, settings.copy(playWinks = !settings.playWinks)) }, { Text("Auto-play") })
                    FilterChip(settings.sounds, { WinkPrefs.update(context, settings.copy(sounds = !settings.sounds)) }, { Text("Sound") })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }, footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { context.startActivity(Intent(context, WinkCatalogActivity::class.java)) }) { Text("Get more winks") }
                TextButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import a file") }
            }
        }) {
        catalog.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { reference ->
                    Column(Modifier.weight(1f).clickable(enabled = enabled && !sending) {
                        if (account != null) scope.launch {
                            sending = true
                            try {
                                val bytes = withContext(Dispatchers.IO) { context.winkStore().bytes(account, reference.metadata) }
                                val result = if (bytes != null) onSend(reference, bytes) else Result.failure(IllegalStateException("Wink is unavailable."))
                                error = result.exceptionOrNull()?.message
                                if (result.isSuccess) onDismiss()
                            } finally { sending = false }
                        }
                    }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        WinkThumbnail(reference, Modifier.size(52.dp))
                        Text(reference.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun WinkThumbnail(reference: WinkReference, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val account = context.winkAccount()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, reference.metadata.sha1d, account) {
        value = withContext(Dispatchers.IO) {
            account?.let { context.winkStore().bytes(it, reference.metadata) }
                ?.let(MsnWinkPackage::parse)?.thumbnail?.let(::winkThumbnail)?.asImageBitmap()
        }
    }
    if (bitmap != null) Image(bitmap!!, reference.name, modifier)
    else Icon(Icons.Default.AutoAwesome, reference.name, modifier)
}

@Composable
internal fun WinkMessage(message: ChatMessage) {
    val reference = message.wink ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var play by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WinkThumbnail(reference, Modifier.size(72.dp))
        Text(reference.name, style = MaterialTheme.typography.titleSmall)
        when {
            message.isWinkPending -> { LinearProgressIndicator(Modifier.width(120.dp)); Text("Downloading wink…", style = MaterialTheme.typography.labelSmall) }
            message.winkTransferError != null -> Text(message.winkTransferError, style = MaterialTheme.typography.bodySmall)
            else -> {
                TextButton(onClick = { play = true }, contentPadding = PaddingValues(0.dp)) { Text("Play again") }
                TextButton(onClick = { scope.launch {
                    val account = context.winkAccount() ?: return@launch
                    status = withContext(Dispatchers.IO) { runCatching {
                        val bytes = context.winkStore().bytes(account, reference.metadata) ?: error("Wink is no longer cached.")
                        context.winkStore().install(account, reference, bytes)
                        "Added to your winks"
                    }.getOrElse { it.message ?: "Could not add wink." } }
                } }, contentPadding = PaddingValues(0.dp)) { Text("Add to my winks") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
    if (play) WinkOverlay(reference, { play = false })
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break
        require(result.size() + count <= limit) { "Wink package is too large." }; result.write(buffer, 0, count) }
    return result.toByteArray()
}
