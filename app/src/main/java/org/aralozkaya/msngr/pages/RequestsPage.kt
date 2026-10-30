@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.ContactInvitationSettings
import org.aralozkaya.msngr.MessengerPlusText
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.ContactRequest
import org.aralozkaya.msngr.data.GroupInvitation
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.LoadingButtonContent

@Composable
internal fun RequestsPage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    requests: List<ContactRequest>,
    onRespond: suspend (String, Boolean, Boolean) -> Result<Unit>,
    refreshing: Boolean = false,
    refreshError: String? = null,
    onRefresh: () -> Unit = {},
    isMock: Boolean = false,
    invitations: List<GroupInvitation> = emptyList(),
    onRespondToGroup: suspend (String, Boolean) -> Result<Unit> = {
        _, _ -> Result.failure(IllegalStateException("Group invitations are unavailable."))
    },
    onJoined: (String) -> Unit = {},
    invitationSettings: ContactInvitationSettings = ContactInvitationSettings(),
    onSaveDeclineDefaults: (Boolean) -> Unit = {},
) {
    var decisionId by rememberSaveable { mutableStateOf<String?>(null) }
    var busyContactResponse by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var contactError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busyGroupResponse by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var groupError by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val groupFailure = stringResource(R.string.group_invitation_failed)
    val contactFailure = stringResource(R.string.contact_request_failed)
    LaunchedEffect(requests, decisionId) {
        if (decisionId != null && requests.none { it.id == decisionId }) decisionId = null
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("requests-content"),
    ) {
        if (refreshing) {
            val refreshingDescription = stringResource(R.string.requests_refreshing)
            LoadingIndicator(
                modifier = Modifier.align(Alignment.Center).testTag("requests-refresh-indicator")
                    .semantics { contentDescription = refreshingDescription },
            )
        } else if (requests.isEmpty() && invitations.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when {
                        refreshError != null -> Text(refreshError, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        else -> Text(stringResource(R.string.requests_empty),
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("requests-empty"))
                    }
                    if (refreshError != null) OutlinedButton(onRefresh, enabled = !refreshing,
                        shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.requests_refresh))
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("requests-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (refreshError != null) item(key = "refresh-error") {
                    Text(refreshError, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(onRefresh, shapes = ButtonDefaults.shapes(), enabled = !refreshing) {
                        Text(stringResource(R.string.requests_refresh))
                    }
                }
                if (requests.isNotEmpty()) item(key = "contact-heading") {
                    RequestSectionHeading(stringResource(R.string.contact_requests_title), requests.size)
                }
                items(requests, key = { "contact:${it.id}" }) { request ->
                    RequestCard(Modifier.animateItem().testTag("contact-request-${request.id}"),
                        icon = Icons.Default.PersonAdd,
                        heading = {
                            request.displayName?.takeIf(String::isNotBlank)?.let {
                                Text(it.messengerPlusPlainText(), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(request.email, style = MaterialTheme.typography.bodyLarge)
                        }) {
                        Text(stringResource(R.string.contact_request_message))
                        request.message?.takeIf(String::isNotBlank)?.let {
                            Text(it, modifier = Modifier.testTag("contact-request-message-${request.id}"))
                        }
                        contactError?.takeIf { it.first == request.id }?.let {
                            Text(it.second, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("contact-request-error-${request.id}")
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        val respond: (Boolean, Boolean) -> Unit = { accept, block ->
                            if (busyContactResponse == null) {
                                busyContactResponse = request.id to accept
                                contactError = null
                                scope.launch {
                                    try {
                                        val result = onRespond(request.id, accept, block)
                                        if (result.isFailure) contactError = request.id to
                                            (result.exceptionOrNull()?.message ?: contactFailure)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { contactError = request.id to contactFailure }
                                    finally { busyContactResponse = null }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            Button(onClick = {
                                if (invitationSettings.useDeclineDefaults) {
                                    respond(false, invitationSettings.blockDeclinedInvitations)
                                } else if (busyContactResponse == null) {
                                    decisionId = request.id
                                }
                            }, enabled = busyContactResponse == null || busyContactResponse == (request.id to false),
                                shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError),
                                modifier = Modifier.testTag("decline-request-${request.id}")) {
                                LoadingButtonContent(
                                    loading = busyContactResponse == (request.id to false),
                                    label = stringResource(R.string.contact_request_busy),
                                    indicatorModifier = Modifier.testTag("decline-request-loading-${request.id}"),
                                ) {
                                    Text(stringResource(R.string.contact_request_decline))
                                }
                            }
                            Button(onClick = { respond(true, false) },
                                enabled = busyContactResponse == null || busyContactResponse == (request.id to true),
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.testTag("accept-request-${request.id}")) {
                                LoadingButtonContent(
                                    loading = busyContactResponse == (request.id to true),
                                    label = stringResource(R.string.contact_request_busy),
                                    indicatorModifier = Modifier.testTag("accept-request-loading-${request.id}"),
                                ) {
                                    Text(stringResource(R.string.contact_request_accept))
                                }
                            }
                        }
                    }
                }
                if (invitations.isNotEmpty()) item(key = "group-heading") {
                    RequestSectionHeading(stringResource(R.string.group_invitations_title), invitations.size)
                }
                items(invitations, key = { "group:${it.id}" }) { invitation ->
                    RequestCard(Modifier.animateItem().testTag("group-invitation-${invitation.id}"),
                        icon = Icons.Default.GroupAdd,
                        heading = {
                            MessengerPlusText(invitation.groupName, style = MaterialTheme.typography.titleMedium)
                            val inviter = invitation.inviterName?.takeIf(String::isNotBlank)?.messengerPlusPlainText()
                                ?: invitation.inviterEmail?.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.group_invitation_unknown_inviter)
                            Text(inviter, style = MaterialTheme.typography.bodyLarge)
                        }) {
                        Text(stringResource(R.string.group_invitation_message))
                        invitation.message?.takeIf(String::isNotBlank)?.let {
                            Text(it, modifier = Modifier.testTag("group-invitation-message-${invitation.id}"))
                        }
                        groupError?.takeIf { it.first == invitation.id }?.let {
                            Text(it.second, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("group-invitation-error-${invitation.id}")
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        val respond: (Boolean) -> Unit = { accept ->
                            if (busyGroupResponse == null) {
                                busyGroupResponse = invitation.id to accept
                                groupError = null
                                scope.launch {
                                    try {
                                        val result = onRespondToGroup(invitation.id, accept)
                                        if (result.isSuccess) {
                                            if (accept) onJoined(invitation.id)
                                        } else groupError = invitation.id to
                                            (result.exceptionOrNull()?.message ?: groupFailure)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { groupError = invitation.id to groupFailure }
                                    finally { busyGroupResponse = null }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            Button(onClick = { respond(false) }, enabled = busyGroupResponse == null || busyGroupResponse == (invitation.id to false),
                                shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError),
                                modifier = Modifier.testTag("decline-group-${invitation.id}")) {
                                LoadingButtonContent(
                                    loading = busyGroupResponse == (invitation.id to false),
                                    label = stringResource(R.string.contact_request_busy),
                                    indicatorModifier = Modifier.testTag("decline-group-loading-${invitation.id}"),
                                ) {
                                    Text(stringResource(R.string.contact_request_decline))
                                }
                            }
                            Button(onClick = { respond(true) }, enabled = busyGroupResponse == null || busyGroupResponse == (invitation.id to true),
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.testTag("accept-group-${invitation.id}")) {
                                LoadingButtonContent(
                                    loading = busyGroupResponse == (invitation.id to true),
                                    label = stringResource(R.string.contact_request_busy),
                                    indicatorModifier = Modifier.testTag("accept-group-loading-${invitation.id}"),
                                ) {
                                    Text(stringResource(R.string.group_invitation_join))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // A request answered on another client disappears while its decision dialog is open.
    requests.firstOrNull { it.id == decisionId }?.let { request ->
        ContactRequestDeclineDialog(request,
            onRespond = { block -> onRespond(request.id, false, block) },
            onDismiss = { decisionId = null }, isMock = isMock,
            initialBlock = invitationSettings.blockDeclinedInvitations,
            onSaveDeclineDefaults = onSaveDeclineDefaults)
    }
}

@Composable
private fun RequestSectionHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Badge { Text(count.toString()) }
    }
}

@Composable
private fun RequestCard(modifier: Modifier, icon: ImageVector,
                        heading: @Composable ColumnScope.() -> Unit,
                        content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null)
                    }
                }
                Column(Modifier.weight(1f), content = heading)
            }
            content()
        }
    }
}

@Composable
internal fun ContactRequestDeclineDialog(request: ContactRequest,
                                        onRespond: suspend (Boolean) -> Result<Unit>,
                                        onDismiss: () -> Unit, isMock: Boolean = false,
                                        initialBlock: Boolean = false,
                                        onSaveDeclineDefaults: (Boolean) -> Unit = {}) {
    var block by rememberSaveable(request.id) { mutableStateOf(initialBlock) }
    var setAsDefault by rememberSaveable(request.id) { mutableStateOf(false) }
    var busy by remember(request.id) { mutableStateOf(false) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fallbackError = stringResource(R.string.contact_request_failed)
    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxWidth().testTag("contact-request-modal"), shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.verticalScroll(rememberScrollState(), enabled = !busy).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(request.email, style = MaterialTheme.typography.headlineSmall)
                request.message?.takeIf(String::isNotBlank)?.let {
                    Text(it, modifier = Modifier.testTag("contact-request-decision-message"))
                }
                if (isMock) Text(stringResource(R.string.contact_request_mock_hint))
                Row(Modifier.fillMaxWidth().testTag("contact-request-block")
                    .toggleable(block, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { block = it; error = null }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(block, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.contact_request_block), modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.fillMaxWidth().testTag("contact-request-set-default")
                    .toggleable(setAsDefault, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { setAsDefault = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(setAsDefault, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.contact_request_set_default), modifier = Modifier.padding(start = 8.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("contact-request-error").semantics { liveRegion = LiveRegionMode.Polite }) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) {
                        Text(stringResource(R.string.contact_request_later))
                    }
                    Button(onClick = {
                        if (!busy) {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val result = onRespond(block)
                                    if (result.isSuccess) {
                                        if (setAsDefault) onSaveDeclineDefaults(block)
                                        onDismiss()
                                    }
                                    else error = result.exceptionOrNull()?.message ?: fallbackError
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = fallbackError }
                                finally { busy = false }
                            }
                        }
                    }, modifier = Modifier.testTag("contact-request-submit"),
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) {
                        LoadingButtonContent(
                            loading = busy,
                            label = stringResource(R.string.contact_request_busy),
                            indicatorModifier = Modifier.testTag("contact-request-spinner"),
                        ) {
                            Text(stringResource(R.string.contact_request_ok))
                        }
                    }
                }
            }
        }
    }
}
