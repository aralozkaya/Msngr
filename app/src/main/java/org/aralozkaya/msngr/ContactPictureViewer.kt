@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.protocol.DisplayPictureFormat

@Composable
internal fun ContactPictureViewer(pictureUri: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val image = rememberAvatarImage(pictureUri, highResolution = true)
    val painter = image.painter
    val format by produceState<DisplayPictureFormat?>(null, pictureUri) {
        value = withContext(Dispatchers.IO) {
            runCatching { DisplayPictureFormat.detect(context.readProfilePictureBytes(pictureUri)) }.getOrNull()
        }
    }
    val mimeType = format?.mimeType ?: "image/png"
    val savePicture = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { destination ->
        if (destination != null) {
            scope.launch {
                saving = true
                val saved = context.saveContactPicture(pictureUri, destination)
                saving = false
                Toast.makeText(
                    context,
                    if (saved) "Display picture saved" else "Could not save display picture. Please try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text("Display picture") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !saving, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Close, contentDescription = "Close picture")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            ) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        painter != null -> Image(
                            painter = painter,
                            contentDescription = "$name display picture",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        image.isLoading -> CircularWavyProgressIndicator()
                        else -> Text("This display picture could not be loaded.")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        if (!saving) {
                            val extension = format?.extension ?: return@FilledTonalButton
                            savePicture.launch("${profilePictureFileStem(name)}-display-picture.$extension")
                        }
                    },
                    enabled = painter != null && !image.isLoading && format != null,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    LoadingButtonContent(
                        loading = saving,
                        label = stringResource(R.string.picture_save_busy),
                        indicatorModifier = Modifier.testTag("picture-save-loading"),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text("Download picture", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

internal suspend fun Context.saveContactPicture(pictureUri: String, destination: Uri): Boolean {
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readProfilePictureBytes(pictureUri)
            requireNotNull(DisplayPictureFormat.detect(bytes))
            contentResolver.openOutputStream(destination, "wt")?.use { output ->
                output.write(bytes)
            } ?: error("Unable to open destination")
        }.isSuccess
    }
}

internal fun profilePictureFileStem(name: String): String = name
    .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
    .trim('.', '-')
    .take(64)
    .ifBlank { "contact" }
