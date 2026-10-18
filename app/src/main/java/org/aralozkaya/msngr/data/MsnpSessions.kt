package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.w3c.dom.Element
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

data class AccountSession(
    val id: String,
    val deviceName: String,
    val isCurrent: Boolean = false,
    val presence: Presence? = null,
)

/** Account UBX publications contain a complete list, including private device names. */
internal fun parseAccountSessions(payload: ByteArray, current: AccountSession,
    previous: List<AccountSession>): List<AccountSession>? = runCatching {
    if (payload.isEmpty()) return@runCatching listOf(current)
    if (payload.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) return@runCatching null
    val factory = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val root = factory.newDocumentBuilder().parse(payload.inputStream()).documentElement
    if (root.tagName != "Data") return@runCatching null
    val sessions = linkedMapOf(current.id to current)
    val known = previous.associateBy(AccountSession::id)
    for (tag in listOf("EndpointData", "PrivateEndpointData")) {
        val nodes = root.getElementsByTagName(tag)
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as Element
            val id = normalizeEndpointId(node.getAttribute("id")) ?: continue
            val old = sessions[id] ?: known[id] ?: AccountSession(id, "Unnamed device")
            val name = node.getElementsByTagName("EpName").item(0)?.textContent?.trim()
            val state = node.getElementsByTagName("State").item(0)?.textContent
            sessions[id] = old.copy(
                deviceName = name?.takeIf(String::isNotBlank) ?: old.deviceName,
                isCurrent = id == current.id,
                presence = state?.let(Presence::fromMsnpCode) ?: old.presence,
            )
        }
    }
    sessions.values.toList()
}.getOrNull()

internal fun normalizeEndpointId(value: String): String? = runCatching {
    val id = value.trim().trim('{', '}')
    UUID.fromString(id).toString().takeIf { it.equals(id, ignoreCase = true) }
}.getOrNull()

internal fun remoteLogoutRequest(command: MsnpCommand, email: String, endpointId: String): Boolean {
    if (command.verb != "UBN" || command.parameters.getOrNull(1) !in setOf("4", "8")) return false
    val sender = command.parameters.firstOrNull().orEmpty().substringAfter(':')
    if (!sender.substringBefore(';').equals(email, ignoreCase = true)) return false
    // A broadcast is also echoed to its sender. Only another session can revoke this one.
    val senderId = sender.substringAfter(';', "").let(::normalizeEndpointId)
    val body = command.payload?.toString(Charsets.UTF_8)
    return senderId != endpointId && body in setOf("gtfo", "goawyplzthxbye", "goawyplzthxbye-nomorempop")
}
