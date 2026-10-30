@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.validateGroupInvitees
import org.aralozkaya.msngr.data.validateGroupName
import org.aralozkaya.msngr.messengerPlusPlainText
import java.util.Locale

@Composable
internal fun GroupEditorDialog(
    ownEmail: String,
    contacts: List<Contact>,
    group: Contact? = null,
    onCreate: suspend (String) -> Result<String>,
    onInvite: suspend (String, List<String>, String) -> Result<Unit>,
    onFinished: (groupId: String, invitationsSent: Boolean) -> Unit,
    onDismiss: () -> Unit,
    sourceBounds: Rect? = null,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    var createdId by rememberSaveable { mutableStateOf<String?>(group?.id) }
    var attemptedInvites by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var closingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val busy = submitting || closingAction != null
    var invitationAction by rememberSaveable { mutableStateOf(group != null) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var focusManager by remember { mutableStateOf<FocusManager?>(null) }
    // Match the space reserved above the outlined field for its floating label.
    val emailLabelTopPadding = with(LocalDensity.current) {
        MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2
    }
    val choices = contacts.filter { !it.isGroup && it.isForwardListContact && !it.isBlocked &&
        !it.email.equals(ownEmail, true) && group?.members.orEmpty().none { member -> member.email.equals(it.email, true) } }
        .distinctBy { it.email.lowercase(Locale.ROOT) }
        .sortedBy { it.displayName.messengerPlusPlainText().lowercase() }

    fun close(action: () -> Unit) {
        focusManager?.clearFocus()
        if (group == null) closingAction = action else action()
    }

    fun dismiss() {
        if (!busy) close(onDismiss)
    }

    fun addEmail() {
        if (busy || email.isBlank()) return
        try {
            val address = validateGroupInvitees(listOf(email), ownEmail, group?.members.orEmpty()).single()
            if (selected.none { it.equals(address, true) }) selected = selected + address
            email = ""
            error = null
        } catch (failure: IllegalArgumentException) {
            error = failure.message
        }
    }

    fun submit() {
        if (busy) return
        val addresses: List<String>
        try {
            if (createdId == null) validateGroupName(name)
            val requested = validateGroupInvitees(selected + listOf(email).filter(String::isNotBlank), ownEmail)
            addresses = validateGroupInvitees(if (attemptedInvites) requested.filterNot { email ->
                group?.members.orEmpty().any { it.email.equals(email, true) }
            } else requested, ownEmail, group?.members.orEmpty())
            if (group != null && !attemptedInvites) require(addresses.isNotEmpty()) { "Choose at least one person to invite." }
        } catch (failure: IllegalArgumentException) { error = failure.message; return }
        submitting = true; error = null
        snackbarHostState.currentSnackbarData?.dismiss()
        focusManager?.clearFocus()
        scope.launch {
            try {
                val id = createdId ?: onCreate(name).getOrThrow().also { createdId = it }
                if (addresses.isNotEmpty()) {
                    attemptedInvites = true
                    onInvite(id, addresses, message).getOrThrow()
                }
                close { onFinished(id, addresses.isNotEmpty()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (group == null && createdId != null) invitationAction = true
                val message = if (group == null && createdId != null)
                    "Your group was created. ${failure.message ?: "Invitations could not be sent."} Retry to send the invitations."
                else failure.message ?: "Could not update this group. Please try again."
                scope.launch { snackbarHostState.showActionSnackbar(message, isError = true) }
            } finally { submitting = false }
        }
    }

    val editorFields: @Composable (Modifier) -> Unit = { modifier ->
        // The dialog has its own focus owner, separate from the activity's.
        val dialogFocusManager = LocalFocusManager.current
        SideEffect { focusManager = dialogFocusManager }
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Column(modifier.verticalScroll(rememberScrollState(), enabled = !busy).testTag("group-editor"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (group != null) Text("Invite people to \"${group.displayName.messengerPlusPlainText()}\"",
                    style = MaterialTheme.typography.titleMedium)
                if (group == null) OutlinedTextField(name, { name = it; error = null },
                    label = { Text("Group name") }, singleLine = true,
                    enabled = !busy && createdId == null,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("group-name"))
                Text(if (group == null) "Choose members now, or create an empty group and invite people later." else "People join after accepting your invitation.")
                OutlinedTextField(email, { email = it; error = null }, label = { Text("Email address") },
                    enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addEmail() }),
                    trailingIcon = {
                        IconButton(onClick = {if (email.isBlank()) showContactPicker = true else addEmail()}, enabled = !busy,
                            modifier = Modifier.testTag("group-add-email")) {
                            Icon(if (email.isBlank()) Icons.Default.Contacts else Icons.Default.Add, contentDescription = "Add email")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("group-invite-emails"))
                OutlinedTextField(message, { message = it; error = null },
                    label = { Text(stringResource(R.string.request_optional_message)) },
                    enabled = !busy, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("group-invitation-message"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("group-editor-error")) }
                Text("Selected members (${selected.size})", style = MaterialTheme.typography.titleSmall)
                if (selected.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        selected.forEachIndexed { index, address ->
                            key(address) {
                                val contact = choices.firstOrNull { it.email.equals(address, true) }
                                SegmentedListItem(
                                    shapes = ListItemDefaults.segmentedShapes(index, selected.size),
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("group-selected-$address"),
                                    supportingContent = contact?.let { { Text(address) } },
                                    trailingContent = {
                                        IconButton(onClick = { selected = selected - address; error = null }, enabled = !busy,
                                            modifier = Modifier.testTag("group-remove-$address")) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove $address")
                                        }
                                    },
                                    content = { Text(contact?.displayName?.messengerPlusPlainText() ?: address) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val submitButton: @Composable () -> Unit = {
        Button(onClick = ::submit,
            enabled = submitting || (!busy && if (invitationAction) selected.isNotEmpty() || email.isNotBlank() else name.isNotBlank()),
            modifier = Modifier.testTag("group-editor-submit")) {
            LoadingButtonContent(
                loading = submitting,
                label = if (createdId == null) "Creating group…" else "Sending invitations…",
                indicatorModifier = Modifier.testTag("group-editor-loading"),
            ) {
                Text(if (invitationAction) "Send" else "Create")
            }
        }
    }

    val editorContent: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize().testTag("group-editor-modal")) {
            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeContent,
                snackbarHost = { ActionSnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = { Text(if (group == null) "Create a group" else "Invite members", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = ::dismiss, enabled = !busy,
                                modifier = Modifier.testTag("group-editor-dismiss")) {
                                Icon(Icons.Default.Close, contentDescription = if (invitationAction) "Done" else "Close")
                            }
                        },
                        actions = { submitButton() },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                },
            ) { padding ->
                editorFields(Modifier.fillMaxSize().padding(padding))
            }
        }
    }

    if (group == null) {
        ContainerTransformDialog(
            sourceBounds = sourceBounds,
            sourceIcon = Icons.Default.GroupAdd,
            sourceLabel = stringResource(R.string.contacts_action_create_group),
            closing = closingAction != null,
            busy = busy,
            onDismissRequest = ::dismiss,
            onClosed = { closingAction?.invoke() },
            content = editorContent,
        )
    } else {
        BasicAlertDialog(
            modifier = Modifier.fillMaxSize(),
            onDismissRequest = ::dismiss,
            properties = DialogProperties(
                dismissOnBackPress = !busy,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(content = editorContent)
            }
        }
    }

    if (showContactPicker) GroupContactPickerDialog(
        contacts = choices,
        selected = selected,
        onSelectionChange = { address, checked ->
            selected = if (checked) selected + address else selected.filterNot { it.equals(address, true) }
            error = null
        },
        onDismiss = { showContactPicker = false },
    )
}

@Composable
private fun GroupContactPickerDialog(
    contacts: List<Contact>,
    selected: List<String>,
    onSelectionChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose contacts") },
        text = {
            if (contacts.isEmpty()) {
                Text("No contacts available to invite.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .testTag("group-contact-picker")
                        .selectableGroup(),
                ) {
                    itemsIndexed(contacts, key = { _, contact -> contact.email }) { index, contact ->
                        val checked = selected.any { it.equals(contact.email, true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    onValueChange = { onSelectionChange(contact.email, it) },
                                    role = Role.Checkbox
                                )
                                .height(56.dp)
                                .padding(horizontal = 16.dp)
                                .testTag("group-select-${contact.email}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(contact.email)
                                Text(
                                    style = MaterialTheme.typography.bodySmall,
                                    text = contact.displayName.messengerPlusPlainText()
                                )
                            }
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss,
                modifier = Modifier.testTag("group-contact-picker-done")) { Text("Done") }
        },
    )
}
