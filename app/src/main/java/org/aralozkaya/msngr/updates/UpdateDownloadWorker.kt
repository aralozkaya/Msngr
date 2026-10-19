package org.aralozkaya.msngr.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class UpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = applicationContext.updateManager
        val release = manager.state.value.release?.takeIf { it.version.code == inputData.getInt("release", -1) }
            ?: return@withContext Result.failure(workDataOf("error" to "The release changed. Please check for updates again."))
        if (!manager.state.value.supported || !isReleaseApkUrl(release.apkUrl.orEmpty())) return@withContext Result.failure()
        val destination = manager.apkFile(release.version.code)
        val partial = File(destination.parentFile, "${release.version.code}.$id.partial.apk")
        try {
            destination.parentFile?.mkdirs()
            if (destination.isFile) {
                verifyUpdateApk(applicationContext, destination, release)
                return@withContext Result.success()
            }
            val connection = openApkConnection(checkNotNull(release.apkUrl))
            try {
                val expectedSize = release.apkSize
                if (expectedSize <= 0 || expectedSize > MAX_APK_SIZE) throw IOException("The APK size is invalid.")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var previousPercent = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > expectedSize) throw IOException("The downloaded APK has an unexpected size.")
                            output.write(buffer, 0, count)
                            val percent = (received * 100 / expectedSize).toInt()
                            if (percent != previousPercent) {
                                setProgress(workDataOf("progress" to received.toFloat() / expectedSize))
                                previousPercent = percent
                            }
                        }
                        if (received != expectedSize) throw IOException("The APK download was incomplete. Please try again.")
                    }
                }
            } finally {
                connection.disconnect()
            }
            verifyUpdateApk(applicationContext, partial, release)
            coroutineContext.ensureActive()
            if (!partial.renameTo(destination)) throw IOException("The APK could not be saved in temporary storage.")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            destination.delete()
            Result.failure(workDataOf("error" to (error.message ?: "The update could not be downloaded.")))
        } finally {
            partial.delete()
        }
    }
}

private const val MAX_APK_SIZE = 512L * 1024 * 1024

private fun openApkConnection(url: String): HttpURLConnection {
    var current = url
    repeat(10) {
        val uri = URI(current)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            (uri.host != "github.com" && !uri.host.orEmpty().endsWith(".githubusercontent.com"))) {
            throw IOException("GitHub returned an unsupported download address.")
        }
        val connection = openUpdateConnection(current).apply { instanceFollowRedirects = false }
        val code = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
        if (code in 200..299) return connection
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (code !in listOf(301, 302, 303, 307, 308) || location == null) throw IOException("The APK download failed (HTTP $code).")
        current = uri.resolve(location).toString()
    }
    throw IOException("GitHub redirected the download too many times.")
}

@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, release: AppRelease) {
    if (file.length() != release.apkSize) throw IOException("The downloaded APK has an unexpected size.")
    release.sha256?.let { expected ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("The APK checksum does not match GitHub's release asset.")
    }
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("The downloaded file is not a valid signed APK.")
    val installed = pm.getPackageInfo(context.packageName, flags)
    fun PackageInfo.code(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
    if (archive.packageName != context.packageName || archive.code() != release.version.code.toLong() || archive.code() <= installed.code()) {
        throw IOException("The APK does not match this app and release version.")
    }
    if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) throw IOException("This update needs a newer Android version.")
    fun PackageInfo.signers(): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
            .orEmpty().map { it.toCharsString() }.toSet()
    if (archive.signers().isEmpty() || archive.signers() != installed.signers()) {
        throw IOException("The update was signed with a different key and cannot update this installation.")
    }
}
