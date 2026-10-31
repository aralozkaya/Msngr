package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.*
import org.junit.Test

class GroupFeaturesTest {
    private val me = "me@example.com"
    private val peer = GroupMember("peer@example.com", presence = Presence.Busy, clientCapabilities = 0x800c)
    private fun group(vararg members: GroupMember) = Contact("circle", "Group", "circle@live.com",
        Presence.Online, isGroup = true, members = members.toList())

    @Test fun onlyNonOfflineRecipientsCountIncludingBusyAndAway() {
        val members = arrayOf(GroupMember(me.uppercase(), presence = Presence.Online), peer,
            GroupMember("offline@example.com"))
        assertEquals(GroupFeatures(GroupInkFormat.Isf, true), group(*members).groupFeatures(me))
        val away = GroupMember("away@example.com", presence = Presence.Away)
        assertEquals(GroupFeatures(), group(*members, away).groupFeatures(me))
        assertEquals(GroupFeatures(GroupInkFormat.Isf, true),
            group(*members, away.copy(presence = Presence.Offline)).groupFeatures(me))
    }

    @Test fun unknownClientsBlockEachFeatureAndHandwritingNeedsACommonFormat() {
        assertEquals(GroupFeatures(), group(peer, GroupMember("unknown", presence = Presence.Online)).groupFeatures(me))
        assertEquals(GroupFeatures(GroupInkFormat.Gif, false),
            group(peer, peer.copy(email = "gif", clientCapabilities = INK_GIF_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(peer.copy(clientCapabilities = INK_ISF_CAPABILITY),
            peer.copy(email = "gif", clientCapabilities = INK_GIF_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(winks = true), group(peer.copy(clientCapabilities = WINK_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(GroupMember(me, presence = Presence.Online)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(peer).copy(isBlocked = true).groupFeatures(me))
    }

    @Test fun pendingInvitationsNeverAffectGroupAvailabilityOrRecipientCapabilities() {
        val pending = GroupMember("pending@example.com", presence = Presence.Online, isPending = true)
        assertEquals(Presence.Offline, group(pending).withGroupPresence(me).presence)
        assertEquals(GroupFeatures(), group(pending).groupFeatures(me))
        assertEquals(GroupFeatures(GroupInkFormat.Isf, true), group(peer, pending).groupFeatures(me))
        assertEquals(GroupMemberRole.Unknown,
            group(pending.copy(email = me, role = GroupMemberRole.Owner)).ownGroupRole(me))
    }

    @Test fun clientIdsKeepUnsignedBitsAndNeverReadTheExtendedMaskAsFeatureSupport() {
        assertEquals(2_416_214_060L, parseClientCapabilities("2416214060:1024"))
        assertEquals(0L, parseClientCapabilities("0:32780"))
        for (value in listOf(null, "", "invalid", "-1", "4294967296"))
            assertEquals(0L, parseClientCapabilities(value))
    }
}
