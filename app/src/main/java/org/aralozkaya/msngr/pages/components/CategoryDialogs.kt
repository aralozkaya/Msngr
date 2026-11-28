@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.EmoticonPanel
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.EmoticonStyle
import org.aralozkaya.msngr.MessengerPlusEditorController
import org.aralozkaya.msngr.MessengerPlusProfileField
import org.aralozkaya.msngr.MsnEmoticonText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactCategory
import org.aralozkaya.msngr.data.FAVORITES_CATEGORY_ID
import org.aralozkaya.msngr.data.OTHER_CONTACTS_CATEGORY_ID
import org.aralozkaya.msngr.data.validateCategoryName
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.rememberEmoticonSheetState
import org.aralozkaya.msngr.withExpandedMsnMenuTokens

@Composable
internal fun CategoryEditorDialog(
    existingCategories: List<ContactCategory>,
    contacts: List<Contact>,
    category: ContactCategory? = null,
    sourceBounds: Rect? = null,
    onCreate: suspend (name: String, memberIds: List<String>) -> Result<String>,
    onUpdate: suspend (categoryId: String, newName: String, memberIds: Set<String>) -> Result<Unit>,
    onSaved: (isEdit: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val nameEditorController = remember { MessengerPlusEditorController() }
    val emoticonSheet = rememberEmoticonSheetState()
    val selectableContacts = remember(contacts) {
        contacts.filter { !it.isBlocked && (it.isGroup || it.isForwardListContact) }
            .sortedWith(
                compareBy<Contact> { it.isGroup }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.messengerPlusPlainText() },
            )
    }
    val initialSelected = remember(category?.id, selectableContacts) {
        if (category == null) emptyList()
        else selectableContacts.filter { category.id in it.categoryIds }.map(Contact::id)
    }
    var nameValue by rememberSaveable(category?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(category?.name.orEmpty()))
    }
    var selectedIds by rememberSaveable(category?.id) { mutableStateOf(initialSelected) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var closingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val busy = saving || closingAction != null
    var focusManager by remember { mutableStateOf<FocusManager?>(null) }

    LaunchedEffect(busy) {
        if (busy) {
            emoticonSheet.dismiss()
            nameEditorController.hideKeyboard()
        }
    }

    fun close(action: () -> Unit) {
        emoticonSheet.dismiss()
        nameEditorController.hideKeyboard()
        focusManager?.clearFocus()
        if (category == null) closingAction = action else action()
    }

    fun dismiss() {
        if (!busy) close(onDismiss)
    }

    fun submit() {
        if (busy) return
        val normalized = try {
            validateCategoryName(
                nameValue.text.withExpandedMsnMenuTokens(),
                existingCategories,
                excludeCategoryId = category?.id,
            )
        } catch (failure: IllegalArgumentException) {
            error = failure.message
            return
        }
        saving = true
        error = null
        emoticonSheet.dismiss()
        nameEditorController.hideKeyboard()
        focusManager?.clearFocus()
        scope.launch {
            try {
                val result = if (category == null) {
                    onCreate(normalized, selectedIds).map { Unit }
                } else {
                    onUpdate(category.id, normalized, selectedIds.toSet())
                }
                result.getOrThrow()
                close { onSaved(category != null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: resources.getString(R.string.category_save_failed)
            } finally {
                saving = false
            }
        }
    }

    val editorContent: @Composable BoxScope.() -> Unit = {
        val dialogFocusManager = LocalFocusManager.current
        SideEffect { focusManager = dialogFocusManager }
        KeyboardSheetScaffold {
            EmoticonPanel(
                state = emoticonSheet,
                emoticonStyle = emoticonSettings.style,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("category-editor-dialog"),
            ) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeContent,
                    snackbarHost = { ActionSnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(
                                        if (category == null) R.string.category_create_title else R.string.category_edit_title,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = ::dismiss,
                                    enabled = !busy,
                                    modifier = Modifier.testTag("category-editor-dismiss"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.common_close),
                                    )
                                }
                            },
                            actions = {
                                TextButton(
                                    onClick = ::submit,
                                    enabled = saving || (!busy && nameValue.text.withExpandedMsnMenuTokens().isNotBlank()),
                                    modifier = Modifier.testTag("category-editor-confirm"),
                                ) {
                                    LoadingButtonContent(
                                        loading = saving,
                                        label = stringResource(
                                            if (category == null) R.string.category_create_action else R.string.common_save,
                                        ),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (category == null) R.string.category_create_action else R.string.common_save,
                                            ),
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MessengerPlusProfileField(
                            value = nameValue,
                            onValueChange = { next ->
                                val sanitized = next.text.replace('\r', ' ').replace('\n', ' ')
                                nameValue = if (sanitized == next.text) next else next.copy(text = sanitized)
                                error = null
                            },
                            controller = nameEditorController,
                            label = stringResource(R.string.category_name_label),
                            emoticonSettings = emoticonSettings,
                            messengerPlusEnabled = false,
                            enabled = !busy,
                            emoticonSheet = emoticonSheet,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("category-name-input"),
                        )
                        error?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("category-editor-error"),
                            )
                        }
                        Text(
                            text = stringResource(R.string.category_members_header),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (selectableContacts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.category_members_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag("category-members-list"),
                            ) {
                                items(selectableContacts, key = Contact::id) { item ->
                                    val checked = item.id in selectedIds
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .toggleable(
                                                value = checked,
                                                enabled = !busy,
                                                role = Role.Checkbox,
                                                onValueChange = { isChecked ->
                                                    selectedIds = if (isChecked) {
                                                        (selectedIds + item.id).distinct()
                                                    } else {
                                                        selectedIds - item.id
                                                    }
                                                },
                                            )
                                            .heightIn(min = 56.dp)
                                            .padding(horizontal = 4.dp, vertical = 8.dp)
                                            .testTag("category-member-${item.id}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        if (item.isGroup) {
                                            Icon(
                                                imageVector = Icons.Default.Group,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.displayName.messengerPlusPlainText(),
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = if (item.isGroup) {
                                                    stringResource(R.string.common_group_singular)
                                                } else {
                                                    item.email
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ContactEditorDialogFrame(
        creating = category == null,
        sourceBounds = sourceBounds,
        sourceIcon = Icons.Default.CreateNewFolder,
        sourceLabel = stringResource(R.string.contacts_action_create_category),
        closingAction = closingAction,
        busy = busy,
        onDismissRequest = ::dismiss,
        content = editorContent,
    )
}

@Composable
internal fun ContactCategoriesDialog(
    contact: Contact,
    categories: List<ContactCategory>,
    onSave: suspend (Set<String>) -> Result<Unit>,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val customCategories = remember(categories) { categories.filterNot(ContactCategory::isBuiltIn) }
    val validCustomIds = remember(customCategories) { customCategories.map(ContactCategory::id).toSet() }
    var selectedCategoryIds by rememberSaveable(contact.id) {
        mutableStateOf(
            contact.categoryIds.filter { it == FAVORITES_CATEGORY_ID || it in validCustomIds },
        )
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun toggle(categoryId: String, checked: Boolean) {
        selectedCategoryIds = if (checked) {
            (selectedCategoryIds + categoryId).distinct()
        } else {
            selectedCategoryIds - categoryId
        }
        error = null
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("contact-categories-dialog"),
        title = {
            Text(
                stringResource(
                    R.string.contact_categories_dialog_title,
                    contact.displayName.messengerPlusPlainText(),
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .testTag("contact-categories-list"),
                ) {
                    item(key = FAVORITES_CATEGORY_ID) {
                        val isFavoriteChecked = FAVORITES_CATEGORY_ID in selectedCategoryIds
                        CategoryCheckboxRow(
                            label = stringResource(R.string.contacts_section_favorites),
                            checked = isFavoriteChecked,
                            enabled = !saving,
                            modifier = Modifier.testTag("assign-category-$FAVORITES_CATEGORY_ID"),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onCheckedChange = { toggle(FAVORITES_CATEGORY_ID, it) },
                        )
                    }
                    items(customCategories, key = ContactCategory::id) { category ->
                        val isChecked = category.id in selectedCategoryIds
                        CategoryCheckboxRow(
                            label = category.name,
                            checked = isChecked,
                            enabled = !saving,
                            emoticonStyle = emoticonSettings.style,
                            modifier = Modifier.testTag("assign-category-${category.id}"),
                            onCheckedChange = { toggle(category.id, it) },
                        )
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val nextCategories = selectedCategoryIds.toSet() - OTHER_CONTACTS_CATEGORY_ID
                            onSave(nextCategories).getOrThrow()
                            onSaved()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: resources.getString(R.string.contact_categories_update_failed)
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("contact-categories-save"),
            ) {
                LoadingButtonContent(loading = saving, label = stringResource(R.string.common_save)) {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun CategoryCheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    emoticonStyle: EmoticonStyle? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingIcon?.invoke()
        if (emoticonStyle != null) {
            MsnEmoticonText(
                text = label,
                emoticonStyle = emoticonStyle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
internal fun DeleteCategoryDialog(
    category: ContactCategory,
    onDelete: suspend () -> Result<Unit>,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        modifier = Modifier.testTag("delete-category-dialog"),
        title = { Text(stringResource(R.string.category_delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MsnEmoticonText(
                    text = stringResource(R.string.category_delete_dialog_message, category.name),
                    emoticonStyle = emoticonSettings.style,
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (deleting) return@Button
                    deleting = true
                    error = null
                    scope.launch {
                        try {
                            onDelete().getOrThrow()
                            onDeleted()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: resources.getString(R.string.category_delete_failed)
                        } finally {
                            deleting = false
                        }
                    }
                },
                enabled = !deleting,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag("delete-category-confirm"),
            ) {
                LoadingButtonContent(loading = deleting, label = stringResource(R.string.common_delete)) {
                    Text(stringResource(R.string.common_delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
