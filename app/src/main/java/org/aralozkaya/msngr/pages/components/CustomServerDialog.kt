@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.aralozkaya.msngr.EmoticonOutlinedTextField
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.ServerOverrides
import org.aralozkaya.msngr.WinkDownloads
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.aralozkaya.msngr.twoNumbersSentenceResource
import org.aralozkaya.msngr.withoutUnicodeEmoji

private val ServerOverridesSaver: Saver<ServerOverrides, Any> = listSaver(
    save = {
        listOf(
            it.mainServer,
            it.mainPort,
            it.switchboardPort,
            it.configServer,
            it.authServerEnabled,
            it.authServer,
            it.addressBookServerEnabled,
            it.addressBookServer,
            it.adsServerEnabled,
            it.adsServer,
            it.storageServerEnabled,
            it.storageServer,
            it.socialServerEnabled,
            it.socialServer,
            it.activitiesServerEnabled,
            it.activitiesServer,
            it.mailServerEnabled,
            it.mailServer,
            it.emotionSoundServer,
            it.winkCatalogServer,
        )
    },
    restore = {
        ServerOverrides(
            mainServer = it[0] as String,
            mainPort = it[1] as Int,
            switchboardPort = it[2] as Int,
            configServer = it[3] as String,
            authServerEnabled = it[4] as Boolean,
            authServer = it[5] as String,
            addressBookServerEnabled = it[6] as Boolean,
            addressBookServer = it[7] as String,
            adsServerEnabled = it[8] as Boolean,
            adsServer = it[9] as String,
            storageServerEnabled = it[10] as Boolean,
            storageServer = it[11] as String,
            socialServerEnabled = it[12] as Boolean,
            socialServer = it[13] as String,
            activitiesServerEnabled = it[14] as Boolean,
            activitiesServer = it[15] as String,
            mailServerEnabled = it[16] as Boolean,
            mailServer = it[17] as String,
            emotionSoundServer = it[18] as String,
            winkCatalogServer = it[19] as String,
        )
    },
)

