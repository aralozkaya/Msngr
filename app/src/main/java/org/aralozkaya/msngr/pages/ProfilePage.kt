@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.ContactPictureViewer
import org.aralozkaya.msngr.DisplayPictureSelection
import org.aralozkaya.msngr.EmoticonPanel
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.LocalMessengerPlusEnabled
import org.aralozkaya.msngr.MessengerPlusColorSheet
import org.aralozkaya.msngr.MessengerPlusEditorController
import org.aralozkaya.msngr.MessengerPlusEditorSyntax
import org.aralozkaya.msngr.MessengerPlusProfileField
import org.aralozkaya.msngr.NowPlayingAccess
import org.aralozkaya.msngr.NowPlayingController
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.createDisplayPictureCaptureUri
import org.aralozkaya.msngr.deleteDisplayPictureCapture
import org.aralozkaya.msngr.displayPictureSync
import org.aralozkaya.msngr.inspectCapturedDisplayPicture
import org.aralozkaya.msngr.inspectDisplayPicture
import org.aralozkaya.msngr.loadDisplayPictureBytes
import org.aralozkaya.msngr.localizeUserMessage
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.nowPlayingController
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.pages.components.ExpandableSettingsGroup
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.pages.components.icon
import org.aralozkaya.msngr.rememberEmoticonSheetState
import org.aralozkaya.msngr.rememberMessengerPlusColorSheetState
import org.aralozkaya.msngr.resizeDisplayPicture
import org.aralozkaya.msngr.ui.theme.ownDisplayPicture
import org.aralozkaya.msngr.withExpandedMsnMenuTokens
import org.aralozkaya.msngr.withoutUnicodeEmoji
import java.io.Serializable

