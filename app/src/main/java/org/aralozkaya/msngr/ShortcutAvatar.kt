package org.aralozkaya.msngr

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import org.aralozkaya.msngr.ui.theme.msngrColorScheme

internal fun avatarInitial(name: String): String =
    name.trim().take(1).uppercase().ifBlank { "?" }

internal fun Context.shortcutAvatarBitmap(name: String, photo: Bitmap?): Bitmap {
    val size = 128
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (photo != null) {
        val side = minOf(photo.width, photo.height)
        val source = Rect(
            (photo.width - side) / 2,
            (photo.height - side) / 2,
            (photo.width + side) / 2,
            (photo.height + side) / 2,
        )
        canvas.drawBitmap(photo, source, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
    } else {
        val appearance = AppearancePrefs.state(this).value
        val isDark = when (appearance.themeMode) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.System -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val colors = msngrColorScheme(this, appearance, isDark)
        canvas.drawColor(colors.primaryContainer.toArgb())
        paint.color = colors.onPrimaryContainer.toArgb()
        paint.typeface = Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 22f / 84f
        val initial = avatarInitial(name)
        val metrics = paint.fontMetrics
        canvas.drawText(initial, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    }
    return bitmap
}
