package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupMaintenanceSchedulerTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences("startup-maintenance-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun scheduleAfterDashboardReadyWaitsForIdleThenRecordsCompletedRun() {
        var nowMs = 10_000L
        var elapsedMs = 100L
        var runCount = 0
        var dirtyCount = 0
        val scheduler =
            newScheduler(
                nowMs = { nowMs },
                elapsedMs = { elapsedMs },
                runStartupMaintenance = { retentionDays ->
                    assertEquals(45, retentionDays)
                    runCount += 1
                    elapsedMs += 37L
                    12
                },
                markStorageSummaryDirty = { dirtyCount += 1 },
            )

        scheduler.scheduleAfterDashboardReady()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(99))

        assertEquals("maintenance must wait for the idle delay", 0, runCount)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertEquals(1, runCount)
        assertEquals("pruned rows should mark the storage summary dirty", 1, dirtyCount)
        assertEquals("completed", prefs.getString(StartupMaintenanceScheduler.PREF_LAST_OUTCOME, ""))
        assertEquals(37L, prefs.getLong(StartupMaintenanceScheduler.PREF_LAST_DURATION_MS, 0L))
        assertEquals(12, prefs.getInt(StartupMaintenanceScheduler.PREF_LAST_PRUNED_ROWS, 0))
        assertEquals(nowMs, prefs.getLong(StartupMaintenanceScheduler.PREF_LAST_RUN_MS, 0L))

        nowMs += 500L
        scheduler.scheduleAfterDashboardReady()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        assertEquals("a recent completed run should satisfy the interval gate", 1, runCount)
    }

    @Test
    fun scheduleAfterDashboardReadySkipsWhenASessionIsActive() {
        var submittedBackground = false
        val scheduler =
            newScheduler(
                isSessionActive = { true },
                submitBackground = {
                    submittedBackground = true
                    it.run()
                },
            )

        scheduler.scheduleAfterDashboardReady()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        assertFalse("active sessions must not contend with startup maintenance", submittedBackground)
        assertEquals(
            "skipped_active_session",
            prefs.getString(StartupMaintenanceScheduler.PREF_LAST_OUTCOME, ""),
        )
        assertEquals(0L, prefs.getLong(StartupMaintenanceScheduler.PREF_LAST_RUN_MS, 0L))
    }

    private fun newScheduler(
        nowMs: () -> Long = { 10_000L },
        elapsedMs: () -> Long = { 100L },
        submitBackground: (Runnable) -> Unit = { it.run() },
        isSessionActive: () -> Boolean = { false },
        runStartupMaintenance: (Int) -> Int? = { 0 },
        markStorageSummaryDirty: () -> Unit = {},
    ): StartupMaintenanceScheduler =
        StartupMaintenanceScheduler(
            prefs = prefs,
            mainHandler = Handler(Looper.getMainLooper()),
            submitBackground = submitBackground,
            readRetentionDays = { 45 },
            isSessionActive = isSessionActive,
            runStartupMaintenance = runStartupMaintenance,
            markStorageSummaryDirty = markStorageSummaryDirty,
            nowMs = nowMs,
            elapsedMs = elapsedMs,
            idleDelayMs = 100L,
            minIntervalMs = 1_000L,
        )
}
