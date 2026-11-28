package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface

internal fun exifOrientationMatrix(orientation: Int): Matrix? {
    return Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            else -> return null
        }
    }
}

internal fun Bitmap.withExifOrientation(orientation: Int): Bitmap {
    val matrix = exifOrientationMatrix(orientation) ?: return this
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
