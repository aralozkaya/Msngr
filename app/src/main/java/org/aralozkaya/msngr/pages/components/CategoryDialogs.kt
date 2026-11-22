@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactCategory
import org.aralozkaya.msngr.data.FAVORITES_CATEGORY_ID
import org.aralozkaya.msngr.data.OTHER_CONTACTS_CATEGORY_ID
import org.aralozkaya.msngr.data.validateCategoryName
import org.aralozkaya.msngr.messengerPlusPlainText

@Composable
internal fun CategoryEditorDialog(
    existingCategories: List<ContactCategory>,
    contacts: List<Contact>,
    category: ContactCategory? = null,
    onCreate: suspend (name: String, memberIds: List<String>) -> Result<String>,
    onUpdate: suspend (categoryId: String, newName: String, memberIds: Set<String>) -> Result<Unit>,
    onSaved: (isEdit: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
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
    var name by rememberSaveable(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var selectedIds by rememberSaveable(category?.id) { mutableStateOf(initialSelected) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun submit() {
        if (saving) return
        val normalized = try {
            validateCategoryName(name, existingCategories, excludeCategoryId = category?.id)
        } catch (failure: IllegalArgumentException) {
            error = failure.message
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                val result = if (category == null) {
                    onCreate(normalized, selectedIds).map { Unit }
                } else {
                    onUpdate(category.id, normalized, selectedIds.toSet())
                }
                result.getOrThrow()
                onSaved(category != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: resources.getString(R.string.category_save_failed)
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("category-editor-dialog"),
        title = {
            Text(
                stringResource(
                    if (category == null) R.string.category_create_title else R.string.category_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.category_name_label)) },
                    singleLine = true,
                    enabled = !saving,
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
                            .heightIn(max = 280.dp)
                            .testTag("category-members-list"),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        itemsIndexed(selectableContacts, key = { _, item -> item.id }) { index, item ->
                            val checked = item.id in selectedIds
                            SegmentedListItem(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedIds = if (isChecked) {
                                        (selectedIds + item.id).distinct()
                                    } else {
                                        selectedIds - item.id
                                    }
                                },
                                enabled = !saving,
                                shapes = ListItemDefaults.segmentedShapes(index, selectableContacts.size),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("category-member-${item.id}"),
                                leadingContent = if (item.isGroup) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Group,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                } else null,
                                trailingContent = {
                                    Checkbox(checked = checked, onCheckedChange = null, enabled = !saving)
                                },
                                supportingContent = {
                                    Text(
                                        text = if (item.isGroup) {
                                            stringResource(R.string.common_group_singular)
                                        } else {
                                            item.email
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                content = {
                                    Text(
                                        text = item.displayName.messengerPlusPlainText(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submit,
                enabled = !saving,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("category-editor-confirm"),
            ) {
                LoadingButtonContent(
                    loading = saving,
                    label = stringResource(if (category == null) R.string.category_create_action else R.string.common_save),
                ) {
                    Text(stringResource(if (category == null) R.string.category_create_action else R.string.common_save))
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
internal fun ContactCategoriesDialog(
    contact: Contact,
    categories: List<ContactCategory>,
    onSave: suspend (Set<String>) -> Result<Unit>,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val customCategories = remember(categories) { categories.filterNot(ContactCategory::isBuiltIn) }
    var selected by rememberSaveable(contact.id) {
        mutableStateOf(contact.categoryIds.filterNot { it == OTHER_CONTACTS_CATEGORY_ID })
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val showOtherContactsOption = !contact.isGroup
    val totalCount = 1 + customCategories.size + (if (showOtherContactsOption) 1 else 0)

    fun toggle(categoryId: String, checked: Boolean) {
        val next = selected.toMutableSet()
        if (categoryId == OTHER_CONTACTS_CATEGORY_ID) {
            if (checked) {
                // Other Contacts means categoryless: remove all custom categories while keeping Favorites as-is.
                next.removeAll { it != FAVORITES_CATEGORY_ID }
            }
        } else {
            if (checked) next.add(categoryId) else next.remove(categoryId)
        }
        selected = next.toList()
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
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    item(key = FAVORITES_CATEGORY_ID) {
                        val isChecked = FAVORITES_CATEGORY_ID in selected
                        SegmentedListItem(
                            checked = isChecked,
                            onCheckedChange = { toggle(FAVORITES_CATEGORY_ID, it) },
                            enabled = !saving,
                            shapes = ListItemDefaults.segmentedShapes(0, totalCount),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("assign-category-$FAVORITES_CATEGORY_ID"),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            trailingContent = {
                                Checkbox(checked = isChecked, onCheckedChange = null, enabled = !saving)
                            },
                            content = { Text(stringResource(R.string.contacts_section_favorites)) },
                        )
                    }
                    itemsIndexed(customCategories, key = { _, cat -> cat.id }) { index, category ->
                        val isChecked = category.id in selected
                        SegmentedListItem(
                            checked = isChecked,
                            onCheckedChange = { toggle(category.id, it) },
                            enabled = !saving,
                            shapes = ListItemDefaults.segmentedShapes(index + 1, totalCount),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("assign-category-${category.id}"),
                            trailingContent = {
                                Checkbox(checked = isChecked, onCheckedChange = null, enabled = !saving)
                            },
                            content = {
                                Text(
                                    text = category.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (showOtherContactsOption) {
                        item(key = OTHER_CONTACTS_CATEGORY_ID) {
                            val isChecked = selected.none { it != FAVORITES_CATEGORY_ID }
                            SegmentedListItem(
                                checked = isChecked,
                                onCheckedChange = { toggle(OTHER_CONTACTS_CATEGORY_ID, it) },
                                enabled = !saving,
                                shapes = ListItemDefaults.segmentedShapes(totalCount - 1, totalCount),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("assign-category-$OTHER_CONTACTS_CATEGORY_ID"),
                                trailingContent = {
                                    Checkbox(checked = isChecked, onCheckedChange = null, enabled = !saving)
                                },
                                content = { Text(stringResource(R.string.contacts_section_other)) },
                            )
                        }
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
                            onSave(selected.toSet() - OTHER_CONTACTS_CATEGORY_ID).getOrThrow()
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
internal fun DeleteCategoryDialog(
    category: ContactCategory,
    onDelete: suspend () -> Result<Unit>,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        modifier = Modifier.testTag("delete-category-dialog"),
        title = { Text(stringResource(R.string.category_delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.category_delete_dialog_message, category.name))
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
