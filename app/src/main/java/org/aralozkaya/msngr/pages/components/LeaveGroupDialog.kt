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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R

@Composable
internal fun LeaveGroupDialog(
    groupName: String,
    onLeave: suspend () -> Result<Unit>,
    onLeft: () -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fallbackError = stringResource(R.string.group_leave_failed)

    fun submit() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = onLeave()
                if (result.isSuccess) onLeft()
                else onError(result.exceptionOrNull()?.message ?: fallbackError)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError(fallbackError)
            } finally {
                busy = false
            }
        }
    }

    BasicAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("group-leave-modal"),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.group_leave_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor)
                Text(stringResource(R.string.group_leave_message, groupName),
                    color = AlertDialogDefaults.textContentColor)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss, enabled = !busy,
                        modifier = Modifier.testTag("group-leave-dismiss")) {
                        Text(stringResource(R.string.contact_add_cancel))
                    }
                    Button(onClick = ::submit,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.testTag("group-leave-submit")) {
                        LoadingButtonContent(
                            loading = busy,
                            label = stringResource(R.string.group_leave_busy),
                            indicatorModifier = Modifier.testTag("group-leave-spinner"),
                        ) {
                            Text(stringResource(R.string.group_leave_submit))
                        }
                    }
                }
            }
        }
    }
}
