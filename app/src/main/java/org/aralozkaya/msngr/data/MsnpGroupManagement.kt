package org.aralozkaya.msngr.data

import org.w3c.dom.Element

internal const val PERSONAL_ADDRESS_BOOK = "00000000-0000-0000-0000-000000000000"
internal val GROUP_GUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** WLM 2009 CircleSave traffic captured on 2026-10-14. */
internal fun buildCreateGroupRequest(ticket: String, name: String, displayName: String): String = groupSoap(ticket, "CircleSave", """
    <CreateCircle xmlns="http://www.msn.com/webservices/AddressBook"><properties>
      <Domain>1</Domain><HostedDomain>live.com</HostedDomain><Type>2</Type><MembershipAccess>0</MembershipAccess>
      <IsPresenceEnabled>true</IsPresenceEnabled><RequestMembershipOption>2</RequestMembershipOption>
      <DisplayName>${validateGroupName(name).groupXml()}</DisplayName></properties>
      <callerInfo><PublicDisplayName>${displayName.groupXml()}</PublicDisplayName></callerInfo>
    </CreateCircle>
""".trimIndent())

internal fun parseGroupOperationResponse(xml: String, action: String): Element {
    val document = circleDocumentFactory().newDocumentBuilder().parse(xml.byteInputStream())
    if (document.getElementsByTagNameNS("*", "Fault").length > 0)
        throw IllegalStateException("The server could not ${when(action) {
            "CreateCircle" -> "create this group"
            "BreakConnection" -> "leave this group"
            else -> "update this group invitation"
        }}. Please refresh and try again.")
    val response = document.getElementsByTagNameNS("*", "${action}Response").item(0) as? Element
        ?: throw IllegalStateException("The group service returned an invalid response.")
    check(response.groupText("clientErrorData").isBlank()) { "The server could not update this group invitation. Please refresh and try again." }
    return response
}

internal fun parseCreatedGroupId(xml: String): String =
    parseGroupOperationResponse(xml, "CreateCircle").getElementsByTagNameNS("*", "Id").item(0)?.textContent
        ?.trim()?.lowercase()?.takeIf(GROUP_GUID::matches)
        ?: throw IllegalStateException("The group service returned an invalid group identifier.")

internal fun buildCreateGroupContactRequest(ticket: String, groupGuid: String, email: String): String {
    require(GROUP_GUID.matches(groupGuid))
    return groupSoap(ticket, "CircleInvite", """
        <CreateContact xmlns="http://www.msn.com/webservices/AddressBook">
          <abHandle><ABId>$groupGuid</ABId><Puid>0</Puid><Cid>0</Cid></abHandle>
          <contactHandle><Email>${email.groupXml()}</Email><Puid>0</Puid><Cid>0</Cid>
            <CircleId>$PERSONAL_ADDRESS_BOOK</CircleId></contactHandle>
        </CreateContact>
    """.trimIndent())
}

internal fun parseCreatedGroupContactId(xml: String): String =
    parseGroupOperationResponse(xml, "CreateContact").groupText("contactId").takeIf(GROUP_GUID::matches)
        ?: throw IllegalStateException("The group service returned an invalid member identifier.")

internal fun buildGroupConnectionRequest(ticket: String, addressBookId: String, contactGuid: String,
                                        role: Int, accept: Boolean = true, message: String = ""): String {
    require(GROUP_GUID.matches(addressBookId) && GROUP_GUID.matches(contactGuid))
    require(role == 0 || role == 3)
    val scenario = if (role == 3) "CircleInvite" else "CircleStatus"
    return groupSoap(ticket, scenario, """
        <ManageWLConnection xmlns="http://www.msn.com/webservices/AddressBook">
          <abHandle><ABId>$addressBookId</ABId><Puid>0</Puid><Cid>0</Cid></abHandle>
          <contactId>$contactGuid</contactId><connection>true</connection><presence>false</presence>
          <action>${if(accept) 1 else 2}</action><relationshipType>5</relationshipType><relationshipRole>$role</relationshipRole>
        </ManageWLConnection>
    """.trimIndent()).replace("</ManageWLConnection>",
        (if (message.isBlank()) "" else "<annotations><Annotation><Name>MSN.IM.InviteMessage</Name><Value>${message.groupXml()}</Value></Annotation></annotations>") +
            "</ManageWLConnection>")
}

