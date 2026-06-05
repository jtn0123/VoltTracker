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
        val jumpPerSecond = abs(speedKph - previousKph) / elapsedSeconds
        if (jumpPerSecond > MAX_JUMP_KPH_PER_SEC) {
            return false
        }
        lastAcceptedKph = speedKph
        lastAcceptedAtMs = nowMs
        return true
    }

    companion object {
        /** Above this change-per-second the reading is a glitch, not real acceleration. */
        const val MAX_JUMP_KPH_PER_SEC = 45.0
    }
}
