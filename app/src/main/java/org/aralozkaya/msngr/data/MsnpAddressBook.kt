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

    suspend fun createCategory(
        host: String,
        ticket: String,
        name: String,
        isFavorite: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        parseCreateCategoryResponse(postSoap(
            host = host,
            path = "/abservice/abservice.asmx",
            soapAction = "http://www.msn.com/webservices/AddressBook/ABGroupAdd",
            body = buildCreateCategoryRequest(ticket, name, isFavorite),
        ))
    }

    suspend fun renameCategory(
        host: String,
        ticket: String,
        groupGuid: String,
        newName: String,
    ) = withContext(Dispatchers.IO) {
        parseCategoryOperationResponse(
            xml = postSoap(
                host = host,
                path = "/abservice/abservice.asmx",
                soapAction = "http://www.msn.com/webservices/AddressBook/ABGroupUpdate",
                body = buildRenameCategoryRequest(ticket, groupGuid, newName),
            ),
            action = "ABGroupUpdate",
        )
    }

    suspend fun deleteCategory(
        host: String,
        ticket: String,
        groupGuid: String,
    ) = withContext(Dispatchers.IO) {
        parseCategoryOperationResponse(
            xml = postSoap(
                host = host,
                path = "/abservice/abservice.asmx",
                soapAction = "http://www.msn.com/webservices/AddressBook/ABGroupDelete",
                body = buildDeleteCategoryRequest(ticket, groupGuid),
            ),
            action = "ABGroupDelete",
        )
    }

    suspend fun addContactToCategory(
        host: String,
        ticket: String,
        groupGuid: String,
        contactGuid: String,
    ) = withContext(Dispatchers.IO) {
        parseCategoryOperationResponse(
            xml = postSoap(
                host = host,
                path = "/abservice/abservice.asmx",
                soapAction = "http://www.msn.com/webservices/AddressBook/ABGroupContactAdd",
                body = buildAddContactToCategoryRequest(ticket, groupGuid, contactGuid),
            ),
            action = "ABGroupContactAdd",
        )
    }

    suspend fun removeContactFromCategory(
        host: String,
        ticket: String,
        groupGuid: String,
        contactGuid: String,
    ) = withContext(Dispatchers.IO) {
        parseCategoryOperationResponse(
            xml = postSoap(
                host = host,
                path = "/abservice/abservice.asmx",
                soapAction = "http://www.msn.com/webservices/AddressBook/ABGroupContactDelete",
                body = buildRemoveContactFromCategoryRequest(ticket, groupGuid, contactGuid),
            ),
            action = "ABGroupContactDelete",
        )
    }

    suspend fun setContactFavoriteFlag(
        host: String,
        ticket: String,
        contactGuid: String,
        isFavorite: Boolean,
    ) = withContext(Dispatchers.IO) {
        parseContactUpdateResponse(
            postSoap(
                host = host,
                path = "/abservice/abservice.asmx",
                soapAction = "http://www.msn.com/webservices/AddressBook/ABContactUpdate",
                body = buildContactFavoriteUpdateRequest(ticket, contactGuid, isFavorite),
            ),
        )
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
                if (soapAction.endsWith("/ABGroupAdd")) parseCreateCategoryResponse(responseBody)
                if (soapAction.endsWith("/ABGroupUpdate") ||
                    soapAction.endsWith("/ABGroupDelete") ||
                    soapAction.endsWith("/ABGroupContactAdd") ||
                    soapAction.endsWith("/ABGroupContactDelete")
                ) {
                    parseCategoryOperationResponse(responseBody, soapAction.substringAfterLast('/'))
                }
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
    val categories: List<ContactCategory> = emptyList(),
    val favoriteCategoryGuid: String? = null,
)

private const val MESSENGER_CATEGORY_GROUP_TYPE = "C8529CE2-6EAD-434d-881F-341E17DB3FF8"
private const val MESSENGER_FAVORITE_GROUP_TYPE = "0969E3B1-2D6B-4526-98B2-7A9C48341247"

