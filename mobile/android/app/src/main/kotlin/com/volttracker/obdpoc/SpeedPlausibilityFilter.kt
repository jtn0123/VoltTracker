package com.volttracker.obdpoc

import kotlin.math.abs
import kotlin.math.max

/**
 * Rejects implausible OBD vehicle-speed readings: out-of-range values and physically impossible
 * jumps from the last accepted reading. Mirrors the GPS
 * [com.volttracker.obdpoc.location.LocationFilter] pattern. Pure and
 * stateful-but-deterministic, so it is unit-testable without a device.
 */
class SpeedPlausibilityFilter {
    private var lastAcceptedKph: Int? = null
    private var lastAcceptedAtMs = 0L

    /** Clears state so a new session starts fresh. */
    fun reset() {
        lastAcceptedKph = null
        lastAcceptedAtMs = 0L
    }

    /**
     * @param speedKph the freshly decoded speed
     * @param nowMs current time, supplied so the decision is testable
     * @return true if the reading is plausible and should be accepted
     */
    fun accept(
        speedKph: Int,
        nowMs: Long,
    ): Boolean {
        if (speedKph < 0 || speedKph >= 255) {
            return false
        }
        val previousKph = lastAcceptedKph
        if (previousKph == null || lastAcceptedAtMs <= 0L) {
            lastAcceptedKph = speedKph
            lastAcceptedAtMs = nowMs
            return true
        }
        val elapsedSeconds = max(0.5, (nowMs - lastAcceptedAtMs) / 1000.0)
        val delta = speedKph - previousKph
        // Asymmetric gate: a Volt sheds speed far faster under regen + friction braking than it can
        // accelerate, so a symmetric ceiling rejected legitimate hard braking (e.g. ~40 km/h drop in
        // under a second at the ~0.85 s poll cadence) and froze the dashboard speed at the pre-braking
        // value. Allow a higher deceleration limit while keeping acceleration tight.
        val limitPerSecond = if (delta < 0) MAX_DECEL_KPH_PER_SEC else MAX_ACCEL_KPH_PER_SEC
        val jumpPerSecond = abs(delta) / elapsedSeconds
        if (jumpPerSecond > limitPerSecond) {
            return false
        }
        lastAcceptedKph = speedKph
        lastAcceptedAtMs = nowMs
        return true
    }

    companion object {
        /** Above this rise-per-second the reading is a glitch, not real acceleration. */
        const val MAX_ACCEL_KPH_PER_SEC = 45.0

        /** Deceleration ceiling: hard braking + regen can drop speed much faster than acceleration
         *  raises it. A panic stop is roughly 1 g (~35 km/h per second); this leaves headroom for
         *  poll-cadence jitter while still rejecting sensor glitches (e.g. a 100→0 km/h step). */
        const val MAX_DECEL_KPH_PER_SEC = 70.0
    }
}
