package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.data.FileTransfer
import org.aralozkaya.msngr.data.FileTransferStatus
import org.aralozkaya.msngr.data.SharedPhoto
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        AccountPreferences.clear(context, account)
        storage = ReceivedTransferStorage(context)
    }

    @After fun clean() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        files.forEach(File::delete)
        AccountPreferences.clear(context, account)
    }

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
        assertTrue(storage.state(account, "file:revoked").error!!.contains("Folder access was lost"))
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
