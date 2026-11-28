@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.protocol.*
import org.aralozkaya.msngr.pages.components.KeyboardPickerSheet

internal val WINK_IMPORT_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/vnd.ms-cab-compressed",
    "application/x-mco",
)

@Composable
internal fun WinkPicker(visible: Boolean, enabled: Boolean, onDismiss: () -> Unit,
    onSend: suspend (WinkReference, ByteArray) -> Result<Unit>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val account = context.winkAccount()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<List<WinkReference>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var pendingExport by remember(account) { mutableStateOf<WinkReference?>(null) }
    val libraryChanges by context.winkStore().libraryChanges.collectAsState()
    var preview by remember(account) { mutableStateOf<WinkReference?>(null) }
    val catalogConfigured = SessionPrefs.readServerConfig(context).winkCatalogServer.isNotBlank()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = account ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            status = null
            try {
                val importedCount = withContext(Dispatchers.IO) {
                    check(context.winkAccount() == owner) { resources.getString(R.string.wink_account_changed) }
                    val displayName = context.queryDocumentDisplayName(uri)
                    val fallbackName = MsnWinkPackage.fallbackNameFromFileName(displayName)
                    val rawBytes = context.contentResolver.openInputStream(uri)?.use {
                        it.readWinkPackageBytes()
                    } ?: error(resources.getString(R.string.wink_import_failed))
                    val items = MsnWinkPackage.install(rawBytes, fallbackName)
                    for ((reference, cab) in items) {
                        context.winkStore().install(owner, reference, cab)
                    }
                    items.size
                }
                status = context.getQuantityNumberSentence(
                    count = importedCount,
                    oneBeforeRes = R.string.wink_added_count_singular_you_singular_before_number,
                    oneAfterRes = R.string.wink_added_count_singular_you_singular_after_number,
                    otherBeforeRes = R.string.wink_added_count_plural_you_singular_before_number,
                    otherAfterRes = R.string.wink_added_count_plural_you_singular_after_number,
                )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: resources.getString(R.string.wink_import_failed)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val reference = pendingExport
        pendingExport = null
        val owner = account ?: return@rememberLauncherForActivityResult
        if (uri == null || reference == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            status = null
            try {
                withContext(Dispatchers.IO) {
                    check(context.winkAccount() == owner) { resources.getString(R.string.wink_account_changed) }
                    val cabBytes = context.winkStore().bytes(owner, reference.metadata)
                        ?: error(resources.getString(R.string.wink_unavailable))
                    val mcoBytes = MsnWinkPackage.exportMco(reference, cabBytes)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(mcoBytes) }
                        ?: error(resources.getString(R.string.wink_export_failed))
                }
                status = resources.getString(R.string.wink_exported)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: resources.getString(R.string.wink_export_failed)
            } finally {
                busy = false
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible, account, generation, libraryChanges) {
        if (visible && account != null) catalog = withContext(Dispatchers.IO) { context.winkStore().catalog(account) }
    }
    KeyboardPickerSheet(visible, onDismiss, stringResource(R.string.chat_menu_winks_plural), "wink_picker", expandable = true,
        header = if (status != null || error != null) {
            {
                status?.let { Text(localizeUserMessage(it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(localizeUserMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            null
        }, footer = {
            val moreOptionsDescription = stringResource(R.string.wink_more_options)
            val menuState = stringResource(
                if (moreMenuExpanded) R.string.contacts_actions_expanded else R.string.contacts_actions_collapsed,
            )
            val menuInteractionSource = remember { MutableInteractionSource() }
            SplitButtonLayout(
                modifier = Modifier.testTag("wink_get_more_split_button"),
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = { context.startActivity(Intent(context, WinkCatalogActivity::class.java)) },
                        enabled = catalogConfigured && !busy,
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                        modifier = Modifier.testTag("wink_get_more_button"),
                    ) {
                        Text(stringResource(R.string.wink_get_more))
                    }
                },
                trailingButton = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(moreOptionsDescription) } },
                        state = rememberTooltipState(),
                    ) {
                        SplitButtonDefaults.TonalTrailingButton(
                            checked = moreMenuExpanded,
                            onCheckedChange = { moreMenuExpanded = it },
                            enabled = !busy && account != null,
                            modifier = Modifier
                                .fillMaxHeight()
                                .testTag("wink_more_options")
                                .semantics {
                                    contentDescription = moreOptionsDescription
                                    stateDescription = menuState
                                },
                        ) {
                            val rotation by animateFloatAsState(
                                targetValue = if (moreMenuExpanded) 180f else 0f,
                                label = "WinkMoreOptionsIconRotation",
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(SplitButtonDefaults.TrailingIconSize)
                                    .graphicsLayer { rotationZ = rotation },
                            )
                        }
                    }
                    DropdownMenuPopup(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                    ) {
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(0, 1),
                            interactionSource = menuInteractionSource,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.wink_import)) },
                                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                                shape = MenuDefaults.itemShape(0, 1).shape,
                                enabled = !busy && account != null,
                                modifier = Modifier.testTag("wink_import_mco"),
                                onClick = {
                                    moreMenuExpanded = false
                                    importLauncher.launch(WINK_IMPORT_MIME_TYPES)
                                },
                            )
                        }
                    }
                },
            )
        }) {
        catalog.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { reference ->
                    val isDefault = context.winkStore().isDefault(reference.metadata)
                    WinkPickerTile(reference, canSend = enabled, enabled = !busy,
                        modifier = Modifier.weight(1f), onPreview = { preview = reference }, onSend = {
                            if (account != null) scope.launch {
                                busy = true
                                try {
                                    val bytes = withContext(Dispatchers.IO) { context.winkStore().bytes(account, reference.metadata) }
                                    val result = if (bytes != null) onSend(reference, bytes)
                                        else Result.failure(IllegalStateException(resources.getString(R.string.wink_unavailable)))
                                    error = result.exceptionOrNull()?.message
                                    status = null
                                    if (result.isSuccess) onDismiss()
                                } finally { busy = false }
                            }
                        }, onExport = if (isDefault) null else ({
                            pendingExport = reference
                            exportLauncher.launch(MsnWinkPackage.suggestedMcoFileName(reference.name))
                        }), onDelete = if (isDefault) null else ({
                            if (account != null) scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        check(context.winkAccount() == account) { resources.getString(R.string.wink_account_changed) }
                                        context.winkStore().remove(account, reference.metadata)
                                    }
                                    error = null
                                    status = null
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (failure: Exception) { error = failure.message ?: resources.getString(R.string.wink_delete_failed) }
                                finally { busy = false }
                            }
                        }))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (busy) MsngrLinearWavyProgressIndicator(Modifier.fillMaxWidth())
    }
    LaunchedEffect(visible) {
        if (!visible) {
            preview = null
            status = null
            error = null
            moreMenuExpanded = false
        }
    }
    if (visible) preview?.let { WinkOverlay(it, { preview = null }) }
}