internal fun buildContactFavoriteUpdateRequest(
    ticket: String,
    contactGuid: String,
    isFavorite: Boolean,
): String {
    require(CONTACT_STORAGE_ID.matches(contactGuid)) { "The contact identifier is invalid." }
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration>
              <PartnerScenario>ContactSave</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest>
              <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <ABContactUpdate xmlns="http://www.msn.com/webservices/AddressBook">
              <abId>00000000-0000-0000-0000-000000000000</abId>
              <contacts><Contact><contactId>$contactGuid</contactId>
                <contactInfo><isFavorite>$isFavorite</isFavorite></contactInfo>
                <propertiesChanged>IsFavorite</propertiesChanged>
              </Contact></contacts>
            </ABContactUpdate>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()
}

internal fun buildCreateCategoryRequest(
    ticket: String,
    name: String,
    isFavorite: Boolean = false,
): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      <soap:Header>
        <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
          <IsMigration>false</IsMigration>
          <PartnerScenario>GroupSave</PartnerScenario>
        </ABApplicationHeader>
        <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
          <ManagedGroupRequest>false</ManagedGroupRequest>
          <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
        </ABAuthHeader>
      </soap:Header>
      <soap:Body>
        <ABGroupAdd xmlns="http://www.msn.com/webservices/AddressBook">
          <abId>00000000-0000-0000-0000-000000000000</abId>
          <groupAddOptions>
            <fRenameOnMsgrConflict>false</fRenameOnMsgrConflict>
          </groupAddOptions>
          <groupInfo>
            <GroupInfo>
              <annotations>
                <Annotation>
                  <Name>MSN.IM.Display</Name>
                  <Value>1</Value>
                </Annotation>
              </annotations>
              <groupType>$MESSENGER_CATEGORY_GROUP_TYPE</groupType>
              <name>${name.escapeAddressBookXml()}</name>
              <IsFavorite>$isFavorite</IsFavorite>
              <fMessenger>false</fMessenger>
            </GroupInfo>
          </groupInfo>
        </ABGroupAdd>
      </soap:Body>
    </soap:Envelope>
