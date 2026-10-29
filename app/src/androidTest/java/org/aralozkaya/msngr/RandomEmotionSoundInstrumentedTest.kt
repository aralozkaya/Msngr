package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.EmotionSoundHttp
import org.aralozkaya.msngr.data.EmotionSoundService
import org.aralozkaya.msngr.protocol.EmotionSound
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

class RandomEmotionSoundInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var owner: String
    private lateinit var bytes: ByteArray
    private lateinit var sound: EmotionSound
    private val requests = ConcurrentLinkedQueue<String>()
    private val kept = AtomicReference<EmotionSound>()

    @Before fun selectIsolatedAccountAndBuildSoundFixture() {
        SessionPrefs.completeLogin(context, "emotion-random-${UUID.randomUUID()}@example.test", "test-password",
            false, ProviderOptions.ESCARGOT, ServerOverrides(), true)
        owner = SessionPrefs.preferenceAccount(context)!!.storageId
        val audio = instrumentation.context.assets.open("emotion-sounds/hello.mp3").use { it.readBytes() }
        val id = "%04X%08X".format(java.util.Locale.ROOT, (audio.size + 128).and(0xffff), CRC32().apply { update(audio) }.value)
        val tag = ByteArray(128)
        "TAG".toByteArray().copyInto(tag)
        "Random preview".toByteArray().copyInto(tag, 3)
        "MsgPlus! - #$id".toByteArray().copyInto(tag, 97)
        tag[93] = 5
        tag[127] = 7
        bytes = audio + tag
        sound = MsnEmotionSound.downloaded(bytes)
    }

    @After fun restoreTransientAccount() { SessionPrefs.logout() }

    private fun showDialog(category: Int = 5, response: (String) -> ByteArray = { bytes }) {
        val service = EmotionSoundService(MsnEmotionSound.DEFAULT_SERVER, EmotionSoundHttp { url ->
            requests += url
            response(url)
        })
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (open.value) RandomEmotionSoundDialog(category, onDismiss = { open.value = false },
                onKept = { kept.set(it); open.value = false }, service = service)
        } }
    }

    private fun fetch() = compose.onNodeWithTag("emotion_sound_random_fetch").performClick()
    private fun awaitCandidate() = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("emotion_sound_random_preview").fetchSemanticsNodes().isNotEmpty()
    }
    private fun assertNotKept() {
        assertNull(kept.get())
        assertFalse(context.emotionSoundStore().isSaved(owner, MsnEmotionSound.DEFAULT_SERVER, sound))
        assertNull(context.emotionSoundStore().bytes(owner, MsnEmotionSound.DEFAULT_SERVER, sound))
    }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("emotionSoundScreenshots") != "true") return
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun anyCategoryShowsTheFetchedCategoryAndResetsForTheNextCandidate() {
        val attempts = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            showDialog(category = 0) {
                val category = if (attempts.incrementAndGet() == 1) 3 else {
                    check(release.await(15, TimeUnit.SECONDS))
                    4
                }
                bytes.copyOf().apply { this[size - 128 + 93] = category.toByte() }
            }
            compose.onNodeWithText("Category: Any category").assertIsDisplayed()
            fetch(); awaitCandidate()
            compose.onNodeWithText("Category: Music").assertIsDisplayed()
            compose.onNodeWithText("Category: Any category").assertDoesNotExist()
            screenshot("emotion-random-fetched-category.png")
            fetch()
            compose.waitUntil(5_000) { attempts.get() == 2 }
            compose.onNodeWithText("Category: Any category").assertIsDisplayed()
            compose.onNodeWithTag("emotion_sound_random_keep").assertIsNotEnabled()
            release.countDown()
            awaitCandidate()
            compose.onNodeWithText("Category: Vocals").assertIsDisplayed()
            compose.onNodeWithTag("emotion_sound_language").performClick()
            compose.onNodeWithTag("emotion_sound_language_7").performScrollTo().performClick()
            compose.onNodeWithText("Category: Any category").assertIsDisplayed()
            assertNotKept()
        } finally { release.countDown() }
    }

    @Test fun fetchedCategoryDoesNotChangeTheRequestFilterAndMissingMetadataShowsUnknown() {
        val attempts = AtomicInteger()
        showDialog {
            bytes.copyOf().apply { this[size - 128 + 93] = if (attempts.incrementAndGet() == 1) 3 else 0 }
        }
        compose.onNodeWithText("Category: Sound Effects").assertIsDisplayed()
        fetch(); awaitCandidate()
        compose.onNodeWithText("Category: Music").assertIsDisplayed()
        fetch(); awaitCandidate()
        compose.onNodeWithText("Category: Unknown").assertIsDisplayed()
        assertEquals(List(2) { MsnEmotionSound.DEFAULT_SERVER + "random?catId=5" }, requests.toList())
        assertNotKept()
    }

    @Test fun selectedLanguageUsesItsNativeIdAndPreviewIsOnlySavedWhenKept() {
        showDialog()
        compose.onNodeWithTag("emotion_sound_random_keep").assertIsNotEnabled()
        assertTrue(requests.isEmpty())
        compose.onNodeWithTag("emotion_sound_language").performClick()
        compose.onNodeWithTag("emotion_sound_language_0").assertIsSelected()
        screenshot("emotion-random-language-menu.png")
        compose.onNodeWithTag("emotion_sound_language_7").performScrollTo().performClick()
        fetch(); awaitCandidate()
        assertEquals(listOf(MsnEmotionSound.DEFAULT_SERVER + "random?catId=5&lngId=7"), requests.toList())
        assertNotKept()
        compose.onNodeWithTag("emotion_sound_random_preview").performClick()
        compose.waitUntil(8_000) { EmotionSoundAudio.isPlaying }
        assertNotKept()
        assertEquals(1, requests.size)
        screenshot("emotion-random-preview.png")
        compose.onNodeWithTag("emotion_sound_random_keep").performClick()
        compose.waitUntil(5_000) { kept.get() != null }
        compose.waitUntil(3_000) { !EmotionSoundAudio.isPlaying }
        assertEquals(sound, kept.get())
        assertTrue(context.emotionSoundStore().isSaved(owner, MsnEmotionSound.DEFAULT_SERVER, sound))
        assertArrayEquals(bytes, context.emotionSoundStore().bytes(owner, MsnEmotionSound.DEFAULT_SERVER, sound))
    }

    @Test fun changingTheLanguageClearsTheCandidateAndDiscardStopsPreviewWithoutSaving() {
        showDialog(); fetch(); awaitCandidate()
        assertEquals(MsnEmotionSound.DEFAULT_SERVER + "random?catId=5", requests.single())
        compose.onNodeWithTag("emotion_sound_language").performClick()
        compose.onNodeWithTag("emotion_sound_language_21").performScrollTo().performClick()
        compose.onNodeWithTag("emotion_sound_random_preview").assertDoesNotExist()
        compose.onNodeWithTag("emotion_sound_random_keep").assertIsNotEnabled()
        fetch(); awaitCandidate()
        assertEquals(MsnEmotionSound.DEFAULT_SERVER + "random?catId=5&lngId=21", requests.last())
        compose.onNodeWithTag("emotion_sound_random_preview").performClick()
        compose.waitUntil(8_000) { EmotionSoundAudio.isPlaying }
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithTag("emotion_sound_random_dialog").assertDoesNotExist()
        compose.waitUntil(3_000) { !EmotionSoundAudio.isPlaying }
        assertNotKept()
    }

    @Test fun failedDownloadCanBeRetriedAndNeverEnablesKeepOrAutoplays() {
        val attempts = AtomicInteger()
        showDialog { if (attempts.incrementAndGet() == 1) byteArrayOf(48) else bytes }
        fetch()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No sounds were found for this category and language.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("emotion_sound_random_keep").assertIsNotEnabled()
        compose.onNodeWithTag("emotion_sound_random_preview").assertDoesNotExist()
        assertNotKept()
        fetch(); awaitCandidate()
        assertFalse(EmotionSoundAudio.isPlaying)
        assertEquals(2, requests.size)
        compose.onNodeWithText("Discard").performClick()
        assertNotKept()
    }

    @Test fun closingDuringDownloadDiscardsALateResponse() {
        val release = CountDownLatch(1)
        val started = AtomicBoolean()
        val finished = AtomicBoolean()
        showDialog {
            started.set(true)
            check(release.await(10, TimeUnit.SECONDS))
            finished.set(true)
            bytes
        }
        try {
            fetch()
            compose.waitUntil(5_000) { started.get() }
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("emotion_sound_random_dialog").assertDoesNotExist()
            release.countDown()
            compose.waitUntil(5_000) { finished.get() }
            assertNotKept()
        } finally { release.countDown() }
    }
}
