package org.aralozkaya.msngr

import java.util.Locale

internal const val EMOTICON_ASSETS_DIR = "emoticons"

internal fun formatMsnShortcutDisplay(shortcut: String): String =
    if (shortcut.startsWith("(") && shortcut.endsWith(")") && shortcut.length > 3) {
        shortcut.lowercase(Locale.ROOT)
    } else {
        shortcut.uppercase(Locale.ROOT)
    }

internal data class MsnEmoticonShortcut(
    val name: String,
    val shortcut: String,
    val originalAsset: String,
    val unicode: String,
    val animated: Boolean,
    val menuToken: Char,
    val alternateShortcuts: List<String> = emptyList(),
) {
    /**
     * Tooltips in the emoticon selector sheet display uppercase shortcuts, except for
     * multi-character shortcuts in parentheses (e.g. `(ci)`, `(h5)`, `(bah)`), which are lowercase.
     */
    val tooltipShortcut: String
        get() = formatMsnShortcutDisplay(shortcut)

    /** Every shortcut spelling recognized for this emoticon (matched case-insensitively). */
    val allShortcuts: List<String>
        get() = if (alternateShortcuts.isEmpty()) listOf(shortcut) else listOf(shortcut) + alternateShortcuts

    /**
     * Asset candidates for the emoticon selector sheet: always the static SVG version
     * (`*-static.svg` for animated emoticons, `*.svg` for static emoticons).
     */
    val selectorAssetCandidates: List<String>
        get() = if (animated) {
            listOf(
                "$EMOTICON_ASSETS_DIR/$originalAsset-static.svg",
                "$EMOTICON_ASSETS_DIR/$originalAsset.svg",
            )
        } else {
            listOf("$EMOTICON_ASSETS_DIR/$originalAsset.svg")
        }

    /**
     * Asset candidates for rendered messages and editors:
     * - Animated emoticons use APNG (`.png`), falling back to `.gif` and static `.svg`.
     * - Static emoticons use `.svg`, falling back to `.png` and `.gif` for future-proofing.
     */
    val artworkAssetCandidates: List<String>
        get() = if (animated) {
            listOf(
                "$EMOTICON_ASSETS_DIR/$originalAsset.png",
                "$EMOTICON_ASSETS_DIR/$originalAsset.gif",
                "$EMOTICON_ASSETS_DIR/$originalAsset-static.svg",
                "$EMOTICON_ASSETS_DIR/$originalAsset.svg",
            )
        } else {
            listOf(
                "$EMOTICON_ASSETS_DIR/$originalAsset.svg",
                "$EMOTICON_ASSETS_DIR/$originalAsset.png",
                "$EMOTICON_ASSETS_DIR/$originalAsset.gif",
            )
        }
}

data class MsnEmoticonRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "An emoticon range cannot start before the message." }
        require(endExclusive > start) { "An emoticon range must contain text." }
    }
}

internal data class MsnEmoticonMatch(
    val range: MsnEmoticonRange,
    val emoticon: MsnEmoticonShortcut,
)

internal data class MsnOutgoingMessage(
    val text: String,
    /** Null means all shortcuts should render. An empty list means none should render. */
    val renderedEmoticonRanges: List<MsnEmoticonRange>?,
)

private data class MsnEmoticonDefinition(
    val name: String,
    val shortcut: String,
    val originalAsset: String,
    val unicode: String,
    val animated: Boolean = false,
    val alternateShortcuts: List<String> = emptyList(),
)

/**
 * MSN Messenger's official emoticon shortcuts, including unused Messenger emoticons.
 * Source: http://web.archive.org/web/20140204231459/http://messenger.msn.com/Resource/Emoticons.aspx
 * Original assets and modern Unicode mappings: https://github.com/bernzrdo/msn-emoticons
 * The source table has no Unicode value for Secret telling, so the closest modern glyph (🤫) is used.
 */
