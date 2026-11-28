package org.aralozkaya.msngr

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.MessageAuthor
import java.text.NumberFormat
import java.util.Locale

private val NO_SPACE_AFTER_PREFIX = setOf('(', '[', '{', '#', '%', '\u2013', '-', '\u00D7', '$', '\u20AC', '\u00A3')
private val NO_SPACE_BEFORE_SUFFIX = setOf('.', ',', ';', ':', '!', '?', '%', '+', ')', ']', '}', '\u2013', '-', '\u00D7', '\'', '\u2019')

fun formatNumberSentence(before: String, number: Any, after: String): String = buildString {
    if (before.isNotEmpty()) {
        append(before)
        val last = before.last()
        if (!last.isWhitespace() && last !in NO_SPACE_AFTER_PREFIX) {
            append(' ')
        }
    }
    append(number.toString())
    if (after.isNotEmpty()) {
        val first = after.first()
        if (!first.isWhitespace() && first !in NO_SPACE_BEFORE_SUFFIX) {
            append(' ')
        }
        append(after)
    }
}

fun formatTwoNumbersSentence(
    beforeFirst: String,
    firstNumber: Any,
    between: String,
    secondNumber: Any,
    afterSecond: String,
): String = formatNumberSentence(formatNumberSentence(beforeFirst, firstNumber, between), secondNumber, afterSecond)

private fun Resources.currentAppLocale(): Locale =
    configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()

fun Context.currentAppLocale(): Locale = resources.currentAppLocale()

@Composable
fun currentAppLocale(): Locale = LocalLocale.current.platformLocale

private fun Resources.formatLocalizedInt(value: Int): String =
    NumberFormat.getIntegerInstance(currentAppLocale()).format(value)

fun Context.formatLocalizedInt(value: Int): String = resources.formatLocalizedInt(value)

@Composable
fun formatLocalizedInt(value: Int): String =
    NumberFormat.getIntegerInstance(currentAppLocale()).format(value)

fun Context.formatDecimal(value: Double, decimals: Int = 1): String =
    String.format(currentAppLocale(), "%.${decimals}f", value)

@Composable
fun formatDecimal(value: Double, decimals: Int = 1): String =
    String.format(currentAppLocale(), "%.${decimals}f", value)

fun Context.getNumberSentence(
    @StringRes beforeRes: Int,
    number: Any,
    @StringRes afterRes: Int,
): String = resources.getNumberSentence(beforeRes, number, afterRes)

fun Resources.getNumberSentence(
    @StringRes beforeRes: Int,
    number: Any,
    @StringRes afterRes: Int,
): String = formatNumberSentence(getString(beforeRes), number, getString(afterRes))

@Composable
fun numberSentenceResource(
    @StringRes beforeRes: Int,
    number: Any,
    @StringRes afterRes: Int,
): String = LocalResources.current.getNumberSentence(beforeRes, number, afterRes)

fun Context.getQuantityNumberSentence(
    count: Int,
    @StringRes oneBeforeRes: Int,
    @StringRes oneAfterRes: Int,
    @StringRes otherBeforeRes: Int,
    @StringRes otherAfterRes: Int,
): String = resources.getQuantityNumberSentence(count, oneBeforeRes, oneAfterRes, otherBeforeRes, otherAfterRes)

private fun Resources.getQuantityNumberSentence(
    count: Int,
    @StringRes oneBeforeRes: Int,
    @StringRes oneAfterRes: Int,
    @StringRes otherBeforeRes: Int,
    @StringRes otherAfterRes: Int,
): String = if (count == 1) {
    getNumberSentence(oneBeforeRes, count, oneAfterRes)
} else {
    getNumberSentence(otherBeforeRes, count, otherAfterRes)
}

@Composable
fun quantityNumberSentenceResource(
    count: Int,
    @StringRes oneBeforeRes: Int,
    @StringRes oneAfterRes: Int,
    @StringRes otherBeforeRes: Int,
    @StringRes otherAfterRes: Int,
): String = LocalResources.current.getQuantityNumberSentence(count, oneBeforeRes, oneAfterRes, otherBeforeRes, otherAfterRes)

fun Context.getTwoNumbersSentence(
    @StringRes beforeFirstRes: Int,
    firstNumber: Any,
    @StringRes betweenRes: Int,
    secondNumber: Any,
    @StringRes afterSecondRes: Int,
): String = resources.getTwoNumbersSentence(beforeFirstRes, firstNumber, betweenRes, secondNumber, afterSecondRes)

private fun Resources.getTwoNumbersSentence(
    @StringRes beforeFirstRes: Int,
    firstNumber: Any,
    @StringRes betweenRes: Int,
    secondNumber: Any,
    @StringRes afterSecondRes: Int,
): String = formatTwoNumbersSentence(
    getString(beforeFirstRes),
    firstNumber,
    getString(betweenRes),
    secondNumber,
    getString(afterSecondRes),
)

@Composable
fun twoNumbersSentenceResource(
    @StringRes beforeFirstRes: Int,
    firstNumber: Any,
    @StringRes betweenRes: Int,
    secondNumber: Any,
    @StringRes afterSecondRes: Int,
): String = LocalResources.current.getTwoNumbersSentence(beforeFirstRes, firstNumber, betweenRes, secondNumber, afterSecondRes)

@StringRes
fun providerDisplayNameRes(provider: String): Int? = when (provider) {
    ProviderOptions.ESCARGOT -> R.string.account_provider_escargot
    ProviderOptions.CROSSTALK -> R.string.account_provider_crosstalk
    ProviderOptions.CUSTOM -> R.string.account_provider_custom
    else -> null
}

fun Context.providerDisplayName(provider: String): String =
    providerDisplayNameRes(provider)?.let(::getString) ?: provider

@Composable
fun providerDisplayName(provider: String): String =
    providerDisplayNameRes(provider)?.let { stringResource(it) } ?: provider

private val SCENE_NAME_RES_BY_ID: Map<String, Int> = mapOf(
    "mesmerizingwhite" to R.string.scene_name_mezmerizing_white,
    "daisyhill" to R.string.scene_name_daisy_hill,
    "field_wlm_2009" to R.string.scene_name_field,
    "silhouette_wlm_2009" to R.string.scene_name_silhouette,
    "morty_wlm_2009" to R.string.scene_name_morty,
    "dawn_wlm" to R.string.scene_name_dawn,
    "zune_02" to R.string.scene_name_zune_2,
    "dottiegreen_wlm" to R.string.scene_name_dottie_green,
    "violet_springtime_wlm" to R.string.scene_name_violet_springtime,
    "cherry_blossoms_wlm" to R.string.scene_name_cherry_blossoms,
    "zune_05" to R.string.scene_name_zune_5,
    "zune_01" to R.string.scene_name_zune_1,
    "flourish_wlm" to R.string.scene_name_flourish,
    "carbonfiber_wlm" to R.string.scene_name_carbon_fiber,
    "robot_wlm" to R.string.scene_name_robot,
    "graffiti_wlm" to R.string.scene_name_graffiti,
    "bamboo_wlm" to R.string.scene_name_bamboo,
    "zune_06" to R.string.scene_name_zune_6,
    "zune_04" to R.string.scene_name_zune_4,
    "mesmerizing_brown_wlm" to R.string.scene_name_mezmerizing_brown,
    "zune_03" to R.string.scene_name_zune_3,
    "butterflypattern_wlm" to R.string.scene_name_butterfly_pattern,
    "wlm_2011_bamboo" to R.string.scene_name_bamboo,
    "wlm_2011_bird_man" to R.string.scene_name_bird_man,
    "wlm_2011_bounce" to R.string.scene_name_bounce,
    "wlm_2011_carbon" to R.string.scene_name_carbon,
    "wlm_2011_cherry_blossoms" to R.string.scene_name_cherry_blossoms,
    "wlm_2011_dahlias" to R.string.scene_name_dahlias,
    "wlm_2011_daisy_hill" to R.string.scene_name_daisy_hill,
    "wlm_2011_dark_hearts" to R.string.scene_name_dark_hearts,
    "wlm_2011_desert" to R.string.scene_name_desert,
    "wlm_2011_flourish" to R.string.scene_name_flourish,
    "wlm_2011_grafitti" to R.string.scene_name_grafitti,
    "wlm_2011_hostas" to R.string.scene_name_hostas,
    "wlm_2011_metalwork" to R.string.scene_name_metalwork,
    "wlm_2011_misty_hills" to R.string.scene_name_misty_hills,
    "wlm_2011_moonlight" to R.string.scene_name_moonlight,
    "wlm_2011_prairie" to R.string.scene_name_prairie,
    "wlm_2011_psychedelic" to R.string.scene_name_psychedelic,
    "wlm_2011_punga" to R.string.scene_name_punga,
    "wlm_2011_robot" to R.string.scene_name_robot,
    "wlm_2011_sky_turtle" to R.string.scene_name_sky_turtle,
    "wlm_2011_spore" to R.string.scene_name_spore,
    "wlm_2011_sporty" to R.string.scene_name_sporty,
    "wlm_2011_sweet_monsters" to R.string.scene_name_sweet_monsters,
    "wlm_2011_tugboat" to R.string.scene_name_tugboat,
    "wlm_2011_violet_springtime" to R.string.scene_name_violet_springtime,
    "wlm_2011_watercolor" to R.string.scene_name_watercolor,
    "wlm_2011_wildflowers" to R.string.scene_name_wildflowers,
    "wlm_2011_windows_live" to R.string.scene_name_windows_live,
    "wlm_2011_zune_hearts" to R.string.scene_name_zune_hearts,
)

