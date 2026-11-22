@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.AppearanceColorOption
import org.aralozkaya.msngr.AppearancePrefs
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.ColorMode
import org.aralozkaya.msngr.ContactInvitationPrefs
import org.aralozkaya.msngr.DisplayPictureSyncBehavior
import org.aralozkaya.msngr.DisplayPictureSyncPrefs
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.MESSENGER_PLUS_DISCLAIMER_RES
import org.aralozkaya.msngr.MessengerPlusPrefs
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.ThemeMode
import org.aralozkaya.msngr.WinkCatalogActivity
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.displayPictureSync
import org.aralozkaya.msngr.notifications.BackgroundConnectionController
import org.aralozkaya.msngr.notifications.BackgroundDeliveryPrefs
import org.aralozkaya.msngr.numberSentenceResource
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.pages.components.ExpandableSettingsGroup
import org.aralozkaya.msngr.providerDisplayName
import org.aralozkaya.msngr.updates.updateManager

enum class SettingsDestination(
    @param:StringRes val titleRes: Int,
) {
    Root(R.string.settings_destination_root),
    Appearance(R.string.settings_destination_appearance),
    Emoticons(R.string.settings_destination_emoticons),
    MessengerPlus(R.string.settings_destination_messenger_plus),
    ContactInvitations(R.string.settings_destination_contact_invitations),
    Transfers(R.string.settings_destination_transfers),
    Notifications(R.string.settings_destination_notifications),
    InAppNotifications(R.string.settings_destination_in_app_notifications),
    CustomizedNotifications(R.string.settings_destination_customized_notifications),
    Connection(R.string.settings_destination_connection),
    ConnectionInformation(R.string.settings_destination_connection_information),
    Updates(R.string.settings_destination_updates),
    About(R.string.settings_destination_about),
    Licenses(R.string.settings_destination_licenses),
}

internal enum class SettingsCategory(
    @param:StringRes val titleRes: Int,
    val presentation: SettingsCategoryPresentation,
    val destination: SettingsDestination? = null,
    val testTag: String? = null,
    @param:StringRes val supportingTextRes: Int? = null,
) {
    Appearance(
        titleRes = R.string.settings_destination_appearance,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.Appearance,
    ),
    Emoticons(
        titleRes = R.string.settings_destination_emoticons,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.Emoticons,
    ),
    MessengerPlus(
        titleRes = R.string.settings_destination_messenger_plus,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.MessengerPlus,
    ),
    ContactInvitations(
        titleRes = R.string.settings_destination_contact_invitations,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.ContactInvitations,
        testTag = "contact_invitation_settings",
    ),
    Transfers(
        titleRes = R.string.settings_destination_transfers,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.Transfers,
        testTag = "transfer_settings",
    ),
    Connection(
        titleRes = R.string.settings_destination_connection,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.Connection,
    ),
    Notifications(
        titleRes = R.string.settings_destination_notifications,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.Notifications,
        testTag = "notification_settings",
    ),
    InAppNotifications(
        titleRes = R.string.settings_destination_in_app_notifications,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.InAppNotifications,
        testTag = "in_app_notification_settings",
    ),
    ConnectionInformation(
        titleRes = R.string.settings_destination_connection_information,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.ConnectionInformation,
    ),
    About(
        titleRes = R.string.settings_destination_about,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.About,
    ),
    Updates(
        titleRes = R.string.settings_destination_updates,
        presentation = SettingsCategoryPresentation.Destination,
        destination = SettingsDestination.Updates,
        testTag = "update-settings",
    ),
    SignOutOtherSessions(
        titleRes = R.string.settings_sign_out_other_places,
        presentation = SettingsCategoryPresentation.Toggle,
        testTag = "sign_out_other_sessions_on_sign_in",
    ),
    BackgroundConnection(
        titleRes = R.string.settings_keep_connected,
        presentation = SettingsCategoryPresentation.Toggle,
        testTag = "keep_messenger_connected",
    ),
    DeleteAllChatHistories(
        titleRes = R.string.settings_delete_all_histories,
        presentation = SettingsCategoryPresentation.DestructiveAction,
        testTag = "delete_all_chat_histories",
    ),
}

internal enum class SettingsCategoryPresentation {
    Destination,
    Toggle,
    DestructiveAction,
}