internal val MSN_EMOTICON_SHORTCUTS = listOf(
    MsnEmoticonDefinition("Smile", ":)", "smile", "🙂", animated = false, alternateShortcuts = listOf(":-)")),
    MsnEmoticonDefinition("Open-mouthed", ":D", "open-mouthed-smile", "😀", animated = false, alternateShortcuts = listOf(":-D")),
    MsnEmoticonDefinition("Surprised", ":O", "surprised-smile", "😲", animated = false, alternateShortcuts = listOf(":-O")),
    MsnEmoticonDefinition("Tongue out", ":P", "smile-with-tongue-out", "😛", animated = false, alternateShortcuts = listOf(":-P")),
    MsnEmoticonDefinition("Wink", ";)", "winking-smile", "😉", animated = true, alternateShortcuts = listOf(";-)")),
    MsnEmoticonDefinition("Sad", ":(", "sad-smile", "☹️", animated = false, alternateShortcuts = listOf(":-(")),
    MsnEmoticonDefinition("Confused", ":S", "confused-smile", "😕", animated = false, alternateShortcuts = listOf(":-S")),
    MsnEmoticonDefinition("Disappointed", ":|", "disappointed-smile", "😐", animated = false, alternateShortcuts = listOf(":-|")),
    MsnEmoticonDefinition("Crying", ":'(", "crying-face", "😢", animated = true, alternateShortcuts = listOf(":'-(")),
    MsnEmoticonDefinition("Embarrassed", ":$", "embarrassed-smile", "😳", animated = false, alternateShortcuts = listOf(":-$")),
    MsnEmoticonDefinition("Hot", "(H)", "hot-smile", "😎"),
    MsnEmoticonDefinition("Angry", ":@", "angry-smile", "😡", animated = false, alternateShortcuts = listOf(":-@")),
    MsnEmoticonDefinition("Angel", "(A)", "angel", "😇"),
    MsnEmoticonDefinition("Devil", "(6)", "devil", "😈"),
    MsnEmoticonDefinition("Don't tell anyone", ":-#", "dont-tell-anyone-smile", "🤐"),
    MsnEmoticonDefinition("Baring teeth", "8O|", "baring-teeth-smile", "😬"),
    MsnEmoticonDefinition("Nerd", "8-|", "nerd-smile", "🤓"),
    MsnEmoticonDefinition("Sarcastic", "^O)", "sarcastic-smile", "🤨"),
    MsnEmoticonDefinition("Secret telling", ":-*", "secret-telling-smile", "🤫"),
    MsnEmoticonDefinition("Sick", "+O(", "sick-smile", "🤢"),
    MsnEmoticonDefinition("I don't know", ":^)", "i-dont-know-smile", "😕", animated = true),
    MsnEmoticonDefinition("Thinking", "*-)", "thinking-smile", "🤔", animated = true),
    MsnEmoticonDefinition("Party", "<:O)", "party-smile", "🥳", animated = true),
    MsnEmoticonDefinition("Eye-rolling", "8-)", "eye-rolling-smile", "🙄", animated = true),
    MsnEmoticonDefinition("Sleepy", "|-)", "sleepy-smile", "🥱", animated = true),
    MsnEmoticonDefinition("Coffee cup", "(C)", "coffee-cup", "☕"),
    MsnEmoticonDefinition("Thumbs up", "(Y)", "thumbs-up", "👍"),
    MsnEmoticonDefinition("Thumbs down", "(N)", "thumbs-down", "👎"),
    MsnEmoticonDefinition("Beer mug", "(B)", "beer-mug", "🍺"),
    MsnEmoticonDefinition("Martini glass", "(D)", "martini-glass", "🍸"),
    MsnEmoticonDefinition("Girl", "(X)", "girl", "👧"),
    MsnEmoticonDefinition("Boy", "(Z)", "boy", "👦"),
    MsnEmoticonDefinition("Left hug", "({)", "left-hug", "🫂"),
    MsnEmoticonDefinition("Right hug", "(})", "right-hug", "🫂"),
    MsnEmoticonDefinition("Vampire bat", ":[", "vampire-bat", "🦇", animated = true, alternateShortcuts = listOf(":-[")),
    MsnEmoticonDefinition("Birthday cake", "(^)", "birthday-cake", "🎂", animated = true),
    MsnEmoticonDefinition("Red heart", "(L)", "red-heart", "❤️"),
    MsnEmoticonDefinition("Broken heart", "(U)", "broken-heart", "💔"),
    MsnEmoticonDefinition("Red lips", "(K)", "red-lips", "👄"),
    MsnEmoticonDefinition("Gift with a bow", "(G)", "gift-with-a-bow", "🎁"),
    MsnEmoticonDefinition("Red rose", "(F)", "red-rose", "🌹"),
    MsnEmoticonDefinition("Wilted rose", "(W)", "wilted-rose", "🥀"),
    MsnEmoticonDefinition("Camera", "(P)", "camera", "📷"),
    MsnEmoticonDefinition("Filmstrip", "(~)", "filmstrip", "🎞️"),
    MsnEmoticonDefinition("Cat face", "(@)", "cat-face", "😺"),
    MsnEmoticonDefinition("Dog face", "(&)", "dog-face", "🐶"),
    MsnEmoticonDefinition("Telephone receiver", "(T)", "telephone-receiver", "📞"),
    MsnEmoticonDefinition("Light bulb", "(I)", "light-bulb", "💡"),
    MsnEmoticonDefinition("Note", "(8)", "note", "🎵"),
    MsnEmoticonDefinition("Sleeping half-moon", "(S)", "sleeping-half-moon", "🌜"),
    MsnEmoticonDefinition("Star", "(*)", "star", "⭐"),
    MsnEmoticonDefinition("E-mail", "(E)", "e-mail", "✉️"),
    MsnEmoticonDefinition("Clock", "(O)", "clock", "🕑"),
    MsnEmoticonDefinition("MSN Messenger icon", "(M)", "messenger", "👥"),
    MsnEmoticonDefinition("Snail", "(sn)", "snail", "🐌"),
    MsnEmoticonDefinition("Black Sheep", "(bah)", "black-sheep", "🐑"),
    MsnEmoticonDefinition("Plate", "(pl)", "plate", "🍽️"),
    MsnEmoticonDefinition("Bowl", "(||)", "bowl", "🥣"),
    MsnEmoticonDefinition("Pizza", "(pi)", "pizza", "🍕"),
    MsnEmoticonDefinition("Soccer ball", "(so)", "soccer-ball", "⚽"),
    MsnEmoticonDefinition("Auto", "(au)", "auto", "🚗"),
    MsnEmoticonDefinition("Airplane", "(ap)", "airplane", "✈️"),
    MsnEmoticonDefinition("Umbrella", "(um)", "umbrella", "☂️"),
    MsnEmoticonDefinition("Island with a palm tree", "(ip)", "island-with-a-palm-tree", "🏝️"),
    MsnEmoticonDefinition("Computer", "(co)", "computer", "🖥️"),
    MsnEmoticonDefinition("Mobile Phone", "(mp)", "mobile-phone", "📱"),
    MsnEmoticonDefinition("Stormy cloud", "(st)", "storm-cloud", "🌧️"),
    MsnEmoticonDefinition("Lightning", "(li)", "lightning", "🌩️", animated = true),
    MsnEmoticonDefinition("Money", "(mo)", "money", "🪙"),
    MsnEmoticonDefinition("Goat", "(nnh)", "goat", "🐐"),
    MsnEmoticonDefinition("Sun", "(#)", "sun", "☀️"),
    MsnEmoticonDefinition("Rainbow", "(R)", "rainbow", "🌈"),
    MsnEmoticonDefinition("Turtle", "(tu)", "turtle", "🐢"),
    MsnEmoticonDefinition("Be right back", "(brb)", "be-right-back", "🔙"),
    MsnEmoticonDefinition("High five!", "(h5)", "high-five", "🙏"),
    MsnEmoticonDefinition("Bunny", "('.')", "bunny", "🐰"),
    MsnEmoticonDefinition("Fingers crossed", "(yn)", "unused-fingers-crossed", "🤞"),
    MsnEmoticonDefinition("Cigarette", "(ci)", "unused-cigarette", "🚬", animated = true),
    MsnEmoticonDefinition("Xbox", "(xx)", "unused-xbox", "🎮"),
).mapIndexed { index, definition ->
    MsnEmoticonShortcut(
        name = definition.name,
        shortcut = formatMsnShortcutDisplay(definition.shortcut),
        originalAsset = definition.originalAsset,
        unicode = definition.unicode,
        animated = definition.animated,
        menuToken = (MSN_MENU_TOKEN_START + index).toChar(),
        alternateShortcuts = definition.alternateShortcuts.map(::formatMsnShortcutDisplay),
    )
}

