@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.messengerPlusPlainText

@Composable
internal fun GroupBlockingDialog(
    group: Contact,
    blocked: Boolean,
    onSetBlocked: suspend () -> Result<Unit>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var busy by remember(group.id, blocked) { mutableStateOf(false) }
    var failed by remember(group.id, blocked) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BasicAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    ) {
        Surface(Modifier.fillMaxWidth().testTag("group-block-modal"),
            shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(if (blocked) R.string.group_block_title else R.string.group_unblock_title),
                    style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(if (blocked) R.string.group_block_message else R.string.group_unblock_message,
                    group.displayName.messengerPlusPlainText()))
                if (failed) Text(stringResource(R.string.group_block_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("group-block-error").semantics { liveRegion = LiveRegionMode.Polite })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) { Text("Cancel") }
                    Button(onClick = {
                        if (!busy) {
                            busy = true
                            failed = false
                            scope.launch {
                                try {
                                    if (onSetBlocked().isSuccess) onChanged() else failed = true
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true }
                                finally { busy = false }
                            }
                        }
                    }, modifier = Modifier.testTag("group-block-submit"), shapes = ButtonDefaults.shapes(),
                        colors = if (blocked) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
                        LoadingButtonContent(busy, stringResource(R.string.group_block_busy),
                            indicatorModifier = Modifier.testTag("group-block-spinner")) {
                            Text(stringResource(if (blocked) R.string.group_block_action else R.string.group_unblock_action))
                        }
                    }
                }
            }
        }
    }
}
