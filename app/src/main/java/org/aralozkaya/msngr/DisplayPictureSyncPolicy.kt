package org.aralozkaya.msngr

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

enum class DisplayPictureSyncBehavior(val label: String, @param:StringRes val labelRes: Int) {
    AlwaysIncoming("Always use incoming one", R.string.display_picture_sync_always_incoming),
    AlwaysLocal("Always use local one", R.string.display_picture_sync_always_local),
    AskEveryTime("Ask every time", R.string.display_picture_sync_ask_every_time);

    @Composable
    fun displayLabel(): String = stringResource(labelRes)
}

/** A failed download is distinct from a successfully fetched profile with no picture. */
data class IncomingDisplayPicture(val bytes: ByteArray? = null, val fetched: Boolean = false)

internal enum class DisplayPictureSyncDecision { Incoming, Local, Ask, Unavailable }

internal fun displayPictureSyncDecision(
    hasLocalSnapshot: Boolean,
    local: ByteArray?,
    incoming: IncomingDisplayPicture,
    behavior: DisplayPictureSyncBehavior,
): DisplayPictureSyncDecision = when {
    !incoming.fetched -> DisplayPictureSyncDecision.Unavailable
    !hasLocalSnapshot -> DisplayPictureSyncDecision.Incoming
    local == null && incoming.bytes == null || local != null && incoming.bytes?.contentEquals(local) == true ->
        DisplayPictureSyncDecision.Incoming
    behavior == DisplayPictureSyncBehavior.AlwaysIncoming -> DisplayPictureSyncDecision.Incoming
    behavior == DisplayPictureSyncBehavior.AlwaysLocal -> DisplayPictureSyncDecision.Local
    else -> DisplayPictureSyncDecision.Ask
}
