package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.parseProtocolXml
import org.w3c.dom.Element

internal const val MSNP_WINDOWS_LIVE_NETWORK_ID = 1

internal const val ONLINE_VIA_MOBILE_CAPABILITY = 0x01L
internal const val MSN_EXPLORER_CAPABILITY = 0x02L
internal const val INK_GIF_CAPABILITY = 0x04L
internal const val INK_ISF_CAPABILITY = 0x08L
internal const val WEBCAM_CAPABILITY = 0x10L
internal const val CHUNKING_CAPABILITY = 0x20L
internal const val MOBILE_ENABLED_CAPABILITY = 0x40L
internal const val WEB_WATCH_CAPABILITY = 0x80L
internal const val ACTIVITIES_CAPABILITY = 0x100L
internal const val WEB_IM_CAPABILITY = 0x200L
internal const val MOBILE_CLIENT_CAPABILITY = 0x400L
internal const val FEDERATED_CLIENT_CAPABILITY = 0x800L
internal const val DIRECT_IM_CAPABILITY = 0x4000L
internal const val WINK_CAPABILITY = 0x8000L
internal const val BOT_CAPABILITY = 0x20000L
internal const val VOICE_CLIP_CAPABILITY = 0x40000L

internal const val EXTENDED_SMS_ONLY_CAPABILITY = 0x01L
internal const val EXTENDED_VOICE_OVER_MSNP_CAPABILITY = 0x02L
internal const val EXTENDED_APPLICATION_MSG_CAPABILITY = 0x08L
internal const val EXTENDED_RTC_VIDEO_CAPABILITY = 0x10L
internal const val EXTENDED_P2PV2_CAPABILITY = 0x20L

private const val MSNC_VERSION_MASK = 0xF000_0000L
private const val BASE_FLAGS_MASK = 0x0FFF_FFFFL

private const val INCOMPATIBLE_BASE_CAPABILITIES =
    ONLINE_VIA_MOBILE_CAPABILITY or
        MSN_EXPLORER_CAPABILITY or
        WEB_WATCH_CAPABILITY or
        WEB_IM_CAPABILITY or
        MOBILE_CLIENT_CAPABILITY or
        FEDERATED_CLIENT_CAPABILITY or
        BOT_CAPABILITY

private const val INCOMPATIBLE_EXTENDED_CAPABILITIES = EXTENDED_SMS_ONLY_CAPABILITY

internal fun isIncompatibleClientCapabilities(base: Long, extended: Long): Boolean =
    (base and INCOMPATIBLE_BASE_CAPABILITIES) != 0L ||
        (extended and INCOMPATIBLE_EXTENDED_CAPABILITIES) != 0L

internal data class ParsedClientCapabilities(
    val base: Long = 0L,
    val extended: Long = 0L,
)

/** The first mask is the client ID; the optional second mask contains extended capabilities. */
internal fun parseClientCapabilitiesPair(value: String?): ParsedClientCapabilities =
    parseClientCapabilitiesPairOrNull(value) ?: ParsedClientCapabilities()

internal fun parseClientCapabilitiesPairOrNull(value: String?): ParsedClientCapabilities? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(':')
    if (parts.size > 2) return null
    val base = parts[0].toLongOrNull()?.takeIf { it in 0..0xffff_ffffL } ?: return null
    val extended = if (parts.size == 2) {
        parts[1].toLongOrNull()?.takeIf { it in 0..0xffff_ffffL } ?: return null
    } else {
        0L
    }
    return ParsedClientCapabilities(base = base, extended = extended)
}

internal fun parseClientCapabilities(value: String?): Long = parseClientCapabilitiesPair(value).base

internal fun parseExtendedCapabilities(value: String?): Long = parseClientCapabilitiesPair(value).extended

/**
 * Extracts and combines `<EndpointData><Capabilities>` pairs from an MSNP18 UBX payload.
 * When a contact has multiple active endpoints, incompatible web/mobile endpoints do not
 * poison compatible desktop endpoints unless no compatible endpoint exists.
 * Returns null when the payload does not advertise any endpoint capabilities.
 */
