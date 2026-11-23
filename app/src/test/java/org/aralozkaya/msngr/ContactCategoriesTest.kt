package org.aralozkaya.msngr

import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactCategory
import org.aralozkaya.msngr.data.FAVORITES_CATEGORY_ID
import org.aralozkaya.msngr.data.GROUPS_CATEGORY_ID
import org.aralozkaya.msngr.data.InMemoryCategoryMembershipOverlayStore
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.OTHER_CONTACTS_CATEGORY_ID
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.belongsToCategory
import org.aralozkaya.msngr.data.buildAddContactToCategoryRequest
import org.aralozkaya.msngr.data.buildContactFavoriteUpdateRequest
import org.aralozkaya.msngr.data.buildCreateCategoryRequest
import org.aralozkaya.msngr.data.buildDeleteCategoryRequest
import org.aralozkaya.msngr.data.buildRemoveContactFromCategoryRequest
import org.aralozkaya.msngr.data.buildRenameCategoryRequest
import org.aralozkaya.msngr.data.isFavorite
import org.aralozkaya.msngr.data.parseAddressBook
import org.aralozkaya.msngr.data.parseCategoryOperationResponse
import org.aralozkaya.msngr.data.parseCreateCategoryResponse
import org.aralozkaya.msngr.data.resolveOrderedCategoryIds
import org.aralozkaya.msngr.data.validateCategoryName
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContactCategoriesTest {
    @Test fun parseAddressBookLoadsCategoriesFavoritesAndContactMemberships() {
        val favGuid = "11111111-1111-1111-1111-111111111111"
        val workGuid = "22222222-2222-2222-2222-222222222222"
        val deletedGuid = "33333333-3333-3333-3333-333333333333"
        val circleGuid = "00000000-0000-0000-0009-abc000000001"
        val xml = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ABFindContactsPagedResponse xmlns="http://www.msn.com/webservices/AddressBook">
                  <ABFindContactsPagedResult>
                    <Groups>
                      <Group>
                        <groupId>$favGuid</groupId>
                        <groupInfo>
                          <name>Favorites</name>
                          <IsFavorite>true</IsFavorite>
                        </groupInfo>
                        <fDeleted>false</fDeleted>
                      </Group>
                      <Group>
                        <groupId>$workGuid</groupId>
                        <groupInfo>
                          <name>Work</name>
                          <IsFavorite>false</IsFavorite>
                        </groupInfo>
                        <fDeleted>false</fDeleted>
                      </Group>
                      <Group>
                        <groupId>$deletedGuid</groupId>
                        <groupInfo>
                          <name>Deleted</name>
                        </groupInfo>
                        <fDeleted>true</fDeleted>
                      </Group>
                    </Groups>
                    <Contacts>
                      <Contact>
                        <contactId>aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa</contactId>
                        <contactInfo>
                          <contactType>Regular</contactType>
                          <passportName>alice@example.com</passportName>
                          <displayName>Alice</displayName>
                          <isMessengerUser>true</isMessengerUser>
                          <groupIds>
                            <guid>$favGuid</guid>
                            <guid>$workGuid</guid>
                          </groupIds>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                      <Contact>
                        <contactId>bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb</contactId>
                        <contactInfo>
                          <contactType>Regular</contactType>
                          <passportName>bob@example.com</passportName>
                          <displayName>Bob</displayName>
                          <isMessengerUser>true</isMessengerUser>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                      <Contact>
                        <contactId>$circleGuid</contactId>
                        <contactInfo>
                          <contactType>Circle</contactType>
                          <passportName>$circleGuid@live.com</passportName>
                          <isMessengerUser>false</isMessengerUser>
                          <groupIds>
                            <guid>$workGuid</guid>
                          </groupIds>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                    </Contacts>
                    <CircleResult>
                      <Circles>
                        <CircleInverseInfo>
                          <Content>
                            <Handle>
                              <Id>$circleGuid</Id>
                              <HostedDomain>live.com</HostedDomain>
                            </Handle>
                            <Info>
                              <DisplayName>Design Team</DisplayName>
                              <State>Accepted</State>
                            </Info>
                          </Content>
                          <Deleted>false</Deleted>
                        </CircleInverseInfo>
                      </Circles>
                    </CircleResult>
                  </ABFindContactsPagedResult>
                </ABFindContactsPagedResponse>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val snapshot = parseAddressBook(xml, "owner@example.com")
        assertEquals(favGuid, snapshot.favoriteCategoryGuid)
        assertEquals(listOf(ContactCategory(id = workGuid, name = "Work", serverGuid = workGuid)), snapshot.categories)

        val alice = snapshot.contacts.first { it.id == "alice@example.com" }
        assertTrue(alice.isFavorite)
        assertTrue(alice.belongsToCategory(FAVORITES_CATEGORY_ID))
        assertTrue(alice.belongsToCategory(workGuid))
        assertFalse(alice.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        val bob = snapshot.contacts.first { it.id == "bob@example.com" }
        assertFalse(bob.isFavorite)
        assertTrue(bob.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        val circle = snapshot.contacts.first { it.isGroup }
        assertTrue(circle.belongsToCategory(GROUPS_CATEGORY_ID))
        assertTrue(circle.belongsToCategory(workGuid))
        assertEquals(circleGuid, snapshot.contactStorageIds[circle.id])
    }

    @Test fun categorySoapRequestsAndResponsesRoundTrip() {
        val groupGuid = "12345678-1234-1234-1234-1234567890ab"
        val contactGuid = "abcdefab-1234-1234-1234-1234567890ab"

        val createXml = buildCreateCategoryRequest("t=1", "Friends & Family", isFavorite = false)
        assertTrue("<ABGroupAdd " in createXml)
        assertTrue("<name>Friends &amp; Family</name>" in createXml)
        assertTrue("<IsFavorite>false</IsFavorite>" in createXml)
        val parsedGuid = parseCreateCategoryResponse(
            """<ABGroupAddResponse xmlns="http://www.msn.com/webservices/AddressBook"><ABGroupAddResult><guid>$groupGuid</guid></ABGroupAddResult></ABGroupAddResponse>""",
        )
        assertEquals(groupGuid, parsedGuid)

        val renameXml = buildRenameCategoryRequest("t=1", groupGuid, "Close Friends")
        assertTrue("<ABGroupUpdate " in renameXml)
        assertTrue("<propertiesChanged>GroupName</propertiesChanged>" in renameXml)
        parseCategoryOperationResponse(
            """<ABGroupUpdateResponse xmlns="http://www.msn.com/webservices/AddressBook" />""",
            "ABGroupUpdate",
        )

        val addContactXml = buildAddContactToCategoryRequest("t=1", groupGuid, contactGuid)
        assertTrue("<ABGroupContactAdd " in addContactXml)
        assertTrue("<contactId>$contactGuid</contactId>" in addContactXml)
        parseCategoryOperationResponse(
            """<ABGroupContactAddResponse xmlns="http://www.msn.com/webservices/AddressBook" />""",
            "ABGroupContactAdd",
        )

        val removeContactXml = buildRemoveContactFromCategoryRequest("t=1", groupGuid, contactGuid)
        assertTrue("<ABGroupContactDelete " in removeContactXml)
        parseCategoryOperationResponse(
            """<ABGroupContactDeleteResponse xmlns="http://www.msn.com/webservices/AddressBook" />""",
            "ABGroupContactDelete",
        )

        val deleteXml = buildDeleteCategoryRequest("t=1", groupGuid)
        assertTrue("<ABGroupDelete " in deleteXml)
        parseCategoryOperationResponse(
            """<ABGroupDeleteResponse xmlns="http://www.msn.com/webservices/AddressBook" />""",
            "ABGroupDelete",
        )

        val favFlagXml = buildContactFavoriteUpdateRequest("t=1", contactGuid, isFavorite = true)
        assertTrue("<ABContactUpdate " in favFlagXml)
        assertTrue("<isFavorite>true</isFavorite>" in favFlagXml)
        assertTrue("<propertiesChanged>IsFavorite</propertiesChanged>" in favFlagXml)
    }

    @Test fun parseAddressBookMapsMsnFavoriteGroupTypeWithoutCreatingDuplicateCustomCategory() {
        val favGuid = "44444444-4444-4444-4444-444444444444"
        val xml = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ABFindContactsPagedResponse xmlns="http://www.msn.com/webservices/AddressBook">
                  <ABFindContactsPagedResult>
                    <Groups>
                      <Group>
                        <groupId>$favGuid</groupId>
                        <groupInfo>
                          <groupType>0969E3B1-2D6B-4526-98B2-7A9C48341247</groupType>
                          <name>Favorites</name>
                        </groupInfo>
                        <fDeleted>false</fDeleted>
                      </Group>
                    </Groups>
                    <Contacts>
                      <Contact>
                        <contactId>aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa</contactId>
                        <contactInfo>
                          <contactType>Regular</contactType>
                          <passportName>alice@example.com</passportName>
                          <displayName>Alice</displayName>
                          <isMessengerUser>true</isMessengerUser>
                          <groupIds><guid>$favGuid</guid></groupIds>
                        </contactInfo>
                        <fDeleted>false</fDeleted>
                      </Contact>
                    </Contacts>
                  </ABFindContactsPagedResult>
                </ABFindContactsPagedResponse>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val snapshot = parseAddressBook(xml, "owner@example.com")
        assertEquals(favGuid, snapshot.favoriteCategoryGuid)
        assertTrue("Built-in MSN Favorites group must not appear as a duplicate custom category", snapshot.categories.isEmpty())
        assertTrue(snapshot.contacts.single().isFavorite)
    }

    @Test fun validateCategoryNameAndResolveOrder() {
        val work = ContactCategory(id = "work", name = "Work")
        val family = ContactCategory(id = "family", name = "Family")
        assertEquals("Friends", validateCategoryName("  Friends  ", listOf(work)))
        assertEquals("Work", validateCategoryName("Work", listOf(work), excludeCategoryId = "work"))
        try {
            validateCategoryName("  ", listOf(work))
            fail("Blank name should fail")
        } catch (_: IllegalArgumentException) {
        }
        try {
            validateCategoryName("work", listOf(work))
            fail("Duplicate name should fail")
        } catch (_: IllegalArgumentException) {
        }

        val defaultOrder = resolveOrderedCategoryIds(emptyList(), listOf(work, family))
        assertEquals(
            listOf(FAVORITES_CATEGORY_ID, GROUPS_CATEGORY_ID, "work", "family", OTHER_CONTACTS_CATEGORY_ID),
            defaultOrder,
        )

        val customOrder = resolveOrderedCategoryIds(
            listOf(OTHER_CONTACTS_CATEGORY_ID, "work", "deleted-id", FAVORITES_CATEGORY_ID, GROUPS_CATEGORY_ID),
            listOf(work, family),
        )
        assertEquals(
            listOf(family.id, OTHER_CONTACTS_CATEGORY_ID, "work", FAVORITES_CATEGORY_ID, GROUPS_CATEGORY_ID).let {
                // Newly added category is inserted right before Other Contacts
                listOf("family", OTHER_CONTACTS_CATEGORY_ID, "work", FAVORITES_CATEGORY_ID, GROUPS_CATEGORY_ID)
            },
            customOrder,
        )
    }

    @Test fun mockRepositoryCategoryCrudFavoritesAndSafeDelete() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0L, addContactDelayMillis = 0L)
        val repository = MessengerRepository(client)
        repository.signIn(
            SignInRequest("user@example.com", "pw", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())),
        ).getOrThrow()

        val emptyId = repository.createCategory("Empty").getOrThrow()
        assertEquals(1, repository.categories.value.size)

        val groupId = repository.createGroup("Weekend Plan").getOrThrow()
        val workId = repository.createCategory("Work", listOf("selin", groupId)).getOrThrow()
        assertEquals(2, repository.categories.value.size)

        val selinAfterCreate = repository.contacts.value.first { it.id == "selin" }
        assertTrue(selinAfterCreate.belongsToCategory(workId))
        assertFalse(selinAfterCreate.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        val groupAfterCreate = repository.contacts.value.first { it.id == groupId }
        assertTrue(groupAfterCreate.belongsToCategory(workId))
        assertTrue(groupAfterCreate.belongsToCategory(GROUPS_CATEGORY_ID))

        // Favorite both contact and group
        repository.setContactFavorite("selin", true).getOrThrow()
        repository.setContactFavorite(groupId, true).getOrThrow()
        assertTrue(repository.contacts.value.first { it.id == "selin" }.isFavorite)
        assertTrue(repository.contacts.value.first { it.id == groupId }.isFavorite)

        // Rename custom category
        repository.renameCategory(workId, "Office").getOrThrow()
        assertEquals("Office", repository.categories.value.first { it.id == workId }.name)

        // Delete custom category must NOT delete contacts; members without other categories return to Other Contacts
        repository.setContactFavorite("selin", false).getOrThrow()
        repository.deleteCategory(workId).getOrThrow()
        assertFalse(repository.categories.value.any { it.id == workId })
        val selinAfterDelete = repository.contacts.value.first { it.id == "selin" }
        assertTrue(selinAfterDelete.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))
        assertTrue(repository.contacts.value.any { it.id == groupId })

        // Built-in categories cannot be edited or deleted
        assertTrue(repository.renameCategory(FAVORITES_CATEGORY_ID, "Stars").isFailure)
        assertTrue(repository.deleteCategory(FAVORITES_CATEGORY_ID).isFailure)
        assertTrue(repository.renameCategory(OTHER_CONTACTS_CATEGORY_ID, "Misc").isFailure)
        assertTrue(repository.deleteCategory(OTHER_CONTACTS_CATEGORY_ID).isFailure)

        // Clean up empty category
        repository.deleteCategory(emptyId).getOrThrow()
        assertTrue(repository.categories.value.isEmpty())
    }

    @Test fun contactCanBelongToMultipleCategoriesAndOnlyShowsInOtherContactsWithoutCustomCategory() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0L, addContactDelayMillis = 0L)
        val repository = MessengerRepository(client)
        repository.signIn(
            SignInRequest("user@example.com", "pw", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())),
        ).getOrThrow()

        // Favorite-only contact still belongs to Other Contacts
        repository.setContactFavorite("selin", true).getOrThrow()
        val favOnly = repository.contacts.value.first { it.id == "selin" }
        assertEquals(setOf(FAVORITES_CATEGORY_ID), favOnly.categoryIds)
        assertTrue(favOnly.belongsToCategory(FAVORITES_CATEGORY_ID))
        assertTrue(favOnly.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        // Creating a custom category with selin removes her from Other Contacts while keeping Favorites
        val workId = repository.createCategory("Work", listOf("selin")).getOrThrow()
        val withWork = repository.contacts.value.first { it.id == "selin" }
        assertEquals(setOf(FAVORITES_CATEGORY_ID, workId), withWork.categoryIds)
        assertFalse(withWork.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        // Creating another category with selin keeps both Work and Family as well as Favorites
        val familyId = repository.createCategory("Family", listOf("selin")).getOrThrow()
        val withBoth = repository.contacts.value.first { it.id == "selin" }
        assertEquals(setOf(FAVORITES_CATEGORY_ID, workId, familyId), withBoth.categoryIds)
        assertTrue(withBoth.belongsToCategory(workId))
        assertTrue(withBoth.belongsToCategory(familyId))
        assertFalse(withBoth.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        // Updating Family's members to remove selin keeps Work and still excludes Other Contacts
        repository.updateCategoryMembers(familyId, emptySet()).getOrThrow()
        val afterRemovingFamily = repository.contacts.value.first { it.id == "selin" }
        assertEquals(setOf(FAVORITES_CATEGORY_ID, workId), afterRemovingFamily.categoryIds)
        assertFalse(afterRemovingFamily.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        // setContactCategories accepts multiple custom categories plus Favorites
        repository.setContactCategories("selin", setOf(FAVORITES_CATEGORY_ID, familyId, workId)).getOrThrow()
        val multiAssigned = repository.contacts.value.first { it.id == "selin" }
        assertEquals(setOf(FAVORITES_CATEGORY_ID, familyId, workId), multiAssigned.categoryIds)
        assertFalse(multiAssigned.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        // Clearing custom categories (leaving only Favorites) returns selin to Other Contacts
        repository.setContactCategories("selin", setOf(FAVORITES_CATEGORY_ID)).getOrThrow()
        val backToFavOnly = repository.contacts.value.first { it.id == "selin" }
        assertEquals(setOf(FAVORITES_CATEGORY_ID), backToFavOnly.categoryIds)
        assertTrue(backToFavOnly.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))

        // Clearing all categories ("None") leaves emptySet and belongs to Other Contacts
        repository.setContactCategories("selin", emptySet()).getOrThrow()
        val uncategorized = repository.contacts.value.first { it.id == "selin" }
        assertTrue(uncategorized.categoryIds.isEmpty())
        assertTrue(uncategorized.belongsToCategory(OTHER_CONTACTS_CATEGORY_ID))
    }
}