private data class ShortcutCandidate(
    val token: String,
    val emoticon: MsnEmoticonShortcut,
)

private val MSN_SHORTCUT_CANDIDATES_LONGEST_FIRST by lazy {
    MSN_EMOTICON_SHORTCUTS
        .flatMap { emoticon ->
            emoticon.allShortcuts.map { token -> ShortcutCandidate(token, emoticon) }
        }
        .sortedByDescending { it.token.length }
}

internal fun findMsnEmoticons(
    text: String,
    includeTypedShortcuts: Boolean = true,
    renderedShortcutRanges: List<MsnEmoticonRange>? = null,
): List<MsnEmoticonMatch> {
    if (text.isEmpty()) return emptyList()
    val matches = mutableListOf<MsnEmoticonMatch>()
    var index = 0
    while (index < text.length) {
        val menuEmoticon = msnEmoticonForMenuToken(text[index])
        if (menuEmoticon != null) {
            matches += MsnEmoticonMatch(
                MsnEmoticonRange(index, index + 1),
                menuEmoticon,
            )
            index++
            continue
        }
        val candidate = if (includeTypedShortcuts) {
            MSN_SHORTCUT_CANDIDATES_LONGEST_FIRST.firstOrNull { candidate ->
                val endExclusive = index + candidate.token.length
                endExclusive <= text.length &&
                    renderedShortcutRanges.allows(index, endExclusive) &&
                    text.regionMatches(
                        thisOffset = index,
                        other = candidate.token,
                        otherOffset = 0,
                        length = candidate.token.length,
                        ignoreCase = true,
                    )
            }
        } else {
            null
        }
        if (candidate == null) {
            index++
        } else {
            val endExclusive = index + candidate.token.length
            matches += MsnEmoticonMatch(MsnEmoticonRange(index, endExclusive), candidate.emoticon)
            index = endExclusive
        }
    }
    return matches
}

