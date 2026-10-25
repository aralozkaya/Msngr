@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.notifications.connectedRepositoryForNotificationAction
import org.aralozkaya.msngr.pages.components.PresenceDot
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class StatusShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        AppShortcuts.reportStatusUsed(this)
        val initialProfile = runCatching {
            appContainer.messengerRepository
                .takeIf { it.hasSignedInSession }
                ?.profile?.value
        }.getOrNull() ?: SessionPrefs.readUserProfile(this)
        setContent {
            MsngrTheme(fillWindowSurface = false) {
                var applying by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(applying) {
                    setFinishOnTouchOutside(!applying)
                }
                BackHandler(enabled = applying) {}
                StatusShortcutWindow(
                    initialPresence = initialProfile.presence,
                    initialStatusMessage = initialProfile.statusMessage,
                    applying = applying,
                    errorMessage = errorMessage,
                    onSave = { presence, statusMessage ->
                        if (!applying) {
                            applying = true
                            errorMessage = null
                            lifecycleScope.launch {
                                val changedPresence = presence.takeIf {
                                    it != initialProfile.presence
                                }
                                if (applyChanges(changedPresence, statusMessage)) {
                                    finish()
                                } else {
                                    applying = false
                                    errorMessage = getString(
                                        R.string.shortcut_status_save_error,
                                    )
                                }
                            }
                        }
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    private suspend fun applyChanges(
        presence: Presence?,
        statusMessage: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val repository = applicationContext.connectedRepositoryForNotificationAction()
                ?: return@withContext false
            presence?.let(repository::setPresence)
            statusMessage?.let(repository::setStatusMessage)
            val profile = repository.profile.value
            SessionPrefs.saveUserProfile(
                context = this@StatusShortcutActivity,
                username = profile.username,
                presence = profile.presence,
                avatarUri = profile.avatarUri,
                statusMessage = profile.statusMessage,
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun onStart() {
        super.onStart()
        window.setGravity(Gravity.CENTER)
        window.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }
}

@Composable
internal fun StatusShortcutWindow(
    initialPresence: Presence,
    initialStatusMessage: String,
    applying: Boolean,
    errorMessage: String?,
    onSave: (Presence, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val messengerPlusEnabled = LocalMessengerPlusEnabled.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    var selectedPresence by rememberSaveable { mutableStateOf(initialPresence) }
    var statusMessageDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialStatusMessage))
    }
    val statusMessageController = remember {
        MessengerPlusEditorController(MessengerPlusEditorSyntax.PROFILE_MARKUP)
    }
    val formattingSheet = rememberMessengerPlusColorSheetState()
    LaunchedEffect(messengerPlusEnabled, applying) {
        if (!messengerPlusEnabled || applying) formattingSheet.dismiss()
    }
    MessengerPlusColorSheet(formattingSheet)
    var presenceMenuOpen by rememberSaveable { mutableStateOf(false) }
    val menuInteractionSource = remember { MutableInteractionSource() }
    // Null preserves the exact saved message when only presence changes.
    val editedMessage = if (statusMessageDraft.text != initialStatusMessage) {
        normalizeShortcutStatusMessage(statusMessageDraft.text, messengerPlusEnabled)
    } else {
        null
    }
    val hasChanges = selectedPresence != initialPresence || editedMessage != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_shortcut_window"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.shortcut_change_status_short),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Box {
                    OutlinedButton(
                        onClick = { presenceMenuOpen = true },
                        enabled = !applying,
                        modifier = Modifier.fillMaxWidth().testTag("shortcut_status_dropdown"),
                    ) {
                        PresenceDot(selectedPresence, modifier = Modifier.size(10.dp))
                        Text(
                            text = selectedPresence.ownStatusLabel(),
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.shortcut_change_status_choose),
                        )
                    }
                    DropdownMenuPopup(
                        expanded = presenceMenuOpen,
                        onDismissRequest = { presenceMenuOpen = false },
                    ) {
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(0, 1),
                            interactionSource = menuInteractionSource,
                        ) {
                            Presence.entries.forEach { presence ->
                                DropdownMenuItem(
                                    text = { Text(presence.ownStatusLabel()) },
                                    onClick = {
                                        selectedPresence = presence
                                        presenceMenuOpen = false
                                    },
                                    modifier = Modifier.testTag("shortcut_status_${presence.name}"),
                                )
                            }
                        }
                    }
                }
                MessengerPlusProfileField(
                    value = statusMessageDraft,
                    onValueChange = { value ->
                        val sanitized = value.text.withoutUnicodeEmoji()
                            .replace('\r', ' ').replace('\n', ' ')
                        statusMessageDraft = if (sanitized == value.text) value else value.copy(
                            text = sanitized,
                            selection = TextRange(
                                value.selection.start.coerceAtMost(sanitized.length),
                                value.selection.end.coerceAtMost(sanitized.length),
                            ),
                            composition = null,
                        )
                    },
                    controller = statusMessageController,
                    onColors = if (messengerPlusEnabled && !applying) {
                        { formattingSheet.open(statusMessageController) }
                    } else null,
                    label = stringResource(R.string.shortcut_status_message_label),
                    emoticonSettings = emoticonSettings,
                    messengerPlusEnabled = messengerPlusEnabled,
                    enabled = !applying,
                    modifier = Modifier.fillMaxWidth().testTag("shortcut_status_message"),
                )
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !applying) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(
                        onClick = { onSave(selectedPresence, editedMessage) },
                        enabled = hasChanges && !applying,
                        modifier = Modifier.testTag("shortcut_status_save"),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.shortcut_status_save))
                    }
                }
            }
        }
    }
}

private fun normalizeShortcutStatusMessage(value: String, messengerPlusEnabled: Boolean): String =
    value.withoutUnicodeEmoji().replace('\r', ' ').replace('\n', ' ').trim().let { normalized ->
        if (messengerPlusEnabled) normalized else normalized.messengerPlusPlainText()
    }
