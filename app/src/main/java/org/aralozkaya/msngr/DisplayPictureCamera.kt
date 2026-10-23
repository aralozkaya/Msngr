package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.DisplayPictureFormat
import java.io.ByteArrayOutputStream
import java.io.File

/** A full-resolution photo in a narrowly scoped, temporary FileProvider directory. */
internal fun Context.createDisplayPictureCaptureUri(): Uri {
    val directory = File(cacheDir, "display_picture_captures").apply { mkdirs() }
    // Recover abandoned captures after a killed process without deleting an in-flight photo.
    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
        ?.forEach { it.delete() }
    val photo = File.createTempFile("capture_", ".jpg", directory)
    return FileProvider.getUriForFile(this, "$packageName.display-pictures", photo)
}

internal fun Context.deleteDisplayPictureCapture(uri: Uri) {
    if (uri.scheme == "content" && uri.authority == "$packageName.display-pictures") {
        runCatching { contentResolver.delete(uri, null, null) }
    }
}

/** Camera apps commonly store orientation in EXIF; bake it into the uploaded pixels. */
internal suspend fun Context.inspectCapturedDisplayPicture(uri: String): DisplayPictureSelection = withContext(Dispatchers.IO) {
    val selected = inspectDisplayPicture(uri)
    if (selected.format != DisplayPictureFormat.JPEG) return@withContext selected
    val orientation = selected.bytes.inputStream().use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            else -> return@withContext selected
        }
    }
    val source = requireNotNull(BitmapFactory.decodeByteArray(selected.bytes, 0, selected.bytes.size))
    var rotated: Bitmap? = null
    try {
        rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val bytes = ByteArrayOutputStream().use { output ->
            check(rotated.compress(Bitmap.CompressFormat.JPEG, 100, output))
            output.toByteArray()
        }
        DisplayPictureSelection(bytes, DisplayPictureFormat.JPEG, rotated.width, rotated.height)
    } finally {
        if (rotated !== source) rotated?.recycle()
        source.recycle()
    }
}
