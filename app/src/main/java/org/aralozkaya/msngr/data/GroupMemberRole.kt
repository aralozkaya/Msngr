package org.aralozkaya.msngr.data

/** Circle RelationshipRole: server Admin is the owner; AssistantAdmin is an administrator. */
enum class GroupMemberRole(val label: String, private val relationshipRole: Int?) {
    Owner("Owner", 1),
    Administrator("Administrator", 2),
    User("User", 3),
    Unknown("Unknown", null);

    companion object {
        internal fun fromRelationshipRole(value: String): GroupMemberRole =
            entries.firstOrNull { it.relationshipRole == value.toIntOrNull() } ?: Unknown
    }
}
