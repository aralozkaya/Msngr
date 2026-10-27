@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    var busy by remember { mutableStateOf(false) }
    val libraryChanges by context.winkStore().libraryChanges.collectAsState()
    var preview by remember(account) { mutableStateOf<WinkReference?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible, account, generation, libraryChanges) {
        if (visible && account != null) catalog = withContext(Dispatchers.IO) { context.winkStore().catalog(account) }
    }
    KeyboardPickerSheet(visible, onDismiss, "Winks", "wink_picker", expandable = true,
        header = {
            Text("Winks", style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }, footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { context.startActivity(Intent(context, WinkCatalogActivity::class.java)) }) { Text("Get more winks") }
            }
        }) {
        catalog.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { reference ->
                    WinkPickerTile(reference, canSend = enabled, enabled = !busy,
                        modifier = Modifier.weight(1f), onPreview = { preview = reference }, onSend = {
                            if (account != null) scope.launch {
                                busy = true
                                try {
                                    val bytes = withContext(Dispatchers.IO) { context.winkStore().bytes(account, reference.metadata) }
                                    val result = if (bytes != null) onSend(reference, bytes)
                                        else Result.failure(IllegalStateException("Wink is unavailable."))
                                    error = result.exceptionOrNull()?.message
                                    if (result.isSuccess) onDismiss()
                                } finally { busy = false }
                            }
                        }, onDelete = if (context.winkStore().isDefault(reference.metadata)) null else ({
                            if (account != null) scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        check(context.winkAccount() == account) { "The selected account changed. Try again." }
                                        context.winkStore().remove(account, reference.metadata)
                                    }
                                    error = null
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (failure: Exception) { error = failure.message ?: "Could not delete wink." }
                                finally { busy = false }
                            }
                        }))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    LaunchedEffect(visible) { if (!visible) preview = null }
    if (visible) preview?.let { WinkOverlay(it, { preview = null }) }
}

@Composable
private fun WinkPickerTile(reference: WinkReference, canSend: Boolean, enabled: Boolean,
    modifier: Modifier, onPreview: () -> Unit, onSend: () -> Unit, onDelete: (() -> Unit)?) {
    var menuExpanded by remember(reference.metadata.sha1d) { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().combinedClickable(
            enabled = enabled,
            onClickLabel = if (canSend) "Send wink" else "Show wink options",
            onLongClickLabel = "Show wink options",
            onClick = { if (canSend) onSend() else menuExpanded = true },
            onLongClick = { menuExpanded = true },
        ).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WinkThumbnail(reference, Modifier.size(52.dp))
            Text(reference.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        DropdownMenuPopup(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                DropdownMenuItem(text = { Text("Preview") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                    shape = MaterialTheme.shapes.medium,
                    onClick = { menuExpanded = false; onPreview() })
                if (onDelete != null) DropdownMenuItem(text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.medium,
                    onClick = { menuExpanded = false; onDelete() })
            }
        }
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
internal fun WinkMessage(message: ChatMessage, onShowOptions: () -> Unit) {
    val reference = message.wink ?: return
    Column(
        modifier = Modifier.testTag("wink_message_${message.id}")
            .clickable(onClickLabel = "Show wink options", role = Role.Button, onClick = onShowOptions),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        key(message.isWinkPending, message.winkTransferError) { WinkThumbnail(reference, Modifier.size(72.dp)) }
        Text(reference.name, style = MaterialTheme.typography.titleSmall)
        if (message.isWinkPending) {
            LinearProgressIndicator(Modifier.width(120.dp).padding(bottom = 8.dp))
            Text("Downloading wink…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private enum class WinkLibraryState { Default, Saved, Unsaved }

@Composable
internal fun WinkMessageSheet(message: ChatMessage, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val reference = message.wink ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = context.winkAccount()
    var play by remember(account, message.id) { mutableStateOf(false) }
    val store = context.winkStore()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    val libraryChanges by store.libraryChanges.collectAsState()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val libraryState by produceState<WinkLibraryState?>(null, account, reference.metadata, libraryChanges, generation) {
        value = withContext(Dispatchers.IO) {
            account?.let {
                when {
                    store.isDefault(reference.metadata) -> WinkLibraryState.Default
                    store.isSaved(it, reference.metadata) -> WinkLibraryState.Saved
                    else -> WinkLibraryState.Unsaved
                }
            }
        }
    }
    var busy by remember(account, reference.metadata.sha1d) { mutableStateOf(false) }
    var error by remember(account, reference.metadata.sha1d) { mutableStateOf<String?>(null) }
    val optionsEnabled = !busy && !message.isWinkPending && message.winkTransferError == null
    val canChangeSaved = libraryState == WinkLibraryState.Saved || libraryState == WinkLibraryState.Unsaved
    val canRetry = !message.isWinkPending && message.winkTransferError != null &&
        message.author == org.aralozkaya.msngr.data.MessageAuthor.Contact
    val actionCount = 1 + (if (canChangeSaved) 1 else 0) + (if (canRetry) 1 else 0)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        modifier = Modifier.testTag("wink_message_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            key(message.isWinkPending, message.winkTransferError, libraryChanges) {
                WinkThumbnail(reference, Modifier.size(128.dp))
            }
            Text(reference.name, Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
            if (message.isWinkPending) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Text("Downloading wink…", style = MaterialTheme.typography.bodySmall)
            } else message.winkTransferError?.let { transferError ->
                Text(transferError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            SegmentedListItem(onClick = { play = true }, enabled = optionsEnabled,
                shapes = ListItemDefaults.segmentedShapes(0, actionCount),
                leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                content = { Text("Play") })
            if (canChangeSaved) {
                val remove = libraryState == WinkLibraryState.Saved
                SegmentedListItem(onClick = {
                    val owner = account ?: return@SegmentedListItem
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            withContext(Dispatchers.IO) {
                                check(context.winkAccount() == owner) { "The selected account changed. Try again." }
                                if (remove) {
                                    // Keep this message playable after removing its library copy.
                                    store.bytes(owner, reference.metadata)?.let { store.receive(owner, message.contactId, reference, it) }
                                    store.remove(owner, reference.metadata)
                                } else if (!store.isSaved(owner, reference.metadata)) {
                                    val bytes = store.bytes(owner, reference.metadata) ?: error("Wink is no longer cached.")
                                    store.install(owner, reference, bytes)
                                }
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: "Could not update saved winks." }
                        finally { busy = false }
                    }
                }, enabled = optionsEnabled, shapes = ListItemDefaults.segmentedShapes(1, actionCount),
                    leadingContent = { Icon(if (remove) Icons.Default.Delete else Icons.Default.Add, null,
                        tint = if (remove && optionsEnabled) MaterialTheme.colorScheme.error else LocalContentColor.current) },
                    trailingContent = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    content = { Text(if (remove) "Remove from saved winks" else "Add to saved winks",
                        color = if (remove && optionsEnabled) MaterialTheme.colorScheme.error else LocalContentColor.current) })
            }
            if (canRetry) {
                SegmentedListItem(onClick = onRetry, enabled = !busy,
                    shapes = ListItemDefaults.segmentedShapes(actionCount - 1, actionCount),
                    leadingContent = { Icon(Icons.Default.Refresh, null) },
                    content = { Text("Retry") })
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
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
