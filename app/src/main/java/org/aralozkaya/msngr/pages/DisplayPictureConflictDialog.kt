@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aralozkaya.msngr.DisplayPictureConflict
import org.aralozkaya.msngr.DisplayPictureSyncState
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.displayPictureSync
import org.aralozkaya.msngr.rememberAvatarImage
import org.aralozkaya.msngr.ui.theme.ownDisplayPicture

@Composable
internal fun DisplayPictureSyncHost(repository: MessengerRepository) {
    val context = LocalContext.current
    val sync = remember(context) { context.displayPictureSync }
    val state by sync.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            sync.consumeNotice(notice)
        }
    }
    val conflict = state.conflict ?: return
    if (!SessionPrefs.canOpenAuthenticatedScreens(context) || SessionPrefs.preferenceAccount(context) != conflict.account) return
    DisplayPictureConflictDialog(conflict, state,
        onKeepLocal = { sync.choose(repository, conflict.id, incoming = false) },
        onUseIncoming = { sync.choose(repository, conflict.id, incoming = true) },
        onDismiss = { sync.dismiss(conflict.id) })
}

@Composable
internal fun DisplayPictureConflictDialog(
    conflict: DisplayPictureConflict,
    state: DisplayPictureSyncState,
    onKeepLocal: () -> Unit,
    onUseIncoming: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Display picture changed") },
        modifier = Modifier.testTag("display-picture-conflict"),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Messenger has a different display picture. Choose which one to keep.")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ComparisonPicture("Old picture", "On this device", conflict.local.uri,
                        Modifier.weight(1f).testTag("old-display-picture"))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Changed to", modifier = Modifier.size(24.dp))
                    ComparisonPicture("New picture", "From Messenger", conflict.incomingUri,
                        Modifier.weight(1f).testTag("incoming-display-picture"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onKeepLocal, enabled = !state.busy, shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier.weight(1f).testTag("keep-local-picture")) { Text("Keep local") }
                    Button(onClick = onUseIncoming, enabled = !state.busy, shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier.weight(1f).testTag("use-incoming-picture")) { Text("Use incoming") }
                }
                if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Updating display picture…", style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("picture-sync-error")) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Not now") }
        },
    )
}

@Composable
private fun ComparisonPicture(title: String, subtitle: String, uri: String?, modifier: Modifier) {
    val image = rememberAvatarImage(uri, highResolution = true)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Surface(shape = MaterialTheme.shapes.ownDisplayPicture, color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(contentAlignment = Alignment.Center) {
                image.painter?.let { Image(it, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: if (image.isLoading) CircularProgressIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Default.Person, contentDescription = "No display picture", modifier = Modifier.size(48.dp))
            }
        }
        Text(if (uri == null) "No picture" else subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
