package org.aralozkaya.msngr

import java.util.Locale

internal data class MessengerPlusPaletteColor(
    val index: Int,
    val label: String,
    val argb: Int,
) {
    val wireToken: String = index.toString().padStart(2, '0')
}

internal data class MessengerPlusColorValue(
    val argb: Int,
    val wireToken: String,
) {
    companion object {
        fun fromHex(value: String): MessengerPlusColorValue? {
            val digits = value.trim().removePrefix("#")
            if (digits.length != HEX_COLOR_DIGITS || digits.any { !it.isHexDigit() }) return null
            val rgb = digits.toLongOrNull(16) ?: return null
            return MessengerPlusColorValue(
                argb = (OPAQUE_ALPHA or rgb).toInt(),
                wireToken = "#${digits.uppercase(Locale.ROOT)}",
            )
        }
    }
}

internal data class MessengerPlusStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeout: Boolean = false,
    val foreground: MessengerPlusColorValue? = null,
    val background: MessengerPlusColorValue? = null,
)

internal data class MessengerPlusTextRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0)
        require(endExclusive >= start)
    }
}

internal data class MessengerPlusStyleSpan(
    val range: MessengerPlusTextRange,
    val style: MessengerPlusStyle,
)

internal data class MessengerPlusSourceStyleSpan(
    val range: MessengerPlusTextRange,
    val style: MessengerPlusStyle,
)

internal data class MessengerPlusParsedText(
    val text: String,
    val styleSpans: List<MessengerPlusStyleSpan>,
    val sourceStyleSpans: List<MessengerPlusSourceStyleSpan>,
    val controlRanges: List<MessengerPlusTextRange>,
    val finalStyle: MessengerPlusStyle,
    private val sourceBoundaryToTextOffset: IntArray,
    internal val glyphs: List<MessengerPlusGlyph>,
    internal val sourceStyles: List<MessengerPlusStyle> = emptyList(),
) {
    fun textOffsetForSource(offset: Int): Int =
        sourceBoundaryToTextOffset[offset.coerceIn(sourceBoundaryToTextOffset.indices)]

    fun textRangeForSource(range: MsnEmoticonRange): MsnEmoticonRange? {
        if (range.start !in sourceBoundaryToTextOffset.indices ||
            range.endExclusive !in sourceBoundaryToTextOffset.indices
        ) {
            return null
        }
        val mappedStart = sourceBoundaryToTextOffset[range.start]
        val mappedEnd = sourceBoundaryToTextOffset[range.endExclusive]
        return if (mappedEnd > mappedStart) {
            MsnEmoticonRange(mappedStart, mappedEnd)
        } else {
            null
        }
    }
}

internal data class MessengerPlusTextTransform(
    val text: String,
    val sourceBoundaryToOutputOffset: IntArray,
) {
    fun map(range: MsnEmoticonRange): MsnEmoticonRange? {
        if (range.start !in sourceBoundaryToOutputOffset.indices ||
            range.endExclusive !in sourceBoundaryToOutputOffset.indices
        ) {
            return null
        }
        val mappedStart = sourceBoundaryToOutputOffset[range.start]
        val mappedEnd = sourceBoundaryToOutputOffset[range.endExclusive]
        return if (mappedEnd > mappedStart) {
            MsnEmoticonRange(mappedStart, mappedEnd)
        } else {
            null
        }
    }
}

internal data class MessengerPlusGlyph(
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val value: String,
    val style: MessengerPlusStyle,
)

internal data class MessengerPlusStyleEdit(val text: String, val selection: MessengerPlusTextRange)

internal object MessengerPlusTextCodec {
    const val BOLD_CONTROL: Char = '\u0002'
    const val COLOR_CONTROL: Char = '\u0003'
    const val ITALIC_CONTROL: Char = '\u0005'
    const val STRIKEOUT_CONTROL: Char = '\u0006'
    const val RESET_CONTROL: Char = '\u000F'
    const val UNDERLINE_CONTROL: Char = '\u001F'

