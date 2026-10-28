package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.EmotionSoundHttp
import org.aralozkaya.msngr.data.EmotionSoundService
import org.aralozkaya.msngr.pages.components.KeyboardSheetScaffold
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EmotionSoundCatalogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val calls = AtomicInteger()
    private val name = "An online sound with a name that is much longer than one row"

    @Before fun selectIsolatedAccount() {
        SessionPrefs.completeLogin(context, "emotion-catalog-${UUID.randomUUID()}@example.test", "test-password",
            false, ProviderOptions.ESCARGOT, ServerOverrides(), true)
    }

    @After fun restoreTransientAccount() { SessionPrefs.logout() }

    private fun catalog(title: String = name) =
        "<h5>Sound Effects</h5><i onclick=\"playAudio('012345abcdef');\" class=\"volume\"></i> $title</th>".toByteArray()

    private fun showPicker(response: (Int) -> ByteArray) {
        val service = EmotionSoundService(MsnEmotionSound.DEFAULT_SERVER, EmotionSoundHttp { url ->
            check(url == "https://escargot.chat/sounds/")
            response(calls.incrementAndGet())
        })
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            Box(Modifier.fillMaxSize())
            EmotionSoundPicker(true, true, {}, onSend = { Result.success(Unit) }, catalogService = service)
        } } }
        compose.onNodeWithTag("emotion_sound_local").assertIsOn()
        compose.onNodeWithTag("emotion_sound_online").assertIsOff()
    }

    private fun online() = compose.onNodeWithTag("emotion_sound_online").performClick()
    private fun local() = compose.onNodeWithTag("emotion_sound_local").performClick()
    private fun assertLoading() {
        compose.onNodeWithTag("emotion_sound_online_loading").assertIsDisplayed()
        compose.onNodeWithTag("emotion_sound_empty").assertDoesNotExist()
        compose.onNodeWithTag("emotion_sound_online_retry").assertDoesNotExist()
    }
    private fun awaitLoad() = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("emotion_sound_online_loading").fetchSemanticsNodes().isEmpty()
    }
    private fun screenshot(file: String) {
        if (InstrumentationRegistry.getArguments().getString("emotionSoundScreenshots") != "true") return
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            java.io.File(context.getExternalFilesDir(null), file).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun pendingLoadShowsAnIndicatorThenResultsAndConnectedButtonsKeepOneSourceSelected() {
        val release = CountDownLatch(1)
        try {
            showPicker { check(release.await(15, TimeUnit.SECONDS)); catalog() }
            online()
            compose.waitUntil(5_000) { calls.get() == 1 }
            compose.onNodeWithTag("emotion_sound_online").assertIsOn()
            compose.onNodeWithTag("emotion_sound_local").assertIsOff()
            assertLoading()
            screenshot("emotion-online-loading.png")
            release.countDown()
            awaitLoad()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithContentDescription("Download $name").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("Preview $name").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("emotion_sound_empty").assertDoesNotExist()
            screenshot("emotion-online-connected.png")
            online() // The selected radio-style button cannot deselect itself.
            compose.onNodeWithTag("emotion_sound_online").assertIsOn()
            local(); online()
            compose.onNodeWithText(name).assertIsDisplayed()
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun emptyTextAppearsOnlyAfterTheLoadFinishesAndTheEmptyCatalogIsCached() {
        val release = CountDownLatch(1)
        try {
            showPicker { check(release.await(15, TimeUnit.SECONDS)); ByteArray(0) }
            online()
            compose.waitUntil(5_000) { calls.get() == 1 }
            assertLoading()
            release.countDown()
            awaitLoad()
            compose.onNodeWithTag("emotion_sound_empty").assertIsDisplayed()
            local(); online()
            compose.onNodeWithTag("emotion_sound_online_loading").assertDoesNotExist()
            compose.onNodeWithTag("emotion_sound_empty").assertIsDisplayed()
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun aFailedLoadOffersRetryWithoutShowingEmptyResults() {
        val release = CountDownLatch(1)
        try {
            showPicker { attempt ->
                if (attempt == 1) error("Catalog unavailable.")
                check(release.await(15, TimeUnit.SECONDS))
                catalog()
            }
            online(); awaitLoad()
            compose.onNodeWithText("Catalog unavailable.").assertExists()
            compose.onNodeWithTag("emotion_sound_empty").assertDoesNotExist()
            compose.onNodeWithTag("emotion_sound_online_retry").performScrollTo().performClick()
            compose.waitUntil(5_000) { calls.get() == 2 }
            assertLoading()
            release.countDown()
            awaitLoad()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("Catalog unavailable.").assertDoesNotExist()
        } finally { release.countDown() }
    }

    @Test fun switchingToLocalCancelsTheLoadAndItsLateResponseCannotReplaceNewResults() {
        val release = CountDownLatch(1)
        val firstFinished = AtomicBoolean()
        try {
            showPicker { attempt ->
                if (attempt == 1) {
                    check(release.await(15, TimeUnit.SECONDS))
                    firstFinished.set(true)
                    catalog("Old response")
                } else catalog()
            }
            online()
            compose.waitUntil(5_000) { calls.get() == 1 }
            assertLoading()
            local()
            compose.onNodeWithTag("emotion_sound_local").assertIsOn()
            compose.onNodeWithText("Hello!").assertIsDisplayed()
            compose.onNodeWithTag("emotion_sound_online_loading").assertDoesNotExist()
            online(); awaitLoad()
            compose.onNodeWithText(name).assertIsDisplayed()
            release.countDown()
            compose.waitUntil(5_000) { firstFinished.get() }
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("Old response").assertDoesNotExist()
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }
}
