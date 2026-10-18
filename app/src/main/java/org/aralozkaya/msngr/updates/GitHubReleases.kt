package org.aralozkaya.msngr.updates

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal const val RELEASES_PAGE = "https://github.com/aralozkaya/Msngr/releases"
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val DOWNLOAD_PREFIX = "$RELEASES_PAGE/download/"

internal fun openUpdateConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 20_000
    readTimeout = 30_000
    setRequestProperty("User-Agent", "Msngr-Android-Updater")
}

internal class GitHubReleases {
    fun fetch(): List<AppRelease> {
        val releases = mutableListOf<AppRelease>()
        // Paginate: GitHub's 'latest' endpoint excludes prereleases and can use publication order.
        for (page in 1..10) {
            val connection = openUpdateConnection("https://api.github.com/repos/aralozkaya/Msngr/releases?per_page=100&page=$page")
            try {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (connection.responseCode !in 200..299) {
                    throw IOException(if (connection.responseCode == 403 || connection.responseCode == 429)
                        "GitHub's request limit was reached. Please try again later."
                    else "GitHub could not be reached (HTTP ${connection.responseCode}).")
                }
                val data = connection.inputStream.bufferedReader().use { reader ->
                    // Bound remote metadata before parsing it.
                    val body = reader.readTextLimited(8 * 1024 * 1024)
                    JSONArray(body)
                }
                for (index in 0 until data.length()) parseGitHubRelease(data.getJSONObject(index))?.let(releases::add)
                if (!connection.getHeaderField("Link").orEmpty().contains("rel=\"next\"")) return releases
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("GitHub returned too many release pages. Please check the releases page.")
    }
}

private fun java.io.Reader.readTextLimited(limit: Int): String {
    val text = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (text.length + count > limit) throw IOException("The release information is too large.")
        text.append(buffer, 0, count)
    }
    return text.toString()
}

internal fun parseGitHubRelease(json: JSONObject): AppRelease? {
    if (json.optBoolean("draft")) return null
    val version = ReleaseVersion.parse(json.optString("tag_name")) ?: return null
    val date = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(json.getString("published_at"))?.time
    }.getOrNull() ?: return null
    val assets = json.optJSONArray("assets") ?: JSONArray()
    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
        it.optString("name") == "Msngr-release.apk" && it.optString("state") == "uploaded" &&
            isReleaseApkUrl(it.optString("browser_download_url"))
    }
    return AppRelease(
        version, date, json.optString("body").takeUnless { it == "null" }.orEmpty(),
        json.optBoolean("prerelease") || version.tag.contains('-'),
        "$RELEASES_PAGE/tag/${version.tag}", asset?.optString("browser_download_url"),
        asset?.optLong("size") ?: 0,
        asset?.optString("digest")?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
    )
}

internal fun isReleaseApkUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
        url.startsWith(DOWNLOAD_PREFIX) && uri.path.endsWith("/Msngr-release.apk") &&
        !uri.path.contains("/../") && uri.query == null && uri.fragment == null
}.getOrDefault(false)

internal fun AppRelease.toJson(): JSONObject = JSONObject().apply {
    put("tag", version.tag)
    put("published", publishedAt)
    put("notes", notes)
    put("prerelease", prerelease)
    put("page", pageUrl)
    put("apk", apkUrl)
    put("size", apkSize)
    put("sha256", sha256)
}

internal fun storedRelease(json: String?): AppRelease? = runCatching {
    val value = JSONObject(json ?: return null)
    AppRelease(
        ReleaseVersion.parse(value.getString("tag")) ?: return null,
        value.getLong("published"), value.getString("notes"), value.getBoolean("prerelease"),
        value.getString("page"), value.optString("apk").takeIf(::isReleaseApkUrl), value.optLong("size"),
        value.optString("sha256").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
    )
}.getOrNull()