    // Messenger Plus! 5.02.712's palette, including its extended colors (16–67).
    val palette: List<MessengerPlusPaletteColor> = (
        "FFFFFF 000000 00007F 009300 FF0000 7F0000 9C009C FC7F00 " +
            "FFFF00 00FC00 009393 00FFFF 0000FC FF00FF 7F7F7F D2D2D2 " +
            "E7E6E4 CFCDD0 FFDEA4 FFAEB9 FFA8FF B4B4FC BAFBE5 C1FFA3 " +
            "FAFDA2 B6B4B7 A2A0A1 F9C152 FF6D66 FF62FF 6C6CFF 68FFC3 " +
            "8EFF67 F9FF57 858482 6E6D7B FFA01E F92611 FF20FF 202BFF " +
            "1EFFA5 60F913 FFF813 5E6464 4B494C D98812 EB0505 DE00DE " +
            "0000D3 03CC88 59D80D D4C804 333335 18171C 944E00 9B0008 " +
            "980299 01038C 01885F 389600 9A9E15 473400 4D0000 5F0162 " +
            "000047 06502F 1C5300 544D05"
        ).split(' ').mapIndexed { index, rgb ->
        val names = listOf(
            "White", "Black", "Dark blue", "Dark green", "Red", "Dark red", "Purple", "Orange",
            "Yellow", "Light green", "Teal", "Cyan", "Blue", "Magenta", "Gray", "Light gray",
        )
        MessengerPlusPaletteColor(index, names.getOrNull(index) ?: "#$rgb", (OPAQUE_ALPHA or rgb.toLong(16)).toInt())
    }

    fun parse(source: String, adaptiveColor: Int = 0xFF000000.toInt()): MessengerPlusParsedText =
        Parser(source, adaptiveColor).parse()

    fun parseForPresentation(
        source: String,
        formattingEnabled: Boolean,
        hideControlCharacters: Boolean = true,
        adaptiveColor: Int = 0xFF000000.toInt(),
    ): MessengerPlusParsedText {
        if (!formattingEnabled && !hideControlCharacters) {
            val glyphs = buildList {
                var index = 0
                while (index < source.length) {
                    val end = index + Character.charCount(source.codePointAt(index))
                    add(MessengerPlusGlyph(index, end, source.substring(index, end), MessengerPlusStyle()))
                    index = end
                }
            }
            return MessengerPlusParsedText(
                source, emptyList(), emptyList(), emptyList(), MessengerPlusStyle(),
                IntArray(source.length + 1) { it }, glyphs,
            )
        }
        val parsed = parse(source, adaptiveColor)
        return if (formattingEnabled) parsed else parsed.copy(
            styleSpans = emptyList(), sourceStyleSpans = emptyList(), finalStyle = MessengerPlusStyle(),
        )
    }

    fun plainText(source: String): String = parse(source).text
    fun hasVisibleContent(source: String): Boolean = plainText(source).any { !it.isWhitespace() }

    // Pair tags against the complete source, even when the caret precedes a closing tag.
    fun styleAt(source: String, sourceOffset: Int): MessengerPlusStyle =
        parse(source).sourceStyles[sourceOffset.coerceIn(0, source.length)]

    // All three native syntaxes can coexist. Rewriting tags independently corrupts
    // nested styles, gradients and [n] literals, especially across multiple send layers.
    fun normalizeProfileMarkupForChat(source: String): String = source

    /** Keep literal-region delimiters so removing styles cannot activate quoted tags. */
    fun removeFormatting(source: String): MessengerPlusTextTransform {
        val retained = BooleanArray(source.length) { true }
        parse(source).controlRanges.forEach { range ->
            val token = source.substring(range.start, range.endExclusive)
            if (!token.equals("[n]", true) && !token.equals("[/n]", true)) {
                for (index in range.start until range.endExclusive) retained[index] = false
            }
        }
        val boundaries = IntArray(source.length + 1)
        val output = buildString {
            source.forEachIndexed { index, character ->
                boundaries[index] = length
                if (retained[index]) append(character)
            }
            boundaries[source.length] = length
        }
        return MessengerPlusTextTransform(output, boundaries)
    }