internal fun buildLeaveGroupRequest(ticket: String, groupGuid: String, ownContactGuid: String): String {
    require(GROUP_GUID.matches(groupGuid) && GROUP_GUID.matches(ownContactGuid))
    return groupSoap(ticket, "CircleLeave", """
        <BreakConnection xmlns="http://www.msn.com/webservices/AddressBook">
          <abHandle><ABId>$groupGuid</ABId><Puid>0</Puid><Cid>0</Cid></abHandle>
          <contactId>$ownContactGuid</contactId><deleteContact>true</deleteContact><blockContact>false</blockContact>
        </BreakConnection>
    """.trimIndent())
}

internal fun groupSoap(ticket: String, scenario: String, body: String): String = """
    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      <soap:Header><ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
        <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId><IsMigration>false</IsMigration>
        <PartnerScenario>${scenario.groupXml()}</PartnerScenario></ABApplicationHeader>
        <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook"><ManagedGroupRequest>false</ManagedGroupRequest>
          <TicketToken>${ticket.groupXml()}</TicketToken></ABAuthHeader></soap:Header>
      <soap:Body>$body</soap:Body>
    </soap:Envelope>
""".trimIndent()

internal fun String.groupXml(): String = replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

internal fun parseGroupInvitations(result: Element): List<GroupInvitation> {
    val contactInfo = (0 until result.getElementsByTagNameNS("*", "Contact").length).mapNotNull {
        result.getElementsByTagNameNS("*", "Contact").item(it) as? Element
    }.associateBy { it.groupText("contactId").lowercase() }
    val circles = result.getElementsByTagNameNS("*", "CircleInverseInfo")
    return (0 until circles.length).mapNotNull { index ->
        val circle = circles.item(index) as? Element ?: return@mapNotNull null
        val guid = circle.groupText("Id").lowercase()
        if (circle.groupText("Deleted").equals("true", true) || circle.groupText("State") != "WaitingResponse" ||
            !GROUP_GUID.matches(guid)) return@mapNotNull null
        val domain = circle.groupText("HostedDomain").lowercase()
        if (domain.isBlank()) return@mapNotNull null
        val contact = contactInfo[guid]
        val networks = contact?.getElementsByTagNameNS("*", "NetworkInfo")
        val relationship = networks?.let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.firstOrNull {
                it.groupText("RelationshipType") == "5" && it.groupText("DomainId") == "1" &&
                    it.groupText("DomainTag").let { tag -> tag.isBlank() || tag.equals(guid, true) }
            }
        }
        GroupInvitation("$guid@$domain", circle.groupText("DisplayName").ifBlank { circle.groupText("Name") }.ifBlank { "Group" },
            relationship?.groupText("InviterEmail")?.takeIf(String::isNotBlank),
            relationship?.groupText("InviterName")?.takeIf(String::isNotBlank),
            // WLM sends an annotation; Escargot returns it on this circle's relationship.
            relationship?.getElementsByTagNameNS("*", "InviterMessage")?.item(0)?.textContent
                ?.takeIf(String::isNotBlank) ?: contact?.invitationMessageAnnotation())
    }.distinctBy(GroupInvitation::id)
}

internal fun Element.groupText(name: String): String = getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty().trim()

internal data class GroupInviteeRecord(val email: String, val contactGuid: String, val state: String)

internal fun parseGroupInviteeRecords(xml: String): List<GroupInviteeRecord> {
    val document = circleDocumentFactory().newDocumentBuilder().parse(xml.byteInputStream())
    check(document.getElementsByTagNameNS("*", "Fault").length == 0 &&
        document.getElementsByTagNameNS("*", "ABFindContactsPagedResponse").length > 0) { "Could not load the current group members." }
    val contacts = document.getElementsByTagNameNS("*", "Contact")
    return (0 until contacts.length).mapNotNull { index ->
        val contact = contacts.item(index) as? Element ?: return@mapNotNull null
        val guid = contact.groupText("contactId")
        val email = contact.groupText("passportName").lowercase()
        if (contact.groupText("contactType") !in setOf("Live", "LivePending") || !GROUP_GUID.matches(guid) || email.isBlank()) null
        else GroupInviteeRecord(email, guid, contact.groupText("RelationshipState"))
    }
}
