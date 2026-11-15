package org.aralozkaya.msngr

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.pages.components.CustomServerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomServerDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun modalFillsScreenValidatesInputsAndSavesNormalizedOverrides() {
        val visible = mutableStateOf(true)
        var saved: ServerOverrides? = null
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    CustomServerDialog(
                        initial = ServerPresets.presetFor(ProviderOptions.ESCARGOT),
                        onDismiss = { visible.value = false },
                        onSave = {
                            saved = it
                            visible.value = false
                        },
                    )
                }
            }
        }

        val modal = compose.onNodeWithTag("custom-server-modal").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertEquals(compose.activity.window.decorView.width.toFloat(), modal.width, 1f)
            assertEquals(compose.activity.window.decorView.height.toFloat(), modal.height, 1f)
        }
        compose.onNodeWithText("Custom MSNP service").assertIsDisplayed()
        compose.onNodeWithTag("custom-server-save").assertIsEnabled()

        compose.onNodeWithTag("custom-server-main-host").performTextClearance()
        compose.onNodeWithTag("custom-server-save").assertIsNotEnabled()
        compose.onNodeWithTag("custom-server-main-host").performTextInput(" msnp.custom.test ")
        compose.onNodeWithTag("custom-server-save").assertIsEnabled()

        compose.onNodeWithTag("custom-server-main-port").performTextReplacement("70000")
        compose.onNodeWithTag("custom-server-save").assertIsNotEnabled()
        compose.onNodeWithTag("custom-server-main-port").performTextReplacement("1865")
        compose.onNodeWithTag("custom-server-save").assertIsEnabled()

        compose.onNodeWithTag("custom-server-config-host").performTextReplacement(" conf.custom.test ")
        compose.onNodeWithText("Mail").performScrollTo().performClick()
        compose.onNodeWithTag("custom-server-save").assertIsNotEnabled()
        compose.onNodeWithText("Mail host").performScrollTo().performTextInput(" mail.custom.test ")
        compose.onNodeWithTag("custom-server-save").assertIsEnabled()

        compose.onNodeWithTag("custom-server-save").performClick()
        compose.onNodeWithTag("custom-server-modal").assertDoesNotExist()
        val result = saved!!
        assertEquals("msnp.custom.test", result.mainServer)
        assertEquals(1865, result.mainPort)
        assertEquals("conf.custom.test", result.configServer)
        assertTrue(result.mailServerEnabled)
        assertEquals("mail.custom.test", result.mailServer)
    }

    @Test
    fun closeButtonAndBackDismissWithoutSaving() {
        val visible = mutableStateOf(true)
        var saved: ServerOverrides? = null
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    CustomServerDialog(
                        initial = ServerPresets.presetFor(ProviderOptions.ESCARGOT),
                        onDismiss = { visible.value = false },
                        onSave = { saved = it },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("custom-server-modal").assertDoesNotExist()
        assertNull(saved)

        compose.runOnUiThread { visible.value = true }
        compose.onNodeWithTag("custom-server-modal").assertIsDisplayed()
        onView(isRoot()).inRoot(isDialog()).perform(ViewActions.pressBack())
        compose.onNodeWithTag("custom-server-modal").assertDoesNotExist()
        assertNull(saved)
    }
}
