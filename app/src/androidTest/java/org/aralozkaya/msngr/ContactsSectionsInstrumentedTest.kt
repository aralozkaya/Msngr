package org.aralozkaya.msngr

import android.widget.EditText
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.ContactsPage
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsSectionsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val contacts = listOf(
        Contact("group-a", "Alpha group", "a@live.com", Presence.Offline, isGroup = true),
        Contact("group-z", "Zulu group", "z@live.com", Presence.Online, isGroup = true),
        Contact("friend-a", "Alice", "alice@example.com", Presence.Offline),
        Contact("friend-z", "Zoe", "zoe@example.com", Presence.Online),
    )

    @Test fun sectionsCollapseIndependently() {
        showContacts()
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Alpha group").assertDoesNotExist()
        compose.onNodeWithText("Other Contacts").performScrollTo().performClick()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Alpha group").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("Other Contacts").performScrollTo().performClick()
        compose.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
    }

    @Test fun sharedSortAndOfflineFilterApplyToBothSections() {
        showContacts()
        assertAbove("Zulu group", "Alpha group")
        assertAbove("Zoe", "Alice")
        compose.onNodeWithTag("contacts_sort_button").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close sort options").assertDoesNotExist()
        compose.onNodeWithText("Name").performClick()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        assertAbove("Alpha group", "Zulu group")
        assertAbove("Alice", "Zoe")
        compose.onNodeWithTag("contacts_sort_button").performScrollTo().performClick()
        compose.onNodeWithTag("hide_offline_contacts").performClick()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Alpha group").assertDoesNotExist()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("Zulu group").assertIsDisplayed()
        compose.onNodeWithText("Zoe").assertIsDisplayed()
    }

    @Test fun searchMatchesVisibleNamesAndEmailsAcrossBothSections() {
        showContacts(contacts.map {
            if (it.id == "friend-a") it.copy(nickname = "[b]Alice[/b]") else it
        })
        val search = compose.onNodeWithContentDescription("Search")
        search.performScrollTo().performTextReplacement(" ALI ")
        compose.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Zoe").assertDoesNotExist()
        compose.onNodeWithText("Alpha group").assertDoesNotExist()
        compose.onNodeWithText("No groups found").assertExists()

        search.performScrollTo().performTextReplacement(" Z@LIVE.COM ")
        compose.onNodeWithText("Zulu group").assertIsDisplayed()
        compose.onNodeWithText("Alpha group").assertDoesNotExist()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("No contacts found").performScrollTo().assertIsDisplayed()

        search.performScrollTo().performTextReplacement("missing")
        compose.onNodeWithText("No groups found").assertExists()
        compose.onNodeWithText("No contacts found").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your contact list is empty.").assertDoesNotExist()
    }

    @Test fun searchRevealsCollapsedSectionsAndClearingRestoresTheList() {
        showContacts()
        val searchBounds = compose.onNodeWithTag("contacts_search_bar").fetchSemanticsNode().boundsInRoot
        val sortBounds = compose.onNodeWithTag("contacts_sort_button").fetchSemanticsNode().boundsInRoot
        assertTrue("Search should be left of Sort", searchBounds.right <= sortBounds.left)
        assertEquals("Search and Sort should be vertically centered", searchBounds.center.y, sortBounds.center.y, 1f)
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Other Contacts").performScrollTo().performClick()

        compose.onNodeWithContentDescription("Search").performScrollTo().performTextReplacement("a")
        compose.onNodeWithText("Alpha group").assertIsDisplayed()
        compose.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performScrollTo().performClick()
        compose.onNodeWithText("Zulu group").assertIsDisplayed()
        compose.onNodeWithText("Zoe").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test fun searchPreservesSortAndOfflineFilteringAndExcludesBlockedContacts() {
        showContacts(contacts + listOf(
            Contact("blocked", "Blocked group", "blocked@live.com", Presence.Online, isBlocked = true, isGroup = true),
            Contact("member", "Group member", "member@live.com", Presence.Online, isForwardListContact = false),
        ))
        compose.onNodeWithContentDescription("Search").performScrollTo().performTextReplacement("group")
        compose.onNodeWithText("Blocked group").assertDoesNotExist()
        compose.onNodeWithText("Group member").assertDoesNotExist()
        compose.onNodeWithTag("contacts_sort_button").performClick()
        compose.onNodeWithText("Name").performClick()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        assertAbove("Alpha group", "Zulu group")
        compose.onNodeWithTag("contacts_sort_button").performScrollTo().performClick()
        compose.onNodeWithTag("hide_offline_contacts").performClick()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Alpha group").assertDoesNotExist()
        compose.onNodeWithText("Zulu group").assertIsDisplayed()

        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("Zoe").performScrollTo().assertIsDisplayed()
    }

    @Test fun sectionHeightAnimatesThroughIntermediatePositionsInBothDirections() {
        showContacts()
        val expandedTop = otherContactsTop()
        val childSpacing = groupChildSpacing()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Groups").performClick()
        compose.mainClock.advanceTimeBy(64)
        val collapsingTop = otherContactsTop()
        assertEquals("Child rows should slide together without compressing their spacing", childSpacing, groupChildSpacing(), 1f)
        compose.mainClock.advanceTimeBy(2_000)
        val collapsedTop = otherContactsTop()
        assertTrue("Collapse should move the next section gradually", collapsingTop > collapsedTop && collapsingTop < expandedTop)
        compose.onNodeWithText("Alpha group").assertDoesNotExist()
        compose.onNodeWithText("Groups").performClick()
        compose.mainClock.advanceTimeBy(64)
        val expandingTop = otherContactsTop()
        assertEquals("Expanding rows should retain their spacing", childSpacing, groupChildSpacing(), 1f)
        assertTrue("Expansion should move the next section gradually", expandingTop > collapsedTop && expandingTop < expandedTop)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Alpha group").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test fun searchFocusAnimatesItsWidthAndSortLabelInBothDirections() {
        showContacts()
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()
        compose.onNodeWithText("Sort").assertIsDisplayed()
        val search = compose.onNodeWithTag("contacts_search_bar")
        val sort = compose.onNodeWithTag("contacts_sort_button")
        val initialSearchWidth = search.fetchSemanticsNode().boundsInRoot.width
        val initialSortWidth = sort.fetchSemanticsNode().touchBoundsInRoot.width
        compose.mainClock.autoAdvance = false
        onView(withContentDescription("Search")).check { view, error ->
            if (error != null) throw error
            assertTrue("Search should gain focus", (view as EditText).requestFocus())
        }
        compose.mainClock.advanceTimeBy(64)
        val growingSearchWidth = search.fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.advanceTimeBy(2_000)
        val focusedSearchWidth = search.fetchSemanticsNode().boundsInRoot.width
        val compactSortWidth = sort.fetchSemanticsNode().touchBoundsInRoot.width
        assertTrue("Search should grow gradually: $initialSearchWidth -> $growingSearchWidth -> $focusedSearchWidth",
            growingSearchWidth > initialSearchWidth && growingSearchWidth < focusedSearchWidth)
        assertTrue("Sort should become compact", compactSortWidth < initialSortWidth)
        assertEquals("Search should use the space released by Sort",
            initialSearchWidth + initialSortWidth, focusedSearchWidth + compactSortWidth, 1f)
        compose.onNodeWithText("Sort").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
        sort.assertHasClickAction()
        sort.assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        assertSortContainerIsCircular()

        onView(withContentDescription("Search")).check { view, error ->
            if (error != null) throw error
            (view as EditText).clearFocus()
        }
        compose.mainClock.advanceTimeBy(64)
        val shrinkingSearchWidth = search.fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue("Search should shrink gradually: $focusedSearchWidth -> $shrinkingSearchWidth -> $initialSearchWidth",
            shrinkingSearchWidth < focusedSearchWidth && shrinkingSearchWidth > initialSearchWidth)
        assertEquals(initialSearchWidth, search.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(initialSortWidth, sort.fetchSemanticsNode().touchBoundsInRoot.width, 1f)
        compose.onNodeWithText("Sort").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        sort.performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
    }

    @Test fun backStopsFocusedSearchAndRestoresTheList() {
        showContacts()
        onView(withContentDescription("Search")).check { view, error ->
            if (error != null) throw error
            (view as EditText).requestFocus()
        }
        compose.onNodeWithContentDescription("Search").performTextReplacement("group")
        compose.onNodeWithText("Alice").assertDoesNotExist()
        pressBack()
        assertSearchStopped()
    }

    @Test fun backStopsSearchAfterTheFieldHasLostFocus() {
        showContacts()
        compose.onNodeWithContentDescription("Search").performTextReplacement("group")
        onView(withContentDescription("Search")).check { view, error ->
            if (error != null) throw error
            (view as EditText).clearFocus()
        }
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()
        pressBack()
        assertSearchStopped()
    }

    @Test fun backStopsSearchWhenTheKeyboardIsOpen() {
        showContacts()
        val search = compose.onNodeWithContentDescription("Search")
        search.performClick().performTextReplacement("group")
        lateinit var editor: EditText
        onView(withContentDescription("Search")).check { view, error ->
            if (error != null) throw error
            editor = view as EditText
        }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        pressBack()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        assertSearchStopped()
    }

    private fun assertSearchStopped() {
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onNodeWithText("Sort").assertIsDisplayed()
        compose.onNodeWithText("Alpha group").assertIsDisplayed()
        compose.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Zoe").performScrollTo().assertIsDisplayed()
    }

    private fun assertSortContainerIsCircular() {
        // Check the painted container; its touch target can be larger than the circle.
        val pixels = compose.onNodeWithTag("contacts_sort_button").captureToImage().toPixelMap()
        val background = pixels[0, 0].toArgb()
        val colorCounts = mutableMapOf<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y].toArgb()
            if (color != background) colorCounts[color] = colorCounts.getOrDefault(color, 0) + 1
        }
        val container = colorCounts.maxBy { it.value }.key
        var left = pixels.width
        var right = 0
        var top = pixels.height
        var bottom = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].toArgb() == container) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        assertEquals("Collapsed Sort must have equal painted width and height",
            (right - left + 1).toFloat(), (bottom - top + 1).toFloat(), 2f)
        assertEquals("The circular container should leave its corner unpainted",
            background, pixels[left + 1, top + 1].toArgb())
    }

    private fun otherContactsTop() = compose.onNodeWithText("Other Contacts").fetchSemanticsNode().positionInRoot.y

    private fun groupChildSpacing() = compose.onNodeWithText("Alpha group").fetchSemanticsNode().positionInRoot.y -
        compose.onNodeWithText("Zulu group").fetchSemanticsNode().positionInRoot.y

    private fun assertAbove(first: String, second: String) {
        compose.onNodeWithText(second).performScrollTo()
        val firstTop = compose.onNodeWithText(first).fetchSemanticsNode().positionInRoot.y
        val secondTop = compose.onNodeWithText(second).fetchSemanticsNode().positionInRoot.y
        assertTrue("$first should precede $second", firstTop < secondTop)
    }

    private fun showContacts(contacts: List<Contact> = this.contacts) {
        compose.setContent {
            MsngrTheme {
                ContactsPage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(), contacts,
                    UserProfileSnapshot("Me", "me@example.com", Presence.Online), {}, {}, {}, {}, { _, _ -> })
            }
        }
    }
}