@Composable
internal fun ProfilePage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onProfileSaved: () -> Unit,
    nowPlayingController: NowPlayingController? = null,
    onOpenNotificationAccessSettings: (() -> Unit)? = null,
    onOpenAppInfoSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val messengerPlusEnabled = LocalMessengerPlusEnabled.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.appContainer.messengerRepository }
    val activeNowPlayingController = nowPlayingController ?: remember(context) { context.nowPlayingController }
    val nowPlayingState by activeNowPlayingController.uiState.collectAsState()
    val initial = remember(repository) { repository.profile.value }
    var showNowPlayingPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var pendingEnableNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showNowPlayingSettingsError by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, activeNowPlayingController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                activeNowPlayingController.refreshState()
                if (pendingEnableNowPlaying) {
                    pendingEnableNowPlaying = false
                    if (activeNowPlayingController.hasNotificationAccess()) {
                        activeNowPlayingController.setEnabled(true)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun openNotificationAccess() {
        showNowPlayingSettingsError = false
        if (onOpenNotificationAccessSettings != null) {
            onOpenNotificationAccessSettings()
        } else {
            runCatching {
                context.startActivity(NowPlayingAccess.createNotificationAccessIntent(context))
            }.onFailure {
                showNowPlayingSettingsError = true
            }
        }
    }
    fun openAppInfo() {
        showNowPlayingSettingsError = false
        if (onOpenAppInfoSettings != null) {
            onOpenAppInfoSettings()
        } else {
            runCatching {
                context.startActivity(NowPlayingAccess.createAppDetailsIntent(context))
            }.onFailure {
                showNowPlayingPermissionDialog = false
                showNowPlayingSettingsError = true
            }
        }
    }
    var usernameEditorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.username))
    }
    var statusMessageEditorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.statusMessage))
    }
    val usernameDraft = usernameEditorValue.text
    val statusMessageDraft = statusMessageEditorValue.text
    var avatarUri by rememberSaveable { mutableStateOf(initial.avatarUri) }
    var displayPictureBytes by remember { mutableStateOf<ByteArray?>(null) }
    var displayPictureChanged by rememberSaveable { mutableStateOf(false) }
    var avatarImageVersion by remember { mutableIntStateOf(0) }
    var showDisplayPictureOptions by rememberSaveable { mutableStateOf(false) }
    var showDefaultDisplayPictures by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedPictureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var processingDisplayPicture by remember { mutableStateOf(false) }
    var pendingPictureResize by remember { mutableStateOf<DisplayPictureSelection?>(null) }
    var savingProfile by remember { mutableStateOf(false) }
    var profileSaveError by rememberSaveable { mutableStateOf<ProfileSaveError?>(null) }
    val usernameEditorController = remember {
        MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
    }
    val statusMessageEditorController = remember {
        MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
    }
    val emoticonSheet = rememberEmoticonSheetState()
    val colorSheet = rememberMessengerPlusColorSheetState()
    LaunchedEffect(messengerPlusEnabled, savingProfile) {
        if (!messengerPlusEnabled || savingProfile) colorSheet.dismiss()
        if (savingProfile) {
            emoticonSheet.dismiss()
            usernameEditorController.hideKeyboard()
            statusMessageEditorController.hideKeyboard()
        }
    }
    EmoticonPanel(
        state = emoticonSheet,
        useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
    )
    MessengerPlusColorSheet(colorSheet)
    val normalizedUsername = usernameDraft.withExpandedMsnMenuTokens().trim().let { value ->
        if (messengerPlusEnabled) value else value.messengerPlusPlainText()
    }
    val normalizedStatusMessage =
        statusMessageDraft.withExpandedMsnMenuTokens().singleLineStatus().trim().let { value ->
            if (messengerPlusEnabled) value else value.messengerPlusPlainText()
        }
    val hasChanges = normalizedUsername != initial.username ||
        normalizedStatusMessage != initial.statusMessage ||
        avatarUri != initial.avatarUri || displayPictureChanged
    fun acceptPicture(picture: DisplayPictureSelection) {
        // Store the actual upload, so previews, exports and sign-in restoration use identical bytes.
        avatarUri = picture.dataUri
        displayPictureBytes = picture.bytes
        displayPictureChanged = true
        avatarImageVersion++
    }
    fun selectPicture(uri: String, captured: Boolean = false) {
        processingDisplayPicture = true
        profileSaveError = null
        scope.launch {
            try {
                val picture = if (captured) context.inspectCapturedDisplayPicture(uri)
                    else context.inspectDisplayPicture(uri)
                if (picture.requiresResize) pendingPictureResize = picture else acceptPicture(picture)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                profileSaveError = ProfileSaveError(R.string.profile_error_read_image, error.message)
            } finally {
                if (captured) context.deleteDisplayPictureCapture(Uri.parse(uri))
                processingDisplayPicture = false
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { selectPicture(it.toString()) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (uri != null) {
            if (success) selectPicture(uri, captured = true)
            else context.deleteDisplayPictureCapture(Uri.parse(uri))
        }
    }

    pendingPictureResize?.let { picture ->
        DisplayPictureResizeDialog(
            picture = picture,
            onCancel = { pendingPictureResize = null },
            onResize = {
                pendingPictureResize = null
                processingDisplayPicture = true
                scope.launch {
                    try {
                        acceptPicture(resizeDisplayPicture(picture))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        profileSaveError = ProfileSaveError(R.string.profile_error_resize_picture, error.message)
                    } finally {
                        processingDisplayPicture = false
                    }
                }
            },
        )
    }

    if (showDisplayPictureOptions) {
        DisplayPictureOptionsDialog(
            name = usernameDraft.messengerPlusPlainText(),
            pictureUri = avatarUri,
            onTakePhoto = {
                showDisplayPictureOptions = false
                profileSaveError = null
                try {
                    val uri = context.createDisplayPictureCaptureUri()
                    pendingCameraUri = uri.toString()
                    camera.launch(uri)
                } catch (_: Exception) {
                    pendingCameraUri?.let { context.deleteDisplayPictureCapture(Uri.parse(it)) }
                    pendingCameraUri = null
                    profileSaveError = ProfileSaveError(R.string.profile_error_open_camera)
                }
            },
            onSelectPhoto = {
                showDisplayPictureOptions = false
                profileSaveError = null
                try {
                    // ImageOnly includes image/gif; do not limit this to JPEG/PNG.
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    profileSaveError = ProfileSaveError(R.string.profile_error_open_picker)
                }
            },
            onDefaultPhoto = { showDisplayPictureOptions = false; showDefaultDisplayPictures = true },
            onViewPicture = { expandedPictureUri = avatarUri; showDisplayPictureOptions = false },
            onRemovePicture = {
                avatarUri = null
                displayPictureBytes = null
                displayPictureChanged = initial.avatarUri != null
                avatarImageVersion++
                profileSaveError = null
                showDisplayPictureOptions = false
            },
            onDismiss = { showDisplayPictureOptions = false },
        )
    }

    if (showDefaultDisplayPictures) {
        DefaultDisplayPictureDialog(
            onSelect = { picture -> showDefaultDisplayPictures = false; selectPicture(picture.uri) },
            onDismiss = { showDefaultDisplayPictures = false },
        )
    }

    expandedPictureUri?.let { uri ->
        ContactPictureViewer(
            pictureUri = uri,
            name = usernameDraft.ifBlank { initial.username }.messengerPlusPlainText(),
            onDismiss = { expandedPictureUri = null },
        )
    }

    if (showNowPlayingPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showNowPlayingPermissionDialog = false },
            title = { Text(stringResource(R.string.profile_now_playing_permission_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profile_now_playing_permission_body_you_singular))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(
                            text = stringResource(R.string.profile_now_playing_permission_restricted_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { openAppInfo() },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.testTag("profile-now-playing-open-app-info"),
                        ) {
                            Text(stringResource(R.string.profile_now_playing_open_app_info))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNowPlayingPermissionDialog = false
                        pendingEnableNowPlaying = true
                        openNotificationAccess()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("profile-now-playing-open-settings"),
                ) {
                    Text(stringResource(R.string.profile_now_playing_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNowPlayingPermissionDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            PageContentList(
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "picture") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(modifier = Modifier.size(144.dp)) {
                            Avatar(
                                name = usernameDraft.messengerPlusPlainText(),
                                avatarUri = avatarUri,
                                presence = initial.presence,
                                modifier = Modifier.matchParentSize(),
                                showPresenceIndicator = false,
                                imageVersion = avatarImageVersion,
                                shape = MaterialTheme.shapes.ownDisplayPicture,
                            )
                            if (processingDisplayPicture) {
                                MsngrCircularWavyProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).testTag("picture-processing"),
                                )
                            }
                            FilledIconButton(
                                onClick = { showDisplayPictureOptions = true },
                                enabled = !savingProfile && !processingDisplayPicture,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.align(Alignment.BottomEnd),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.profile_change_display_picture),
                                )
                            }
                        }
                    }
                }
                item(key = "fields") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MessengerPlusProfileField(
                            value = usernameEditorValue,
                            onValueChange = { value ->
                                usernameEditorValue = value.sanitizeProfileValue {
                                    it.withoutUnicodeEmoji().singleLineStatus()
                                }
                            },
                            controller = usernameEditorController,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.profile_display_name_label),
                            enabled = !savingProfile,
                            emoticonSettings = emoticonSettings,
                            messengerPlusEnabled = messengerPlusEnabled,
                            emoticonSheet = emoticonSheet,
                            onColors = if (messengerPlusEnabled) {
                                { colorSheet.open(usernameEditorController) }
                            } else {
                                null
                            },
                        )
                        MessengerPlusProfileField(
                            value = statusMessageEditorValue,
                            onValueChange = { value ->
                                statusMessageEditorValue = value.sanitizeProfileValue {
                                    it.withoutUnicodeEmoji().singleLineStatus()
                                }
                            },
                            controller = statusMessageEditorController,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.profile_personal_message_label),
                            enabled = !savingProfile,
                            placeholder = stringResource(R.string.profile_personal_message_placeholder_you_singular),
                            supportingText = stringResource(R.string.profile_personal_message_supporting_you_singular),
                            emoticonSettings = emoticonSettings,
                            messengerPlusEnabled = messengerPlusEnabled,
                            trailingIcon = if (statusMessageDraft.isNotEmpty()) {
                                {
                                    IconButton(
                                        onClick = { statusMessageEditorValue = TextFieldValue("") },
                                        modifier = Modifier.size(
                                            IconButtonDefaults.smallContainerSize(
                                                widthOption = IconButtonDefaults.IconButtonWidthOption.Narrow,
                                            ),
                                        ),
                                        enabled = !savingProfile,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = stringResource(R.string.profile_clear_personal_message),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            emoticonSheet = emoticonSheet,
                            onColors = if (messengerPlusEnabled) {
                                { colorSheet.open(statusMessageEditorController) }
                            } else {
                                null
                            },
                        )
                        Button(
                            enabled = normalizedUsername.isNotEmpty() && hasChanges &&
                                !processingDisplayPicture && pendingPictureResize == null,
                            shapes = ButtonDefaults.shapes(),
                            onClick = {
                                if (savingProfile) return@Button
                                usernameEditorValue = TextFieldValue(normalizedUsername)
                                statusMessageEditorValue = TextFieldValue(normalizedStatusMessage)
                                savingProfile = true
                                focusManager.clearFocus()
                                profileSaveError = null
                                scope.launch {
                                    val bytesToUpload = if (displayPictureChanged && avatarUri != null) {
                                        displayPictureBytes
                                            ?: context.loadDisplayPictureBytes(avatarUri)
                                    } else {
                                        null
                                    }
                                    if (displayPictureChanged && avatarUri != null && bytesToUpload == null) {
                                        profileSaveError = ProfileSaveError(R.string.profile_error_read_image)
                                        savingProfile = false
                                        return@launch
                                    }
                                    context.displayPictureSync.saveProfile(
                                        repository = repository,
                                        username = normalizedUsername,
                                        avatarUri = avatarUri,
                                        pictureBytes = bytesToUpload,
                                        pictureChanged = displayPictureChanged,
                                        statusMessage = normalizedStatusMessage,
                                    ).onSuccess {
                                        savingProfile = false
                                        onProfileSaved()
                                    }.onFailure { error ->
                                        profileSaveError = ProfileSaveError(
                                            R.string.profile_error_save_picture_server,
                                            error.message,
                                        )
                                        savingProfile = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            LoadingButtonContent(
                                loading = savingProfile,
                                label = stringResource(R.string.profile_save_busy),
                                indicatorModifier = Modifier.testTag("profile-save-loading"),
                            ) {
                                Text(stringResource(R.string.profile_save_button))
                            }
                        }
                        profileSaveError?.let { error ->
                            Text(
                                error.detail?.let { localizeUserMessage(it) } ?: stringResource(error.fallback),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                item(key = "profile_cards") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profile-now-playing-card"),
                        ) {
                            val nowPlayingExpanded =
                                nowPlayingState.enabled || showNowPlayingSettingsError
                            ExpandableSettingsGroup(
                                expanded = nowPlayingExpanded,
                                highlighted = nowPlayingState.enabled,
                                master = { shapes ->
                                    SegmentedListItem(
                                        checked = nowPlayingState.enabled,
                                        onCheckedChange = { wantEnabled ->
                                            showNowPlayingSettingsError = false
                                            if (wantEnabled) {
                                                if (activeNowPlayingController.hasNotificationAccess()) {
                                                    activeNowPlayingController.setEnabled(true)
                                                } else {
                                                    showNowPlayingPermissionDialog = true
                                                }
                                            } else {
                                                pendingEnableNowPlaying = false
                                                activeNowPlayingController.setEnabled(false)
                                            }
                                        },
                                        shapes = shapes,
                                        enabled = !savingProfile,
                                        modifier = Modifier.testTag("profile-now-playing-toggle"),
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = nowPlayingState.enabled,
                                                onCheckedChange = null,
                                                enabled = !savingProfile,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.profile_now_playing_title))
                                    }
                                },
                                content = { shapes, colors ->
                                    val errorSupportingContent: (@Composable () -> Unit)? =
                                        if (showNowPlayingSettingsError) {
                                            {
                                                Text(
                                                    text = stringResource(R.string.profile_now_playing_settings_unavailable),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        } else null
                                    when {
                                        !nowPlayingState.enabled && showNowPlayingSettingsError -> {
                                            SegmentedListItem(
                                                shapes = shapes,
                                                colors = colors,
                                            ) {
                                                errorSupportingContent?.invoke()
                                            }
                                        }
                                        !nowPlayingState.hasNotificationAccess -> {
                                            SegmentedListItem(
                                                onClick = {
                                                    pendingEnableNowPlaying = true
                                                    openNotificationAccess()
                                                },
                                                shapes = shapes,
                                                colors = colors,
                                                modifier = Modifier.testTag("profile-now-playing-grant-access"),
                                                supportingContent = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(
                                                            text = stringResource(R.string.profile_now_playing_access_missing),
                                                            color = MaterialTheme.colorScheme.error,
                                                        )
                                                        errorSupportingContent?.invoke()
                                                    }
                                                },
                                            ) {
                                                Text(stringResource(R.string.profile_now_playing_grant_access))
                                            }
                                        }
                                        else -> {
                                            val detectedMedia = nowPlayingState.detectedMedia
                                            if (detectedMedia != null) {
                                                SegmentedListItem(
                                                    shapes = shapes,
                                                    colors = colors,
                                                    modifier = Modifier.testTag("profile-now-playing-preview"),
                                                    leadingContent = {
                                                        Icon(
                                                            imageVector = detectedMedia.icon(),
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    },
                                                    overlineContent = {
                                                        Text(stringResource(R.string.profile_now_playing_currently_sharing))
                                                    },
                                                    supportingContent = errorSupportingContent,
                                                ) {
                                                    Text(
                                                        text = detectedMedia.displayText,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            } else {
                                                SegmentedListItem(
                                                    shapes = shapes,
                                                    colors = colors,
                                                    modifier = Modifier.testTag("profile-now-playing-empty"),
                                                    supportingContent = errorSupportingContent,
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.profile_now_playing_nothing_playing_you_singular),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        SegmentedListItem(
                            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            overlineContent = {
                                Text(stringResource(R.string.profile_sign_in_address_label))
                            },
                        ) {
                            SelectionContainer {
                                Text(initial.email)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProfileSaveError(
    @param:StringRes val fallback: Int,
    val detail: String? = null,
) : Serializable

private fun TextFieldValue.sanitizeProfileValue(
    transform: (String) -> String,
): TextFieldValue {
    val sanitized = transform(text)
    if (sanitized == text) return this
    return copy(
        text = sanitized,
        selection = TextRange(
            selection.start.coerceAtMost(sanitized.length),
            selection.end.coerceAtMost(sanitized.length),
        ),
        composition = null,
    )
}

private fun String.singleLineStatus(): String = replace('\r', ' ').replace('\n', ' ')