@Composable
internal fun MessengerScene.localizedName(): String =
    SCENE_NAME_RES_BY_ID[id]?.let { stringResource(it) } ?: name

@Composable
internal fun MessengerScene.localizedGroupName(): String = when (group) {
    SCENE_GROUP_2009 -> stringResource(R.string.scene_collection_2009)
    SCENE_GROUP_2011_2012 -> stringResource(R.string.scene_collection_2011_2012)
    else -> collectionName
}

private val DEFAULT_DP_NAME_RES_BY_ID: Map<String, Int> = mapOf(
    "beach_chairs" to R.string.dp_beach_chairs,
    "chess_pieces" to R.string.dp_chess_pieces,
    "dirt_bike" to R.string.dp_dirt_bike,
    "friendly_dog" to R.string.dp_friendly_dog,
    "orange_daisy" to R.string.dp_orange_daisy,
    "palm_trees" to R.string.dp_palm_trees,
    "rocket_launch" to R.string.dp_rocket_launch,
    "rubber_ducky" to R.string.dp_rubber_ducky,
    "running_horses" to R.string.dp_running_horses,
    "skateboarder" to R.string.dp_skateboarder,
    "soccer_ball" to R.string.dp_soccer_ball,
    "basketball_windows_live_messenger" to R.string.dp_basketball,
    "bonsai" to R.string.dp_bonsai,
    "chef" to R.string.dp_chef,
    "chess_windows_live_messenger" to R.string.dp_chess,
    "daisy_windows_live_messenger" to R.string.dp_daisy,
    "doctor" to R.string.dp_doctor,
    "dog_windows_live_messenger" to R.string.dp_dog,
    "electric_guitar" to R.string.dp_electric_guitar,
    "executive" to R.string.dp_executive,
    "fall" to R.string.dp_fall,
    "fish_windows_live_messenger" to R.string.dp_fish,
    "flare" to R.string.dp_flare,
    "gerber_daisy" to R.string.dp_gerber_daisy,
    "golf_windows_live_messenger" to R.string.dp_golf,
    "guest_windows_live_messenger" to R.string.dp_guest,
    "guitar_windows_live_messenger" to R.string.dp_guitar,
    "kitten_windows_live_messenger" to R.string.dp_kitten,
    "leaf_windows_live_messenger" to R.string.dp_leaf,
    "morty" to R.string.dp_morty,
    "music_windows_live_messenger" to R.string.dp_music,
    "robot_windows_live_messenger" to R.string.dp_robot,
    "seastar" to R.string.dp_seastar,
    "shopping" to R.string.dp_shopping,
    "soccer_windows_live_messenger" to R.string.dp_soccer,
    "sports_windows_live_messenger" to R.string.dp_sports,
    "spring" to R.string.dp_spring,
    "summer" to R.string.dp_summer,
    "surf" to R.string.dp_surf,
    "tennis_windows_live_messenger" to R.string.dp_tennis,
    "winter" to R.string.dp_winter,
    "animatedusertile1" to R.string.dp_city_light,
    "animatedusertile3" to R.string.dp_fish_tank,
    "animatedusertile2" to R.string.dp_shooting_star,
    "animatedusertile5" to R.string.dp_sunshine,
)

@Composable
internal fun DefaultDisplayPicture.localizedName(): String =
    DEFAULT_DP_NAME_RES_BY_ID[id]?.let { stringResource(it) } ?: name

@Composable
fun localizedDefaultPictureVersion(version: String): String = when (version) {
    DEFAULT_DP_VERSION_CLASSIC -> twoNumbersSentenceResource(
        R.string.default_picture_version_6_to_8_before_first_number,
        "6.1",
        R.string.default_picture_version_6_to_8_between_numbers,
        "8.5",
        R.string.default_picture_version_6_to_8_after_second_number,
    )
    DEFAULT_DP_VERSION_2009 -> numberSentenceResource(
        R.string.default_picture_version_2009_before_number,
        2009,
        R.string.default_picture_version_2009_after_number,
    )
    DEFAULT_DP_VERSION_2011_2012 -> twoNumbersSentenceResource(
        R.string.default_picture_version_2011_2012_before_first_number,
        2011,
        R.string.default_picture_version_2011_2012_between_numbers,
        2012,
        R.string.default_picture_version_2011_2012_after_second_number,
    )
    else -> version
}

private val BUILTIN_WINK_NAME_RES: Map<String, Int> = mapOf(
    "Bouncy Ball" to R.string.wink_builtin_bouncy_ball,
    "Bow" to R.string.wink_builtin_bow,
    "Crying" to R.string.wink_builtin_crying,
    "Dancer" to R.string.wink_builtin_dancer,
    "Dancing Pig" to R.string.wink_builtin_dancing_pig,
    "Frog" to R.string.wink_builtin_frog,
    "Guitar Smash" to R.string.wink_builtin_guitar_smash,
    "Heart" to R.string.wink_builtin_heart,
    "Kiss" to R.string.wink_builtin_kiss,
    "Knock" to R.string.wink_builtin_knock,
    "Laughing Girl" to R.string.wink_builtin_laughing_girl,
    "Lightbulb" to R.string.wink_builtin_lightbulb,
    "Silly Face" to R.string.wink_builtin_silly_face,
    "Ufo" to R.string.wink_builtin_ufo,
    "Water Balloon" to R.string.wink_builtin_water_balloon,
    "Wink" to R.string.wink_fallback_name_singular,
)

fun Context.localizeWinkName(name: String): String = resources.localizeWinkName(name)

private fun Resources.localizeWinkName(name: String): String =
    BUILTIN_WINK_NAME_RES[name]?.let(::getString) ?: name

@Composable
fun localizedWinkName(name: String): String =
    BUILTIN_WINK_NAME_RES[name]?.let { stringResource(it) } ?: name

private val BUILTIN_SOUND_NAME_RES: Map<String, Int> = mapOf(
    "Hello!" to R.string.emotion_sound_default_hello,
    "Bye!" to R.string.emotion_sound_default_bye,
    "Be Right Back" to R.string.emotion_sound_default_brb,
    "I'm So Stupid!" to R.string.emotion_sound_default_im_so_stupid,
    "Are You Mad?!?" to R.string.emotion_sound_default_are_you_mad_you_singular,
    "Oh My God!" to R.string.emotion_sound_default_omg,
    "Kiss" to R.string.emotion_sound_default_kiss,
    "Evil Laugh" to R.string.emotion_sound_default_evil_laugh,
    "Imperial March" to R.string.emotion_sound_default_imperial_march,
    "Come on!" to R.string.emotion_sound_default_come_on,
    "Dangerous" to R.string.emotion_sound_default_dangerous,
    "Applause" to R.string.emotion_sound_default_applause,
    "Woow!" to R.string.emotion_sound_default_woow,
    "Right..." to R.string.emotion_sound_default_right,
    "Trekkie" to R.string.emotion_sound_default_trekkie,
    "Yawn" to R.string.emotion_sound_default_yawn,
    "You're Boring!" to R.string.emotion_sound_default_boring_you_singular,
    "Emotion sound" to R.string.emotion_sound_fallback_name_singular,
)