private val primarySettingsCategories = listOf(
    SettingsCategory.Appearance,
    SettingsCategory.Emoticons,
    SettingsCategory.MessengerPlus,
    SettingsCategory.ContactInvitations,
    SettingsCategory.Transfers,
    SettingsCategory.Notifications,
    SettingsCategory.Connection,
    SettingsCategory.Updates,
    SettingsCategory.About,
)

private val connectionSettingsCategories = listOf(
    SettingsCategory.SignOutOtherSessions,
    SettingsCategory.BackgroundConnection,
    SettingsCategory.ConnectionInformation,
)

private val destructiveSettingsCategories = SettingsCategory.entries.filter {
    it.presentation == SettingsCategoryPresentation.DestructiveAction
}

@Composable
fun SettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onDestinationSelected: (SettingsDestination) -> Unit,
    onDeleteAllChatHistories: () -> Unit,
) {
    var showDeleteHistoryConfirmation by rememberSaveable { mutableStateOf(false) }

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        primarySettingsCategories.forEachIndexed { index, category ->
            settingsCategory(
                category = category,
                index = index,
                count = primarySettingsCategories.size,
                onClick = { onDestinationSelected(checkNotNull(category.destination)) },
            )
        }
        settingsSectionSpacer()
        destructiveSettingsCategories.forEach { category ->
            settingsCategory(
                category = category,
                onClick = { showDeleteHistoryConfirmation = true },
            )
        }
    }

    if (showDeleteHistoryConfirmation) {
        DeleteHistoryDialog(
            onDismissRequest = { showDeleteHistoryConfirmation = false },
            onConfirm = {
                showDeleteHistoryConfirmation = false
                onDeleteAllChatHistories()
            },
        )
    }
}