@Composable
private fun WinkPickerTile(reference: WinkReference, canSend: Boolean, enabled: Boolean,
    modifier: Modifier, onPreview: () -> Unit, onSend: () -> Unit, onExport: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menuExpanded by remember(reference.metadata.sha1d) { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().combinedClickable(
            enabled = enabled,
            onClickLabel = stringResource(if (canSend) R.string.wink_send_label else R.string.wink_options_label),
            onLongClickLabel = stringResource(R.string.wink_options_label),
            onClick = { if (canSend) onSend() else menuExpanded = true },
            onLongClick = { menuExpanded = true },
        ).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WinkThumbnail(reference, Modifier.size(52.dp))
            Text(localizedWinkName(reference.name), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        DropdownMenuPopup(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.common_preview)) },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                    shape = MaterialTheme.shapes.medium,
                    onClick = { menuExpanded = false; onPreview() })
                if (onExport != null) DropdownMenuItem(text = { Text(stringResource(R.string.wink_export)) },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    shape = MaterialTheme.shapes.medium,
                    onClick = { menuExpanded = false; onExport() })
                if (onDelete != null) DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
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
internal fun WinkThumbnail(
    reference: WinkReference,
    modifier: Modifier = Modifier,
    packageBytes: ByteArray? = null,
) {
    val context = LocalContext.current
    val account = context.winkAccount()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, reference.metadata.sha1d, account, packageBytes) {
        value = withContext(Dispatchers.IO) {
            (packageBytes ?: account?.let { context.winkStore().bytes(it, reference.metadata) })
                ?.let(MsnWinkPackage::parse)?.thumbnail?.let(::winkThumbnail)?.asImageBitmap()
        }
    }
    if (bitmap != null) Image(bitmap!!, reference.name, modifier)
    else Icon(Icons.Default.AutoAwesome, reference.name, modifier)
}

@Composable
internal fun WinkMessage(message: ChatMessage, onShowOptions: () -> Unit, onRetry: () -> Unit) {
    val reference = message.wink ?: return
    Column(
        modifier = Modifier.testTag("wink_message_${message.id}")
            .clickable(onClickLabel = stringResource(R.string.wink_options_label), role = Role.Button, onClick = onShowOptions),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        key(message.isWinkPending, message.winkTransferError) { WinkThumbnail(reference, Modifier.size(72.dp)) }
        Text(localizedWinkName(reference.name), style = MaterialTheme.typography.titleSmall)
        if (message.isWinkPending) {
            MsngrLinearWavyProgressIndicator(Modifier.width(120.dp).padding(bottom = 8.dp))
            Text(stringResource(R.string.wink_downloading), style = MaterialTheme.typography.labelSmall)
        } else if (message.winkTransferError != null && message.author == MessageAuthor.Contact) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.chat_message_retry))
            }
        }
    }
}

