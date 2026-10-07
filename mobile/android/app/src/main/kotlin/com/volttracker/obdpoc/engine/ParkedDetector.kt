package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.VoltGear
import org.json.JSONObject

/**
 * Whether the car is parked, for the SW-CAN listen cadence and the guided car test. Standing still
 * is not enough: at a red light the car is in Drive, and a parked-length window there costs live
 * data mid-drive. With a gear reading from the last [gearFreshMs], only Park counts. Without one,
 * the car must have stood still (a fresh speed of [STOPPED_KPH] or less, so not crawling in
 * traffic) for [stillForMs], longer than most lights.
 *
 * [isInPark] is stricter, for the guided test's walk-round steps: Park read fresh at a standstill,
 * and nothing since (a fresh speed above a standstill, a fresh gear other than Park, or [moved])
 * has shown the car leaving it. A missing speed (a quiet HS bus while the test holds the adapter,
 * or the car switched off) is not motion and keeps it.
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
    private var inPark = false

    fun reset() {
        stillSinceMs = NOT_STILL
        gear = ""
        gearAtMs = 0L
        inPark = false
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
        val still = !speedKph.isNaN() && speedKph <= STOPPED_KPH
        stillSinceMs =
            when {
                !still -> NOT_STILL
                stillSinceMs == NOT_STILL -> now
                else -> stillSinceMs
            }
        val freshGear = gearLetter.isNotEmpty() && gearAgeMs <= gearFreshMs
        if (gearLetter.isNotEmpty()) {
            gear = gearLetter
            gearAtMs = now - maxOf(0L, gearAgeMs)
        }
        inPark =
            when {
                !speedKph.isNaN() && !still -> false
                freshGear && gearLetter != VoltGear.PARK -> false
                freshGear && still -> true
                else -> inPark
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

    /** Park was read at a standstill and nothing has shown the car moving since. */
    fun isInPark(): Boolean = inPark

    /** Something other than HS polling (the body bus's wheel speeds) saw the car move. */
    fun moved() {
        inPark = false
        stillSinceMs = NOT_STILL
    }

    private companion object {
        const val NOT_STILL = Long.MIN_VALUE

        /** A standstill: speed reads whole km/h, so this is a 0 reading, never a crawl. */
        const val STOPPED_KPH = 0.5
    }
}