    /** Removes one attribute from a selection, splitting enclosing scopes at its edges. */
    fun removeStyle(source: String, selection: MessengerPlusTextRange, control: Char): MessengerPlusStyleEdit {
        val kind = when (control) {
            BOLD_CONTROL -> 'b'
            ITALIC_CONTROL -> 'i'
            UNDERLINE_CONTROL -> 'u'
            STRIKEOUT_CONTROL -> 's'
            else -> return MessengerPlusStyleEdit(source, selection)
        }
        val start = selection.start.coerceAtMost(source.length)
        val end = selection.endExclusive.coerceIn(start, source.length)
        val retained = BooleanArray(source.length) { true }
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        matchedTags(source).filter { it.kind == kind && !it.closing }.forEach { open ->
            val close = checkNotNull(open.partner)
            if (open.end <= start && close.start >= end) {
                prefix.append("[/$kind]")
                suffix.append("[$kind]")
            } else if (open.start < end && close.end > start) {
                if (open.start >= start) {
                    for (index in open.start until open.end) retained[index] = false
                } else prefix.append("[/$kind]")
                if (close.end <= end) {
                    for (index in close.start until close.end) retained[index] = false
                } else suffix.append("[$kind]")
            }
        }
        var binary = false
        var binaryAtStart = false
        var binaryAtEnd = false
        parse(source).controlRanges.forEach { range ->
            val code = binaryControlAt(source, range.start)?.first
            if (code == control || code == RESET_CONTROL) {
                binary = if (code == RESET_CONTROL) false else !binary
                if (range.endExclusive <= start) binaryAtStart = binary
                if (range.endExclusive <= end) binaryAtEnd = binary
                if (code == control && range.start >= start && range.endExclusive <= end) {
                    for (index in range.start until range.endExclusive) retained[index] = false
                }
            }
        }
        // Readable controls also work in profile fields, where binary controls are filtered.
        val readable = when (control) {
            BOLD_CONTROL -> "·#"
            ITALIC_CONTROL -> "·&"
            UNDERLINE_CONTROL -> "·@"
            else -> "·'"
        }
        if (binaryAtStart) prefix.append(readable)
        if (binaryAtEnd) suffix.insert(0, readable)
        var selectionStart = 0
        var selectionEnd = 0
        val output = buildString {
            for (index in 0..source.length) {
                if (index == start) {
                    append(prefix)
                    selectionStart = length
                }
                if (index == end) {
                    selectionEnd = length
                    append(suffix)
                }
                if (index < source.length && retained[index]) append(source[index])
            }
        }
        return MessengerPlusStyleEdit(output, MessengerPlusTextRange(selectionStart, selectionEnd))
    }

    fun trimVisibleWhitespace(source: String): MessengerPlusTextTransform {
        val parsed = parse(source)
        val first = parsed.glyphs.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return MessengerPlusTextTransform("", IntArray(source.length + 1))
        val last = parsed.glyphs.indexOfLast { !it.isWhitespace() }
        val retained = BooleanArray(source.length) { true }
        parsed.glyphs.forEachIndexed { index, glyph ->
            if (index < first || index > last) {
                for (sourceIndex in glyph.sourceStart until glyph.sourceEndExclusive) retained[sourceIndex] = false
            }
        }
        val boundaries = IntArray(source.length + 1)
        val output = StringBuilder(source.length)
        source.forEachIndexed { index, character ->
            boundaries[index] = output.length
            if (retained[index]) output.append(character)
        }
        boundaries[source.length] = output.length
        return MessengerPlusTextTransform(output.toString(), boundaries)
    }

    fun colorControl(foreground: MessengerPlusColorValue?, background: MessengerPlusColorValue? = null): String =
        buildString {
            append(COLOR_CONTROL)
            foreground?.let { append(it.binaryToken()) }
            background?.let { append(','); append(it.binaryToken()) }
        }

    fun resetColorsControl(): String = COLOR_CONTROL.toString()
    fun colorsFromReset(style: MessengerPlusStyle): String =
        if (style.foreground == null && style.background == null) ""
        else colorControl(style.foreground, style.background)

