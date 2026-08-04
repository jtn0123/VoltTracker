package com.volttracker.obdpoc.update

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Process-wide owner of the update flow, so Activity recreation neither
 * repeats the automatic release check nor forgets an already-offered build.
 * The Activity is a view of this state: it republishes [lastResult] into its
 * store on create and forwards user actions here.
 */
class UpdateCoordinator internal constructor(
    private val managerFactory: () -> UpdateManager,
) {
    /** The newest check outcome; survives configuration recreation. */
    @Volatile
    var lastResult: UpdateManager.CheckResult? = null
        private set

    @Volatile
    private var autoChecked = false

    private val manager: UpdateManager by lazy(managerFactory)

    fun availableBuild(): UpdateFeed.AvailableBuild? = (lastResult as? UpdateManager.CheckResult.UpdateAvailable)?.build

    /**
     * Runs the once-per-process automatic check. Returns false (and does
     * nothing) when it already ran — the caller republishes [lastResult]
     * instead.
     */
    fun autoCheckOnce(onResult: (UpdateManager.CheckResult) -> Unit): Boolean {
        if (autoChecked) return false
        autoChecked = true
        check(onResult)
        return true
    }

    /** A user-requested check; always allowed to run again. */
    fun check(onResult: (UpdateManager.CheckResult) -> Unit) {
        manager.checkForUpdate { result ->
            lastResult = result
            onResult(result)
        }
    }

    /** Downloads and installs the offered build, if one is on record. */
    fun downloadAndInstall(onProgress: (Int) -> Unit) {
        val build = availableBuild() ?: return
        manager.downloadAndInstall(build, onProgress)
    }

    companion object {
        @Volatile
        private var shared: UpdateCoordinator? = null

        /** The one instance per process, bound to the application context. */
        fun shared(context: Context): UpdateCoordinator =
            shared ?: synchronized(this) {
                shared ?: run {
                    val app = context.applicationContext
                    UpdateCoordinator {
                        UpdateManager(app, mainThread = ContextCompat.getMainExecutor(app))
                    }.also { shared = it }
                }
            }
    }
}
