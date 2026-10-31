package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.enums.Presence
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** MSNP18-era Address Book SOAP client used to load forward-list contacts after SSO. */
internal object MsnpAddressBookService {
    suspend fun createGroup(host: String, ticket: String, name: String, displayName: String): String = withContext(Dispatchers.IO) {
        parseCreatedGroupId(postSoap(host, "/abservice/SharingService.asmx",
            "http://www.msn.com/webservices/AddressBook/CreateCircle", buildCreateGroupRequest(ticket, name, displayName)))
    }

    suspend fun inviteGroupMember(host: String, ticket: String, groupGuid: String, email: String, message: String = "") = withContext(Dispatchers.IO) {
        val roster = parseGroupInviteeRecords(postSoap(host, "/abservice/abservice.asmx", ADDRESS_BOOK_SOAP_ACTION,
            buildAddressBookRequest(ticket, groupGuid)))
        val existing = roster.firstOrNull { it.email.equals(email, true) }
        // Retry after a lost response or a reconnect without resending a persisted invitation.
        if (existing?.state in setOf("2", "3")) return@withContext
        require(roster.count { it.state in setOf("2", "3") } < 20) { "A group can have up to 20 members." }
        val contactGuid = existing?.contactGuid ?: parseCreatedGroupContactId(postSoap(host, "/abservice/abservice.asmx",
            "http://www.msn.com/webservices/AddressBook/CreateContact", buildCreateGroupContactRequest(ticket, groupGuid, email)))
        parseGroupOperationResponse(postSoap(host, "/abservice/abservice.asmx",
            "http://www.msn.com/webservices/AddressBook/ManageWLConnection",
            buildGroupConnectionRequest(ticket, groupGuid, contactGuid, role = 3, message = message)), "ManageWLConnection")
        Unit
    }

    suspend fun respondToGroupInvitation(host: String, ticket: String, groupGuid: String, accept: Boolean) = withContext(Dispatchers.IO) {
        parseGroupOperationResponse(postSoap(host, "/abservice/abservice.asmx",
            "http://www.msn.com/webservices/AddressBook/ManageWLConnection",
            buildGroupConnectionRequest(ticket, PERSONAL_ADDRESS_BOOK, groupGuid, role = 0, accept = accept)), "ManageWLConnection")
        Unit
    }

    suspend fun fetchGroupMembers(host: String, ticket: String, groupGuid: String): List<GroupMember> = withContext(Dispatchers.IO) {
        parseCircleMembers(postSoap(host, "/abservice/abservice.asmx", ADDRESS_BOOK_SOAP_ACTION, buildAddressBookRequest(ticket, groupGuid)))
    }

    suspend fun leaveGroup(host: String, ticket: String, groupGuid: String, ownContactGuid: String) = withContext(Dispatchers.IO) {
        parseGroupOperationResponse(postSoap(host, "/abservice/abservice.asmx",
            "http://www.msn.com/webservices/AddressBook/BreakConnection", buildLeaveGroupRequest(ticket, groupGuid, ownContactGuid)), "BreakConnection")
        Unit
    }
    private const val ADDRESS_BOOK_SOAP_ACTION =
        "http://www.msn.com/webservices/AddressBook/ABFindContactsPaged"
    private const val MEMBERSHIP_SOAP_ACTION =
        "http://www.msn.com/webservices/AddressBook/FindMembership"

    suspend fun addContact(host: String, ticket: String, email: String, displayName: String, message: String = ""): String =
        withContext(Dispatchers.IO) {
            parseAddContactResponse(postSoap(host, "/abservice/abservice.asmx",
                "http://www.msn.com/webservices/AddressBook/ABContactAdd",
                buildAddContactRequest(ticket, email, displayName, message = message)))
        }

