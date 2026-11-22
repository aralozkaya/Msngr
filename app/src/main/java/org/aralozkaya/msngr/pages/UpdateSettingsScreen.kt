@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages

import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.DestructiveOutlinedButton
import androidx.compose.material3.OutlinedButton
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.localizeUserMessage
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.pages.components.ExpandableSettingsGroup
import org.aralozkaya.msngr.pages.components.ReleaseNotes
import org.aralozkaya.msngr.updates.APK_MIME_TYPE
import org.aralozkaya.msngr.updates.RELEASES_PAGE
import org.aralozkaya.msngr.updates.UpdateInterval
import org.aralozkaya.msngr.updates.UpdateManager
import org.aralozkaya.msngr.updates.updateManager
import org.aralozkaya.msngr.updates.verifyUpdateApk
import java.util.Date

@Composable
fun UpdateBadge(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.update_available)
    Badge(modifier.semantics { contentDescription = description }) { Text(stringResource(R.string.update_badge)) }
}

@Composable
fun UpdateSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    manager: UpdateManager = LocalContext.current.updateManager,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by manager.state.collectAsStateWithLifecycle()
    val settings = state.preferences
    val release = state.release
    val scope = rememberCoroutineScope()
    var intervalDialog by rememberSaveable { mutableStateOf(false) }
    var downloadDialog by rememberSaveable { mutableStateOf(false) }
    var releaseMenuExpanded by rememberSaveable(release?.version?.tag) { mutableStateOf(false) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVersion by rememberSaveable { mutableStateOf<Int?>(null) }
    var externalActionVersion by rememberSaveable { mutableStateOf<Int?>(null) }
    var fileActionBusy by remember { mutableStateOf(false) }
    var fileMessage by rememberSaveable { mutableStateOf<String?>(null) }

    fun install() {
        val target = release ?: return
        scope.launch {
            fileActionBusy = true
            try {
                val apk = manager.apkFile(target.version.code)
                withContext(Dispatchers.IO) { verifyUpdateApk(context, apk, target) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(resources.getString(R.string.update_clip_label), uri)
                })
            } catch (error: Exception) {
                manager.reportError(error.message ?: resources.getString(R.string.update_install_failed))
            } finally { fileActionBusy = false }
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (externalActionVersion != release?.version?.code) manager.reportError(resources.getString(R.string.update_release_changed))
        else if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) install()
        else manager.reportError(resources.getString(R.string.update_install_permission_denied))
        externalActionVersion = null
    }
    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(APK_MIME_TYPE)) { uri ->
        if (uri != null && release != null && externalActionVersion == release.version.code) {
            val target = release
            scope.launch {
                fileActionBusy = true
                try {
                    withContext(Dispatchers.IO) {
                        val apk = manager.apkFile(target.version.code)
                        verifyUpdateApk(context, apk, target)
                        val output = context.contentResolver.openOutputStream(uri, "w")
                            ?: error(resources.getString(R.string.update_save_failed))
                        output.use { stream -> apk.inputStream().use { it.copyTo(stream) } }
                    }
                    fileMessage = resources.getString(R.string.update_saved_you_singular)
                } catch (error: Exception) {
                    manager.reportError(error.message ?: resources.getString(R.string.update_save_failed))
                } finally { fileActionBusy = false }
            }
        } else if (uri != null) manager.reportError(resources.getString(R.string.update_release_changed))
        externalActionVersion = null
    }

    fun performFileAction(action: String) {
        externalActionVersion = release?.version?.code
        if (action == "save") saveApk.launch("Msngr-${release?.version?.tag}.apk")
        else if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()))
            }.onFailure { manager.reportError(resources.getString(R.string.update_install_failed)) }
        } else install()
    }

    LaunchedEffect(state.downloading) {
        if (state.downloading) releaseMenuExpanded = false
    }

    LaunchedEffect(state.downloading, state.downloadedVersion, state.error, pendingAction) {
        val action = pendingAction
        if (!state.downloading && action != null) {
            pendingAction = null
            if (state.downloadedVersion == pendingVersion && pendingVersion == release?.version?.code) performFileAction(action)
            pendingVersion = null
        }
    }

    PageContentList(innerPadding = innerPadding, scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("status") {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(when {
                            !state.supported -> R.string.update_release_only
                            state.checking -> R.string.update_checking
                            state.hasUpdate -> R.string.update_available
                            release != null -> R.string.update_skipped
                            state.lastChecked > 0 -> R.string.update_up_to_date_you_singular
                            else -> R.string.update_stay_current
                        }), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (state.hasUpdate) UpdateBadge(Modifier.testTag("updates-page-badge"))
                    }
                    Text(stringResource(R.string.update_installed_version, BuildConfig.VERSION_NAME))
                    if (!state.supported) Text(stringResource(R.string.update_release_only_explanation))
                    if (state.lastChecked > 0) Text(stringResource(R.string.update_last_checked,
                        DateFormat.getMediumDateFormat(context).format(Date(state.lastChecked)) + " · " +
                            DateFormat.getTimeFormat(context).format(Date(state.lastChecked))))
                    Button(onClick = { fileMessage = null; manager.checkManually() },
                        enabled = state.supported && !state.checking && !state.downloading && !fileActionBusy,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.testTag("check-for-updates")) {
                        Text(stringResource(if (state.checking) R.string.update_checking else R.string.update_check_now))
                    }
                }
            }
        }
        if (state.error != null || fileMessage != null) item("feedback") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                Text(localizeUserMessage(state.error ?: fileMessage.orEmpty()), Modifier.padding(16.dp).testTag("update-feedback"))
            }
        }
        if (release != null) item("release") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(release.version.tag, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.update_release_date, DateFormat.getMediumDateFormat(context).format(Date(release.publishedAt))))
                    if (release.prerelease) Text(stringResource(R.string.update_prerelease_label), color = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.update_changelog), style = MaterialTheme.typography.titleMedium)
                    ReleaseNotes(release.notes, Modifier.fillMaxWidth().testTag("release-notes"))
                    if (state.downloading) {
                        MsngrLinearWavyProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            org.aralozkaya.msngr.numberSentenceResource(
                                R.string.update_downloading_before_number,
                                (state.downloadProgress * 100).toInt(),
                                R.string.update_downloading_after_number,
                            ),
                        )
                        DestructiveOutlinedButton(onClick = { pendingAction = null; pendingVersion = null; manager.cancelDownload() }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    } else {
                        val isSkipped = settings.skippedTag == release.version.tag
                        val moreOptionsDescription = stringResource(R.string.update_more_options)
                        val menuState = stringResource(
                            if (releaseMenuExpanded) R.string.contacts_actions_expanded else R.string.contacts_actions_collapsed,
                        )
                        val menuInteractionSource = remember { MutableInteractionSource() }
                        SplitButtonLayout(
                            modifier = Modifier.fillMaxWidth().testTag("update-split-button"),
                            leadingButton = {
                                SplitButtonDefaults.LeadingButton(
                                    onClick = { downloadDialog = true },
                                    enabled = state.supported && release.apkUrl != null && !fileActionBusy && !state.checking,
                                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("download-update"),
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                                    )
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text(stringResource(if (state.downloadedVersion == release.version.code) R.string.update_apk_ready else R.string.update_download))
                                }
                            },
                            trailingButton = {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                    tooltip = { PlainTooltip { Text(moreOptionsDescription) } },
                                    state = rememberTooltipState(),
                                ) {
                                    SplitButtonDefaults.TrailingButton(
                                        checked = releaseMenuExpanded,
                                        onCheckedChange = { releaseMenuExpanded = it },
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .testTag("update-more-options")
                                            .semantics {
                                                contentDescription = moreOptionsDescription
                                                stateDescription = menuState
                                            },
                                    ) {
                                        val rotation by animateFloatAsState(
                                            targetValue = if (releaseMenuExpanded) 180f else 0f,
                                            label = "UpdateOptionsIconRotation",
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(SplitButtonDefaults.TrailingIconSize)
                                                .graphicsLayer { rotationZ = rotation },
                                        )
                                    }
                                }
                                DropdownMenuPopup(
                                    expanded = releaseMenuExpanded,
                                    onDismissRequest = { releaseMenuExpanded = false },
                                ) {
                                    DropdownMenuGroup(
                                        shapes = MenuDefaults.groupShape(0, 1),
                                        interactionSource = menuInteractionSource,
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (isSkipped) R.string.update_unskip else R.string.update_skip_version)) },
                                            leadingIcon = {
                                                Icon(
                                                    if (isSkipped) Icons.Default.Restore else Icons.Default.SkipNext,
                                                    contentDescription = null,
                                                )
                                            },
                                            shape = MenuDefaults.itemShape(0, 2).shape,
                                            enabled = !state.downloading && !fileActionBusy,
                                            modifier = Modifier.testTag("skip-update"),
                                            onClick = {
                                                releaseMenuExpanded = false
                                                manager.configure(settings.copy(skippedTag = if (isSkipped) null else release.version.tag))
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.update_view_release)) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                            shape = MenuDefaults.itemShape(1, 2).shape,
                                            modifier = Modifier.testTag("view-release-on-github"),
                                            onClick = {
                                                releaseMenuExpanded = false
                                                context.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri()))
                                            },
                                        )
                                    }
                                }
                            },
                        )
                        if (release.apkUrl == null) Text(stringResource(R.string.update_apk_not_available))
                    }
                }
            }
        }
        item("preferences") {
            ExpandableSettingsGroup(expanded = state.supported && settings.enabled, master = { shapes ->
                SegmentedListItem(checked = settings.enabled,
                    onCheckedChange = { manager.configure(settings.copy(enabled = it)) }, enabled = state.supported,
                    shapes = shapes,
                    trailingContent = { Switch(checked = settings.enabled, onCheckedChange = null, enabled = state.supported) },
                    supportingContent = { Text(stringResource(R.string.update_automatic_description_you_singular)) },
                    modifier = Modifier.testTag("automatic-updates"), content = { Text(stringResource(R.string.update_automatic)) })
            }) { shapes, colors ->
                SegmentedListItem(onClick = { intervalDialog = true },
                    shapes = shapes, colors = colors,
                    supportingContent = { Text(settings.interval.displayLabel()) },
                    modifier = Modifier.testTag("update-interval"), content = { Text(stringResource(R.string.update_frequency)) })
            }
        }
        item("include-prereleases") {
            SegmentedListItem(checked = settings.includePrereleases,
                onCheckedChange = { manager.configure(settings.copy(includePrereleases = it)); manager.checkManually() },
                enabled = state.supported && !state.checking && !state.downloading,
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                trailingContent = { Switch(checked = settings.includePrereleases, onCheckedChange = null,
                    enabled = state.supported && !state.checking && !state.downloading) },
                supportingContent = { Text(stringResource(R.string.update_prerelease_description)) },
                modifier = Modifier.testTag("include-prereleases"), content = { Text(stringResource(R.string.update_include_prereleases)) })
        }
        item("schedule-note") { Text(stringResource(R.string.update_schedule_note), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
        item("releases-link") {
            OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_PAGE.toUri())) }) {
                Text(stringResource(R.string.update_all_releases))
            }
        }
    }
    if (intervalDialog) AlertDialog(onDismissRequest = { intervalDialog = false },
        title = { Text(stringResource(R.string.update_frequency)) },
        text = {
            Column(Modifier.selectableGroup()) {
                UpdateInterval.entries.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = settings.interval == interval,
                                onClick = { manager.configure(settings.copy(interval = interval)); intervalDialog = false },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RadioButton(selected = settings.interval == interval, onClick = null)
                        Text(interval.displayLabel())
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { intervalDialog = false }) { Text(stringResource(android.R.string.cancel)) } })

    if (downloadDialog) {
        var downloadChoiceMenuExpanded by remember { mutableStateOf(false) }
        val downloadChoiceMenuInteractionSource = remember { MutableInteractionSource() }
        val moreOptionsDescription = stringResource(R.string.update_more_options)
        val menuState = stringResource(
            if (downloadChoiceMenuExpanded) R.string.contacts_actions_expanded else R.string.contacts_actions_collapsed,
        )
        AlertDialog(
            onDismissRequest = {
                downloadChoiceMenuExpanded = false
                downloadDialog = false
            },
            title = { Text(stringResource(R.string.update_download_choice_title_you_singular)) },
            text = { Text(stringResource(R.string.update_download_choice_description_you_singular)) },
            confirmButton = {
                val downloadAction: (String) -> Unit = { action ->
                    downloadChoiceMenuExpanded = false
                    downloadDialog = false
                    if (state.downloadedVersion == release?.version?.code) performFileAction(action)
                    else {
                        pendingVersion = release?.version?.code
                        manager.download()
                        pendingAction = action
                    }
                }
                SplitButtonLayout(
                    modifier = Modifier.testTag("download-choice-split-button"),
                    leadingButton = {
                        SplitButtonDefaults.LeadingButton(
                            onClick = { downloadAction("install") },
                            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                            modifier = Modifier.testTag("download-choice-install"),
                        ) {
                            Text(stringResource(R.string.update_install))
                        }
                    },
                    trailingButton = {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text(moreOptionsDescription) } },
                            state = rememberTooltipState(),
                        ) {
                            SplitButtonDefaults.TrailingButton(
                                checked = downloadChoiceMenuExpanded,
                                onCheckedChange = { downloadChoiceMenuExpanded = it },
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .testTag("download-choice-more-options")
                                    .semantics {
                                        contentDescription = moreOptionsDescription
                                        stateDescription = menuState
                                    },
                            ) {
                                val rotation by animateFloatAsState(
                                    targetValue = if (downloadChoiceMenuExpanded) 180f else 0f,
                                    label = "DownloadChoiceOptionsIconRotation",
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(SplitButtonDefaults.TrailingIconSize)
                                        .graphicsLayer { rotationZ = rotation },
                                )
                            }
                        }
                        DropdownMenuPopup(
                            expanded = downloadChoiceMenuExpanded,
                            onDismissRequest = { downloadChoiceMenuExpanded = false },
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(0, 1),
                                interactionSource = downloadChoiceMenuInteractionSource,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.update_save_apk)) },
                                    leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                                    shape = MenuDefaults.itemShape(0, 1).shape,
                                    modifier = Modifier.testTag("download-choice-save"),
                                    onClick = { downloadAction("save") },
                                )
                            }
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        downloadChoiceMenuExpanded = false
                        downloadDialog = false
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun UpdateInterval.displayLabel(): String = when (this) {
    UpdateInterval.OnAppStart -> stringResource(R.string.update_interval_start)
    UpdateInterval.Every6Hours -> org.aralozkaya.msngr.numberSentenceResource(
        R.string.update_interval_hours_before_number,
        6,
        R.string.update_interval_hours_after_number,
    )
    UpdateInterval.Every12Hours -> org.aralozkaya.msngr.numberSentenceResource(
        R.string.update_interval_hours_before_number,
        12,
        R.string.update_interval_hours_after_number,
    )
    UpdateInterval.Every24Hours -> org.aralozkaya.msngr.numberSentenceResource(
        R.string.update_interval_hours_before_number,
        24,
        R.string.update_interval_hours_after_number,
    )
    UpdateInterval.EveryWeek -> stringResource(R.string.update_interval_week)
}