    fun gradient(
        source: String,
        startColor: MessengerPlusColorValue,
        endColor: MessengerPlusColorValue,
        background: Boolean = false,
    ): String {
        val tag = if (background) "a" else "c"
        return "[$tag=${startColor.wireToken}]$source[/$tag=${endColor.wireToken}]"
    }

    fun paletteColor(index: Int): MessengerPlusColorValue? = palette.getOrNull(index)?.asValue()
    fun colorValue(token: String): MessengerPlusColorValue? = parseColorToken(token.trim())

    private class Parser(private val source: String, private val adaptiveColor: Int) {
        private val limit = source.indexOf('\u0000').let { if (it < 0) source.length else it }
        private val glyphs = mutableListOf<MessengerPlusGlyph>()
        private val controls = mutableListOf<MessengerPlusTextRange>()
        private val styles = MutableList(source.length + 1) { MessengerPlusStyle() }
        private val foregroundAtBoundary = arrayOfNulls<Tag>(source.length + 1)
        private val backgroundAtBoundary = arrayOfNulls<Tag>(source.length + 1)
        private val foregroundOwners = mutableListOf<Tag?>()
        private val backgroundOwners = mutableListOf<Tag?>()
        private val tags = matchedTags(source.substring(0, limit)).associateBy { it.start }

        private fun color(token: String): MessengerPlusColorValue? = parseColorToken(token)?.let {
            if (it.wireToken.equals("adapt", ignoreCase = true)) it.copy(argb = adaptiveColor) else it
        }

        fun parse(): MessengerPlusParsedText {
            // Native controls and BBCode are separate layers: a binary reset does not
            // cancel an enclosing [b], and a color reset resumes an enclosing gradient.
            var binary = MessengerPlusStyle()
            var binaryForegroundStart = -1
            var binaryBackgroundStart = -1
            val active = mutableListOf<Tag>()
            fun colorOwner(kind: Char, binaryStart: Int): Tag? = if (binaryStart >= 0) null else {
                active.lastOrNull { it.kind == kind && color(it.argument.orEmpty()) != null }
            }
            fun currentStyle(): MessengerPlusStyle {
                val foreground = colorOwner('c', binaryForegroundStart)
                val background = colorOwner('a', binaryBackgroundStart)
                return binary.copy(
                    bold = binary.bold || active.any { it.kind == 'b' },
                    italic = binary.italic || active.any { it.kind == 'i' },
                    underline = binary.underline || active.any { it.kind == 'u' },
                    strikeout = binary.strikeout || active.any { it.kind == 's' },
                    foreground = foreground?.let { color(it.argument.orEmpty()) } ?: binary.foreground,
                    background = background?.let { color(it.argument.orEmpty()) } ?: binary.background,
                )
            }
            var index = 0
            while (index < limit) {
                val start = index
                val before = currentStyle()
                val foregroundBefore = colorOwner('c', binaryForegroundStart)
                val backgroundBefore = colorOwner('a', binaryBackgroundStart)
                val tag = tags[index]
                val control = binaryControlAt(source, index)
                when {
                    tag != null -> {
                        if (tag.closing) active.remove(tag.partner) else active += tag
                        index = tag.end
                        controls += MessengerPlusTextRange(start, index)
                    }
                    control != null -> {
                        index += control.second
                        when (control.first) {
                            BOLD_CONTROL -> binary = binary.copy(bold = !binary.bold)
                            ITALIC_CONTROL -> binary = binary.copy(italic = !binary.italic)
                            UNDERLINE_CONTROL -> binary = binary.copy(underline = !binary.underline)
                            STRIKEOUT_CONTROL -> binary = binary.copy(strikeout = !binary.strikeout)
                            RESET_CONTROL -> {
                                binary = MessengerPlusStyle()
                                binaryForegroundStart = -1
                                binaryBackgroundStart = -1
                            }
                            COLOR_CONTROL -> {
                                val foreground = binaryColorAt(source, index, limit)
                                foreground?.let { index = it.second }
                                val background = if (index < limit && source[index] == ',') binaryColorAt(source, index + 1, limit) else null
                                background?.let { index = it.second }
                                if (foreground == null && background == null) {
                                    binary = binary.copy(foreground = null, background = null)
                                    binaryForegroundStart = -1
                                    binaryBackgroundStart = -1
                                } else {
                                    foreground?.let { binary = binary.copy(foreground = it.first); binaryForegroundStart = start }
                                    background?.let { binary = binary.copy(background = it.first); binaryBackgroundStart = start }
                                }
                            }
                        }
                        controls += MessengerPlusTextRange(start, index)
                    }
                    else -> {
                        index += Character.charCount(source.codePointAt(index))
                        glyphs += MessengerPlusGlyph(start, index, source.substring(start, index), before)
                        foregroundOwners += colorOwner('c', binaryForegroundStart)
                        backgroundOwners += colorOwner('a', binaryBackgroundStart)
                    }
                }
                for (boundary in start until index) {
                    styles[boundary] = before
                    foregroundAtBoundary[boundary] = foregroundBefore
                    backgroundAtBoundary[boundary] = backgroundBefore
                }
                styles[index] = currentStyle()
                foregroundAtBoundary[index] = colorOwner('c', binaryForegroundStart)
                backgroundAtBoundary[index] = colorOwner('a', binaryBackgroundStart)
            }
            val finalStyle = currentStyle()
            // Native strings terminate at NUL. Keep source mapping defined for the discarded tail.
            if (limit < source.length) controls += MessengerPlusTextRange(limit, source.length)
            for (boundary in limit..source.length) styles[boundary] = finalStyle
            applyGradients(foregroundOwners, background = false)
            applyGradients(backgroundOwners, background = true)
            styles.indices.forEach { boundary ->
                val foreground = gradientColor(foregroundAtBoundary[boundary], boundary)
                val background = gradientColor(backgroundAtBoundary[boundary], boundary)
                styles[boundary] = styles[boundary].copy(
                    foreground = foreground ?: styles[boundary].foreground,
                    background = background ?: styles[boundary].background,
                )
            }
            glyphs.forEach { glyph ->
                for (boundary in glyph.sourceStart until glyph.sourceEndExclusive) styles[boundary] = glyph.style
            }
            return MessengerPlusParsedText(
                glyphs.joinToString("") { it.value }, displayStyleSpans(glyphs), sourceStyleSpans(glyphs),
                controls, finalStyle, sourceBoundaryMap(glyphs, source.length), glyphs, styles,
            )
        }

