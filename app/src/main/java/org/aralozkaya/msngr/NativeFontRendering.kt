package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import java.util.concurrent.ConcurrentHashMap

/** Resolve once for both Compose and EditText, including fonts whose italic face is upright. */
internal class NativeFontRendering(
    val typeface: Typeface,
    val italicSkew: Float = 0f,
) {
    private val family = FontFamily(typeface)

    fun spanStyle(weight: Int, italic: Boolean) = SpanStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textGeometricTransform = TextGeometricTransform(skewX = -italicSkew),
    )

    fun applyTo(paint: TextPaint) {
        paint.typeface = typeface
        // A previous StyleSpan may have applied its own synthesis. Apply ours exactly once.
        paint.textSkewX = if (italicSkew == 0f) 0f else -italicSkew
        paint.isFakeBoldText = false
    }
}

private val nativeFontRenderings = ConcurrentHashMap<Int, NativeFontRendering>()

internal fun nativeFontRendering(weight: Int, italic: Boolean): NativeFontRendering =
    nativeFontRenderings.getOrPut(weight * 2 + if (italic) 1 else 0) {
        val upright = systemTypeface(weight, false)
        if (italic) resolveNativeItalic(upright, systemTypeface(weight, true))
        else NativeFontRendering(upright)
    }

/** Some device fonts report isItalic without changing the glyphs; test the rendered face. */
internal fun resolveNativeItalic(upright: Typeface, requested: Typeface): NativeFontRendering {
    fun glyphs(typeface: Typeface): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 80, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 48f
        }
        Canvas(bitmap).drawText("HIl", 16f, 60f, paint)
        return bitmap
    }
    val normalGlyphs = glyphs(upright)
    val italicGlyphs = glyphs(requested)
    val needsSynthesis = try {
        normalGlyphs.sameAs(italicGlyphs)
    } finally {
        normalGlyphs.recycle()
        italicGlyphs.recycle()
    }
    return if (needsSynthesis) NativeFontRendering(upright, italicSkew = 0.25f)
    else NativeFontRendering(requested)
}

private fun systemTypeface(weight: Int, italic: Boolean): Typeface {
    if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.SANS_SERIF, weight, italic)
    val family = when {
        weight <= 350 -> "sans-serif-light"
        weight == 600 -> "sans-serif-medium"
        weight >= 900 -> "sans-serif-black"
        else -> "sans-serif"
    }
    val style = when {
        weight == 700 && italic -> Typeface.BOLD_ITALIC
        weight == 700 -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return Typeface.create(family, style)
}
