package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ColorDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mainColorBrightnessTracksTypedColorAndUpdatesTheAppliedColor() {
        var selected: Int? = null
        compose.setContent {
            MaterialTheme { MainColorDialog(0xFFFFFFFF.toInt(), { selected = it }, {}) }
        }
        verifyBrightnessEditing(compose.onNodeWithTag("main_color_hex"))
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(0xFF404040.toInt(), selected) }
    }

    @Test fun plusColorBrightnessTracksTypedColorAndUpdatesTheAppliedColor() {
        var selected: Int? = null
        compose.setContent {
            MaterialTheme {
                MessengerPlusCustomColorDialog(
                    initialColor = requireNotNull(MessengerPlusColorValue.fromHex("#FFFFFF")),
                    applyLabel = "Apply",
                    onColorSelected = { selected = it.argb },
                    onDismissRequest = {},
                )
            }
        }
        verifyBrightnessEditing(compose.onNodeWithText("Color code"))
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(0xFF404040.toInt(), selected) }
    }

    private fun verifyBrightnessEditing(field: SemanticsNodeInteraction) {
        val slider = compose.onNodeWithContentDescription("Brightness")
        field.performTextReplacement("#808080")
        assertEquals(128f / 255f,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        field.assertTextContains("#FFFFFF")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        field.assertTextContains("#404040")
    }
}
