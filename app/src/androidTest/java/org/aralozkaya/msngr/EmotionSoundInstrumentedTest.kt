package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.EmotionSoundService
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.KeyboardSheetScaffold
import org.aralozkaya.msngr.protocol.EmotionSound
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class EmotionSoundInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val hello get() = MsnEmotionSound.builtIns.first()
    private fun fixture() {
        SessionPrefs.completeLogin(context, "emotion-ui@example.test", "test-password", false, ProviderOptions.ESCARGOT, ServerOverrides(), true)
        val bytes = instrumentation.context.assets.open("emotion-sounds/hello.mp3").use { it.readBytes() }
        context.emotionSoundStore().put(SessionPrefs.preferenceAccount(context)!!.storageId, MsnEmotionSound.DEFAULT_SERVER, hello, bytes)
    }
    @After fun restoreTransientAccount() { SessionPrefs.logout() }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("emotionSoundScreenshots") != "true") return
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun groupComposerOffersEmotionSoundsWithUnknownMemberCapabilitiesAndSearchUsesTheKeyboardSheet() {
        fixture()
        val sent = AtomicReference<EmotionSound>()
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            ChatScreen("", {}, Contact("group@live.com", "Test group", "group@live.com", Presence.Online, isGroup = true),
                messages = emptyList(), isHistoryLoaded = true, canLoadOlderMessages = false,
                isTyping = false, onBack = {}, onSendMessage = { _, _ -> }, onRetryMessage = {},
                onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {},
                onSendEmotionSound = { sent.set(it); Result.success(Unit) })
        } } }
        compose.onNodeWithContentDescription("Show emoticons").performTouchInput { longClick() }
        compose.onNodeWithTag("emotion_sound_menu_item").assertIsEnabled().performClick()
        compose.onNodeWithTag("emotion_sound_picker").assertIsDisplayed()
        screenshot("emotion-sound-picker.png")
        compose.onNodeWithTag("emotion_sound_category").performClick()
        compose.onNodeWithTag("emotion_sound_category_menu").assertIsDisplayed()
        compose.onNodeWithTag("emotion_sound_category_0").assertIsSelected()
        screenshot("emotion-sound-category-menu.png")
        compose.onNodeWithTag("emotion_sound_category_5").performScrollTo().performClick()
        compose.onNodeWithTag("emotion_sound_category_menu").assertDoesNotExist()
        compose.onNodeWithTag("emotion_sound_random").performClick()
        compose.onNodeWithTag("emotion_sound_random_dialog").assertIsDisplayed()
        compose.onNodeWithTag("emotion_sound_random_keep").assertIsNotEnabled()
        compose.onNodeWithText("Category: Sound Effects").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("emotion_sound_random_dialog").assertDoesNotExist()
        Espresso.onView(ViewMatchers.withContentDescription("Search sounds")).perform(ViewActions.click(), ViewActions.replaceText("hello"))
        compose.onNodeWithText("Hello!").assertIsDisplayed()
        compose.onNodeWithText("Bye!").assertDoesNotExist()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Hello!").performClick()
        compose.waitUntil(10_000) { sent.get() != null }
        assertEquals(hello, sent.get())
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("emotion_sound_picker").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun styledSoundRemainsTextAndOpensTheNativePlaybackSheet() {
        fixture()
        MessengerPlusPrefs.setPlayEmotionSounds(context, false)
        val message = ChatMessage(22, "peer", MessageAuthor.Contact, "\u0004#[b][c=#FF6600]Styled sound[/c][/b]", 15,
            nativeStyle = NativeTextStyle(italic = true, underline = true))
        compose.setContent { MaterialTheme { MessageBubble(message, true) } }
        compose.onNodeWithText("Styled sound").assertIsDisplayed()
        compose.onNodeWithTag("emotion_sound_message_22").performClick()
        compose.onNodeWithTag("emotion_sound_message_sheet").assertIsDisplayed()
        screenshot("emotion-sound-message.png")
        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(8_000) { EmotionSoundAudio.isPlaying }
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(3_000) { !EmotionSoundAudio.isPlaying }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
    @Test fun autoplayPreferenceIsIndependentOfRenderingAndSurvivesReload() {
        fixture()
        MessengerPlusPrefs.setPlayEmotionSounds(context, false)
        MessengerPlusPrefs.setEnabled(context, false)
        MessengerPlusPrefs.reload(context)
        assertFalse(MessengerPlusPrefs.state(context).value.playEmotionSounds)
        MessengerPlusPrefs.setEnabled(context, true)
        assertFalse(MessengerPlusPrefs.state(context).value.playEmotionSounds)
        MessengerPlusPrefs.setPlayEmotionSounds(context, true)
        MessengerPlusPrefs.reload(context)
        assertTrue(MessengerPlusPrefs.state(context).value.playEmotionSounds)
    }
    @Test fun randomAndIdDownloadUseTheRealConfiguredServiceAndCanBeSavedAndReplayed() = runBlocking {
        fixture()
        val service = EmotionSoundService(SessionPrefs.readServerConfig(context).emotionSoundServer)
        val (sound, bytes) = service.random(5)
        assertNotNull(MsnEmotionSound.hash(sound.id))
        val (downloaded, fetched) = service.download(sound)
        assertEquals(sound.id, downloaded.id)
        assertArrayEquals(bytes, fetched)
        val owner = SessionPrefs.preferenceAccount(context)!!.storageId
        context.emotionSoundStore().put(owner, MsnEmotionSound.DEFAULT_SERVER, sound, bytes, true)
        val message = ChatMessage(24, "peer", MessageAuthor.Contact, MsnEmotionSound.message(sound), 15)
        compose.setContent { MaterialTheme { EmotionSoundMessageSheet(message) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Remove from saved sounds").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove from saved sounds").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Add to saved sounds").fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(bytes, context.emotionSoundStore().bytes(owner, MsnEmotionSound.DEFAULT_SERVER, sound))
        compose.onNodeWithText("Add to saved sounds").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Remove from saved sounds").fetchSemanticsNodes().isNotEmpty() }
    }
}
