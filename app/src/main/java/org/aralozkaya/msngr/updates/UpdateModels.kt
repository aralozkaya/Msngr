package org.aralozkaya.msngr.updates

/** Matches the release tag/versionCode contract in app/build.gradle.kts. */
data class ReleaseVersion(val tag: String, val code: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = code.compareTo(other.code)

    companion object {
        fun parse(tag: String): ReleaseVersion? {
            val match = Regex("""v(0|[1-9]\d{0,2})\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-(alpha|beta|rc)\.([1-9]\d?))?""")
                .matchEntire(tag) ?: return null
            val major = match.groupValues[1].toInt()
            if (major > 209) return null
            val stage = when (match.groupValues[4]) {
                "alpha" -> 100
                "beta" -> 200
                "rc" -> 300
                else -> 999
            }
            return ReleaseVersion(tag, major * 10_000_000 + match.groupValues[2].toInt() * 100_000 +
                match.groupValues[3].toInt() * 1_000 + stage + (match.groupValues[5].toIntOrNull() ?: 0))
        }
    }
}

data class AppRelease(
    val version: ReleaseVersion,
    val publishedAt: Long,
    val notes: String,
    val prerelease: Boolean,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long = 0,
    val sha256: String? = null,
)

enum class UpdateInterval(val hours: Long) {
    OnAppStart(0), Every6Hours(6), Every12Hours(12), Every24Hours(24), EveryWeek(168),
}

data class UpdatePreferences(
    val enabled: Boolean = true,
    val interval: UpdateInterval = UpdateInterval.Every24Hours,
    val includePrereleases: Boolean = false,
    val skippedTag: String? = null,
)

data class UpdateState(
    val supported: Boolean,
    val preferences: UpdatePreferences = UpdatePreferences(),
    val release: AppRelease? = null,
    val lastChecked: Long = 0,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedVersion: Int? = null,
    val error: String? = null,
) {
    val hasUpdate: Boolean get() = supported && release != null && release.version.tag != preferences.skippedTag
}

internal fun newestRelease(releases: List<AppRelease>, installedCode: Int, includePrereleases: Boolean): AppRelease? =
    releases.filter { it.version.code > installedCode && (includePrereleases || !it.prerelease) }
        .maxByOrNull { it.version.code }

/** Activity recreation, navigation, services and reconnect screens are not app launches. */
internal class UpdateLaunchTracker {
    private var activities = 0
    private var recreating = false

    fun created(isReconnect: Boolean): Boolean {
        val isLaunch = activities == 0 && !recreating && !isReconnect
        activities++
        recreating = false
        return isLaunch
    }

    fun destroyed(changingConfigurations: Boolean) {
        activities = (activities - 1).coerceAtLeast(0)
        if (activities == 0) recreating = changingConfigurations
    }
}
