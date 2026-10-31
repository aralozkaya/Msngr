package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.MsnGameInviteContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

data class GameCatalogCategory(
    val categoryId: Int,
    val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val sequence: Int = 1,
    val appType: Int = 0,
)

data class GameCatalogEntry(
    val appId: Int,
    val categoryId: Int,
    val appType: Int,
    val name: String,
    val description: String,
    val url: String,
    val iconUrl: String = "",
    val appIconUrl: String = iconUrl,
    val sequence: Int = 1,
    val kids: Int = 1,
    val width: Int = 500,
    val height: Int = 500,
    val minUsers: Int = 2,
    val maxUsers: Int = 2,
    val maxPacketRate: Int = 120,
    val clientVersion: String = "6.0",
    val location: String = "side",
    val type: String = "game",
    val replaceIm: Boolean = false,
    val sendIm: Boolean = false,
    val receiveIm: Boolean = false,
    val windows: Boolean = false,
    val error: String = "",
    val hidden: Boolean = false,
) {
    val effectiveIconUrl: String
        get() = appIconUrl.ifBlank { iconUrl }

    fun toInviteContext(): MsnGameInviteContext = MsnGameInviteContext(
        entryId = appId,
        kids = kids,
        appName = name,
    )
}

data class GameCatalog(
    val categories: List<GameCatalogCategory>,
    val entries: List<GameCatalogEntry>,
) {
    val visibleEntries: List<GameCatalogEntry>
        get() = entries.filter { !it.hidden && it.error.isBlank() && it.url.isNotBlank() }

    fun findEntry(appId: Int): GameCatalogEntry? =
        entries.firstOrNull { it.appId == appId }
}

object MsnpGameCatalogService {
    const val DEFAULT_ACTIVITIES_SERVER = "apps.escargot.nina.chat"
    private const val DEFAULT_SERVICE_URL = "https://apps.escargot.nina.chat/activities/service/"
    private const val CLIENT_VERSION = "14.0.8117.416"
    private const val TIMEOUT_MILLIS = 10_000

    private val cachedCatalogs = ConcurrentHashMap<String, GameCatalog>()

    @Volatile
    var currentActivitiesServer: String = DEFAULT_ACTIVITIES_SERVER

    fun defaultCatalog(): GameCatalog = GameCatalog(
        categories = DEFAULT_CATEGORIES,
        entries = DEFAULT_ENTRIES,
    )

