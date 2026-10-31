package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.enums.Presence

/** A successful full membership lookup replaces block flags, including blocks outside the forward list. */
internal fun mergeBlockedContactUpdate(current: List<Contact>, blocked: List<Contact>): List<Contact> {
    val blockedIds = blocked.map(Contact::id).toSet()
    val retained = current.filterNot {
        it.isBlocked && !it.isForwardListContact && !it.isGroup && it.id !in blockedIds
    }.map { contact ->
        val isBlocked = contact.id in blockedIds
        contact.copy(
            isBlocked = isBlocked,
            presence = if (isBlocked) Presence.Offline else contact.presence,
            unreadCount = if (isBlocked) 0 else contact.unreadCount,
            isMarkedUnread = !isBlocked && contact.isMarkedUnread,
        )
    }
    // Reuse the login merge's guard against resurrecting circles that have been left.
    return mergeBlockedMemberships(AddressBookSnapshot(retained), blocked).contacts
}

/** Membership comes from SOAP; live presence, pictures and unread state stay on the active roster. */
internal fun mergeAddressBookUpdate(
    current: List<Contact>,
    snapshot: AddressBookSnapshot,
    ownEmail: String,
): List<Contact> {
    val existing = current.associateBy(Contact::id)
    val freshIds = snapshot.contacts.map(Contact::id).toSet()
    val retainedBlocked = if (snapshot.failedBlockedLookup) current.filter {
        it.isBlocked && !it.isGroup && !it.isForwardListContact && it.id !in freshIds
    } else emptyList()
    return (snapshot.contacts + retainedBlocked).map { fresh ->
        val previous = existing[fresh.id]
        val members = if (fresh.id in snapshot.failedMemberLookups) previous?.members.orEmpty() else {
            val oldMembers = previous?.members.orEmpty().associateBy(GroupMember::email)
            fresh.members.map { member ->
                val old = oldMembers[member.email]
                member.copy(
                    displayName = member.displayName.takeUnless { it.equals(member.email, true) }
                        ?: old?.displayName ?: member.displayName,
                    presence = if (member.isPending) member.presence else old?.takeUnless { it.isPending }?.presence ?: member.presence,
                    avatarUri = old?.avatarUri ?: member.avatarUri,
                    clientCapabilities = if (member.isPending) 0L else old?.takeUnless { it.isPending }?.clientCapabilities ?: member.clientCapabilities,
                )
            }
        }
        fresh.copy(
            isBlocked = if (snapshot.failedBlockedLookup) previous?.isBlocked ?: fresh.isBlocked else fresh.isBlocked,
            presence = previous?.presence ?: fresh.presence,
            statusMessage = previous?.statusMessage ?: fresh.statusMessage,
            avatarUri = previous?.avatarUri ?: fresh.avatarUri,
            unreadCount = previous?.unreadCount ?: fresh.unreadCount,
            isMarkedUnread = previous?.isMarkedUnread ?: fresh.isMarkedUnread,
            members = members,
        ).withGroupPresence(ownEmail)
    }
}

internal fun isAddressBookNotification(payload: ByteArray): Boolean = runCatching {
    val root = circleDocumentFactory().newDocumentBuilder().parse(payload.inputStream()).documentElement
    root.localName == "NOTIFICATION" && root.getAttribute("siteid") == "45705"
}.getOrDefault(false)
