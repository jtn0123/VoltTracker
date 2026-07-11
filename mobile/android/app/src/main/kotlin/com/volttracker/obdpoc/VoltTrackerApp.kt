package com.volttracker.obdpoc

import android.app.Application
import android.os.StrictMode
import android.util.Log
import java.io.File

/**
 * Process-wide bootstrap. Installs the uncaught-exception crash capture (report item B4) so a
 * fatal crash lands in the rolling app log — previously the diagnostics bundle recorded
 * everything *except* the crash that killed the process. In debug builds it also arms StrictMode
 * so accidental main-thread disk/network I/O and leaked resources surface in logcat during
 * development; release builds get no policy (zero overhead, no behavior change).
 */
class VoltTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupTrace.reset("app_on_create")
        installCrashCapture()
        if (BuildConfig.DEBUG) {
            installStrictMode()
        }
        StartupTrace.mark("app_on_create_complete")
    }

    private fun installCrashCapture() {
        // The crash logger appends to the same files/app-log/app.log the service's OBDLog mirror
        // uses (interleaving is line-safe; see RollingAppLog.DIR_NAME). Best-effort: a failure to
        // arm crash capture must never break app startup itself.
        try {
            UncaughtCrashLogger.install(RollingAppLog(File(filesDir, RollingAppLog.DIR_NAME)))
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "crash capture install failed; continuing without it", ex)
        }
    }

    private fun installStrictMode() {
        // Log-only penalties: violations should be visible, never fatal, so an OS-version
        // quirk or a third-party lib can't crash a dev build.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}
