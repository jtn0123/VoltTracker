package com.volttracker.obdpoc

import com.volttracker.obdpoc.classify.VehicleState
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Low-power extended reconnect tier for mid-drive link drops (audit item B3).
 *
 * When the fast reconnect budget ([ObdProbes.MAX_RECONNECT_ATTEMPTS]) exhausts while the car was
 * last seen actively driving, the session must NOT give up permanently — a tunnel/parking-garage
 * Bluetooth dropout or a competing app briefly holding the adapter can outlast the fast tier, and
 * stopping there means silently losing the rest of the drive. Instead [ObdPollingEngine] arms this
 * tier: one reconnect attempt roughly every [RETRY_INTERVAL_MS] for up to [RETRY_WINDOW_MS], then
 * a clean stop through the existing exhaustion reporting.
 *
 * The inter-attempt wait is a timed [Semaphore] acquire rather than a plain sleep so that
 * [signal] — wired to the OS ACL-connected broadcast for the active adapter and to the user's
 * cancel-retry action — ends the wait immediately. Battery: no wake lock is taken here; the
 * session's existing partial wake lock (see `ObdService.acquireSessionWakeLock`) already covers
 * the session, and the tier adds only one short connect attempt per interval on top of it.
 *
 * Threading: [begin], [reset], [windowExpired], and [awaitNextAttempt] run on the engine's single
 * worker thread; [signal] may be called from any thread (the [Semaphore] provides the
 * happens-before edge for the wake-up).
 */
class ExtendedReconnectTier(
    private val intervalMs: Long = RETRY_INTERVAL_MS,
    private val windowMs: Long = RETRY_WINDOW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val wakeUps = Semaphore(0)

    /** True while the tier is armed (between [begin] and [reset]). Engine-thread only. */
    var active: Boolean = false
        private set

    /** Attempts awaited since [begin] — diagnostic only. Engine-thread only. */
    var attemptCount: Int = 0
        private set

    private var deadlineMs = 0L

    /** Arms the tier: stamps the give-up deadline and clears any stale wake-up signals. */
    fun begin() {
        active = true
        attemptCount = 0
        deadlineMs = nowMs() + windowMs
        wakeUps.drainPermits()
    }

    /** Disarms the tier (successful reconnect or session end). */
    fun reset() {
        active = false
        attemptCount = 0
        deadlineMs = 0L
    }

    /** True when the extended window has elapsed and the tier must give up cleanly. */
    fun windowExpired(): Boolean = nowMs() >= deadlineMs

    /**
     * Blocks until the next attempt is due: either [intervalMs] elapses or [signal] wakes the
     * wait early. Returns false when interrupted (the session is being torn down), true otherwise.
     */
    fun awaitNextAttempt(): Boolean =
        try {
            wakeUps.tryAcquire(intervalMs, TimeUnit.MILLISECONDS)
            attemptCount += 1
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    /**
     * Wakes a pending [awaitNextAttempt] immediately. Called when the OS reports the adapter's
     * ACL link came back, or when the user cancels the retry (the engine consumes the cancel flag
     * right after the wait returns).
     */
    fun signal() {
        wakeUps.release()
    }

    companion object {
        /** Cadence of extended-tier reconnect attempts: low-power, ~1 connect try per minute. */
        const val RETRY_INTERVAL_MS: Long = 60_000L

        /** Total extended window before giving up for good: ~15 minutes of waiting. */
        const val RETRY_WINDOW_MS: Long = 15L * 60_000L

        /**
         * Pure decision (extracted for testability): enter the extended tier only when the link
         * had connected at least once this session AND the car was last seen in an
         * actively-driving state (driving_ev / driving_gas / ready). Exhaustion while parked /
         * plugged / charging / unknown keeps the pre-B3 prompt stop — an asleep-car or
         * parked-idle session must not burn battery retrying for 15 minutes in a parking lot
         * (see [ObdPollingEngine.isVehicleOffDisconnect] for the benign-end classification).
         */
        @JvmStatic
        fun shouldEnter(
            everConnected: Boolean,
            lastVehicleState: String,
        ): Boolean =
            everConnected &&
                when (lastVehicleState) {
                    VehicleState.DRIVING_EV.asPayloadKey(),
                    VehicleState.DRIVING_GAS.asPayloadKey(),
                    VehicleState.READY.asPayloadKey(),
                    -> true
                    else -> false
                }
    }
}
