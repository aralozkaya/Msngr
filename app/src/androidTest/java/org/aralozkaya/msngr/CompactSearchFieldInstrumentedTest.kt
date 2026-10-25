package org.aralozkaya.msngr

import android.text.style.ReplacementSpan
import android.view.ActionMode
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactSearchFieldInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previousSettings: EmoticonSettings

    @Before fun enableEmoticons() {
        previousSettings = EmoticonPrefs.state(compose.activity).value
        EmoticonPrefs.setConvertTypedShortcuts(compose.activity, true)
        EmoticonPrefs.setUseOriginalEmoticons(compose.activity, true)
    }

    @After fun restoreEmoticons() {
        EmoticonPrefs.setConvertTypedShortcuts(compose.activity, previousSettings.convertTypedShortcuts)
        EmoticonPrefs.setUseOriginalEmoticons(compose.activity, previousSettings.useOriginalEmoticons)
    }

    @Test
    fun compactHeightGrowsWithLargeTextAndPreservesShortcutAndUnicodeQueries() {
        val state = TextFieldState()
        val fontScale = mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                MaterialTheme {
                    CompactSearchField(
                        state = state,
                        placeholder = "Search members",
                        clearContentDescription = "Clear search",
                        modifier = Modifier.fillMaxWidth().testTag("search"),
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("search")
        field.assertHeightIsEqualTo(48.dp)
        val compactHeight = field.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithContentDescription("Search members").performTextReplacement("(Y) 🎂 :)")
        compose.runOnIdle {
            assertEquals("(Y) 🎂 :)", state.text.toString())
            fontScale.floatValue = 2f
        }
        compose.waitForIdle()
        val largeTextHeight = field.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("Large text must increase the field height: $compactHeight -> $largeTextHeight",
            largeTextHeight > compactHeight)
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle {
            assertEquals("", state.text.toString())
            assertEquals(TextRange.Zero, state.selection)
        }
    }

    @Test
    fun selectionMenuOmitsEmoticonPickerAndRetainsSearchText() {
        val state = TextFieldState("one two three")
        compose.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        CompactSearchField(
                            state = state,
                            placeholder = "Search members",
                            clearContentDescription = "Clear search",
                            modifier = Modifier.fillMaxWidth().testTag("search"),
                        )
                    }
                }
        }
        onView(withContentDescription("Search members")).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            editor.requestFocus()
            editor.setSelection(4, 7)
            val callback = checkNotNull(editor.customSelectionActionModeCallback)
            val mode = checkNotNull(editor.startActionMode(callback, ActionMode.TYPE_FLOATING))
            assertTrue(mode.menu.findItem(R.id.insert_emoticon) == null)
            assertTrue(mode.menu.findItem(R.id.format_colors) == null)
            mode.finish()
        }
        compose.onNodeWithTag(EMOTICON_PICKER_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("one two three", state.text.toString())
        }
    }

    @Test
    fun rendersOriginalAndModernGlyphsWithoutChangingTheSearchText() {
        val state = TextFieldState(":)")
        compose.setContent {
            MaterialTheme {
                CompactSearchField(state, "Search members", "Clear search", Modifier.fillMaxWidth())
            }
        }
        fun assertGlyph(name: String) {
            onView(withContentDescription("Search members")).check { view, error ->
                if (error != null) throw error
                val text = (view as EditText).text
                val spans = text.getSpans(0, text.length, ReplacementSpan::class.java)
                assertEquals(name, spans.single().javaClass.simpleName)
                assertEquals(":)", text.toString())
            }
        }
        assertGlyph("OriginalMsnGlyphSpan")
        compose.runOnIdle { EmoticonPrefs.setUseOriginalEmoticons(compose.activity, false) }
        compose.waitForIdle()
        assertGlyph("UnicodeMsnGlyphSpan")
        compose.runOnIdle { assertEquals(":)", state.text.toString()) }
    }
}
