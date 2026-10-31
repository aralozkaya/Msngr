@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.MsnBase64
import org.aralozkaya.msngr.protocol.MsnFileContext
import org.aralozkaya.msngr.pages.components.DestructiveOutlinedButton
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

private fun Context.sharingRepository() = (applicationContext as MsngrApplication).container.messengerRepository

@Composable
internal fun SharingMenu(contactId: String, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val selected = withContext(Dispatchers.IO) { stageSharedFile(context, uri) }
                val result = context.sharingRepository().sendFile(contactId, selected.first, selected.second)
                error = result.exceptionOrNull()?.message
                if (result.isFailure) selected.first.delete()
            } catch (failure: Exception) { error = failure.message ?: "The selected file could not be opened." }
            finally { busy = false }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(32)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                require(uris.size <= 32) { "Choose up to 32 photos at once." }
                val prepared = withContext(Dispatchers.IO) { prepareSharedPhotos(context, uris) }
                error = context.sharingRepository().sharePhotos(contactId, prepared).exceptionOrNull()?.message
            } catch (failure: Exception) { error = failure.message ?: "The selected photos could not be opened." }
            finally { busy = false }
        }
    }
    Box {
        if (busy) CircularWavyProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        else IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.AttachFile, contentDescription = "Share photos or files")
        }
        DropdownMenuPopup(expanded = expanded && enabled && !busy, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), modifier = Modifier.testTag("sharing_menu")) {
                DropdownMenuItem(
                    text = { Text("Photos") },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    shape = MenuDefaults.leadingItemShape,
                    modifier = Modifier.testTag("sharing_menu_photos"),
                    onClick = { expanded = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                DropdownMenuItem(
                    text = { Text("Files") },
                    leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                    shape = MenuDefaults.trailingItemShape,
                    modifier = Modifier.testTag("sharing_menu_files"),
                    onClick = { expanded = false; files.launch(arrayOf("*/*")) },
                )
            }
        }
    }
    LaunchedEffect(enabled, busy) { if (!enabled || busy) expanded = false }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Could not share") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

