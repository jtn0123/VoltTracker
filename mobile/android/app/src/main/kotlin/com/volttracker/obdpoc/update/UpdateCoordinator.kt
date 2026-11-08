package com.volttracker.obdpoc.update

import android.content.Context
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

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

    // Atomic, not a @Volatile check-then-set: the coordinator is process-wide, so the
    // once-per-process guarantee must not depend on every caller being on one thread.
    private val autoChecked = AtomicBoolean(false)

    private val manager: UpdateManager by lazy(managerFactory)

    fun availableBuild(): UpdateFeed.AvailableBuild? = (lastResult as? UpdateManager.CheckResult.UpdateAvailable)?.build

    /**
     * Runs the once-per-process automatic check. Returns false (and does
     * nothing) when it already ran — the caller republishes [lastResult]
     * instead.
     */
    fun autoCheckOnce(onResult: (UpdateManager.CheckResult) -> Unit): Boolean {
        if (!autoChecked.compareAndSet(false, true)) return false
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

    /**
     * Downloads and installs [build] — the one the caller showed the user, passed in rather
     * than re-read from [lastResult], which a later check may have replaced (an Offline
     * result, say). Ends with exactly one terminal [UpdateManager.InstallEvent].
     */
    fun downloadAndInstall(
        build: UpdateFeed.AvailableBuild,
        onEvent: (UpdateManager.InstallEvent) -> Unit,
    ) {
        manager.downloadAndInstall(build, onEvent)
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