@Composable
fun ConnectionSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onDestinationSelected: (SettingsDestination) -> Unit,
) {
    val context = LocalContext.current
    val account = SessionPrefs.preferenceAccount(context)
    var usesSamePictureAndStatusEverywhere by remember(account) {
        mutableStateOf(DisplayPictureSyncPrefs.usesSamePictureAndStatusEverywhere(context, account))
    }
    var pictureBehavior by remember(account) { mutableStateOf(DisplayPictureSyncPrefs.behavior(context)) }
    var showPictureBehavior by rememberSaveable { mutableStateOf(false) }
    var keepsBackgroundConnection by rememberSaveable {
        mutableStateOf(BackgroundDeliveryPrefs.keepsConnectionAlive(context))
    }
    var signsOutOtherSessions by rememberSaveable {
        mutableStateOf(SessionPrefs.signsOutOtherSessionsOnSignIn(context))
    }

    fun updateBackgroundConnection(enabled: Boolean) {
        keepsBackgroundConnection = enabled
        BackgroundDeliveryPrefs.setKeepsConnectionAlive(context, enabled)
        if (enabled) {
            BackgroundConnectionController.startIfEnabled(context)
        } else {
            BackgroundConnectionController.stop(context)
        }
    }

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        connectionSettingsCategories.forEachIndexed { index, category ->
            settingsCategory(
                category = category,
                index = index,
                count = connectionSettingsCategories.size,
                toggleChecked = when (category) {
                    SettingsCategory.BackgroundConnection -> keepsBackgroundConnection
                    SettingsCategory.SignOutOtherSessions -> signsOutOtherSessions
                    else -> false
                },
                onClick = { onDestinationSelected(checkNotNull(category.destination)) },
                onToggle = { enabled ->
                    when (category) {
                        SettingsCategory.BackgroundConnection -> {
                            updateBackgroundConnection(enabled)
                        }
                        SettingsCategory.SignOutOtherSessions -> {
                            SessionPrefs.setSignOutOtherSessionsOnSignIn(context, enabled)
                            signsOutOtherSessions = enabled
                        }
                        else -> Unit
                    }
                },
            )
        }
        settingsSectionSpacer()
        item(key = "roaming-profile") {
            ExpandableSettingsGroup(
                expanded = usesSamePictureAndStatusEverywhere,
                master = { shapes ->
                    SegmentedListItem(
                        checked = usesSamePictureAndStatusEverywhere,
                        onCheckedChange = { enabled ->
                            DisplayPictureSyncPrefs.setUsesSamePictureAndStatusEverywhere(context, enabled, account)
                            usesSamePictureAndStatusEverywhere = enabled
                            if (!enabled) showPictureBehavior = false
                            runCatching { context.appContainer.messengerRepository }.getOrNull()?.let {
                                context.displayPictureSync.onRoamingPreferenceChanged(it, enabled)
                            }
                        },
                        shapes = shapes,
                        modifier = Modifier.settingsRow().testTag("roaming-profile-setting"),
                        trailingContent = { Switch(checked = usesSamePictureAndStatusEverywhere, onCheckedChange = null) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_roaming_supporting_you_singular))
                        },
                    ) { Text(stringResource(R.string.settings_roaming_title)) }
                },
            ) { shapes, colors ->
                SegmentedListItem(
                    onClick = { showPictureBehavior = true },
                    shapes = shapes,
                    colors = colors,
                    modifier = Modifier.settingsRow().testTag("display-picture-sync-setting"),
                    supportingContent = { Text(pictureBehavior.displayLabel()) },
                ) { Text(stringResource(R.string.settings_picture_changes_title)) }
            }
        }
    }
    if (showPictureBehavior) {
        AlertDialog(
            onDismissRequest = { showPictureBehavior = false },
            title = { Text(stringResource(R.string.settings_picture_changes_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.settings_picture_changes_body))
                    Column(Modifier.selectableGroup()) {
                        DisplayPictureSyncBehavior.entries.forEach { behavior ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .selectable(
                                        selected = pictureBehavior == behavior,
                                        onClick = {
                                            DisplayPictureSyncPrefs.setBehavior(context, behavior)
                                            pictureBehavior = behavior
                                            showPictureBehavior = false
                                            runCatching { context.appContainer.messengerRepository }.getOrNull()?.let {
                                                context.displayPictureSync.requestRefresh(it)
                                            }
                                        },
                                        role = Role.RadioButton,
                                    )
                                    .testTag("picture-sync-${behavior.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RadioButton(selected = pictureBehavior == behavior, onClick = null)
                                Column {
                                    Text(behavior.displayLabel())
                                    if (behavior == DisplayPictureSyncBehavior.AskEveryTime) {
                                        Text(
                                            text = stringResource(R.string.common_default),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPictureBehavior = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun LazyListScope.settingsCategory(
    category: SettingsCategory,
    onClick: () -> Unit,
    index: Int = 0,
    count: Int = 1,
    toggleChecked: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
) {
    item(key = category.name) {
        val modifier = category.testTag?.let { tag ->
            Modifier.settingsRow().testTag(tag)
        } ?: Modifier.settingsRow()

        when (category.presentation) {
            SettingsCategoryPresentation.Destination -> SegmentedListItem(
                onClick = onClick,
                shapes = ListItemDefaults.segmentedShapes(index, count),
                modifier = modifier,
                content = { Text(stringResource(category.titleRes)) },
                trailingContent = if (category == SettingsCategory.Updates) {
                    {
                        val updateState by LocalContext.current.updateManager.state.collectAsState()
                        if (updateState.hasUpdate) UpdateBadge(Modifier.testTag("updates-settings-badge"))
                    }
                } else null,
            )
            SettingsCategoryPresentation.Toggle -> SegmentedListItem(
                checked = toggleChecked,
                onCheckedChange = onToggle,
                shapes = ListItemDefaults.segmentedShapes(index, count),
                modifier = modifier,
                trailingContent = {
                    Switch(
                        checked = toggleChecked,
                        onCheckedChange = null,
                    )
                },
                supportingContent = category.supportingTextRes?.let { res ->
                    { Text(stringResource(res)) }
                },
                content = { Text(stringResource(category.titleRes)) },
            )
            SettingsCategoryPresentation.DestructiveAction -> FilledTonalButton(
                onClick = onClick,
                shapes = ButtonDefaults.shapes(),
                modifier = modifier,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(category.titleRes))
            }
        }
    }
}

@Composable
fun ContactInvitationSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val settings by remember(context) { ContactInvitationPrefs.state(context) }.collectAsState()

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "use-decline-defaults") {
            ExpandableSettingsGroup(expanded = settings.useDeclineDefaults, master = { shapes ->
                SegmentedListItem(
                    checked = settings.useDeclineDefaults,
                    onCheckedChange = { ContactInvitationPrefs.setUseDeclineDefaults(context, it) },
                    shapes = shapes,
                    modifier = Modifier.settingsRow().testTag("use_contact_invitation_decline_defaults"),
                    trailingContent = {
                        Switch(checked = settings.useDeclineDefaults, onCheckedChange = null)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.settings_decline_default_supporting))
                    },
                    content = { Text(stringResource(R.string.settings_decline_default_title)) },
                )
            }) { shapes, colors ->
                SegmentedListItem(
                    checked = settings.blockDeclinedInvitations,
                    onCheckedChange = { ContactInvitationPrefs.setBlockDeclinedInvitations(context, it) },
                    shapes = shapes,
                    colors = colors,
                    modifier = Modifier.settingsRow().testTag("block_declined_contact_invitations"),
                    trailingContent = {
                        Checkbox(
                            checked = settings.blockDeclinedInvitations,
                            onCheckedChange = null,
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.settings_decline_block_supporting_you_singular)) },
                    content = { Text(stringResource(R.string.contact_request_block)) },
                )
            }
        }
    }
}

@Composable
fun MessengerPlusSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val settings by remember(context) { MessengerPlusPrefs.state(context) }.collectAsState()

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "rendering") {
            ExpandableSettingsGroup(expanded = !settings.enabled, highlighted = false, master = { shapes ->
                SegmentedListItem(
                    checked = settings.enabled,
                    onCheckedChange = { MessengerPlusPrefs.setEnabled(context, it) },
                    shapes = shapes,
                    modifier = Modifier.settingsRow().testTag("messenger_plus_enabled"),
                    trailingContent = {
                        Switch(checked = settings.enabled, onCheckedChange = null)
                    },
                    supportingContent = { Text(stringResource(MESSENGER_PLUS_DISCLAIMER_RES)) },
                    content = { Text(stringResource(R.string.settings_plus_rendering_title)) },
                )
            }) { shapes, colors ->
                SegmentedListItem(
                    checked = settings.shouldHideControlCharacters,
                    onCheckedChange = { MessengerPlusPrefs.setHideControlCharacters(context, it) },
                    shapes = shapes,
                    colors = colors,
                    modifier = Modifier.settingsRow().testTag("messenger_plus_hide_control_characters"),
                    trailingContent = {
                        Checkbox(
                            checked = settings.shouldHideControlCharacters,
                            onCheckedChange = null,
                        )
                    },
                    supportingContent = {
                        Text(stringResource(R.string.settings_plus_hide_codes_supporting))
                    },
                    content = { Text(stringResource(R.string.settings_plus_hide_codes_title)) },
                )
            }
        }
        item(key = "emotion-sounds-autoplay") {
            SegmentedListItem(
                checked = settings.playEmotionSounds,
                onCheckedChange = { MessengerPlusPrefs.setPlayEmotionSounds(context, it) },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier.settingsRow().testTag("emotion_sound_autoplay"),
                trailingContent = { Switch(checked = settings.playEmotionSounds, onCheckedChange = null) },
                supportingContent = { Text(stringResource(R.string.settings_emotion_sounds_auto_supporting)) },
                content = { Text(stringResource(R.string.settings_emotion_sounds_auto_title)) },
            )
        }
    }
}

