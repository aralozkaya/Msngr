package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.ServerConfig
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

data class MessengerTabSite(
    val id: String,
    val name: String,
    val tooltip: String,
    val contentUrl: String,
    val hitUrl: String = contentUrl,
    val iconUrl: String = "",
)

data class MessengerBannerAd(
    val imageUrl: String,
    val targetUrl: String,
)

data class MessengerTextAd(
    val text: String,
    val hitUrl: String,
)

data class MsgrClientConfig(
    val todayUrl: String? = null,
    val bannerAdUrl: String? = null,
    val bannerRefreshSeconds: Int = DEFAULT_AD_REFRESH_SECONDS,
    val textAdUrl: String? = null,
    val textAdRotationSeconds: Int = DEFAULT_TEXT_AD_ROTATION_SECONDS,
    val tabs: List<MessengerTabSite> = emptyList(),
    val appDirServiceUrl: String? = null,
    val addressBookUrl: String? = null,
    val storageServiceUrl: String? = null,
) {
    companion object {
        const val DEFAULT_AD_REFRESH_SECONDS = 300
        const val DEFAULT_TEXT_AD_ROTATION_SECONDS = 420
    }
}

internal data class ParsedMsgrConfig(
    val todayUrl: String? = null,
    val bannerAdUrl: String? = null,
    val bannerRefreshSeconds: Int = MsgrClientConfig.DEFAULT_AD_REFRESH_SECONDS,
    val textAdUrl: String? = null,
    val textAdRotationSeconds: Int = MsgrClientConfig.DEFAULT_TEXT_AD_ROTATION_SECONDS,
    val slotUrls: List<Pair<String, String>> = emptyList(),
    val inlineTabs: List<MessengerTabSite> = emptyList(),
    val appDirServiceUrl: String? = null,
    val addressBookUrl: String? = null,
    val storageServiceUrl: String? = null,
)

/**
 * Fetches and parses MSNP18 / WLM 2009 client configuration (`MsgrConfig.asmx`),
 * dynamic service tabs (`TabConfig`), Today URL (`MsnTodayConfig`), and ads (`AdMainConfig`).
 */
