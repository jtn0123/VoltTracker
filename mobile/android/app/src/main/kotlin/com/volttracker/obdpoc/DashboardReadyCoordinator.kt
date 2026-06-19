package com.volttracker.obdpoc

import android.os.Handler

/**
 * Owns the native side of the JS dashboard-ready handshake: mark the page ready immediately, then
 * delay heavier refresh work until the Activity is resumed and the first dashboard paint has room.
 */
internal class DashboardReadyCoordinator(
    private val mainHandler: Handler,
    private val host: Host,
    private val postReadyRefreshDelayMs: Long,
) {
    interface Host {
        fun dashboardPublisher(): DashboardPublisher?

        fun isActivityResumed(): Boolean

        fun isActivityGone(): Boolean

        fun markDashboardContentReady()

        fun publishPostReadyDashboardRefresh()
    }

    private var postReadyDashboardRefreshPending = false
    private val postReadyDashboardRefreshRunnable =
        Runnable {
            if (!postReadyDashboardRefreshPending || !host.isActivityResumed() || host.isActivityGone()) {
                return@Runnable
            }
            postReadyDashboardRefreshPending = false
            host.publishPostReadyDashboardRefresh()
        }

    fun onDashboardReady(): Boolean {
        StartupTrace.mark("dashboard_ready_bridge_start")
        val publisher = host.dashboardPublisher() ?: return false
        if (publisher.isPageReady()) {
            return false
        }
        publisher.setPageReady(true)
        host.markDashboardContentReady()
        StartupTrace.mark("dashboard_ready_post_work_scheduled")
        postReadyDashboardRefreshPending = true
        schedulePendingRefresh()
        StartupTrace.mark("dashboard_ready_native_complete")
        return true
    }

    fun onResume() {
        if (postReadyDashboardRefreshPending) {
            schedulePendingRefresh()
        }
    }

    fun onPause() {
        mainHandler.removeCallbacks(postReadyDashboardRefreshRunnable)
    }

    fun dispose() {
        postReadyDashboardRefreshPending = false
        mainHandler.removeCallbacks(postReadyDashboardRefreshRunnable)
    }

    private fun schedulePendingRefresh() {
        mainHandler.removeCallbacks(postReadyDashboardRefreshRunnable)
        mainHandler.postDelayed(postReadyDashboardRefreshRunnable, postReadyRefreshDelayMs)
    }
}