@Composable
fun AppearanceSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val savedAppearance by remember(context) { AppearancePrefs.state(context) }.collectAsState()
    val appearance = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && savedAppearance.colorMode == ColorMode.Dynamic)
        savedAppearance.copy(colorMode = ColorMode.Default) else savedAppearance

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        settingsSectionHeader(R.string.settings_theme_header, first = true)
        ThemeMode.entries.forEachIndexed { index, mode ->
            item(key = "theme-${mode.name}") {
                SegmentedListItem(
                    selected = appearance.themeMode == mode,
                    onClick = { AppearancePrefs.setThemeMode(context, mode) },
                    shapes = ListItemDefaults.segmentedShapes(index, ThemeMode.entries.size),
                    modifier = Modifier.settingsRow(),
                    trailingContent = {
                        RadioButton(
                            selected = appearance.themeMode == mode,
                            onClick = null,
                        )
                    },
                    content = { Text(mode.displayLabel()) },
                )
            }
        }
        run {
            settingsSectionSpacer()
            settingsSectionHeader(R.string.settings_color_scheme_header)
            val colorModes = ColorMode.entries.filter { it != ColorMode.Dynamic || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            colorModes.forEachIndexed { index, mode ->
                item(key = "color-${mode.name}") {
                    AppearanceColorOption(mode, index, colorModes.size, appearance)
                }
            }
        }
        settingsSectionSpacer()
        settingsSectionHeader(R.string.settings_home_header)
        item(key = "show-ads") {
            SegmentedListItem(
                checked = appearance.showAds,
                onCheckedChange = { AppearancePrefs.setShowAds(context, it) },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                modifier = Modifier
                    .settingsRow()
                    .testTag("show_ads_setting"),
                trailingContent = {
                    Switch(
                        checked = appearance.showAds,
                        onCheckedChange = null,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_show_ads_supporting_you_singular))
                },
                content = { Text(stringResource(R.string.settings_show_ads_title)) },
            )
        }
    }
}