internal fun parseEndpointCapabilities(payload: ByteArray): ParsedClientCapabilities? {
    if (payload.isEmpty()) return null
    val text = payload.toString(Charsets.UTF_8)
    if (!text.contains("<Capabilities", ignoreCase = true)) {
        return null
    }
    return runCatching {
        val root = parseProtocolXml(payload).documentElement
        val endpointNodes = root.getElementsByTagName("EndpointData")
        val endpoints = mutableListOf<ParsedClientCapabilities>()
        for (i in 0 until endpointNodes.length) {
            val endpoint = endpointNodes.item(i) as? Element ?: continue
            val capsNodes = endpoint.getElementsByTagName("Capabilities")
            for (j in 0 until capsNodes.length) {
                val parsed = parseClientCapabilitiesPairOrNull(capsNodes.item(j)?.textContent) ?: continue
                endpoints += parsed
            }
        }
        if (endpoints.isEmpty()) return@runCatching null
        val compatible = endpoints.filterNot { isIncompatibleClientCapabilities(it.base, it.extended) }
        val selected = compatible.ifEmpty { endpoints }
        val maxMsnc = selected.maxOf { it.base and MSNC_VERSION_MASK }
        val combinedFlags = selected.fold(0L) { acc, item -> acc or (item.base and BASE_FLAGS_MASK) }
        ParsedClientCapabilities(
            base = maxMsnc or combinedFlags,
            extended = selected.fold(0L) { acc, item -> acc or item.extended },
        )
    }.getOrNull()
}

/** EndpointData capabilities omit the MSNC version high nibble; preserve it when non-zero bits arrive. */
internal fun mergeEndpointBaseCapabilities(existingBase: Long, endpointBase: Long): Long = when {
    endpointBase == 0L -> 0L
    endpointBase ushr 28 != 0L -> endpointBase
    else -> (existingBase and MSNC_VERSION_MASK) or endpointBase
}

internal enum class GroupInkFormat { Isf, Gif }

internal data class GroupFeatures(
    val inkFormat: GroupInkFormat? = null,
    val winks: Boolean = false,
    val nudges: Boolean = false,
)

internal data class RecipientFeatures(
    val inkFormat: GroupInkFormat? = null,
    val winks: Boolean = false,
    val voiceClips: Boolean = false,
    val nudges: Boolean = false,
    val files: Boolean = false,
    val photos: Boolean = false,
    val activities: Boolean = false,
)

private fun effectiveMsncVersion(base: Long, extended: Long): Long {
    val msncVersion = base ushr 28
    if (msncVersion != 0L) return msncVersion
    // MSNP18 EndpointData <Capabilities> can omit the MSNC high nibble; P2Pv2 (0x20) implies MSNC10+.
    return if ((extended and EXTENDED_P2PV2_CAPABILITY) != 0L) 10L else 0L
}

private fun supportsWinkCapabilities(base: Long, extended: Long, networkId: Int): Boolean {
    if (networkId != MSNP_WINDOWS_LIVE_NETWORK_ID || isIncompatibleClientCapabilities(base, extended)) return false
    val msncVersion = effectiveMsncVersion(base, extended)
    return (base and WINK_CAPABILITY) != 0L &&
        (msncVersion == 0L || (base and CHUNKING_CAPABILITY) != 0L)
}

private fun supportsNudgeCapabilities(base: Long, extended: Long, networkId: Int): Boolean {
    if (networkId != MSNP_WINDOWS_LIVE_NETWORK_ID || isIncompatibleClientCapabilities(base, extended)) return false
    return (base and CHUNKING_CAPABILITY) != 0L && effectiveMsncVersion(base, extended) >= 4L
}

