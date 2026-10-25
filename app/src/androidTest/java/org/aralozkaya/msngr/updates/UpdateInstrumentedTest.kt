package org.aralozkaya.msngr.updates

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.aralozkaya.msngr.notifications.NotificationChannels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import org.aralozkaya.msngr.createDisplayPictureCaptureUri
import java.util.UUID

class UpdateInstrumentedTest {
    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "updates_test_${UUID.randomUUID()}_"
        return object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
    }

    @Test fun parsesOnlyTheSignedReleaseAssetAndPreservesNotesAndDate() {
        val release = checkNotNull(parseGitHubRelease(fixture()))
        assertEquals("v1.2.3-beta.4", release.version.tag)
        assertTrue(release.prerelease)
        assertEquals(1_792_337_215_000L, release.publishedAt)
        assertEquals("## Changes\n- Fixed connections", release.notes)
        assertEquals("$RELEASES_PAGE/download/v1.2.3-beta.4/Msngr-release.apk", release.apkUrl)
        assertEquals("a".repeat(64), release.sha256)
        assertEquals(release, storedRelease(release.toJson().toString()))

        val withoutSignedApk = fixture().apply { getJSONArray("assets").remove(2) }
        assertNull(parseGitHubRelease(withoutSignedApk)?.apkUrl)
        assertNull(parseGitHubRelease(fixture().put("draft", true)))
        assertNull(parseGitHubRelease(fixture().put("tag_name", "nightly")))
        assertNull(parseGitHubRelease(fixture().put("published_at", JSONObject.NULL)))
    }

    @Test fun manualChecksWorkWhileDisabledAndSkipAndChannelPreferencesSurviveRestart() = runBlocking {
        val context = isolatedContext()
        val stable = checkNotNull(parseGitHubRelease(fixture().put("tag_name", "v1.2.3").put("prerelease", false)))
        val beta = checkNotNull(parseGitHubRelease(fixture().put("tag_name", "v1.3.0-beta.1")))
        var requests = 0
        fun manager() = UpdateManager(context, supported = true, installedCode = 1,
            releaseSource = { requests++; listOf(beta, stable) })
        val first = manager()
        first.configure(first.state.value.preferences.copy(enabled = false))
        assertTrue(first.check())
        assertEquals(0, requests)
        assertTrue(first.check(manual = true))
        assertEquals(stable.version, first.state.value.release?.version)
        assertTrue(first.state.value.hasUpdate)
        first.configure(first.state.value.preferences.copy(skippedTag = stable.version.tag, interval = UpdateInterval.OnAppStart))
        val restored = manager()
        assertFalse(restored.state.value.hasUpdate)
        assertFalse(restored.state.value.preferences.enabled)
        assertEquals(UpdateInterval.OnAppStart, restored.state.value.preferences.interval)
        restored.configure(restored.state.value.preferences.copy(includePrereleases = true))
        assertTrue(restored.check(manual = true))
        assertEquals(beta.version, restored.state.value.release?.version)
        assertTrue(restored.state.value.hasUpdate)
        restored.configure(restored.state.value.preferences.copy(includePrereleases = false))
        assertNull(restored.state.value.release)
        assertTrue(restored.check(manual = true))
        assertEquals(stable.version, restored.state.value.release?.version)
        assertFalse(restored.state.value.hasUpdate) // The skipped stable release stays skipped.
        context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }

    @Test fun unsupportedBuildNeverMakesARequestAndFailedChecksKeepKnownUpdates() = runBlocking {
        val context = isolatedContext()
        val unsupported = UpdateManager(context, supported = false, releaseSource = { error("Must not request GitHub") })
        assertTrue(unsupported.check(manual = true))
        assertFalse(unsupported.state.value.hasUpdate)
        var fails = false
        val available = checkNotNull(parseGitHubRelease(fixture()))
        val manager = UpdateManager(context, supported = true, installedCode = 1,
            releaseSource = { if (fails) error("Offline") else listOf(available) })
        manager.configure(UpdatePreferences(enabled = false, includePrereleases = true))
        assertTrue(manager.check(manual = true))
        val lastChecked = manager.state.value.lastChecked
        fails = true
        assertFalse(manager.check(manual = true))
        assertEquals(available, manager.state.value.release)
        assertEquals(lastChecked, manager.state.value.lastChecked)
        assertEquals("Offline", manager.state.value.error)
        assertFalse(manager.state.value.checking)
        context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }

    @Test fun downloadUrlsRejectUnsignedAssetsAndOtherOrigins() {
        assertTrue(isReleaseApkUrl("$RELEASES_PAGE/download/v1.0.0/Msngr-release.apk"))
        listOf(
            "$RELEASES_PAGE/download/v1.0.0/Msngr-debug.apk",
            "$RELEASES_PAGE/download/v1.0.0/Msngr-release-unsigned.apk",
            "http://github.com/aralozkaya/Msngr/releases/download/v1.0.0/Msngr-release.apk",
            "https://github.com.evil.test/aralozkaya/Msngr/releases/download/v1.0.0/Msngr-release.apk",
            "https://github.com/someone/else/releases/download/v1.0.0/Msngr-release.apk",
            "$RELEASES_PAGE/download/v1.0.0/Msngr-release.apk?redirect=evil",
        ).forEach { assertFalse(it, isReleaseApkUrl(it)) }
    }

    @Test fun updateNotificationsUseTheirOwnChannelAndSkippingDismissesThem() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = isolatedContext()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val manager = UpdateManager(context, supported = true, installedCode = 1,
            releaseSource = { listOf(checkNotNull(parseGitHubRelease(fixture()))) })
        fun activeUpdate() = notifications.activeNotifications.firstOrNull { it.notification.channelId == NotificationChannels.APP_UPDATES }
        try {
            manager.dismissNotification()
            manager.configure(UpdatePreferences(includePrereleases = true))
            assertTrue(manager.check(manual = true))
            for (attempt in 0..30) { if (activeUpdate() != null) break; SystemClock.sleep(50) }
            val notification = checkNotNull(activeUpdate()).notification
            assertEquals("Msngr v1.2.3-beta.4 is available", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            assertNotNull(notification.contentIntent)
            manager.configure(manager.state.value.preferences.copy(skippedTag = "v1.2.3-beta.4"))
            for (attempt in 0..30) { if (activeUpdate() == null) break; SystemClock.sleep(50) }
            assertNull(activeUpdate())
            assertTrue(manager.check(manual = true))
            assertNull(activeUpdate())
        } finally {
            manager.configure(UpdatePreferences(enabled = false))
            manager.dismissNotification()
            context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
        }
        Unit
    }

    @Test fun invalidApksAndChecksumMismatchesCannotBeInstalledOrExported() {
        val context = isolatedContext()
        val file = File.createTempFile("invalid-update", ".apk", context.cacheDir)
        try {
            file.writeText("This is not a signed APK")
            val release = checkNotNull(parseGitHubRelease(fixture())).copy(apkSize = file.length())
            fun rejected(candidate: AppRelease, expected: String) {
                try {
                    verifyUpdateApk(context, file, candidate)
                    fail("The invalid APK was accepted")
                } catch (error: IOException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expected)) }
            }
            rejected(release.copy(apkSize = file.length() + 1), "size")
            rejected(release, "checksum")
            rejected(release.copy(sha256 = null), "valid signed APK")
        } finally { file.delete() }
    }

    @Test fun downloadedApkCanBeReadThroughItsProviderAlongsideDisplayPictures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File.createTempFile("provider-test-", ".apk", directory)
        val photo = context.createDisplayPictureCaptureUri()
        try {
            val photoContents = "Camera image contents"
            context.contentResolver.openOutputStream(photo)!!.use { it.write(photoContents.toByteArray()) }
            val contents = "Downloaded APK contents"
            apk.writeText(contents)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            assertEquals(APK_MIME_TYPE, context.contentResolver.getType(uri))
            assertEquals(contents, context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() })
            assertEquals("image/jpeg", context.contentResolver.getType(photo))
            assertEquals(photoContents, context.contentResolver.openInputStream(photo)?.bufferedReader()?.use { it.readText() })
        } finally {
            apk.delete()
            context.contentResolver.delete(photo, null, null)
        }
    }

    @Test fun installedUpdateCleanupDeletesItsApkAndOldPartialsButPreservesFutureDownloads() = runBlocking {
        val target = isolatedContext()
        val cache = File(target.cacheDir, "updates_cleanup_${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getCacheDir() = cache }
        val release = checkNotNull(parseGitHubRelease(fixture()))
        val before = UpdateManager(context, supported = true, installedCode = 1, releaseSource = { listOf(release) })
        before.configure(UpdatePreferences(enabled = false, includePrereleases = true))
        before.check(manual = true)
        before.configure(before.state.value.preferences.copy(skippedTag = release.version.tag))
        val directory = File(cache, "updates").apply { mkdirs() }
        val installedApk = File(directory, "${release.version.code}.apk").apply { writeText("installed APK") }
        val futureApk = File(directory, "${release.version.code + 1}.apk").apply { writeText("future APK") }
        val oldPartial = File(directory, "abandoned.partial.apk").apply {
            writeText("partial")
            setLastModified(System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(8))
        }
        try {
            val after = UpdateManager(context, supported = true, installedCode = release.version.code)
            after.cleanupInstalledApks()
            assertFalse(installedApk.exists())
            assertFalse(oldPartial.exists())
            assertTrue(futureApk.exists())
            assertNull(after.state.value.release)
            assertNull(after.state.value.preferences.skippedTag)
            assertNull(context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).getString("release", null))
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            cache.delete()
            context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE).edit().clear().commit()
        }
        Unit
    }

    private fun fixture() = JSONObject("""{
      "tag_name":"v1.2.3-beta.4", "draft":false, "prerelease":true,
      "published_at":"2026-10-18T15:26:55Z", "body":"## Changes\n- Fixed connections",
      "assets":[
        {"name":"Msngr-debug.apk", "state":"uploaded", "browser_download_url":"$RELEASES_PAGE/download/v1.2.3-beta.4/Msngr-debug.apk"},
        {"name":"Msngr-release-unsigned.apk", "state":"uploaded", "browser_download_url":"$RELEASES_PAGE/download/v1.2.3-beta.4/Msngr-release-unsigned.apk"},
        {"name":"Msngr-release.apk", "state":"uploaded", "size":12345, "digest":"sha256:${"a".repeat(64)}", "browser_download_url":"$RELEASES_PAGE/download/v1.2.3-beta.4/Msngr-release.apk"}
      ]
    }""")
}
