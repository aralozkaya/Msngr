package org.aralozkaya.msngr

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.FileTransfer
import org.aralozkaya.msngr.data.FileTransferStatus
import org.aralozkaya.msngr.data.SharedPhoto
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class TransferSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context

    @Before fun prepare() {
        context = compose.activity
        SessionPrefs.logout()
        SessionPrefs.completeLogin(context, "transfer-settings@example.test", "test-password", true,
            ProviderOptions.ESCARGOT, ServerOverrides(), useMockServer = true)
        AccountPreferences.clear(context, SessionPrefs.preferenceAccount(context))
        AccountPreferences.reload(context)
    }

    @After fun clean() {
        SessionPrefs.disableRememberedCredentials(context)
        SessionPrefs.logout()
        AccountPreferences.reload(compose.activity)
    }

    @Test fun dedicatedPagePersistsIndependentTogglesAndPhotoCheckboxDependsOnFileAutosave() {
        compose.setContent { CompositionLocalProvider(LocalContext provides context) {
            MsngrTheme { SettingsRoute({}, {}) }
        } }
        open()
        compose.onNodeWithTag("auto_accept_transfers").assertIsOff()
        compose.onNodeWithTag("auto_save_files").assertIsOff()
        compose.onNodeWithTag("auto_save_photos").assertDoesNotExist()
        compose.onNodeWithTag("auto_save_files").performClick().assertIsOn()
        compose.onNodeWithTag("auto_accept_transfers").assertIsOff()
        compose.onNodeWithTag("auto_save_photos").assertIsOff().assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithTag("auto_accept_transfers").performClick().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        open()
        compose.onNodeWithTag("auto_save_files").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("auto_save_photos").assertDoesNotExist()
        compose.runOnIdle { assertEquals(TransferSettings(true, false, true), TransferPrefs.read(context)) }
        compose.onNodeWithTag("auto_save_files").performClick()
        compose.onNodeWithTag("auto_save_photos").assertIsOn().assertIsEnabled()
        compose.onNodeWithText("Save locations").assertIsDisplayed()
        compose.onNodeWithText("Downloads (default)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("DCIM/Msngr (default)").assertIsDisplayed()
        compose.onNodeWithText("Photo folder").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose folder").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose file folder").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose photo folder").assertIsDisplayed()
        File(compose.activity.getExternalFilesDir(null), "transfer-settings-expanded.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun customFolderChoiceIsAccountScopedAndDefaultsCanBeRestoredAtLargeText() {
        TransferPrefs.update(context) { it.copy(fileTree = "content://test/tree/custom", fileTreeName = "Received files",
            photoTree = "content://test/tree/photos", photoTreeName = "Received photos") }
        val current = SessionPrefs.preferenceAccount(context)
        assertNull(TransferPrefs.read(context, AccountIdentity.forLogin("other@example.test", useMockServer = true)).fileTree)
        assertNull(TransferPrefs.read(context, AccountIdentity.forLogin("other@example.test", useMockServer = true)).photoTree)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(density.density, 1.6f)) {
                MsngrTheme { SettingsRoute({}, {}) }
            }
        }
        open()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("transfer_file_folder"))
        compose.onNodeWithText("Received files").assertIsDisplayed()
        compose.onNodeWithText("Use Downloads instead").assertDoesNotExist()
        compose.onNodeWithText("Use DCIM/Msngr instead").assertDoesNotExist()
        compose.onNodeWithTag("transfer_default_folder").assertDoesNotExist()
        compose.onNodeWithTag("transfer_file_folder").performTouchInput { longClick() }
        compose.onNodeWithText("Restore default location").assertIsDisplayed()
        File(compose.activity.getExternalFilesDir(null), "transfer-location-menu-large-text.png").outputStream().use {
            compose.onNode(isPopup()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("transfer_default_folder").performClick()
        compose.onNodeWithText("Downloads (default)").assertIsDisplayed()
        assertNull(TransferPrefs.read(context, current).fileTree)
        assertEquals("content://test/tree/photos", TransferPrefs.read(context, current).photoTree)
        compose.onNodeWithTag("transfer_default_folder").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("transfer_photo_folder"))
        compose.onNodeWithTag("transfer_photo_folder").performTouchInput { longClick() }
        compose.onNodeWithTag("transfer_default_photo_folder").performClick()
        compose.onNodeWithText("DCIM/Msngr (default)").assertIsDisplayed()
        assertNull(TransferPrefs.read(context, current).photoTree)
        compose.onNodeWithTag("transfer_photo_folder").performTouchInput { longClick() }
        compose.onNodeWithTag("transfer_default_photo_folder").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        File(compose.activity.getExternalFilesDir(null), "transfer-settings-large-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun nativeAllowAccessPickerGrantsPersistentAccessAndFilesSaveToTheChosenFolder() = verifyNativeFolder(photo = false)

    @Test fun nativePhotoFolderPickerGrantsPersistentAccessAndOriginalsSaveToTheChosenFolder() = verifyNativeFolder(photo = true)

    private fun verifyNativeFolder(photo: Boolean) = runBlocking {
        val resolver = context.contentResolver
        val folderName = "Msngr-picker-test-${UUID.randomUUID()}"
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download/$folderName")
        val seed = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "seed.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folderName")
        })!!
        resolver.openOutputStream(seed)!!.use { it.write("seed".toByteArray()) }
        val source = File.createTempFile("custom-folder", ".txt", context.cacheDir).apply { writeText("Custom folder original") }
        if (photo) {
            val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffab3412.toInt()) }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            TransferPrefs.update(context) {
                if (photo) it.copy(photoTree = tree.toString(), photoTreeName = folderName)
                else it.copy(fileTree = tree.toString(), fileTreeName = folderName)
            }
            compose.setContent { MsngrTheme { SettingsRoute({}, {}) } }
            open()
            compose.onNodeWithTag(if (photo) "transfer_photo_folder" else "transfer_file_folder").performScrollTo().performClick()
            clickNative("Use this folder")
            clickNative("Allow")
            compose.waitUntil(10_000) { resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } }
            TransferPrefs.reload(context)
            val settings = TransferPrefs.read(context)
            assertEquals(tree.toString(), if (photo) settings.photoTree else settings.fileTree)
            assertNull(if (photo) settings.fileTree else settings.photoTree)
            val storage = ReceivedTransferStorage(context)
            val account = SessionPrefs.preferenceAccount(context)
            val target = if (photo) {
                val metadata = MsnDisplayPicture.create("peer@example.test", byteArrayOf(1), 15)
                val received = SharedPhoto(UUID.randomUUID().toString(), "received.png", "hash", metadata, metadata, originalPath = source.absolutePath)
                storage.savePhoto(account, "custom-folder", received).getOrThrow().also {
                    assertEquals(it, ReceivedTransferStorage(context).savePhoto(account, "custom-folder", received).getOrThrow())
                }
            } else storage.saveFile(account,
                FileTransfer(UUID.randomUUID().toString(), "received.txt", source.length(), false,
                    FileTransferStatus.Complete, localPath = source.absolutePath)).getOrThrow()
            assertTrue(DocumentsContract.getDocumentId(target).startsWith("primary:Download/$folderName/"))
            assertArrayEquals(source.readBytes(), resolver.openInputStream(target)!!.use { it.readBytes() })
        } finally {
            source.delete()
            runCatching { DocumentsContract.deleteDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))) }
            runCatching { resolver.delete(seed, null, null) }
            runCatching { resolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    private fun clickNative(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(100)
        }
        automation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "transfer-native-picker.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fun labels(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
            listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { labels(node.getChild(it)) }
        fail("The native picker did not show '$text'. Visible labels: ${labels(automation.rootInActiveWindow)}")
    }

    private fun open() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("transfer_settings"))
        compose.onNodeWithTag("transfer_settings").performClick()
    }
}