@Composable
fun EmoticonSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val settings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val winkSettings by remember(context) { org.aralozkaya.msngr.WinkPrefs.state(context) }.collectAsState()
    val styles = org.aralozkaya.msngr.EmoticonStyle.entries
    val previewEmoticon = org.aralozkaya.msngr.MSN_EMOTICON_SHORTCUTS.first()
    val behaviorItemCount = 3

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        settingsSectionHeader(R.string.settings_emoticon_style_header, first = true)
        styles.forEachIndexed { index, style ->
            item(key = "emoticon-style-${style.storageValue}") {
                SegmentedListItem(
                    selected = settings.style == style,
                    onClick = { EmoticonPrefs.setEmoticonStyle(context, style) },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = styles.size),
                    modifier = Modifier
                        .settingsRow()
                        .testTag("emoticon_style_${style.storageValue}"),
                    leadingContent = {
                        org.aralozkaya.msngr.MsnEmoticonGlyph(
                            emoticon = previewEmoticon,
                            emoticonStyle = style,
                        )
                    },
                    trailingContent = {
                        RadioButton(
                            selected = settings.style == style,
                            onClick = null,
                        )
                    },
                    content = { Text(style.displayLabel()) },
                )
            }
        }
        settingsSectionSpacer()
        settingsSectionHeader(R.string.settings_emoticon_behavior_header)
        item(key = "typed-shortcuts") {
            SegmentedListItem(
                checked = settings.convertTypedShortcuts,
                onCheckedChange = { enabled ->
                    EmoticonPrefs.setConvertTypedShortcuts(context, enabled)
                },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = behaviorItemCount),
                modifier = Modifier
                    .settingsRow()
                    .testTag("convert_typed_shortcuts"),
                trailingContent = {
                    Switch(
                        checked = settings.convertTypedShortcuts,
                        onCheckedChange = null,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_convert_shortcuts_supporting_you_singular))
                },
                content = { Text(stringResource(R.string.settings_convert_shortcuts_title)) },
            )
        }
        item(key = "play-winks") {
            SegmentedListItem(checked = winkSettings.playWinks,
                onCheckedChange = { org.aralozkaya.msngr.WinkPrefs.update(context, winkSettings.copy(playWinks = it)) },
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = behaviorItemCount), modifier = Modifier.settingsRow(),
                trailingContent = { Switch(winkSettings.playWinks, null) },
                supportingContent = { Text(stringResource(R.string.settings_play_winks_auto_supporting)) },
                content = { Text(stringResource(R.string.settings_play_winks_auto_title)) })
        }
        item(key = "wink-sounds") {
            SegmentedListItem(checked = winkSettings.sounds,
                onCheckedChange = { org.aralozkaya.msngr.WinkPrefs.update(context, winkSettings.copy(sounds = it)) },
                shapes = ListItemDefaults.segmentedShapes(index = 2, count = behaviorItemCount), modifier = Modifier.settingsRow(),
                trailingContent = { Switch(winkSettings.sounds, null) },
                supportingContent = { Text(stringResource(R.string.settings_wink_sounds_supporting_you_singular)) },
                content = { Text(stringResource(R.string.settings_wink_sounds_title)) })
        }
    }
}

@Composable
private fun org.aralozkaya.msngr.EmoticonStyle.displayLabel(): String = when (this) {
    org.aralozkaya.msngr.EmoticonStyle.Wlm2009 -> stringResource(R.string.settings_emoticon_style_wlm_2009_title)
    org.aralozkaya.msngr.EmoticonStyle.Wlm2012 -> stringResource(R.string.settings_emoticon_style_wlm_2012_title)
    org.aralozkaya.msngr.EmoticonStyle.AndroidEmoji -> stringResource(R.string.settings_emoticon_style_android_title)
}

