@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("DEPRECATION")

package org.aralozkaya.msngr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.DefaultDisplayPictureDialog
import org.aralozkaya.msngr.pages.DisplayPictureOptionsDialog
import org.aralozkaya.msngr.pages.ProfilePage
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DisplayPicturePickerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity

    @Test fun everyBundledOriginalDecodesAndAnimatedDefaultsPreserveFramesBytesRestoreAndExport() = runBlocking {
        val pictures = context.loadDefaultDisplayPictures()
        assertEquals(45, pictures.size)
        assertEquals(45, pictures.map { it.id }.toSet().size)
        assertEquals(mapOf("6.1–8.5" to 11, "2009" to 30, "2011–2012" to 4), pictures.groupingBy { it.version }.eachCount())
        assertEquals(9, pictures.count { it.animated })
        val manifest = context.assets.open("display_pictures/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONArray("pictures")
        pictures.forEachIndexed { index, picture ->
            val original = context.readProfilePictureBytes(picture.uri)
            val digest = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
            assertEquals(picture.name, manifest.getJSONObject(index).getString("sha256"), digest)
            val selected = context.inspectDisplayPicture(picture.uri)
            assertFalse(picture.name, selected.requiresResize)
            assertArrayEquals(picture.name, original, selected.bytes)
            assertNotNull(picture.name, context.loadAvatarBitmap(picture.uri))
            assertArrayEquals(picture.name, original, context.loadDisplayPictureBytes(selected.dataUri))
            if (picture.animated) {
                val gif = requireNotNull(selected.animation)
                assertTrue(picture.name, gif.frameDelaysMillis.size > 1)
                assertArrayEquals(original, context.loadAvatarGifBytes(picture.uri))
                val movie = requireNotNull(Movie.decodeByteArray(original, 0, original.size))
                val bitmap = Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(gif.width * gif.height)
                val hashes = mutableSetOf<Int>()
                var time = 0
                gif.frameDelaysMillis.forEach { delay ->
                    bitmap.eraseColor(0)
                    movie.setTime(time)
                    movie.draw(Canvas(bitmap), 0f, 0f)
                    bitmap.getPixels(pixels, 0, gif.width, 0, 0, gif.width, gif.height)
                    hashes += pixels.contentHashCode()
                    time += delay
                }
                bitmap.recycle()
                assertTrue("${picture.name} must animate", hashes.size > 1)
                val export = File(context.cacheDir, "default-export.gif")
                try {
                    assertTrue(context.saveContactPicture(selected.dataUri, Uri.fromFile(export)))
                    assertArrayEquals(original, export.readBytes())
                } finally { export.delete() }
            }
        }
    }

    @Test fun optionsOfferTheThreeOrderedSourcesAndKeepViewRemoveAndCancel() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MsngrTheme {
                DisplayPictureOptionsDialog("You", DEFAULT_DISPLAY_PICTURE_URI_PREFIX + "beach_chairs.png",
                    { actions += "camera" }, { actions += "gallery" }, { actions += "defaults" },
                    { actions += "view" }, { actions += "remove" }, { actions += "cancel" })
            }
        }
        compose.onNodeWithText("Take a photo").assertExists()
        compose.onNodeWithText("Select a photo").assertExists()
        compose.onNodeWithText("Use a default photo").assertExists()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("You display picture").assertExists() }.isSuccess
        }
        screenshot("display-picture-options")
        compose.onNodeWithTag("picture-source-0").performClick()
        compose.onNodeWithTag("picture-source-1").performClick()
        compose.onNodeWithTag("picture-source-2").performClick()
        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("camera", "gallery", "defaults", "view", "remove", "cancel"), actions)
    }

    @Test fun defaultsFilterByVersionPreviewWithoutCommittingAndConfirmAnimatedPicture() {
        var selected: DefaultDisplayPicture? = null
        compose.setContent { MsngrTheme { DefaultDisplayPictureDialog({ selected = it }, {}) } }
        compose.onNodeWithTag("use-default-picture").assertIsNotEnabled()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("picture-version-2011–2012").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("picture-version-2011–2012").performClick()
        compose.onNodeWithTag("default-picture-animatedusertile3").performClick().assertIsSelected()
        assertNull(selected)
        compose.onNodeWithTag("default-picture-beach_chairs").assertDoesNotExist()
        screenshot("default-display-pictures")
        compose.onNodeWithTag("use-default-picture").assertIsEnabled().performClick()
        assertEquals("Fish Tank", selected?.name)
        assertTrue(selected?.animated == true)
    }

    @Test fun defaultSelectionOnlyChangesTheDraftAndRemoveRestoresIt() {
        showProfile()
        compose.onNodeWithContentDescription("Change display picture").performClick()
        compose.onNodeWithText("Use a default photo").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("default-picture-beach_chairs").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("default-picture-beach_chairs").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save profile").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Change display picture").performScrollTo().performClick()
        compose.onNodeWithText("Use a default photo").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("default-picture-beach_chairs").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("default-picture-beach_chairs").performClick()
        compose.onNodeWithTag("use-default-picture").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Save profile").performScrollTo().assertIsEnabled() }.isSuccess
        }
        assertNull(context.appContainer.messengerRepository.profile.value.avatarUri)
        compose.onNodeWithContentDescription("Change display picture").performScrollTo().performClick()
        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("Display picture").assertExists()
        compose.onNodeWithContentDescription("Close picture").performClick()
        compose.onNodeWithContentDescription("Change display picture").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Save profile").performScrollTo().assertIsNotEnabled()
    }

    @Test fun androidPhotoPickerIncludesGifsAndCancellationLeavesDraftAlone() {
        val registry = PickerRegistry()
        showProfile(registry)
        compose.onNodeWithContentDescription("Change display picture").performClick()
        compose.onNodeWithText("Select a photo").performClick()
        assertEquals("image/*", registry.intent?.type)
        assertTrue(registry.intent?.action in setOf(MediaStore.ACTION_PICK_IMAGES, Intent.ACTION_OPEN_DOCUMENT,
            "androidx.activity.result.contract.action.PICK_IMAGES", "com.google.android.gms.provider.action.PICK_IMAGES"))
        compose.runOnIdle { registry.dispatchResult(registry.requestCode, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithText("Save profile").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Change display picture").performScrollTo().performClick()
        compose.onNodeWithText("Select a photo").performClick()
        val gif = File(context.cacheDir, "picker.gif").apply {
            writeBytes(context.readProfilePictureBytes(DEFAULT_DISPLAY_PICTURE_URI_PREFIX + "soccer_windows_live_messenger.gif"))
        }
        try {
            compose.runOnIdle { registry.dispatchResult(registry.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(gif))) }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Save profile").performScrollTo().assertIsEnabled() }.isSuccess
            }
            assertNull(context.appContainer.messengerRepository.profile.value.avatarUri)
        } finally { gif.delete() }
    }

    @Test fun cameraUsesFullResolutionTemporaryUriAndCleansUpCancellationAndSuccess() {
        val registry = PickerRegistry()
        showProfile(registry)
        fun openCamera(): Uri {
            compose.onNodeWithContentDescription("Change display picture").performScrollTo().performClick()
            compose.onNodeWithText("Take a photo").performClick()
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, registry.intent?.action)
            return requireNotNull(registry.intent?.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)).also {
                assertEquals("${context.packageName}.display-pictures", it.authority)
            }
        }
        val cancelled = openCamera()
        compose.runOnIdle { registry.dispatchResult(registry.requestCode, Activity.RESULT_CANCELED, null) }
        assertTrue(runCatching { context.contentResolver.openInputStream(cancelled)?.close() }.isFailure)
        compose.onNodeWithText("Save profile").performScrollTo().assertIsNotEnabled()
        val captured = openCamera()
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff2266aa.toInt()) }
        context.contentResolver.openOutputStream(captured)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        context.contentResolver.openFileDescriptor(captured, "rw")!!.use {
            ExifInterface(it.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
        }
        runBlocking {
            val prepared = context.inspectCapturedDisplayPicture(captured.toString())
            assertEquals(48, prepared.width)
            assertEquals(64, prepared.height)
        }
        compose.runOnIdle { registry.dispatchResult(registry.requestCode, Activity.RESULT_OK, null) }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Save profile").performScrollTo().assertIsEnabled() }.isSuccess
        }
        assertTrue(runCatching { context.contentResolver.openInputStream(captured)?.close() }.isFailure)
    }

    private fun showProfile(registry: PickerRegistry = PickerRegistry()) {
        context.appContainer.repositoryFor(useMockServer = true)
            .setLocalProfile(UserProfileSnapshot("You", "you@example.com", Presence.Online))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                MsngrTheme { ProfilePage(PaddingValues(), TopAppBarDefaults.exitUntilCollapsedScrollBehavior(), {}) }
            }
        }
    }

    private inner class PickerRegistry : ActivityResultRegistry() {
        var intent: Intent? = null
        var requestCode = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.requestCode = requestCode
            intent = contract.createIntent(context, input)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {
            val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: requireNotNull(context.getExternalFilesDir(null))
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