object MsnpClientConfigService {
    private const val CLIENT_VERSION = "14.0.8117.0416"
    private const val USER_AGENT = "MSNMSGR/14.0.8117.0416"
    private const val TIMEOUT_MILLIS = 10_000
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    suspend fun fetchConfig(serverConfig: ServerConfig): MsgrClientConfig {
        val getEndpoints = candidateServiceEndpoints(
            hostOrUrl = serverConfig.configServer,
            defaultPath = "/Config/MsgrConfig.asmx?ver=$CLIENT_VERSION",
        )
        if (getEndpoints.isEmpty()) return MsgrClientConfig()

        val soapEndpoints = candidateServiceEndpoints(
            hostOrUrl = serverConfig.configServer,
            defaultPath = "/Config/MsgrConfig.asmx",
        )
        var succeededEndpoint: String? = null
        val rawXml = runCatching {
            withServiceEndpointFallback(getEndpoints) { url ->
                getText(url).also { succeededEndpoint = url }
            }
        }.getOrElse {
            withServiceEndpointFallback(soapEndpoints) { url ->
                postGetClientConfigSoap(url).also { succeededEndpoint = url }
            }
        }

        val parsed = parseMsgrConfig(rawXml)
        val resolvedTabs = if (parsed.slotUrls.isNotEmpty()) {
            val slotTabs = coroutineScope {
                parsed.slotUrls.map { (slotId, slotUrl) ->
                    async {
                        runCatching {
                            parseTabSlotXml(getText(slotUrl), slotId)
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            slotTabs.ifEmpty { parsed.inlineTabs }
        } else {
            parsed.inlineTabs
        }

        val bannerAdUrl = parsed.bannerAdUrl
            ?: resolveFallbackAdUrl(serverConfig.adsServer, serverConfig.configServer, succeededEndpoint, "/ads/banner")
        val textAdUrl = parsed.textAdUrl
            ?: resolveFallbackAdUrl(serverConfig.adsServer, serverConfig.configServer, succeededEndpoint, "/ads/txt")

        return MsgrClientConfig(
            todayUrl = parsed.todayUrl,
            bannerAdUrl = bannerAdUrl,
            bannerRefreshSeconds = parsed.bannerRefreshSeconds,
            textAdUrl = textAdUrl,
            textAdRotationSeconds = parsed.textAdRotationSeconds,
            tabs = resolvedTabs,
            appDirServiceUrl = parsed.appDirServiceUrl,
            addressBookUrl = parsed.addressBookUrl,
            storageServiceUrl = parsed.storageServiceUrl,
        )
    }

    private fun resolveFallbackAdUrl(
        adsServer: String,
        configServer: String,
        succeededConfigEndpoint: String?,
        path: String,
    ): String? {
        val normalizedAdsHost = adsServer.normalizedHttpHost()
        if (normalizedAdsHost.isEmpty()) return null
        val trimmedAds = adsServer.trim()
        if (normalizedAdsHost.equals(configServer.normalizedHttpHost(), ignoreCase = true) &&
            succeededConfigEndpoint?.startsWith("http://", ignoreCase = true) == true &&
            !trimmedAds.startsWith("https://", ignoreCase = true)
        ) {
            return "http://$normalizedAdsHost$path"
        }
        return candidateServiceEndpoints(trimmedAds, path).firstOrNull()
    }

    suspend fun fetchBannerAd(url: String): MessengerBannerAd? {
        val safeUrl = normalizeWebUrl(url) ?: return null
        val html = getText(safeUrl)
        return parseBannerAdHtml(html, safeUrl)
    }

    suspend fun fetchTextAd(url: String): MessengerTextAd? {
        val safeUrl = normalizeWebUrl(url) ?: return null
        val xml = getText(safeUrl)
        return parseTextAdXml(xml)
    }

    suspend fun fetchImageBytes(url: String, maxBytes: Int = MAX_RESPONSE_BYTES): ByteArray? =
        withContext(Dispatchers.IO) {
            val safeUrl = normalizeWebUrl(url) ?: return@withContext null
            val connection = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                readBoundedBytes(connection, maxBytes)
            } finally {
                connection.disconnect()
            }
        }

    internal fun parseMsgrConfig(xml: String): ParsedMsgrConfig {
        val root = parseXmlDocument(xml).documentElement
        val innerResult = root.getElementsByTagNameNS("*", "GetClientConfigResult")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.startsWith("<") }
        val configRoot = if (innerResult != null) {
            parseXmlDocument(innerResult).documentElement
        } else {
            root
        }

        val localized = configRoot.getElementsByTagNameNS("*", "LocalizedConfig").item(0) as? Element
            ?: configRoot

        val todayUrl = (localized.getElementsByTagNameNS("*", "MsnTodayConfig").item(0) as? Element)
            ?.firstDescendantText("MsnTodayURL")
            ?.let(::normalizeWebUrl)

        val adConfig = localized.getElementsByTagNameNS("*", "AdMainConfig").item(0) as? Element
        val bannerEl = adConfig?.getElementsByTagNameNS("*", "AdBanner20URL")?.item(0) as? Element
        val bannerAdUrl = bannerEl?.textContent?.trim()?.let(::normalizeWebUrl)
        val bannerRefresh = bannerEl?.getAttribute("Refresh")?.toIntOrNull()?.coerceAtLeast(30)
            ?: MsgrClientConfig.DEFAULT_AD_REFRESH_SECONDS
        val textAdUrl = adConfig?.firstDescendantText("TextAdServer")?.let(::normalizeWebUrl)
        val textAdRotation = adConfig?.firstDescendantText("TextAdRotation")
            ?.toIntOrNull()?.coerceAtLeast(30)
            ?: MsgrClientConfig.DEFAULT_TEXT_AD_ROTATION_SECONDS

        val tabConfig = localized.getElementsByTagNameNS("*", "TabConfig").item(0) as? Element
            ?: configRoot.getElementsByTagNameNS("*", "TabConfig").item(0) as? Element

        val slotUrls = mutableListOf<Pair<String, String>>()
        val inlineTabs = mutableListOf<MessengerTabSite>()
        if (tabConfig != null) {
            val slots = tabConfig.getElementsByTagNameNS("*", "URL")
            for (index in 0 until slots.length) {
                val el = slots.item(index) as? Element ?: continue
                val url = normalizeWebUrl(el.textContent) ?: continue
                val id = el.getAttribute("id").ifBlank { (index + 1).toString() }
                slotUrls += id to url
            }
            val tabs = tabConfig.getElementsByTagNameNS("*", "tab")
            for (index in 0 until tabs.length) {
                val tabEl = tabs.item(index) as? Element ?: continue
                parseTabElement(tabEl, id = (index + 1).toString())?.let(inlineTabs::add)
            }
        }

        val appDirConfig = (configRoot.getElementsByTagNameNS("*", "AppDirConfig").item(0) as? Element)
            ?: (localized.getElementsByTagNameNS("*", "AppDirConfig").item(0) as? Element)
        val appDirServiceUrl = appDirConfig?.let { el ->
            sequenceOf("AppDirSeviceURL", "AppDirServiceURL", "AppDirPageURL")
                .mapNotNull { tag -> el.firstDescendantText(tag).takeIf { it.isNotEmpty() } }
                .mapNotNull(::normalizeAppDirServiceUrl)
                .firstOrNull()
        }

        val abchCfg = (configRoot.getElementsByTagNameNS("*", "AbchCfg").item(0) as? Element)
            ?: (localized.getElementsByTagNameNS("*", "AbchCfg").item(0) as? Element)
        val addressBookUrl = abchCfg?.let { el ->
            sequenceOf("url", "Url", "URL")
                .mapNotNull { tag -> el.firstDescendantText(tag).takeIf { it.isNotEmpty() } }
                .mapNotNull(::normalizeWebUrl)
                .firstOrNull()
        }

        val contactCard = (configRoot.getElementsByTagNameNS("*", "ContactCard").item(0) as? Element)
            ?: (localized.getElementsByTagNameNS("*", "ContactCard").item(0) as? Element)
        val storageServiceUrl = contactCard?.let { el ->
            sequenceOf("GetItemVerUrl", "StorageServiceUrl", "GetItemUrl")
                .mapNotNull { tag -> el.firstDescendantText(tag).takeIf { it.isNotEmpty() } }
                .mapNotNull(::normalizeWebUrl)
                .firstOrNull()
        }

        return ParsedMsgrConfig(
            todayUrl = todayUrl,
            bannerAdUrl = bannerAdUrl,
            bannerRefreshSeconds = bannerRefresh,
            textAdUrl = textAdUrl,
            textAdRotationSeconds = textAdRotation,
            slotUrls = slotUrls,
            inlineTabs = inlineTabs,
            appDirServiceUrl = appDirServiceUrl,
            addressBookUrl = addressBookUrl,
            storageServiceUrl = storageServiceUrl,
        )
    }

    internal fun parseTabSlotXml(xml: String, slotId: String = "1"): MessengerTabSite? {
        val doc = parseXmlDocument(xml)
        val tabEl = (doc.getElementsByTagNameNS("*", "tabad").item(0) as? Element)
            ?: (doc.getElementsByTagNameNS("*", "tab").item(0) as? Element)
            ?: return null
        return parseTabElement(tabEl, id = slotId)
    }

    private fun parseTabElement(element: Element, id: String): MessengerTabSite? {
        val type = element.firstDescendantText("type")
        if (type.isNotEmpty() && !type.equals("page", ignoreCase = true)) return null
        val contentUrl = element.firstDescendantText("contenturl").let(::normalizeWebUrl)
            ?: element.firstDescendantText("hiturl").let(::normalizeWebUrl)
            ?: return null
        val hitUrl = element.firstDescendantText("hiturl").let(::normalizeWebUrl) ?: contentUrl
        val name = element.firstDescendantText("name").ifBlank {
            element.firstDescendantText("tooltip")
        }.ifBlank {
            runCatching { URL(contentUrl).host }.getOrDefault("Site")
        }
        val tooltip = element.firstDescendantText("tooltip").ifBlank { name }
        val iconUrl = element.firstDescendantText("image").let(::normalizeWebUrl).orEmpty()
        return MessengerTabSite(
            id = id,
            name = name,
            tooltip = tooltip,
            contentUrl = contentUrl,
            hitUrl = hitUrl,
            iconUrl = iconUrl,
        )
    }

    internal fun parseBannerAdHtml(html: String, baseUrl: String): MessengerBannerAd? {
        val hrefRaw = ANCHOR_HREF_REGEX.find(html)?.groupValues?.getOrNull(1)?.unescapeBasicHtml()
        val srcRaw = IMG_SRC_REGEX.find(html)?.groupValues?.getOrNull(1)?.unescapeBasicHtml()
            ?: return null
        val imageUrl = resolveAndNormalizeUrl(srcRaw, baseUrl) ?: return null
        val targetUrl = hrefRaw?.let { resolveAndNormalizeUrl(it, baseUrl) } ?: baseUrl
        return MessengerBannerAd(
            imageUrl = imageUrl,
            targetUrl = targetUrl,
        )
    }

    internal fun parseTextAdXml(xml: String): MessengerTextAd? {
        val root = runCatching { parseXmlDocument(xml).documentElement }.getOrNull() ?: return null
        val text = root.firstDescendantText("adtext").takeIf(String::isNotBlank) ?: return null
        val hitUrl = root.firstDescendantText("hiturl").let(::normalizeWebUrl).orEmpty()
        return MessengerTextAd(text = text, hitUrl = hitUrl)
    }

    internal fun normalizeWebUrl(rawUrl: String): String? {
        val upgraded = upgradeCommunityHttpsUrl(rawUrl)
        val parsed = runCatching { URL(upgraded) }.getOrNull() ?: return null
        return if (parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) {
            upgraded
        } else {
            null
        }
    }

    private fun resolveAndNormalizeUrl(rawUrl: String, baseUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val resolved = runCatching { URL(URL(baseUrl), trimmed).toString() }.getOrDefault(trimmed)
        return normalizeWebUrl(resolved)
    }

    internal fun candidateServiceEndpoints(hostOrUrl: String, defaultPath: String = ""): List<String> {
        val trimmed = hostOrUrl.trim()
        if (trimmed.isEmpty()) return emptyList()
        val normalizedPath = when {
            defaultPath.isBlank() -> ""
            defaultPath.startsWith("/") -> defaultPath
            else -> "/$defaultPath"
        }
        val upgraded = upgradeCommunityHttpsUrl(trimmed)
        if (upgraded.startsWith("http://", ignoreCase = true) ||
            upgraded.startsWith("https://", ignoreCase = true)
        ) {
            return listOfNotNull(appendPathIfMissing(upgraded, normalizedPath))
        }
        if ("://" in upgraded) return emptyList()
        val bare = upgraded.trimStart('/')
        return listOfNotNull(
            appendPathIfMissing("https://$bare", normalizedPath),
            appendPathIfMissing("http://$bare", normalizedPath),
        ).distinct()
    }

    internal suspend fun <T> withServiceEndpointFallback(
        endpoints: List<String>,
        block: suspend (String) -> T,
    ): T {
        require(endpoints.isNotEmpty()) { "No service endpoints configured." }
        var lastError: IOException? = null
        for ((index, endpoint) in endpoints.withIndex()) {
            try {
                return block(endpoint)
            } catch (error: IOException) {
                lastError = error
                if (index == endpoints.lastIndex) throw error
            }
        }
        throw requireNotNull(lastError)
    }

    internal fun normalizeAppDirServiceUrl(rawUrl: String): String? {
        val normalized = normalizeWebUrl(rawUrl) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
        val path = uri.path.orEmpty()
        if (!path.endsWith("/Directory.aspx", ignoreCase = true)) return normalized
        val rewrittenPath = path.dropLast("Directory.aspx".length) + "AppDirectory.asmx"
        return runCatching {
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, rewrittenPath, uri.query, uri.fragment)
                .toASCIIString()
        }.getOrDefault(normalized)
    }

    private fun appendPathIfMissing(rawUrl: String, normalizedPath: String): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        val currentPath = uri.path.orEmpty()
        if (normalizedPath.isEmpty() || (currentPath.isNotEmpty() && currentPath != "/")) {
            return rawUrl
        }
        val base = rawUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        return "$base$normalizedPath"
    }

    internal fun upgradeCommunityHttpsUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return trimmed
        val remainder = trimmed.substring(7)
        val host = remainder.substringBefore('/').substringBefore(':').lowercase()
        return if (
            host == "static.levelleap.com" ||
            host == "wiby.me" || host == "www.wiby.me" ||
            host == "theoldnet.com" || host == "www.theoldnet.com" ||
            host == "classicconnect.net" || host == "www.classicconnect.net" ||
            host.endsWith(".escargot.chat") ||
            host.endsWith(".nina.chat") ||
            host.endsWith(".ctsrv.xyz") ||
            host.endsWith(".ugnet.xyz") ||
            host.endsWith(".ctsrv.gay") ||
            host.endsWith(".ugnet.gay")
        ) {
            "https://$remainder"
        } else {
            trimmed
        }
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val safeUrl = normalizeWebUrl(url)
            ?: throw IllegalArgumentException("Invalid configuration URL.")
        val connection = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Configuration request failed with HTTP $status.")
            }
            readBoundedBytes(connection, MAX_RESPONSE_BYTES).toString(StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun postGetClientConfigSoap(url: String): String = withContext(Dispatchers.IO) {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                           xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <GetClientConfig xmlns="http://www.msn.com/webservices/Messenger/Client">
                  <ClientInfo>
                    <Country>US</Country>
                    <CLCID>0409</CLCID>
                    <PLCID>0409</PLCID>
                    <AppId>MSNMSGR</AppId>
                    <Version>14.0.8117.416</Version>
                  </ClientInfo>
                </GetClientConfig>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            instanceFollowRedirects = true
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", "\"http://www.msn.com/webservices/Messenger/Client/GetClientConfig\"")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.outputStream.use { it.write(envelope.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("GetClientConfig failed with HTTP $status.")
            }
            readBoundedBytes(connection, MAX_RESPONSE_BYTES).toString(StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedBytes(connection: HttpURLConnection, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > maxBytes) {
                    throw IllegalStateException("Response exceeded $maxBytes bytes.")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun parseXmlDocument(xml: String) = secureXmlFactory().newDocumentBuilder()
        .parse(xml.trim().byteInputStream(StandardCharsets.UTF_8))

    private fun secureXmlFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

    private fun Element.firstDescendantText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty().trim()

    private fun String.normalizedHttpHost(): String = trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    private fun String.unescapeBasicHtml(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private val ANCHOR_HREF_REGEX = Regex(
        """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val IMG_SRC_REGEX = Regex(
        """<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
}