private val EMOTION_SOUND_NUMBERED_REGEX = Regex("""^Emotion sound (\d+)$""")

fun Context.localizeEmotionSoundName(name: String): String = resources.localizeEmotionSoundName(name)

fun Resources.localizeEmotionSoundName(name: String): String {
    BUILTIN_SOUND_NAME_RES[name]?.let { return getString(it) }
    EMOTION_SOUND_NUMBERED_REGEX.matchEntire(name)?.let { match ->
        return getNumberSentence(
            R.string.emotion_sound_numbered_singular_before_number,
            match.groupValues[1],
            R.string.emotion_sound_numbered_singular_after_number,
        )
    }
    return name
}

@Composable
fun localizedEmotionSoundName(name: String): String =
    LocalResources.current.localizeEmotionSoundName(name)

private val EMOTION_SOUND_CATEGORY_RES: List<Int> = listOf(
    R.string.emotion_sound_cat_any,
    R.string.emotion_sound_cat_cartoons_games,
    R.string.emotion_sound_cat_tv_movies,
    R.string.emotion_sound_cat_music,
    R.string.emotion_sound_cat_vocals,
    R.string.emotion_sound_cat_sfx,
)

private val EMOTION_SOUND_LANGUAGE_RES: List<Int> = listOf(
    R.string.emotion_sound_lang_any,
    R.string.emotion_sound_lang_arabic,
    R.string.emotion_sound_lang_catalan,
    R.string.emotion_sound_lang_chinese,
    R.string.emotion_sound_lang_danish,
    R.string.emotion_sound_lang_german,
    R.string.emotion_sound_lang_greek,
    R.string.emotion_sound_lang_english,
    R.string.emotion_sound_lang_spanish,
    R.string.emotion_sound_lang_finnish,
    R.string.emotion_sound_lang_french,
    R.string.emotion_sound_lang_hebrew,
    R.string.emotion_sound_lang_portuguese,
    R.string.emotion_sound_lang_italian,
    R.string.emotion_sound_lang_japanese,
    R.string.emotion_sound_lang_korean,
    R.string.emotion_sound_lang_dutch,
    R.string.emotion_sound_lang_norwegian,
    R.string.emotion_sound_lang_russian,
    R.string.emotion_sound_lang_swedish,
    R.string.emotion_sound_lang_thai,
    R.string.emotion_sound_lang_turkish,
    R.string.emotion_sound_lang_kurdish,
    R.string.emotion_sound_lang_estonian,
    R.string.emotion_sound_lang_hungarian,
)

@Composable
fun localizedEmotionSoundCategories(): List<String> =
    EMOTION_SOUND_CATEGORY_RES.map { stringResource(it) }

@Composable
fun localizedEmotionSoundLanguages(): List<String> =
    EMOTION_SOUND_LANGUAGE_RES.map { stringResource(it) }

@Composable
fun localizedEmotionSoundCategory(categoryIndex: Int): String =
    if (categoryIndex in 1 until EMOTION_SOUND_CATEGORY_RES.size) {
        stringResource(EMOTION_SOUND_CATEGORY_RES[categoryIndex])
    } else {
        stringResource(R.string.common_unknown)
    }

private val GAME_CATEGORY_NAME_RES: Map<String, Int> = mapOf(
    "Microsoft" to R.string.game_cat_microsoft_name,
    "Escargot Games" to R.string.game_cat_escargot_games_name,
    "Mess.be Games" to R.string.game_cat_messbe_name,
    "Escargot Activities" to R.string.game_cat_escargot_activities_name,
)

private val GAME_CATEGORY_DESC_RES: Map<String, Int> = mapOf(
    "Official games by Microsoft and Zone.com" to R.string.game_cat_microsoft_desc,
    "Games made for Escargot!" to R.string.game_cat_escargot_games_desc,
    "Classic games from Mess.be" to R.string.game_cat_messbe_desc,
    "Shared activities for Escargot" to R.string.game_cat_escargot_activities_desc,
)

private val GAME_NAME_RES: Map<String, Int> = mapOf(
    "Tic Tac Toe" to R.string.game_tic_tac_toe_name,
    "Wordle (beta)" to R.string.game_wordle_name,
    "Reversi" to R.string.game_reversi_name,
    "Yahtzee" to R.string.game_yahtzee_name,
    "Battleships" to R.string.game_battleships_name,
    "Chess" to R.string.game_chess_name,
    "Nine Men's Morris" to R.string.game_nine_mens_morris_name,
    "Connect 4" to R.string.game_connect4_name,
    "Memory" to R.string.game_memory_name,
    "Tetris" to R.string.game_tetris_name,
    "Backgammon" to R.string.game_backgammon_name,
    "Whiteboard" to R.string.game_whiteboard_name,
)

private val GAME_DESC_RES: Map<String, Int> = mapOf(
    "Play classic Tic Tac Toe with a friend!" to R.string.game_tic_tac_toe_desc,
    "Guess the words faster than your friend!" to R.string.game_wordle_desc_you_singular,
    "Take control of the board by flipping your opponent's disks!" to R.string.game_reversi_desc_you_singular,
    "Roll five dice for high-scoring combinations!" to R.string.game_yahtzee_desc,
    "Find and sink your opponent's fleet before they sink yours!" to R.string.game_battleships_desc_you_singular,
    "The classic game of strategy and tactics!" to R.string.game_chess_desc,
    "Form mills of three pieces to capture your opponent's men!" to R.string.game_nine_mens_morris_desc_you_singular,
    "Drop your discs and be the first to connect four in a row!" to R.string.game_connect4_desc_you_singular,
    "Match pairs of cards in this classic memory game!" to R.string.game_memory_desc,
    "Clear lines and outlast your opponent in head-to-head Tetris!" to R.string.game_tetris_desc_you_singular,
    "Roll the dice and bear off all your checkers first!" to R.string.game_backgammon_desc_you_singular,
    "Draw together in real time!" to R.string.game_whiteboard_desc,
)

fun Context.localizeGameName(name: String): String = resources.localizeGameName(name)

private fun Resources.localizeGameName(name: String): String =
    GAME_NAME_RES[name]?.let(::getString) ?: name

@Composable
fun localizedGameName(name: String): String =
    GAME_NAME_RES[name]?.let { stringResource(it) } ?: name

@Composable
fun localizedGameDescription(description: String): String =
    GAME_DESC_RES[description]?.let { stringResource(it) } ?: description

@Composable
fun localizedGameCategoryName(name: String): String =
    GAME_CATEGORY_NAME_RES[name]?.let { stringResource(it) } ?: name

@Composable
fun localizedGameCategoryDescription(description: String): String =
    GAME_CATEGORY_DESC_RES[description]?.let { stringResource(it) } ?: description

