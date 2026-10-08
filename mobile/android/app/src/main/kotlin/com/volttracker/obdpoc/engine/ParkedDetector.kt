package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.VoltGear
import org.json.JSONObject

/**
 * Whether the car is parked, for the SW-CAN listen cadence. Standing still is not enough: at a red
 * light the car is in Drive, and a parked-length window there costs live data mid-drive. With a
 * gear reading from the last [gearFreshMs], only Park counts. Without one, the car must have stood
 * still (a fresh speed of [MAX_STILL_KPH] or less) for [stillForMs], longer than most lights.
 *
 * Only touched on the polling thread.
 */
class ParkedDetector(
    private val stillForMs: Long = 60_000L,
    private val gearFreshMs: Long = 30_000L,
) {
    private var stillSinceMs = NOT_STILL
    private var gear = ""
    private var gearAtMs = 0L

    fun reset() {
        stillSinceMs = NOT_STILL
        gear = ""
        gearAtMs = 0L
    }

    /**
     * Folds in one sample: [speedKph] is its fresh road speed (NaN when stale or missing), [gearLetter]
     * its decoded gear ("" when absent) and [gearAgeMs] how old that gear reading is.
     */
    fun observe(
        speedKph: Double,
        gearLetter: String,
        gearAgeMs: Long,
        now: Long,
    ) {
        val still = !speedKph.isNaN() && speedKph <= MAX_STILL_KPH
        stillSinceMs =
            when {
                !still -> NOT_STILL
                stillSinceMs == NOT_STILL -> now
                else -> stillSinceMs
            }
        if (gearLetter.isNotEmpty()) {
            gear = gearLetter
            gearAtMs = now - maxOf(0L, gearAgeMs)
        }
    }

    /** [observe] for a live sample: its decoded gear (`prndlState`) and that reading's age. */
    fun observe(
        sample: JSONObject,
        speedKph: Double,
        now: Long,
    ) = observe(speedKph, sample.optString("prndlState", ""), sample.optLong("prndlStateStaleMs", 0L), now)

    fun isParked(now: Long): Boolean {
        if (stillSinceMs == NOT_STILL) return false
        if (gear.isNotEmpty() && now - gearAtMs <= gearFreshMs) return gear == VoltGear.PARK
        return now - stillSinceMs >= stillForMs
    }

    private companion object {
        const val NOT_STILL = Long.MIN_VALUE

        /** The engine's own moving threshold (ObdPollingEngine.MOVING_SPEED_KPH). */
        const val MAX_STILL_KPH = 5.0
    }
}
