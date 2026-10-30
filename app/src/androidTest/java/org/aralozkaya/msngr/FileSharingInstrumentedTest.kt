package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import org.aralozkaya.msngr.data.*
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.KeyboardSheetScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileSharingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun minimizedPhotosFloatAboveTheComposerWithoutMovingTheHeader() {
        val share = mutableStateOf<PhotoShare?>(null)
        val sent = mutableListOf<String>()
        compose.setContent { MsngrTheme {
            var draft by remember { mutableStateOf("We can keep talking") }
            ChatScreen(
                draft = draft, onDraftChange = { draft = it },
                contact = Contact("photo-fab-peer", "Photo sharing", "peer@example.test", Presence.Online),
                messages = listOf(ChatMessage(1, "photo-fab-peer", MessageAuthor.Contact,
                    "You can keep chatting while sharing photos.", System.currentTimeMillis())),
                isHistoryLoaded = true, canLoadOlderMessages = false, isTyping = false,
                onBack = {}, onSendMessage = { text, _ -> sent.add(text) }, onRetryMessage = {},
                onSendTyping = {}, onSendNudge = {}, onLoadOlderMessages = {}, photoShare = share.value,
            )
        } }
        val header = compose.onNodeWithText("Photo sharing").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { share.value = PhotoShare("floating", false, PhotoShareStatus.Active) }
        compose.onNodeWithTag("photo_share").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo_share").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reopen_photo_share").assertIsDisplayed()
        compose.onNodeWithText("View photos").assertDoesNotExist()
        assertEquals(header, compose.onNodeWithText("Photo sharing").fetchSemanticsNode().boundsInRoot)
        fun assertFloatingAboveComposer() {
            val fab = compose.onNodeWithTag("reopen_photo_share").fetchSemanticsNode().boundsInRoot
            val composer = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
            assertTrue("Photos should float above the composer", fab.bottom <= composer.top)
            assertTrue("Photos should float at the trailing edge", fab.center.x > composer.center.x)
            assertTrue("Photos should stay out of the header", fab.top > header.bottom)
            assertEquals("The photo control should be a compact FAB", fab.width, fab.height, 1f)
        }
        assertFloatingAboveComposer()
        capture("photo-share-floating-control.png")
        Espresso.onView(withContentDescription("Message input")).perform(click())
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        assertFloatingAboveComposer()
        capture("photo-share-floating-keyboard.png")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(listOf("We can keep talking"), sent) }
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { share.value = share.value!!.copy(status = PhotoShareStatus.Stopped, remoteStopped = true) }
        compose.onNodeWithTag("reopen_photo_share").performClick()
        compose.onNodeWithText("The other person stopped sharing photos.").assertIsDisplayed()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithTag("reopen_photo_share").assertDoesNotExist()
        compose.runOnIdle { share.value = share.value!!.copy(selectedId = "late-update") }
        compose.onNodeWithTag("reopen_photo_share").assertDoesNotExist()
        compose.runOnIdle { share.value = PhotoShare("new-invitation", false) }
        compose.onNodeWithText("You are invited to share photos.").assertIsDisplayed()
    }

    @Test fun composerKeepsItsBottomInsetOpaqueAfterTheKeyboardCloses() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var surfaceColor = 0
        var bottomInset = 0
        compose.setContent { MaterialTheme { KeyboardSheetScaffold {
            val density = LocalDensity.current
            val inset = WindowInsets.safeDrawing.getBottom(density)
            val surface = MaterialTheme.colorScheme.surface
            SideEffect { surfaceColor = surface.toArgb(); bottomInset = inset }
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Magenta), contentAlignment = Alignment.BottomCenter) {
                MessageComposer(
                    draft = "", onDraftChange = {}, onSendMessage = {}, onSendNudge = {},
                    emoticonSettings = EmoticonSettings(), messengerPlusEnabled = false,
                    nativeStyle = NativeTextStyle(), onNativeStyleChange = {}, onSizeNoticeRequested = { false },
                )
            }
        } } }
        fun assertOpaqueInset() {
            compose.runOnIdle { assertTrue("This check requires a navigation inset", bottomInset > 0) }
            val pixels = compose.onNodeWithTag("chat_composer").captureToImage().toPixelMap()
            for (x in listOf(2, pixels.width - 3)) {
                assertEquals("Composer background must cover the bottom inset", surfaceColor, pixels[x, pixels.height - 2].toArgb())
                assertEquals("Composer background must cover the top corners", surfaceColor, pixels[x, 2].toArgb())
            }
        }
        assertOpaqueInset()
        Espresso.onView(withContentDescription("Message input")).perform(click())
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.waitForIdle()
        assertOpaqueInset()
        capture("composer-opaque-inset.png")
    }

    @Test fun fileMessagesShowImageThumbnailsAndFallBackToFileIcons() {
        val images = carouselFixtures()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val document = File.createTempFile("attachment-", ".txt", context.cacheDir).apply { writeText("A regular document") }
        val transfer = mutableStateOf(FileTransfer("image-preview", "picture.png", images.first().length(), true,
            localPath = images.first().absolutePath))
        compose.setContent { MaterialTheme {
            Surface(Modifier.widthIn(max = 280.dp)) {
                FileTransferMessage(ChatMessage(1, "peer", MessageAuthor.Me, "", 1, fileTransfer = transfer.value))
            }
        } }
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("file_thumbnail_image-preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("file_thumbnail_image-preview").assertIsDisplayed()
            capture("file-image-thumbnail.png")
            val thumbnail = requireNotNull(decodeFileTransferThumbnail(images.first().absolutePath))
            assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 256)
            thumbnail.recycle()
            compose.runOnIdle { transfer.value = transfer.value.copy(id = "document-preview", name = "notes.txt", localPath = document.absolutePath) }
            compose.onNodeWithTag("file_icon_document-preview").assertIsDisplayed()
            capture("file-document-icon.png")
            assertNull(decodeFileTransferThumbnail(document.absolutePath))
            compose.runOnIdle { transfer.value = transfer.value.copy(id = "invalid-preview", name = "broken.png") }
            compose.onNodeWithTag("file_icon_invalid-preview").assertIsDisplayed()
        } finally { images.forEach { it.delete() }; document.delete() }
    }

    @Test fun minimizingKeepsSharingAliveWhileChattingAndReopensWithIncomingPhotos() {
        val files = carouselFixtures()
        val photos = files.mapIndexed { index, file ->
            val metadata = MsnDisplayPicture.create("peer@example.test", file.readBytes(), 15)
            SharedPhoto("minimized-$index", "photo-$index.png", "hash", metadata, metadata, file.absolutePath)
        }
        val share = mutableStateOf(PhotoShare("minimize", false, PhotoShareStatus.Active, photos.take(1), photos.first().id))
        var stopCalls = 0
        val sent = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            var draft by remember { mutableStateOf("") }
            Surface(Modifier.fillMaxSize()) {
                Column {
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.testTag("chat_draft"))
                    Button(onClick = { sent.add(draft); draft = "" }) { Text("Send message") }
                    SharingMenu("peer", enabled = true)
                    PhotoSharingHost("peer", share.value, onStop = {
                        stopCalls++
                        share.value = share.value.copy(status = PhotoShareStatus.Stopped)
                        Result.success(Unit)
                    })
                }
            }
        } }
        try {
            compose.onNodeWithTag("photo_share").performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo_share").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("photo_share").assertDoesNotExist()
            compose.onNodeWithTag("reopen_photo_share").assertIsDisplayed()
            compose.onNodeWithTag("chat_draft").performTextInput("Still chatting")
            compose.onNodeWithText("Send message").performClick()
            compose.runOnIdle { assertEquals(listOf("Still chatting"), sent); assertEquals(0, stopCalls) }
            Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription("Share photos or files").performClick()
            compose.onNodeWithTag("sharing_menu").assertIsDisplayed()
            compose.onNodeWithTag("sharing_menu_photos").assertIsDisplayed()
            compose.onNodeWithTag("sharing_menu_files").assertIsDisplayed()
            capture("sharing-menu-active-session.png")
            Espresso.pressBack()
            compose.runOnIdle { share.value = share.value.copy(photos = photos, selectedId = photos.last().id) }
            compose.onNodeWithTag("photo_share").assertDoesNotExist()
            compose.onNodeWithTag("reopen_photo_share").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sharing photos").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, stopCalls); assertEquals(PhotoShareStatus.Active, share.value.status) }
            capture("photo-share-reopened.png")
            compose.onNodeWithText("Stop sharing").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1, stopCalls); assertEquals(PhotoShareStatus.Stopped, share.value.status) }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun backScrimAndSwipeDismissalDoNotDeclineThePhotoInvitation() {
        val share = mutableStateOf(PhotoShare("invitation", false))
        var declines = 0
        compose.setContent { MaterialTheme {
            PhotoSharingHost("peer", share.value, onStop = {
                declines++; share.value = share.value.copy(status = PhotoShareStatus.Declined); Result.success(Unit)
            })
        } }
        Espresso.pressBack()
        compose.onNodeWithTag("reopen_photo_share").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithTag("reopen_photo_share").assertIsDisplayed().performClick()
        compose.onNodeWithTag("photo_share").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo_share").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(0, declines); assertEquals(PhotoShareStatus.Invited, share.value.status) }
        compose.onNodeWithTag("reopen_photo_share").performClick()
        compose.onNodeWithText("Decline").performClick()
        compose.runOnIdle { assertEquals(1, declines); assertEquals(PhotoShareStatus.Declined, share.value.status) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("photo_share").assertDoesNotExist()
        compose.onNodeWithTag("reopen_photo_share").assertDoesNotExist()
        compose.runOnIdle { share.value = PhotoShare("outgoing-invitation", true) }
        compose.onNodeWithText("Cancel").performClick()
        Espresso.pressBack()
        compose.onNodeWithTag("photo_share").assertDoesNotExist()
        compose.onNodeWithTag("reopen_photo_share").assertDoesNotExist()
    }

    @Test fun carouselSynchronizesSwipesWithoutEchoingRemoteSelectionAndRetainsAddedPhotos() {
        val files = carouselFixtures()
        val photos = files.mapIndexed { index, file ->
            val objectInfo = MsnDisplayPicture.create("peer@example.test", file.readBytes(), 15)
            SharedPhoto("photo-$index", "photo-$index.png", "hash", objectInfo, objectInfo, file.absolutePath)
        }
        val share = mutableStateOf(PhotoShare("carousel", false, PhotoShareStatus.Active, photos.take(2), "photo-0"))
        val selections = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            PhotoSharingHost("peer", share.value, onSelect = { id ->
                selections.add(id); share.value = share.value.copy(selectedId = id); Result.success(Unit)
            })
        } }
        try {
            compose.onNodeWithText("Next").assertDoesNotExist()
            compose.onNodeWithText("Previous").assertDoesNotExist()
            compose.onNodeWithTag("photo_carousel").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { share.value.selectedId == "photo-1" }
            compose.runOnIdle { assertEquals(listOf("photo-1"), selections) }

            compose.runOnIdle { share.value = share.value.copy(selectedId = "photo-0") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(listOf("photo-1"), selections) }

            compose.runOnIdle { share.value = share.value.copy(photos = photos, selectedId = "photo-2") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithTag("photo_carousel_item_photo-2").assertIsSelected()
            capture("photo-carousel-active.png")
            compose.runOnIdle { share.value = share.value.copy(status = PhotoShareStatus.Stopped, remoteStopped = true) }
            compose.waitForIdle()
            compose.onNodeWithTag("photo_carousel").performTouchInput { swipeRight() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(listOf("photo-1"), selections) }
            compose.onNodeWithText("The other person stopped sharing photos.").performScrollTo().assertIsDisplayed()
            capture("photo-carousel-stopped.png")
        } catch (failure: Throwable) {
            capture("photo-carousel-failure.png")
            throw failure
        } finally { files.forEach { it.delete() } }
    }

    @Test fun carouselSupportsRtlGesturesAndLargeText() {
        val files = carouselFixtures()
        val photos = files.take(2).mapIndexed { index, file ->
            val objectInfo = MsnDisplayPicture.create("peer@example.test", file.readBytes(), 15)
            SharedPhoto("rtl-$index", "photo-$index.png", "hash", objectInfo, objectInfo, file.absolutePath)
        }
        val share = mutableStateOf(PhotoShare("rtl-carousel", false, PhotoShareStatus.Active, photos, "rtl-0"))
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 1.5f)) {
                PhotoSharingHost("peer", share.value, onSelect = { id ->
                    share.value = share.value.copy(selectedId = id); Result.success(Unit)
                })
            }
        } }
        try {
            compose.onNodeWithTag("photo_carousel").performTouchInput { swipeRight() }
            compose.waitUntil(5_000) { share.value.selectedId == "rtl-1" }
            compose.onNodeWithText("Stop sharing").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Add photos").assertIsDisplayed()
            capture("photo-carousel-rtl-large-text.png")
        } finally { files.forEach { it.delete() } }
    }

    private fun carouselFixtures(): List<File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return listOf(Color.rgb(38, 130, 170), Color.rgb(155, 82, 130), Color.rgb(28, 130, 95)).map { color ->
            val file = File.createTempFile("carousel-", ".png", context.cacheDir)
            val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                for (x in 50..240) for (y in 50..250) setPixel(x, y, Color.rgb(245, 194, 60))
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            file
        }
    }

    @Test fun incomingOfferProvidesAcceptanceAndDeclineWithTerminalState() {
        val state = mutableStateOf(FileTransfer("offer", "image.png", 220754, false))
        var accepted = false
        compose.setContent { MaterialTheme {
            FileTransferMessage(ChatMessage(1, "peer", MessageAuthor.Contact, "", 1, fileTransfer = state.value),
                onAccept = { accepted = true; state.value = state.value.copy(status = FileTransferStatus.Transferring); Result.success(Unit) },
                onCancel = { state.value = state.value.copy(status = FileTransferStatus.Cancelled); Result.success(Unit) })
        } }
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertTrue(accepted) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("File transfer cancelled").assertIsDisplayed()
        compose.onNodeWithText("Accept").assertDoesNotExist()
    }

    @Test fun photoViewerKeepsTheSessionAndPhotosWhenThePeerStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "photo-viewer-fixture.png")
        val bitmap = Bitmap.createBitmap(480, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(48, 113, 164))
            for (x in 40..220) for (y in 40..240) setPixel(x, y, Color.rgb(225, 180, 64))
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val metadata = MsnDisplayPicture.create("peer@example.test", file.readBytes(), 15)
        val share = mutableStateOf(PhotoShare("session", false, PhotoShareStatus.Active,
            photos = listOf(SharedPhoto("one", "photo.png", "hash", metadata, metadata, localPath = file.absolutePath)), selectedId = "one"))
        compose.setContent { MaterialTheme {
            PhotoSharingHost("peer", share.value, onStop = {
                share.value = share.value.copy(status = PhotoShareStatus.Stopped, remoteStopped = true)
                Result.success(Unit)
            })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("photo.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save photo").performScrollTo().assertIsDisplayed()
        capture("photo-active.png")
        compose.onNodeWithText("Stop sharing").performScrollTo().performClick()
        compose.onNodeWithText("The other person stopped sharing photos.").performScrollTo().assertIsDisplayed()
        capture("photo-stopped.png")
        compose.onNodeWithText("photo.png").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 / 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("photo_share").assertIsDisplayed()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithTag("reopen_photo_share").assertDoesNotExist()
        file.delete()
    }

    @Test fun previewsRespectExifAndSessionIdentityWhileOriginalsRemainUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "rotated-photo.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        val bytes = source.readBytes()
        val prepared = prepareSharedPhoto(context, Uri.fromFile(source))
        try {
            val decoded = BitmapFactory.decodeByteArray(prepared.preview, 0, prepared.preview.size)
            assertEquals(40, decoded.width); assertEquals(80, decoded.height)
            val thumbnail = requireNotNull(decodeFileTransferThumbnail(source.absolutePath))
            assertEquals(40, thumbnail.width); assertEquals(80, thumbnail.height)
            thumbnail.recycle()
            val scoped = MsnpPhotoSession.scopedPreview(prepared.preview, "session-one")
            val scopedBitmap = BitmapFactory.decodeByteArray(scoped, 0, scoped.size)
            assertTrue(decoded.sameAs(scopedBitmap))
            assertFalse(scoped.contentEquals(MsnpPhotoSession.scopedPreview(prepared.preview, "session-two")))
            assertArrayEquals(bytes, File(prepared.originalPath!!).readBytes())
            decoded.recycle(); scopedBitmap.recycle()
        } finally { source.delete(); File(prepared.originalPath!!).delete() }
    }

    @Test fun completedFileProviderRejectsWritesAndPathsOutsideTransferStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val area = File(context.noBackupFilesDir, "file_transfers").apply { mkdirs() }
        val file = File.createTempFile("provider-test-", ".bin", area).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = File.createTempFile("provider-outside-", ".bin", context.cacheDir)
        try {
            val uri = FileSharingProvider.uri(context, file, "received.png")
            assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(runCatching { context.contentResolver.openOutputStream(uri)!!.close() }.isFailure)
            assertTrue(runCatching { FileSharingProvider.uri(context, outside, "outside.bin") }.isFailure)
            val escaped = Uri.Builder().scheme("content").authority(context.packageName + ".files")
                .appendPath("..").appendPath("..").appendPath("cache").appendPath(outside.name).build()
            assertTrue(runCatching { context.contentResolver.openInputStream(escaped)!!.close() }.isFailure)
        } finally { file.delete(); outside.delete() }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val area = File(instrumentation.targetContext.getExternalFilesDir(null), "file-sharing-qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(area, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