private val EXACT_USER_MESSAGE_RES: Map<String, Int> = mapOf(
    // Contact & Group validation and management
    "Enter a valid instant messaging email address." to R.string.err_contact_invalid_email,
    "You cannot add yourself as a contact." to R.string.err_contact_add_self_you_singular,
    "This contact is blocked. Unblock them from Blocked contacts first." to R.string.err_contact_blocked_unblock_first,
    "This person is already in your contacts." to R.string.err_contact_already_exists_you_singular,
    "Could not erase this contact's saved history. Please try again." to R.string.err_history_erase_failed,
    "Sending was interrupted. Delivery could not be confirmed." to R.string.err_sending_interrupted,
    "Voice clip download was interrupted." to R.string.err_voice_clip_download_interrupted,
    "Wink download was interrupted." to R.string.err_wink_download_interrupted,
    "Enter a name for your group." to R.string.err_group_enter_name_you_singular,
    "Enter a group name without control characters." to R.string.err_group_name_control_chars,
    "This person already has a pending group invitation." to R.string.err_group_pending_invite,
    "This person is already a group member." to R.string.err_group_already_member,
    "Group invitations are unavailable." to R.string.requests_group_invitations_unavailable,
    "Choose at least one person to invite." to R.string.group_invite_at_least_one,

    // Emotion sound errors
    "Online emotion sounds are not configured for this service." to R.string.emotion_sound_not_configured,
    "Invalid emotion sounds service URL." to R.string.err_emotion_sound_invalid_url,
    "This sound is no longer available on the server." to R.string.err_emotion_sound_unavailable_server,
    "No sounds were found for this category and language." to R.string.err_emotion_sound_none_found,
    "The sound download is too large." to R.string.err_emotion_sound_download_too_large,
    "Your saved sounds library is full." to R.string.err_emotion_sound_library_full_you_singular,
    "The sound could not be saved." to R.string.emotion_sound_save_failed,
    "The sound could not be removed." to R.string.err_emotion_sound_remove_failed,
    "Invalid sound ID." to R.string.err_emotion_sound_invalid_id,
    "Invalid or oversized emotion sound." to R.string.err_emotion_sound_invalid_or_oversized,
    "The server did not return an MP3 sound." to R.string.err_emotion_sound_not_mp3,
    "The sound's Plus! metadata is missing." to R.string.err_emotion_sound_missing_metadata,
    "The downloaded sound ID does not match." to R.string.err_emotion_sound_id_mismatch,
    "The downloaded sound failed its integrity check." to R.string.err_emotion_sound_integrity_failed,

    // Display picture errors & inline decoding
    "That image could not be opened." to R.string.display_picture_select_cant_open,
    "Choose a PNG, JPEG, BMP, or GIF picture. Animated pictures must use GIF." to R.string.display_picture_select_unsupported_format,
    "This animation is too complex to resize safely. Choose a smaller GIF." to R.string.display_picture_select_complex_gif,
    "This GIF could not be decoded." to R.string.display_picture_select_cant_decode_gif,
    "This image is damaged or could not be decoded." to R.string.display_picture_select_damaged,
    "Animated PNG pictures are not supported by Messenger. Choose an animated GIF." to R.string.display_picture_select_apng_unsupported,
    "The signed-in account changed. Please try again." to R.string.display_picture_sync_account_changed,
    "The latest picture could not be checked. Try again." to R.string.display_picture_sync_check_failed,
    "The display picture could not be updated. Try again." to R.string.display_picture_sync_update_failed,
    "Unsupported inline display-picture encoding." to R.string.display_picture_inline_unsupported,
    "Inline display picture is too large." to R.string.display_picture_inline_too_large,
    "Invalid inline display picture." to R.string.display_picture_inline_invalid,
    "GIF is too large." to R.string.display_picture_gif_too_large,

    // Received transfer storage & file sharing
    "The transfer could not be saved." to R.string.received_transfer_save_failed,
    "The received original is no longer available." to R.string.received_transfer_original_unavailable,
    "The file name is unsupported." to R.string.file_sharing_unsupported_name,
    "Executable files cannot be shared." to R.string.file_sharing_executable_unsupported,
    "The original is not a supported photo." to R.string.received_transfer_unsupported_photo,
    "Folder access was lost. Choose the photo folder again in File & photo transfers." to R.string.received_transfer_photo_folder_lost,
    "Folder access was lost. Choose the file folder again in File & photo transfers, or use Save as." to R.string.received_transfer_file_folder_lost,
    "The selected folder is unavailable." to R.string.received_transfer_selected_folder_unavailable,
    "The default folder is unavailable." to R.string.received_transfer_default_folder_unavailable,
    "Allow storage access to save to the default folder on this Android version, or choose a custom folder." to R.string.received_transfer_default_access_needed,
    "File transfer was interrupted." to R.string.err_file_transfer_interrupted,
    "This file is unavailable." to R.string.file_sharing_unavailable,
    "File transfer timed out." to R.string.err_file_timed_out,
    "The selected file is unavailable." to R.string.err_file_selected_unavailable,
    "Too many active file transfers." to R.string.err_file_too_many_active,
    "File invitation could not be sent." to R.string.err_file_invite_send_failed,
    "The other client could not receive this file." to R.string.err_file_peer_cant_receive,
    "This original photo cannot be saved." to R.string.err_photo_original_cannot_save,
    "Invalid original photo hash." to R.string.err_photo_original_invalid_hash,
    "File could not be sent." to R.string.err_file_send_failed,
    "Not enough storage space." to R.string.err_storage_not_enough_space,
    "File could not be received." to R.string.err_file_receive_failed,
    "The received file was incomplete or invalid." to R.string.err_file_incomplete_invalid,
    "The conversation disconnected." to R.string.err_conversation_disconnected,

    // Auth, session, repository, and storage service
    "Email and password are required." to R.string.err_auth_email_password_required,
    "The community server did not accept this sign-in." to R.string.err_auth_server_rejected_signin,
    "The group authentication ticket is missing." to R.string.err_auth_missing_circle_ticket,
    "This session was signed out during sign-in." to R.string.err_auth_signed_out_during_signin,
    "Sign in before changing your display picture." to R.string.err_dp_sign_in_required_you_singular,
    "The signed-in session changed. Please try again." to R.string.err_session_changed_retry,
    "Picture updated for this session. Your service couldn't save it for future sign-ins." to R.string.err_dp_roaming_storage_issue_you_singular,
    "The service did not return a complete display picture. Try again." to R.string.err_dp_incomplete,
    "The storage service did not return a photo resource ID." to R.string.err_storage_missing_photo_id,
    "The storage service did not return an expression profile resource ID." to R.string.err_storage_missing_expression_id,
    "The service did not remove the roaming picture. Your local picture was kept. Try again." to R.string.err_dp_roaming_remove_failed_you_singular,
    "The storage service did not return a profile resource ID." to R.string.err_storage_missing_profile_id,
    "The storage profile has no photo resource ID." to R.string.err_storage_no_photo_id,
    "The display picture is empty." to R.string.err_dp_empty,
    "The storage profile has no profile resource ID." to R.string.err_storage_no_profile_id,
    "The storage profile has no expression profile resource ID." to R.string.err_storage_no_expression_id,
    "The storage profile resource ID is invalid." to R.string.err_storage_invalid_cid,
    "The storage service returned an invalid profile." to R.string.err_storage_invalid_profile,

    // Winks & Voice clips
    "This wink is already downloading. Try again shortly." to R.string.err_wink_already_downloading,
    "Sign in before retrying this wink." to R.string.err_wink_retry_sign_in,
    "The wink sender is no longer online in this group." to R.string.err_wink_sender_offline_group,
    "Unblock this contact before retrying." to R.string.err_wink_unblock_before_retry,
    "The selected account changed. Try again." to R.string.wink_account_changed,
    "Winks require the contact to be online." to R.string.err_wink_require_online,
    "All online group members must support winks." to R.string.err_wink_group_unsupported,
    "This contact's client does not support winks." to R.string.err_wink_client_unsupported,
    "Wink data does not match its metadata." to R.string.err_wink_metadata_mismatch,
    "Invalid wink package." to R.string.err_wink_invalid_package,
    "Unsupported or invalid wink package." to R.string.err_wink_unsupported_or_invalid,
    "Wink download timed out." to R.string.err_wink_download_timed_out,
    "This wink is no longer cached. Add it to your library to send it again." to R.string.err_wink_not_cached_send_you_singular,
    "This wink download link is not from the configured catalog." to R.string.wink_untrusted_link,
    "Invalid download redirect." to R.string.wink_invalid_redirect,
    "Untrusted wink download redirect." to R.string.wink_untrusted_redirect,
    "Wink package is too large." to R.string.wink_too_large,
    "Too many download redirects." to R.string.wink_too_many_redirects,
    "Default winks cannot be deleted." to R.string.err_wink_default_delete,
    "Could not delete wink." to R.string.wink_delete_failed,
    "Your wink library is full." to R.string.err_wink_library_full_you_singular,
    "Could not save wink." to R.string.err_wink_save_failed,
    "Could not replace wink metadata." to R.string.err_wink_replace_metadata,
    "Could not save wink metadata." to R.string.err_wink_save_metadata,
    "Missing wink manifest." to R.string.err_wink_missing_manifest,
    "This package is not a wink." to R.string.err_wink_not_a_wink,
    "Missing wink animation." to R.string.err_wink_missing_animation,
    "Missing SWF." to R.string.err_wink_missing_swf,
    "SWF is too large." to R.string.err_wink_swf_too_large,
    "Missing package manifest." to R.string.err_wink_missing_pkg_manifest,
    "Unsupported content package." to R.string.err_wink_unsupported_pkg,
    "Missing wink in installer." to R.string.err_wink_missing_in_installer,
    "This installer contains no winks." to R.string.err_wink_empty_installer,
    "DTD is not allowed." to R.string.err_wink_dtd_disallowed,
    "External entities are not allowed." to R.string.err_wink_external_entities,
    "Unsafe package path." to R.string.err_wink_unsafe_path,
    "Wink package is too large or incomplete." to R.string.err_wink_too_large_or_incomplete,
    "Expected a CAB/MCO package." to R.string.err_wink_expected_cab,
    "Unsupported CAB compression." to R.string.err_wink_unsupported_compression,
    "CAB checksum mismatch." to R.string.err_wink_cab_checksum,

    "Voice clips are not supported in group chats." to R.string.err_voice_group_unsupported,
    "Voice clips require the contact to be online." to R.string.err_voice_require_online,
    "Voice clips require an online contact." to R.string.err_voice_require_online_alt,
    "This contact's client does not support voice clips." to R.string.err_voice_client_unsupported,
    "Invalid voice clip." to R.string.err_voice_invalid,
    "Voice clip could not be downloaded." to R.string.err_voice_download_failed,
    "Voice clip could not be encoded." to R.string.err_voice_encode_failed,
    "Voice clip could not be played." to R.string.voice_clip_cant_play,

    // Messaging, photos, games, nudges, handwriting
    "The original photo could not be requested." to R.string.err_photo_original_request_failed,
    "This contact is unavailable for photo sharing." to R.string.err_photo_contact_unavailable,
    "This contact's client does not support photo sharing." to R.string.err_photo_client_unsupported,
    "This photo share has ended." to R.string.err_photo_share_ended,
    "Wait for the photo invitation to be accepted." to R.string.err_photo_wait_invite,
    "These photos exceed the sharing session's preview limit." to R.string.err_photo_preview_limit,
    "Photo invitation could not be sent." to R.string.err_photo_invite_send_failed,
    "The photo could not be received. Select it to retry." to R.string.err_photo_receive_retry,
    "Photo sharing invitation timed out." to R.string.err_photo_invite_timed_out,
    "This game session has ended." to R.string.err_game_session_ended,
    "This contact is unavailable for games and activities." to R.string.err_game_contact_unavailable,
    "This contact's client does not support games and activities." to R.string.err_game_client_unsupported,
    "Wait for the current game invitation to be answered." to R.string.err_game_wait_current_invite,
    "Game invitation could not be sent." to R.string.err_game_invite_send_failed,
    "Game invitation timed out." to R.string.err_game_invite_timed_out,
    "Sign in before sharing files." to R.string.err_file_sign_in_required,
    "File transfers are only available in one-to-one chats." to R.string.err_file_group_unsupported,
    "This contact is unavailable for file transfers." to R.string.err_file_contact_unavailable,
    "This contact's client does not support file transfers." to R.string.err_file_client_unsupported,
    "This file offer has ended." to R.string.err_file_offer_ended,
    "Invalid file name or size." to R.string.err_file_invalid_name_or_size,
    "The Messenger session is not connected." to R.string.err_session_not_connected,
    "Messages cannot be sent to a blocked contact." to R.string.err_message_blocked_contact,
    "All online group members must support handwriting." to R.string.err_handwriting_group_unsupported,
    "Handwriting messages require the contact to be online." to R.string.err_handwriting_require_online,
    "This contact's client does not support handwriting." to R.string.err_handwriting_client_unsupported,
    "Cannot encode an empty handwriting drawing." to R.string.err_ink_empty,
    "Message could not be sent." to R.string.err_message_send_failed,
    "Nudges cannot be sent to an offline contact." to R.string.err_nudge_offline,
    "Nudges require an online contact." to R.string.err_nudge_require_online,
    "Nudges cannot be sent to a blocked contact." to R.string.err_nudge_blocked,
    "All online group members must support nudges." to R.string.err_nudge_group_unsupported,
    "This contact's client does not support nudges." to R.string.err_nudge_client_unsupported,
    "Nudge could not be sent." to R.string.chat_nudge_failed,

    // Contacts, groups, requests, sessions, switchboard, config, social
    "Sign in before adding a contact." to R.string.err_contact_add_sign_in,
    "This session ended while adding the contact." to R.string.err_contact_add_session_ended,
    "The contact was saved, but the connection timed out. Reconnect to refresh your contacts." to R.string.err_contact_saved_timeout_you_singular,
    "The contact was saved, but this session could not update it. Reconnect to refresh your contacts." to R.string.err_contact_saved_session_you_singular,
    "Sign in before deleting a contact." to R.string.err_contact_delete_sign_in,
    "You cannot delete yourself as a contact." to R.string.err_contact_delete_self_you_singular,
    "Groups cannot be deleted with this action." to R.string.err_contact_delete_group,
    "This contact is no longer in your contacts." to R.string.err_contact_not_in_list_you_singular,
    "This session ended while deleting the contact." to R.string.err_contact_delete_session_ended,
    "The contact identifier is unavailable. Reconnect and try again." to R.string.err_contact_id_unavailable,
    "The connection timed out while deleting the contact. Reconnect and check your contacts before trying again." to R.string.err_contact_delete_timeout_you_singular,
    "The contact was blocked, but could not be deleted. Please try again." to R.string.err_contact_blocked_not_deleted,
    "The contact was deleted, but the connection timed out. Reconnect to refresh your contacts." to R.string.err_contact_deleted_timeout_you_singular,
    "The contact was deleted, but this session could not update it. Reconnect to refresh your contacts." to R.string.err_contact_deleted_session_you_singular,
    "Sign in before creating a group." to R.string.err_group_create_sign_in,
    "This session ended while creating the group." to R.string.err_group_create_session_ended,
    "Sign in before inviting group members." to R.string.err_group_invite_sign_in,
    "This session ended while inviting group members." to R.string.err_group_invite_session_ended,
    "This group is no longer available." to R.string.err_group_unavailable,
    "Only owners and administrators can invite group members." to R.string.err_group_invite_permission,
    "Sign in before leaving a group." to R.string.err_group_leave_sign_in,
    "Group owners cannot leave their group." to R.string.err_group_owner_cannot_leave,
    "Your group member identifier is unavailable. Refresh and try again." to R.string.err_group_member_id_unavailable_you_singular,
    "Sign in to load group invitations." to R.string.err_group_invitations_sign_in,
    "Sign in before responding to a group invitation." to R.string.err_group_invitation_respond_sign_in,
    "This group invitation has already been handled or withdrawn." to R.string.err_group_invitation_handled_withdrawn,
    "This group invitation has already been handled." to R.string.err_group_invitation_handled,
    "This session ended while updating the group." to R.string.err_group_update_session_ended,
    "Sign in to load contact requests." to R.string.err_requests_sign_in,
    "This session ended while loading requests." to R.string.err_requests_session_ended,
    "Sign in to load blocked contacts." to R.string.err_blocked_sign_in,
    "This session ended while loading blocked contacts." to R.string.err_blocked_session_ended,
    "Sign in before responding to a contact request." to R.string.err_request_respond_sign_in,
    "An accepted contact cannot be blocked." to R.string.err_request_accept_cannot_block,
    "This session ended while responding to the request." to R.string.err_request_respond_session_ended,
    "This contact request has already been handled or withdrawn." to R.string.err_request_handled_withdrawn,
    "This contact request has already been handled." to R.string.err_request_handled,
    "You cannot respond to a request from yourself." to R.string.err_request_self_you_singular,
    "The connection timed out while responding. Refresh requests before trying again." to R.string.err_request_respond_timeout,
    "Sign in before changing group blocking." to R.string.err_group_blocking_sign_in,
    "This session ended while changing the contact." to R.string.err_contact_change_session_ended,
    "The notification server rejected the contact-list change." to R.string.err_ns_rejected_list_change,
    "Use local logout to end the current session." to R.string.err_remote_signout_use_local,
    "This session has already ended." to R.string.err_session_already_ended,
    "The server did not provide the active sessions. Please try again." to R.string.err_sessions_unavailable,
    "The selected server does not support MSNP18." to R.string.err_server_no_msnp18,
    "The community server returned an unsupported redirect." to R.string.err_server_unsupported_redirect,
    "The community server returned an invalid redirect." to R.string.err_server_invalid_redirect,
    "The community server returned an invalid SSO challenge." to R.string.err_server_invalid_sso,
    "The SSO policy was missing." to R.string.err_sso_missing_policy,
    "The SSO nonce was missing." to R.string.err_sso_missing_nonce,
    "The community server redirected too many times." to R.string.err_server_too_many_redirects,
    "The notification server disconnected." to R.string.err_ns_disconnected,
    "You were logged out from another session." to R.string.err_logged_out_other_session_you_singular,
    "The server is restarting." to R.string.err_server_restarting,
    "The server ended this session. Please sign in again." to R.string.err_server_ended_session,
    "You are no longer a member of this group." to R.string.err_group_no_longer_member_you_singular,
    "The notification server returned an invalid switchboard." to R.string.err_ns_invalid_sb,
    "The switchboard endpoint was missing." to R.string.err_sb_missing_endpoint,
    "The switchboard authentication key was missing." to R.string.err_sb_missing_key,
    "The notification server rejected the contact list." to R.string.err_ns_rejected_list,
    "The community server rejected these credentials." to R.string.err_msnp_rejected_credentials,
    "The community server is unavailable." to R.string.err_msnp_server_unavailable,
    "The authentication service rejected these credentials." to R.string.err_sso_rejected_credentials,
    "The authentication service did not return a Messenger credential." to R.string.err_sso_missing_messenger,
    "The authentication service did not return an address-book credential." to R.string.err_sso_missing_ab,
    "The authentication service did not return a storage credential." to R.string.err_sso_missing_storage,
    "The authentication service did not return an offline-message credential." to R.string.err_sso_missing_oim,
    "Could not authenticate with the community service." to R.string.err_sso_could_not_authenticate,
    "The authentication service returned an invalid response." to R.string.err_sso_invalid_response,
    "Creating groups is not supported by this client." to R.string.err_client_no_create_group,
    "Group invitations are not supported by this client." to R.string.err_client_no_group_invites,
    "Leaving groups is not supported by this client." to R.string.err_client_no_leave_group,
    "Session management is not supported by this client." to R.string.err_client_no_sessions,
    "Message retry is not supported." to R.string.err_client_no_retry,
    "Handwriting messages are not supported." to R.string.err_client_no_ink,
    "Photo sharing is unavailable." to R.string.err_client_no_photos,
    "Games and activities are unavailable." to R.string.err_client_no_games,
    "File transfers are unavailable." to R.string.err_client_no_files,
    "Voice clips are not supported." to R.string.err_client_no_voice,
    "Winks are unavailable." to R.string.err_client_no_winks,
    "Emotion sounds are unavailable." to R.string.chat_error_emotion_sounds_unavailable,
    "Voice clips are unavailable." to R.string.chat_error_voice_clips_unavailable,
    "Allow microphone access to record a voice clip." to R.string.voice_clip_mic_permission,
    "A microphone is not available." to R.string.voice_clip_mic_unavailable,
    "Microphone could not be opened." to R.string.voice_clip_mic_open_failed,
    "Microphone could not be started." to R.string.voice_clip_mic_start_failed,
    "Microphone recording failed." to R.string.voice_clip_mic_recording_failed,
    "Audio playback failed." to R.string.voice_clip_audio_playback_failed,
    "Adding contacts is not supported by this client." to R.string.err_client_no_add_contact,
    "Deleting contacts is not supported by this client." to R.string.err_client_no_delete_contact,
    "Contact requests are not supported by this client." to R.string.err_client_no_contact_requests,
    "This contact is no longer available." to R.string.err_contact_unavailable,
    "Connection interrupted" to R.string.err_repo_connection_interrupted,
    "The previous sign-in session is no longer available." to R.string.err_repo_previous_session_unavailable,
    "Sign in before updating groups." to R.string.err_repo_group_update_sign_in,
    "Could not load the Messenger contact list." to R.string.err_ab_load_failed,
    "The contact service returned an invalid response." to R.string.err_contact_service_invalid,
    "No Messenger account was found for this email address." to R.string.err_contact_account_not_found,
    "The server could not add this contact. Please try again." to R.string.err_contact_add_server_retry,
    "The contact identifier is invalid." to R.string.err_contact_id_invalid,
    "The server could not update this contact. Please try again." to R.string.err_contact_update_server_retry,
    "The server could not update this contact request. Please try again." to R.string.err_request_update_server_retry,
    "The address-book service rejected the request." to R.string.err_ab_service_rejected,
    "The address-book service returned an invalid response." to R.string.err_ab_service_invalid,
    "The membership service rejected the request." to R.string.err_membership_rejected,
    "The membership service returned an invalid response." to R.string.err_membership_invalid,
    "A circle is required to change group blocking." to R.string.err_circle_required,
    "Invalid configuration URL." to R.string.err_config_invalid_url,
    "The server could not create this group. Please refresh and try again." to R.string.err_group_create_retry,
    "The server could not leave this group. Please refresh and try again." to R.string.err_group_leave_retry,
    "The server could not update this group invitation. Please refresh and try again." to R.string.err_group_invite_update_retry,
    "The group service returned an invalid response." to R.string.err_group_service_invalid,
    "The group service returned an invalid group identifier." to R.string.err_group_service_invalid_id,
    "The group service returned an invalid member identifier." to R.string.err_group_service_invalid_member_id,
    "Could not load the current group members." to R.string.err_group_members_load_failed,
    "The offline message recipient is invalid." to R.string.err_oim_recipient_invalid,
    "The switchboard has no conversation contact." to R.string.err_sb_no_contact,
    "The switchboard is closed." to R.string.err_sb_closed,
    "This game invitation has ended." to R.string.err_game_invite_ended,
    "The conversation is disconnected. Try again." to R.string.err_sb_disconnected_retry,
    "The wink sender does not match this conversation." to R.string.err_wink_sender_mismatch,
    "The message is too long for an MSNP switchboard." to R.string.err_sb_message_too_long,
    "The switchboard rejected a P2P packet." to R.string.err_sb_p2p_rejected,
    "Social service host is not configured." to R.string.err_social_host_not_configured,
    "Social service authentication ticket is missing." to R.string.err_social_ticket_missing,
    "The social feed service rejected this request." to R.string.err_social_rejected,
    "MSNP socket is not connected" to R.string.err_socket_not_connected,
    "MSNP socket closed" to R.string.err_socket_closed,

    // Updates
    "GitHub's request limit was reached. Please try again later." to R.string.update_github_rate_limit,
    "GitHub returned too many release pages. Please check the releases page." to R.string.update_github_too_many_pages,
    "The release information is too large." to R.string.update_release_info_too_large,
    "The release changed. Please check for updates again." to R.string.update_worker_release_changed,
    "The APK size is invalid." to R.string.update_apk_invalid_size,
    "The downloaded APK has an unexpected size." to R.string.update_apk_unexpected_size,
    "The APK download was incomplete. Please try again." to R.string.update_apk_incomplete,
    "The APK could not be saved in temporary storage." to R.string.update_apk_temp_save_failed,
    "The update could not be downloaded." to R.string.update_download_failed_generic,
    "GitHub returned an unsupported download address." to R.string.update_apk_unsupported_address,
    "GitHub redirected the download too many times." to R.string.update_apk_too_many_redirects,
    "The APK checksum does not match GitHub's release asset." to R.string.update_apk_checksum_mismatch,
    "The downloaded file is not a valid signed APK." to R.string.update_apk_not_valid_signed,
    "The APK does not match this app and release version." to R.string.update_apk_wrong_package,
    "This update needs a newer Android version." to R.string.update_apk_min_sdk_higher,
    "The update was signed with a different key and cannot update this installation." to R.string.update_apk_wrong_signature,

    // Chat system & fallback literals
    "Handwriting" to R.string.chat_handwriting_message,
    "Voice clip" to R.string.chat_voice_clip_message,
    "You sent a nudge." to R.string.chat_nudge_sent_you_singular,
    "You received a nudge!" to R.string.chat_nudge_received_you_singular,
    "Photo sharing invitation sent." to R.string.chat_photo_invite_sent,
    "Photo sharing invitation received." to R.string.chat_photo_invite_received,
    "The other person stopped sharing photos." to R.string.photo_sharing_stopped_remote,
    "You stopped sharing photos." to R.string.photo_sharing_stopped_local_you_singular,
    "Photo sharing invitation declined." to R.string.photo_sharing_declined,
    "Photo sharing failed." to R.string.photo_sharing_failed,
    "Group" to R.string.common_group_singular,
    "You" to R.string.common_you_singular,
    "Site" to R.string.common_site_fallback,
    "Android" to R.string.common_device_android,
    "This device" to R.string.common_device_this,
    "Unnamed device" to R.string.common_device_unnamed,
    "Announcement" to R.string.common_announcement,
    "Contact" to R.string.common_contact_singular,
    "Wink" to R.string.wink_fallback_name_singular,
    "Emotion sound" to R.string.emotion_sound_fallback_name_singular,
    "Mock Profile" to R.string.mock_profile_name,
    "Testing with mock data" to R.string.mock_profile_status,
    "Mock contact \u00B7 Test data only" to R.string.mock_contact_status_data,
    "I love MSN!" to R.string.mock_status_i_love_msn,
    "Heads down today" to R.string.mock_status_heads_down,
    "At the studio" to R.string.mock_status_at_studio,
    "Ping me later" to R.string.mock_status_ping_later,
    "Coffee first" to R.string.mock_status_coffee_first,
    "Shipping build assets" to R.string.mock_status_shipping,
    "In a call" to R.string.mock_status_in_call,
)

