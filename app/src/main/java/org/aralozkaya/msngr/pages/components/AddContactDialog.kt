@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.isValidContactEmail
import org.aralozkaya.msngr.data.validateContactAddress

@Composable
internal fun AddContactDialog(
    ownEmail: String,
    contacts: List<Contact>,
    onAddContact: suspend (String, String) -> Result<Unit>,
    onAdded: () -> Unit,
    onDismiss: () -> Unit,
    isMock: Boolean = false,
    sourceBounds: Rect? = null,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var closingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val busy = submitting || closingAction != null
    var error by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fallbackError = stringResource(R.string.contact_add_failed)
    var focusManager by remember { mutableStateOf<FocusManager?>(null) }
    val validEmail = isValidContactEmail(email)

    fun close(action: () -> Unit) {
        focusManager?.clearFocus()
        closingAction = action
    }

    fun dismiss() {
        if (!busy) close(onDismiss)
    }

    fun submit() {
        if (busy || !validEmail) return
        val address = try {
            validateContactAddress(email, ownEmail, contacts)
        } catch (failure: IllegalArgumentException) {
            error = failure.message
            return
        }
        submitting = true
        error = null
        snackbarHostState.currentSnackbarData?.dismiss()
        focusManager?.clearFocus()
        scope.launch {
            try {
                val result = onAddContact(address, message)
                if (result.isSuccess) close(onAdded)
                else scope.launch {
                    snackbarHostState.showActionSnackbar(result.exceptionOrNull()?.message ?: fallbackError, isError = true)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                scope.launch { snackbarHostState.showActionSnackbar(fallbackError, isError = true) }
            } finally {
                submitting = false
            }
        }
    }

    ContainerTransformDialog(
        sourceBounds = sourceBounds,
        sourceIcon = Icons.Default.PersonAdd,
        sourceLabel = stringResource(R.string.contacts_action_add_contact),
        closing = closingAction != null,
        busy = busy,
        onDismissRequest = ::dismiss,
        onClosed = { closingAction?.invoke() },
    ) {
        val dialogFocusManager = LocalFocusManager.current
        SideEffect { focusManager = dialogFocusManager }
        Box(Modifier.fillMaxSize().testTag("add-contact-modal")) {
            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeContent,
                snackbarHost = { ActionSnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(stringResource(R.string.contacts_action_add_contact),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = ::dismiss, enabled = !busy,
                                modifier = Modifier.testTag("add-contact-dismiss")) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        },
                        actions = {
                            TextButton(onClick = ::submit, enabled = submitting || (!busy && validEmail),
                                modifier = Modifier.testTag("add-contact-submit")) {
                                LoadingButtonContent(
                                    loading = submitting,
                                    label = stringResource(R.string.contact_add_busy),
                                    indicatorModifier = Modifier.testTag("add-contact-spinner"),
                                ) {
                                    Text(stringResource(R.string.contact_add_submit))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState(), enabled = !busy),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(if (isMock) R.string.contact_add_mock_hint else R.string.contact_add_invitation_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.contact_add_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("add-contact-email"),
                        isError = error != null,
                    )
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it; error = null },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.request_optional_message)) },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("add-contact-message"),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("add-contact-error")
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }
}
