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
            group(peer.copy(clientCapabilities = 0x4000_802CL)).groupFeatures(me))
        assertEquals(GroupFeatures(), group(peer.copy(clientCapabilities = 0x800cL or WEB_IM_CAPABILITY)).groupFeatures(me))
        assertEquals(GroupFeatures(),
            group(peer.copy(clientCapabilities = 0x4000_802CL, extendedCapabilities = EXTENDED_SMS_ONLY_CAPABILITY)).groupFeatures(me))
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
        // Combining endpoints of differing MSNC versions keeps max MSNC nibble instead of bitwise OR.
        assertEquals(
            ParsedClientCapabilities(0x805D_803CL, 0L),
            parseEndpointCapabilities(
                ("<Data><EndpointData id=\"{1}\"><Capabilities>2153611324:0</Capabilities></EndpointData>" +
                    "<EndpointData id=\"{2}\"><Capabilities>${0x5004_802CL}:0</Capabilities></EndpointData></Data>").toByteArray(),
            ),
        )
        assertEquals(
            ParsedClientCapabilities(0xA07D_803CL, 0x30L),
            parseEndpointCapabilities(
                ("<Data><EndpointData id=\"{1}\"><Capabilities>2688319548:48</Capabilities></EndpointData>" +
                    "<EndpointData id=\"{2}\"><Capabilities>2153611324:0</Capabilities></EndpointData></Data>").toByteArray(),
            ),
        )
        // Merging stripped WLM 2012 EndpointData capabilities preserves MSNC12 from NLN.
        assertEquals(
            0xC03C_D13CL,
            mergeEndpointBaseCapabilities(0xC000_0020L, 0x003C_D13CL),
        )
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
        assertEquals(RecipientFeatures(nudges = true, files = true), direct(0x9000_0020L, 0L))
        // Post-WLM 2009 extended bits alone are ignored when MSNP8-MSNP18 base/MSNC requirements are absent.
        assertEquals(RecipientFeatures(), direct(CHUNKING_CAPABILITY, 0x4002_1400L))
        assertEquals(
            RecipientFeatures(nudges = true, files = true, photos = true, activities = true),
            direct(0xA000_0020L, EXTENDED_P2PV2_CAPABILITY),
        )
        assertEquals(
            RecipientFeatures(files = true, activities = true),
            direct(0x2000_0020L, EXTENDED_APPLICATION_MSG_CAPABILITY),
        )
        assertEquals(
            RecipientFeatures(files = true, activities = true),
            direct(0x2000_0020L or ACTIVITIES_CAPABILITY, 0L),
        )
        assertEquals(
            RecipientFeatures(files = true),
            direct(0x1000_0020L, EXTENDED_APPLICATION_MSG_CAPABILITY),
        )

        // Official WLM 2009 (MSNC10 + P2Pv2, without Wave 4 extended flags) supports all desktop features in 1:1 & groups.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(2_688_319_548L, 48L),
        )
        assertEquals(
            GroupFeatures(inkFormat = GroupInkFormat.Isf, winks = true, nudges = true),
            group(
                GroupMember("wlm2009@example.com", presence = Presence.Online,
                    clientCapabilities = 2_688_319_548L, extendedCapabilities = 48L),
            ).groupFeatures(me),
        )
        // WLM 2009 EndpointData with stripped MSNC high nibble infers MSNC10+ from P2Pv2 (0x20).
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(0x003C_803CL, 0x30L),
        )
        // Official WLM 2011/2012 (MSNC10 + Wave 4 extended flags) supports all desktop features.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(2_688_340_284L, 2_550_273_040L),
        )
        // Official WLM 8.5 (MSNC8, extended = 0) supports all features except WLM 2009 photo sharing.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = false, activities = true),
            direct(2_153_611_324L, 0L),
        )
        // MSN 7.5 (MSNC5 + chunking + ink + winks + voice clips) supports everything except photo sharing.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = false, activities = true),
            direct(0x5004_802CL, 0L),
        )
        // MSN 7.0 (MSNC4 + chunking + ink + winks) lacks voice clips and photo sharing.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = false, nudges = true,
                files = true, photos = false, activities = true),
            direct(0x4000_802CL, 0L),
        )
        // MSN 6.0 (MSNC1 + chunking + ink) supports ink and file transfers only.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, files = true),
            direct(0x1000_002CL, 0L),
        )
        // MSN 6.1 and MSN 6.2 (MSNC2/MSNC3 + chunking + ink) also support P4 games/activities.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Gif, files = true, activities = true),
            direct(0x2000_0024L, 0L),
        )
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, files = true, activities = true),
            direct(0x2000_002CL, 0L),
        )
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, files = true, activities = true),
            direct(0x3000_002CL, 0L),
        )
        // WLM 2009 over MSNP15/bridged server omitting :extended still exposes desktop photo sharing, while minimal MSNC10 keeps it disabled.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(0xA03C_803CL, 0L),
        )
        assertEquals(
            RecipientFeatures(nudges = true, files = true),
            direct(0xA000_0020L, 0L),
        )
        // WLM 2012 (MSNC12 with or without extended flags) remains backwards compatible in 1:1 and groups.
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(0xC03C_D13CL, 0x9802_1410L),
        )
        assertEquals(
            RecipientFeatures(GroupInkFormat.Isf, winks = true, voiceClips = true, nudges = true,
                files = true, photos = true, activities = true),
            direct(0xC03C_803CL, 0L),
        )
        assertEquals(
            GroupFeatures(inkFormat = GroupInkFormat.Isf, winks = true, nudges = true),
            group(
                GroupMember("wlm2012@example.com", presence = Presence.Online,
                    clientCapabilities = 0xC03C_803CL, extendedCapabilities = 0L),
            ).groupFeatures(me),
        )
        assertEquals(
            RecipientFeatures(activities = true),
            direct(CHUNKING_CAPABILITY or DIRECT_IM_CAPABILITY, 0L),
        )
        // Messenger for Mac 8 (MSNC4 + chunking + extended file transfer, without ink/winks/voice/photos/activities).
        assertEquals(
            RecipientFeatures(nudges = true, files = true),
            direct(0x40B0_0020L, 0x0002_0010L),
        )
        // Pidgin-like MSNC7 + chunking (and optional GIF ink) supports nudges and files, but not winks, voice clips, photos, or activities.
        assertEquals(
            RecipientFeatures(inkFormat = GroupInkFormat.Gif, nudges = true, files = true),
            direct(0x7000_0024L, 0L),
        )
        assertEquals(
            RecipientFeatures(nudges = true, files = true),
            direct(0x7000_0020L, 0L),
        )
        // Escargot non-MSN bridge without chunking (0x20) disables all interactive features.
        assertEquals(RecipientFeatures(), direct(0x4000_0000L, 0L))

        // Every MSNP client-type incompatibility bit disables all rich features.
        for (incompatibleBase in listOf(
            ONLINE_VIA_MOBILE_CAPABILITY,
            MSN_EXPLORER_CAPABILITY,
            WEB_WATCH_CAPABILITY,
            WEB_IM_CAPABILITY,
            MOBILE_CLIENT_CAPABILITY,
            FEDERATED_CLIENT_CAPABILITY,
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
