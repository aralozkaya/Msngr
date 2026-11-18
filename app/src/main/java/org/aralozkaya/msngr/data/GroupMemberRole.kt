package org.aralozkaya.msngr.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.R

/** Circle RelationshipRole: server Admin is the owner; AssistantAdmin is an administrator. */
enum class GroupMemberRole(
    val label: String,
    private val relationshipRole: Int?,
    @param:StringRes val labelRes: Int,
) {
    Owner("Owner", 1, R.string.group_role_owner),
    Administrator("Administrator", 2, R.string.group_role_administrator),
    User("User", 3, R.string.group_role_user),
    Unknown("Unknown", null, R.string.group_role_unknown);

    @Composable
    fun displayLabel(): String = stringResource(labelRes)

    companion object {
        internal fun fromRelationshipRole(value: String): GroupMemberRole =
            entries.firstOrNull { it.relationshipRole == value.toIntOrNull() } ?: Unknown
    }
}
