package org.aralozkaya.msngr

import androidx.annotation.StringRes
import java.util.Locale

internal const val EMOTICON_ASSETS_DIR = "emoticons"
internal const val EMOTICON_2012_ASSETS_DIR = "emoticons_2012"

internal fun formatMsnShortcutDisplay(shortcut: String): String =
    if (shortcut.startsWith("(") && shortcut.endsWith(")") && shortcut.length > 3) {
        shortcut.lowercase(Locale.ROOT)
    } else {
        shortcut.uppercase(Locale.ROOT)
    }

internal data class MsnEmoticonShortcut(
    val name: String,
    @param:StringRes val nameRes: Int,
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
     * Asset candidates for the emoticon selector sheet: always the static version
     * (`*-static.svg` / `*.svg` for WLM 2009; `*-static.png` / `*.png` for WLM 2012,
     * falling back to WLM 2009 for emoticons removed in 2012 such as cigarette).
     */
    val selectorAssetCandidates: List<String>
        get() = selectorAssetCandidates(EmoticonStyle.Wlm2009)

    fun selectorAssetCandidates(style: EmoticonStyle): List<String> {
        val wlm2009 = if (animated) {
            listOf(
                "$EMOTICON_ASSETS_DIR/$originalAsset-static.svg",
                "$EMOTICON_ASSETS_DIR/$originalAsset.svg",
            )
        } else {
            listOf("$EMOTICON_ASSETS_DIR/$originalAsset.svg")
        }
        return when (style) {
            EmoticonStyle.Wlm2012 -> listOf(
                "$EMOTICON_2012_ASSETS_DIR/$originalAsset-static.png",
                "$EMOTICON_2012_ASSETS_DIR/$originalAsset.png",
            ) + wlm2009
            EmoticonStyle.Wlm2009, EmoticonStyle.AndroidEmoji -> wlm2009
        }
    }

    /**
     * Asset candidates for rendered messages and editors:
     * - For WLM 2009: animated emoticons use APNG (`.png`), falling back to `.gif` and static `.svg`;
     *   static emoticons use `.svg`, falling back to `.png` and `.gif`.
     * - For WLM 2012: tries `emoticons_2012/<asset>.png` (which is APNG for animated 2012 emoticons
     *   and static PNG for non-animated ones), falling back to `*-static.png` and then WLM 2009.
     */
    val artworkAssetCandidates: List<String>
        get() = artworkAssetCandidates(EmoticonStyle.Wlm2009)

    fun artworkAssetCandidates(style: EmoticonStyle): List<String> {
        val wlm2009 = if (animated) {
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
        return when (style) {
            EmoticonStyle.Wlm2012 -> listOf(
                "$EMOTICON_2012_ASSETS_DIR/$originalAsset.png",
                "$EMOTICON_2012_ASSETS_DIR/$originalAsset-static.png",
            ) + wlm2009
            EmoticonStyle.Wlm2009, EmoticonStyle.AndroidEmoji -> wlm2009
        }
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
    @param:StringRes val nameRes: Int,
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
    MsnEmoticonDefinition("Smile", R.string.emoticon_smile, ":)", "smile", "🙂", animated = false, alternateShortcuts = listOf(":-)")),
    MsnEmoticonDefinition("Open-mouthed", R.string.emoticon_open_mouthed, ":D", "open-mouthed-smile", "😀", animated = false, alternateShortcuts = listOf(":-D")),
    MsnEmoticonDefinition("Surprised", R.string.emoticon_surprised, ":O", "surprised-smile", "😲", animated = false, alternateShortcuts = listOf(":-O")),
    MsnEmoticonDefinition("Tongue out", R.string.emoticon_tongue_out, ":P", "smile-with-tongue-out", "😛", animated = false, alternateShortcuts = listOf(":-P")),
    MsnEmoticonDefinition("Wink", R.string.emoticon_wink_singular, ";)", "winking-smile", "😉", animated = true, alternateShortcuts = listOf(";-)")),
    MsnEmoticonDefinition("Sad", R.string.emoticon_sad, ":(", "sad-smile", "☹️", animated = false, alternateShortcuts = listOf(":-(")),
    MsnEmoticonDefinition("Confused", R.string.emoticon_confused, ":S", "confused-smile", "😕", animated = false, alternateShortcuts = listOf(":-S")),
    MsnEmoticonDefinition("Disappointed", R.string.emoticon_disappointed, ":|", "disappointed-smile", "😐", animated = false, alternateShortcuts = listOf(":-|")),
    MsnEmoticonDefinition("Crying", R.string.emoticon_crying, ":'(", "crying-face", "😢", animated = true, alternateShortcuts = listOf(":'-(")),
    MsnEmoticonDefinition("Embarrassed", R.string.emoticon_embarrassed, ":$", "embarrassed-smile", "😳", animated = false, alternateShortcuts = listOf(":-$")),
    MsnEmoticonDefinition("Hot", R.string.emoticon_hot, "(H)", "hot-smile", "😎"),
    MsnEmoticonDefinition("Angry", R.string.emoticon_angry, ":@", "angry-smile", "😡", animated = false, alternateShortcuts = listOf(":-@")),
    MsnEmoticonDefinition("Angel", R.string.emoticon_angel, "(A)", "angel", "😇"),
    MsnEmoticonDefinition("Devil", R.string.emoticon_devil, "(6)", "devil", "😈"),
    MsnEmoticonDefinition("Don't tell anyone", R.string.emoticon_dont_tell_anyone, ":-#", "dont-tell-anyone-smile", "🤐"),
    MsnEmoticonDefinition("Baring teeth", R.string.emoticon_baring_teeth, "8O|", "baring-teeth-smile", "😬"),
    MsnEmoticonDefinition("Nerd", R.string.emoticon_nerd, "8-|", "nerd-smile", "🤓"),
    MsnEmoticonDefinition("Sarcastic", R.string.emoticon_sarcastic, "^O)", "sarcastic-smile", "🤨"),
    MsnEmoticonDefinition("Secret telling", R.string.emoticon_secret_telling, ":-*", "secret-telling-smile", "🤫"),
    MsnEmoticonDefinition("Sick", R.string.emoticon_sick, "+O(", "sick-smile", "🤢"),
    MsnEmoticonDefinition("I don't know", R.string.emoticon_i_dont_know, ":^)", "i-dont-know-smile", "😕", animated = true),
    MsnEmoticonDefinition("Thinking", R.string.emoticon_thinking, "*-)", "thinking-smile", "🤔", animated = true),
    MsnEmoticonDefinition("Party", R.string.emoticon_party, "<:O)", "party-smile", "🥳", animated = true),
    MsnEmoticonDefinition("Eye-rolling", R.string.emoticon_eye_rolling, "8-)", "eye-rolling-smile", "🙄", animated = true),
    MsnEmoticonDefinition("Sleepy", R.string.emoticon_sleepy, "|-)", "sleepy-smile", "🥱", animated = true),
    MsnEmoticonDefinition("Coffee cup", R.string.emoticon_coffee_cup, "(C)", "coffee-cup", "☕"),
    MsnEmoticonDefinition("Thumbs up", R.string.emoticon_thumbs_up, "(Y)", "thumbs-up", "👍"),
    MsnEmoticonDefinition("Thumbs down", R.string.emoticon_thumbs_down, "(N)", "thumbs-down", "👎"),
    MsnEmoticonDefinition("Beer mug", R.string.emoticon_beer_mug, "(B)", "beer-mug", "🍺"),
    MsnEmoticonDefinition("Martini glass", R.string.emoticon_martini_glass, "(D)", "martini-glass", "🍸"),
    MsnEmoticonDefinition("Girl", R.string.emoticon_girl, "(X)", "girl", "👧"),
    MsnEmoticonDefinition("Boy", R.string.emoticon_boy, "(Z)", "boy", "👦"),
    MsnEmoticonDefinition("Left hug", R.string.emoticon_left_hug, "({)", "left-hug", "🫂"),
    MsnEmoticonDefinition("Right hug", R.string.emoticon_right_hug, "(})", "right-hug", "🫂"),
    MsnEmoticonDefinition("Vampire bat", R.string.emoticon_vampire_bat, ":[", "vampire-bat", "🦇", animated = true, alternateShortcuts = listOf(":-[")),
    MsnEmoticonDefinition("Birthday cake", R.string.emoticon_birthday_cake, "(^)", "birthday-cake", "🎂", animated = true),
    MsnEmoticonDefinition("Red heart", R.string.emoticon_red_heart, "(L)", "red-heart", "❤️"),
    MsnEmoticonDefinition("Broken heart", R.string.emoticon_broken_heart, "(U)", "broken-heart", "💔"),
    MsnEmoticonDefinition("Red lips", R.string.emoticon_red_lips, "(K)", "red-lips", "👄"),
    MsnEmoticonDefinition("Gift with a bow", R.string.emoticon_gift_with_bow, "(G)", "gift-with-a-bow", "🎁"),
    MsnEmoticonDefinition("Red rose", R.string.emoticon_red_rose, "(F)", "red-rose", "🌹"),
    MsnEmoticonDefinition("Wilted rose", R.string.emoticon_wilted_rose, "(W)", "wilted-rose", "🥀"),
    MsnEmoticonDefinition("Camera", R.string.emoticon_camera, "(P)", "camera", "📷"),
    MsnEmoticonDefinition("Filmstrip", R.string.emoticon_filmstrip, "(~)", "filmstrip", "🎞️"),
    MsnEmoticonDefinition("Cat face", R.string.emoticon_cat_face, "(@)", "cat-face", "😺"),
    MsnEmoticonDefinition("Dog face", R.string.emoticon_dog_face, "(&)", "dog-face", "🐶"),
    MsnEmoticonDefinition("Telephone receiver", R.string.emoticon_telephone_receiver, "(T)", "telephone-receiver", "📞"),
    MsnEmoticonDefinition("Light bulb", R.string.emoticon_light_bulb, "(I)", "light-bulb", "💡"),
    MsnEmoticonDefinition("Note", R.string.emoticon_note, "(8)", "note", "🎵"),
    MsnEmoticonDefinition("Sleeping half-moon", R.string.emoticon_sleeping_half_moon, "(S)", "sleeping-half-moon", "🌜"),
    MsnEmoticonDefinition("Star", R.string.emoticon_star, "(*)", "star", "⭐"),
    MsnEmoticonDefinition("E-mail", R.string.emoticon_email, "(E)", "e-mail", "✉️"),
    MsnEmoticonDefinition("Clock", R.string.emoticon_clock, "(O)", "clock", "🕑"),
    MsnEmoticonDefinition("MSN Messenger icon", R.string.emoticon_msn_icon, "(M)", "messenger", "👥"),
    MsnEmoticonDefinition("Snail", R.string.emoticon_snail, "(sn)", "snail", "🐌"),
    MsnEmoticonDefinition("Black Sheep", R.string.emoticon_black_sheep, "(bah)", "black-sheep", "🐑"),
    MsnEmoticonDefinition("Plate", R.string.emoticon_plate, "(pl)", "plate", "🍽️"),
    MsnEmoticonDefinition("Bowl", R.string.emoticon_bowl, "(||)", "bowl", "🥣"),
    MsnEmoticonDefinition("Pizza", R.string.emoticon_pizza, "(pi)", "pizza", "🍕"),
    MsnEmoticonDefinition("Soccer ball", R.string.emoticon_soccer_ball, "(so)", "soccer-ball", "⚽"),
    MsnEmoticonDefinition("Auto", R.string.emoticon_auto, "(au)", "auto", "🚗"),
    MsnEmoticonDefinition("Airplane", R.string.emoticon_airplane, "(ap)", "airplane", "✈️"),
    MsnEmoticonDefinition("Umbrella", R.string.emoticon_umbrella, "(um)", "umbrella", "☂️"),
    MsnEmoticonDefinition("Island with a palm tree", R.string.emoticon_island, "(ip)", "island-with-a-palm-tree", "🏝️"),
    MsnEmoticonDefinition("Computer", R.string.emoticon_computer, "(co)", "computer", "🖥️"),
    MsnEmoticonDefinition("Mobile Phone", R.string.emoticon_mobile_phone, "(mp)", "mobile-phone", "📱"),
    MsnEmoticonDefinition("Stormy cloud", R.string.emoticon_stormy_cloud, "(st)", "storm-cloud", "🌧️"),
    MsnEmoticonDefinition("Lightning", R.string.emoticon_lightning, "(li)", "lightning", "🌩️", animated = true),
    MsnEmoticonDefinition("Money", R.string.emoticon_money, "(mo)", "money", "🪙"),
    MsnEmoticonDefinition("Goat", R.string.emoticon_goat, "(nnh)", "goat", "🐐"),
    MsnEmoticonDefinition("Sun", R.string.emoticon_sun, "(#)", "sun", "☀️"),
    MsnEmoticonDefinition("Rainbow", R.string.emoticon_rainbow, "(R)", "rainbow", "🌈"),
    MsnEmoticonDefinition("Turtle", R.string.emoticon_turtle, "(tu)", "turtle", "🐢"),
    MsnEmoticonDefinition("Be right back", R.string.emoticon_be_right_back, "(brb)", "be-right-back", "🔙"),
    MsnEmoticonDefinition("High five!", R.string.emoticon_high_five, "(h5)", "high-five", "🙏"),
    MsnEmoticonDefinition("Bunny", R.string.emoticon_bunny, "('.')", "bunny", "🐰"),
    MsnEmoticonDefinition("Fingers crossed", R.string.emoticon_fingers_crossed, "(yn)", "unused-fingers-crossed", "🤞"),
    MsnEmoticonDefinition("Cigarette", R.string.emoticon_cigarette, "(ci)", "unused-cigarette", "🚬", animated = true),
    MsnEmoticonDefinition("Xbox", R.string.emoticon_xbox, "(xx)", "unused-xbox", "🎮"),
).mapIndexed { index, definition ->
    MsnEmoticonShortcut(
        name = definition.name,
        nameRes = definition.nameRes,
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
