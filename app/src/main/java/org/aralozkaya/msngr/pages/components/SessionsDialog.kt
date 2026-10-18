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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.AccountSession

/** Standard modal focus and labeled controls work with TalkBack, keyboards, and touch. */
@Composable
internal fun SessionsDialog(
    sessions: List<AccountSession>,
    onLogOutSessions: suspend (Set<String>) -> Result<Unit>,
    onCurrentSessionLoggedOut: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                feedback = result.exceptionOrNull()?.message
                    ?: if (selected.size == 1) "${selected.single().deviceName} was logged out."
                    else "The other sessions were logged out."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
        title = { Text("Active sessions") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Choose where to log out. Remembered credentials stay saved on this device.")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("active-sessions-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions.sortedBy { !it.isCurrent }, key = AccountSession::id) { session ->
                        val sessionTarget = "session:${session.id}"
                        val sessionBusy = busyTarget == sessionTarget
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(session.deviceName, style = MaterialTheme.typography.titleSmall, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(if (session.isCurrent) "This device" else null,
                                            session.presence?.ownStatusLabel()).joinToString(" · "),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { logOut(sessionTarget, listOf(session)) },
                                    enabled = !busy || sessionBusy,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                        .padding(end = 12.dp)
                                        .testTag("logout-session-${session.id}")
                                        .semantics {
                                            contentDescription = if (session.isCurrent) "Log out of this device"
                                            else "Log out of ${session.deviceName}, session ${session.id.take(8)}"
                                        },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    LoadingButtonContent(
                                        loading = sessionBusy,
                                        label = "Logging out…",
                                        indicatorModifier = Modifier.testTag("logout-session-loading-${session.id}"),
                                    ) {
                                        Text("Log out")
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
                        OutlinedButton(
                            onClick = { logOut("other", others) },
                            enabled = !busy || otherBusy,
                            modifier = Modifier.fillMaxWidth().testTag("logout-other-sessions"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            LoadingButtonContent(
                                loading = otherBusy,
                                label = "Logging out…",
                                indicatorModifier = Modifier.testTag("logout-other-sessions-loading"),
                            ) {
                                Text("Log out other sessions")
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
                                label = "Logging out…",
                                indicatorModifier = Modifier.testTag("logout-all-sessions-loading"),
                            ) {
                                Text("Log out all sessions")
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
            TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Close") }
        },
    )
}