@Composable
internal fun CustomServerDialog(
    initial: ServerOverrides,
    onDismiss: () -> Unit,
    onSave: (ServerOverrides) -> Unit,
) {
    var draft by rememberSaveable(initial, stateSaver = ServerOverridesSaver) { mutableStateOf(initial) }
    var mainPort by rememberSaveable(initial) { mutableStateOf(initial.mainPort.toString()) }
    var switchboardPort by rememberSaveable(initial) { mutableStateOf(initial.switchboardPort.toString()) }
    val parsedMainPort = mainPort.toIntOrNull() ?: 0
    val parsedSwitchboardPort = switchboardPort.toIntOrNull() ?: 0
    val normalized = draft.copy(
        mainPort = parsedMainPort,
        switchboardPort = parsedSwitchboardPort,
    ).normalized()
    val mainPortError = mainPort.isNotEmpty() && parsedMainPort !in 1..65535
    val switchboardPortError = switchboardPort.isNotEmpty() && parsedSwitchboardPort !in 1..65535
    val winkCatalogError = normalized.winkCatalogServer.isNotEmpty() &&
        WinkDownloads.catalogUrl(normalized.winkCatalogServer) == null
    val emotionSoundError = normalized.emotionSoundServer.isNotEmpty() &&
        MsnEmotionSound.server(normalized.emotionSoundServer) == null

    BasicAlertDialog(
        modifier = Modifier.fillMaxSize(),
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val colors = MaterialTheme.colorScheme
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    val lightBars = colors.surface.luminance() > 0.5f
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        val focusManager = LocalFocusManager.current
        fun dismiss() {
            focusManager.clearFocus()
            onDismiss()
        }
        fun save() {
            if (!normalized.isValid()) return
            focusManager.clearFocus()
            onSave(normalized)
        }

        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Box(Modifier.fillMaxSize().testTag("custom-server-modal")) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeContent,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(R.string.custom_server_title),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = ::dismiss,
                                    modifier = Modifier.testTag("custom-server-dismiss"),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                                }
                            },
                            actions = {
                                TextButton(
                                    onClick = ::save,
                                    enabled = normalized.isValid(),
                                    modifier = Modifier.testTag("custom-server-save"),
                                ) {
                                    Text(stringResource(R.string.common_save))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            windowInsets = WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                    },
                ) { padding ->
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 16.dp)
                                .testTag("custom-server-form"),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            val portRangeError = twoNumbersSentenceResource(
                                R.string.custom_server_port_range_before_first_number,
                                1,
                                R.string.custom_server_port_range_between_numbers,
                                65535,
                                R.string.custom_server_port_range_after_second_number,
                            )
                            Text(
                                text = stringResource(R.string.custom_server_description),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ServerTextField(
                                label = stringResource(R.string.custom_server_notification_host),
                                value = draft.mainServer,
                                modifier = Modifier.testTag("custom-server-main-host"),
                            ) {
                                draft = draft.copy(mainServer = it)
                            }
                            ServerTextField(
                                label = stringResource(R.string.custom_server_notification_port),
                                value = mainPort,
                                keyboardType = KeyboardType.Number,
                                isError = mainPortError,
                                supportingText = if (mainPortError) {
                                    { Text(portRangeError) }
                                } else null,
                                modifier = Modifier.testTag("custom-server-main-port"),
                            ) {
                                mainPort = it
                            }
                            ServerTextField(
                                label = stringResource(R.string.custom_server_switchboard_port),
                                value = switchboardPort,
                                keyboardType = KeyboardType.Number,
                                isError = switchboardPortError,
                                supportingText = if (switchboardPortError) {
                                    { Text(portRangeError) }
                                } else null,
                                modifier = Modifier.testTag("custom-server-switchboard-port"),
                            ) {
                                switchboardPort = it
                            }
                            ServerTextField(
                                label = stringResource(R.string.custom_server_config_host),
                                value = draft.configServer,
                                modifier = Modifier.testTag("custom-server-config-host"),
                            ) {
                                draft = draft.copy(configServer = it)
                            }
                            Text(
                                text = stringResource(R.string.custom_server_optional_services),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            OptionalServerField(
                                label = stringResource(R.string.connection_authentication),
                                enabled = draft.authServerEnabled,
                                value = draft.authServer,
                                onEnabledChange = { draft = draft.copy(authServerEnabled = it) },
                                onValueChange = { draft = draft.copy(authServer = it) },
                            )
                            OptionalServerField(
                                label = stringResource(R.string.connection_address_book),
                                enabled = draft.addressBookServerEnabled,
                                value = draft.addressBookServer,
                                onEnabledChange = { draft = draft.copy(addressBookServerEnabled = it) },
                                onValueChange = { draft = draft.copy(addressBookServer = it) },
                            )
                            OptionalServerField(
                                label = stringResource(R.string.connection_storage),
                                enabled = draft.storageServerEnabled,
                                value = draft.storageServer,
                                onEnabledChange = { draft = draft.copy(storageServerEnabled = it) },
                                onValueChange = { draft = draft.copy(storageServer = it) },
                            )
                            OptionalServerField(
                                label = stringResource(R.string.custom_server_service_advertising),
                                enabled = draft.adsServerEnabled,
                                value = draft.adsServer,
                                onEnabledChange = { draft = draft.copy(adsServerEnabled = it) },
                                onValueChange = { draft = draft.copy(adsServer = it) },
                            )
                            OptionalServerField(
                                label = stringResource(R.string.custom_server_service_social),
                                enabled = draft.socialServerEnabled,
                                value = draft.socialServer,
                                onEnabledChange = { draft = draft.copy(socialServerEnabled = it) },
                                onValueChange = { draft = draft.copy(socialServer = it) },
                            )
                            OptionalServerField(
                                label = stringResource(R.string.connection_games_activities),
                                enabled = draft.activitiesServerEnabled,
                                value = draft.activitiesServer,
                                onEnabledChange = { draft = draft.copy(activitiesServerEnabled = it) },
                                onValueChange = { draft = draft.copy(activitiesServer = it) },
                            )
                            EmoticonOutlinedTextField(
                                value = draft.winkCatalogServer,
                                onValueChange = { draft = draft.copy(winkCatalogServer = it.withoutUnicodeEmoji()) },
                                label = { Text(stringResource(R.string.custom_server_winks_url_label)) },
                                supportingText = { Text(stringResource(R.string.custom_server_winks_url_supporting)) },
                                isError = winkCatalogError,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("wink_catalog_server"),
                            )
                            EmoticonOutlinedTextField(
                                value = draft.emotionSoundServer,
                                onValueChange = { draft = draft.copy(emotionSoundServer = it.withoutUnicodeEmoji()) },
                                label = { Text(stringResource(R.string.custom_server_emotion_sounds_url_label)) },
                                supportingText = { Text(stringResource(R.string.custom_server_emotion_sounds_url_supporting)) },
                                isError = emotionSoundError,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("emotion_sound_server"),
                            )
                            OptionalServerField(
                                label = stringResource(R.string.connection_mail),
                                enabled = draft.mailServerEnabled,
                                value = draft.mailServer,
                                onEnabledChange = { draft = draft.copy(mailServerEnabled = it) },
                                onValueChange = { draft = draft.copy(mailServer = it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Uri,
    imeAction: ImeAction = ImeAction.Next,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    EmoticonOutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.withoutUnicodeEmoji()) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
    )
}

@Composable
private fun OptionalServerField(
    label: String,
    enabled: Boolean,
    value: String,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (enabled) ServerTextField(stringResource(R.string.custom_server_host_suffix, label), value, onValueChange = onValueChange)
    }
}