@Composable
internal fun FileTransferMessage(message: ChatMessage,
    onAccept: (suspend () -> Result<Unit>)? = null,
    onCancel: (suspend () -> Result<Unit>)? = null,
) {
    val transfer = message.fileTransfer ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(transfer.id) { mutableStateOf<String?>(null) }
    var saved by remember(transfer.id) { mutableStateOf(false) }
    var busy by remember(transfer.id) { mutableStateOf(false) }
    var destination by rememberSaveable(transfer.id) { mutableStateOf<String?>(null) }
    val saveRevision by context.receivedTransferStorage.revisions.collectAsState()
    val saveState = remember(transfer.id, saveRevision) {
        context.receivedTransferStorage.state(SessionPrefs.preferenceAccount(context), "file:${transfer.id}")
    }
    suspend fun accept() {
        busy = true
        try { error = (onAccept?.invoke() ?: context.sharingRepository().acceptFile(message.contactId, transfer.id)).exceptionOrNull()?.message }
        finally { busy = false }
    }
    suspend fun export(uri: Uri) {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val file = File(requireNotNull(transfer.localPath))
                file.inputStream().use { input -> requireNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
            }
            saved = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The file could not be saved." }
        finally { busy = false }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            if (transfer.status == FileTransferStatus.Offered) { destination = uri.toString(); accept() }
            else export(uri)
        }
    }
    LaunchedEffect(transfer.status, destination) {
        if (transfer.status == FileTransferStatus.Complete && destination != null) {
            val target = Uri.parse(destination)
            try { export(target) } finally { destination = null }
        }
    }
    Column(Modifier.widthIn(min = 180.dp).testTag("file_transfer_${transfer.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileTransferThumbnail(transfer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(transfer.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(android.text.format.Formatter.formatFileSize(context, transfer.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(when (transfer.status) {
            FileTransferStatus.Offered -> if (transfer.outgoing) "Waiting for acceptance" else "Wants to send you a file"
            FileTransferStatus.Transferring -> "${if (transfer.outgoing) "Sending" else "Receiving"} ${android.text.format.Formatter.formatFileSize(context, transfer.transferred)}"
            FileTransferStatus.Complete -> if (saved || saveState.uri != null) "Saved" else if (transfer.outgoing) "Send complete" else "Receive complete"
            FileTransferStatus.Declined -> "File declined"
            FileTransferStatus.Cancelled -> "File transfer cancelled"
            FileTransferStatus.Failed -> transfer.error ?: "File transfer failed"
        }, style = MaterialTheme.typography.bodySmall)
        if (transfer.status == FileTransferStatus.Transferring) LinearWavyProgressIndicator(
            progress = { if (transfer.size == 0L) 0f else (transfer.transferred.toDouble() / transfer.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        saveState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!transfer.outgoing && transfer.status == FileTransferStatus.Offered) Button(shapes = ButtonDefaults.shapes(), enabled = !busy, onClick = {
                scope.launch { accept() }
            }) { Text("Accept") }
            if (!transfer.outgoing && transfer.status == FileTransferStatus.Offered)
                OutlinedButton(shapes = ButtonDefaults.shapes(), enabled = !busy, onClick = { save.launch(transfer.name) }) { Text("Save as") }
            if (transfer.active) DestructiveOutlinedButton(enabled = !busy, onClick = {
                scope.launch { error = (onCancel?.invoke() ?: context.sharingRepository().cancelFile(message.contactId, transfer.id)).exceptionOrNull()?.message }
            }) { Text(if (!transfer.outgoing && transfer.status == FileTransferStatus.Offered) "Decline" else "Cancel") }
            if (!transfer.outgoing && transfer.status == FileTransferStatus.Complete && transfer.localPath != null)
                Button(shapes = ButtonDefaults.shapes(), enabled = !busy, onClick = { save.launch(transfer.name) }) { Text("Save as") }
            if (!transfer.outgoing && transfer.status == FileTransferStatus.Complete && transfer.localPath != null)
                OutlinedButton(shapes = ButtonDefaults.shapes(), enabled = !busy, onClick = {
                    try {
                        val uri = FileSharingProvider.uri(context, File(transfer.localPath), transfer.name)
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(uri, FileSharingProvider.mime(transfer.name))
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) { error = "No app could open this file. You can save it first." }
                }) { Text("Open") }
        }
    }
}

@Composable
private fun FileTransferThumbnail(transfer: FileTransfer) {
    val imageFile = FileSharingProvider.mime(transfer.name).startsWith("image/")
    val bitmap by produceState<Bitmap?>(null, transfer.localPath, transfer.name) {
        value = null
        if (imageFile) transfer.localPath?.let { path ->
            value = withContext(Dispatchers.IO) { runCatching { decodeFileTransferThumbnail(path) }.getOrNull() }
        }
    }
    Surface(modifier = Modifier.size(64.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            if (imageFile && bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("file_thumbnail_${transfer.id}"), contentScale = ContentScale.Fit)
            else Icon(Icons.Default.InsertDriveFile, contentDescription = "File",
                modifier = Modifier.size(32.dp).testTag("file_icon_${transfer.id}"))
        }
    }
}

