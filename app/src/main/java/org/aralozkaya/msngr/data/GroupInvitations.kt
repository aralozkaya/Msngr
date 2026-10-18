package org.aralozkaya.msngr.data

import java.util.Locale

data class GroupInvitation(
    val groupId: String,
    val groupName: String,
    val inviterEmail: String? = null,
    val inviterName: String? = null,
    val message: String? = null,
) {
    val id: String get() = groupId.lowercase(Locale.ROOT)
}

data class GroupInvitationInbox(
    val accountId: String? = null,
    val invitations: List<GroupInvitation> = emptyList(),
)

internal fun Contact.ownGroupRole(ownEmail: String): GroupMemberRole =
    members.firstOrNull { it.email.equals(ownEmail, true) }?.role ?: GroupMemberRole.Unknown

internal fun Contact.canInviteGroupMembers(ownEmail: String): Boolean =
    isGroup && ownGroupRole(ownEmail) in setOf(GroupMemberRole.Owner, GroupMemberRole.Administrator)

internal fun Contact.canLeaveGroup(ownEmail: String): Boolean =
    isGroup && ownGroupRole(ownEmail) in setOf(GroupMemberRole.Administrator, GroupMemberRole.User)

internal fun validateGroupName(raw: String): String = raw.trim().also {
    require(it.isNotBlank()) { "Enter a name for your group." }
    require(it.length <= 100) { "Group names can have up to 100 characters." }
    require(it.none { c -> c.isISOControl() }) { "Enter a group name without control characters." }
}

internal fun validateGroupInvitees(raw: List<String>, ownEmail: String, members: List<GroupMember> = emptyList()): List<String> {
    val addresses = raw.map { validateContactAddress(it, ownEmail, emptyList()) }.distinct()
    require(addresses.none { email -> members.any { it.email.equals(email, true) } }) { "This person is already a group member." }
    require(addresses.size + members.size.coerceAtLeast(1) <= 20) { "A group can have up to 20 members." }
    return addresses
}
