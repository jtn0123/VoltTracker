package com.volttracker.obdpoc

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit

/**
 * Schedules raw-data prune/VACUUM after the dashboard is useful instead of during Activity
 * construction. The work is still best-effort, but now has last-run bookkeeping and app-visible
 * timing marks for benchmark traces.
 */
internal class StartupMaintenanceScheduler(
    private val prefs: SharedPreferences,
    private val mainHandler: Handler,
    private val submitBackground: (Runnable) -> Unit,
    private val readRetentionDays: () -> Int,
    private val isSessionActive: () -> Boolean,
    private val runStartupMaintenance: (Int) -> Int?,
    private val markStorageSummaryDirty: () -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val idleDelayMs: Long = DEFAULT_IDLE_DELAY_MS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private val runAfterIdle =
        Runnable {
            if (!isDue()) {
                StartupTrace.mark("startup_maintenance_not_due")
                return@Runnable
            }
            if (isSessionActive()) {
                recordOutcome("skipped_active_session", 0L, 0)
                StartupTrace.mark("startup_maintenance_skipped_active_session")
                return@Runnable
            }
            submitBackground(Runnable { runMaintenance() })
        }

    fun scheduleAfterDashboardReady() {
        mainHandler.removeCallbacks(runAfterIdle)
        if (!isDue()) {
            StartupTrace.mark("startup_maintenance_not_due")
            return
        }
        StartupTrace.mark("startup_maintenance_scheduled")
        mainHandler.postDelayed(runAfterIdle, idleDelayMs)
    }

    fun cancelPending() {
        mainHandler.removeCallbacks(runAfterIdle)
    }

    private fun runMaintenance() {
        val retentionDays = readRetentionDays()
        val startedAt = elapsedMs()
        StartupTrace.mark("startup_maintenance_start")
        try {
            val pruned = runStartupMaintenance(retentionDays)
            if (pruned == null) {
                recordOutcome("skipped_store_unavailable", 0L, 0)
                StartupTrace.mark("startup_maintenance_skipped_store_unavailable")
                return
            }
            val durationMs = elapsedMs() - startedAt
            recordOutcome("completed", durationMs, pruned)
            if (pruned > 0) {
                markStorageSummaryDirty()
                Log.i(AppPrefs.LOG_TAG, "Pruned $pruned raw rows older than $retentionDays days")
            }
            StartupTrace.mark("startup_maintenance_end")
        } catch (ex: RuntimeException) {
            recordOutcome("failed", elapsedMs() - startedAt, 0)
            StartupTrace.mark("startup_maintenance_failed")
            Log.w(AppPrefs.LOG_TAG, "Retention prune failed; continuing without it", ex)
        }
    }

    private fun isDue(): Boolean {
        val lastRunMs = prefs.getLong(PREF_LAST_RUN_MS, 0L)
        return lastRunMs <= 0L || nowMs() - lastRunMs >= minIntervalMs
    }

    private fun recordOutcome(
        outcome: String,
        durationMs: Long,
        prunedRows: Int,
    ) {
        prefs.edit {
            putLong(PREF_LAST_ATTEMPT_MS, nowMs())
            putString(PREF_LAST_OUTCOME, outcome)
            putLong(PREF_LAST_DURATION_MS, durationMs)
            putInt(PREF_LAST_PRUNED_ROWS, prunedRows)
            if (outcome == "completed") {
                putLong(PREF_LAST_RUN_MS, nowMs())
            }
        }
    }

    companion object {
        const val PREF_LAST_ATTEMPT_MS = "startup_maintenance_last_attempt_ms"
        const val PREF_LAST_RUN_MS = "startup_maintenance_last_run_ms"
        const val PREF_LAST_DURATION_MS = "startup_maintenance_last_duration_ms"
        const val PREF_LAST_PRUNED_ROWS = "startup_maintenance_last_pruned_rows"
        const val PREF_LAST_OUTCOME = "startup_maintenance_last_outcome"
        const val DEFAULT_IDLE_DELAY_MS = 2_500L
        private const val DEFAULT_MIN_INTERVAL_MS = 86_400_000L
    }
}