@Composable
internal fun PhotoSharingHost(contactId: String, share: PhotoShare?,
    onAccept: (suspend () -> Result<Unit>)? = null,
    onStop: (suspend () -> Result<Unit>)? = null,
    onSelect: (suspend (String) -> Result<Unit>)? = null,
    onRequestOriginal: (suspend (String) -> Result<Unit>)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ongoing = share?.status in listOf(PhotoShareStatus.Invited, PhotoShareStatus.Active)
    var visible by rememberSaveable(contactId, share?.id) {
        mutableStateOf(ongoing)
    }
    var closed by rememberSaveable(contactId, share?.id) { mutableStateOf(!ongoing) }
    var error by remember(share?.id) { mutableStateOf<String?>(null) }
    var savePhotoId by rememberSaveable(share?.id) { mutableStateOf<String?>(null) }
    var saveRequested by rememberSaveable(share?.id) { mutableStateOf(false) }
    var requestingOriginal by remember(share?.id) { mutableStateOf(false) }
    var savedPhotoId by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val addPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(32)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            preparing = true
            try {
                val prepared = withContext(Dispatchers.IO) { prepareSharedPhotos(context, uris) }
                error = context.sharingRepository().sharePhotos(contactId, prepared).exceptionOrNull()?.message
            } catch (failure: Exception) { error = failure.message ?: "The selected photos could not be opened." }
            finally { preparing = false }
        }
    }
    fun requestSave() {
        val selected = share?.photos?.firstOrNull { it.id == savePhotoId }
        if (selected != null) scope.launch {
            saveRequested = true
            if (selected.originalPath == null) {
                requestingOriginal = true
                try {
                    error = (onRequestOriginal?.invoke(selected.id) ?: context.sharingRepository()
                        .requestPhotoOriginal(contactId, share.id, selected.id)).exceptionOrNull()?.message
                    if (error != null) saveRequested = false
                } finally { requestingOriginal = false }
            }
        }
    }
    val savePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) requestSave() else error = "Storage access is needed to save photos to DCIM/Msngr on Android 9 and earlier."
    }
    val requestedOriginal = share?.photos?.firstOrNull { it.id == savePhotoId }
    LaunchedEffect(requestedOriginal?.originalPath, requestedOriginal?.originalError, requestedOriginal?.originalPending, saveRequested, requestingOriginal) {
        if (requestingOriginal) return@LaunchedEffect
        if (requestedOriginal?.originalError != null && !requestedOriginal.originalPending) saveRequested = false
        if (requestedOriginal?.originalPath != null && saveRequested) {
            try {
                context.receivedTransferStorage.savePhoto(SessionPrefs.preferenceAccount(context), share.id, requestedOriginal).getOrThrow()
                savedPhotoId = savePhotoId
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The photo could not be saved." }
            finally { saveRequested = false }
        }
    }
    val saveRevision by context.receivedTransferStorage.revisions.collectAsState()
    if (share == null || closed) return
    val dismiss = {
        visible = false
        if (!ongoing) closed = true
    }
    val stop: () -> Unit = { scope.launch { error = (onStop?.invoke() ?: context.sharingRepository().stopPhotos(contactId, share.id)).exceptionOrNull()?.message } }
    // Keep the FAB composed behind the sheet so Material can animate both directions.
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("View photos") } },
        state = rememberTooltipState(),
    ) {
        FloatingActionButton(
            onClick = { visible = true },
            shape = FloatingActionButtonDefaults.shape,
            modifier = Modifier.animateFloatingActionButton(
                visible = !visible,
                alignment = Alignment.BottomEnd,
            ).then(if (visible) Modifier.clearAndSetSemantics {} else
                Modifier.testTag("reopen_photo_share").semantics {
                    stateDescription = when (share.status) {
                        PhotoShareStatus.Invited -> "Photo sharing invitation"
                        PhotoShareStatus.Active -> "Sharing ${share.photos.size} photos"
                        else -> "Photo sharing ended"
                    }
                }),
        ) {
            BadgedBox(badge = {
                if (share.photos.isNotEmpty()) Badge { Text(share.photos.size.toString()) }
            }) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "View photos")
            }
        }
    }
    if (!visible) return
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp).testTag("photo_share"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Photos", style = MaterialTheme.typography.headlineSmall)
            when (share.status) {
                PhotoShareStatus.Invited -> {
                    Text(if (share.outgoing) "Waiting for the other person to accept…" else "You are invited to share photos.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!share.outgoing) Button(shapes = ButtonDefaults.shapes(), onClick = { scope.launch {
                            error = (onAccept?.invoke() ?: context.sharingRepository().acceptPhotos(contactId, share.id)).exceptionOrNull()?.message
                        } }) { Text("Accept") }
                        DestructiveOutlinedButton(onClick = stop) { Text(if (share.outgoing) "Cancel" else "Decline") }
                    }
                }
                PhotoShareStatus.Stopped -> Text(if (share.remoteStopped) "The other person stopped sharing photos." else "You stopped sharing photos.")
                PhotoShareStatus.Declined -> Text("Photo sharing invitation declined.")
                PhotoShareStatus.Failed -> Text(share.error ?: "Photo sharing failed.")
                PhotoShareStatus.Active -> Text("Sharing photos", style = MaterialTheme.typography.labelMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (share.photos.isNotEmpty()) key(share.id) {
                val selectedIndex = share.photos.indexOfFirst { it.id.equals(share.selectedId, true) }.coerceAtLeast(0)
                val carousel = rememberCarouselState(initialItem = selectedIndex) { share.photos.size }
                val latestShare by rememberUpdatedState(share)
                val latestOnSelect by rememberUpdatedState(onSelect)
                var followingPeer by remember { mutableStateOf(false) }
                LaunchedEffect(share.selectedId, share.photos.map { it.id }) {
                    followingPeer = true
                    try {
                        if (carousel.currentItem != selectedIndex) carousel.animateScrollToItem(selectedIndex)
                    } finally { followingPeer = false }
                }
                LaunchedEffect(carousel) {
                    var didScroll = false
                    snapshotFlow { carousel.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
                        if (scrolling) didScroll = true
                        else {
                            val current = latestShare
                            val photo = current.photos.getOrNull(carousel.currentItem)
                            if (didScroll && !followingPeer && current.status == PhotoShareStatus.Active &&
                                photo != null && !photo.id.equals(current.selectedId, true)) {
                                error = (latestOnSelect?.invoke(photo.id) ?: context.sharingRepository()
                                    .selectPhoto(contactId, current.id, photo.id)).exceptionOrNull()?.message
                            }
                            didScroll = false
                        }
                    }
                }
                val index = carousel.currentItem.coerceIn(share.photos.indices)
                val selected = share.photos[index]
                val saveState = remember(share.id, selected.id, saveRevision) {
                    context.receivedTransferStorage.state(SessionPrefs.preferenceAccount(context), "photo:${share.id}:${selected.id}")
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    HorizontalUncontainedCarousel(
                        state = carousel,
                        itemWidth = if (share.photos.size == 1) maxWidth else (maxWidth - 32.dp).coerceAtLeast(1.dp),
                        itemSpacing = 8.dp,
                        flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(carousel),
                        modifier = Modifier.fillMaxWidth().testTag("photo_carousel"),
                    ) { itemIndex ->
                        val photo = share.photos[itemIndex]
                        val bitmap by produceState<Bitmap?>(null, photo.localPath) {
                            value = withContext(Dispatchers.IO) { photo.localPath?.let(::decodeSharedPreview) }
                        }
                        Box(Modifier.fillMaxWidth().height(320.dp).maskClip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { scope.launch { carousel.animateScrollToItem(itemIndex) } }
                            .semantics { this.selected = itemIndex == index }
                            .testTag("photo_carousel_item_${photo.id}"), contentAlignment = Alignment.Center) {
                            if (bitmap != null) Image(bitmap!!.asImageBitmap(), photo.name,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                when {
                                    photo.previewError != null -> Text(photo.previewError, color = MaterialTheme.colorScheme.error)
                                    photo.localPath != null -> Text("Preview unavailable.")
                                    else -> { LinearWavyProgressIndicator(Modifier.fillMaxWidth()); Text("Receiving photo…") }
                                }
                            }
                        }
                    }
                }
                Text(selected.name, style = MaterialTheme.typography.bodyMedium)
                Text("${index + 1} / ${share.photos.size}", style = MaterialTheme.typography.labelMedium)
                if (selected.previewError != null && share.status == PhotoShareStatus.Active) OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { scope.launch {
                    error = (onSelect?.invoke(selected.id) ?: context.sharingRepository().selectPhoto(contactId, share.id, selected.id)).exceptionOrNull()?.message
                } }) { Text("Retry photo") }
                if (selected.originalPending) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                if (selected.localPath != null) FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = {
                    savePhotoId = selected.id; savedPhotoId = null
                    if (TransferPrefs.read(context).photoTree != null || hasDefaultTransferStorageAccess(context)) requestSave()
                    else savePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }, enabled = !selected.originalPending && !saveRequested) {
                    Text(if (selected.originalPending || saveRequested) "Saving…" else if (savedPhotoId == selected.id || saveState.uri != null) "Saved" else "Save photo")
                }
                selected.originalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                saveState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (preparing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            if (share.status == PhotoShareStatus.Active) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shapes = ButtonDefaults.shapes(), enabled = !preparing && share.photos.size < 32, onClick = { addPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (preparing) "Preparing…" else "Add photos")
                }
                DestructiveOutlinedButton(onClick = stop) { Text("Stop sharing") }
            }
            if (!ongoing) TextButton(onClick = dismiss) { Text("Close") }
        }
    }
}