private val MOCK_REPLY_RES: Map<String, Int> = mapOf(
    "Hey! Great to see you online \uD83D\uDC4B" to R.string.mock_reply_1_you_singular,
    "I think so \u2014 just like the old Messenger days." to R.string.mock_reply_2,
    "Got it! This mock reply arrived through the MSNP event stream." to R.string.mock_reply_3,
)

private val REGEX_EMOTION_SOUND_HTTP = Regex("""^The emotion sounds service returned HTTP (\d+)\.$""")
private val REGEX_WINK_HTTP = Regex("""^Wink download failed \(HTTP (\d+)\)\.$""")
private val REGEX_GITHUB_HTTP = Regex("""^GitHub could not be reached \(HTTP (\d+)\)\.$""")
private val REGEX_APK_HTTP = Regex("""^The APK download failed \(HTTP (\d+)\)\.$""")
private val REGEX_AB_HTTP = Regex("""^The address-book service returned HTTP (\d+)\.$""")
private val REGEX_CONFIG_HTTP = Regex("""^Configuration request failed with HTTP (\d+)\.$""")
private val REGEX_CLIENT_CONFIG_HTTP = Regex("""^GetClientConfig failed with HTTP (\d+)\.$""")
private val REGEX_WHATS_NEW_HTTP = Regex("""^What's New request failed with HTTP (\d+)\.$""")
private val REGEX_STORAGE_HTTP = Regex("""^Storage (.+) failed with HTTP (\d+)\.$""")
private val REGEX_STORAGE_ACTION = Regex("""^Could not complete storage (.+)\.$""")
private val REGEX_RESPONSE_BYTES = Regex("""^Response exceeded (\d+) bytes\.$""")
private val REGEX_GROUP_NAME_CHARS = Regex("""^Group names can have up to (\d+) characters\.$""")
private val REGEX_GROUP_MEMBERS_MAX = Regex("""^A group can have up to (\d+) members\.$""")
private val REGEX_GROUP_MSG_BYTES = Regex("""^Group message is too long \(maximum ([\d,]+) UTF-8 bytes\)\.$""")
private val REGEX_OIM_MSG_BYTES = Regex("""^Offline message is too long \(maximum ([\d,]+) UTF-8 bytes\)\.$""")
private val REGEX_PHOTO_SHARE_MAX = Regex("""^A photo share can contain up to (\d+) photos\.$""")
private val REGEX_GAME_DATA_MAX = Regex("""^Game data exceeds the (\d+) KB P4 packet limit\.$""")
private val REGEX_INK_CHUNKS_MAX = Regex("""^Handwriting drawing exceeds the MSNP maximum of (\d+) chunks\.$""")
private val REGEX_NUDGE_COOLDOWN = Regex("""^You may not send a nudge that often\. Please wait (\d+) seconds\.$""")
private val REGEX_REMOTE_SIGNOUT_MSNP = Regex("""^The server could not deliver remote sign-out \(MSNP (\d+)\)\. If all sessions appear offline, change your status to Online and try again\.$""")
private val REGEX_DP_MIB = Regex("""^Select an image smaller than (\d+) MiB\.$""")
private val REGEX_DP_MP = Regex("""^Choose an image of at most (\d+) megapixels\.$""")
private val REGEX_DP_KB_ANIMATED = Regex("""^This picture cannot fit Messenger's (\d+) KB limit while preserving its animation\.$""")
private val REGEX_DP_GIF_LIMITS = Regex("""^This GIF is damaged or exceeds the (\d+)-frame or (\d+)-megapixel safety limit\.$""")
private val REGEX_VOICE_MAX_SECONDS = Regex("""^Record up to (\d+) seconds of audio\.$""")
private val REGEX_MSNP_SERVER_ERROR = Regex("""^The community server returned MSNP error (.+)\.$""")
private val REGEX_MSNP_SWITCHBOARD_ERROR = Regex("""^The switchboard returned MSNP error (.+)\.$""")
private val REGEX_LOGOUT_NOT_CONFIRMED = Regex("""^(.+) did not confirm logout\. Please try again\.$""")
private val REGEX_REMOTE_SIGNOUT_FAILED = Regex("""^Could not sign you out of all other places\. ?(.*)$""")
private val REGEX_WINK_OPEN_FAILED = Regex("""^Wink could not be opened: (.+)$""")
private val REGEX_NUDGE_FAILED_REASON = Regex("""^Nudge could not be sent: (.+)$""")
private val REGEX_NUDGE_FROM = Regex("""^(.+) sent a nudge!$""")
private val REGEX_GROUP_OFFLINE = Regex("""^(.+) is currently offline\. You can send a message when more group members are online\.$""")
private val REGEX_WINK_MSG = Regex("""^Wink: (.+)$""")
private val REGEX_SOUND_MSG = Regex("""^Sent a sound: (.+)$""")
private val REGEX_FILE_MSG = Regex("""^File: (.+)$""")
private val REGEX_GAME_INVITE_SENT = Regex("""^Game invitation sent: (.+)\.$""")
private val REGEX_GAME_INVITE_RECEIVED = Regex("""^Game invitation received: (.+)\.$""")
private val REGEX_GAME_CLOSED_REMOTE = Regex("""^The other person closed (.+)\.$""")
private val REGEX_GAME_CLOSED_LOCAL = Regex("""^You closed (.+)\.$""")
private val REGEX_GAME_DECLINED = Regex("""^(.+) invitation declined\.$""")
private val REGEX_GAME_FAILED = Regex("""^(.+) failed\.$""")

