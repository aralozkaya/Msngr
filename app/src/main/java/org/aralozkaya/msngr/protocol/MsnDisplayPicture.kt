package org.aralozkaya.msngr.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

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
) {
    val downloadUrl: String? get() = url1?.takeIf(String::isNotBlank)
        ?: url?.takeIf(String::isNotBlank)

    fun toXml(): String = buildString {
        append("<msnobj Creator=\"").append(creator.escapeMsnObjectXml())
        append("\" Size=\"").append(size)
        append("\" Type=\"").append(objectType).append("\" Location=\"").append(location.escapeMsnObjectXml())
        append("\" Friendly=\"").append(friendly.escapeMsnObjectXml())
        append("\" SHA1D=\"").append(sha1d.escapeMsnObjectXml())
        sha1c?.let { append("\" SHA1C=\"").append(it.escapeMsnObjectXml()) }
        url?.let { append("\" Url=\"").append(it.escapeMsnObjectXml()) }
        url1?.let { append("\" Url1=\"").append(it.escapeMsnObjectXml()) }
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

        fun create(creator: String, bytes: ByteArray, objectType: Int = USER_TILE_TYPE): MsnDisplayPicture {
            require(creator.isNotBlank())
            require(bytes.isNotEmpty())
            val sha1d = MsnBase64.encode(bytes.sha1())
            val location = DEFAULT_LOCATION
            val canonical = buildString {
                append("Creator").append(creator)
                append("Size").append(bytes.size)
                append("Type").append(objectType)
                append("Location").append(location)
                append("FriendlyAAA=")
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
            )
        }

        fun parse(rawValue: String?): MsnDisplayPicture? = parseObject(rawValue, USER_TILE_TYPE)

        /** Voice clips use the same MSNObject envelope, with Type=11. */
        fun parseObject(rawValue: String?, objectType: Int): MsnDisplayPicture? = runCatching {
            val raw = rawValue?.trim().orEmpty()
            if (raw.isEmpty() || raw == "0") return null
            val xml = if (raw.startsWith("<msnobj", ignoreCase = true)) {
                raw
            } else {
                URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            }
            val document = secureDocumentBuilderFactory().newDocumentBuilder()
                .parse(xml.byteInputStream(StandardCharsets.UTF_8))
            val element = document.documentElement
            if (!element.tagName.equals("msnobj", ignoreCase = true)) return null
            if (element.getAttribute("Type").toIntOrNull() != objectType) return null
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
            )
        }.getOrNull()
    }
}

private fun ByteArray.sha1(): ByteArray = MessageDigest.getInstance("SHA-1").digest(this)

private fun String.escapeMsnObjectXml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