    suspend fun removeContact(host: String, ticket: String, contactGuid: String) = withContext(Dispatchers.IO) {
        parseContactUpdateResponse(postSoap(host, "/abservice/abservice.asmx",
            "http://www.msn.com/webservices/AddressBook/ABContactUpdate",
            buildContactMessengerUpdateRequest(ticket, contactGuid, isMessengerUser = false)))
    }

    suspend fun restoreContact(host: String, ticket: String, contactGuid: String) = withContext(Dispatchers.IO) {
        parseContactUpdateResponse(postSoap(host, "/abservice/abservice.asmx",
            "http://www.msn.com/webservices/AddressBook/ABContactUpdate",
            buildContactMessengerUpdateRequest(ticket, contactGuid, isMessengerUser = true)))
    }

    suspend fun fetchMemberships(host: String, ticket: String, email: String): MessengerMemberships =
        withContext(Dispatchers.IO) {
            parseMessengerMemberships(postSoap(host, "/abservice/SharingService.asmx",
                MEMBERSHIP_SOAP_ACTION, buildMembershipRequest(ticket)), email)
        }

    suspend fun changeMembership(host: String, ticket: String, email: String, role: String,
                                 remove: Boolean, membershipId: String?) = withContext(Dispatchers.IO) {
        val action = if (remove) "DeleteMember" else "AddMember"
        parseMembershipChangeResponse(postSoap(host, "/abservice/SharingService.asmx",
            "http://www.msn.com/webservices/AddressBook/$action",
            buildMembershipChangeRequest(ticket, email, role, remove, membershipId)), action)
    }

    suspend fun acceptContact(host: String, ticket: String, email: String, displayName: String): String =
        withContext(Dispatchers.IO) {
            parseAddContactResponse(postSoap(host, "/abservice/abservice.asmx",
                "http://www.msn.com/webservices/AddressBook/ABContactAdd",
                buildAddContactRequest(ticket, email, displayName, "ContactMsgrAPI")))
        }

