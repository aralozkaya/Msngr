package org.aralozkaya.msngr

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.FileTransferStatus
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.PhotoShareStatus
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.notifications.NotificationChannels
import org.aralozkaya.msngr.notifications.SharingNotificationController
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.MsnWinkPackage
import org.aralozkaya.msngr.protocol.WinkReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
@RunWith(AndroidJUnit4::class)
class ExternalShareInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val createdFiles = mutableListOf<File>()

    @Test
    fun legacyFileUrisAreLimitedToSharedStorage() {
        val external = requireNotNull(context.getExternalFilesDir(null))
        assertFalse(context.isSafeExternalFileUri(Uri.fromFile(File(external, "private.txt"))))
        assertFalse(context.isSafeExternalFileUri(Uri.fromFile(File(context.filesDir, "private.txt"))))
        assertFalse(context.isSafeExternalFileUri(Uri.fromFile(File(context.createDeviceProtectedStorageContext().filesDir, "private.txt"))))
        @Suppress("DEPRECATION")
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        assertTrue(context.isSafeExternalFileUri(Uri.fromFile(File(downloads, "document.txt"))))
        assertTrue(context.isSafeExternalFileUri(Uri.parse("content://documents.example/book")))
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationChannels.createAll(context)
        dismissSharingNotifications()
        signInMockAppSession()
    }

    @After
    fun tearDown() {
        dismissSharingNotifications()
        createdFiles.forEach { it.delete() }
        createdFiles.clear()
        runCatching { context.appContainer.currentRepository?.logout() }
        SessionPrefs.logout()
    }

    @Test
    fun manifestRegistersSeparateFilesAndPhotosShareTargetsAndMcoOpenHandler() {
        val pm = context.packageManager

        val pdfSend = Intent(Intent.ACTION_SEND).apply { type = "application/pdf" }
        val pdfMatches = pm.queryIntentActivities(pdfSend, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(pdfMatches.any { it.activityInfo.name == ShareActivity::class.java.name })
        assertFalse(pdfMatches.any { it.activityInfo.name == SharePhotosActivity::class.java.name })

        val mcoSend = Intent(Intent.ACTION_SEND).apply { type = "application/x-msn-wink" }
        val mcoSendMatches = pm.queryIntentActivities(mcoSend, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(mcoSendMatches.any { it.activityInfo.name == ShareActivity::class.java.name })
        assertFalse(mcoSendMatches.any { it.activityInfo.name == SharePhotosActivity::class.java.name })

        val singleImage = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        val singleImageMatches = pm.queryIntentActivities(singleImage, PackageManager.MATCH_DEFAULT_ONLY)
        val filesResolve = singleImageMatches.firstOrNull { it.activityInfo.name == ShareActivity::class.java.name }
        val photosResolve = singleImageMatches.firstOrNull { it.activityInfo.name == SharePhotosActivity::class.java.name }
        assertNotNull("Expected ShareActivity to handle ACTION_SEND image/png", filesResolve)
        assertNotNull("Expected SharePhotosActivity to handle ACTION_SEND image/png", photosResolve)
        assertEquals(context.getString(R.string.share_option_files_title), filesResolve!!.loadLabel(pm).toString())
        assertEquals(context.getString(R.string.share_option_photos_title), photosResolve!!.loadLabel(pm).toString())

        val multiImage = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" }
        val multiMatches = pm.queryIntentActivities(multiImage, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(multiMatches.any { it.activityInfo.name == SharePhotosActivity::class.java.name })
        assertFalse(multiMatches.any { it.activityInfo.name == ShareActivity::class.java.name })

        val externalStorageMco = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fpunch.mco"),
                "application/octet-stream",
            )
        }
        assertTrue(
            pm.queryIntentActivities(externalStorageMco, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == WinkImportActivity::class.java.name },
        )

        val downloadsMco = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.providers.downloads.documents/document/msf%3A123"),
                "application/octet-stream",
            )
        }
        assertTrue(
            pm.queryIntentActivities(downloadsMco, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == WinkImportActivity::class.java.name },
        )

        val fileUriMultiDotMco = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("file:///storage/emulated/0/Download/my.custom.punch.mco")
        }
        assertTrue(
            pm.queryIntentActivities(fileUriMultiDotMco, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == WinkImportActivity::class.java.name },
        )

        val pdfView = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.example.files/doc.pdf"), "application/pdf")
        }
        assertFalse(
            pm.queryIntentActivities(pdfView, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == WinkImportActivity::class.java.name },
        )
    }

    @Test
    fun animatedUploadDrawableHasMultipleRepeatingFrames() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_notification_sharing_upload)
        assertTrue("Expected AnimationDrawable for animated upload icon", drawable is AnimationDrawable)
        val animation = drawable as AnimationDrawable
        assertEquals(6, animation.numberOfFrames)
        assertFalse(animation.isOneShot)
    }

    @Test
    fun singleFileShareValidatesRecipientsSendsOptionalMessageAndStartsTransfer(): Unit = runBlocking {
        val uri = createSharedDocumentUri("notes.txt", ByteArray(2_048) { (it % 97).toByte() })
        val repository = context.appContainer.messengerRepository
        repository.setPresence(Presence.Offline)

        val shareIntent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<ShareActivity>(shareIntent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_go_online_button").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_recipients_list").performScrollToNode(hasTestTag("share_recipient_selin"))
            compose.onNodeWithTag("share_recipient_selin").assertIsNotEnabled()
            compose.onNodeWithTag("share_go_online_button").performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_go_online_button").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("share_recipients_list").performScrollToNode(hasTestTag("share_recipient_can"))
            compose.onNodeWithTag("share_recipient_can").assertIsNotEnabled()
            compose.onNodeWithTag("share_recipients_list").performScrollToNode(hasTestTag("share_recipient_selin"))
            val recipient = compose.onNodeWithTag("share_recipient_selin").assertIsEnabled()
            recipient.performSemanticsAction(SemanticsActions.OnClick)
            recipient.assertIsSelected()

            compose.onNodeWithTag("share_optional_message_input").assertIsDisplayed()
            compose.onNodeWithTag("share_optional_message_input").performTextInput("Here is the document!")
            // The IME moves the footer while the note field is focused.
            compose.onNodeWithTag("share_send_button").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

            compose.waitUntil(8_000) {
                repository.liveTransferMessages.value["selin"].orEmpty().any {
                    it.fileTransfer?.name == "notes.txt"
                } &&
                    compose.onAllNodesWithText("Here is the document!").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Here is the document!").assertIsDisplayed()
            compose.onNodeWithText("notes.txt").assertIsDisplayed()
            return@use
        }
    }

    @Test
    fun singlePictureOpensDirectlyInChosenFilesOrPhotosShareTarget() {
        val uri = createSharedPngUri("snapshot.png", Color.BLUE)

        val filesIntent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<ShareActivity>(filesIntent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_recipient_selin").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_optional_message_input").assertIsDisplayed()
        }

        val photosIntent = Intent(context, SharePhotosActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<SharePhotosActivity>(photosIntent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_recipient_selin").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_optional_message_input").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.share_send_photos_action)).assertIsDisplayed()
        }
    }

    @Test
    fun mcoShareViaShareMenuTreatsItAsRegularFileWithoutImportPrompt() {
        val mcoUri = createSharedMcoUri("punch.mco")
        val repository = context.appContainer.messengerRepository
        val shareIntent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/x-msn-wink"
            putExtra(Intent.EXTRA_STREAM, mcoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<ShareActivity>(shareIntent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_optional_message_input").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("wink_import_preview_dialog").assertDoesNotExist()
            compose.onNodeWithTag("share_optional_message_input").performTextInput("Install this wink")
            compose.onNodeWithTag("share_recipients_list").performScrollToNode(hasTestTag("share_recipient_selin"))
            val recipient = compose.onNodeWithTag("share_recipient_selin").assertIsEnabled()
            recipient.performSemanticsAction(SemanticsActions.OnClick)
            recipient.assertIsSelected()
            // The IME moves the footer while the note field is focused.
            compose.onNodeWithTag("share_send_button").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

            compose.waitUntil(8_000) {
                repository.liveTransferMessages.value["selin"].orEmpty().any {
                    it.fileTransfer?.name == "punch.mco"
                } &&
                    compose.onAllNodesWithText("punch.mco").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("punch.mco").assertIsDisplayed()
        }
    }

    @Test
    fun multiPhotoSharePostsAnimatedNotificationReopensPhotoSheetAndStopsOnCancelAction() {
        val uris = arrayListOf(
            createSharedPngUri("one.png", Color.RED),
            createSharedPngUri("two.png", Color.GREEN),
            createSharedPngUri("three.png", Color.YELLOW),
        )
        val shareIntent = Intent(context, SharePhotosActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val repository = context.appContainer.messengerRepository
        ActivityScenario.launch<SharePhotosActivity>(shareIntent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_recipient_selin").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_optional_message_input").assertDoesNotExist()

            compose.onNodeWithTag("share_recipients_list").performScrollToNode(hasTestTag("share_recipient_selin"))
            compose.onNodeWithTag("share_recipient_selin").performClick()
            compose.onNodeWithTag("share_send_button").performClick()

            compose.waitUntil(8_000) {
                compose.onAllNodesWithTag("photo_share").fetchSemanticsNodes().isNotEmpty()
            }
            val activeShare = repository.photoShares.value["selin"]
            assertNotNull(activeShare)
            compose.waitUntil(5_000) {
                repository.photoShares.value["selin"]?.photos?.size == 3
            }

            compose.onNodeWithTag("photo_share").performTouchInput { swipeDown() }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("reopen_photo_share").fetchSemanticsNodes().isNotEmpty()
            }

            val notification = awaitSharingNotification()
            val text = notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertTrue("Expected shared/remaining byte breakdown in '$text'", text.contains("shared") && text.contains("remaining"))
            assertTrue((notification.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                assertEquals(R.drawable.ic_notification_sharing_upload, notification.notification.smallIcon.resId)
            }
            assertEquals(1000, notification.notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))

            firePendingIntentWithBal(notification.notification.contentIntent)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("photo_share").fetchSemanticsNodes().isNotEmpty()
            }

            val stopAction = notification.notification.actions.firstOrNull()
            assertNotNull(stopAction)
            stopAction!!.actionIntent.send()

            compose.waitUntil(5_000) {
                repository.photoShares.value["selin"]?.status == PhotoShareStatus.Stopped &&
                    currentSharingNotifications().isEmpty()
            }
        }
    }

    @Test
    fun fileTransferNotificationShowsSharedVsRemainingBytesAndCancelsOnAction(): Unit = runBlocking {
        val slowClient = MockMsnp18Client(
            photoCacheDirectory = File(context.cacheDir, "slow-mock-photos"),
            transferStepDelayMillis = 4_000L,
        )
        val slowRepo = MessengerRepository(client = slowClient)
        val controller = SharingNotificationController(context)
        try {
            slowRepo.signIn(
                SignInRequest(
                    email = MockServerData.ACCOUNT_EMAIL,
                    password = MockServerData.ACCOUNT_PASSWORD,
                    serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
                ),
            ).getOrThrow()
            controller.attach(slowRepo)

            val payloadFile = File.createTempFile("notify-test-", ".bin", context.cacheDir).apply {
                writeBytes(ByteArray(32_768) { (it % 251).toByte() })
            }
            createdFiles += payloadFile
            slowRepo.sendFile("selin", payloadFile, "archive.bin").getOrThrow()

            val notification = awaitSharingNotification()
            val text = notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertTrue("Expected shared & remaining size in '$text'", text.contains("shared") && text.contains("remaining"))
            val cancelAction = notification.notification.actions.single()
            assertEquals(context.getString(R.string.common_cancel), cancelAction.title.toString())

            val appRepo = context.appContainer.messengerRepository
            controller.dismissAll()
            dismissSharingNotifications()
            appRepo.sendFile("selin", payloadFile, "app-archive.bin").getOrThrow()
            val appNotification = awaitSharingNotification()
            appNotification.notification.actions.single().actionIntent.send()

            compose.waitUntil(5_000) {
                appRepo.liveTransferMessages.value["selin"].orEmpty().lastOrNull()?.fileTransfer?.status == FileTransferStatus.Cancelled &&
                    currentSharingNotifications().isEmpty()
            }
        } finally {
            controller.dismissAll()
            slowRepo.logout()
        }
    }

    @Test
    fun nonPhotoFilesShowErrorsWhenTargetingPhotosOrMultipleFilesTargetFilesShare() {
        val singleDocUri = createSharedDocumentUri("notes.txt", "alpha".toByteArray())
        val wrongPhotosTarget = Intent(context, SharePhotosActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, singleDocUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<SharePhotosActivity>(wrongPhotosTarget).use {
            val expectedError = context.getString(R.string.photo_sharing_unsupported_image)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(expectedError).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(expectedError).assertIsDisplayed()
        }

        val uris = arrayListOf(
            singleDocUri,
            createSharedDocumentUri("second.txt", "beta".toByteArray()),
        )
        val multiFilesIntent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<ShareActivity>(multiFilesIntent).use {
            val expectedError = context.getString(R.string.share_error_multiple_non_photos)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(expectedError).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(expectedError).assertIsDisplayed()
        }
    }

    @Test
    fun mcoOpenWithHandlerPreviewsAnimationAndInstallsToWinkStore() {
        val mcoUri = createSharedMcoUri("punch.mco")
        val account = checkNotNull(context.winkAccount())
        val cabBytes = instrumentation.context.assets.open("winks/screen-punch.cab").use { it.readBytes() }
        val expectedMetadata = MsnDisplayPicture.create(account, cabBytes, 8)
        context.winkStore().remove(account, expectedMetadata)

        val viewIntent = Intent(context, WinkImportActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(mcoUri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<WinkImportActivity>(viewIntent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("wink_import_preview_dialog").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_wink_preview_button").performClick()

            val closePlayerDesc = context.getString(R.string.wink_close_player)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(closePlayerDesc).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(closePlayerDesc).performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("share_wink_import_confirm_button").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share_wink_import_confirm_button").performClick()

            compose.waitUntil(5_000) {
                context.winkStore().catalog(account).any { it.name.equals("Screen Punch", ignoreCase = true) }
            }
            assertTrue(context.winkStore().catalog(account).any { it.name.equals("Screen Punch", ignoreCase = true) })
        }
    }

    private fun signInMockAppSession() {
        runCatching { context.appContainer.currentRepository?.logout() }
        SessionPrefs.logout()
        val repository = context.appContainer.repositoryFor(useMockServer = true)
        runBlocking {
            repository.signIn(
                SignInRequest(
                    email = MockServerData.ACCOUNT_EMAIL,
                    password = MockServerData.ACCOUNT_PASSWORD,
                    serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
                ),
            ).getOrThrow()
        }
        SessionPrefs.completeLogin(
            context = context,
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            savePassword = false,
            provider = ProviderOptions.ESCARGOT,
            customServers = ServerOverrides(),
            useMockServer = true,
        )
    }

    private fun transferProviderArea(): File =
        File(context.noBackupFilesDir, "file_transfers").apply { mkdirs() }

    private fun createSharedDocumentUri(displayName: String, content: ByteArray): Uri {
        val file = File.createTempFile("ext-share-", ".bin", transferProviderArea()).apply {
            writeBytes(content)
        }
        createdFiles += file
        return FileSharingProvider.uri(context, file, displayName)
    }

    private fun createSharedPngUri(displayName: String, color: Int): Uri {
        val file = File.createTempFile("ext-photo-", ".png", transferProviderArea())
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        createdFiles += file
        return FileSharingProvider.uri(context, file, displayName)
    }

    private fun createSharedMcoUri(displayName: String): Uri {
        val cabBytes = instrumentation.context.assets.open("winks/screen-punch.cab").use { it.readBytes() }
        val metadata = MsnDisplayPicture.create("fixture", cabBytes, 8)
        val mcoBytes = MsnWinkPackage.exportMco(WinkReference(metadata, "Screen Punch"), cabBytes)
        val file = File.createTempFile("ext-wink-", ".mco", transferProviderArea()).apply {
            writeBytes(mcoBytes)
        }
        createdFiles += file
        return FileSharingProvider.uri(context, file, displayName)
    }

    private fun currentSharingNotifications(): List<StatusBarNotification> {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.activeNotifications.filter { it.notification.channelId == NotificationChannels.SHARING }
    }

    private fun awaitSharingNotification(timeoutMillis: Long = 8_000L): StatusBarNotification {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            currentSharingNotifications().firstOrNull()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for sharing notification on ${NotificationChannels.SHARING}")
    }

    private fun dismissSharingNotifications() {
        (context.applicationContext as? MsngrApplication)?.sharingNotifications?.dismissAll()
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    @Suppress("DEPRECATION")
    private fun firePendingIntentWithBal(pendingIntent: android.app.PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
            pendingIntent.send(context, 0, null, null, null, null, options)
        } else {
            pendingIntent.send()
        }
    }
}
