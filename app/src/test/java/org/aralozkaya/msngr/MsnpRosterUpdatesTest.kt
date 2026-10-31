package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.GroupMemberRole
import org.aralozkaya.msngr.data.isAddressBookNotification
import org.aralozkaya.msngr.data.mergeAddressBookUpdate
import org.aralozkaya.msngr.data.mergeBlockedMemberships
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsnpRosterUpdatesTest {
    @Test fun refreshKeepsLivePresenceAndUnreadStateButRemovesDepartedMembers() {
        val me = GroupMember("me@example.com", "Me", Presence.Online)
        val peer = GroupMember("peer@example.com", "Peer :-)", Presence.Away, avatarUri = "data:photo")
        val group = Contact("group@live.com", "Old", "group@live.com", Presence.Online,
            isGroup = true, members = listOf(me, peer), unreadCount = 3, isMarkedUnread = true)
        val snapshot = AddressBookSnapshot(listOf(group.copy(nickname = "New", members = listOf(
            GroupMember(me.email), GroupMember(peer.email),
        ))))
        val refreshed = mergeAddressBookUpdate(listOf(group), snapshot, me.email).single()
        assertEquals("New", refreshed.displayName)
        assertEquals(Presence.Online, refreshed.presence)
        assertEquals(peer, refreshed.members.last())
        assertEquals(3, refreshed.unreadCount)
        assertTrue(refreshed.isMarkedUnread)
        val departed = mergeAddressBookUpdate(listOf(group), snapshot.copy(contacts = listOf(group.copy(members = listOf(me)))), me.email).single()
        assertEquals(Presence.Offline, departed.presence)
        assertEquals(listOf(me), departed.members)
        val failed = snapshot.copy(contacts = listOf(group.copy(members = emptyList())), failedMemberLookups = setOf(group.id))
        assertEquals(group.members, mergeAddressBookUpdate(listOf(group), failed, me.email).single().members)
    }

    @Test fun staleBlockedCircleCannotResurrectRemovedGroup() {
        val blocked = Contact("group@live.com", "Old", "group@live.com", Presence.Offline, isGroup = true, isBlocked = true)
        assertTrue(mergeBlockedMemberships(AddressBookSnapshot(emptyList()), listOf(blocked)).contacts.isEmpty())
    }

    @Test fun refreshUpdatesAccessLevelsWhileKeepingLiveMemberState() {
        val peer = GroupMember("peer@example.com", "Peer", Presence.Away,
            avatarUri = "data:photo", role = GroupMemberRole.Administrator, clientCapabilities = 0x800c)
        val group = Contact("group@live.com", "Test", "group@live.com", Presence.Online,
            isGroup = true, members = listOf(peer))
        val snapshot = AddressBookSnapshot(listOf(group.copy(members = listOf(
            GroupMember(peer.email, role = GroupMemberRole.Owner),
        ))))
        val refreshed = mergeAddressBookUpdate(listOf(group), snapshot, "me@example.com").single()
        assertEquals(peer.copy(role = GroupMemberRole.Owner), refreshed.members.single())
        val failed = snapshot.copy(failedMemberLookups = setOf(group.id))
        assertEquals(peer, mergeAddressBookUpdate(listOf(group), failed, "me@example.com").single().members.single())
    }

    @Test fun contactRefreshKeepsLiveStateAndDoesNotUnblockOnLookupFailure() {
        val peer = Contact("peer@example.com", "Old", "peer@example.com", Presence.Busy,
            isBlocked = true, avatarUri = "data:picture", unreadCount = 2)
        val blockedOnly = peer.copy(id = "blocked@example.com", email = "blocked@example.com", isForwardListContact = false)
        val snapshot = AddressBookSnapshot(listOf(peer.copy(nickname = "Updated", presence = Presence.Offline,
            isBlocked = false, avatarUri = null, unreadCount = 0)), failedBlockedLookup = true)
        val retained = mergeAddressBookUpdate(listOf(peer, blockedOnly), snapshot, "me@example.com")
        assertEquals(peer.copy(nickname = "Updated"), retained.first())
        assertEquals(blockedOnly, retained.last())
        val refreshed = mergeAddressBookUpdate(retained, snapshot.copy(failedBlockedLookup = false), "me@example.com")
        assertEquals(listOf(peer.copy(nickname = "Updated", isBlocked = false)), refreshed)
    }

    @Test fun refreshTracksPendingAcceptanceAndRemovalAndKeepsInvitationsOnLookupFailure() {
        val pending = GroupMember("pending@example.com", "Pending person", avatarUri = "data:picture", isPending = true)
        val group = Contact("group@live.com", "Test", "group@live.com", Presence.Offline,
            isGroup = true, members = listOf(pending))
        val accepted = group.copy(members = listOf(GroupMember(pending.email, role = GroupMemberRole.User)))
        val refreshed = mergeAddressBookUpdate(listOf(group), AddressBookSnapshot(listOf(accepted)), "me@example.com").single()
        assertEquals(pending.copy(isPending = false, role = GroupMemberRole.User), refreshed.members.single())
        assertEquals(1, refreshed.joinedMembers.size)
        val empty = AddressBookSnapshot(listOf(group.copy(members = emptyList())))
        assertTrue(mergeAddressBookUpdate(listOf(group), empty, "me@example.com").single().members.isEmpty())
        assertEquals(listOf(pending), mergeAddressBookUpdate(listOf(group),
            empty.copy(failedMemberLookups = setOf(group.id)), "me@example.com").single().members)

        val staleOnline = group.copy(members = listOf(pending.copy(isPending = false, presence = Presence.Online, clientCapabilities = 0x800c)))
        val reinvited = mergeAddressBookUpdate(listOf(staleOnline), AddressBookSnapshot(listOf(group)), "me@example.com").single()
        assertEquals(Presence.Offline, reinvited.presence)
        assertEquals(listOf(pending), reinvited.members)
    }

    @Test fun addressBookNotificationIsFramedWithoutATransactionAndIgnoresOtherServices() {
        val body = "<NOTIFICATION siteid=\"45705\"><MSG><BODY>Changed</BODY></MSG></NOTIFICATION>".toByteArray()
        val wire = "NOT ${body.size}\r\n".toByteArray() + body + "QNG 40\r\n".toByteArray()
        val frames = MsnpFrameDecoder().feed(wire)
        assertEquals(listOf("NOT", "QNG"), frames.map { it.verb })
        assertNull(frames.first().transactionId)
        assertTrue(isAddressBookNotification(frames.first().payload!!))
        assertFalse(isAddressBookNotification("<NOTIFICATION siteid=\"1\"/>".toByteArray()))
    }
}
