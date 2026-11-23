package org.aralozkaya.msngr

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.ContactCategory
import org.aralozkaya.msngr.data.FAVORITES_CATEGORY_ID
import org.aralozkaya.msngr.data.GROUPS_CATEGORY_ID
import org.aralozkaya.msngr.data.OTHER_CONTACTS_CATEGORY_ID
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.ContactsPage
import org.aralozkaya.msngr.pages.components.CategoryEditorDialog
import org.aralozkaya.msngr.pages.components.CategoryOrderDialog
import org.aralozkaya.msngr.pages.components.ContactCategoriesDialog
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class ContactCategoriesInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun favoritesAndCustomCategoriesRenderWithEditableMenuOnlyOnCustom() {
        val work = ContactCategory(id = "work", name = "Work")
        val contacts = listOf(
            Contact("fav-1", "Alice", "alice@example.com", Presence.Online, categoryIds = setOf(FAVORITES_CATEGORY_ID)),
            Contact("work-1", "Bob", "bob@example.com", Presence.Online, categoryIds = setOf("work")),
            Contact("other-1", "Charlie", "charlie@example.com", Presence.Online),
            Contact("grp-1", "Team Chat", "team@live.com", Presence.Online, isGroup = true, categoryIds = setOf("work")),
        )
        compose.setContent {
            MsngrTheme {
                ContactsPage(
                    innerPadding = PaddingValues(),
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                    contacts = contacts,
                    profile = UserProfileSnapshot("Owner", "owner@example.com", Presence.Online),
                    onPresenceSelected = {},
                    onEditProfile = {},
                    onOpenChat = {},
                    onBlockContact = {},
                    onReadStateChange = { _, _ -> },
                    categories = listOf(work),
                    onEditCategory = {},
                    onDeleteCategory = {},
                )
            }
        }

        compose.onNodeWithTag("section-$FAVORITES_CATEGORY_ID").assertIsDisplayed()
        compose.onNodeWithTag("section-work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("category-options-work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("category-options-$FAVORITES_CATEGORY_ID").assertDoesNotExist()
        compose.onNodeWithTag("category-options-$OTHER_CONTACTS_CATEGORY_ID").assertDoesNotExist()
        compose.onNodeWithTag("category-options-$GROUPS_CATEGORY_ID").assertDoesNotExist()
    }

    @Test fun categoryOrderDialogTogglesCategoryVisibilityAndHidesOnContactsPage() {
        val work = ContactCategory(id = "work", name = "Work")
        var savedOrder: List<String>? = null
        var savedHidden: Set<String>? = null
        compose.setContent {
            MsngrTheme {
                CategoryOrderDialog(
                    initialOrder = emptyList(),
                    customCategories = listOf(work),
                    onSave = { order, hidden ->
                        savedOrder = order
                        savedHidden = hidden
                    },
                    onReset = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("category-visibility-work").performClick()
        compose.onNodeWithTag("category-order-save").performClick()

        assertEquals(
            listOf(FAVORITES_CATEGORY_ID, GROUPS_CATEGORY_ID, "work", OTHER_CONTACTS_CATEGORY_ID),
            savedOrder,
        )
        assertEquals(setOf("work"), savedHidden)
    }

    @Test fun favoritesCanUseRegularLayoutWhileOtherContactsUseCompactLayout() {
        val contacts = listOf(
            Contact("fav-1", "Alice", "alice@example.com", Presence.Online, categoryIds = setOf(FAVORITES_CATEGORY_ID)),
            Contact("other-1", "Bob", "bob@example.com", Presence.Online),
        )
        compose.setContent {
            MsngrTheme {
                CompositionLocalProvider(
                    LocalLayoutSettings provides LayoutSettings(density = UiDensity.Minimal),
                    LocalFavoritesLayoutSettings provides LayoutSettings(density = UiDensity.Regular),
                ) {
                    ContactsPage(
                        innerPadding = PaddingValues(),
                        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                        contacts = contacts,
                        profile = UserProfileSnapshot("Owner", "owner@example.com", Presence.Online),
                        onPresenceSelected = {},
                        onEditProfile = {},
                        onOpenChat = {},
                        onBlockContact = {},
                        onReadStateChange = { _, _ -> },
                    )
                }
            }
        }

        val favHeight = compose.onAllNodesWithTag("contact-row-fav-1").onFirst().fetchSemanticsNode().boundsInRoot.height
        val otherHeight = compose.onNodeWithTag("contact-row-other-1").performScrollTo().fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Favorites row ($favHeight) should be taller than compact Other Contacts row ($otherHeight)",
            favHeight > otherHeight,
        )
    }

    @Test fun categoryEditorSupportsCreatingEmptyCategoryAndWithMembers() {
        var createdName: String? = null
        var createdMembers: List<String>? = null
        val contacts = listOf(
            Contact("alice", "Alice", "alice@example.com", Presence.Online),
            Contact("grp-1", "Design", "design@live.com", Presence.Online, isGroup = true),
        )
        compose.setContent {
            MsngrTheme {
                var show by remember { mutableStateOf(true) }
                if (show) {
                    CategoryEditorDialog(
                        existingCategories = emptyList(),
                        contacts = contacts,
                        onCreate = { name, members ->
                            createdName = name
                            createdMembers = members
                            Result.success("cat-1")
                        },
                        onUpdate = { _, _, _ -> Result.success(Unit) },
                        onSaved = { show = false },
                        onDismiss = { show = false },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Show emoticons").assertIsDisplayed()
        Espresso.onView(withContentDescription("Category name"))
            .inRoot(isDialog())
            .perform(replaceText("Projects :)"))
        compose.waitForIdle()
        compose.onNodeWithTag("category-member-alice").performClick()
        compose.onNodeWithTag("category-member-grp-1").performClick()
        compose.onNodeWithTag("category-editor-confirm").performClick()
        compose.waitForIdle()

        assertEquals("Projects :)", createdName)
        assertEquals(listOf("alice", "grp-1"), createdMembers)
    }

    @Test fun contactCategoriesDialogShowsCheckboxListWithFavoritesAndMultiSelect() {
        var savedSet: Set<String>? = null
        val work = ContactCategory(id = "work", name = "Work")
        val family = ContactCategory(id = "family", name = "Family")
        val contact = Contact(
            "alice",
            "Alice",
            "alice@example.com",
            Presence.Online,
            categoryIds = emptySet(),
        )
        compose.setContent {
            MsngrTheme {
                ContactCategoriesDialog(
                    contact = contact,
                    categories = listOf(work, family),
                    onSave = {
                        savedSet = it
                        Result.success(Unit)
                    },
                    onSaved = {},
                    onDismiss = {},
                )
            }
        }

        // Scrollable checkbox list shows Favorites and custom categories without None or Other Contacts
        compose.onNodeWithTag("contact-categories-list").assertIsDisplayed()
        compose.onNodeWithText("None").assertDoesNotExist()
        compose.onNodeWithText("Other Contacts").assertDoesNotExist()

        compose.onNodeWithTag("assign-category-$FAVORITES_CATEGORY_ID").performClick()
        compose.onNodeWithTag("assign-category-work").performClick()
        compose.onNodeWithTag("assign-category-family").performClick()
        compose.onNodeWithTag("contact-categories-save").performClick()
        compose.waitForIdle()

        assertEquals(setOf(FAVORITES_CATEGORY_ID, "work", "family"), savedSet)

        // Unchecking all categories saves emptySet()
        compose.onNodeWithTag("assign-category-$FAVORITES_CATEGORY_ID").performClick()
        compose.onNodeWithTag("assign-category-work").performClick()
        compose.onNodeWithTag("assign-category-family").performClick()
        compose.onNodeWithTag("contact-categories-save").performClick()
        compose.waitForIdle()

        assertEquals(emptySet<String>(), savedSet)
    }

    @Test fun contactProfilePageShowsConditionalCategoryCardForFavoritesAndMultipleCategories() {
        val work = ContactCategory(id = "work", name = "Work")
        val family = ContactCategory(id = "family", name = "Family")
        val contactState = mutableStateOf(
            Contact(
                id = "alice",
                nickname = "Alice",
                email = "alice@example.com",
                presence = Presence.Online,
                categoryIds = emptySet(),
            ),
        )
        compose.setContent {
            MsngrTheme {
                ContactProfilePage(
                    contact = contactState.value,
                    innerPadding = PaddingValues(),
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                    onBlockContact = {},
                    onEraseHistory = { Result.success(Unit) },
                    categories = listOf(work, family),
                    onManageCategories = {},
                )
            }
        }

        compose.onNodeWithTag("contact-profile-categories").assertDoesNotExist()
        compose.onNodeWithTag("contact_manage_categories").performScrollTo().assertIsDisplayed()

        contactState.value = contactState.value.copy(categoryIds = setOf(FAVORITES_CATEGORY_ID, "work", "family"))
        compose.waitForIdle()

        compose.onNodeWithTag("contact-profile-categories").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Favorites, Work, Family").assertIsDisplayed()
    }
}
