package org.aralozkaya.msngr

import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.Msnp18Protocol
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
        assertEquals(GroupFeatures(GroupInkFormat.Isf, winks = true, nudges = true),
            group(peer.copy(extendedCapabilities = EXTENDED_NUDGE_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(peer.copy(clientCapabilities = 0x800cL or WEB_IM_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(peer.copy(extendedCapabilities = EXTENDED_NUDGE_CAPABILITY or EXTENDED_SMS_ONLY_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(peer.copy(networkId = 32)).groupFeatures(me))
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

    @Test fun clientIdsKeepUnsignedBitsAndParseExtendedAndEndpointCapabilities() {
        assertEquals(2_416_214_060L, parseClientCapabilities("2416214060:1024"))
        assertEquals(1024L, parseExtendedCapabilities("2416214060:1024"))
        assertEquals(0L, parseClientCapabilities("0:32780"))
        assertEquals(32780L, parseExtendedCapabilities("0:32780"))
        assertEquals(
            ParsedClientCapabilities(Msnp18Protocol.CLIENT_ID, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
            parseClientCapabilitiesPair(Msnp18Protocol.CLIENT_IDENTITY),
        )
        for (value in listOf(null, "", "invalid", "-1", "4294967296", "1:2:3")) {
            assertEquals(0L, parseClientCapabilities(value))
            assertEquals(ParsedClientCapabilities(), parseClientCapabilitiesPair(value))
        }
        assertNull(parseEndpointCapabilities("<Data><PSM>Hi</PSM></Data>".toByteArray()))
        assertNull(parseEndpointCapabilities("<!DOCTYPE foo><Data><EndpointData><Capabilities>1:2</Capabilities></EndpointData></Data>".toByteArray()))
        assertEquals(
            ParsedClientCapabilities(Msnp18Protocol.BASE_CAPABILITIES.toLong(), Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
            parseEndpointCapabilities("<Data><EndpointData id=\"{1234}\"><Capabilities>${Msnp18Protocol.ENDPOINT_CAPABILITIES}</Capabilities></EndpointData></Data>".toByteArray()),
        )
        // Secondary web/mobile MPOP endpoint does not poison a compatible desktop endpoint.
        assertEquals(
            ParsedClientCapabilities(Msnp18Protocol.BASE_CAPABILITIES.toLong(), Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
            parseEndpointCapabilities(
                ("<Data><EndpointData id=\"{1}\"><Capabilities>${Msnp18Protocol.ENDPOINT_CAPABILITIES}</Capabilities></EndpointData>" +
                    "<EndpointData id=\"{2}\"><Capabilities>${WEB_IM_CAPABILITY or MOBILE_CLIENT_CAPABILITY}:$EXTENDED_SMS_ONLY_CAPABILITY</Capabilities></EndpointData></Data>").toByteArray(),
            ),
        )
        // When only an incompatible endpoint is present, its flags disable features cleanly.
        assertEquals(
            ParsedClientCapabilities(WEB_IM_CAPABILITY, EXTENDED_SMS_ONLY_CAPABILITY),
            parseEndpointCapabilities(
                "<Data><EndpointData id=\"{2}\"><Capabilities>$WEB_IM_CAPABILITY:$EXTENDED_SMS_ONLY_CAPABILITY</Capabilities></EndpointData></Data>".toByteArray(),
            ),
        )
        assertEquals(
            Msnp18Protocol.CLIENT_ID,
            mergeEndpointBaseCapabilities(0xA000_0020L, Msnp18Protocol.BASE_CAPABILITIES.toLong()),
        )
        assertEquals(0L, mergeEndpointBaseCapabilities(0xA004_802CL, 0L))
    }

    @Test fun privateRecipientFeaturesMatchAdvertisedMsnpFlagsAndGateGroups() {
        fun direct(
            base: Long,
            ext: Long,
            presence: Presence = Presence.Online,
            blocked: Boolean = false,
            networkId: Int = MSNP_WINDOWS_LIVE_NETWORK_ID,
        ) = Contact(
            "peer", "Peer", "peer@example.com", presence, isBlocked = blocked,
            clientCapabilities = base, extendedCapabilities = ext, networkId = networkId,
        ).recipientFeatures(me)

        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(Msnp18Protocol.CLIENT_ID, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        )
        // Profile-linked MSN Mobile bit (0x40) does not restrict desktop features.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(Msnp18Protocol.CLIENT_ID or MOBILE_ENABLED_CAPABILITY, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
        )
        assertEquals(RecipientFeatures(), direct(Msnp18Protocol.CLIENT_ID, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong(), Presence.Offline))
        assertEquals(RecipientFeatures(), direct(Msnp18Protocol.CLIENT_ID, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong(), blocked = true))
        assertEquals(RecipientFeatures(), direct(0L, 0L))
        assertEquals(RecipientFeatures(inkFormat = GroupInkFormat.Gif), direct(INK_GIF_CAPABILITY, 0L))
        assertEquals(RecipientFeatures(winks = true), direct(WINK_CAPABILITY, 0L))
        // P2P features (voice clips, files, photos, activities, real MSNC winks) require chunking (0x20).
        assertEquals(RecipientFeatures(), direct(VOICE_CLIP_CAPABILITY, 0L))
        assertEquals(RecipientFeatures(voiceClips = true), direct(CHUNKING_CAPABILITY or VOICE_CLIP_CAPABILITY, 0L))
        assertEquals(RecipientFeatures(), direct(0x9000_0000L or WINK_CAPABILITY, 0L))
        assertEquals(RecipientFeatures(nudges = true), direct(0x9000_0020L, EXTENDED_NUDGE_CAPABILITY))
        assertEquals(RecipientFeatures(), direct(0L, EXTENDED_FILE_TRANSFER_CAPABILITY))
        assertEquals(RecipientFeatures(files = true), direct(CHUNKING_CAPABILITY, EXTENDED_FILE_TRANSFER_CAPABILITY))
        assertEquals(RecipientFeatures(), direct(0L, EXTENDED_PHOTO_SHARE_CAPABILITY))
        assertEquals(RecipientFeatures(photos = true), direct(CHUNKING_CAPABILITY, EXTENDED_PHOTO_SHARE_CAPABILITY))
        assertEquals(RecipientFeatures(activities = true), direct(0xA000_0020L, EXTENDED_P2PV2_CAPABILITY))
        assertEquals(RecipientFeatures(activities = true), direct(0x2000_0020L, EXTENDED_APPLICATION_MSG_CAPABILITY))
        assertEquals(RecipientFeatures(activities = true), direct(0x2000_0020L or ACTIVITIES_CAPABILITY, 0L))
        assertEquals(RecipientFeatures(), direct(0x1000_0020L, EXTENDED_APPLICATION_MSG_CAPABILITY))

        // Every MSNP client-type incompatibility bit disables all rich features.
        for (incompatibleBase in listOf(
            ONLINE_VIA_MOBILE_CAPABILITY,
            MSN_EXPLORER_CAPABILITY,
            WEB_WATCH_CAPABILITY,
            WEB_IM_CAPABILITY,
            MOBILE_CLIENT_CAPABILITY,
            BOT_CAPABILITY,
        )) {
            assertEquals(
                RecipientFeatures(),
                direct(Msnp18Protocol.CLIENT_ID or incompatibleBase, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()),
            )
        }
        assertEquals(
            RecipientFeatures(),
            direct(Msnp18Protocol.CLIENT_ID, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong() or EXTENDED_SMS_ONLY_CAPABILITY),
        )
        // Federated/non-Windows-Live networks (e.g., 32 Yahoo!, 4 SMS) disable MSNP rich features.
        for (nonLiveNetwork in listOf(2, 4, 32)) {
            assertEquals(
                RecipientFeatures(),
                direct(Msnp18Protocol.CLIENT_ID, Msnp18Protocol.EXTENDED_CAPABILITIES.toLong(), networkId = nonLiveNetwork),
            )
        }
        // Pidgin-like MSNC7 + chunking with no extended capabilities disables unsupported WLM features.
        assertEquals(RecipientFeatures(), direct(0x7000_0020L, 0L))
        // Groups never expose 1:1-only features even when members advertise them.
        val fullMember = GroupMember("peer@example.com", presence = Presence.Online,
            clientCapabilities = Msnp18Protocol.CLIENT_ID,
            extendedCapabilities = Msnp18Protocol.EXTENDED_CAPABILITIES.toLong())
        assertEquals(
            RecipientFeatures(inkFormat = GroupInkFormat.Isf, winks = true, nudges = true),
            group(fullMember).recipientFeatures(me),
        )
    }
}
