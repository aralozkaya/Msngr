package org.aralozkaya.msngr.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateModelsTest {
    @Test fun versionsMatchTheBuildContractAndSortPrereleasesBeforeStable() {
        val tags = listOf("v1.2.3-alpha.1", "v1.2.3-alpha.99", "v1.2.3-beta.1", "v1.2.3-rc.1", "v1.2.3", "v1.2.4-alpha.1", "v2.0.0")
        val versions = tags.map { checkNotNull(ReleaseVersion.parse(it)) }
        assertEquals(10_203_101, versions.first().code)
        assertEquals(10_203_999, versions[4].code)
        assertEquals(versions, versions.shuffled().sorted())
        assertEquals(2_099_999_999, ReleaseVersion.parse("v209.99.99")?.code)
    }

    @Test fun unsupportedTagsDoNotOverflowOrLookLikeUpdates() {
        listOf("1.0.0", "v210.0.0", "v1.100.0", "v1.0.100", "v01.0.0", "v1.0.0-dev", "v1.0.0-beta.0", "v1.0.0-rc.100", "v9999999999.0.0")
            .forEach { assertNull(it, ReleaseVersion.parse(it)) }
    }

    @Test fun selectionUsesVersionOrderingAndRespectsPrereleaseOptIn() {
        val installed = release("v1.0.0")
        val stable = release("v1.1.0")
        val prerelease = release("v1.2.0-beta.3", prerelease = true)
        val oldRepublishedLater = release("v0.9.0").copy(publishedAt = Long.MAX_VALUE)
        val releases = listOf(prerelease, oldRepublishedLater, installed, stable)
        assertEquals(stable, newestRelease(releases, installed.version.code, false))
        assertEquals(prerelease, newestRelease(releases, installed.version.code, true))
        assertNull(newestRelease(releases, prerelease.version.code, true))
    }

    @Test fun skippingOneTagHidesItsBadgeButDoesNotHideFutureVersions() {
        val available = release("v2.0.0")
        val state = UpdateState(supported = true, release = available,
            preferences = UpdatePreferences(skippedTag = "v2.0.0"))
        assertFalse(state.hasUpdate)
        assertTrue(state.copy(release = release("v2.0.1")).hasUpdate)
        assertTrue(state.copy(preferences = state.preferences.copy(skippedTag = null)).hasUpdate)
    }

    @Test fun onlyGenuineActivityLaunchesTriggerStartupChecks() {
        val tracker = UpdateLaunchTracker()
        assertTrue(tracker.created(isReconnect = false))
        assertFalse(tracker.created(isReconnect = false)) // Login -> main.
        tracker.destroyed(changingConfigurations = false)
        assertFalse(tracker.created(isReconnect = true))
        tracker.destroyed(changingConfigurations = false)
        tracker.destroyed(changingConfigurations = true)
        assertFalse(tracker.created(isReconnect = false)) // Rotation.
        tracker.destroyed(changingConfigurations = false)
        assertTrue(tracker.created(isReconnect = false)) // Task was closed and reopened.
    }

    @Test fun restoringOnlyAConnectionDoesNotCountAsAnAppLaunch() {
        val tracker = UpdateLaunchTracker()
        assertFalse(tracker.created(isReconnect = true))
        assertFalse(tracker.created(isReconnect = false))
    }

    private fun release(tag: String, prerelease: Boolean = false) = AppRelease(
        checkNotNull(ReleaseVersion.parse(tag)), 1, "Notes", prerelease, RELEASES_PAGE, null)
}