@Composable
fun ConnectionInformationScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val snapshot = remember(context) { SessionPrefs.readLoginSnapshot(context) }
    val accountDetails = listOf(
        stringResource(R.string.connection_provider) to providerDisplayName(snapshot.provider),
        stringResource(R.string.connection_remembered_credentials) to stringResource(
            if (snapshot.savePassword) R.string.connection_encrypted_on_device
            else R.string.connection_not_saved,
        ),
        stringResource(R.string.connection_notification_server) to stringResource(
            R.string.connection_host_port_format,
            snapshot.resolvedServers.mainServer,
            snapshot.resolvedServers.mainPort.toString(),
        ),
        stringResource(R.string.connection_configuration) to snapshot.resolvedServers.configServer,
    )
    val serviceEndpoints = listOf(
        stringResource(R.string.connection_authentication) to snapshot.resolvedServers.authServer,
        stringResource(R.string.connection_address_book) to snapshot.resolvedServers.addressBookServer,
        stringResource(R.string.connection_storage) to snapshot.resolvedServers.storageServer,
        stringResource(R.string.connection_games_activities) to snapshot.resolvedServers.activitiesServer,
        stringResource(R.string.connection_mail) to snapshot.resolvedServers.mailServer,
    )
    val notConfigured = stringResource(R.string.connection_not_configured)
    val plusDetails = messengerPlusConnectionDetailItems(
        winkCatalogUrl = snapshot.resolvedServers.winkCatalogServer,
        emotionSoundUrl = snapshot.resolvedServers.emotionSoundServer,
    ).map { (labelRes, url) -> stringResource(labelRes) to (url ?: notConfigured) }

    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        settingsDetailsSection(R.string.connection_section_account, accountDetails, first = true)
        settingsSectionSpacer()
        settingsDetailsSection(R.string.connection_section_services, serviceEndpoints)
        settingsSectionSpacer()
        settingsDetailsSection(R.string.settings_destination_messenger_plus, plusDetails)
    }
}

internal fun messengerPlusConnectionDetailItems(
    winkCatalogUrl: String?,
    emotionSoundUrl: String?,
): List<Pair<Int, String?>> = listOf(
    R.string.connection_winks_plural to winkCatalogUrl?.takeIf { it.isNotBlank() },
    R.string.connection_emotion_sounds_plural to emotionSoundUrl?.takeIf { it.isNotBlank() },
)

@Composable
fun AboutSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onLicenses: () -> Unit = {},
) {
    PageContentList(
        innerPadding = innerPadding,
        scrollBehavior = scrollBehavior,
    ) {
        item(key = "about") {
            SettingsInformationCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        numberSentenceResource(
                            R.string.settings_about_version_before_number,
                            BuildConfig.VERSION_NAME,
                            R.string.settings_about_version_after_number,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsValue(
                    stringResource(R.string.settings_about_developer_label),
                    stringResource(R.string.settings_about_developer_name),
                )
                Text(stringResource(R.string.settings_about_license_summary))
                TextButton(onClick = onLicenses) {
                    Text(stringResource(R.string.settings_about_licenses_button))
                }
                Text(
                    stringResource(R.string.settings_about_credits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsInformationCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

private fun LazyListScope.settingsDetailsSection(
    @StringRes titleRes: Int,
    details: List<Pair<String, String>>,
    first: Boolean = false,
) {
    settingsSectionHeader(titleRes, first)
    details.forEachIndexed { index, (label, value) ->
        item(key = "$titleRes-$index") {
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, details.size),
                modifier = Modifier.fillMaxWidth(),
                content = { SettingsValue(label, value) },
            )
        }
    }
}

@Composable
private fun SettingsValue(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DeleteHistoryDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_delete_all_title)) },
        text = {
            Text(stringResource(R.string.settings_delete_all_message))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.settings_delete_all_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

internal fun LazyListScope.settingsSectionHeader(@StringRes titleRes: Int, first: Boolean = false) {
    item(key = "header-$titleRes") {
        Text(
            text = stringResource(titleRes),
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (first) 2.dp else 8.dp,
                    bottom = 8.dp,
                )
                .semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

internal fun LazyListScope.settingsSectionSpacer() {
    item { Spacer(Modifier.height(10.dp)) }
}

private fun Modifier.settingsRow(): Modifier =
    fillMaxWidth()
