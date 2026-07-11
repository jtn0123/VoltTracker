package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the B1 renderer-death recovery policy (crash-vs-OOM logging, the bounded
 * auto-recovery window, the persistent error surface once the cap is hit, the always-allowed user
 * Retry) and the B2 dashboard-handshake load watchdog.
 */
class DashboardRecoveryCoordinatorTest {
    private class RecordingSeam : DashboardRecoveryCoordinator.Seam {
        var recreateCount = 0
        var destroyCount = 0
        var hideCount = 0
        var dashboardReady = false
        val shownSurfaces = ArrayList<Pair<Int, Boolean>>()
        val errorLogs = ArrayList<String>()
        val scheduled = ArrayList<Pair<Long, Runnable>>()

        override fun recreateWebView() {
            recreateCount += 1
        }

        override fun destroyWebView() {
            destroyCount += 1
        }

        override fun showErrorSurface(
            messageResId: Int,
            showRetry: Boolean,
        ) {
            shownSurfaces.add(messageResId to showRetry)
        }

        override fun hideErrorSurface() {
            hideCount += 1
        }

        override fun isDashboardReady(): Boolean = dashboardReady

        override fun scheduleDelayed(
            delayMs: Long,
            task: Runnable,
        ) {
            scheduled.add(delayMs to task)
        }

        override fun cancelScheduled(task: Runnable) {
            scheduled.removeAll { it.second === task }
        }

        override fun logError(message: String) {
            errorLogs.add(message)
        }

        /** Simulates the main handler firing every pending task (the watchdog elapsing). */
        fun firePending() {
            val pending = ArrayList(scheduled)
            scheduled.clear()
            pending.forEach { it.second.run() }
        }
    }

    private var nowMs = 0L
    private val seam = RecordingSeam()
    private val coordinator = DashboardRecoveryCoordinator(seam) { nowMs }

    @Test
    fun rendererCrashRecreatesTheWebViewAndShowsReconnecting() {
        coordinator.onRendererGone(crashed = true)

        assertEquals(1, seam.recreateCount)
        assertEquals(0, seam.destroyCount)
        assertEquals(listOf(R.string.dashboard_reconnecting to false), seam.shownSurfaces)
        assertTrue(
            "a real renderer crash must be logged as a crash: ${seam.errorLogs}",
            seam.errorLogs.any { it.contains("renderer crashed") },
        )
    }

    @Test
    fun oomRendererKillIsLoggedDistinctlyFromACrash() {
        coordinator.onRendererGone(crashed = false)

        assertEquals(1, seam.recreateCount)
        assertTrue(
            "a system kill must be logged as low-memory, not a crash: ${seam.errorLogs}",
            seam.errorLogs.any { it.contains("killed by the system") && it.contains("low memory") },
        )
        assertFalse(seam.errorLogs.any { it.contains("renderer crashed") })
    }

    @Test
    fun mainFrameLoadFailureTakesTheSameRecoveryPath() {
        coordinator.onMainFrameLoadFailed(-14, "net::ERR_FILE_NOT_FOUND")

        assertEquals(1, seam.recreateCount)
        assertEquals(listOf(R.string.dashboard_reconnecting to false), seam.shownSurfaces)
        assertTrue(
            "the main-frame error code and description must be logged: ${seam.errorLogs}",
            seam.errorLogs.any { it.contains("code=-14") && it.contains("net::ERR_FILE_NOT_FOUND") },
        )
    }

    @Test
    fun autoRecoveryIsCappedPerWindowThenShowsThePersistentSurface() {
        coordinator.onRendererGone(crashed = true)
        nowMs += 1_000
        coordinator.onRendererGone(crashed = true)
        nowMs += 1_000
        coordinator.onRendererGone(crashed = true)

        assertEquals("only the capped number of auto-recreates may run", 2, seam.recreateCount)
        assertEquals("the third death must tear the WebView down for good", 1, seam.destroyCount)
        assertEquals(
            "the give-up surface is persistent and offers Retry",
            R.string.dashboard_failed_repeatedly to true,
            seam.shownSurfaces.last(),
        )
        assertTrue(
            "hitting the cap must be logged: ${seam.errorLogs}",
            seam.errorLogs.any { it.contains("recovery cap reached") },
        )
    }

    @Test
    fun autoRecoveryBudgetRefillsAfterTheWindowElapses() {
        coordinator.onRendererGone(crashed = true)
        nowMs += 1_000
        coordinator.onRendererGone(crashed = true)
        assertEquals(2, seam.recreateCount)

        nowMs += DashboardRecoveryCoordinator.AUTO_RECOVERY_WINDOW_MS
        coordinator.onRendererGone(crashed = true)

        assertEquals("a death after the window must auto-recover again", 3, seam.recreateCount)
        assertEquals(0, seam.destroyCount)
    }

