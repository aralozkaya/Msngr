package org.aralozkaya.msngr

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.data.CHUNKING_CAPABILITY
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.INK_GIF_CAPABILITY
import org.aralozkaya.msngr.data.INK_ISF_CAPABILITY
import org.aralozkaya.msngr.data.ONLINE_VIA_MOBILE_CAPABILITY
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.KeyboardSheetScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientCapabilitiesAvailabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun oneToOneChatDisablesAndEnablesFeaturesAccordingToRecipientCapabilities() {
        val wlm2009Base = 2_688_319_548L
        val wlm2009Ext = 48L
        val wlm85Base = 2_153_611_324L
        val msn70Base = 0x4000_802CL
        val pidginBase = 0x7000_0024L
        val msn60Base = 0x1000_002CL
        val contact = mutableStateOf(
            Contact(
                id = "peer@example.com",
                nickname = "Peer",
                email = "peer@example.com",
                presence = Presence.Online,
                clientCapabilities = wlm2009Base,
                extendedCapabilities = wlm2009Ext,
            ),
        )
        val draft = mutableStateOf("")
        compose.setContent {
            MsngrTheme {
                KeyboardSheetScaffold {
                    ChatScreen(
                        draft = draft.value,
                        onDraftChange = { draft.value = it },
                        contact = contact.value,
                        messages = emptyList(),
                        isHistoryLoaded = true,
                        canLoadOlderMessages = false,
                        isTyping = false,
                        onBack = {},
                        onSendMessage = { _, _ -> },
                        onRetryMessage = {},
                        onSendTyping = {},
                        onSendNudge = {},
                        onLoadOlderMessages = {},
                    )
                }
            }
        }

        // Official WLM 2009 (2688319548:48) enables voice clips, nudges, handwriting, winks, photos, files, and activities.
        compose.onNodeWithContentDescription("Record voice clip").assertIsEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsEnabled()
        Espresso.pressBack()

        // Official WLM 8.5 (2153611324:0) supports voice clips, nudges, files, games/activities, handwriting, and winks, but not photos.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = wlm85Base,
                extendedCapabilities = 0L,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsEnabled()
        Espresso.pressBack()

        // MSN 7.0 (0x4000802C:0) enables nudges, files, games/activities, handwriting, and winks, while disabling voice clips and photos.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = msn70Base,
                extendedCapabilities = 0L,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsEnabled()

        // Open Games & activities dialog on WLM 2009, then switch to Pidgin (0x70000024:0) and verify the dialog closes cleanly.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = wlm2009Base,
                extendedCapabilities = wlm2009Ext,
            )
        }
        compose.onNodeWithTag("sharing_menu_games").performClick()
        compose.onNodeWithTag("game_catalog_dialog").assertIsDisplayed()
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = pidginBase,
                extendedCapabilities = 0L,
            )
        }
        compose.onNodeWithTag("game_catalog_dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsNotEnabled()
        Espresso.pressBack()
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsEnabled()
        compose.onNodeWithTag("wink_menu_item").assertIsNotEnabled()
        compose.onNodeWithTag("emotion_sound_menu_item").assertIsEnabled()
        Espresso.pressBack()

        // MSN 6.0 (0x1000002C:0) keeps files & handwriting enabled while disabling nudges, winks, voice clips, photos, and activities; text stays enabled.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = msn60Base,
                extendedCapabilities = 0L,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsNotEnabled()
        Espresso.pressBack()

        // Opening handwriting on WLM 2009 and losing ink support closes handwriting mode cleanly.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = wlm2009Base,
                extendedCapabilities = wlm2009Ext,
            )
        }
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertIsDisplayed()
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = wlm2009Base and (INK_GIF_CAPABILITY or INK_ISF_CAPABILITY).inv(),
            )
        }
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag("wink_menu_item").assertIsEnabled()
        compose.onNodeWithTag("emotion_sound_menu_item").assertIsEnabled()
        Espresso.pressBack()

        // Minimal (0:0), removing chunking (0x20), and mobile clients disable interactive MSNP features cleanly.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = 0L,
                extendedCapabilities = 0L,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsNotEnabled()

        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = wlm2009Base and CHUNKING_CAPABILITY.inv(),
                extendedCapabilities = wlm2009Ext,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsNotEnabled()

        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = wlm2009Base or ONLINE_VIA_MOBILE_CAPABILITY,
                extendedCapabilities = wlm2009Ext,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsNotEnabled()
    }
}