        private fun applyGradients(owners: List<Tag?>, background: Boolean) {
            owners.forEachIndexed { index, owner ->
                val color = gradientColor(owner, glyphs[index].sourceStart) ?: return@forEachIndexed
                val glyph = glyphs[index]
                glyphs[index] = glyph.copy(style = if (background) glyph.style.copy(background = color) else glyph.style.copy(foreground = color))
            }
        }

        private fun gradientColor(owner: Tag?, position: Int): MessengerPlusColorValue? {
            if (owner == null) return null
            val first = color(owner.argument.orEmpty()) ?: return null
            val last = color(owner.partner?.argument.orEmpty()) ?: return null
            // Plus measures gradients in UTF-16 source positions. Nested tags,
            // binary codes and whitespace all occupy positions in the ramp.
            val count = checkNotNull(owner.partner).start - owner.end
            val offset = (position - owner.end).coerceIn(0, (count - 1).coerceAtLeast(0))
            val argb = interpolate(first.argb, last.argb, offset, count)
            return MessengerPlusColorValue(argb, argb.hexToken())
        }
    }
}

internal fun MessengerPlusPaletteColor.asValue(): MessengerPlusColorValue =
    MessengerPlusColorValue(argb = argb, wireToken = wireToken)
internal fun String.messengerPlusPlainText(): String = MessengerPlusTextCodec.plainText(this)
internal fun String.hasMessengerPlusVisibleContent(): Boolean = MessengerPlusTextCodec.hasVisibleContent(this)
internal fun String.trimMessengerPlusWhitespace(): MessengerPlusTextTransform = MessengerPlusTextCodec.trimVisibleWhitespace(this)