    @Test
    fun userRetryAlwaysRecreatesEvenAfterTheCapIsHit() {
        repeat(3) { coordinator.onRendererGone(crashed = true) }
        assertEquals(2, seam.recreateCount)

        coordinator.onRetryRequested()

        assertEquals("user Retry bypasses the auto-recovery cap", 3, seam.recreateCount)
        assertTrue("Retry must clear the error surface first", seam.hideCount >= 1)
    }

    @Test
    fun dashboardReadyClearsTheErrorSurface() {
        coordinator.onRendererGone(crashed = true)

        coordinator.onDashboardReady()

        assertTrue(seam.hideCount >= 1)
    }

    // ---- B2: dashboard-handshake load watchdog ---------------------------------------

    @Test
    fun loadStartArmsTheWatchdogWithTheNamedTimeout() {
        coordinator.onDashboardLoadStarted()

        assertEquals(1, seam.scheduled.size)
        assertEquals(DashboardRecoveryCoordinator.DASHBOARD_LOAD_TIMEOUT_MS, seam.scheduled.single().first)
    }

    @Test
    fun watchdogTimeoutShowsTheFailedToLoadSurfaceWithRetry() {
        coordinator.onDashboardLoadStarted()

        seam.firePending()

        assertEquals(listOf(R.string.dashboard_failed_to_load to true), seam.shownSurfaces)
        assertEquals("the watchdog must not tear down a still-loading WebView", 0, seam.destroyCount)
        assertEquals(0, seam.recreateCount)
        assertTrue(
            "the missed handshake must be logged: ${seam.errorLogs}",
            seam.errorLogs.any { it.contains("watchdog fired") },
        )
    }

    @Test
    fun dashboardReadyCancelsTheWatchdogBeforeItFires() {
        coordinator.onDashboardLoadStarted()

        seam.dashboardReady = true
        coordinator.onDashboardReady()

        assertTrue("ready must cancel the pending watchdog", seam.scheduled.isEmpty())
    }

    @Test
    fun lateHandshakeAfterTheTimeoutStillDismissesTheSurface() {
        coordinator.onDashboardLoadStarted()
        seam.firePending()
        assertEquals(listOf(R.string.dashboard_failed_to_load to true), seam.shownSurfaces)

        seam.dashboardReady = true
        coordinator.onDashboardReady()

        assertTrue("a slow cold start self-heals: ready clears the surface", seam.hideCount >= 1)
    }

    @Test
    fun watchdogIsANoOpIfTheDashboardTurnsOutReady() {
        coordinator.onDashboardLoadStarted()

        seam.dashboardReady = true
        seam.firePending()

        assertTrue("no surface for a ready dashboard", seam.shownSurfaces.isEmpty())
        assertTrue(seam.errorLogs.isEmpty())
    }

    @Test
    fun rearmingReplacesAnyPendingWatchdog() {
        coordinator.onDashboardLoadStarted()
        coordinator.onDashboardLoadStarted()

        assertEquals("re-arming must not stack watchdogs", 1, seam.scheduled.size)
    }

    @Test
    fun autoRecoveryRearmsTheWatchdogForTheReplacementLoad() {
        coordinator.onRendererGone(crashed = true)

        assertEquals("the recreated page gets its own handshake watchdog", 1, seam.scheduled.size)
        seam.firePending()
        assertEquals(
            "a recreate whose JS never comes up falls through to failed-to-load",
            R.string.dashboard_failed_to_load to true,
            seam.shownSurfaces.last(),
        )
    }

    @Test
    fun retryRearmsTheWatchdog() {
        coordinator.onRetryRequested()

        assertEquals(1, seam.recreateCount)
        assertEquals(1, seam.scheduled.size)
    }

    @Test
    fun disposeCancelsTheWatchdogAndBlocksRearming() {
        coordinator.onDashboardLoadStarted()

        coordinator.dispose()

        assertTrue("dispose must cancel pending watchdog work", seam.scheduled.isEmpty())
        coordinator.onDashboardLoadStarted()
        assertTrue("a disposed coordinator must not schedule new work", seam.scheduled.isEmpty())
    }

    @Test
    fun watchdogRunnableIsInertAfterDispose() {
        coordinator.onDashboardLoadStarted()
        val pending = ArrayList(seam.scheduled)

        coordinator.dispose()
        // Simulate a handler that already dequeued the runnable before dispose's cancel landed.
        pending.forEach { it.second.run() }

        assertTrue("a disposed coordinator must not show surfaces", seam.shownSurfaces.isEmpty())
    }
}