internal fun String.withUnicodeMsnEmoticons(
    includeTypedShortcuts: Boolean = true,
    renderedShortcutRanges: List<MsnEmoticonRange>? = null,
): String {
    val matches = findMsnEmoticons(this, includeTypedShortcuts, renderedShortcutRanges)
    if (matches.isEmpty()) return this
    return buildString {
        var textStart = 0
        matches.forEach { match ->
            append(this@withUnicodeMsnEmoticons, textStart, match.range.start)
            append(match.emoticon.unicode)
            textStart = match.range.endExclusive
        }
        append(
            this@withUnicodeMsnEmoticons,
            textStart,
            this@withUnicodeMsnEmoticons.length,
        )
    }
}

internal fun String.toMsnOutgoingMessage(
    convertTypedShortcuts: Boolean,
    messengerPlusEnabled: Boolean = true,
): MsnOutgoingMessage {
    val sanitized = withoutUnicodeEmoji()
    val encoded = if (messengerPlusEnabled) {
        MessengerPlusTextCodec.normalizeProfileMarkupForChat(sanitized)
    } else {
        MessengerPlusTextCodec.plainText(sanitized)
    }
    val expanded = StringBuilder(encoded.length)
    val pickerRanges = mutableListOf<MsnEmoticonRange>()
    encoded.forEach { character ->
        val emoticon = msnEmoticonForMenuToken(character)
        if (emoticon == null) {
            expanded.append(character)
        } else {
            val start = expanded.length
            expanded.append(emoticon.shortcut)
            pickerRanges += MsnEmoticonRange(start, expanded.length)
        }
    }

    val trimmed = expanded.toString().trimMessengerPlusWhitespace()
    if (trimmed.text.isEmpty()) {
        return MsnOutgoingMessage(
            text = "",
            renderedEmoticonRanges = if (convertTypedShortcuts) null else emptyList(),
        )
    }
    val adjustedPickerRanges = pickerRanges.mapNotNull(trimmed::map)
    return MsnOutgoingMessage(
        text = trimmed.text,
        renderedEmoticonRanges = if (convertTypedShortcuts) null else adjustedPickerRanges,
    )
}

internal fun String.withExpandedMsnMenuTokens(): String {
    if (none { msnEmoticonForMenuToken(it) != null }) return this
    return buildString {
        this@withExpandedMsnMenuTokens.forEach { character ->
            append(msnEmoticonForMenuToken(character)?.shortcut ?: character)
        }
    }
}

private fun msnEmoticonForMenuToken(character: Char): MsnEmoticonShortcut? =
    MSN_EMOTICON_SHORTCUTS.getOrNull(character.code - MSN_MENU_TOKEN_START)

private fun List<MsnEmoticonRange>?.allows(start: Int, endExclusive: Int): Boolean =
    this == null || any { it.start == start && it.endExclusive == endExclusive }

private const val MSN_MENU_TOKEN_START = 0xE000

/** Removes Unicode emoji committed by an IME or paste while retaining ordinary Unicode text. */
internal fun String.withoutUnicodeEmoji(): String {
    val ranges = unicodeEmojiRanges()
    if (ranges.isEmpty()) return this
    return buildString(length) {
        var previousEnd = 0
        for (range in ranges) {
            append(this@withoutUnicodeEmoji, previousEnd, range.first)
            previousEnd = range.last + 1
        }
        append(this@withoutUnicodeEmoji, previousEnd, this@withoutUnicodeEmoji.length)
    }
}
