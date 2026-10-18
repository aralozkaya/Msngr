package org.aralozkaya.msngr.updates

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.BuildConfig
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.ReconnectActivity
import org.aralozkaya.msngr.UpdateActivity
import org.aralozkaya.msngr.notifications.NotificationChannels
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateManager internal constructor(
    private val context: Context,
    supported: Boolean = supportsUpdates(context),
    private val installedCode: Int = BuildConfig.VERSION_CODE,
    private val releaseSource: () -> List<AppRelease> = { GitHubReleases().fetch() },
) {
    private val prefs = context.getSharedPreferences("msngr_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val mutableState = MutableStateFlow(UpdateState(
        supported = supported,
        preferences = UpdatePreferences(
            enabled = prefs.getBoolean("enabled", true),
            interval = UpdateInterval.entries.firstOrNull { it.name == prefs.getString("interval", null) }
                ?: UpdateInterval.Every24Hours,
            includePrereleases = prefs.getBoolean("prereleases", false),
            skippedTag = prefs.getString("skipped", null),
        ),
        release = storedRelease(prefs.getString("release", null))?.takeIf { it.version.code > installedCode },
        lastChecked = prefs.getLong("last_checked", 0),
    ))
    val state = mutableState.asStateFlow()

    fun initialize(application: Application) {
        val launchTracker = UpdateLaunchTracker()
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (launchTracker.created(activity is ReconnectActivity)) onAppStarted()
            }
            override fun onActivityDestroyed(activity: Activity) = launchTracker.destroyed(activity.isChangingConfigurations)
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        scope.launch {
            cleanupInstalledApks()
            if (state.value.supported) {
                schedule()
                workManager.getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK).collect { infos ->
                    val work = infos.firstOrNull { it.id.toString() == prefs.getString("download_work", null) }
                    val release = state.value.release
                    val downloaded = release?.let { apkFile(it.version.code) }?.takeIf(File::isFile)
                    mutableState.update { it.copy(
                        downloading = work?.let { info -> !info.state.isFinished } ?: it.downloading,
                        downloadProgress = work?.progress?.getFloat("progress", 0f) ?: 0f,
                        downloadedVersion = downloaded?.let { release.version.code },
                        error = if (work?.state == WorkInfo.State.FAILED) work.outputData.getString("error") else it.error,
                    ) }
                }
            } else {
                workManager.cancelUniqueWork(CHECK_WORK)
                workManager.cancelUniqueWork(START_WORK)
                workManager.cancelUniqueWork(DOWNLOAD_WORK)
            }
        }
    }

    private fun onAppStarted() {
        if (!state.value.supported || !state.value.preferences.enabled) return
        workManager.enqueueUniqueWork(START_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(networkConstraints).build())
    }

    fun configure(preferences: UpdatePreferences) {
        mutableState.update { it.copy(preferences = preferences, error = null) }
        prefs.edit {
            putBoolean("enabled", preferences.enabled)
            putString("interval", preferences.interval.name)
            putBoolean("prereleases", preferences.includePrereleases)
            putString("skipped", preferences.skippedTag)
        }
        if (!preferences.includePrereleases && state.value.release?.prerelease == true) {
            mutableState.update { it.copy(release = null) }
            prefs.edit { remove("release") }
        }
        if (!state.value.hasUpdate || !preferences.enabled) dismissNotification()
        schedule()
        if (!preferences.enabled) workManager.cancelUniqueWork(START_WORK)
    }

    fun checkManually() {
        scope.launch { check(manual = true) }
    }

    internal suspend fun check(manual: Boolean = false): Boolean = checkLock.withLock {
        if (!state.value.supported || (!manual && !state.value.preferences.enabled)) return@withLock true
        mutableState.update { it.copy(checking = true, error = null) }
        try {
            val releases = withContext(Dispatchers.IO) { releaseSource() }
            // Read preferences again: the user may change channels or disable checks during a request.
            val settings = state.value.preferences
            if (!manual && !settings.enabled) return@withLock true
            val newest = newestRelease(releases, installedCode, settings.includePrereleases)
            val checkedAt = System.currentTimeMillis()
            prefs.edit {
                putString("release", newest?.toJson()?.toString())
                putLong("last_checked", checkedAt)
            }
            mutableState.update { it.copy(release = newest, lastChecked = checkedAt,
                downloadedVersion = newest?.version?.code?.takeIf { code -> apkFile(code).isFile }) }
            if (state.value.hasUpdate && settings.enabled) notifyUpdate(checkNotNull(newest)) else dismissNotification()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message ?: context.getString(R.string.update_check_failed)) }
            false
        } finally {
            mutableState.update { it.copy(checking = false) }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (!state.value.supported || release.apkUrl == null || state.value.downloading) return
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(workDataOf("release" to release.version.code))
            .setConstraints(networkConstraints).build()
        prefs.edit { putString("download_work", request.id.toString()) }
        mutableState.update { it.copy(downloading = true, downloadProgress = 0f, error = null) }
        workManager.enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(DOWNLOAD_WORK)
        mutableState.update { it.copy(downloading = false, error = null) }
    }

    fun reportError(message: String?) { mutableState.update { it.copy(error = message) } }

    internal fun apkFile(code: Int): File = File(context.cacheDir, "updates/$code.apk")

    fun dismissNotification() { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }

    internal fun cleanupInstalledApks() {
        File(context.cacheDir, "updates").listFiles()?.forEach { file ->
            val code = file.name.removeSuffix(".apk").toIntOrNull()
            if ((code != null && code <= installedCode) ||
                System.currentTimeMillis() - file.lastModified() > TimeUnit.DAYS.toMillis(7)) file.delete()
        }
        if (storedRelease(prefs.getString("release", null))?.version?.code?.let { it <= installedCode } == true) {
            prefs.edit { remove("release"); remove("skipped"); remove("notified") }
            mutableState.update { it.copy(release = null, downloadedVersion = null,
                preferences = it.preferences.copy(skippedTag = null)) }
            dismissNotification()
        }
    }

    private fun schedule() {
        val current = state.value
        if (!current.supported || !current.preferences.enabled || current.preferences.interval.hours == 0L) {
            workManager.cancelUniqueWork(CHECK_WORK)
            return
        }
        val hours = current.preferences.interval.hours
        workManager.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS).setConstraints(networkConstraints).build())
    }

    @Suppress("MissingPermission")
    private fun notifyUpdate(release: AppRelease) {
        val notifications = NotificationManagerCompat.from(context)
        if (prefs.getString("notified", null) == release.version.tag || !notifications.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26 && notifications.getNotificationChannel(NotificationChannels.APP_UPDATES)?.importance == 0) return
        val intent = PendingIntent.getActivity(context, 501, UpdateActivity.createIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, NotificationChannels.APP_UPDATES)
                .setSmallIcon(R.drawable.ic_notification_msngr)
                .setContentTitle(context.getString(R.string.update_notification_title, release.version.tag))
                .setContentText(context.getString(R.string.update_notification_text))
                .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build())
            prefs.edit { putString("notified", release.version.tag) }
        } catch (_: SecurityException) { /* The in-app badge remains available without notification permission. */ }
    }

    companion object {
        internal const val CHECK_WORK = "app-update-check"
        internal const val START_WORK = "app-update-start-check"
        internal const val DOWNLOAD_WORK = "app-update-download"
        private const val NOTIFICATION_ID = 7_100_001
        private val networkConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        @Suppress("DEPRECATION")
        private fun supportsUpdates(context: Context): Boolean = runCatching {
            !BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "release" &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.isNotEmpty() == true
        }.getOrDefault(false)
    }
}

internal val Context.updateManager: UpdateManager
    get() = (applicationContext as MsngrApplication).updateManager

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if (applicationContext.updateManager.check() || runAttemptCount >= 2) Result.success() else Result.retry()
}

class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.updateManager.cleanupInstalledApks()
    }
}
