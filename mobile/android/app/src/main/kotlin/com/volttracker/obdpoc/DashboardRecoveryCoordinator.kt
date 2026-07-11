package com.volttracker.obdpoc

/**
 * Decision core for the "app never dies silently" dashboard resilience paths: WebView renderer
 * death / main-frame load-failure recovery (report item B1) and the dashboard-handshake load
 * watchdog (report item B2).
 *
 * The coordinator owns only policy and timing; every side effect (tearing down / recreating the
 * WebView, showing the native error surface, scheduling) goes through the [Seam] so the whole
 * decision tree is unit-testable on the plain JVM, mirroring [DashboardBroadcastCoordinator].
 *
 * Threading: every entry point runs on the main thread — [onRendererGone] /
 * [onMainFrameLoadFailed] arrive on WebViewClient callbacks, [onDashboardReady] is posted to the
 * UI thread by [VoltBridge.dashboardReady], [onRetryRequested] is a button click, and the
 * watchdog runnable is posted to the main handler — so no internal synchronization is needed.
 */
class DashboardRecoveryCoordinator(
    private val seam: Seam,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Host side effects. Implemented by [MainActivity] as an anonymous object. */
    interface Seam {
        /** Tears down the current (possibly dead) WebView and builds + loads a fresh one. */
        fun recreateWebView()

        /** Tears down the current WebView without a replacement (recovery gave up). */
        fun destroyWebView()

        /** Shows the native error/reconnecting surface over (or instead of) the WebView. */
        fun showErrorSurface(
            messageResId: Int,
            showRetry: Boolean,
        )

        /** Hides the native error surface if it is showing. */
        fun hideErrorSurface()

        /** True once the dashboard JS handshake ([DashboardHost.onDashboardReady]) has landed. */
        fun isDashboardReady(): Boolean

        /** Schedules [task] on the main thread after [delayMs]. */
        fun scheduleDelayed(
            delayMs: Long,
            task: Runnable,
        )

        /** Cancels a pending [scheduleDelayed] task (no-op if it is not scheduled). */
        fun cancelScheduled(task: Runnable)

        /** Error-level log line; the host tees it into the rolling app log via [OBDLog]. */
        fun logError(message: String)
    }

    private val autoRecoveryTimesMs = ArrayDeque<Long>()
    private val loadWatchdogTimeout = Runnable { onLoadWatchdogTimeout() }
    private var disposed = false

    /**
     * Arms the handshake watchdog (report item B2): call right after the dashboard URL is
     * (re)loaded. If the JS handshake does not land within [DASHBOARD_LOAD_TIMEOUT_MS] the
     * native "Dashboard failed to load" surface appears with a Retry action — instead of
     * today's blank-screen-forever when the dashboard JS never comes up.
     */
    fun onDashboardLoadStarted() {
        seam.cancelScheduled(loadWatchdogTimeout)
        if (disposed) {
            return
        }
        seam.scheduleDelayed(DASHBOARD_LOAD_TIMEOUT_MS, loadWatchdogTimeout)
    }

    /**
     * JS handshake landed: cancel the watchdog and clear any error/reconnecting surface. A
     * handshake that arrives after the watchdog already fired still clears the surface — a slow
     * cold start self-heals rather than being torn down.
     */
    fun onDashboardReady() {
        seam.cancelScheduled(loadWatchdogTimeout)
        seam.hideErrorSurface()
    }

    /** Cancels pending watchdog work; call from the host's onDestroy so nothing outlives it. */
    fun dispose() {
        disposed = true
        seam.cancelScheduled(loadWatchdogTimeout)
    }

    private fun onLoadWatchdogTimeout() {
        if (disposed || seam.isDashboardReady()) {
            return
        }
        seam.logError(
            "dashboard handshake watchdog fired: JS did not call dashboardReady within " +
                "${DASHBOARD_LOAD_TIMEOUT_MS}ms of loadUrl",
        )
        // Keep the WebView loading underneath: a genuinely slow cold start that completes after
        // the timeout still lands onDashboardReady, which dismisses this surface. Retry reloads
        // through the full recovery path for pages that are truly dead.
        seam.showErrorSurface(R.string.dashboard_failed_to_load, true)
    }

    /**
     * The WebView's renderer process is gone (report item B1). [crashed] distinguishes a real
     * renderer crash from the system reclaiming the renderer under memory pressure
     * ([android.webkit.RenderProcessGoneDetail.didCrash]); both are logged distinctly and take
     * the same recovery path: recreate the WebView (bounded) or show the persistent error surface.
     */
    fun onRendererGone(crashed: Boolean) {
        seam.logError(
            if (crashed) {
                "dashboard WebView renderer crashed; attempting recovery"
            } else {
                "dashboard WebView renderer was killed by the system (likely low memory); attempting recovery"
            },
        )
        recoverOrGiveUp()
    }

    /** The dashboard main frame failed to load (report item B1's onReceivedError leg). */
    fun onMainFrameLoadFailed(
        errorCode: Int,
        description: String,
    ) {
        seam.logError("dashboard main-frame load failed (code=$errorCode, $description); attempting recovery")
        recoverOrGiveUp()
    }

    /** User tapped Retry on the error surface. Always allowed — the cap gates only auto-recovery. */
    fun onRetryRequested() {
        seam.hideErrorSurface()
        seam.recreateWebView()
        onDashboardLoadStarted()
    }

    private fun recoverOrGiveUp() {
        seam.cancelScheduled(loadWatchdogTimeout)
        if (allowAutoRecovery()) {
            // Brief reconnecting state (no Retry: the reload is already happening). Cleared by
            // the fresh page's handshake via onDashboardReady().
            seam.showErrorSurface(R.string.dashboard_reconnecting, false)
            seam.recreateWebView()
            // The replacement load gets its own handshake watchdog: a recreate whose JS never
            // comes up must fall through to the failed-to-load surface, not hang silently.
            onDashboardLoadStarted()
        } else {
            seam.logError(
                "dashboard recovery cap reached ($MAX_AUTO_RECOVERIES per ${AUTO_RECOVERY_WINDOW_MS}ms); " +
                    "showing persistent error surface",
            )
            seam.destroyWebView()
            seam.showErrorSurface(R.string.dashboard_failed_repeatedly, true)
        }
    }

    /** Sliding-window recovery-loop guard: at most [MAX_AUTO_RECOVERIES] per [AUTO_RECOVERY_WINDOW_MS]. */
    private fun allowAutoRecovery(): Boolean {
        val now = clock()
        while (autoRecoveryTimesMs.isNotEmpty() && now - autoRecoveryTimesMs.first() >= AUTO_RECOVERY_WINDOW_MS) {
            autoRecoveryTimesMs.removeFirst()
        }
        if (autoRecoveryTimesMs.size >= MAX_AUTO_RECOVERIES) {
            return false
        }
        autoRecoveryTimesMs.addLast(now)
        return true
    }

    companion object {
        /**
         * How long the dashboard JS gets to complete its native handshake after loadUrl before
         * the native "Dashboard failed to load" surface appears. The emulator smoke allows up to
         * 40s for the handshake and healthy cold starts land it in well under 5s; 8s is the top
         * of the report's suggested 6-8s band so a slow-but-healthy cold start doesn't
         * false-positive (and a false positive is harmless anyway: the late handshake dismisses
         * the surface without touching the WebView).
         */
        const val DASHBOARD_LOAD_TIMEOUT_MS: Long = 8_000L

        /** Automatic recreate budget inside one [AUTO_RECOVERY_WINDOW_MS] window. */
        const val MAX_AUTO_RECOVERIES: Int = 2

        /** Sliding window for [MAX_AUTO_RECOVERIES]; beyond it the persistent error surface shows. */
        const val AUTO_RECOVERY_WINDOW_MS: Long = 60_000L
    }
}
