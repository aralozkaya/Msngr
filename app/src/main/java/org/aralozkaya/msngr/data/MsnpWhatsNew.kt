package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

enum class WhatsNewKind {
    Status,
    Photo,
    DisplayPicture,
    Announcement,
    Other,
}

data class WhatsNewActivity(
    val id: String,
    val kind: WhatsNewKind,
    val publisherCid: String,
    val publisherName: String,
    val publisherAvatarUrl: String? = null,
    val publisherProfileUrl: String? = null,
    val text: String = "",
    val mediaUrl: String? = null,
    val targetUrl: String? = null,
    val sourceLabel: String? = null,
    val publishedAtMillis: Long = 0L,
    val isFavorite: Boolean = false,
)

/**
 * MSNP18 / WLM 2009 "What's New" social activity feed client (`whatsnewservice.asmx`).
 */
object MsnpWhatsNewService {
    private const val ADDRESS_BOOK_NAMESPACE = "http://www.msn.com/webservices/AddressBook"
    private const val SOAP_ACTION =
        "http://www.msn.com/webservices/AddressBook/GetContactsRecentActivity"
    private const val ENDPOINT_PATH = "/whatsnew/whatsnewservice.asmx"
    private const val USER_AGENT = "MSNMSGR/14.0.8117.0416"
    private const val TIMEOUT_MILLIS = 12_000