/** Members come from the accepted membership/connected circle roster, not the contact list. */
internal fun Contact.groupFeatures(ownEmail: String): GroupFeatures {
    if (!isGroup || isBlocked || !presence.isOnline) return GroupFeatures()
    val recipients = joinedMembers.filter { it.presence.isOnline && !it.email.equals(ownEmail, true) }
    if (recipients.isEmpty()) return GroupFeatures()
    if (recipients.any {
            it.networkId != MSNP_WINDOWS_LIVE_NETWORK_ID ||
                isIncompatibleClientCapabilities(it.clientCapabilities, it.extendedCapabilities)
        }
    ) {
        return GroupFeatures()
    }
    fun allSupport(mask: Long) = recipients.all { it.clientCapabilities and mask != 0L }
    return GroupFeatures(
        inkFormat = when {
            allSupport(INK_ISF_CAPABILITY) -> GroupInkFormat.Isf
            allSupport(INK_GIF_CAPABILITY) -> GroupInkFormat.Gif
            else -> null
        },
        winks = recipients.all { supportsWinkCapabilities(it.clientCapabilities, it.extendedCapabilities, it.networkId) },
        nudges = recipients.all { supportsNudgeCapabilities(it.clientCapabilities, it.extendedCapabilities, it.networkId) },
    )
}

/**
 * Computes supported interactive features for either a 1:1 contact or an MSNP18 circle group.
 *
 * Capability evaluation targets MSNP8–MSNP18 (up to WLM 2009 / MSNC10) and ignores post-WLM 2009
 * extended flags while remaining backwards compatible with newer clients (such as WLM 2011/2012)
 * through standard base capability bits and the MSNC version nibble:
 * - MSNP8–MSNP15 (MSNC1–MSNC8): P2Pv1 files (`MSNC1+`), P4 games/activities (`MSNC2+`), nudges
 *   (`MSNC4+`), winks (`0x8000`), voice clips (`0x40000`), and GIF/ISF ink (`0x04`/`0x08`).
 * - MSNP16–MSNP18 (MSNC9–MSNC10, e.g. WLM 2009 `2688319548:48`): adds
 *   `EXTENDED_P2PV2_CAPABILITY` (`0x20`) and WLM 2009+ Photo Sharing (`msncVersion >= 10` with
 *   P2Pv2 or rich desktop capabilities).
 */
internal fun Contact.recipientFeatures(ownEmail: String): RecipientFeatures {
    if (isBlocked || !presence.isOnline) return RecipientFeatures()
    if (isGroup) {
        val group = groupFeatures(ownEmail)
        return RecipientFeatures(
            inkFormat = group.inkFormat,
            winks = group.winks,
            nudges = group.nudges,
        )
    }
    val base = clientCapabilities
    val extended = extendedCapabilities
    if (networkId != MSNP_WINDOWS_LIVE_NETWORK_ID || isIncompatibleClientCapabilities(base, extended)) {
        return RecipientFeatures()
    }
    val msncVersion = effectiveMsncVersion(base, extended)
    val supportsChunking = (base and CHUNKING_CAPABILITY) != 0L
    val supportsRichDesktop = (base and (WINK_CAPABILITY or VOICE_CLIP_CAPABILITY)) != 0L
    val explicitActivitySupport =
        (base and (ACTIVITIES_CAPABILITY or DIRECT_IM_CAPABILITY)) != 0L ||
            (extended and (EXTENDED_APPLICATION_MSG_CAPABILITY or EXTENDED_P2PV2_CAPABILITY)) != 0L
    val legacyOfficialDesktopActivities =
        (msncVersion in 2L..3L && (base and (INK_GIF_CAPABILITY or INK_ISF_CAPABILITY)) != 0L) ||
            (msncVersion >= 4L && supportsRichDesktop)
    val supportsP2pActivities = supportsChunking && (
        (msncVersion != 1L && explicitActivitySupport) ||
            legacyOfficialDesktopActivities
        )
    return RecipientFeatures(
        inkFormat = when {
            (base and INK_ISF_CAPABILITY) != 0L -> GroupInkFormat.Isf
            (base and INK_GIF_CAPABILITY) != 0L -> GroupInkFormat.Gif
            else -> null
        },
        winks = supportsWinkCapabilities(base, extended, networkId),
        voiceClips = supportsChunking && (base and VOICE_CLIP_CAPABILITY) != 0L,
        nudges = supportsNudgeCapabilities(base, extended, networkId),
        files = supportsChunking && msncVersion >= 1L,
        photos = supportsChunking &&
            msncVersion >= 10L &&
            ((extended and EXTENDED_P2PV2_CAPABILITY) != 0L || supportsRichDesktop),
        activities = supportsP2pActivities,
    )
}
