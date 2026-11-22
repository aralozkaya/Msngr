@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.AppearanceSettingsScreen
import org.aralozkaya.msngr.pages.BlockedContactsPage
import org.aralozkaya.msngr.pages.ContactsPage
import org.aralozkaya.msngr.pages.LayoutSettingsScreen
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class CompactUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun isolatedContext() = object : ContextWrapper(compose.activity) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("compact_ui_test_$name", mode)
    }.also {
        AccountPreferences.preferences(it, "msngr_appearance").edit().clear().commit()
        AppearancePrefs.reload(it)
    }

    @Test fun appearanceLayoutOptionOpensSubpageAndAllFourSizesScalePreview() {
        val context = isolatedContext()
        assertEquals(UiDensity.Regular, AppearancePrefs.state(context).value.uiDensity)
        val inLayoutPage = mutableStateOf(false)

        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MsngrTheme {
                    if (!inLayoutPage.value) {
                        AppearanceSettingsScreen(
                            innerPadding = PaddingValues(),
                            scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                            onLayoutClick = { inLayoutPage.value = true },
                        )
                    } else {
                        LayoutSettingsScreen(
                            innerPadding = PaddingValues(),
                            scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                        )
                    }
                }
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("layout_settings"))
        compose.onNodeWithTag("layout_settings").assertIsDisplayed().performClick()

        compose.onNodeWithTag("layout_preview").assertIsDisplayed()
        compose.onNodeWithTag("layout_size_slider").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("compact_search_and_sort_switch"))
        compose.onNodeWithTag("compact_inline_status_switch").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("compact_hide_presence_text_switch").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("compact_search_and_sort_switch").assertIsEnabled().assertIsOff()

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("layout_preview"))
        val regularHeight = compose.onNodeWithTag("contact-row-preview-1").fetchSemanticsNode().boundsInRoot.height

        compose.onNodeWithTag("layout_size_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f / 3f) }
        compose.waitForIdle()
        val mediumHeight = compose.onNodeWithTag("contact-row-preview-1").fetchSemanticsNode().boundsInRoot.height

        compose.onNodeWithTag("layout_size_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f / 3f) }
        compose.waitForIdle()
        val smallHeight = compose.onNodeWithTag("contact-row-preview-1").fetchSemanticsNode().boundsInRoot.height

        compose.onNodeWithTag("layout_size_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()
        val minimalHeight = compose.onNodeWithTag("contact-row-preview-1").fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle {
            assertEquals(UiDensity.Minimal, AppearancePrefs.state(context).value.uiDensity)
            AppearancePrefs.reload(context)
            assertEquals(UiDensity.Minimal, AppearancePrefs.state(context).value.uiDensity)
        }

        assertTrue("Medium ($mediumHeight) should be shorter than Regular ($regularHeight)", mediumHeight < regularHeight)
        assertTrue("Small ($smallHeight) should be shorter than Medium ($mediumHeight)", smallHeight < mediumHeight)
        assertTrue("Minimal ($minimalHeight) should be shorter than Small ($smallHeight)", minimalHeight < smallHeight)
    }

    @Test fun compactTogglesInlineStatusAndHidePresenceTextInPreview() {
        val context = isolatedContext()
        AppearancePrefs.setUiDensity(context, UiDensity.Small)

        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MsngrTheme {
                    LayoutSettingsScreen(
                        innerPadding = PaddingValues(),
                        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    )
                }
            }
        }

        compose.waitForIdle()
        val nameBefore = compose.onNodeWithText("Alice", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val statusBefore = compose.onNodeWithText("Working on the new release all afternoon", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Status should be below name before inline toggle", statusBefore.top >= nameBefore.bottom - 2f)
        compose.onNodeWithText("Available", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Busy", useUnmergedTree = true).assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("compact_hide_presence_text_switch"))
        compose.onNodeWithTag("compact_inline_status_switch").assertIsEnabled().performClick()
        compose.onNodeWithTag("compact_hide_presence_text_switch").assertIsEnabled().performClick()

        compose.runOnIdle {
            AppearancePrefs.reload(context)
            assertTrue(AppearancePrefs.state(context).value.compactInlineStatusMessage)
            assertTrue(AppearancePrefs.state(context).value.compactHidePresenceText)
        }
        compose.onNodeWithTag("compact_inline_status_switch").assertIsOn()
        compose.onNodeWithTag("compact_hide_presence_text_switch").assertIsOn()

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("layout_preview"))
        val nameAfter = compose.onNodeWithText("Alice", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val statusAfter = compose.onNodeWithText("- Working on the new release all afternoon", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Inline status ($statusAfter) should be on the same line as name ($nameAfter)",
            abs(statusAfter.center.y - nameAfter.center.y) < nameAfter.height,
        )
        assertTrue(
            "Inline status ($statusAfter) should be to the right of name ($nameAfter)",
            statusAfter.left >= nameAfter.right - 2f,
        )
        compose.onNodeWithText("Available", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Busy", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Away", useUnmergedTree = true).assertDoesNotExist()

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("compact_hide_presence_text_switch"))
        compose.onNodeWithTag("compact_hide_presence_text_switch").performClick()
        compose.runOnIdle { assertFalse(AppearancePrefs.state(context).value.compactHidePresenceText) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("layout_preview"))
        compose.onNodeWithText("Available", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun minimalContactsPagePreservesInteractions() {
        val context = isolatedContext()
        val contacts = listOf(
            Contact("group-1", "Study Group", "study@live.com", Presence.Online, isGroup = true),
            Contact("friend-1", "Alice", "alice@example.com", Presence.Online, statusMessage = "Hello there"),
            Contact("friend-2", "Bob", "bob@example.com", Presence.Away, statusMessage = "At lunch", unreadCount = 3),
        )
        var openedChatId: String? = null
        var blockedId: String? = null

        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MsngrTheme {
                    ContactsPage(
                        innerPadding = PaddingValues(),
                        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                        contacts = contacts,
                        profile = UserProfileSnapshot("Me", "me@example.com", Presence.Online, statusMessage = "Online now"),
                        onPresenceSelected = {},
                        onEditProfile = {},
                        onOpenChat = { openedChatId = it.id },
                        onBlockContact = { blockedId = it.id },
                        onReadStateChange = { _, _ -> },
                    )
                }
            }
        }

        val regularUserCardHeight = compose.onNodeWithTag("logged_in_user_card").fetchSemanticsNode().boundsInRoot.height
        val regularSearchHeight = compose.onNodeWithTag("contacts_search_bar").fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle {
            AppearancePrefs.setUiDensity(context, UiDensity.Minimal)
            AppearancePrefs.setCompactInlineStatusMessage(context, true)
            AppearancePrefs.setCompactHidePresenceText(context, true)
        }
        compose.waitForIdle()

        val minimalUserCardHeight = compose.onNodeWithTag("logged_in_user_card").fetchSemanticsNode().boundsInRoot.height
        assertEquals(
            "Logged-in user card should never compact",
            regularUserCardHeight,
            minimalUserCardHeight,
            1f,
        )
        val defaultMinimalSearchHeight = compose.onNodeWithTag("contacts_search_bar").fetchSemanticsNode().boundsInRoot.height
        assertEquals(
            "Search bar should stay regular when compact search and sort toggle is off",
            regularSearchHeight,
            defaultMinimalSearchHeight,
            1f,
        )

        compose.runOnIdle {
            AppearancePrefs.setCompactSearchAndSort(context, true)
        }
        compose.waitForIdle()

        val compactSearchHeight = compose.onNodeWithTag("contacts_search_bar").fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Compact search bar ($compactSearchHeight) should be shorter than regular ($regularSearchHeight)",
            compactSearchHeight < regularSearchHeight,
        )

        compose.onNodeWithTag("contacts_sort_button").assertIsDisplayed().performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close sheet").performClick()

        compose.onNodeWithTag("contact-row-friend-1").performClick()
        compose.runOnIdle { assertEquals("friend-1", openedChatId) }

        compose.onNodeWithTag("contact-row-friend-2").performTouchInput { longClick() }
        compose.onNodeWithText("Block").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("friend-2", blockedId) }

        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Study Group").assertDoesNotExist()
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Study Group").assertIsDisplayed()
    }

    @Test fun blockedContactsPageIgnoresCompactLayoutSettings() {
        val blocked = listOf(
            Contact("blocked-1", "Blocked Friend", "blocked@example.com", Presence.Offline, isBlocked = true),
        )
        val density = mutableStateOf(UiDensity.Regular)

        compose.setContent {
            MsngrTheme {
                CompositionLocalProvider(LocalLayoutSettings provides LayoutSettings(density = density.value)) {
                    BlockedContactsPage(
                        innerPadding = PaddingValues(),
                        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                        contacts = blocked,
                        onUnblockContact = {},
                    )
                }
            }
        }

        val regularHeight = compose.onNodeWithTag("contact-row-blocked-1").fetchSemanticsNode().boundsInRoot.height
        density.value = UiDensity.Minimal
        compose.waitForIdle()
        val minimalHeight = compose.onNodeWithTag("contact-row-blocked-1").fetchSemanticsNode().boundsInRoot.height
        assertEquals("Blocked contacts row should not compact", regularHeight, minimalHeight, 1f)
    }
}