    internal fun candidateServiceUrls(activitiesServer: String = DEFAULT_ACTIVITIES_SERVER): List<String> {
        val trimmed = activitiesServer.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return listOf(DEFAULT_SERVICE_URL)
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val upgraded = upgradeKnownHttpsUrl(trimmed)
            val afterScheme = upgraded.substringAfter("://")
            return if ('/' in afterScheme) {
                listOf(if (upgraded.endsWith(".asmx", ignoreCase = true)) upgraded else "$upgraded/")
            } else {
                candidateServiceUrlsForHost(afterScheme)
            }
        }
        return if ('/' in trimmed) {
            val normalized = "https://$trimmed"
            listOf(if (normalized.endsWith(".asmx", ignoreCase = true)) normalized else "$normalized/")
        } else {
            candidateServiceUrlsForHost(trimmed)
        }
    }

    private fun candidateServiceUrlsForHost(host: String): List<String> {
        val escargotEndpoint = "https://$host/activities/service/"
        val wlmEndpoint = "https://$host/AppDirectory/AppDirectory.asmx"
        val lower = host.lowercase()
        return if (lower.contains("ctsrv") || lower.contains("ugnet")) {
            listOf(wlmEndpoint, escargotEndpoint)
        } else {
            listOf(escargotEndpoint, wlmEndpoint)
        }
    }

    suspend fun fetchCatalog(
        locale: String = "en-US",
        forceRefresh: Boolean = false,
        activitiesServer: String = currentActivitiesServer,
    ): GameCatalog {
        val cacheKey = activitiesServer.trim().lowercase().ifEmpty { DEFAULT_ACTIVITIES_SERVER }
        currentActivitiesServer = cacheKey
        if (!forceRefresh) {
            cachedCatalogs[cacheKey]?.let { return it }
        }
        for (serviceUrl in candidateServiceUrls(activitiesServer)) {
            val fetched = runCatching {
                val gamesXml = getUrl(
                    "$serviceUrl?op=GetFilteredDataSet2&locale=$locale&Page=1&Kids=-1&AppType=0&ver=$CLIENT_VERSION",
                )
                val activitiesXml = runCatching {
                    getUrl(
                        "$serviceUrl?op=GetFilteredDataSet2&locale=$locale&Page=1&Kids=-1&AppType=1&ver=$CLIENT_VERSION",
                    )
                }.getOrDefault("")

                val gamesCatalog = parseDataSetXml(gamesXml, defaultAppType = 0)
                val activitiesCatalog = if (activitiesXml.isNotBlank()) {
                    parseDataSetXml(activitiesXml, defaultAppType = 1)
                } else {
                    GameCatalog(emptyList(), emptyList())
                }

                val categories = (gamesCatalog.categories + activitiesCatalog.categories)
                    .distinctBy { it.categoryId }
                    .sortedWith(compareBy<GameCatalogCategory> { it.appType }.thenBy { it.sequence })
                    .ifEmpty { DEFAULT_CATEGORIES }
                val entries = (gamesCatalog.entries + activitiesCatalog.entries)
                    .distinctBy { it.appId }
                    .sortedWith(compareBy<GameCatalogEntry> { it.categoryId }.thenBy { it.sequence })
                check(entries.isNotEmpty()) { "Empty game catalog from $serviceUrl" }

                GameCatalog(categories = categories, entries = entries)
            }.getOrNull()
            if (fetched != null) {
                cachedCatalogs[cacheKey] = fetched
                return fetched
            }
        }
        return cachedCatalogs[cacheKey] ?: defaultCatalog()
    }

    suspend fun resolveEntry(
        appId: Int,
        fallbackName: String = "",
        locale: String = "en-US",
        activitiesServer: String = currentActivitiesServer,
    ): GameCatalogEntry? {
        val cacheKey = activitiesServer.trim().lowercase().ifEmpty { DEFAULT_ACTIVITIES_SERVER }
        cachedCatalogs[cacheKey]?.findEntry(appId)?.let { return it }

        for (serviceUrl in candidateServiceUrls(activitiesServer)) {
            val fetchedEntry = runCatching {
                val xml = getUrl("$serviceUrl?op=GetAppEntry&ID=$appId&EntryID=$appId&locale=$locale&ver=$CLIENT_VERSION")
                parseAppEntryXml(xml)
            }.getOrNull()
            if (fetchedEntry != null && fetchedEntry.error.isBlank()) return fetchedEntry
        }

        val catalog = fetchCatalog(locale = locale, activitiesServer = activitiesServer)
        catalog.findEntry(appId)?.let { return it }
        defaultCatalog().findEntry(appId)?.let { return it }
        return null
    }

    internal fun parseDataSetXml(xml: String, defaultAppType: Int = 0): GameCatalog {
        val document = catalogDocumentBuilderFactory().newDocumentBuilder()
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))

        val categoryNodes = document.getElementsByTagName("Category")
        val categories = buildList {
            for (index in 0 until categoryNodes.length) {
                val element = categoryNodes.item(index) as? Element ?: continue
                val id = element.catalogText("CategoryID").toIntOrNull() ?: continue
                val name = element.catalogText("Name")
                    .ifBlank { element.catalogText("CategoryName") }
                    .ifBlank { continue }
                val description = element.catalogText("Description")
                    .ifBlank { element.catalogText("CategoryDescription") }
                val iconUrl = element.catalogText("IconURL")
                    .ifBlank { element.catalogText("CategoryIconURL") }
                add(
                    GameCatalogCategory(
                        categoryId = id,
                        name = name,
                        description = description,
                        iconUrl = upgradeKnownHttpsUrl(iconUrl),
                        sequence = element.catalogText("Sequence").toIntOrNull() ?: (index + 1),
                        appType = defaultAppType,
                    ),
                )
            }
        }.sortedBy { it.sequence }

        val entryNodes = document.getElementsByTagName("Entry")
        val entries = buildList {
            for (index in 0 until entryNodes.length) {
                val element = entryNodes.item(index) as? Element ?: continue
                parseEntryElement(element, defaultAppType)?.let(::add)
            }
        }.sortedWith(compareBy<GameCatalogEntry> { it.categoryId }.thenBy { it.sequence })

        return GameCatalog(categories = categories, entries = entries)
    }

    internal fun parseAppEntryXml(xml: String): GameCatalogEntry? {
        val document = catalogDocumentBuilderFactory().newDocumentBuilder()
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))
        val entry = document.getElementsByTagName("Entry").item(0) as? Element
            ?: (document.documentElement?.takeIf { it.tagName == "Entry" })
            ?: return null
        return parseEntryElement(entry, defaultAppType = 0)
    }

    private fun parseEntryElement(element: Element, defaultAppType: Int): GameCatalogEntry? {
        val appId = element.catalogText("EntryID").toIntOrNull() ?: return null
        val name = element.catalogText("Name").ifBlank { return null }
        val url = upgradeKnownHttpsUrl(element.catalogText("URL"))
        if (url.isBlank()) return null
        val iconUrl = upgradeKnownHttpsUrl(element.catalogText("IconURL"))
        val appIconUrl = upgradeKnownHttpsUrl(element.catalogText("AppIconURL")).ifBlank { iconUrl }
        val categoryId = element.catalogText("CategoryID").toIntOrNull() ?: 0
        val appType = element.catalogText("AppType").toIntOrNull() ?: defaultAppType
        val sequence = element.catalogText("Sequence").toIntOrNull() ?: 1
        val kids = element.catalogText("Kids").toIntOrNull() ?: 1
        val width = element.catalogText("Width").toIntOrNull() ?: 500
        val height = element.catalogText("Height").toIntOrNull() ?: 500
        val minUsers = element.catalogText("MinUsers").toIntOrNull() ?: 2
        val maxUsers = element.catalogText("MaxUsers").toIntOrNull() ?: 2
        val maxPacketRate = element.catalogText("MaxPacketRate").toIntOrNull() ?: 120
        val clientVersion = element.catalogText("ClientVersion").ifBlank { "6.0" }
        val location = element.catalogText("Location").ifBlank { "side" }
        val type = element.catalogText("Type").ifBlank { "game" }
        val replaceIm = element.catalogBool("ReplaceIM")
        val sendIm = element.catalogBool("SendIM")
        val receiveIm = element.catalogBool("ReceiveIM")
        val windows = element.catalogBool("Windows")
        val error = element.catalogText("Error")
        val hidden = element.catalogBool("Hidden")

        return GameCatalogEntry(
            appId = appId,
            categoryId = categoryId,
            appType = appType,
            name = name,
            description = element.catalogText("Description"),
            url = url,
            iconUrl = iconUrl,
            appIconUrl = appIconUrl,
            sequence = sequence,
            kids = kids,
            width = width,
            height = height,
            minUsers = minUsers,
            maxUsers = maxUsers,
            maxPacketRate = maxPacketRate,
            clientVersion = clientVersion,
            location = location,
            type = type,
            replaceIm = replaceIm,
            sendIm = sendIm,
            receiveIm = receiveIm,
            windows = windows,
            error = error,
            hidden = hidden,
        )
    }

    internal fun upgradeKnownHttpsUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return trimmed
        val remainder = trimmed.substring(7)
        val host = remainder.substringBefore('/').lowercase()
        return if (
            host == "static.levelleap.com" ||
            host.endsWith(".escargot.chat") ||
            host.endsWith(".nina.chat") ||
            host.endsWith(".ctsrv.gay") ||
            host.endsWith(".ugnet.gay")
        ) {
            "https://$remainder"
        } else {
            trimmed
        }
    }

    private suspend fun getUrl(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Game catalog request failed with HTTP $status")
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private val BASE_APP_DATA =
        "https://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data"

    private val DEFAULT_CATEGORIES = listOf(
        GameCatalogCategory(
            categoryId = 101,
            name = "Microsoft",
            description = "Official games by Microsoft and Zone.com",
            sequence = 1,
            appType = 0,
        ),
        GameCatalogCategory(
            categoryId = 103,
            name = "Escargot Games",
            description = "Games made for Escargot!",
            sequence = 2,
            appType = 0,
        ),
        GameCatalogCategory(
            categoryId = 102,
            name = "Mess.be Games",
            description = "Classic games from Mess.be",
            sequence = 3,
            appType = 0,
        ),
        GameCatalogCategory(
            categoryId = 100,
            name = "Escargot Activities",
            description = "Shared activities for Escargot",
            sequence = 1,
            appType = 1,
        ),
    )

    private val DEFAULT_ENTRIES = listOf(
        GameCatalogEntry(
            appId = 101000,
            categoryId = 101,
            appType = 0,
            sequence = 1,
            name = "Tic Tac Toe",
            description = "Play classic Tic Tac Toe with a friend!",
            url = "$BASE_APP_DATA/tic-tac-toe/tictactoe.htm",
            appIconUrl = "",
            width = 390,
            height = 500,
        ),
        GameCatalogEntry(
            appId = 103000,
            categoryId = 103,
            appType = 0,
            sequence = 1,
            name = "Wordle (beta)",
            description = "Guess the words faster than your friend!",
            url = "$BASE_APP_DATA/wordle/index.html",
            appIconUrl = "$BASE_APP_DATA/wordle/icon.png",
            width = 490,
            height = 350,
            replaceIm = true,
        ),
        GameCatalogEntry(
            appId = 102000,
            categoryId = 102,
            appType = 0,
            sequence = 1,
            name = "Reversi",
            description = "Take control of the board by flipping your opponent's disks!",
            url = "$BASE_APP_DATA/messbe/reversi/reversi.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/reversi/images/icon.png",
            width = 490,
            height = 350,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102001,
            categoryId = 102,
            appType = 0,
            sequence = 2,
            name = "Yahtzee",
            description = "Roll five dice for high-scoring combinations!",
            url = "$BASE_APP_DATA/messbe/yahtzee/yahtzee.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/yahtzee/images/icon.png",
            width = 530,
            height = 390,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102002,
            categoryId = 102,
            appType = 0,
            sequence = 3,
            name = "Battleships",
            description = "Find and sink your opponent's fleet before they sink yours!",
            url = "$BASE_APP_DATA/messbe/battleships/battleships.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/battleships/images/icon.png",
            width = 530,
            height = 390,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102003,
            categoryId = 102,
            appType = 0,
            sequence = 4,
            name = "Chess",
            description = "The classic game of strategy and tactics!",
            url = "$BASE_APP_DATA/messbe/chess/chess.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/chess/images/icon.png",
            width = 530,
            height = 410,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102004,
            categoryId = 102,
            appType = 0,
            sequence = 5,
            name = "Nine Men's Morris",
            description = "Form mills of three pieces to capture your opponent's men!",
            url = "$BASE_APP_DATA/messbe/morris/index.en-us.html",
            appIconUrl = "$BASE_APP_DATA/messbe/morris/imgs/icon.png",
            width = 530,
            height = 390,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102005,
            categoryId = 102,
            appType = 0,
            sequence = 6,
            name = "Connect 4",
            description = "Drop your discs and be the first to connect four in a row!",
            url = "$BASE_APP_DATA/messbe/connect4/connect4.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/connect4/images/icon.png",
            width = 530,
            height = 390,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102006,
            categoryId = 102,
            appType = 0,
            sequence = 7,
            name = "Memory",
            description = "Match pairs of cards in this classic memory game!",
            url = "$BASE_APP_DATA/messbe/memory/memory.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/memory/images/icon.png",
            width = 530,
            height = 390,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102007,
            categoryId = 102,
            appType = 0,
            sequence = 8,
            name = "Tetris",
            description = "Clear lines and outlast your opponent in head-to-head Tetris!",
            url = "$BASE_APP_DATA/messbe/tetris/tetris.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/tetris/images/icon.png",
            width = 530,
            height = 390,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 102008,
            categoryId = 102,
            appType = 0,
            sequence = 9,
            name = "Backgammon",
            description = "Roll the dice and bear off all your checkers first!",
            url = "$BASE_APP_DATA/messbe/backgammon/backgammon.htm",
            appIconUrl = "$BASE_APP_DATA/messbe/backgammon/images/icon.png",
            width = 530,
            height = 410,
            windows = true,
        ),
        GameCatalogEntry(
            appId = 100000,
            categoryId = 100,
            appType = 1,
            sequence = 1,
            name = "Whiteboard",
            description = "Draw together in real time!",
            url = "$BASE_APP_DATA/whiteboard/index.html",
            appIconUrl = "$BASE_APP_DATA/whiteboard/icon.png",
            width = 500,
            height = 500,
        ),
    )
}

private fun Element.catalogText(tagName: String): String =
    getElementsByTagName(tagName).item(0)?.textContent.orEmpty().trim()

private fun Element.catalogBool(tagName: String): Boolean {
    val raw = catalogText(tagName)
    return raw == "1" || raw.equals("true", ignoreCase = true)
}

private fun catalogDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
