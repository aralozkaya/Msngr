package org.aralozkaya.msngr

/** Segoe UI faces exposed by the original Messenger's Font style list. */
enum class NativeFontFace(val wireName: String, val weight: Int) {
    Regular("Segoe UI", 400),
    Light("Segoe UI Light", 300),
    Semilight("Segoe UI Semilight", 350),
    Semibold("Segoe UI Semibold", 600),
    Black("Segoe UI Black", 900),
}

enum class NativeFontStyle(
    val label: String,
    val face: NativeFontFace,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    Light("Light", NativeFontFace.Light),
    LightItalic("Light Italic", NativeFontFace.Light, italic = true),
    Semilight("Semilight", NativeFontFace.Semilight),
    SemilightItalic("Semilight Italic", NativeFontFace.Semilight, italic = true),
    Regular("Regular", NativeFontFace.Regular),
    Italic("Italic", NativeFontFace.Regular, italic = true),
    Semibold("Semibold", NativeFontFace.Semibold),
    SemiboldItalic("Semibold Italic", NativeFontFace.Semibold, italic = true),
    Bold("Bold", NativeFontFace.Regular, bold = true),
    BoldItalic("Bold Italic", NativeFontFace.Regular, bold = true, italic = true),
    Black("Black", NativeFontFace.Black, bold = true),
    BlackItalic("Black Italic", NativeFontFace.Black, bold = true, italic = true),
    ;

    val weight: Int get() = if (face == NativeFontFace.Regular && bold) 700 else face.weight
}

/** Whole-message Messenger formatting, independent of Plus! markup in the body. */
data class NativeTextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikeout: Boolean = false,
    val underline: Boolean = false,
    val colorRgb: Int = 0,
    // WLM does not send font size. The chat's current local size overrides message history sizes.
    val size: Int = DEFAULT_SIZE,
    val fontFace: NativeFontFace = NativeFontFace.Regular,
) {
    init {
        require(colorRgb in 0..0xFFFFFF)
        require(size in MIN_SIZE..MAX_SIZE)
    }

    val fontStyle: NativeFontStyle
        get() = NativeFontStyle.entries.first { it.face == fontFace && it.italic == italic &&
            (fontFace != NativeFontFace.Regular || it.bold == bold) }

    val fontWeight: Int get() = if (fontFace == NativeFontFace.Regular && bold) 700 else fontFace.weight

    fun withFontStyle(style: NativeFontStyle): NativeTextStyle =
        copy(fontFace = style.face, bold = style.bold, italic = style.italic)

    fun toFormatHeader(): String {
        val effects = buildString {
            if (bold) append('B')
            if (italic) append('I')
            if (strikeout) append('S')
            if (underline) append('U')
        }
        val bgr = ((colorRgb and 0xFF) shl 16) or (colorRgb and 0xFF00) or (colorRgb ushr 16)
        return "FN=${fontFace.wireName.replace(" ", "%20")}; EF=$effects; CO=${bgr.toString(16)}; CS=0; PF=22"
    }

    companion object {
        const val DEFAULT_SIZE = 10
        const val MIN_SIZE = 8
        const val MAX_SIZE = 72

        fun fromFormatHeader(header: String?): NativeTextStyle {
            val fields = header.orEmpty().split(';').mapNotNull {
                val separator = it.indexOf('=')
                if (separator < 0) null else it.substring(0, separator).trim().uppercase(java.util.Locale.ROOT) to
                    it.substring(separator + 1).trim()
            }.toMap()
            val effects = fields["EF"].orEmpty().uppercase(java.util.Locale.ROOT)
            val bgr = fields["CO"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{1,6}")) }
                ?.toIntOrNull(16) ?: 0
            val rgb = ((bgr and 0xFF) shl 16) or (bgr and 0xFF00) or (bgr ushr 16)
            val faceName = runCatching { java.net.URLDecoder.decode(fields["FN"].orEmpty(), "UTF-8") }.getOrDefault("")
            val face = NativeFontFace.entries.firstOrNull { it.wireName.equals(faceName, ignoreCase = true) }
                ?: NativeFontFace.Regular
            return NativeTextStyle('B' in effects, 'I' in effects, 'S' in effects, 'U' in effects, rgb, fontFace = face)
        }
    }
}
