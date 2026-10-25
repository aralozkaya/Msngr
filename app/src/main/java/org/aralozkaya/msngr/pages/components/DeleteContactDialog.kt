@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
internal fun DeleteContactDialog(
    contact: Contact,
    onDeleteContact: suspend (Boolean) -> Result<Unit>,
    onDeleted: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    isMock: Boolean = false,
) {
    var block by rememberSaveable(contact.id) { mutableStateOf(false) }
    var busy by remember(contact.id) { mutableStateOf(false) }
    var error by remember(contact.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fallbackError = stringResource(R.string.contact_delete_failed)

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = onDeleteContact(block)
                if (result.isSuccess) onDeleted(block)
                else error = result.exceptionOrNull()?.message ?: fallbackError
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = fallbackError
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
            modifier = Modifier.fillMaxWidth().testTag("delete-contact-modal"),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null,
                    tint = AlertDialogDefaults.iconContentColor, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.contact_delete_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.contact_delete_message, contact.displayName.messengerPlusPlainText()),
                    color = AlertDialogDefaults.textContentColor)
                if (isMock) Text(stringResource(R.string.contact_delete_mock_hint),
                    color = AlertDialogDefaults.textContentColor)
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("delete-contact-block")
                        .toggleable(value = block, enabled = !busy && !contact.isBlocked, role = Role.Checkbox,
                            onValueChange = { block = it; error = null }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = block, onCheckedChange = null, enabled = !busy && !contact.isBlocked)
                    Text(stringResource(if (contact.isBlocked) R.string.contact_delete_already_blocked
                        else R.string.contact_delete_also_block), modifier = Modifier.padding(start = 8.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("delete-contact-error").semantics { liveRegion = LiveRegionMode.Polite })
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(stringResource(R.string.contact_add_cancel))
                    }
                    Button(onClick = ::submit, modifier = Modifier.testTag("delete-contact-submit"),
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) {
                        LoadingButtonContent(
                            loading = busy,
                            label = stringResource(R.string.contact_delete_busy),
                            indicatorModifier = Modifier.testTag("delete-contact-spinner"),
                        ) {
                            Text(stringResource(R.string.contact_delete_submit))
                        }
                    }
                }
            }
        }
    }
}