private class Tag(val start: Int, val end: Int, val kind: Char, val closing: Boolean, val argument: String?) {
    var partner: Tag? = null
}

private val TAG_PATTERN = Regex("\\[(/?)([biusn])]|\\[(/?)([ca])(?:=([^]\\r\\n]+))?]", RegexOption.IGNORE_CASE)

private fun matchedTags(source: String): List<Tag> {
    val tokens = TAG_PATTERN.findAll(source).mapNotNull { match ->
        val basic = match.groups[2] != null
        val kind = match.groupValues[if (basic) 2 else 4].lowercase(Locale.ROOT).single()
        val closing = match.groupValues[if (basic) 1 else 3].isNotEmpty()
        val argument = match.groups[5]?.value
        if (!basic && !closing && argument == null) null
        else Tag(match.range.first, match.range.last + 1, kind, closing, argument)
    }.toList()
    val noParse = mutableListOf<Pair<Tag, Tag>>()
    var noParseClose: Tag? = null
    // Unlike the style tags, [n] does not nest. Plus scans it from the right:
    // the last close consumes the nearest preceding open and quotes the interior.
    tokens.asReversed().filter { it.kind == 'n' }.forEach { tag ->
        if (tag.closing && noParseClose == null) noParseClose = tag
        if (!tag.closing && noParseClose != null) {
            noParse += tag to checkNotNull(noParseClose)
            noParseClose = null
        }
    }
    noParse.forEach { (open, close) -> open.partner = close; close.partner = open }
    val stacks = mutableMapOf<Char, MutableList<Tag>>()
    tokens.filter { it.kind != 'n' }.forEach { tag ->
        val stack = stacks.getOrPut(tag.kind) { mutableListOf() }
        if (!tag.closing) {
            if (noParse.none { (open, close) -> tag.start >= open.end && tag.start < close.start }) stack += tag
        } else if (stack.isNotEmpty()) {
            val open = stack.removeAt(stack.lastIndex)
            open.partner = tag
            tag.partner = open
        }
    }
    return tokens.filter { it.partner != null }
}

private fun binaryControlAt(source: String, index: Int): Pair<Char, Int>? {
    val character = source[index]
    if (character in "\u0002\u0003\u0005\u0006\u000F\u001F") return character to 1
    if (character != '·' || index + 1 >= source.length) return null
    val control = when (source[index + 1]) {
        '#' -> MessengerPlusTextCodec.BOLD_CONTROL
        '$' -> MessengerPlusTextCodec.COLOR_CONTROL
        '&' -> MessengerPlusTextCodec.ITALIC_CONTROL
        '@' -> MessengerPlusTextCodec.UNDERLINE_CONTROL
        '\'' -> MessengerPlusTextCodec.STRIKEOUT_CONTROL
        '0' -> MessengerPlusTextCodec.RESET_CONTROL
        else -> return null
    }
    return control to 2
}

private fun binaryColorAt(source: String, start: Int, limit: Int): Pair<MessengerPlusColorValue, Int>? {
    if (start >= limit) return null
    val end = when {
        source[start] == '#' -> start + 7
        source[start] in '0'..'9' -> if (start + 1 < limit && source[start + 1] in '0'..'9') start + 2 else start + 1
        else -> return null
    }
    if (end > limit) return null
    return (parseColorToken(source.substring(start, end)) ?: return null) to end
}

private val NAMED_COLORS = mapOf(
    "white" to "FFFFFF", "black" to "000000", "red" to "FF0000", "yellow" to "FFFF00",
    "pink" to "FFC0CB", "green" to "008000", "orange" to "FFA500", "purple" to "800080",
    "blue" to "0000FF", "beige" to "F5F5DC", "brown" to "A52A2A", "teal" to "008080",
    "navy" to "000080", "maroon" to "800000", "limegreen" to "32CD32", "gray" to "808080",
)

