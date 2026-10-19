package org.aralozkaya.msngr

/** The power set of eight effects, including solid colors nested with gradients. */
internal object MessengerPlusCombinationCases {
    data class Case(val mask: Int, val foreground: Int, val background: Int) {
        val id = "%02d%d%d".format(java.util.Locale.ROOT, mask, foreground, background)
        val features: List<String> = buildList {
            listOf("b", "i", "u", "s").forEachIndexed { bit, tag ->
                if (mask and (1 shl bit) != 0) add(tag)
            }
            if (foreground and 1 != 0) add("c")
            if (foreground and 2 != 0) add("C")
            if (background and 1 != 0) add("a")
            if (background and 2 != 0) add("A")
        }

        fun source(body: String = "AbçZ", order: List<String> = features): String {
            var result = body
            order.asReversed().forEach { tag ->
                result = when (tag) {
                    "c" -> "[c=#C00000]$result[/c]"
                    "C" -> "[c=#FF0000]$result[/c=#0000FF]"
                    "a" -> "[a=#FFFF80]$result[/a]"
                    "A" -> "[a=#FFFF00]$result[/a=#00FF00]"
                    else -> "[$tag]$result[/$tag]"
                }
            }
            return result
        }

        fun assertStyles(source: String, body: String = "AbçZ") {
            val parsed = MessengerPlusTextCodec.parse(source)
            check(parsed.text == body) { "$id: expected $body, got ${parsed.text}" }
            parsed.glyphs.forEach { glyph ->
                val style = glyph.style
                check(style.bold == (mask and 1 != 0)) { "$id bold" }
                check(style.italic == (mask and 2 != 0)) { "$id italic" }
                check(style.underline == (mask and 4 != 0)) { "$id underline" }
                check(style.strikeout == (mask and 8 != 0)) { "$id strikeout" }
                check(style.foreground?.argb == expectedColor(source, glyph.sourceStart, false)) {
                    "$id foreground at ${glyph.sourceStart}"
                }
                check(style.background?.argb == expectedColor(source, glyph.sourceStart, true)) {
                    "$id background at ${glyph.sourceStart}"
                }
            }
        }

        fun expectedColor(source: String, offset: Int, isBackground: Boolean): Int? {
            val tag = if (isBackground) "a" else "c"
            val solidToken = if (isBackground) "#FFFF80" else "#C00000"
            val gradientToken = if (isBackground) "#FFFF00" else "#FF0000"
            val solidStart = source.lastIndexOf("[$tag=$solidToken]", offset)
            val gradientStart = source.lastIndexOf("[$tag=$gradientToken]", offset)
            if (solidStart < 0 && gradientStart < 0) return null
            if (solidStart > gradientStart) return if (isBackground) 0xFFFFFF80.toInt() else 0xFFC00000.toInt()
            val first = if (isBackground) 0xFFFFFF00.toInt() else 0xFFFF0000.toInt()
            val last = if (isBackground) 0xFF00FF00.toInt() else 0xFF0000FF.toInt()
            val start = source.indexOf(']', gradientStart) + 1
            val end = source.indexOf("[/$tag=")
            val denominator = (end - start - 1).coerceAtLeast(1)
            val numerator = (offset - start).coerceAtLeast(0)
            var rgb = 0xFF000000.toInt()
            for (shift in listOf(16, 8, 0)) {
                val a = (first ushr shift) and 255
                val b = (last ushr shift) and 255
                rgb = rgb or (((a * (denominator - numerator) + b * numerator) / denominator) shl shift)
            }
            return rgb
        }
    }

    val all = (0..15).flatMap { mask ->
        (0..3).flatMap { foreground -> (0..3).map { background -> Case(mask, foreground, background) } }
    }

    fun <T> permutations(items: List<T>): List<List<T>> = if (items.isEmpty()) listOf(emptyList()) else
        items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
}