private enum class WinkLibraryState { Default, Saved, Unsaved }

@Composable
internal fun WinkMessageSheet(message: ChatMessage, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val reference = message.wink ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
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
    var status by remember(account, reference.metadata.sha1d) { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val owner = account ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            status = null
            try {
                withContext(Dispatchers.IO) {
                    check(context.winkAccount() == owner) { resources.getString(R.string.wink_account_changed) }
                    val cabBytes = store.bytes(owner, reference.metadata)
                        ?: error(resources.getString(R.string.wink_not_cached))
                    val mcoBytes = MsnWinkPackage.exportMco(reference, cabBytes)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(mcoBytes) }
                        ?: error(resources.getString(R.string.wink_export_failed))
                }
                status = resources.getString(R.string.wink_exported)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: resources.getString(R.string.wink_export_failed)
            } finally {
                busy = false
            }
        }
    }
    val optionsEnabled = !busy && !message.isWinkPending && message.winkTransferError == null
    val canChangeSaved = libraryState == WinkLibraryState.Saved || libraryState == WinkLibraryState.Unsaved
    val canExport = canChangeSaved
    val canRetry = !message.isWinkPending && message.winkTransferError != null &&
        message.author == MessageAuthor.Contact
    val actionCount = 1 + (if (canChangeSaved) 1 else 0) + (if (canExport) 1 else 0) + (if (canRetry) 1 else 0)
    var actionIndex = 0
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
            Text(localizedWinkName(reference.name), Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
            if (message.isWinkPending) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MsngrLinearWavyProgressIndicator(
                        Modifier.fillMaxWidth().testTag("wink_sheet_progress"),
                    )
                    Text(
                        stringResource(R.string.wink_downloading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else message.winkTransferError?.let { transferError ->
                Text(
                    localizeUserMessage(transferError),
                    Modifier.padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SegmentedListItem(onClick = { play = true }, enabled = optionsEnabled,
                shapes = ListItemDefaults.segmentedShapes(actionIndex++, actionCount),
                leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                content = { Text(stringResource(R.string.common_play)) })
            if (canChangeSaved) {
                val remove = libraryState == WinkLibraryState.Saved
                SegmentedListItem(onClick = {
                    val owner = account ?: return@SegmentedListItem
                    scope.launch {
                        busy = true
                        error = null
                        status = null
                        try {
                            withContext(Dispatchers.IO) {
                                check(context.winkAccount() == owner) { resources.getString(R.string.wink_account_changed) }
                                if (remove) {
                                    // Keep this message playable after removing its library copy.
                                    store.bytes(owner, reference.metadata)?.let { store.receive(owner, message.contactId, reference, it) }
                                    store.remove(owner, reference.metadata)
                                } else if (!store.isSaved(owner, reference.metadata)) {
                                    val bytes = store.bytes(owner, reference.metadata) ?: error(resources.getString(R.string.wink_not_cached))
                                    store.install(owner, reference, bytes)
                                }
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: resources.getString(R.string.wink_update_saved_failed) }
                        finally { busy = false }
                    }
                }, enabled = optionsEnabled, shapes = ListItemDefaults.segmentedShapes(actionIndex++, actionCount),
                    leadingContent = { Icon(if (remove) Icons.Default.Delete else Icons.Default.Add, null,
                        tint = if (remove && optionsEnabled) MaterialTheme.colorScheme.error else LocalContentColor.current) },
                    trailingContent = { if (busy) MsngrCircularWavyProgressIndicator(Modifier.size(20.dp)) },
                    content = { Text(stringResource(if (remove) R.string.wink_save_remove else R.string.wink_save_add),
                        color = if (remove && optionsEnabled) MaterialTheme.colorScheme.error else LocalContentColor.current) })
            }
            if (canExport) {
                SegmentedListItem(
                    onClick = { exportLauncher.launch(MsnWinkPackage.suggestedMcoFileName(reference.name)) },
                    enabled = optionsEnabled,
                    shapes = ListItemDefaults.segmentedShapes(actionIndex++, actionCount),
                    leadingContent = { Icon(Icons.Default.Download, null) },
                    content = { Text(stringResource(R.string.wink_export)) },
                )
            }
            if (canRetry) {
                SegmentedListItem(onClick = onRetry, enabled = !busy,
                    shapes = ListItemDefaults.segmentedShapes(actionIndex++, actionCount),
                    leadingContent = { Icon(Icons.Default.Refresh, null) },
                    content = { Text(stringResource(R.string.common_retry)) })
            }
            status?.let { Text(localizeUserMessage(it), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(localizeUserMessage(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (play) WinkOverlay(reference, { play = false })
}
