package com.volttracker.obdpoc

import org.json.JSONObject

/**
 * Decides whether a car command may be transmitted right now. Pure logic, fed every live sample the
 * polling engine broadcasts; the engine asks [evaluate] immediately before transmitting, so the
 * answer is based on the engine's own freshest data, never on what the dashboard last displayed.
 *
 * "Parked" is deliberately strict and fails closed — any missing, stale or ambiguous input refuses:
 * - the latest speed reading is fresh ([Policy.maxSpeedAgeMs]) and exactly 0 km/h;
 * - the classifier does not say the car is driving;
 * - the gear DID (222889) read a Park code within [Policy.maxGearAgeMs];
 * - no non-zero speed has been seen since that Park reading (so a car that pulled away and stopped
 *   at a light cannot ride an older Park reading).
 *
 * Gear: the live sample's `prndlState` is the [VoltGear]-decoded letter. Only [VoltGear.PARK]
 * (raw code 8, confirmed from field logs) passes; every other letter, including `?` for an
 * undecoded code, refuses.
 */
class CarControlGate(
    private val policy: Policy = Policy(),
) {
    class Policy(
        /** A live sample must have arrived this recently. */
        val maxSampleAgeMs: Long = 5_000L,
        val maxSpeedAgeMs: Long = 5_000L,
        /** The gear DID is polled every ~24 cycles, so its reading is allowed to be older. */
        val maxGearAgeMs: Long = 120_000L,
        /** Minimum spacing between command starts (no bursts, no auto-repeat). */
        val minIntervalMs: Long = 3_000L,
    )

    /** What the adapter can do, as established by the SW-CAN listener this session. */
    enum class Adapter {
        /** Identity not probed yet. */
        UNKNOWN,

        /** Plain ELM327 (or anything that is not an OBDLink/STN): cannot reach SW-CAN. */
        NOT_STN,

        /** OBDLink/STN, but no SW-CAN listen window has heard the car yet this session. */
        STN_UNVERIFIED,

        /** OBDLink/STN that has already heard SW-CAN broadcasts from this car. */
        READY,
    }

    /** Why a command is refused. [wire] goes to the dashboard and the session log. */
    enum class Block(
        val wire: String,
        val message: String,
    ) {
        DISABLED("disabled", "Car controls are turned off in Settings."),
        NO_LIVE_DATA("no_live_data", "No live data from the car. Connect and wait for readings."),
        ADAPTER_UNKNOWN("adapter_unknown", "Still identifying the adapter."),
        ADAPTER_NOT_STN(
            "adapter_not_stn",
            "Needs an OBDLink (STN) adapter. A plain ELM327 cannot reach the single-wire CAN bus " +
                "(OBD pin 1) that carries body commands.",
        ),
        SWCAN_UNVERIFIED(
            "swcan_unverified",
            "Waiting for the first single-wire CAN check to hear the car (about 20 s after connecting).",
        ),
        SPEED_UNKNOWN("speed_unknown", "Vehicle speed is unknown or stale."),
        MOVING("moving", "The car is moving."),
        DRIVING("driving", "A drive is in progress."),
        GEAR_UNKNOWN("gear_unknown", "Gear is unknown or stale. Waiting for the next gear reading."),
        NOT_IN_PARK("not_in_park", "The car is not in Park."),
        MOVED_SINCE_PARK("moved_since_park", "The car moved since Park was last read. Waiting for a new gear reading."),
        RATE_LIMITED("rate_limited", "Wait a few seconds between commands."),
        BUSY("busy", "Another command is still running."),
    }

    private var sampleAtMs = NEVER
    private var speedKph: Double? = null
    private var speedAtMs = NEVER
    private var lastMovingAtMs = NEVER
    private var gear: String? = null
    private var gearAtMs = NEVER
    private var vehicleState = ""

    fun reset() {
        sampleAtMs = NEVER
        speedKph = null
        speedAtMs = NEVER
        lastMovingAtMs = NEVER
        gear = null
        gearAtMs = NEVER
        vehicleState = ""
    }

    /** Records what [sample] (broadcast at [now]) says about motion, gear and driving state. */
    fun observe(
        sample: JSONObject,
        now: Long,
    ) {
        sampleAtMs = now
        vehicleState = sample.optString("vehicleState", vehicleState)
        val speed = sample.optDouble("speedKph", Double.NaN)
        if (sample.has("speedRejectedKph")) {
            // An implausible reading was filtered out: speed is unknown, and it may have been motion.
            speedKph = null
            lastMovingAtMs = now
        } else if (speed.isFinite()) {
            val at = now - maxOf(0L, sample.optLong("speedKphStaleMs", 0L))
            speedKph = speed
            speedAtMs = at
            if (speed > 0.0) lastMovingAtMs = maxOf(lastMovingAtMs, at)
        }
        val gearValue = sample.optString("prndlState", "")
        if (gearValue.isNotEmpty()) {
            gear = gearValue
            gearAtMs = now - maxOf(0L, sample.optLong("prndlStateStaleMs", 0L))
        }
    }

    /** Null when a command may be sent now; otherwise the first reason it may not. */
    fun evaluate(
        now: Long,
        adapter: Adapter,
        lastCommandAtMs: Long,
    ): Block? {
        adapterBlock(adapter)?.let { return it }
        return motionBlock(now) ?: if (now - lastCommandAtMs < policy.minIntervalMs) Block.RATE_LIMITED else null
    }

    private fun adapterBlock(adapter: Adapter): Block? =
        when (adapter) {
            Adapter.UNKNOWN -> Block.ADAPTER_UNKNOWN
            Adapter.NOT_STN -> Block.ADAPTER_NOT_STN
            Adapter.STN_UNVERIFIED -> Block.SWCAN_UNVERIFIED
            Adapter.READY -> null
        }

    private fun motionBlock(now: Long): Block? {
        val speed = speedKph
        val currentGear = gear
        return when {
            sampleAtMs == NEVER || now - sampleAtMs > policy.maxSampleAgeMs -> Block.NO_LIVE_DATA
            speed == null || now - speedAtMs > policy.maxSpeedAgeMs -> Block.SPEED_UNKNOWN
            speed > 0.0 -> Block.MOVING
            vehicleState in DRIVING_STATES -> Block.DRIVING
            currentGear == null || now - gearAtMs > policy.maxGearAgeMs -> Block.GEAR_UNKNOWN
            currentGear.trim().uppercase() !in PARK_CODES -> Block.NOT_IN_PARK
            lastMovingAtMs != NEVER && lastMovingAtMs >= gearAtMs -> Block.MOVED_SINCE_PARK
            else -> null
        }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2

        /** Classifier states that mean a trip is actively driving. */
        @JvmField val DRIVING_STATES = setOf("driving_ev", "driving_gas")

        /** Gear letters accepted as Park (see [VoltGear]). */
        @JvmField val PARK_CODES = setOf(VoltGear.PARK)
    }
}
