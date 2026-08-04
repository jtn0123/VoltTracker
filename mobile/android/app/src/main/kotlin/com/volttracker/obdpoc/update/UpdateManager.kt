package com.volttracker.obdpoc.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.BuildConfig
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks GitHub Releases for a newer build, downloads it, and hands it to
 * Android's package installer.
 *
 * Two things this deliberately does NOT do:
 * 1. It does not install anything — Android does. The system shows its own
 *    confirmation sheet; there is no silent path for a sideloaded app.
 * 2. It does not verify the download itself — Android refuses an APK signed
 *    with a different key than the installed app, which is the real check
 *    (and why the release workflow never rotates the signing key).
 */
class UpdateManager(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val mainThread: Executor,
    /** Injectable for tests; the default does a real HTTPS GET. */
    private val fetcher: (String) -> String = ::defaultFetch,
    private val runningVersionCode: Int = BuildConfig.VERSION_CODE,
    private val wantDebugVariant: Boolean = BuildConfig.DEBUG,
) {
    sealed interface CheckResult {
        /** A newer build is published and downloadable. */
        data class UpdateAvailable(
            val build: UpdateFeed.AvailableBuild,
        ) : CheckResult

        /** This install matches (or is newer than) the newest published APK. */
        data object UpToDate : CheckResult

        /** No published release carries an APK yet. */
        data object NoBuilds : CheckResult

        /** Version comparison was not possible (dev build or odd tag). */
        data class Unknown(
            val build: UpdateFeed.AvailableBuild,
        ) : CheckResult

        /** The network did not answer. */
        data object Offline : CheckResult

        data class Failed(
            val detail: String,
        ) : CheckResult
    }

    private val busy = AtomicBoolean(false)

    /** Asks GitHub what the newest installable build is. Callback on [mainThread]. */
    fun checkForUpdate(onResult: (CheckResult) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            // Every caller gets a terminal answer — a silently dropped request
            // would leave the UI stuck on its previous state.
            mainThread.execute { onResult(CheckResult.Failed("An update operation is already running")) }
            return
        }
        executor.execute {
            val result = checkNow()
            busy.set(false)
            mainThread.execute { onResult(result) }
        }
    }

    /**
     * Downloads [build]'s APK into the updates cache and launches the install
     * sheet. Progress lands on [mainThread] as 0..100; a negative value means
     * the download failed and [onProgress] receives -1 exactly once.
     */
    fun downloadAndInstall(
        build: UpdateFeed.AvailableBuild,
        onProgress: (percent: Int) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            // Terminal answer for the same reason as checkForUpdate's busy path.
            mainThread.execute { onProgress(-1) }
            return
        }
        executor.execute {
            val file =
                try {
                    download(build) { pct -> mainThread.execute { onProgress(pct) } }
                } catch (ex: IOException) {
                    Log.w(AppPrefs.LOG_TAG, "update download failed", ex)
                    null
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "update download failed", ex)
                    null
                }
            busy.set(false)
            mainThread.execute {
                if (file != null) {
                    onProgress(100)
                    launchInstaller(file)
                } else {
                    onProgress(-1)
                }
            }
        }
    }

    /** The decision half of the check, synchronous and test-reachable. */
    internal fun checkNow(): CheckResult {
        val body =
            try {
                fetcher("$RELEASES_API?per_page=$RELEASES_PAGE")
            } catch (ex: IOException) {
                Log.w(AppPrefs.LOG_TAG, "update check offline", ex)
                return CheckResult.Offline
            }
        val releases =
            try {
                JSONArray(body)
            } catch (ex: org.json.JSONException) {
                Log.w(AppPrefs.LOG_TAG, "update check parse failure", ex)
                return CheckResult.Failed("GitHub sent something that was not a releases list")
            }
        val build =
            UpdateFeed.pickBuild(releases, wantDebugVariant = wantDebugVariant)
                ?: return CheckResult.NoBuilds
        return when (UpdateFeed.compare(runningVersionCode, build.versionCode)) {
            UpdateFeed.Comparison.NEWER -> CheckResult.UpdateAvailable(build)
            UpdateFeed.Comparison.CURRENT, UpdateFeed.Comparison.OLDER -> CheckResult.UpToDate
            UpdateFeed.Comparison.UNKNOWN -> CheckResult.Unknown(build)
        }
    }

    /**
     * Streams the APK to the updates cache dir. Anything downloaded earlier is
     * thrown away first: a half-finished file from a cancelled attempt must
     * never be offered to the installer as though it were complete, and stale
     * 20 MB APKs add up.
     */
    private fun download(
        build: UpdateFeed.AvailableBuild,
        onProgress: (Int) -> Unit,
    ): File {
        val dir = File(context.cacheDir, UPDATES_DIR)
        dir.deleteRecursively()
        check(dir.mkdirs()) { "could not create updates dir" }
        // Both fields come from the GitHub API response, not from the app:
        // the asset name must not be able to path-traverse out of the updates
        // dir, and the bytes must not travel over cleartext.
        val destination = File(dir, UpdateFeed.safeAssetFileName(build.assetName))
        val url = URL(build.downloadUrl)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("refusing non-https download url")
        }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CHECK_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("download answered $status")
            }
            // Content-Length may be absent; the release already told us the size.
            // (contentLength, not contentLengthLong: the Long variant is API 24+
            // and minSdk is 23 — an APK will not overflow Int.)
            val total = conn.contentLength.toLong().takeIf { it > 0 } ?: build.sizeBytes.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                destination.outputStream().use { output ->
                    copyWithProgress(input, output, total, onProgress)
                }
            }
            // A stream that ends early "succeeds" as far as copy is concerned;
            // a truncated APK must fail here, not as an installer parse error.
            if (total > 0 && destination.length() != total) {
                throw IOException("download truncated at ${destination.length()} of $total bytes")
            }
            return destination
        } finally {
            conn.disconnect()
        }
    }

    internal fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var written = 0L
        var lastPct = -1
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read
            if (totalBytes <= 0) continue
            val pct = ((written * 99) / totalBytes).toInt()
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
            }
        }
    }

    /**
     * Hands the APK to the package installer as a `content://` URI from the
     * app's FileProvider — bare `file://` URIs have been refused since
     * Android 7, and the read grant travels with the intent flag. The install
     * itself belongs to the system from here.
     */
    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "installer launch failed", ex)
        }
    }

    internal companion object {
        const val RELEASES_API = "https://api.github.com/repos/jtn0123/VoltTracker/releases"
        const val RELEASES_PAGE = 10
        const val CHECK_TIMEOUT_MS = 15_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val UPDATES_DIR = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "VoltTracker-Android"

        fun defaultFetch(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CHECK_TIMEOUT_MS
                conn.readTimeout = CHECK_TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub answered $status")
                }
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
