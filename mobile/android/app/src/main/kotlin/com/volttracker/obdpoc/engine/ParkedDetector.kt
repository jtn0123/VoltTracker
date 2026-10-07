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
 * [isInPark] is stricter, for the guided test's walk-round steps: Park read in the last
 * [parkFreshMs] (the test asks for that read, [GEAR_COMMAND]), at a standstill, and nothing since (a
 * fresh speed above a standstill, a fresh gear other than Park, or [moved]) has shown the car leaving
 * it. An older Park is not kept: whatever the car did since was not seen. [motionCount] counts every
 * sign of motion, so a caller can tell whether there has been any since it last looked.
 *
 * Only touched on the polling thread.
 */
class ParkedDetector(
    private val stillForMs: Long = 60_000L,
    private val gearFreshMs: Long = 30_000L,
    private val parkFreshMs: Long = 15_000L,
) {
    private var stillSinceMs = NOT_STILL
    private var gear = ""
    private var gearAtMs = 0L
    private var inPark = false
    private var motions = 0L

    fun reset() {
        stillSinceMs = NOT_STILL
        gear = ""
        gearAtMs = 0L
        inPark = false
        // What the car did meanwhile wasn't seen: a caller comparing [motionCount] must not assume nothing.
        motions += 1
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
        val moving = !speedKph.isNaN() && !still || freshGear && gearLetter != VoltGear.PARK
        if (moving) motions += 1
        inPark =
            when {
                moving -> false
                freshGear && still -> true
                else -> inPark
            }
    }

    /**
     * [observe] for a live sample: its decoded gear (`prndlState`), aged by when the gear PID last
     * answered ([gearAnsweredAgoMs], null when it never has), not when it was last asked: a read that
     * got no answer leaves the old letter in the sample.
     */
    fun observe(
        sample: JSONObject,
        speedKph: Double,
        gearAnsweredAgoMs: Long?,
        now: Long,
    ) = observe(speedKph, sample.optString("prndlState", ""), gearAnsweredAgoMs ?: Long.MAX_VALUE, now)

    fun isParked(now: Long): Boolean {
        if (stillSinceMs == NOT_STILL) return false
        if (gear.isNotEmpty() && now - gearAtMs <= gearFreshMs) return gear == VoltGear.PARK
        return now - stillSinceMs >= stillForMs
    }

    /** Park was read at a standstill in the last [parkFreshMs], and nothing has shown the car moving since. */
    fun isInPark(now: Long): Boolean = inPark && gear == VoltGear.PARK && now - gearAtMs <= parkFreshMs

    /** How many times anything has shown the car moving or out of Park. */
    fun motionCount(): Long = motions

    /** Something other than HS polling (the body bus's wheel speeds) saw the car move. */
    fun moved() {
        motions += 1
        inPark = false
        stillSinceMs = NOT_STILL
    }

    companion object {
        /** The gear (PRNDL) PID: what the guided test asks to be read before a parked step. */
        const val GEAR_COMMAND = "222889"

        private const val NOT_STILL = Long.MIN_VALUE

        /** A standstill: speed reads whole km/h, so this is a 0 reading, never a crawl. */
        private const val STOPPED_KPH = 0.5
    }
}
