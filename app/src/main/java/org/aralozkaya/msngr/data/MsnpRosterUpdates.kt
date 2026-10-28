package org.aralozkaya.msngr.data

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
                    presence = old?.presence ?: member.presence,
                    avatarUri = old?.avatarUri ?: member.avatarUri,
                    clientCapabilities = old?.clientCapabilities ?: member.clientCapabilities,
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
