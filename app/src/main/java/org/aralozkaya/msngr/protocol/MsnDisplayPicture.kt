package org.aralozkaya.msngr.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** MSNP user-tile metadata advertised in CHG, ILN, and NLN commands. */
data class MsnDisplayPicture(
    val creator: String,
    val size: Int,
    val location: String,
    val sha1d: String,
    val sha1c: String? = null,
    val url: String? = null,
    val url1: String? = null,
    val objectType: Int = 3,
    val friendly: String = "AAA=",
    val extraAttributes: Map<String, String> = emptyMap(),
) {
    val downloadUrl: String? get() = url1?.takeIf(String::isNotBlank)
        ?: url?.takeIf(String::isNotBlank)

    fun toXml(): String = buildString {
        append("<msnobj Creator=\"").append(creator.escapeXml())
        append("\" Size=\"").append(size)
        append("\" Type=\"").append(objectType).append("\" Location=\"").append(location.escapeXml())
        append("\" Friendly=\"").append(friendly.escapeXml())
        append("\" SHA1D=\"").append(sha1d.escapeXml())
        sha1c?.let { append("\" SHA1C=\"").append(it.escapeXml()) }
        url?.let { append("\" Url=\"").append(it.escapeXml()) }
        url1?.let { append("\" Url1=\"").append(it.escapeXml()) }
        extraAttributes.forEach { (name, value) ->
            if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]*")) && name !in setOf("Creator", "Size", "Type", "Location", "Friendly", "SHA1D", "SHA1C", "Url", "Url1"))
                append("\" ").append(name).append("=\"").append(value.escapeXml())
        }
        append("\"/>")
    }

    fun toEncodedValue(): String = URLEncoder.encode(
        toXml(),
        StandardCharsets.UTF_8.name(),
    ).replace("+", "%20")

    fun matches(bytes: ByteArray): Boolean = bytes.isNotEmpty() &&
        bytes.size == size &&
        MsnBase64.encode(bytes.sha1()) == sha1d

    companion object {
        private const val USER_TILE_TYPE = 3
        private const val DEFAULT_LOCATION = "TFR2C2.tmp"

        fun create(creator: String, bytes: ByteArray, objectType: Int = USER_TILE_TYPE, location: String = DEFAULT_LOCATION, friendly: String = "AAA="): MsnDisplayPicture {
            require(creator.isNotBlank())
            require(bytes.isNotEmpty())
            val sha1d = MsnBase64.encode(bytes.sha1())
            val canonical = buildString {
                append("Creator").append(creator)
                append("Size").append(bytes.size)
                append("Type").append(objectType)
                append("Location").append(location)
                append("Friendly").append(friendly)
                append("SHA1D").append(sha1d)
            }
            val sha1c = MsnBase64.encode(
                canonical.toByteArray(StandardCharsets.UTF_8).sha1(),
            )
            return MsnDisplayPicture(
                creator = creator,
                size = bytes.size,
                location = location,
                sha1d = sha1d,
                sha1c = sha1c,
                objectType = objectType,
                friendly = friendly,
            )
        }

        fun parse(rawValue: String?): MsnDisplayPicture? = parseObject(rawValue, USER_TILE_TYPE)

        fun parseObject(rawValue: String?, objectType: Int): MsnDisplayPicture? =
            parseObject(rawValue)?.takeIf { it.objectType == objectType }

        internal fun parseObject(rawValue: String?): MsnDisplayPicture? = runCatching {
            val raw = rawValue?.trim().orEmpty()
            if (raw.isEmpty() || raw == "0") return null
            val xml = if (raw.startsWith("<msnobj", ignoreCase = true)) {
                raw
            } else {
                URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            }
            require(xml.length <= 64 * 1024)
            val document = parseProtocolXml(xml, namespaceAware = false)
            val element = document.documentElement
            if (!element.tagName.equals("msnobj", ignoreCase = true)) return null
            val objectType = element.getAttribute("Type").toIntOrNull() ?: return null
            val creator = element.getAttribute("Creator").trim()
            val size = element.getAttribute("Size").toIntOrNull() ?: return null
            val sha1d = element.getAttribute("SHA1D").trim()
            if (creator.isEmpty() || size <= 0 || sha1d.isEmpty()) return null
            MsnDisplayPicture(
                creator = creator,
                size = size,
                location = element.getAttribute("Location").ifBlank { DEFAULT_LOCATION },
                sha1d = sha1d,
                sha1c = element.getAttribute("SHA1C").takeIf(String::isNotBlank),
                url = element.getAttribute("Url").takeIf(String::isNotBlank),
                url1 = element.getAttribute("Url1").takeIf(String::isNotBlank),
                objectType = objectType,
                friendly = element.getAttribute("Friendly").ifBlank { "AAA=" },
                extraAttributes = (0 until element.attributes.length).map { element.attributes.item(it) }
                    .filter { it.nodeName !in setOf("Creator", "Size", "Type", "Location", "Friendly", "SHA1D", "SHA1C", "Url", "Url1") }
                    .associate { it.nodeName to it.nodeValue },
            )
        }.getOrNull()
    }
}

private fun ByteArray.sha1(): ByteArray = MessageDigest.getInstance("SHA-1").digest(this)
