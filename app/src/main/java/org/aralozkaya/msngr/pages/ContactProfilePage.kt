@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import org.aralozkaya.msngr.pages.components.DestructiveOutlinedButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.ContactPictureViewer
import org.aralozkaya.msngr.ConversationNotificationSettingsActivity
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.MessengerPlusText
import org.aralozkaya.msngr.MsnEmoticonText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.pages.components.DeleteContactDialog
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.pages.components.PresenceLabel
import org.aralozkaya.msngr.pages.components.ProfileDetailCard
import org.aralozkaya.msngr.ui.theme.avatar

@Composable
fun ContactProfilePage(
    contact: Contact?,
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onBlockContact: (Contact) -> Unit,
    onEraseHistory: suspend (Contact) -> Unit,
    onDeleteContact: (suspend (Contact, Boolean) -> Result<Unit>)? = null,
    onContactDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    // Keep the opened photo stable if the contact changes it while it is being viewed.
    var expandedPictureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showBlockConfirmation by rememberSaveable(contact?.id, contact?.isBlocked) { mutableStateOf(false) }
    var showEraseConfirmation by rememberSaveable(contact?.id) { mutableStateOf(false) }
    var isErasing by remember(contact?.id) { mutableStateOf(false) }
    var eraseError by remember(contact?.id) { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Contact?>(null) }
    val scope = rememberCoroutineScope()
    PageContentList(innerPadding = innerPadding, scrollBehavior = scrollBehavior) {
        item {
            if (contact == null) {
                Text("Contact is no longer available.", Modifier.padding(24.dp))
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Surface(
                            onClick = { expandedPictureUri = contact.avatarUri },
                            enabled = !contact.avatarUri.isNullOrBlank(),
                            shape = MaterialTheme.shapes.avatar,
                            modifier = Modifier.size(144.dp).semantics {
                                contentDescription = "View display picture"
                                role = Role.Button
                            },
                        ) {
                            Avatar(
                                name = contact.displayName.messengerPlusPlainText(),
                                avatarUri = contact.avatarUri,
                                presence = contact.presence,
                                modifier = Modifier.size(144.dp),
                                showPresenceIndicator = false,
                                highResolution = true,
                            )
                        }
                        if (contact.avatarUri.isNullOrBlank()) {
                            Text(
                                "No display picture",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SelectionContainer {
                            MessengerPlusText(
                                text = contact.displayName,
                                style = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                if (contact.isBlocked) {
                                    Text("Blocked")
                                } else {
                                    PresenceLabel(contact.presence)
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ProfileDetailCard(Icons.Default.Email, "Email address") {
                            Text(contact.email, style = MaterialTheme.typography.bodyLarge)
                        }
                        ProfileDetailCard(Icons.AutoMirrored.Filled.Chat, "Status message") {
                            MsnEmoticonText(
                                text = contact.statusMessage.takeIf {
                                    it.messengerPlusPlainText().isNotBlank()
                                } ?: "No status message",
                                useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
        if (contact != null) {
            item {
                FilledTonalButton(onClick = { context.startActivity(ConversationNotificationSettingsActivity.createIntent(
                    context, contact.id, contact.displayName.messengerPlusPlainText(), false)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("contact_notification_settings"),
                    shapes = ButtonDefaults.shapes()) { Text("Customize Notifications") }
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DestructiveOutlinedButton(
                        onClick = { showBlockConfirmation = true },
                        enabled = !contact.isBlocked,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(if (contact.isBlocked) "Blocked" else "Block")
                    }
                    DestructiveOutlinedButton(
                        onClick = {
                            eraseError = null
                            showEraseConfirmation = true
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Erase history")
                    }
                    if (onDeleteContact != null && contact.isForwardListContact && !contact.isGroup) {
                        DestructiveOutlinedButton(
                            onClick = { deleteTarget = contact },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Outlined.PersonRemove, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.contact_delete_action))
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        onDeleteContact?.let { delete ->
            DeleteContactDialog(
                contact = target,
                isMock = SessionPrefs.isMockServerActive(),
                onDeleteContact = { block -> delete(target, block) },
                onDeleted = { deleteTarget = null; onContactDeleted() },
                onDismiss = { deleteTarget = null },
            )
        }
    }
    if (showEraseConfirmation && contact != null) {
        AlertDialog(
            onDismissRequest = { if (!isErasing) showEraseConfirmation = false },
            title = { Text("Erase chat history?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This permanently erases your saved conversation with " +
                            contact.displayName.messengerPlusPlainText() +
                            " from this device. This cannot be undone.",
                    )
                    eraseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isErasing) return@Button
                        isErasing = true
                        eraseError = null
                        scope.launch {
                            try {
                                onEraseHistory(contact)
                                showEraseConfirmation = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                eraseError = "Could not erase history. Please try again."
                            } finally {
                                isErasing = false
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    LoadingButtonContent(
                        loading = isErasing,
                        label = stringResource(R.string.chat_history_erase_busy),
                        indicatorModifier = Modifier.testTag("chat-history-erase-loading"),
                    ) {
                        Text("Erase")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEraseConfirmation = false },
                    enabled = !isErasing,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
    if (showBlockConfirmation && contact != null && !contact.isBlocked) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmation = false },
            title = { Text("Block contact?") },
            text = {
                Text(
                    "Are you sure you want to block " +
                        contact.displayName.messengerPlusPlainText() +
                        "? You can unblock them later from Blocked contacts.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockConfirmation = false
                        onBlockContact(contact)
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBlockConfirmation = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
    expandedPictureUri?.let { uri ->
        ContactPictureViewer(
            pictureUri = uri,
            name = contact?.displayName?.messengerPlusPlainText() ?: "Contact",
            onDismiss = { expandedPictureUri = null },
        )
    }
}
