package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.FileTransfer
import org.aralozkaya.msngr.data.FileTransferStatus
import org.aralozkaya.msngr.data.SharedPhoto
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class TransferStorageInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = AccountIdentity.forLogin("transfer-storage@example.test", useMockServer = true)
    private val created = mutableListOf<Uri>()
    private val files = mutableListOf<File>()
    private lateinit var storage: ReceivedTransferStorage

    @Before fun prepare() {
        AccountPreferences.clear(context, account)
        storage = ReceivedTransferStorage(context)
    }

    @After fun clean() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        files.forEach(File::delete)
        AccountPreferences.clear(context, account)
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test fun defaultFileSaveUsesDownloadsWithoutStoragePermissionAndSurvivesRecreation() = runBlocking {
        val bytes = ByteArray(131_113) { (it * 17).toByte() }
        val source = fixture(bytes)
        val transfer = FileTransfer(UUID.randomUUID().toString(), "msngr-test.txt", bytes.size.toLong(), false,
            FileTransferStatus.Complete, localPath = source.absolutePath)
        val uri = storage.saveFile(account, transfer).getOrThrow().also(created::add)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertColumns(uri, "Download/", "text/plain")
        assertEquals(uri, ReceivedTransferStorage(context).saveFile(account, transfer).getOrThrow())
        assertEquals(uri.toString(), storage.state(account, "file:${transfer.id}").uri)
        assertNull(storage.state(AccountIdentity.forLogin("another@example.test", useMockServer = true), "file:${transfer.id}").uri)
        val collision = storage.saveFile(account, transfer.copy(id = UUID.randomUUID().toString())).getOrThrow().also(created::add)
        assertNotEquals(uri, collision)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertTrue(source.exists())
    }

    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.P)
    @Test fun legacyDownloadRetriesAfterPermissionIsGrantedAndPreservesExistingFiles() = runBlocking {
        val bytes = "Received file contents".toByteArray()
        val source = fixture(bytes)
        val id = UUID.randomUUID().toString()
        val transfer = FileTransfer(id, "msngr-$id.txt", source.length(), false,
            FileTransferStatus.Complete, localPath = source.absolutePath)
        val deniedContext = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                Manifest.permission.READ_EXTERNAL_STORAGE -> PackageManager.PERMISSION_DENIED
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> PackageManager.PERMISSION_GRANTED
                else -> super.checkPermission(permission, pid, uid)
            }
        }
        val denied = ReceivedTransferStorage(deniedContext)
        assertTrue(denied.saveFile(account, transfer).isFailure)
        assertEquals(context.getString(R.string.received_transfer_default_access_needed), denied.state(account, "file:$id").error)
        assertNull(denied.state(account, "file:$id").uri)
        assertArrayEquals(bytes, source.readBytes())

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in legacyTransferStoragePermissions) {
            val output = automation.executeShellCommand("pm grant ${context.packageName} $permission")
            val grantResult = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
            assertEquals(grantResult, PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertTrue(downloads.isDirectory || downloads.mkdirs())
        val existing = File(downloads, transfer.name)
        assertTrue(existing.createNewFile())
        files.add(existing)
        existing.writeText("Already saved")

        val uri = storage.saveFile(account, transfer).getOrThrow()
        val saved = File(requireNotNull(uri.path)).also(files::add)
        assertEquals(Uri.fromFile(File(downloads, "msngr-$id (1).txt")), uri)
        assertArrayEquals(bytes, saved.readBytes())
        assertEquals("Already saved", existing.readText())
        assertArrayEquals(bytes, source.readBytes())
        assertNull(storage.state(account, "file:$id").error)
        assertEquals(uri, ReceivedTransferStorage(context).saveFile(account, transfer).getOrThrow())
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    @Test fun savedPhotoIsTheUnchangedOriginalInDcimMsngrAndAppearsInImages() = runBlocking {
        val source = File.createTempFile("msngr-photo", ".png", context.cacheDir).also(files::add)
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff1234aa.toInt()) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val metadata = MsnDisplayPicture.create("peer@example.test", byteArrayOf(1), 15)
        val photo = SharedPhoto(UUID.randomUUID().toString(), "msngr-photo.png", "hash", metadata, metadata,
            localPath = "/unrelated-preview.jpg", originalPath = source.absolutePath)
        val uri = storage.savePhoto(account, "share", photo).getOrThrow().also(created::add)
        assertTrue(uri.toString().contains("/images/media/"))
        assertColumns(uri, "DCIM/Msngr/", "image/png")
        assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(uri, storage.savePhoto(account, "share", photo).getOrThrow())
        assertTrue(source.exists())
    }

    @Test fun revokedCustomFolderAndInvalidPhotoFailWithoutDeletingThePrivateOriginal() = runBlocking {
        val source = fixture("Keep the original".toByteArray())
        AccountPreferences.forAccount(context, "msngr_transfers", account).edit()
            .putString("file_tree", "content://com.android.externalstorage.documents/tree/primary%3AMissing").commit()
        val transfer = FileTransfer("revoked", "document.txt", source.length(), false,
            FileTransferStatus.Complete, localPath = source.absolutePath)
        assertTrue(storage.saveFile(account, transfer).isFailure)
        assertEquals(context.getString(R.string.received_transfer_file_folder_lost), storage.state(account, "file:revoked").error)
        assertNull(storage.state(account, "file:revoked").uri)
        val metadata = MsnDisplayPicture.create("peer@example.test", byteArrayOf(1), 15)
        assertTrue(storage.savePhoto(account, "share", SharedPhoto("invalid", "fake.png", "hash", metadata, metadata,
            originalPath = source.absolutePath)).isFailure)
        assertEquals("Keep the original", source.readText())
    }

    @Test fun photosAndCustomFoldersUseTheSystemPickerContractsWhileFilesUseDocuments() {
        val photo = ActivityResultContracts.PickMultipleVisualMedia(32).createIntent(context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) assertEquals(MediaStore.ACTION_PICK_IMAGES, photo.action)
        assertEquals("image/*", photo.type)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, ActivityResultContracts.OpenDocumentTree().createIntent(context, null).action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("*/*")).action)
    }

    private fun fixture(bytes: ByteArray) = File.createTempFile("msngr-transfer", ".bin", context.cacheDir)
        .also { it.writeBytes(bytes); files += it }

    private fun assertColumns(uri: Uri, path: String, mime: String) {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(path, it.getString(0))
            assertEquals(mime, it.getString(1))
            assertEquals(0, it.getInt(2))
        }
    }
}
