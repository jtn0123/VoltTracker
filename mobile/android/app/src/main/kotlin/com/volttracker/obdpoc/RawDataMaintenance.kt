package com.volttracker.obdpoc

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import com.volttracker.obdpoc.data.ObdLocalStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-owned retention for either dashboard. Lifecycle requests coalesce and give connection
 * startup a head start. The worker checks a persisted daily throttle, takes the same exclusion as
 * backup/restore only when no recording writer owns SQLite, and opens its own short-lived store.
 * Busy or failed attempts leave the timestamp untouched and retry on a later lifecycle request.
 */
internal class RawDataMaintenance(
    private val prefs: () -> SharedPreferences,
    private val maintain: (Int) -> Unit,
    private val schedule: (Runnable) -> Unit,
    private val worker: Executor,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Application.ActivityLifecycleCallbacks {
    private val pending = AtomicBoolean(false)

    fun request() {
        if (!pending.compareAndSet(false, true)) return
        try {
            schedule(
                Runnable {
                    try {
                        worker.execute {
                            try {
                                runIfDue()
                            } finally {
                                pending.set(false)
                            }
                        }
                    } catch (ex: RejectedExecutionException) {
                        pending.set(false)
                        Log.w(AppPrefs.LOG_TAG, "Raw maintenance worker unavailable", ex)
                    }
                },
            )
        } catch (ex: RejectedExecutionException) {
            pending.set(false)
            Log.w(AppPrefs.LOG_TAG, "Raw maintenance not scheduled", ex)
        }
    }

    private fun runIfDue() {
        val lease = DatabaseOperationLease.tryAcquireWhenIdle("raw maintenance") ?: return
        lease.use {
            try {
                val settings = prefs()
                val lastRun = settings.getLong(LAST_SUCCESS, 0L)
                if (lastRun > 0 && nowMs() - lastRun in 0 until INTERVAL_MS) return
                val days = settings.getInt("raw_retention_days", ObdLocalStore.DEFAULT_RAW_RETENTION_DAYS)
                if (days > 0) maintain(days)
                settings.edit(commit = true) { putLong(LAST_SUCCESS, nowMs()) }
            } catch (ex: RuntimeException) {
                Log.w(AppPrefs.LOG_TAG, "Raw maintenance failed; retrying on a later lifecycle request", ex)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) = request()

    override fun onActivityStopped(activity: Activity) = request()

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        internal const val LAST_SUCCESS = "raw_maintenance_last_success_ms"
        internal const val INTERVAL_MS = 86_400_000L
        private const val STARTUP_DELAY_MS = 10_000L

        fun install(app: Application) {
            val handler = Handler(Looper.getMainLooper())
            val worker =
                Executors.newSingleThreadExecutor { task ->
                    Thread(task, "volt-raw-maintenance").apply { isDaemon = true }
                }
            val coordinator =
                RawDataMaintenance(
                    prefs = { app.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE) },
                    maintain = { days -> ObdLocalStore(app).use { it.dbMaintenance.runStartupMaintenance(days) } },
                    schedule = { task ->
                        if (!handler.postDelayed(task, STARTUP_DELAY_MS)) {
                            throw RejectedExecutionException("Main looper is exiting")
                        }
                    },
                    worker = worker,
                )
            app.registerActivityLifecycleCallbacks(coordinator)
        }
    }
}
