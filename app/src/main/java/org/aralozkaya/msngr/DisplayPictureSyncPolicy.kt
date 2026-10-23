package org.aralozkaya.msngr

enum class DisplayPictureSyncBehavior(val label: String) {
    AlwaysIncoming("Always use incoming one"),
    AlwaysLocal("Always use local one"),
    AskEveryTime("Ask every time"),
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