fun Context.localizeUserMessage(message: String): String = resources.localizeUserMessage(message)

fun Resources.localizeUserMessage(message: String): String {
    EXACT_USER_MESSAGE_RES[message]?.let { return getString(it) }
    REGEX_EMOTION_SOUND_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.emotion_sound_service_http_error_before_number, it.groupValues[1], R.string.emotion_sound_service_http_error_after_number)
    }
    REGEX_WINK_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.wink_download_http_error_before_number, it.groupValues[1], R.string.wink_download_http_error_after_number)
    }
    REGEX_GITHUB_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.update_github_http_error_before_number, it.groupValues[1], R.string.update_github_http_error_after_number)
    }
    REGEX_APK_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.update_apk_download_http_error_before_number, it.groupValues[1], R.string.update_apk_download_http_error_after_number)
    }
    REGEX_AB_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.msnp_address_book_http_error_before_number, it.groupValues[1], R.string.msnp_address_book_http_error_after_number)
    }
    REGEX_CONFIG_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.msnp_config_http_error_before_number, it.groupValues[1], R.string.msnp_config_http_error_after_number)
    }
    REGEX_CLIENT_CONFIG_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.msnp_get_client_config_http_error_before_number, it.groupValues[1], R.string.msnp_get_client_config_http_error_after_number)
    }
    REGEX_WHATS_NEW_HTTP.matchEntire(message)?.let {
        return getNumberSentence(R.string.msnp_whats_new_http_error_before_number, it.groupValues[1], R.string.msnp_whats_new_http_error_after_number)
    }
    REGEX_STORAGE_HTTP.matchEntire(message)?.let {
        val statusText = getNumberSentence(R.string.msnp_http_status_before_number, it.groupValues[2], R.string.msnp_http_status_after_number)
        return getString(R.string.msnp_storage_action_http_error, it.groupValues[1], statusText)
    }
    REGEX_STORAGE_ACTION.matchEntire(message)?.let {
        return getString(R.string.msnp_storage_action_failed, it.groupValues[1])
    }
    REGEX_RESPONSE_BYTES.matchEntire(message)?.let {
        return getNumberSentence(R.string.msnp_response_exceeded_bytes_before_number, it.groupValues[1], R.string.msnp_response_exceeded_bytes_after_number)
    }
    REGEX_GROUP_NAME_CHARS.matchEntire(message)?.let {
        return getNumberSentence(R.string.group_name_max_chars_before_number, it.groupValues[1], R.string.group_name_max_chars_after_number)
    }
    REGEX_GROUP_MEMBERS_MAX.matchEntire(message)?.let {
        return getNumberSentence(R.string.group_members_max_count_before_number, it.groupValues[1], R.string.group_members_max_count_after_number)
    }
    REGEX_GROUP_MSG_BYTES.matchEntire(message)?.let {
        val num = it.groupValues[1].replace(",", "").toIntOrNull()?.let(::formatLocalizedInt) ?: it.groupValues[1]
        return getNumberSentence(R.string.msnp_group_message_too_long_before_number, num, R.string.msnp_group_message_too_long_after_number)
    }
    REGEX_OIM_MSG_BYTES.matchEntire(message)?.let {
        val num = it.groupValues[1].replace(",", "").toIntOrNull()?.let(::formatLocalizedInt) ?: it.groupValues[1]
        return getNumberSentence(R.string.msnp_offline_message_too_long_before_number, num, R.string.msnp_offline_message_too_long_after_number)
    }
    REGEX_PHOTO_SHARE_MAX.matchEntire(message)?.let {
        return getNumberSentence(R.string.photo_share_max_count_before_number, it.groupValues[1], R.string.photo_share_max_count_after_number)
    }
    REGEX_GAME_DATA_MAX.matchEntire(message)?.let {
        return getNumberSentence(R.string.game_data_limit_before_number, it.groupValues[1], R.string.game_data_limit_after_number)
    }
    REGEX_INK_CHUNKS_MAX.matchEntire(message)?.let {
        return getNumberSentence(R.string.handwriting_chunks_max_before_number, it.groupValues[1], R.string.handwriting_chunks_max_after_number)
    }
    REGEX_NUDGE_COOLDOWN.matchEntire(message)?.let {
        val secs = it.groupValues[1].toIntOrNull() ?: 1
        return getQuantityNumberSentence(
            secs,
            R.string.nudge_rate_limit_singular_you_singular_before_number,
            R.string.nudge_rate_limit_singular_you_singular_after_number,
            R.string.nudge_rate_limit_plural_you_singular_before_number,
            R.string.nudge_rate_limit_plural_you_singular_after_number,
        )
    }
    REGEX_REMOTE_SIGNOUT_MSNP.matchEntire(message)?.let {
        return getNumberSentence(R.string.remote_sign_out_msnp_error_you_singular_before_number, it.groupValues[1], R.string.remote_sign_out_msnp_error_you_singular_after_number)
    }
    REGEX_DP_MIB.matchEntire(message)?.let {
        return getNumberSentence(R.string.display_picture_select_max_mib_before_number, it.groupValues[1], R.string.display_picture_select_max_mib_after_number)
    }
    REGEX_DP_MP.matchEntire(message)?.let {
        return getNumberSentence(R.string.display_picture_select_max_mp_before_number, it.groupValues[1], R.string.display_picture_select_max_mp_after_number)
    }
    REGEX_DP_KB_ANIMATED.matchEntire(message)?.let {
        return getNumberSentence(R.string.display_picture_select_max_kb_animated_before_number, it.groupValues[1], R.string.display_picture_select_max_kb_animated_after_number)
    }
    REGEX_DP_GIF_LIMITS.matchEntire(message)?.let {
        return getTwoNumbersSentence(
            R.string.display_picture_gif_limits_before_first_number,
            it.groupValues[1],
            R.string.display_picture_gif_limits_between_numbers,
            it.groupValues[2],
            R.string.display_picture_gif_limits_after_second_number,
        )
    }
    REGEX_VOICE_MAX_SECONDS.matchEntire(message)?.let {
        return getNumberSentence(R.string.voice_clip_max_seconds_before_number, it.groupValues[1], R.string.voice_clip_max_seconds_after_number)
    }
    REGEX_MSNP_SERVER_ERROR.matchEntire(message)?.let {
        return getString(R.string.msnp_server_returned_error, it.groupValues[1])
    }
    REGEX_MSNP_SWITCHBOARD_ERROR.matchEntire(message)?.let {
        return getString(R.string.msnp_switchboard_returned_error, it.groupValues[1])
    }
    REGEX_LOGOUT_NOT_CONFIRMED.matchEntire(message)?.let {
        return getString(R.string.sessions_logout_not_confirmed, localizeUserMessage(it.groupValues[1]))
    }
    REGEX_REMOTE_SIGNOUT_FAILED.matchEntire(message)?.let {
        val reason = it.groupValues[1].trim()
        val localized = if (reason.isEmpty()) "" else " ${localizeUserMessage(reason)}"
        return getString(R.string.settings_remote_sign_out_failed_you_singular, localized)
    }
    REGEX_WINK_OPEN_FAILED.matchEntire(message)?.let {
        return getString(R.string.wink_open_failed, localizeUserMessage(it.groupValues[1]))
    }
    REGEX_NUDGE_FAILED_REASON.matchEntire(message)?.let {
        return getString(R.string.chat_nudge_failed_with_reason, localizeUserMessage(it.groupValues[1]))
    }
    REGEX_NUDGE_FROM.matchEntire(message)?.let {
        return getString(R.string.chat_nudge_received_from, it.groupValues[1])
    }
    REGEX_GROUP_OFFLINE.matchEntire(message)?.let {
        return getString(R.string.chat_group_offline_you_singular, it.groupValues[1])
    }
    REGEX_WINK_MSG.matchEntire(message)?.let {
        return getString(R.string.chat_wink_message, localizeWinkName(it.groupValues[1]))
    }
    REGEX_SOUND_MSG.matchEntire(message)?.let {
        return getString(R.string.chat_emotion_sound_message, localizeEmotionSoundName(it.groupValues[1]))
    }
    REGEX_FILE_MSG.matchEntire(message)?.let {
        return getString(R.string.chat_file_message, it.groupValues[1])
    }
    REGEX_GAME_INVITE_SENT.matchEntire(message)?.let {
        return getString(R.string.chat_game_invite_sent, localizeGameName(it.groupValues[1]))
    }
    REGEX_GAME_INVITE_RECEIVED.matchEntire(message)?.let {
        return getString(R.string.chat_game_invite_received, localizeGameName(it.groupValues[1]))
    }
    REGEX_GAME_CLOSED_REMOTE.matchEntire(message)?.let {
        return getString(R.string.chat_game_stopped_remote, localizeGameName(it.groupValues[1]))
    }
    REGEX_GAME_CLOSED_LOCAL.matchEntire(message)?.let {
        return getString(R.string.chat_game_stopped_local_you_singular, localizeGameName(it.groupValues[1]))
    }
    REGEX_GAME_DECLINED.matchEntire(message)?.let {
        return getString(R.string.chat_game_declined, localizeGameName(it.groupValues[1]))
    }
    REGEX_GAME_FAILED.matchEntire(message)?.let {
        return getString(R.string.chat_game_failed, localizeGameName(it.groupValues[1]))
    }
    EMOTION_SOUND_NUMBERED_REGEX.matchEntire(message)?.let {
        return getNumberSentence(
            R.string.emotion_sound_numbered_singular_before_number,
            it.groupValues[1],
            R.string.emotion_sound_numbered_singular_after_number,
        )
    }
    return message
}

@Composable
fun localizeUserMessage(message: String): String =
    LocalResources.current.localizeUserMessage(message)

fun Context.localizeChatMessageText(message: ChatMessage): String = resources.localizeChatMessageText(message)

private fun Resources.localizeChatMessageText(message: ChatMessage): String {
    if (message.author == MessageAuthor.System ||
        message.isNudge ||
        message.wink != null ||
        message.isInk ||
        message.isVoiceClip ||
        message.fileTransfer != null ||
        message.emotionSound != null
    ) {
        return localizeUserMessage(message.text)
    }
    if (SessionPrefs.isMockServerActive()) {
        MOCK_REPLY_RES[message.text]?.let { return getString(it) }
    }
    return message.text
}

@Composable
fun localizedChatMessageText(message: ChatMessage): String =
    LocalResources.current.localizeChatMessageText(message)
