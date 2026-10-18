package org.aralozkaya.msngr

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import java.util.Locale

@Composable
internal fun ContactPresenceSnackbar(contact: Contact?, isConversationAvailable: Boolean = true) {
    val hostState = remember { SnackbarHostState() }
    var dismissed by rememberSaveable(contact?.id, contact?.presence, isConversationAvailable) { mutableStateOf(false) }
    val name = contact?.displayName?.messengerPlusPlainText().orEmpty()
    val message = if (contact?.isGroup == true) when {
        !isConversationAvailable -> stringResource(R.string.chat_group_removed, name)
        !contact.presence.isOnline -> stringResource(R.string.chat_group_offline, name)
        else -> null
    } else when (contact?.presence) {
        Presence.Busy, Presence.Away -> stringResource(
            R.string.chat_contact_unavailable,
            name,
            contact.presence.label.lowercase(Locale.ROOT),
        )
        Presence.Offline -> stringResource(R.string.chat_contact_offline, name)
        Presence.Online, null -> null
    }
    val dismissLabel = stringResource(R.string.chat_dismiss_status)

    LaunchedEffect(contact?.id, message, dismissed, dismissLabel) {
        if (message != null && !dismissed) {
            hostState.showSnackbar(
                message = message,
                actionLabel = dismissLabel,
                duration = SnackbarDuration.Indefinite,
            )
            // Remember explicit dismissal across recompositions and rotation, but
            // allow a fresh notice when the contact's presence changes.
            dismissed = true
        }
    }

    SnackbarHost(hostState = hostState) { data ->
        Snackbar(
            snackbarData = data,
            actionOnNewLine = contact?.isGroup == true || contact?.presence == Presence.Offline,
        )
    }
}
