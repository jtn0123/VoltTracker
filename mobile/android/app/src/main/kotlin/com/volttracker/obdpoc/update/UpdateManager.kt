package com.volttracker.obdpoc.update

import android.content.Context
import android.util.Log
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.BuildConfig
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks GitHub Releases for a newer build, downloads it, and hands it to
 * Android's package installer.
 *
 * Two things this deliberately does NOT do:
 * 1. It does not install anything — Android does. The system shows its own
 *    confirmation sheet; there is no silent path for a sideloaded app.
 * 2. It does not trust the download blindly — [ApkSignerCheck] confirms the
 *    APK is this app signed with this key before the install sheet opens.
 *    Android repeats that check itself (and is why the release workflow never
 *    rotates the signing key); checking first turns a wrong asset into a plain
 *    failed download instead of a confusing installer error.
 *
 * Threading: the public entry points are called on the main thread; network and
 * file work runs on [executor]; every callback lands on [mainThread]. The only
 * state shared across those threads is [busy], an atomic flag.
 */
class UpdateManager internal constructor(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val mainThread: Executor,
    /** Injectable for tests; the default does a real HTTPS GET. */
    private val fetcher: (String) -> String = ::defaultFetch,
    private val runningVersionCode: Int = BuildConfig.VERSION_CODE,
    private val wantDebugVariant: Boolean = BuildConfig.DEBUG,
    /** Injectable for tests; the default compares package name and signing key. */
    private val verifyApk: (File) -> Boolean = { apk -> ApkSignerCheck.verify(context, apk) },
    /** Injectable for tests; the default opens a real connection to the release asset. */
    private val openConnection: (URL) -> HttpURLConnection = ::defaultOpenConnection,
    /** Injectable for tests; the default opens Android's install sheet. */
    private val installer: ApkInstaller = SystemApkInstaller(context),
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

    /** One step of [downloadAndInstall]. Every call ends with exactly one terminal event. */
    sealed interface InstallEvent {
        /** Bytes are arriving, 0..99. Not terminal. */
        data class Downloading(
            val percent: Int,
        ) : InstallEvent

        /** Android's install sheet is open; confirming is the user's step. Terminal. */
        data object InstallerOpened : InstallEvent

        /** Nothing reached the installer. Terminal. */
        data class Failed(
            val reason: InstallFailure,
        ) : InstallEvent
    }

    /** Why an update did not reach the installer; each maps to one actionable message. */
    enum class InstallFailure {
        /** Another check or download is still running, or the worker could not take the job. */
        BUSY,

        /** The connection dropped or never opened. */
        NETWORK,

        /** GitHub answered with something other than the file, or the link was unusable. */
        SERVER,

        /** The file was not the size the release advertised. */
        INCOMPLETE,

        /** The file is not this app, or not signed with this app's key. */
        NOT_THIS_APP,

        /** The download could not be written to the app's cache. */
        STORAGE,

        /** The APK was good but Android's installer could not be opened. */
        INSTALLER,

        /** Anything else; logged with its stack trace. */
        UNEXPECTED,
    }

    private val busy = AtomicBoolean(false)

    private val downloader = ApkDownloader(File(context.cacheDir, UPDATES_DIR), openConnection, verifyApk)

    /** Asks GitHub what the newest installable build is. Callback on [mainThread]. */
    fun checkForUpdate(onResult: (CheckResult) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            // Every caller gets a terminal answer — a silently dropped request
            // would leave the UI stuck on its previous state.
            mainThread.execute { onResult(CheckResult.Failed("An update operation is already running")) }
            return
        }
        submitOrRelease(onRejected = { onResult(CheckResult.Failed("Couldn't start the update check — try again")) }) {
            val result =
                try {
                    checkNow()
                } finally {
                    busy.set(false)
                }
            mainThread.execute { onResult(result) }
        }
    }

    /**
     * Downloads [build]'s APK into the updates cache and opens the install
     * sheet. Events land on [mainThread]: any number of [InstallEvent.Downloading]
     * ticks, then exactly one [InstallEvent.InstallerOpened] or [InstallEvent.Failed].
     * The operation stays [busy] until the installer launch has been decided, so a
     * second tap cannot wipe the updates dir under a file the installer is opening.
     */
    fun downloadAndInstall(
        build: UpdateFeed.AvailableBuild,
        onEvent: (InstallEvent) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            // Terminal answer for the same reason as checkForUpdate's busy path.
            mainThread.execute { onEvent(InstallEvent.Failed(InstallFailure.BUSY)) }
            return
        }
        submitOrRelease(onRejected = { onEvent(InstallEvent.Failed(InstallFailure.BUSY)) }) {
            var handedToMain = false
            try {
                val result =
                    downloader.download(build) { pct ->
                        mainThread.execute { onEvent(InstallEvent.Downloading(pct)) }
                    }
                mainThread.execute { finishInstall(result, onEvent) }
                handedToMain = true
            } finally {
                // Something fatal escaped the download: free the slot so the next
                // request is not refused as "busy" for the rest of the process.
                if (!handedToMain) busy.set(false)
            }
        }
    }

    /** Main thread. Launching here makes a failed launch the terminal event, not a log line. */
    private fun finishInstall(
        result: ApkDownloader.Result,
        onEvent: (InstallEvent) -> Unit,
    ) {
        val event =
            try {
                when (result) {
                    is ApkDownloader.Result.Ready ->
                        if (installer.launch(result.apk)) {
                            InstallEvent.InstallerOpened
                        } else {
                            InstallEvent.Failed(InstallFailure.INSTALLER)
                        }
                    is ApkDownloader.Result.Failed -> InstallEvent.Failed(result.reason)
                }
            } finally {
                busy.set(false)
            }
        onEvent(event)
    }

    /**
     * Runs [job] on [executor] while the caller holds [busy]. A rejected job
     * (executor shut down or saturated) releases the flag and still delivers
     * [onRejected] on [mainThread], so the caller gets its terminal answer.
     */
    private fun submitOrRelease(
        onRejected: () -> Unit,
        job: () -> Unit,
    ) {
        try {
            executor.execute(job)
        } catch (ex: RejectedExecutionException) {
            Log.w(AppPrefs.LOG_TAG, "update worker rejected the job", ex)
            busy.set(false)
            mainThread.execute(onRejected)
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
            } catch (ex: RuntimeException) {
                Log.w(AppPrefs.LOG_TAG, "update check failed", ex)
                return CheckResult.Failed("The update check failed — try again")
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

    internal companion object {
        const val RELEASES_API = "https://api.github.com/repos/jtn0123/VoltTracker/releases"
        const val RELEASES_PAGE = 10
        const val CHECK_TIMEOUT_MS = 15_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        const val UPDATES_DIR = "updates"
        const val USER_AGENT = "VoltTracker-Android"

        fun defaultOpenConnection(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection

        fun defaultFetch(url: String): String {
            val conn = defaultOpenConnection(URL(url))
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
