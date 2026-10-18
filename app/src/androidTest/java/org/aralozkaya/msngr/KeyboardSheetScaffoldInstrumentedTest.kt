package org.aralozkaya.msngr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.pages.components.DefaultKeyboardSheetHeight
import org.aralozkaya.msngr.pages.components.KeyboardSheet
import org.aralozkaya.msngr.pages.components.keyboardSheetWindowInsets
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardSheetScaffoldInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var firstVisible by mutableStateOf(false)
    private var secondVisible by mutableStateOf(false)
    private var expandableVisible by mutableStateOf(false)

    @Before
    fun clearSavedKeyboardHeight() {
        compose.activity.applicationContext
            .getSharedPreferences("msngr_keyboard", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun showScaffold() {
        compose.setContent {
            MsngrTheme {
                Box(Modifier.fillMaxSize().testTag(PAGE_TAG)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(keyboardSheetWindowInsets())
                            .testTag(INSET_TAG),
                    )
                    KeyboardSheet(visible = firstVisible, onDismissRequest = { firstVisible = false }) {
                        Box(Modifier.fillMaxSize().testTag(FIRST_TAG))
                    }
                    KeyboardSheet(visible = secondVisible, onDismissRequest = { secondVisible = false }) {
                        Box(Modifier.fillMaxSize().testTag(SECOND_TAG))
                    }
                    KeyboardSheet(
                        visible = expandableVisible,
                        onDismissRequest = { expandableVisible = false },
                        expandable = true,
                    ) {
                        Box(Modifier.fillMaxSize().testTag(EXPANDABLE_TAG))
                    }
                }
            }
        }
    }

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String) = compose.onNode(hasTestTag(tag)).getBoundsInRoot()

    /** The top of the sheet reported through [keyboardSheetWindowInsets]. */
    private fun sheetTop(): Float = bounds(INSET_TAG).bottom.value

    private fun defaultTop(): Float =
        (compose.onRoot().getBoundsInRoot().bottom - DefaultKeyboardSheetHeight).value

    private fun openFirst() {
        showScaffold()
        compose.runOnIdle { firstVisible = true }
        compose.waitUntil(5_000) { exists(FIRST_TAG) }
        compose.waitForIdle()
    }

    private fun openExpandable() {
        showScaffold()
        compose.runOnIdle { expandableVisible = true }
        compose.waitUntil(5_000) { exists(EXPANDABLE_TAG) }
        compose.waitForIdle()
    }

    @Test
    fun sheetsDoNotExpandByDefault() {
        openFirst()
        compose.onNode(hasTestTag(FIRST_TAG)).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(defaultTop(), sheetTop(), 1f)
        assertTrue(firstVisible)
    }

    @Test
    fun expandableSheetOpensAtKeyboardHeightAndGrowsUpToTwice() {
        openExpandable()
        assertEquals(defaultTop(), sheetTop(), 1f)
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals(root.bottom.value, bounds(PAGE_TAG).bottom.value, 1f)
        // The content fits the visible part of the sheet.
        assertTrue(bounds(EXPANDABLE_TAG).bottom <= root.bottom)

        compose.onNodeWithContentDescription(DRAG_HANDLE).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expandedTop = (root.bottom - DefaultKeyboardSheetHeight * 2).value
        assertEquals(expandedTop, sheetTop(), 1f)
        assertEquals(root.bottom.value, bounds(PAGE_TAG).bottom.value, 1f)
        val content = bounds(EXPANDABLE_TAG)
        assertTrue(content.top.value < defaultTop())
        assertTrue(content.bottom <= root.bottom)

        // Back collapses to the keyboard height before dismissing.
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(defaultTop(), sheetTop(), 1f)
        assertTrue(expandableVisible)
        Espresso.pressBack()
        compose.waitUntil(5_000) { !exists(EXPANDABLE_TAG) }
        compose.runOnIdle { assertFalse(expandableVisible) }
    }

    @Test
    fun swappingToAnExpandableSheetKeepsTheKeyboardHeight() {
        openFirst()
        compose.runOnIdle { expandableVisible = true }
        compose.waitUntil(5_000) { exists(EXPANDABLE_TAG) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(firstVisible) }
        assertEquals(defaultTop(), sheetTop(), 1f)

        // It can still expand afterwards.
        compose.onNodeWithContentDescription(DRAG_HANDLE).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals((root.bottom - DefaultKeyboardSheetHeight * 2).value, sheetTop(), 1f)

        // Swapping back from the expanded sheet collapses to the keyboard height.
        compose.runOnIdle { firstVisible = true }
        compose.waitUntil(5_000) { exists(FIRST_TAG) }
        compose.waitForIdle()
        assertEquals(defaultTop(), sheetTop(), 1f)
    }

    @Test
    fun sheetUsesDefaultHeightFromTheBottomAndLeavesThePageFullScreenBehindIt() {
        openFirst()
        val root = compose.onRoot().getBoundsInRoot()
        // The page stays full-screen behind the sheet while keyboardSheetWindowInsets reports the top.
        val expectedTop = (root.bottom - DefaultKeyboardSheetHeight).value
        assertEquals(root.bottom.value, bounds(PAGE_TAG).bottom.value, 1f)
        assertEquals(expectedTop, sheetTop(), 1f)
        // The handle takes its space from the slot; the content starts below it.
        val handle = compose.onNodeWithContentDescription(DRAG_HANDLE).getBoundsInRoot()
        val content = bounds(FIRST_TAG)
        assertTrue(handle.top.value >= expectedTop - 1f)
        assertTrue(content.top >= handle.bottom)
        assertTrue(content.bottom <= root.bottom)
    }

    @Test
    fun tappingTheHandleDismissesTheSheet() {
        openFirst()
        compose.onNodeWithContentDescription(DRAG_HANDLE).performClick()
        compose.waitUntil(5_000) { !exists(FIRST_TAG) }
        compose.runOnIdle { assertFalse(firstVisible) }
    }

    @Test
    fun draggingTheSheetDownDismissesIt() {
        openFirst()
        compose.onNode(hasTestTag(FIRST_TAG)).performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { !exists(FIRST_TAG) }
        compose.runOnIdle { assertFalse(firstVisible) }
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals(root.bottom.value, sheetTop(), 1f)
    }

    @Test
    fun showingAnotherSheetReplacesTheFirstAndNotifiesIt() {
        openFirst()
        compose.runOnIdle { secondVisible = true }
        compose.waitUntil(5_000) { exists(SECOND_TAG) }
        compose.runOnIdle {
            assertFalse(firstVisible)
            assertTrue(secondVisible)
        }
        assertFalse(exists(FIRST_TAG))
    }

    @Test
    fun backDismissesTheSheetAndReleasesThePage() {
        openFirst()
        Espresso.pressBack()
        compose.waitUntil(5_000) { !exists(FIRST_TAG) }
        compose.runOnIdle { assertFalse(firstVisible) }
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals(root.bottom.value, sheetTop(), 1f)
    }

    private companion object {
        const val PAGE_TAG = "keyboard_sheet_page"
        const val INSET_TAG = "keyboard_sheet_inset"
        const val FIRST_TAG = "keyboard_sheet_first"
        const val SECOND_TAG = "keyboard_sheet_second"
        const val EXPANDABLE_TAG = "keyboard_sheet_expandable"
        const val DRAG_HANDLE = "Drag handle"
    }
}
