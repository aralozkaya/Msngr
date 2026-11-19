package org.aralozkaya.msngr.protocol

/**
 * MSNP11-MSNP18 `<CurrentMedia>` category sent inside UUX/UBX `<Data>` XML payloads.
 *
 * Wire values are standardized by Windows Live Messenger (`Music`, `Games`, `Office`).
 */
enum class CurrentMediaType(val wireValue: String) {
    Music("Music"),
    Games("Games"),
    Office("Office"),
    Other("Other"),
    ;

    companion object {
        fun fromWire(value: String): CurrentMediaType = when {
            value.equals(Music.wireValue, ignoreCase = true) -> Music
            value.equals(Games.wireValue, ignoreCase = true) -> Games
            value.equals(Office.wireValue, ignoreCase = true) -> Office
            else -> Other
        }
    }
}

/**
 * Ephemeral "What I'm listening to?" (or playing/working on) presence metadata carried by MSNP `<CurrentMedia>`.
 */
data class CurrentMedia(
    val type: CurrentMediaType = CurrentMediaType.Music,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val appName: String = "",
    val format: String = defaultCurrentMediaFormat(title, artist),
    val displayText: String = MsnCurrentMedia.formatDisplay(format, listOf(title, artist, album)),
)

private fun defaultCurrentMediaFormat(title: String, artist: String): String =
    if (artist.isNotBlank() && title.isNotBlank()) "{0} - {1}" else "{0}"

/**
 * Codec for the MSNP `<CurrentMedia>` backslash-zero (`\0`) delimited field format:
 * `<AppName>\0<MediaType>\0<Enabled>\0<Format>\0<Field0>\0<Field1>\0<Field2>\0<ContentID>\0`
 */
object MsnCurrentMedia {
    private const val DELIMITER = "\\0"
    private val WHITESPACE = Regex("\\s+")
    private val PLACEHOLDER_REGEX = Regex("\\{(\\d+)\\}")

    /**
     * Builds a sanitized [CurrentMedia] entry for music playback, returning `null` if [title] is blank.
     */
    fun music(
        title: String,
        artist: String = "",
        album: String = "",
        appName: String = "",
    ): CurrentMedia? {
        val cleanTitle = normalizeField(title)
        if (cleanTitle.isEmpty()) return null
        val rawArtist = normalizeField(artist)
        val cleanArtist = if (rawArtist.equals(cleanTitle, ignoreCase = true)) "" else rawArtist
        val cleanAlbum = normalizeField(album)
        val cleanApp = normalizeField(appName)
        val format = defaultCurrentMediaFormat(cleanTitle, cleanArtist)
        val display = formatDisplay(format, listOf(cleanTitle, cleanArtist, cleanAlbum))
        if (display.isEmpty()) return null
        return CurrentMedia(
            type = CurrentMediaType.Music,
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            appName = cleanApp,
            format = format,
            displayText = display,
        )
    }

    /**
     * Encodes [media] into the unescaped inner text of `<CurrentMedia>...</CurrentMedia>`,
     * or returns an empty string to clear active media.
     */
    fun encode(media: CurrentMedia?): String {
        if (media == null) return ""
        val safeApp = normalizeField(media.appName)
        val safeTitle = normalizeField(media.title)
        val safeArtist = normalizeField(media.artist)
        val safeAlbum = normalizeField(media.album)
        val rawFormat = normalizeField(media.format)
        val effectiveFormat = rawFormat.ifEmpty { defaultCurrentMediaFormat(safeTitle, safeArtist) }
        val display = formatDisplay(effectiveFormat, listOf(safeTitle, safeArtist, safeAlbum))
        if (safeTitle.isEmpty() && safeArtist.isEmpty() && display.isEmpty()) return ""
        return buildString {
            append(safeApp).append(DELIMITER)
            append(media.type.wireValue).append(DELIMITER)
            append('1').append(DELIMITER)
            append(effectiveFormat).append(DELIMITER)
            append(safeTitle).append(DELIMITER)
            append(safeArtist).append(DELIMITER)
            append(safeAlbum).append(DELIMITER)
            append(DELIMITER)
        }
    }

    /**
     * Parses the XML-unescaped inner text of `<CurrentMedia>...</CurrentMedia>`.
     * Returns `null` when the payload is empty, malformed, disabled (`Enabled != "1"`), or yields no display text.
     */
    fun parse(raw: String): CurrentMedia? {
        if (raw.isBlank()) return null
        val parts = raw.split(DELIMITER)
        if (parts.size < 5) return null
        val appName = normalizeField(parts[0])
        val rawType = normalizeField(parts[1])
        if (rawType.isEmpty()) return null
        val enabled = parts[2].trim()
        if (enabled != "1") return null
        val format = normalizeField(parts[3])
        val fields = parts.drop(4).map(::normalizeField)
        val displayText = formatDisplay(format, fields)
        if (displayText.isEmpty()) return null
        return CurrentMedia(
            type = CurrentMediaType.fromWire(rawType),
            title = fields.getOrNull(0).orEmpty(),
            artist = fields.getOrNull(1).orEmpty(),
            album = fields.getOrNull(2).orEmpty(),
            appName = appName,
            format = format,
            displayText = displayText,
        )
    }

    /**
     * Formats `<Format>` placeholders (`{0}`, `{1}`, `{2}`, ...) against [fields] in a single pass.
     */
    fun formatDisplay(format: String, fields: List<String>): String {
        val cleanFormat = normalizeField(format)
        val formatted = when {
            cleanFormat.isEmpty() -> {
                fields.take(2).filter(String::isNotBlank).joinToString(" - ")
                    .ifEmpty { fields.firstOrNull(String::isNotBlank).orEmpty() }
            }
            cleanFormat == "{0} - {1}" -> {
                listOf(fields.getOrNull(0).orEmpty(), fields.getOrNull(1).orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString(" - ")
            }
            cleanFormat == "{1} - {0}" -> {
                listOf(fields.getOrNull(1).orEmpty(), fields.getOrNull(0).orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString(" - ")
            }
            else -> {
                PLACEHOLDER_REGEX.replace(cleanFormat) { match ->
                    val index = match.groupValues[1].toIntOrNull() ?: -1
                    fields.getOrNull(index).orEmpty()
                }
            }
        }
        return formatted.replace(WHITESPACE, " ").trim()
    }

    private fun normalizeField(value: String): String = value
        .replace(DELIMITER, " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\t', ' ')
        .replace('\u0000', ' ')
        .replace(WHITESPACE, " ")
        .trim()
}
