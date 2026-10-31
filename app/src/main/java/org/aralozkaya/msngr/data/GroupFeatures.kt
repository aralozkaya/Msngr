package org.aralozkaya.msngr.data

internal const val INK_GIF_CAPABILITY = 0x04L
internal const val INK_ISF_CAPABILITY = 0x08L
internal const val WINK_CAPABILITY = 0x8000L

/** The first mask is the client ID; the optional second mask contains extended capabilities. */
internal fun parseClientCapabilities(value: String?): Long =
    value?.substringBefore(':')?.toLongOrNull()?.takeIf { it in 0..0xffff_ffffL } ?: 0L

internal enum class GroupInkFormat { Isf, Gif }

internal data class GroupFeatures(
    val inkFormat: GroupInkFormat? = null,
    val winks: Boolean = false,
)

/** Members come from the accepted membership/connected circle roster, not the contact list. */
internal fun Contact.groupFeatures(ownEmail: String): GroupFeatures {
    if (!isGroup || isBlocked || !presence.isOnline) return GroupFeatures()
    val recipients = joinedMembers.filter { it.presence.isOnline && !it.email.equals(ownEmail, true) }
    if (recipients.isEmpty()) return GroupFeatures()
    fun allSupport(mask: Long) = recipients.all { it.clientCapabilities and mask != 0L }
    return GroupFeatures(
        inkFormat = when {
            allSupport(INK_ISF_CAPABILITY) -> GroupInkFormat.Isf
            allSupport(INK_GIF_CAPABILITY) -> GroupInkFormat.Gif
            else -> null
        },
        winks = allSupport(WINK_CAPABILITY),
    )
}