    suspend fun fetchContactsRecentActivity(
        host: String,
        ticket: String,
        count: Int = 25,
        locale: String = "en-us",
    ): List<WhatsNewActivity> = withContext(Dispatchers.IO) {
        val endpoints = MsnpClientConfigService.candidateServiceEndpoints(host, ENDPOINT_PATH)
        require(endpoints.isNotEmpty()) { "Social service host is not configured." }
        require(ticket.isNotBlank()) { "Social service authentication ticket is missing." }

        val envelope = buildGetContactsRecentActivityRequest(
            ticket = ticket,
            count = count.coerceIn(1, 50),
            locale = locale,
        )
        MsnpClientConfigService.withServiceEndpointFallback(endpoints) { endpointUrl ->
            val connection = (URL(endpointUrl).openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    instanceFollowRedirects = true
                    doOutput = true
                    setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                    setRequestProperty("SOAPAction", "\"$SOAP_ACTION\"")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            try {
                connection.outputStream.use { it.write(envelope.toByteArray(StandardCharsets.UTF_8)) }
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    val fault = runCatching {
                        parseXml(body).documentElement.firstDescendantText("faultstring")
                    }.getOrDefault("")
                    throw IllegalStateException(
                        fault.ifBlank { "What's New request failed with HTTP $status." },
                    )
                }
                parseActivitiesResponse(body)
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun buildGetContactsRecentActivityRequest(
        ticket: String,
        count: Int = 25,
        locale: String = "en-us",
    ): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soap:Header>
            <ABAuthHeader xmlns="$ADDRESS_BOOK_NAMESPACE">
              <ManagedGroupRequest>false</ManagedGroupRequest>
              <TicketToken>${ticket.escapeXml()}</TicketToken>
            </ABAuthHeader>
          </soap:Header>
          <soap:Body>
            <GetContactsRecentActivity xmlns="$ADDRESS_BOOK_NAMESPACE">
              <entityHandle>
                <Cid>0</Cid>
              </entityHandle>
              <count>$count</count>
              <locales>
                <string>${locale.escapeXml()}</string>
              </locales>
            </GetContactsRecentActivity>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()

    internal fun parseActivitiesResponse(xml: String): List<WhatsNewActivity> {
        val document = parseXml(xml)
        val fault = document.getElementsByTagNameNS("*", "Fault").item(0) as? Element
        if (fault != null) {
            throw IllegalStateException(
                fault.firstDescendantText("faultstring")
                    .ifBlank { "The social feed service rejected this request." },
            )
        }

        val detailsList = document.getElementsByTagNameNS("*", "ActivityDetails")
        val activities = ArrayList<WhatsNewActivity>(detailsList.length)
        for (index in 0 until detailsList.length) {
            val detail = detailsList.item(index) as? Element ?: continue
            parseActivityDetail(detail)?.let(activities::add)
        }
        return activities.sortedByDescending(WhatsNewActivity::publishedAtMillis)
    }

    private fun parseActivityDetail(detail: Element): WhatsNewActivity? {
        val objectId = detail.firstDescendantText("ObjectId")
        val applicationId = detail.firstDescendantText("ApplicationId").toIntOrNull()
        val changeType = detail.firstDescendantText("ChangeType").toIntOrNull()
        val publishedAtMillis = parseIsoTimestamp(detail.firstDescendantText("PublishDate"))

        var publisherCid = ""
        var publisherName = ""
        var publisherAvatarUrl: String? = null
        var publisherProfileUrl: String? = null
        var isFavorite = false
        var statusText = ""
        var mediaUrl: String? = null
        var mediaHref: String? = null
        var appLinkUrl: String? = null
        var appLinkLabel: String? = null

        val vars = detail.getElementsByTagNameNS("*", "TemplateVariable")
        for (index in 0 until vars.length) {
            val variable = vars.item(index) as? Element ?: continue
            val varName = variable.firstDescendantText("Name")
            val xsiType = variable.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type")
                .ifBlank { variable.getAttribute("xsi:type") }

            when {
                xsiType.contains("PublisherIdTemplateVariable", ignoreCase = true) ||
                    varName.equals("publisher", ignoreCase = true) -> {
                    publisherCid = variable.firstDescendantText("Id")
                    publisherName = variable.firstDescendantText("NameHint")
                    publisherAvatarUrl = variable.firstDescendantText("ProfilePictureUrl")
                        .ifBlank { variable.firstDescendantText("Value") }
                        .let(MsnpClientConfigService::normalizeWebUrl)
                    publisherProfileUrl = variable.firstDescendantText("ProfileUrlHint")
                        .let(MsnpClientConfigService::normalizeWebUrl)
                    isFavorite = variable.firstDescendantText("IsFavorite").equals("true", true)
                }

                xsiType.contains("HlinkTemplateVariable", ignoreCase = true) ||
                    varName.equals("AppName", ignoreCase = true) -> {
                    appLinkUrl = variable.firstDescendantText("ValueAsSafeLink")
                        .ifBlank { variable.firstDescendantText("Value") }
                        .let(MsnpClientConfigService::normalizeWebUrl)
                    appLinkLabel = variable.firstDescendantText("Text").ifBlank { null }
                }

                xsiType.contains("ImageTemplateVariable", ignoreCase = true) ||
                    varName.equals("Photo", ignoreCase = true) ||
                    varName.equals("DisplayPicture", ignoreCase = true) -> {
                    mediaUrl = variable.firstDescendantText("Value")
                        .let(MsnpClientConfigService::normalizeWebUrl)
                    mediaHref = variable.firstDescendantText("HrefAsSafeLink")
                        .ifBlank { variable.firstDescendantText("Href") }
                        .let(MsnpClientConfigService::normalizeWebUrl)
                }

                xsiType.contains("TextTemplateVariable", ignoreCase = true) ||
                    varName.equals("CanonicalStatusText", ignoreCase = true) ||
                    varName.equals("StatusText", ignoreCase = true) -> {
                    val value = variable.firstDescendantText("Value")
                    if (value.isNotEmpty() && (varName.equals("CanonicalStatusText", true) || statusText.isEmpty())) {
                        statusText = value
                    }
                }
            }
        }

        val kind = when {
            changeType == 20 || applicationId == 420 -> WhatsNewKind.Announcement
            changeType == 2 -> WhatsNewKind.Photo
            changeType == 3 -> WhatsNewKind.DisplayPicture
            changeType == 1 -> WhatsNewKind.Status
            else -> WhatsNewKind.Other
        }

        val resolvedName = publisherName.ifBlank {
            appLinkLabel ?: if (kind == WhatsNewKind.Announcement) "Announcement" else "Contact"
        }
        val targetUrl = when (kind) {
            WhatsNewKind.Photo -> mediaHref ?: mediaUrl ?: publisherProfileUrl ?: appLinkUrl
            WhatsNewKind.Announcement -> appLinkUrl ?: publisherProfileUrl
            else -> publisherProfileUrl ?: mediaHref ?: appLinkUrl
        }
        if (objectId.isBlank() && statusText.isBlank() && mediaUrl == null) return null

        return WhatsNewActivity(
            id = objectId.ifBlank { "${publisherCid}_${publishedAtMillis}_${kind.name}" },
            kind = kind,
            publisherCid = publisherCid,
            publisherName = resolvedName,
            publisherAvatarUrl = publisherAvatarUrl,
            publisherProfileUrl = publisherProfileUrl,
            text = statusText,
            mediaUrl = mediaUrl,
            targetUrl = targetUrl,
            sourceLabel = appLinkLabel,
            publishedAtMillis = publishedAtMillis,
            isFavorite = isFavorite,
        )
    }

    internal fun parseIsoTimestamp(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        val normalized = value.replace(Regex("""\.(\d{3})\d+"""), ".$1")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder().parse(xml.trim().byteInputStream(StandardCharsets.UTF_8))

    private fun Element.firstDescendantText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty().trim()

    private fun String.escapeXml(): String = buildString(length) {
        this@escapeXml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }
}
