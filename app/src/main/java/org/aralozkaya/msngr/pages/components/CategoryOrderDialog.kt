@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.ContactCategory
import org.aralozkaya.msngr.data.FAVORITES_CATEGORY_ID
import org.aralozkaya.msngr.data.GROUPS_CATEGORY_ID
import org.aralozkaya.msngr.data.OTHER_CONTACTS_CATEGORY_ID
import org.aralozkaya.msngr.data.resolveOrderedCategoryIds

@Composable
internal fun CategoryOrderDialog(
    initialOrder: List<String>,
    customCategories: List<ContactCategory>,
    onSave: (order: List<String>, hidden: Set<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    initialHidden: Set<String> = emptySet(),
) {
    val orderedIds = remember(initialOrder, customCategories) {
        mutableStateListOf<String>().apply {
            addAll(resolveOrderedCategoryIds(initialOrder, customCategories))
        }
    }
    var hiddenIds by remember(initialHidden) { mutableStateOf(initialHidden) }
    val customById = remember(customCategories) { customCategories.associateBy(ContactCategory::id) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableMapOf<String, Int>() }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in orderedIds.indices || toIndex !in orderedIds.indices) return
        val item = orderedIds.removeAt(fromIndex)
        orderedIds.add(toIndex, item)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("category-order-dialog"),
        title = { Text(stringResource(R.string.settings_category_order_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_category_order_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState(), enabled = draggingId == null)
                        .testTag("category-order-list"),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    orderedIds.forEachIndexed { index, id ->
                        key(id) {
                            val label = when (id) {
                                FAVORITES_CATEGORY_ID -> stringResource(R.string.contacts_section_favorites)
                                GROUPS_CATEGORY_ID -> stringResource(R.string.contacts_section_groups_plural)
                                OTHER_CONTACTS_CATEGORY_ID -> stringResource(R.string.contacts_section_other)
                                else -> customById[id]?.name ?: id
                            }
                            val visible = id !in hiddenIds
                            val visibilityDescription = stringResource(
                                R.string.settings_category_order_toggle_visibility,
                                label,
                            )
                            val isDragging = draggingId == id
                            val dragModifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                    scaleX = if (isDragging) 1.02f else 1f
                                    scaleY = if (isDragging) 1.02f else 1f
                                    alpha = if (visible || isDragging) 1f else 0.6f
                                }
                                .onSizeChanged { itemHeights[id] = it.height }
                                .pointerInput(id) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            draggingId = id
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggingId = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragOffsetY = 0f
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount
                                            val currentIndex = orderedIds.indexOf(id)
                                            if (currentIndex < 0) return@detectVerticalDragGestures
                                            val rowHeight = (itemHeights[id] ?: 120).coerceAtLeast(48).toFloat()
                                            val threshold = rowHeight * 0.55f
                                            if (dragOffsetY > threshold && currentIndex < orderedIds.lastIndex) {
                                                moveItem(currentIndex, currentIndex + 1)
                                                dragOffsetY -= rowHeight
                                            } else if (dragOffsetY < -threshold && currentIndex > 0) {
                                                moveItem(currentIndex, currentIndex - 1)
                                                dragOffsetY += rowHeight
                                            }
                                        },
                                    )
                                }

                            SegmentedListItem(
                                shapes = ListItemDefaults.segmentedShapes(index, orderedIds.size),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(dragModifier)
                                    .testTag("category-order-item-$id"),
                                leadingContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DragHandle,
                                            contentDescription = stringResource(
                                                R.string.settings_category_order_drag_handle,
                                                label,
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (id == FAVORITES_CATEGORY_ID) {
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    Switch(
                                        checked = visible,
                                        onCheckedChange = { isVisible ->
                                            hiddenIds = if (isVisible) hiddenIds - id else hiddenIds + id
                                        },
                                        modifier = Modifier
                                            .testTag("category-visibility-$id")
                                            .semantics {
                                                contentDescription = visibilityDescription
                                            },
                                    )
                                },
                                content = {
                                    Text(
                                        text = label,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onReset()
                        orderedIds.clear()
                        orderedIds.addAll(resolveOrderedCategoryIds(emptyList(), customCategories))
                        hiddenIds = emptySet()
                    },
                    modifier = Modifier.testTag("category-order-reset"),
                ) {
                    Text(stringResource(R.string.settings_category_order_reset))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = { onSave(orderedIds.toList(), hiddenIds) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.testTag("category-order-save"),
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        },
        dismissButton = null,
    )
}