    suspend fun fetchContacts(
        host: String,
        ticket: String,
        signedInEmail: String,
    ): AddressBookSnapshot = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val addressBookResponse = async {
                    postSoap(
                        host = host,
                        path = "/abservice/abservice.asmx",
                        soapAction = ADDRESS_BOOK_SOAP_ACTION,
                        body = buildAddressBookRequest(ticket),
                    )
                }
                val blockedContacts = async {
                    runCatching {
                        parseMessengerMemberships(
                            postSoap(
                                host = host,
                                path = "/abservice/SharingService.asmx",
                                soapAction = MEMBERSHIP_SOAP_ACTION,
                                body = buildMembershipRequest(ticket),
                            ),
                            signedInEmail,
                        )
                    }
                }
                val addressBook = parseAddressBook(
                    addressBookResponse.await(),
                    signedInEmail,
                )
                val blockedResult = blockedContacts.await()
                val memberships = blockedResult.getOrNull()
                val merged = mergeMessengerMemberships(addressBook, memberships)
                val failedMemberLookups = mutableSetOf<String>()
                merged.copy(contacts = merged.contacts.map { contact ->
                    if (!contact.isGroup) contact else contact.copy(members = runCatching {
                        parseCircleMembers(postSoap(
                            host, "/abservice/abservice.asmx", ADDRESS_BOOK_SOAP_ACTION,
                            buildAddressBookRequest(ticket, contact.email.substringBefore('@')),
                        ))
                    }.getOrElse { failedMemberLookups += contact.id; emptyList() })
                }, failedMemberLookups = failedMemberLookups, failedBlockedLookup = blockedResult.isFailure)
            }
        } catch (error: AddressBookException) {
            throw error
        } catch (error: Exception) {
            throw AddressBookException("Could not load the Messenger contact list.", error)
        }
    }

    private fun postSoap(
        host: String,
        path: String,
        soapAction: String,
        body: String,
    ): String {
        val connection = (URL(
            "https://${host.addressBookHost()}$path",
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MILLIS
            readTimeout = HTTP_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", soapAction)
            setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                if (soapAction.endsWith("/AddMember") || soapAction.endsWith("/DeleteMember"))
                    parseMembershipChangeResponse(responseBody, soapAction.substringAfterLast('/'))
                if (soapAction.endsWith("/ABContactAdd")) parseAddContactResponse(responseBody)
                if (soapAction.endsWith("/ABContactUpdate")) parseContactUpdateResponse(responseBody)
                val fault = parseAddressBookFault(responseBody)
                throw AddressBookException(
                    fault.ifBlank { "The address-book service returned HTTP $status." },
                )
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequest(ticket: String): String = buildAddressBookRequest(ticket)

    private const val HTTP_TIMEOUT_MILLIS = 10_000
}

/** Captured from WLM 2009's wlcomm.exe, with mobile numbers and categories omitted. */
internal fun buildAddContactRequest(ticket: String, email: String, displayName: String,
                                    scenario: String = "ContactSave", message: String = ""): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      <soap:Header>
        <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
          <IsMigration>false</IsMigration><PartnerScenario>${scenario.escapeAddressBookXml()}</PartnerScenario>
        </ABApplicationHeader>
        <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ManagedGroupRequest>false</ManagedGroupRequest><TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
        </ABAuthHeader>
      </soap:Header>
      <soap:Body>
        <ABContactAdd xmlns="http://www.msn.com/webservices/AddressBook">
          <abId>00000000-0000-0000-0000-000000000000</abId>
          <contacts><Contact><contactInfo>
            <passportName>${email.escapeAddressBookXml()}</passportName><isMessengerUser>true</isMessengerUser>
            <MessengerMemberInfo><DisplayName>${displayName.escapeAddressBookXml()}</DisplayName></MessengerMemberInfo>
          </contactInfo></Contact></contacts>
          <options><EnableAllowListManagement>true</EnableAllowListManagement></options>
        </ABContactAdd>
      </soap:Body>
    </soap:Envelope>
""".trimIndent().replace("<MessengerMemberInfo>", "<MessengerMemberInfo>" +
    if (message.isBlank()) "" else "<PendingAnnotations><Annotation><Name>MSN.IM.InviteMessage</Name><Value>${message.escapeAddressBookXml()}</Value></Annotation></PendingAnnotations>")

internal fun parseAddContactResponse(xml: String): String {
    val document = try {
        secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
    } catch (error: Exception) {
        throw AddressBookException("The contact service returned an invalid response.", error)
    }
    val fault = document.getElementsByTagNameNS("*", "Fault").item(0) as? Element
    if (fault != null) {
        val message = when (fault.descendantText("errorcode")) {
            "ContactAlreadyExists" -> "This person is already in your contacts."
            "InvalidPassportUser", "InvalidUser", "PassportNotFound" -> "No Messenger account was found for this email address."
            "EmailMissingAtSign", "EmailMissingDot", "InvalidEmail" -> "Enter a valid instant messaging email address."
            else -> "The server could not add this contact. Please try again."
        }
        throw AddressBookException(message)
    }
    val result = document.getElementsByTagNameNS("*", "ABContactAddResult").item(0) as? Element
    val guid = result?.descendantText("guid").orEmpty()
    if (!CONTACT_STORAGE_ID.matches(guid)) throw AddressBookException("The contact service returned an invalid response.")
    return guid
}

/** WLM's default Delete contact removes Messenger membership, retaining its address-book entry. */
internal fun buildContactMessengerUpdateRequest(ticket: String, contactGuid: String, isMessengerUser: Boolean): String {
    require(CONTACT_STORAGE_ID.matches(contactGuid)) { "The contact identifier is invalid." }
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration><PartnerScenario>BlockUnblock</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest><TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <ABContactUpdate xmlns="http://www.msn.com/webservices/AddressBook">
              <abId>00000000-0000-0000-0000-000000000000</abId>
              <contacts><Contact><contactId>$contactGuid</contactId>
                <contactInfo><isMessengerUser>$isMessengerUser</isMessengerUser></contactInfo>
                <propertiesChanged>IsMessengerUser</propertiesChanged>
              </Contact></contacts>
            </ABContactUpdate>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()
}

internal fun parseContactUpdateResponse(xml: String) {
    val document = try {
        secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
    } catch (error: Exception) {
        throw AddressBookException("The contact service returned an invalid response.", error)
    }
    if (document.getElementsByTagNameNS("*", "Fault").length > 0) {
        throw AddressBookException("The server could not update this contact. Please try again.")
    }
    if (document.getElementsByTagNameNS("*", "ABContactUpdateResponse").length == 0) {
        throw AddressBookException("The contact service returned an invalid response.")
    }
}

internal data class AddressBookSnapshot(
    val contacts: List<Contact>,
    val ownerDisplayName: String? = null,
    val contactStorageIds: Map<String, String> = emptyMap(),
    val circleTicket: String? = null,
    val failedMemberLookups: Set<String> = emptySet(),
    val failedBlockedLookup: Boolean = false,
    val contactRequests: List<ContactRequest> = emptyList(),
    val groupInvitations: List<GroupInvitation> = emptyList(),
    val ownerCid: String? = null,
)

/** WLM 2009 ContactMsgrAPI request, including the server's opaque Pending membership ID. */
internal fun buildMembershipChangeRequest(ticket: String, email: String, role: String,
                                         remove: Boolean, membershipId: String? = null): String {
    require(role in setOf("Pending", "Reverse", "Allow", "Block"))
    val action = if (remove) "DeleteMember" else "AddMember"
    val identity = if (remove && !membershipId.isNullOrBlank())
        "<MembershipId>${membershipId.escapeAddressBookXml()}</MembershipId>"
    else "<PassportName>${email.escapeAddressBookXml()}</PassportName>"
    return """
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration><PartnerScenario>ContactMsgrAPI</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest><TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body><$action xmlns="http://www.msn.com/webservices/AddressBook">
            <serviceHandle><Id>0</Id><Type>Messenger</Type><ForeignId/></serviceHandle>
            <memberships><Membership><MemberRole>$role</MemberRole><Members>
              <Member xsi:type="PassportMember"><Type>Passport</Type>$identity<State>Accepted</State></Member>
            </Members></Membership></memberships>
          </$action></soap:Body>
        </soap:Envelope>
    """.trimIndent()
}

internal fun parseMembershipChangeResponse(xml: String, action: String) {
    val document = secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream())
    if (document.getElementsByTagNameNS("*", "Fault").length > 0 ||
        document.getElementsByTagNameNS("*", "${action}Response").length == 0)
        throw AddressBookException("The server could not update this contact request. Please try again.")
}

internal fun parseMessengerMemberships(xml: String, signedInEmail: String): MessengerMemberships {
    val blocked = parseBlockedMemberships(xml, signedInEmail)
    val document = secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream())
    val requests = document.getElementsByTagNameNS("*", "Membership").asElementSequence()
        .filter { it.descendantText("MemberRole").equals("Pending", true) }
        .filter { membership ->
            var ancestor = membership.parentNode
            while (ancestor != null && ancestor.localName != "Service") ancestor = ancestor.parentNode
            val info = (ancestor as? Element)?.getElementsByTagNameNS("*", "Info")?.item(0) as? Element
            info == null || info.descendantText("Type").equals("Messenger", true)
        }
        .flatMap { it.getElementsByTagNameNS("*", "Member").asElementSequence() }
        .filterNot { it.descendantText("Deleted").equals("true", true) }
        .filter { it.descendantText("Type").equals("Passport", true) }
        .mapNotNull { member ->
            val email = member.descendantText("PassportName").trim().lowercase()
            val id = member.descendantText("MembershipId")
            if (email.isBlank() || email.equals(signedInEmail.trim(), true) || id.isBlank() ||
                member.descendantText("State") !in setOf("Accepted", "Pending")) null
            else ContactRequest(email, id, member.descendantText("DisplayName").takeIf(String::isNotBlank),
                member.invitationMessageAnnotation())
        }.filter { request -> blocked.none { it.id == request.id } }.distinctBy(ContactRequest::id).toList()
    return MessengerMemberships(blocked, requests)
}

internal fun buildAddressBookRequest(ticket: String, addressBookId: String? = null): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      <soap:Header>
        <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ApplicationId>F6D2794D-501F-443A-ADBE-8F1490FF30FD</ApplicationId>
          <IsMigration>false</IsMigration>
          <PartnerScenario>Initial</PartnerScenario>
        </ABApplicationHeader>
        <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ManagedGroupRequest>false</ManagedGroupRequest>
          <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
        </ABAuthHeader>
      </soap:Header>
      <soap:Body>
        <ABFindContactsPaged xmlns="http://www.msn.com/webservices/AddressBook">
          ${addressBookId?.let { "<ABId>${it.escapeAddressBookXml()}</ABId>" }.orEmpty()}
          <abView>Full</abView>
          <extendedContent>AB AllGroups CircleResult</extendedContent>
        </ABFindContactsPaged>
      </soap:Body>
    </soap:Envelope>
""".trimIndent().trimStart()

internal fun buildMembershipRequest(ticket: String): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      <soap:Header>
        <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ApplicationId>F6D2794D-501F-443A-ADBE-8F1490FF30FD</ApplicationId>
          <IsMigration>false</IsMigration>
          <PartnerScenario>Initial</PartnerScenario>
        </ABApplicationHeader>
        <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ManagedGroupRequest>false</ManagedGroupRequest>
          <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
        </ABAuthHeader>
      </soap:Header>
      <soap:Body>
        <FindMembership xmlns="http://www.msn.com/webservices/AddressBook">
          <serviceFilter>
            <Types><ServiceType>Messenger</ServiceType></Types>
          </serviceFilter>
        </FindMembership>
      </soap:Body>
    </soap:Envelope>
""".trimIndent().trimStart()

internal fun parseAddressBookContacts(xml: String, signedInEmail: String): List<Contact> =
    parseAddressBook(xml, signedInEmail).contacts

internal fun parseAddressBook(xml: String, signedInEmail: String): AddressBookSnapshot {
    val document = secureAddressBookDocumentBuilderFactory().newDocumentBuilder()
        .parse(xml.byteInputStream(StandardCharsets.UTF_8))
    val fault = document.getElementsByTagNameNS("*", "Fault").item(0) as? Element
    if (fault != null) {
        throw AddressBookException(
            fault.descendantText("faultstring")
                .ifBlank { "The address-book service rejected the request." },
        )
    }
    val result = document.getElementsByTagNameNS("*", "ABFindContactsPagedResult")
        .item(0) as? Element
        ?: throw AddressBookException("The address-book service returned an invalid response.")
    val contactNodes = result.getElementsByTagNameNS("*", "Contact")
    val contacts = LinkedHashMap<String, Contact>()
    val contactStorageIds = LinkedHashMap<String, String>()
    var ownerDisplayName: String? = null
    var ownerCid: String? = null
    for (index in 0 until contactNodes.length) {
        val element = contactNodes.item(index) as? Element ?: continue
        if (element.descendantText("fDeleted").equals("true", ignoreCase = true)) continue
        val info = element.getElementsByTagNameNS("*", "contactInfo").item(0) as? Element ?: continue
        if (info.descendantText("contactType").equals("Me", ignoreCase = true)) {
            ownerDisplayName = info.descendantText("displayName").takeIf(String::isNotBlank)
            ownerCid = info.descendantText("CID").takeIf { it.toLongOrNull() != null }
            continue
        }
        if (info.descendantText("isMessengerUser").equals("false", ignoreCase = true)) continue
        val email = info.descendantText("passportName").trim()
        if (email.isEmpty() || email.equals(signedInEmail, ignoreCase = true)) continue
        val id = email.lowercase()
        val annotationNickname = info.getElementsByTagNameNS("*", "Annotation")
            .asElementSequence()
            .firstOrNull {
                it.descendantText("Name").equals("AB.NickName", ignoreCase = true)
            }
            ?.descendantText("Value")
            .orEmpty()
        val displayName = info.descendantText("displayName")
            .takeUnless { it.equals(email, ignoreCase = true) }
            .orEmpty()
        contacts[id] = Contact(
            id = id,
            nickname = annotationNickname.ifBlank { displayName }.takeIf(String::isNotBlank),
            email = email,
            presence = Presence.Offline,
        )
        element.descendantText("contactId")
            .takeIf { CONTACT_STORAGE_ID.matches(it) }
            ?.let { contactStorageIds[id] = it }
    }
    return AddressBookSnapshot(
        contacts = contacts.values.toList() + parseAddressBookCircles(result),
        ownerDisplayName = ownerDisplayName,
        ownerCid = ownerCid,
        contactStorageIds = contactStorageIds,
        // This is signed XML. Preserve its whitespace when encoding the SHA ticket.
        circleTicket = result.getElementsByTagNameNS("*", "CircleTicket")
            .item(0)?.textContent?.takeIf(String::isNotBlank),
        groupInvitations = parseGroupInvitations(result),
    )
}

private fun parseAddressBookCircles(result: Element): List<Contact> =
    result.getElementsByTagNameNS("*", "CircleInverseInfo").asElementSequence()
        .filterNot { it.descendantText("Deleted").equals("true", true) }
        .filter { it.descendantText("State").equals("Accepted", true) }
        .mapNotNull { circle ->
            val id = circle.descendantText("Id").lowercase()
            val domain = circle.descendantText("HostedDomain").lowercase()
            if (!CONTACT_STORAGE_ID.matches(id) || domain.isBlank()) return@mapNotNull null
            val email = "$id@$domain"
            Contact(
                id = email,
                email = email,
                nickname = circle.descendantText("DisplayName").ifBlank {
                    circle.descendantText("Name")
                }.ifBlank { "Group" },
                presence = Presence.Offline,
                isGroup = true,
            )
        }.distinctBy(Contact::id).toList()

internal fun parseBlockedMemberships(xml: String, signedInEmail: String): List<Contact> {
    val document = secureAddressBookDocumentBuilderFactory().newDocumentBuilder()
        .parse(xml.byteInputStream(StandardCharsets.UTF_8))
    val fault = document.getElementsByTagNameNS("*", "Fault").item(0) as? Element
    if (fault != null) {
        throw AddressBookException(
            fault.descendantText("faultstring")
                .ifBlank { "The membership service rejected the request." },
        )
    }
    document.getElementsByTagNameNS("*", "FindMembershipResult").item(0) as? Element
        ?: throw AddressBookException("The membership service returned an invalid response.")

    val blocked = LinkedHashMap<String, Contact>()
    val memberships = document.getElementsByTagNameNS("*", "Membership")
    for (membershipIndex in 0 until memberships.length) {
        val membership = memberships.item(membershipIndex) as? Element ?: continue
        if (!membership.descendantText("MemberRole").equals("Block", ignoreCase = true)) continue
        val members = membership.getElementsByTagNameNS("*", "Member")
        for (memberIndex in 0 until members.length) {
            val member = members.item(memberIndex) as? Element ?: continue
            if (member.descendantText("Deleted").equals("true", ignoreCase = true)) continue
            val circleId = member.descendantText("CircleId").takeIf { CONTACT_STORAGE_ID.matches(it) }
            val email = circleId?.let { "${it.lowercase()}@live.com" }
                ?: member.descendantText("PassportName").trim()
            if (email.isEmpty() || email.equals(signedInEmail, ignoreCase = true)) continue
            val id = email.lowercase()
            blocked[id] = Contact(
                id = id,
                nickname = member.descendantText("DisplayName").takeIf(String::isNotBlank),
                email = email,
                presence = Presence.Offline,
                isBlocked = true,
                isForwardListContact = false,
                isGroup = circleId != null,
            )
        }
    }
    return blocked.values.toList()
}

internal fun parseCircleMembers(xml: String): List<GroupMember> {
    val document = secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream())
    return document.getElementsByTagNameNS("*", "Contact").asElementSequence()
        .filterNot { it.descendantText("fDeleted").equals("true", true) }
        .filter { it.descendantText("contactType") in setOf("Live", "LivePending") }
        .mapNotNull { contact ->
            val relationship = contact.getElementsByTagNameNS("*", "NetworkInfo").asElementSequence()
                .firstOrNull { it.descendantText("RelationshipType") == "5" } ?: contact
            val state = relationship.descendantText("RelationshipState")
            // Live/state 2 can also be a departed member; invitations are LivePending/state 2.
            val isPending = contact.descendantText("contactType") == "LivePending" && state == "2"
            if (!(contact.descendantText("contactType") == "Live" && state == "3") && !isPending) return@mapNotNull null
            val email = contact.descendantText("passportName").lowercase()
            if (email.isBlank()) null else GroupMember(
                email, contact.descendantText("displayName").ifBlank { email },
                storageId = contact.descendantText("contactId").takeIf(CONTACT_STORAGE_ID::matches),
                role = GroupMemberRole.fromRelationshipRole(relationship.descendantText("RelationshipRole")),
                isPending = isPending,
            )
        }.distinctBy(GroupMember::email).toList()
}

internal fun mergeBlockedMemberships(
    addressBook: AddressBookSnapshot,
    blockedContacts: List<Contact>,
): AddressBookSnapshot {
    if (blockedContacts.isEmpty()) return addressBook
    val contacts = LinkedHashMap(addressBook.contacts.associateBy(Contact::id))
    blockedContacts.forEach { blocked ->
        val existing = contacts[blocked.id]
        // A stale block membership must not resurrect a circle we have left.
        if (blocked.isGroup && existing == null) return@forEach
        contacts[blocked.id] = existing?.copy(isBlocked = true) ?: blocked
    }
    return addressBook.copy(contacts = contacts.values.toList())
}

internal fun mergeMessengerMemberships(addressBook: AddressBookSnapshot, memberships: MessengerMemberships?): AddressBookSnapshot {
    val merged = mergeBlockedMemberships(addressBook, memberships?.blockedContacts.orEmpty())
    val requests = memberships?.requests.orEmpty()
    val pendingIds = requests.map(ContactRequest::id).toSet()
    // An old address-book row is not permission to accept an explicit Pending membership.
    return merged.copy(contactRequests = requests, contacts = merged.contacts.map { contact ->
        if (contact.id in pendingIds) contact.copy(isForwardListContact = false) else contact
    })
}

private fun parseAddressBookFault(xml: String): String = runCatching {
    val document = secureAddressBookDocumentBuilderFactory().newDocumentBuilder()
        .parse(xml.byteInputStream(StandardCharsets.UTF_8))
    document.documentElement.descendantText("faultstring")
}.getOrDefault("")

private fun org.w3c.dom.NodeList.asElementSequence(): Sequence<Element> = sequence {
    for (index in 0 until length) (item(index) as? Element)?.let { yield(it) }
}

private fun Element.descendantText(localName: String): String =
    getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty().trim()

private fun String.addressBookHost(): String = trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

private fun String.escapeAddressBookXml(): String = buildString(length) {
    this@escapeAddressBookXml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}

private fun secureAddressBookDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }

private class AddressBookException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

private val CONTACT_STORAGE_ID = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
)
