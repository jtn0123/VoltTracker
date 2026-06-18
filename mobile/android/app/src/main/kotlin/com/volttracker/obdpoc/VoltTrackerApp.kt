package com.volttracker.obdpoc

import android.app.Application
import android.os.StrictMode

/**
 * Process-wide bootstrap. In debug builds it arms StrictMode so accidental main-thread
 * disk/network I/O and leaked resources surface in logcat during development; release
 * builds get no policy (zero overhead, no behavior change).
 */
class VoltTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupTrace.reset("app_on_create")
        if (BuildConfig.DEBUG) {
            installStrictMode()
        }
        StartupTrace.mark("app_on_create_complete")
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