""".trimIndent().trimStart()

internal fun parseCreateCategoryResponse(xml: String): String {
    val document = try {
        secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
    } catch (error: Exception) {
        throw AddressBookException("The category service returned an invalid response.", error)
    }
    val fault = document.getElementsByTagNameNS("*", "Fault").item(0) as? Element
    if (fault != null) {
        val message = when (fault.descendantText("errorcode")) {
            "GroupAlreadyExists" -> "A category with this name already exists."
            else -> fault.descendantText("faultstring").ifBlank { "The server could not create this category. Please try again." }
        }
        throw AddressBookException(message)
    }
    val result = document.getElementsByTagNameNS("*", "ABGroupAddResult").item(0) as? Element
    val guid = result?.descendantText("guid").orEmpty().lowercase()
    if (!CONTACT_STORAGE_ID.matches(guid)) {
        throw AddressBookException("The category service returned an invalid response.")
    }
    return guid
}

internal fun buildRenameCategoryRequest(
    ticket: String,
    groupGuid: String,
    newName: String,
): String {
    require(CONTACT_STORAGE_ID.matches(groupGuid)) { "The category identifier is invalid." }
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration>
              <PartnerScenario>GroupSave</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest>
              <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <ABGroupUpdate xmlns="http://www.msn.com/webservices/AddressBook">
              <abId>00000000-0000-0000-0000-000000000000</abId>
              <groups>
                <Group>
                  <groupId>${groupGuid.escapeAddressBookXml()}</groupId>
                  <groupInfo>
                    <name>${newName.escapeAddressBookXml()}</name>
                  </groupInfo>
                  <propertiesChanged>GroupName</propertiesChanged>
                </Group>
              </groups>
            </ABGroupUpdate>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()
}

internal fun buildDeleteCategoryRequest(ticket: String, groupGuid: String): String {
    require(CONTACT_STORAGE_ID.matches(groupGuid)) { "The category identifier is invalid." }
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration>
              <PartnerScenario>Timer</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest>
              <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <ABGroupDelete xmlns="http://www.msn.com/webservices/AddressBook">
              <abId>00000000-0000-0000-0000-000000000000</abId>
              <groupFilter>
                <groupIds>
                  <guid>${groupGuid.escapeAddressBookXml()}</guid>
                </groupIds>
              </groupFilter>
            </ABGroupDelete>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()
}

internal fun buildAddContactToCategoryRequest(
    ticket: String,
    groupGuid: String,
    contactGuid: String,
): String {
    require(CONTACT_STORAGE_ID.matches(groupGuid)) { "The category identifier is invalid." }
    require(CONTACT_STORAGE_ID.matches(contactGuid)) { "The contact identifier is invalid." }
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration>
              <PartnerScenario>GroupSave</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest>
              <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <ABGroupContactAdd xmlns="http://www.msn.com/webservices/AddressBook">
              <abId>00000000-0000-0000-0000-000000000000</abId>
              <groupFilter>
                <groupIds>
                  <guid>${groupGuid.escapeAddressBookXml()}</guid>
                </groupIds>
              </groupFilter>
              <contacts>
                <Contact>
                  <contactId>${contactGuid.escapeAddressBookXml()}</contactId>
                </Contact>
              </contacts>
            </ABGroupContactAdd>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()
}

internal fun buildRemoveContactFromCategoryRequest(
    ticket: String,
    groupGuid: String,
    contactGuid: String,
): String {
    require(CONTACT_STORAGE_ID.matches(groupGuid)) { "The category identifier is invalid." }
    require(CONTACT_STORAGE_ID.matches(contactGuid)) { "The contact identifier is invalid." }
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soap:Header>
            <ABApplicationHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ApplicationId>AAD9B99B-58E6-4F23-B975-D9EC1F9EC24A</ApplicationId>
              <IsMigration>false</IsMigration>
              <PartnerScenario>GroupSave</PartnerScenario>
            </ABApplicationHeader>
            <ABAuthHeader xmlns="http://www.msn.com/webservices/AddressBook">
              <ManagedGroupRequest>false</ManagedGroupRequest>
              <TicketToken>${ticket.escapeAddressBookXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <ABGroupContactDelete xmlns="http://www.msn.com/webservices/AddressBook">
              <abId>00000000-0000-0000-0000-000000000000</abId>
              <contacts>
                <Contact>
                  <contactId>${contactGuid.escapeAddressBookXml()}</contactId>
                </Contact>
              </contacts>
              <groupFilter>
                <groupIds>
                  <guid>${groupGuid.escapeAddressBookXml()}</guid>
                </groupIds>
              </groupFilter>
            </ABGroupContactDelete>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()
}

internal fun parseCategoryOperationResponse(xml: String, action: String) {
    val document = try {
        secureAddressBookDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
    } catch (error: Exception) {
        throw AddressBookException("The category service returned an invalid response.", error)
    }
    val fault = document.getElementsByTagNameNS("*", "Fault").item(0) as? Element
    if (fault != null) {
        val errorCode = fault.descendantText("errorcode")
        val message = when (errorCode) {
            "GroupAlreadyExists" -> "A category with this name already exists."
            "GroupDoesNotExist" -> "This category no longer exists."
            else -> fault.descendantText("faultstring").ifBlank { "The server could not update this category. Please try again." }
        }
        throw AddressBookException(message)
    }
    if (document.getElementsByTagNameNS("*", "${action}Response").length == 0) {
        throw AddressBookException("The category service returned an invalid response.")
    }
}

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
    val parsedCategories = parseAddressBookCategories(result)
    val customCategoryIds = parsedCategories.categories.map(ContactCategory::id).toSet()
    val favoriteCategoryGuid = parsedCategories.favoriteCategoryGuid
    val contactNodes = result.getElementsByTagNameNS("*", "Contact")
    val contacts = LinkedHashMap<String, Contact>()
    val contactStorageIds = LinkedHashMap<String, String>()
    val circleCategoryIds = LinkedHashMap<String, Set<String>>()
    var ownerDisplayName: String? = null
    var ownerCid: String? = null
    for (index in 0 until contactNodes.length) {
        val element = contactNodes.item(index) as? Element ?: continue
        if (element.descendantText("fDeleted").equals("true", ignoreCase = true)) continue
        val info = element.getElementsByTagNameNS("*", "contactInfo").item(0) as? Element ?: continue
        val contactType = info.descendantText("contactType")
        if (contactType.equals("Me", ignoreCase = true)) {
            ownerDisplayName = info.descendantText("displayName").takeIf(String::isNotBlank)
            ownerCid = info.descendantText("CID").takeIf { it.toLongOrNull() != null }
            continue
        }
        val rawCategoryIds = parseContactCategoryIds(info, customCategoryIds, favoriteCategoryGuid)
        if (contactType.equals("Circle", ignoreCase = true)) {
            val circleEmail = info.descendantText("passportName").trim().lowercase()
            if (circleEmail.isNotEmpty()) {
                element.descendantText("contactId")
                    .takeIf { CONTACT_STORAGE_ID.matches(it) }
                    ?.let { contactStorageIds[circleEmail] = it }
                if (rawCategoryIds.isNotEmpty()) circleCategoryIds[circleEmail] = rawCategoryIds
            }
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
            categoryIds = rawCategoryIds,
        )
        element.descendantText("contactId")
            .takeIf { CONTACT_STORAGE_ID.matches(it) }
            ?.let { contactStorageIds[id] = it }
    }
    return AddressBookSnapshot(
        contacts = contacts.values.toList() + parseAddressBookCircles(result, circleCategoryIds),
        ownerDisplayName = ownerDisplayName,
        ownerCid = ownerCid,
        contactStorageIds = contactStorageIds,
        // This is signed XML. Preserve its whitespace when encoding the SHA ticket.
        circleTicket = result.getElementsByTagNameNS("*", "CircleTicket")
            .item(0)?.textContent?.takeIf(String::isNotBlank),
        groupInvitations = parseGroupInvitations(result),
        categories = parsedCategories.categories,
        favoriteCategoryGuid = favoriteCategoryGuid,
    )
}

private data class ParsedAddressBookCategories(
    val categories: List<ContactCategory>,
    val favoriteCategoryGuid: String?,
)

private fun parseAddressBookCategories(result: Element): ParsedAddressBookCategories {
    val groupsContainer = result.getElementsByTagNameNS("*", "Groups").item(0) as? Element
        ?: return ParsedAddressBookCategories(emptyList(), null)
    val categories = LinkedHashMap<String, ContactCategory>()
    var favoriteCategoryGuid: String? = null
    groupsContainer.getElementsByTagNameNS("*", "Group").asElementSequence().forEach { group ->
        if (group.descendantText("fDeleted").equals("true", ignoreCase = true)) return@forEach
        val guid = group.descendantText("groupId").lowercase()
        if (!CONTACT_STORAGE_ID.matches(guid) || guid == "00000000-0000-0000-0000-000000000000") return@forEach
        val info = group.getElementsByTagNameNS("*", "groupInfo").item(0) as? Element ?: group
        val groupType = info.descendantText("groupType")
        val isFavorite = info.descendantText("IsFavorite").equals("true", ignoreCase = true) ||
            info.descendantText("isFavorite").equals("true", ignoreCase = true) ||
            groupType.equals(MESSENGER_FAVORITE_GROUP_TYPE, ignoreCase = true)
        if (isFavorite) {
            if (favoriteCategoryGuid == null) favoriteCategoryGuid = guid
            return@forEach
        }
        val name = info.descendantText("name").trim().ifBlank { return@forEach }
        categories[guid] = ContactCategory(
            id = guid,
            name = name,
            serverGuid = guid,
        )
    }
    return ParsedAddressBookCategories(categories.values.toList(), favoriteCategoryGuid)
}

private fun parseContactCategoryIds(
    info: Element,
    customCategoryIds: Set<String>,
    favoriteCategoryGuid: String?,
): Set<String> = buildSet {
    if (info.descendantText("isFavorite").equals("true", ignoreCase = true) ||
        info.descendantText("IsFavorite").equals("true", ignoreCase = true)
    ) {
        add(FAVORITES_CATEGORY_ID)
    }
    val groupIdsNode = info.getElementsByTagNameNS("*", "groupIds").item(0) as? Element ?: return@buildSet
    groupIdsNode.getElementsByTagNameNS("*", "guid").asElementSequence().forEach { guidElement ->
        val guid = guidElement.textContent.orEmpty().trim().lowercase()
        if (guid.isEmpty()) return@forEach
        when {
            favoriteCategoryGuid != null && guid == favoriteCategoryGuid -> add(FAVORITES_CATEGORY_ID)
            guid in customCategoryIds -> add(guid)
        }
    }
}

private fun parseAddressBookCircles(
    result: Element,
    circleCategoryIds: Map<String, Set<String>> = emptyMap(),
): List<Contact> =
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
                categoryIds = circleCategoryIds[email].orEmpty(),
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
