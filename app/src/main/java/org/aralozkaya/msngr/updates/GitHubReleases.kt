package org.aralozkaya.msngr.updates

import org.aralozkaya.msngr.readBytesBounded
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal const val RELEASES_PAGE = "https://github.com/aralozkaya/Msngr/releases"
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val DOWNLOAD_PREFIX = "$RELEASES_PAGE/download/"
private const val RELEASE_APK = "Msngr-release.apk"
private val sha256Pattern = Regex("[a-fA-F0-9]{64}")

internal fun openUpdateConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 20_000
    readTimeout = 30_000
    setRequestProperty("User-Agent", "Msngr-Android-Updater")
}

internal fun fetchGitHubReleases(): List<AppRelease> {
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
            val data = connection.inputStream.use { input ->
                JSONArray(input.readBytesBounded(8 * 1024 * 1024) { "The release information is too large." }.decodeToString())
            }
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let(::parseGitHubRelease)?.let(releases::add)
            }
            if (!connection.getHeaderField("Link").orEmpty().contains("rel=\"next\"")) return releases
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("GitHub returned too many release pages. Please check the releases page.")
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
    val apkUrl = "$DOWNLOAD_PREFIX${version.tag}/$RELEASE_APK"
    val asset = (0 until assets.length()).asSequence().mapNotNull(assets::optJSONObject).firstOrNull {
        val digest = it.optString("digest")
        it.optString("name") == RELEASE_APK && it.optString("state") == "uploaded" &&
            it.optString("browser_download_url") == apkUrl &&
            (!digest.startsWith("sha256:") || sha256Pattern.matches(digest.removePrefix("sha256:")))
    }
    return AppRelease(
        version, date, json.optString("body").takeUnless { it == "null" }.orEmpty(),
        json.optBoolean("prerelease") || version.tag.contains('-'),
        "$RELEASES_PAGE/tag/${version.tag}", asset?.optString("browser_download_url"),
        asset?.optLong("size") ?: 0,
        asset?.optString("digest")?.removePrefix("sha256:")?.takeIf { sha256Pattern.matches(it) },
    )
}

internal fun isReleaseApkUrl(url: String): Boolean {
    val tag = url.removePrefix(DOWNLOAD_PREFIX).removeSuffix("/$RELEASE_APK")
    return ReleaseVersion.parse(tag) != null && url == "$DOWNLOAD_PREFIX$tag/$RELEASE_APK"
}

internal fun AppRelease.toJson(): JSONObject = JSONObject().apply {
    put("tag", version.tag)
    put("published", publishedAt)
    put("notes", notes)
    put("prerelease", prerelease)
    put("apk", apkUrl)
    put("size", apkSize)
    put("sha256", sha256)
}

internal fun storedRelease(json: String?): AppRelease? = runCatching {
    val value = JSONObject(json ?: return null)
    val version = ReleaseVersion.parse(value.getString("tag")) ?: return null
    AppRelease(
        version,
        value.getLong("published"), value.getString("notes"), value.getBoolean("prerelease"),
        "$RELEASES_PAGE/tag/${version.tag}", value.optString("apk").takeIf { it == "$DOWNLOAD_PREFIX${version.tag}/$RELEASE_APK" }, value.optLong("size"),
        value.optString("sha256").takeIf { sha256Pattern.matches(it) },
    )
}.getOrNull()
