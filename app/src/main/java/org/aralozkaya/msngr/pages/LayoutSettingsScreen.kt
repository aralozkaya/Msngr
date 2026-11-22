@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.AppearancePrefs
import org.aralozkaya.msngr.LayoutSettings
import org.aralozkaya.msngr.LocalLayoutSettings
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.UiDensity
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.ContactRow
import org.aralozkaya.msngr.pages.components.ExpandableSettingsGroup
import org.aralozkaya.msngr.pages.components.PageContentList
import kotlin.math.roundToInt

@Composable
fun LayoutSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val appearance by remember(context) { AppearancePrefs.state(context) }.collectAsState()
    val layout = appearance.layoutSettings
    val favoritesLayout = appearance.favoritesLayoutSettings
    val isCompact = layout.isCompact
    val isFavoritesCompact = favoritesLayout.isCompact
    val longStatus = stringResource(R.string.layout_preview_status_long)
    val shortStatus = stringResource(R.string.layout_preview_status_short)
    val previewFavoriteContacts = remember(longStatus) {
        listOf(
            Contact(
                id = "preview-1",
                nickname = "Alice",
                email = "alice@example.com",
                presence = Presence.Online,
                statusMessage = longStatus,
                unreadCount = 2,
            ),
        )
    }
    val previewOtherContacts = remember(shortStatus) {
        listOf(
            Contact(
                id = "preview-2",
                nickname = "Bob",
                email = "bob@example.com",
                presence = Presence.Busy,
                statusMessage = shortStatus,
            ),
            Contact(
                id = "preview-3",
                nickname = "Charlie",
                email = "charlie@example.com",
                presence = Presence.Away,
            ),
        )
    }

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        settingsSectionHeader(R.string.settings_layout_preview_header, first = true)
        item(key = "layout-preview") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("layout_preview"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (appearance.useSeparateFavoritesLayout) {
                        LayoutPreviewSection(
                            title = stringResource(R.string.contacts_section_favorites),
                            contacts = previewFavoriteContacts,
                            layoutSettings = favoritesLayout,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (favoritesLayout.isCompact) 18.dp else 22.dp),
                                )
                            },
                        )
                        LayoutPreviewSection(
                            title = stringResource(R.string.contacts_section_other),
                            contacts = previewOtherContacts,
                            layoutSettings = layout,
                        )
                    } else {
                        LayoutPreviewSection(
                            title = stringResource(R.string.contacts_section_other),
                            contacts = previewFavoriteContacts + previewOtherContacts,
                            layoutSettings = layout,
                        )
                    }
                }
            }
        }
        settingsSectionSpacer()
        item(key = "size-slider-card") {
            DensitySliderCard(
                density = appearance.uiDensity,
                onDensitySelected = { AppearancePrefs.setUiDensity(context, it) },
                valueTag = "layout_size_value",
                sliderTag = "layout_size_slider",
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
            )
        }
        settingsSectionSpacer()
        settingsSectionHeader(R.string.settings_layout_compact_options_header)
        item(key = "compact-inline-status") {
            SegmentedListItem(
                checked = appearance.compactInlineStatusMessage,
                onCheckedChange = { AppearancePrefs.setCompactInlineStatusMessage(context, it) },
                enabled = isCompact,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compact_inline_status_switch"),
                trailingContent = {
                    Switch(
                        checked = appearance.compactInlineStatusMessage,
                        onCheckedChange = null,
                        enabled = isCompact,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_layout_inline_status_supporting))
                },
                content = { Text(stringResource(R.string.settings_layout_inline_status_title)) },
            )
        }
        item(key = "compact-hide-presence-text") {
            SegmentedListItem(
                checked = appearance.compactHidePresenceText,
                onCheckedChange = { AppearancePrefs.setCompactHidePresenceText(context, it) },
                enabled = isCompact,
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compact_hide_presence_text_switch"),
                trailingContent = {
                    Switch(
                        checked = appearance.compactHidePresenceText,
                        onCheckedChange = null,
                        enabled = isCompact,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_layout_hide_presence_text_supporting))
                },
                content = { Text(stringResource(R.string.settings_layout_hide_presence_text_title)) },
            )
        }
        settingsSectionSpacer()
        item(key = "compact-search-and-sort") {
            SegmentedListItem(
                checked = appearance.compactSearchAndSort,
                onCheckedChange = { AppearancePrefs.setCompactSearchAndSort(context, it) },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compact_search_and_sort_switch"),
                trailingContent = {
                    Switch(
                        checked = appearance.compactSearchAndSort,
                        onCheckedChange = null,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_layout_compact_search_sort_supporting))
                },
                content = { Text(stringResource(R.string.settings_layout_compact_search_sort_title)) },
            )
        }
        settingsSectionSpacer()
        settingsSectionHeader(R.string.settings_layout_favorites_header)
        item(key = "favorites-layout-group") {
            ExpandableSettingsGroup(
                expanded = appearance.useSeparateFavoritesLayout,
                master = { shapes ->
                    SegmentedListItem(
                        checked = appearance.useSeparateFavoritesLayout,
                        onCheckedChange = { AppearancePrefs.setUseSeparateFavoritesLayout(context, it) },
                        shapes = shapes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("favorites_separate_layout_switch"),
                        trailingContent = {
                            Switch(
                                checked = appearance.useSeparateFavoritesLayout,
                                onCheckedChange = null,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_layout_favorites_separate_supporting))
                        },
                        content = { Text(stringResource(R.string.settings_layout_favorites_separate_title)) },
                    )
                },
            ) { _, colors ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    DensitySliderCard(
                        density = appearance.favoritesUiDensity,
                        onDensitySelected = { AppearancePrefs.setFavoritesUiDensity(context, it) },
                        valueTag = "favorites_layout_size_value",
                        sliderTag = "favorites_layout_size_slider",
                        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 4),
                        colors = colors,
                    )
                    SegmentedListItem(
                        checked = appearance.favoritesCompactInlineStatusMessage,
                        onCheckedChange = { AppearancePrefs.setFavoritesCompactInlineStatusMessage(context, it) },
                        enabled = isFavoritesCompact,
                        shapes = ListItemDefaults.segmentedShapes(index = 2, count = 4),
                        colors = colors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("favorites_compact_inline_status_switch"),
                        trailingContent = {
                            Switch(
                                checked = appearance.favoritesCompactInlineStatusMessage,
                                onCheckedChange = null,
                                enabled = isFavoritesCompact,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_layout_inline_status_supporting))
                        },
                        content = { Text(stringResource(R.string.settings_layout_inline_status_title)) },
                    )
                    SegmentedListItem(
                        checked = appearance.favoritesCompactHidePresenceText,
                        onCheckedChange = { AppearancePrefs.setFavoritesCompactHidePresenceText(context, it) },
                        enabled = isFavoritesCompact,
                        shapes = ListItemDefaults.segmentedShapes(index = 3, count = 4),
                        colors = colors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("favorites_compact_hide_presence_text_switch"),
                        trailingContent = {
                            Switch(
                                checked = appearance.favoritesCompactHidePresenceText,
                                onCheckedChange = null,
                                enabled = isFavoritesCompact,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_layout_hide_presence_text_supporting))
                        },
                        content = { Text(stringResource(R.string.settings_layout_hide_presence_text_title)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutPreviewSection(
    title: String,
    contacts: List<Contact>,
    layoutSettings: LayoutSettings,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    CompositionLocalProvider(LocalLayoutSettings provides layoutSettings) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ContactSectionHeader(
                title = title,
                count = contacts.size,
                expanded = true,
                onToggle = {},
                leadingIcon = leadingIcon,
            )
            if (layoutSettings.isCompact) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        contacts.forEachIndexed { index, contact ->
                            ContactRow(
                                index = index + 1,
                                count = contacts.size + 1,
                                contact = contact,
                                onClick = null,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ListItemDefaults.SegmentedGap),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    contacts.forEachIndexed { index, contact ->
                        ContactRow(
                            index = index + 1,
                            count = contacts.size + 1,
                            contact = contact,
                            onClick = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DensitySliderCard(
    density: UiDensity,
    onDensitySelected: (UiDensity) -> Unit,
    valueTag: String,
    sliderTag: String,
    shapes: androidx.compose.material3.ListItemShapes,
    colors: androidx.compose.material3.ListItemColors = ListItemDefaults.segmentedColors(),
) {
    SegmentedListItem(
        shapes = shapes,
        colors = colors,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_layout_size_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = density.displayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(valueTag),
                )
            }
            val maxIndex = UiDensity.entries.lastIndex.toFloat()
            val normalizedValue = density.ordinal / maxIndex
            val sliderState = rememberSliderState(
                value = normalizedValue,
                steps = UiDensity.entries.size - 2,
            ).apply {
                value = normalizedValue
            }
            Slider(
                state = sliderState,
                onValueChange = { raw ->
                    val index = (raw * maxIndex).roundToInt().coerceIn(0, UiDensity.entries.lastIndex)
                    onDensitySelected(UiDensity.entries[index])
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(sliderTag),
            )
        }
    }
}
