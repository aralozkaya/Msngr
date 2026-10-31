@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import org.aralozkaya.msngr.TransferPrefs
import org.aralozkaya.msngr.hasDefaultTransferStorageAccess
import org.aralozkaya.msngr.pages.components.PageContentList

@Composable
internal fun TransferSettingsScreen(innerPadding: PaddingValues, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    val settings by remember(context) { TransferPrefs.state(context) }.collectAsState()
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionAction by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) when (permissionAction) {
            "files" -> TransferPrefs.update(context) { it.copy(autoSaveFiles = true) }
            "photos" -> TransferPrefs.update(context) { it.copy(autoSavePhotos = true) }
            "default" -> TransferPrefs.update(context) { it.copy(fileTree = null, fileTreeName = null) }
            "photo_default" -> TransferPrefs.update(context) { it.copy(photoTree = null, photoTreeName = null) }
        } else error = "Storage access is needed for the default folders on Android 9 and earlier. You can choose a custom folder instead."
        permissionAction = null
    }
    fun withDefaultAccess(action: String, change: () -> Unit) {
        if (hasDefaultTransferStorageAccess(context)) change()
        else { permissionAction = action; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }
    fun authorizeFolder(uri: Uri?, photo: Boolean) {
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission })
            val name = TransferPrefs.treeName(context, uri)
            TransferPrefs.update(context) {
                if (photo) it.copy(photoTree = uri.toString(), photoTreeName = name)
                else it.copy(fileTree = uri.toString(), fileTreeName = name)
            }
        } catch (_: Exception) { error = "The folder could not be authorized. Choose it again and allow access." }
    }
    val fileFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { authorizeFolder(it, photo = false) }
    val photoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { authorizeFolder(it, photo = true) }
    fun initialFolder(tree: String?): Uri? = tree?.let(Uri::parse)?.let {
        DocumentsContract.buildDocumentUri(it.authority, DocumentsContract.getTreeDocumentId(it))
    }
    PageContentList(innerPadding = innerPadding, scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item {
            SegmentedListItem(checked = settings.autoAccept,
                onCheckedChange = { checked -> TransferPrefs.update(context) { it.copy(autoAccept = checked) } },
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                modifier = Modifier.fillMaxWidth().testTag("auto_accept_transfers"),
                trailingContent = { Switch(settings.autoAccept, onCheckedChange = null) },
                supportingContent = { Text("Accept incoming file requests and photo sharing invitations automatically.") },
                content = { Text("Automatically accept transfers") })
        }
        item {
            Column {
                val parentShapes = ListItemDefaults.segmentedShapes(0, if (settings.autoSaveFiles) 2 else 1)
                SegmentedListItem(checked = settings.autoSaveFiles,
                    onCheckedChange = { checked ->
                        val change = { TransferPrefs.update(context) { it.copy(autoSaveFiles = checked) } }
                        if (checked && (settings.fileTree == null || (settings.autoSavePhotos && settings.photoTree == null))) withDefaultAccess("files", change) else change()
                    },
                    shapes = parentShapes.copy(selectedShape = parentShapes.shape),
                    modifier = Modifier.fillMaxWidth().testTag("auto_save_files"),
                    trailingContent = { Switch(settings.autoSaveFiles, onCheckedChange = null) },
                    supportingContent = { Text("Save received files to the file folder after they finish downloading.") },
                    content = { Text("Automatically save received files") })
                if (settings.autoSaveFiles) Spacer(Modifier.height(ListItemDefaults.SegmentedGap))
                AnimatedVisibility(visible = settings.autoSaveFiles) {
                    val shapes = ListItemDefaults.segmentedShapes(1, 2)
                    val colors = ListItemDefaults.segmentedColors()
                    SegmentedListItem(checked = settings.autoSavePhotos,
                        onCheckedChange = { checked ->
                            val change = { TransferPrefs.update(context) { it.copy(autoSavePhotos = checked) } }
                            if (checked && settings.photoTree == null) withDefaultAccess("photos", change) else change()
                        },
                        shapes = shapes.copy(selectedShape = shapes.shape),
                        colors = colors.copy(containerColor = colors.selectedContainerColor, contentColor = colors.selectedContentColor),
                        modifier = Modifier.fillMaxWidth().semantics { role = Role.Checkbox }.testTag("auto_save_photos"),
                        trailingContent = { Checkbox(settings.autoSavePhotos, onCheckedChange = null) },
                        content = { Text("Include shared photos") })
                }
            }
        }
        settingsSectionSpacer()
        settingsSectionHeader("Save locations")
        item(key = "file-folder") {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                SegmentedListItem(onClick = { fileFolder.launch(initialFolder(settings.fileTree)) },
                    onLongClick = { menuExpanded = true }, onLongClickLabel = "Location options",
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    modifier = Modifier.fillMaxWidth().testTag("transfer_file_folder"),
                    supportingContent = { Text(settings.fileTreeName ?: "Downloads (default)") },
                    trailingContent = { Icon(Icons.Default.FolderOpen, contentDescription = "Choose file folder") },
                    content = { Text("File folder") })
                RestoreLocationMenu(menuExpanded, settings.fileTree != null, "transfer_default_folder",
                    onDismiss = { menuExpanded = false }, onRestore = {
                        val change = { TransferPrefs.update(context) { it.copy(fileTree = null, fileTreeName = null) } }
                        if (settings.autoSaveFiles) withDefaultAccess("default", change) else change()
                    })
            }
        }
        item(key = "photo-folder") {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                SegmentedListItem(onClick = { photoFolder.launch(initialFolder(settings.photoTree)) },
                    onLongClick = { menuExpanded = true }, onLongClickLabel = "Location options",
                    shapes = ListItemDefaults.segmentedShapes(1, 2),
                    modifier = Modifier.fillMaxWidth().testTag("transfer_photo_folder"),
                    supportingContent = { Text(settings.photoTreeName ?: "DCIM/Msngr (default)") },
                    trailingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Choose photo folder") },
                    content = { Text("Photo folder") })
                RestoreLocationMenu(menuExpanded, settings.photoTree != null, "transfer_default_photo_folder",
                    onDismiss = { menuExpanded = false }, onRestore = {
                        val change = { TransferPrefs.update(context) { it.copy(photoTree = null, photoTreeName = null) } }
                        if (settings.autoSaveFiles && settings.autoSavePhotos) withDefaultAccess("photo_default", change) else change()
                    })
            }
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Could not set save location") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

@Composable
private fun RestoreLocationMenu(expanded: Boolean, enabled: Boolean, testTag: String,
    onDismiss: () -> Unit, onRestore: () -> Unit) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            DropdownMenuItem(
                text = { Text("Restore default location") },
                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                shape = MenuDefaults.standaloneItemShape,
                modifier = Modifier.testTag(testTag),
                enabled = enabled,
                onClick = { onDismiss(); onRestore() },
            )
        }
    }
}