private fun decodeSharedPreview(path: String, maxDimension: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

internal fun decodeFileTransferThumbnail(path: String): Bitmap? {
    val decoded = decodeSharedPreview(path, maxDimension = 256) ?: return null
    return try {
        orientSharedPhoto(File(path), decoded).also { if (it !== decoded) decoded.recycle() }
    } catch (_: Exception) { decoded.recycle(); null }
}

private fun prepareSharedPhotos(context: Context, uris: List<Uri>): List<PhotoToShare> {
    require(uris.size in 1..32) { "Choose up to 32 photos at once." }
    val prepared = mutableListOf<PhotoToShare>()
    try {
        for (uri in uris) prepared += prepareSharedPhoto(context, uri)
        return prepared
    } catch (failure: Exception) {
        prepared.forEach { it.originalPath?.let(::File)?.delete() }
        throw failure
    }
}

private fun stageSharedFile(context: Context, uri: Uri): Pair<File, String> {
    val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } }.getOrNull() ?: uri.lastPathSegment ?: "shared-file"
    require(MsnFileContext.validName(name)) { "The file name is unsupported." }
    require(!MsnFileContext.isExecutable(name)) { "Executable files cannot be shared." }
    val area = File(context.cacheDir, "outgoing-files").apply { mkdirs() }
    val target = File.createTempFile("send-", ".bin", area)
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                total += count
                require(total <= MsnFileContext.MAX_FILE_BYTES) { "Files larger than 2 GB are unsupported." }
                output.write(buffer, 0, count)
            }
        } }
        return target to name
    } catch (failure: Exception) { target.delete(); throw failure }
}

internal fun prepareSharedPhoto(context: Context, uri: Uri): PhotoToShare {
    val (file, name) = stageSharedFile(context, uri)
    var keepOriginal = false
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported image." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }))
        val bitmap = try { orientSharedPhoto(file, decoded) } catch (failure: Exception) { decoded.recycle(); throw failure }
        if (bitmap !== decoded) decoded.recycle()
        fun scaled(max: Int): ByteArray {
            val ratio = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
            val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            return try { ByteArrayOutputStream().use { output -> check(resized.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.toByteArray() } }
            finally { if (resized !== bitmap) resized.recycle() }
        }
        val hash = MessageDigest.getInstance("SHA-1").apply { file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; update(buffer, 0, n) }
        } }.digest()
        return try { PhotoToShare(name, MsnBase64.encode(hash), scaled(96), scaled(1600), file.absolutePath).also { keepOriginal = true } } finally { bitmap.recycle() }
    } finally { if (!keepOriginal) file.delete() }
}

private fun orientSharedPhoto(file: File, source: Bitmap): Bitmap {
    val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            else -> return source
        }
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
