package com.volttracker.obdpoc.materialize

import com.volttracker.obdpoc.GearConfidence
import com.volttracker.obdpoc.VoltGear

/**
 * Gear-aware trip splitting, shared by [TripMaterializer] and `DriveWindowDetector` so both carve
 * a session into the same trips.
 *
 * The legacy splitters only cut a trip where the car looks inactive (no speed, RPM, 12 V charge,
 * pack power or current). Parking with the car on — climate drawing over 1 kW — therefore never
 * split: one real 156-min "trip" held a 33-min stop. With the PRNDL gear decoded ([VoltGear]):
 *
 * - Park held for [PARK_SPLIT_MS] (10 min) splits the trip, however much power the car draws.
 * - Park held for [PARK_DOOR_SPLIT_MS] (3 min) splits sooner if a door/hatch opened meanwhile.
 *   Door status comes from the listen-only SW-CAN reads (unconfirmed on the car, heard only
 *   intermittently), so it is an optional accelerator, never required.
 * - A known non-Park gear (R, N, D, L) never splits, however long the car sits still (traffic,
 *   drive-thru): legacy split spans lying wholly inside a stretch of fresh gear readings are
 *   dropped, because while the gear is known it — not the activity heuristics — decides.
 * - Where the gear is unknown or stale (no fresh reading for [MAX_GEAR_GAP_MS], an undecoded
 *   code, or an old session with no gear column) the legacy behavior applies unchanged.
 * - Park stops of at least [PARK_STOP_MIN_MS] (2 min) that do not split, with driving on both
 *   sides, are reported as in-trip [ParkStop]s ("Stopped N min").
 *
 * Only sessions stamped with [GEAR_AWARE] or later get these rules (see `obd_sessions`
 * `trip_rules_version`, written when a session starts): trips recorded before this version keep
 * the exact windows — and so the route keys their labels, favorites and hides are keyed on —
 * that they had when they were saved.
 */
object TripSplitRules {
    /** Sessions recorded before gear-aware splitting existed. */
    const val LEGACY: Int = 0

    /** First version with Park-based splitting and in-trip stops. */
    const val GEAR_AWARE: Int = 1

    /** Version stamped on newly started sessions. */
    const val CURRENT: Int = GEAR_AWARE

    const val PARK_SPLIT_MS: Long = 10L * 60_000L
    const val PARK_DOOR_SPLIT_MS: Long = 3L * 60_000L
    const val PARK_STOP_MIN_MS: Long = 2L * 60_000L

    /**
     * The PRNDL PID is polled about every 37 s and a stored reading is dropped once it is 2 min
     * old, so two gear readings further apart than this mean the gear was unknown in between.
     */
    const val MAX_GEAR_GAP_MS: Long = 150_000L

    @JvmStatic
    fun appliesTo(rulesVersion: Int): Boolean = rulesVersion >= GEAR_AWARE

    /** One telemetry row as the rules see it: time, fresh raw PRNDL code, any-door-open flag. */
    class GearSample(
        @JvmField val atMs: Long,
        @JvmField val prndlRaw: Int?,
        @JvmField val doorOpen: Boolean?,
    )

    class Span(
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
    )

    /** A Park stop inside a trip, too short to split it. */
    class ParkStop(
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val doorOpened: Boolean,
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    class Analysis(
        /** Park stretches that end a trip; treated like the legacy inactive spans. */
        @JvmField val splitSpans: List<Span>,
        /** Park stops inside a trip. */
        @JvmField val stops: List<ParkStop>,
        private val knownGearRuns: List<Span>,
    ) {
        /**
         * True when a legacy split span lies wholly inside a stretch of fresh, decoded gear
         * readings — the gear rules govern there, so the legacy split must be dropped.
         */
        fun governs(
            startMs: Long,
            endMs: Long,
        ): Boolean = knownGearRuns.any { startMs >= it.startMs && endMs <= it.endMs }

        /** Stops lying wholly inside [startMs]..[endMs]. */
        fun stopsWithin(
            startMs: Long,
            endMs: Long,
        ): List<ParkStop> = stops.filter { it.startMs >= startMs && it.endMs <= endMs }

        companion object {
            @JvmField
            val NONE: Analysis = Analysis(emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Analyzes [samples] (ordered oldest-first) under [rulesVersion]. Legacy sessions get
     * [Analysis.NONE], which leaves the legacy splitters untouched.
     */
    @JvmStatic
    fun analyze(
        rulesVersion: Int,
        samples: List<GearSample>,
    ): Analysis {
        if (!appliesTo(rulesVersion) || samples.isEmpty()) return Analysis.NONE
        val scan = Scan()
        for (sample in samples) {
            scan.accept(sample)
        }
        scan.finish()
        return classify(scan)
    }

    private fun classify(scan: Scan): Analysis {
        val splits = ArrayList<Span>()
        val stops = ArrayList<ParkStop>()
        for (run in scan.parkRuns) {
            val durationMs = run.endMs - run.startMs
            val doorOpened = scan.doorOpenAtMs.any { it in run.startMs..run.endMs }
            when {
                durationMs >= PARK_SPLIT_MS -> splits.add(Span(run.startMs, run.endMs))
                durationMs >= PARK_DOOR_SPLIT_MS && doorOpened -> splits.add(Span(run.startMs, run.endMs))
                durationMs >= PARK_STOP_MIN_MS &&
                    run.drivenBefore &&
                    scan.lastNonParkAtMs > run.endMs -> stops.add(ParkStop(run.startMs, run.endMs, doorOpened))
            }
        }
        if (splits.isEmpty() && stops.isEmpty() && scan.knownRuns.isEmpty()) return Analysis.NONE
        return Analysis(splits, stops, scan.knownRuns)
    }

    private class ParkRun(
        val startMs: Long,
        var endMs: Long,
        val drivenBefore: Boolean,
    )

    /** Single pass over the samples building Park runs and runs of known gear. */
    private class Scan {
        val parkRuns = ArrayList<ParkRun>()
        val knownRuns = ArrayList<Span>()
        val doorOpenAtMs = ArrayList<Long>()
        var lastNonParkAtMs: Long = Long.MIN_VALUE

        private var park: ParkRun? = null
        private var knownStartMs = -1L
        private var lastEvidenceAtMs = -1L

        fun accept(sample: GearSample) {
            if (sample.doorOpen == true) doorOpenAtMs.add(sample.atMs)
            val raw = sample.prndlRaw ?: return
            val atMs = sample.atMs
            if (lastEvidenceAtMs >= 0L && atMs - lastEvidenceAtMs > MAX_GEAR_GAP_MS) {
                closeKnown()
                park = null
            }
            val gear = VoltGear.decode(raw)
            if (gear == null || gear.confidence == GearConfidence.UNKNOWN) {
                closeKnown()
                park = null
                lastEvidenceAtMs = -1L
                return
            }
            if (knownStartMs < 0L) knownStartMs = atMs
            lastEvidenceAtMs = atMs
            if (gear.isPark) {
                val open = park
                if (open == null) {
                    park = ParkRun(atMs, atMs, lastNonParkAtMs != Long.MIN_VALUE).also(parkRuns::add)
                } else {
                    open.endMs = atMs
                }
            } else {
                park = null
                lastNonParkAtMs = atMs
            }
        }

        fun finish() {
            closeKnown()
        }

        private fun closeKnown() {
            if (knownStartMs >= 0L && lastEvidenceAtMs >= knownStartMs) {
                knownRuns.add(Span(knownStartMs, lastEvidenceAtMs))
            }
            knownStartMs = -1L
        }
    }
}
