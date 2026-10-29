package org.aralozkaya.msngr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.graphics.fonts.FontStyle as AndroidFontStyle

/** Check glyph pixels: Typeface.isItalic alone can be true while the glyphs remain upright. */
class NativeTextRenderingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val style = mutableStateOf(NativeTextStyle())

    @Test fun composerRendersDistinctItalicGlyphsAtEveryNativeWeight() {
        compose.setContent {
            MsngrTheme { MsnEmoticonEditor("Hamburgefontsiv Türkçe", {}, false, false, nativeStyle = style.value) }
        }
        onView(withContentDescription("Message input")).check { view, error ->
            if (error != null) throw error
            (view as android.widget.EditText).typeface = Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)
        }
        var upright: Bitmap? = null
        for (option in NativeFontStyle.entries) {
            compose.runOnIdle { style.value = NativeTextStyle().withFontStyle(option) }
            onView(withContentDescription("Message input")).check { view, error ->
                if (error != null) throw error
                val editor = view as android.widget.EditText
                val paint = TextPaint(editor.paint).apply { textSize = 64f; color = android.graphics.Color.BLACK }
                editor.text.getSpans(0, 1, CharacterStyle::class.java).forEach { it.updateDrawState(paint) }
                val bitmap = Bitmap.createBitmap(1200, 160, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawText(editor.text.toString(), 20f, 100f, paint)
                if (option.italic) assertFalse("${option.label} renders upright", bitmap.sameAs(upright))
                else upright = bitmap
            }
        }
    }

    @Test fun inheritedTypefaceCannotSuppressTheNativeFontWeightOrItalics() {
        compose.setContent {
            MsngrTheme {
                Column {
                    MsnEmoticonText("Hamburgefontsiv Türkçe", false, nativeStyle = style.value,
                        style = TextStyle(fontFamily = FontFamily(Typeface.create("sans-serif-smallcaps", Typeface.NORMAL))),
                        modifier = Modifier.testTag("styled"))
                }
            }
        }
        var upright: Bitmap? = null
        for (option in NativeFontStyle.entries) {
            compose.runOnIdle { style.value = NativeTextStyle().withFontStyle(option) }
            compose.onNodeWithTag("styled").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                check(action(results))
                val bitmap = renderTextGlyphs(results.single())
                if (option.italic) assertFalse("${option.label} renders upright", bitmap.sameAs(upright))
                else upright = bitmap
            }
        }
    }

    @Test fun incomingAndOutgoingBubblesRenderEveryNativeFontStyle() {
        val author = mutableStateOf(MessageAuthor.Contact)
        compose.setContent {
            MsngrTheme {
                MessageBubble(ChatMessage(1, "peer", author.value, "Hamburgefontsiv Türkçe", 0,
                    nativeStyle = style.value), false)
            }
        }
        for (direction in listOf(MessageAuthor.Contact, MessageAuthor.Me)) {
            var upright: Bitmap? = null
            val weights = mutableListOf<Bitmap>()
            for (option in NativeFontStyle.entries) {
                compose.runOnIdle {
                    author.value = direction
                    style.value = NativeTextStyle().withFontStyle(option)
                }
                compose.onNode(hasText("Hamburgefontsiv Türkçe") and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                        val results = mutableListOf<TextLayoutResult>()
                        check(action(results))
                        val bitmap = renderTextGlyphs(results.single())
                        if (option.italic) assertFalse("$direction ${option.label} renders upright", bitmap.sameAs(upright))
                        else {
                            weights.forEach { assertFalse("$direction ${option.label} loses its weight", bitmap.sameAs(it)) }
                            weights += bitmap
                            upright = bitmap
                        }
                    }
            }
        }
    }

    @Test fun plusItalicsPreserveTheSurroundingTypographyWeight() {
        compose.setContent {
            MsngrTheme {
                MessengerPlusText("[i]Italic label[/i]", messengerPlusEnabled = true,
                    style = TextStyle(fontWeight = FontWeight.Medium))
            }
        }
        compose.onNodeWithText("Italic label").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            check(action(results))
            val span = results.single().layoutInput.text.spanStyles.single().item
            assertEquals(FontWeight.Medium, span.fontWeight)
            assertRenderedItalic(results.single(), 500, "Italic label")
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun aFontReportingItalicWithUprightGlyphsGetsSynthesisInBothRenderers() {
        // Keep the normal glyphs/variation axes but declare the face italic, like an OEM
        // font that advertises an italic style without providing italic outlines.
        val font = TextRunShaper.shapeTextRun("HIl", 0, 3, 0, 3, 0f, 0f, false,
            Paint().apply { typeface = Typeface.SANS_SERIF; textSize = 48f }).getFont(0)
        fun typeface(slant: Int): Typeface {
            val face = Font.Builder(font).setSlant(slant).build()
            return Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(face).build())
                .setStyle(AndroidFontStyle(400, slant)).build()
        }
        val upright = typeface(AndroidFontStyle.FONT_SLANT_UPRIGHT)
        val brokenItalic = typeface(AndroidFontStyle.FONT_SLANT_ITALIC)
        assertTrue(brokenItalic.isItalic)
        val rendering = resolveNativeItalic(upright, brokenItalic)
        assertEquals(0.25f, rendering.italicSkew)
        val normalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = upright; textSize = 48f }
        val italicPaint = TextPaint(normalPaint).also(rendering::applyTo)
        fun pixels(paint: TextPaint): Bitmap = Bitmap.createBitmap(256, 100, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawText("H", 20f, 70f, paint)
        }
        assertFalse("EditText fallback must slant the glyphs", pixels(normalPaint).sameAs(pixels(italicPaint)))
        assertRightLeaning(pixels(italicPaint))
        // Applying again must not double the slant, and returning to normal must clear it.
        rendering.applyTo(italicPaint)
        assertEquals(-0.25f, italicPaint.textSkewX)
        NativeFontRendering(upright).applyTo(italicPaint)
        assertEquals(0f, italicPaint.textSkewX)
        compose.setContent {
            MsngrTheme {
                Column {
                    Text("Italic fallback")
                    Text("H", style = TextStyle(fontSize = 48.sp).merge(rendering.spanStyle(400, true)),
                        modifier = Modifier.testTag("fallback"))
                    Text("Upright device font")
                    Text("H", style = TextStyle(fontFamily = FontFamily(upright), fontSize = 48.sp),
                        modifier = Modifier.testTag("normal"))
                }
            }
        }
        assertFalse("Compose fallback must slant the glyphs", layoutPixels("fallback").sameAs(layoutPixels("normal")))
        assertRightLeaning(layoutPixels("fallback"))
        if (InstrumentationRegistry.getArguments().getString("styleScreenshots") == "true") {
            val file = java.io.File(compose.activity.getExternalFilesDir(null), "native-italic-fallback.png")
            file.outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun layoutPixels(tag: String): Bitmap {
        lateinit var bitmap: Bitmap
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            check(action(results))
            bitmap = renderTextGlyphs(results.single())
        }
        return bitmap
    }

    private fun assertRightLeaning(bitmap: Bitmap) {
        fun centerOfInk(y: Int): Float? {
            var ink = 0L
            var position = 0L
            for (x in 0 until bitmap.width) {
                val alpha = android.graphics.Color.alpha(bitmap.getPixel(x, y))
                ink += alpha
                position += x * alpha
            }
            return if (ink == 0L) null else position.toFloat() / ink
        }
        val rows = (0 until bitmap.height).mapNotNull { y -> centerOfInk(y)?.let { y to it } }
        val top = rows[2].second
        val bottom = rows[rows.lastIndex - 2].second
        assertTrue("Italic glyph must lean right ($top <= $bottom)", top > bottom + 1f)
    }
}

internal fun renderTextGlyphs(result: TextLayoutResult): Bitmap {
    val image = ImageBitmap(1200, 240)
    result.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(image), Color.Black)
    return image.asAndroidBitmap()
}

internal fun assertRenderedItalic(result: TextLayoutResult, weight: Int, label: String) {
    val input = result.layoutInput
    val upright = TextMeasurer(
        defaultFontFamilyResolver = input.fontFamilyResolver,
        defaultLocaleList = input.defaultLocaleList,
        defaultDensity = input.density,
        defaultLayoutDirection = input.layoutDirection,
    ).measure(
        text = AnnotatedString(input.text.text),
        style = input.style.merge(nativeFontRendering(weight, false).spanStyle(weight, false)),
        constraints = input.constraints,
    )
    assertFalse("$label preview renders upright", renderTextGlyphs(result).sameAs(renderTextGlyphs(upright)))
}
