@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.ContactPictureViewer
import org.aralozkaya.msngr.EmoticonPanel
import org.aralozkaya.msngr.MessengerPlusColorSheet
import org.aralozkaya.msngr.MessengerPlusEditorController
import org.aralozkaya.msngr.MessengerPlusEditorSyntax
import org.aralozkaya.msngr.MessengerPlusProfileField
import org.aralozkaya.msngr.rememberEmoticonSheetState
import org.aralozkaya.msngr.rememberMessengerPlusColorSheetState
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.loadDisplayPictureBytes
import org.aralozkaya.msngr.DisplayPictureSelection
import org.aralozkaya.msngr.inspectDisplayPicture
import org.aralozkaya.msngr.inspectCapturedDisplayPicture
import org.aralozkaya.msngr.createDisplayPictureCaptureUri
import org.aralozkaya.msngr.deleteDisplayPictureCapture
import org.aralozkaya.msngr.resizeDisplayPicture
import kotlinx.coroutines.CancellationException
import org.aralozkaya.msngr.LocalMessengerPlusEnabled
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.withExpandedMsnMenuTokens
import org.aralozkaya.msngr.withoutUnicodeEmoji
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.pages.components.ProfileDetailCard
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.ui.theme.ownDisplayPicture

@Composable
fun ProfilePage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onProfileSaved: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val messengerPlusEnabled = LocalMessengerPlusEnabled.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val scope = rememberCoroutineScope()
    val repository = remember(context) { context.appContainer.messengerRepository }
    val initial = remember(repository) { repository.profile.value }
    var usernameEditorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.username, TextRange(initial.username.length)))
    }
    var statusMessageEditorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.statusMessage, TextRange(initial.statusMessage.length)))
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
    var profileSaveError by rememberSaveable { mutableStateOf<String?>(null) }
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
                profileSaveError = error.message ?: "That image could not be read."
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
                        profileSaveError = error.message ?: "This picture could not be resized."
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
                    profileSaveError = "The camera could not be opened. Try selecting a photo instead."
                }
            },
            onSelectPhoto = {
                showDisplayPictureOptions = false
                profileSaveError = null
                try {
                    // ImageOnly includes image/gif; do not limit this to JPEG/PNG.
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    profileSaveError = "The photo picker could not be opened. Please try again."
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
                                CircularProgressIndicator(
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
                                    contentDescription = "Change display picture",
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
                            label = "Display name",
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
                            label = "Personal message",
                            enabled = !savingProfile,
                            placeholder = "Share what you're up to",
                            supportingText = "Visible to your Messenger contacts",
                            emoticonSettings = emoticonSettings,
                            messengerPlusEnabled = messengerPlusEnabled,
                            trailingIcon = if (statusMessageDraft.isNotEmpty()) {
                                {
                                    IconButton(
                                        onClick = { statusMessageEditorValue = TextFieldValue("") },
                                        enabled = !savingProfile,
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear personal message",
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
                                        profileSaveError = "That image could not be read."
                                        savingProfile = false
                                        return@launch
                                    }
                                    repository.updateProfile(
                                        username = normalizedUsername,
                                        avatarUri = avatarUri,
                                        displayPictureBytes = bytesToUpload,
                                    ).onSuccess {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                SessionPrefs.saveUserProfile(
                                                    context = context,
                                                    username = normalizedUsername,
                                                    presence = initial.presence,
                                                    avatarUri = avatarUri,
                                                    statusMessage = normalizedStatusMessage,
                                                )
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            profileSaveError = "Your profile was sent, but its local copy could not be saved. Please try again."
                                            savingProfile = false
                                            return@launch
                                        }
                                        repository.setStatusMessage(normalizedStatusMessage)
                                        savingProfile = false
                                        onProfileSaved()
                                    }.onFailure { error ->
                                        profileSaveError = error.message
                                            ?: "The display picture could not be saved to the server."
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
                                Text("Save profile")
                            }
                        }
                        profileSaveError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item(key = "account") {
                    ProfileDetailCard(
                        icon = Icons.Default.Email,
                        label = "Sign-in address",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        supportingText = "Managed by your selected community service",
                    ) {
                        Text(initial.email, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

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