private fun parseColorToken(token: String): MessengerPlusColorValue? {
    if (token.equals("adapt", ignoreCase = true)) return MessengerPlusColorValue(0xFF000000.toInt(), "adapt")
    if (token.startsWith('#')) return MessengerPlusColorValue.fromHex(token.take(7))
    if (token.length in 1..2 && token.all { it in '0'..'9' }) {
        return MessengerPlusTextCodec.paletteColor(token.toInt()) ?: MessengerPlusColorValue(0xFF000000.toInt(), token)
    }
    return NAMED_COLORS[token.lowercase(Locale.ROOT)]?.let {
        MessengerPlusColorValue((OPAQUE_ALPHA or it.toLong(16)).toInt(), token)
    }
}

private fun displayStyleSpans(glyphs: List<MessengerPlusGlyph>): List<MessengerPlusStyleSpan> {
    if (glyphs.isEmpty()) return emptyList()
    val spans = mutableListOf<MessengerPlusStyleSpan>()
    var displayOffset = 0
    var spanStart = 0
    var spanStyle = glyphs.first().style
    glyphs.forEachIndexed { index, glyph ->
        if (index > 0 && glyph.style != spanStyle) {
            spans += MessengerPlusStyleSpan(
                MessengerPlusTextRange(spanStart, displayOffset),
                spanStyle,
            )
            spanStart = displayOffset
            spanStyle = glyph.style
        }
        displayOffset += glyph.value.length
    }
    spans += MessengerPlusStyleSpan(
        MessengerPlusTextRange(spanStart, displayOffset),
        spanStyle,
    )
    return spans
}

private fun sourceStyleSpans(glyphs: List<MessengerPlusGlyph>): List<MessengerPlusSourceStyleSpan> {
    if (glyphs.isEmpty()) return emptyList()
    val spans = mutableListOf<MessengerPlusSourceStyleSpan>()
    var spanStart = glyphs.first().sourceStart
    var spanEnd = glyphs.first().sourceEndExclusive
    var spanStyle = glyphs.first().style
    glyphs.drop(1).forEach { glyph ->
        if (glyph.style == spanStyle && glyph.sourceStart == spanEnd) {
            spanEnd = glyph.sourceEndExclusive
        } else {
            spans += MessengerPlusSourceStyleSpan(
                MessengerPlusTextRange(spanStart, spanEnd),
                spanStyle,
            )
            spanStart = glyph.sourceStart
            spanEnd = glyph.sourceEndExclusive
            spanStyle = glyph.style
        }
    }
    spans += MessengerPlusSourceStyleSpan(
        MessengerPlusTextRange(spanStart, spanEnd),
        spanStyle,
    )
    return spans
}

private fun sourceBoundaryMap(glyphs: List<MessengerPlusGlyph>, sourceLength: Int): IntArray {
    val displayGrowthAtBoundary = IntArray(sourceLength + 1)
    glyphs.forEach { glyph ->
        displayGrowthAtBoundary[glyph.sourceEndExclusive] += glyph.value.length
    }
    var displayOffset = 0
    return IntArray(sourceLength + 1) { boundary ->
        displayOffset += displayGrowthAtBoundary[boundary]
        displayOffset
    }
}

private fun MessengerPlusGlyph.isWhitespace(): Boolean = value.all(Char::isWhitespace)

private fun interpolate(start: Int, end: Int, index: Int, count: Int): Int {
    if (count <= 1) return start
    val ratio = index.toFloat() / (count - 1)
    fun channel(shift: Int): Int {
        val startChannel = start ushr shift and 0xFF
        val endChannel = end ushr shift and 0xFF
        return (startChannel + (endChannel - startChannel) * ratio).toInt()
    }
    return (channel(24) shl 24) or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}

private fun Int.hexToken(): String =
    "#${(this and 0x00FFFFFF).toString(16).uppercase(Locale.ROOT).padStart(6, '0')}"

private fun MessengerPlusColorValue.binaryToken(): String =
    if (wireToken.startsWith('#') || wireToken.all { it in '0'..'9' }) wireToken else argb.hexToken()

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val HEX_COLOR_DIGITS = 6
private const val OPAQUE_ALPHA = 0xFF000000L
