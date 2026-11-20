@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.AccountSession
import org.aralozkaya.msngr.localizeUserMessage

/** Standard modal focus and labeled controls work with TalkBack, keyboards, and touch. */
@Composable
internal fun SessionsDialog(
    sessions: List<AccountSession>,
    onLogOutSessions: suspend (Set<String>) -> Result<Unit>,
    onCurrentSessionLoggedOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busyTarget by remember { mutableStateOf<String?>(null) }
    val busy = busyTarget != null
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val others = sessions.filterNot(AccountSession::isCurrent)

    fun logOut(target: String, selected: List<AccountSession>) {
        if (busy || selected.isEmpty()) return
        busyTarget = target
        feedback = null
        scope.launch {
            val result = try {
                onLogOutSessions(selected.map(AccountSession::id).toSet())
            } finally {
                busyTarget = null
            }
            failed = result.isFailure
            if (result.isSuccess && selected.any(AccountSession::isCurrent)) {
                onCurrentSessionLoggedOut()
            } else {
                feedback = result.exceptionOrNull()?.message?.let(resources::localizeUserMessage)
                    ?: if (selected.size == 1) {
                        resources.getString(
                            R.string.sessions_logged_out_singular,
                            resources.localizeUserMessage(selected.single().deviceName),
                        )
                    } else {
                        resources.getString(R.string.sessions_logged_out_plural)
                    }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
        title = { Text(stringResource(R.string.sessions_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.sessions_description))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("active-sessions-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions.sortedBy { !it.isCurrent }, key = AccountSession::id) { session ->
                        val sessionTarget = "session:${session.id}"
                        val sessionBusy = busyTarget == sessionTarget
                        val deviceLabel = localizeUserMessage(session.deviceName)
                        val sessionActionDesc = if (session.isCurrent) {
                            stringResource(R.string.sessions_log_out_this_device)
                        } else {
                            stringResource(R.string.sessions_log_out_device_format, deviceLabel, session.id.take(8))
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(deviceLabel, style = MaterialTheme.typography.titleSmall, overflow = TextOverflow.Ellipsis)
                                    val presenceText = session.presence?.displayOwnStatusLabel()
                                    val metaText = when {
                                        session.isCurrent && presenceText != null -> stringResource(
                                            R.string.sessions_meta_separator,
                                            stringResource(R.string.common_device_this),
                                            presenceText,
                                        )
                                        session.isCurrent -> stringResource(R.string.common_device_this)
                                        else -> presenceText.orEmpty()
                                    }
                                    Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    onClick = { logOut(sessionTarget, listOf(session)) },
                                    enabled = !busy || sessionBusy,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                        .padding(end = 12.dp)
                                        .testTag("logout-session-${session.id}")
                                        .semantics {
                                            contentDescription = sessionActionDesc
                                        },
                                ) {
                                    LoadingButtonContent(
                                        loading = sessionBusy,
                                        label = stringResource(R.string.sessions_logging_out),
                                        indicatorModifier = Modifier.testTag("logout-session-loading-${session.id}"),
                                    ) {
                                        Text(stringResource(R.string.sessions_log_out_action))
                                    }
                                }
                            }
                        }
                    }
                }
                if (others.isNotEmpty()) {
                    val otherBusy = busyTarget == "other"
                    val allBusy = busyTarget == "all"
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DestructiveOutlinedButton(
                            onClick = { logOut("other", others) },
                            enabled = !busy || otherBusy,
                            modifier = Modifier.fillMaxWidth().testTag("logout-other-sessions"),
                        ) {
                            LoadingButtonContent(
                                loading = otherBusy,
                                label = stringResource(R.string.sessions_logging_out),
                                indicatorModifier = Modifier.testTag("logout-other-sessions-loading"),
                            ) {
                                Text(stringResource(R.string.sessions_log_out_others))
                            }
                        }
                        Button(
                            onClick = { logOut("all", sessions) },
                            enabled = !busy || allBusy,
                            modifier = Modifier.fillMaxWidth().testTag("logout-all-sessions"),
                            shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            LoadingButtonContent(
                                loading = allBusy,
                                label = stringResource(R.string.sessions_logging_out),
                                indicatorModifier = Modifier.testTag("logout-all-sessions-loading"),
                            ) {
                                Text(stringResource(R.string.sessions_log_out_all))
                            }
                        }
                    }
                }
                feedback?.let {
                    Text(it, color = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_close)) }
        },
    )
}
