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
import org.aralozkaya.msngr.data.EXTENDED_FILE_TRANSFER_CAPABILITY
import org.aralozkaya.msngr.data.EXTENDED_NUDGE_CAPABILITY
import org.aralozkaya.msngr.data.EXTENDED_PHOTO_SHARE_CAPABILITY
import org.aralozkaya.msngr.data.INK_GIF_CAPABILITY
import org.aralozkaya.msngr.data.INK_ISF_CAPABILITY
import org.aralozkaya.msngr.data.ONLINE_VIA_MOBILE_CAPABILITY
import org.aralozkaya.msngr.data.VOICE_CLIP_CAPABILITY
import org.aralozkaya.msngr.data.WINK_CAPABILITY
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.KeyboardSheetScaffold
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientCapabilitiesAvailabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun oneToOneChatDisablesAndEnablesFeaturesAccordingToRecipientCapabilities() {
        val fullBase = Msnp18Protocol.CLIENT_ID
        val fullExt = Msnp18Protocol.EXTENDED_CAPABILITIES.toLong()
        val contact = mutableStateOf(
            Contact(
                id = "peer@example.com",
                nickname = "Peer",
                email = "peer@example.com",
                presence = Presence.Online,
                clientCapabilities = fullBase,
                extendedCapabilities = fullExt,
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

        // Full client support enables voice clips, nudges, handwriting, winks, photos, files, and activities.
        compose.onNodeWithContentDescription("Record voice clip").assertIsEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsEnabled()
        Espresso.pressBack()

        // Removing only voice clip support disables the voice clip microphone button on the chat window.
        compose.runOnIdle {
            contact.value = contact.value.copy(clientCapabilities = fullBase and VOICE_CLIP_CAPABILITY.inv())
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()

        // Removing only file transfers, photos, or activities toggles individual sharing menu items.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = fullBase,
                extendedCapabilities = fullExt and EXTENDED_FILE_TRANSFER_CAPABILITY.inv(),
            )
        }
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsEnabled()
        Espresso.pressBack()

        compose.runOnIdle {
            contact.value = contact.value.copy(
                extendedCapabilities = fullExt and EXTENDED_PHOTO_SHARE_CAPABILITY.inv(),
            )
        }
        compose.onNodeWithContentDescription("Share or start an activity").assertIsEnabled().performClick()
        compose.onNodeWithTag("sharing_menu_photos").assertIsNotEnabled()
        compose.onNodeWithTag("sharing_menu_files").assertIsEnabled()
        compose.onNodeWithTag("sharing_menu_games").assertIsEnabled()

        // Open Games & activities dialog and verify removing activities support closes it.
        compose.onNodeWithTag("sharing_menu_games").performClick()
        compose.onNodeWithTag("game_catalog_dialog").assertIsDisplayed()
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = fullBase,
                extendedCapabilities = EXTENDED_NUDGE_CAPABILITY,
            )
        }
        compose.onNodeWithTag("game_catalog_dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsEnabled()

        // Removing nudge support disables Send nudge while text and emoticons stay enabled.
        compose.runOnIdle {
            contact.value = contact.value.copy(extendedCapabilities = 0L)
        }
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()

        // Opening handwriting and losing ink support closes handwriting mode cleanly.
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertIsDisplayed()
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = fullBase and (INK_GIF_CAPABILITY or INK_ISF_CAPABILITY).inv(),
            )
        }
        compose.onNodeWithTag(HANDWRITING_CANVAS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag(EMOTICON_MENU_HANDWRITING_ITEM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag("wink_menu_item").assertIsEnabled()
        compose.onNodeWithTag("emotion_sound_menu_item").assertIsEnabled()
        Espresso.pressBack()

        // Removing wink support disables Winks in the menu.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = contact.value.clientCapabilities and WINK_CAPABILITY.inv(),
            )
        }
        compose.onNodeWithTag(EMOTICON_MODE_BUTTON_TAG).performTouchInput { longClick() }
        compose.onNodeWithTag("wink_menu_item").assertIsNotEnabled()
        compose.onNodeWithTag("emotion_sound_menu_item").assertIsEnabled()
        Espresso.pressBack()

        // Removing chunking or switching to a mobile/non-Live client disables all interactive MSNP features cleanly.
        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = fullBase and CHUNKING_CAPABILITY.inv(),
                extendedCapabilities = fullExt,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsNotEnabled()

        compose.runOnIdle {
            contact.value = contact.value.copy(
                clientCapabilities = fullBase or ONLINE_VIA_MOBILE_CAPABILITY,
                extendedCapabilities = fullExt,
            )
        }
        compose.onNodeWithContentDescription("Record voice clip").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send nudge").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share or start an activity").assertIsNotEnabled()
    }
}
