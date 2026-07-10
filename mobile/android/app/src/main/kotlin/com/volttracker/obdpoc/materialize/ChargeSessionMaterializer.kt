package com.volttracker.obdpoc.materialize

/**
 * Conservative charge-session materializer.
 *
 * **Primary signal — HV pack current.** When [TelemetrySample.packCurrentA] is available (signed;
 * discharge positive per the Volt convention) we treat a sample as plugged when current is flowing
 * *into* the pack — `packCurrentA <= -`[Tunables.CHARGING_PACK_CURRENT_A_THRESHOLD]. Combined with
 * near-zero speed, that's a high-confidence "the car is on a charger" signal that
 * [Confidence.OBSERVED] earns.
 *
 * **Fallback — aux 12V.** When pack current isn't in the sample we fall back to the legacy
 * heuristic: [TelemetrySample.adapterVoltage] above [Tunables.PLUGGED_VOLTAGE_THRESHOLD] with
 * near-zero speed. This is documented [Confidence.WEAK] because the alternator under load can raise
 * 12V transiently without an EVSE involved.
 *
 * **Gap behaviour.** A single gap inside an otherwise-plugged window *merges* into one session with
 * [ChargeSession.interruptionCount] incremented; the intent is that a brief cable jiggle or polling
 * stall during a real charge does not split the row. Only gaps that exceed [Tunables.SPLIT_GAP_MS]
 * actually split the window into two sessions.
 *
 * **Transient-break debounce.** A momentary movement/discharge sample (a one-off GPS speed glitch, a
 * DC-DC converter blip, a brief power spike) no longer instantly splits an active charge — that was
 * the main cause of one physical charge being logged as several sessions. A break only splits the
 * window once it is *sustained* for [Tunables.BREAK_DEBOUNCE_MS]; a shorter break that resumes
 * charging is folded back in as an [ChargeSession.interruptionCount]. A genuinely sustained drive
 * still splits, so a drive is never stitched into a charge. A finalized window also needs at least
 * [Tunables.MIN_SAMPLES] plugged samples, which rejects sparse two-sample noise.
 */
object ChargeSessionMaterializer {
    /**
     * Charge-session thresholds are pinned to the policy in ADR 0004.
     *
     * The key calibration is the preference for Volt pack current over aux-voltage inference:
     * negative pack current is high-confidence charging evidence, while `ATRV` above 13.5 V is
     * only a fallback because post-drive 12V behavior can look charger-like. The split/merge
     * windows protect short cable wiggles or polling stalls without joining genuinely separate
     * charge sessions. See `docs/adr/0004-charge-detection-heuristics.md` and the materializer
     * tests before changing these values.
     */
    private object Tunables {
        /** Brief gap inside a session — counted as an interruption but not a split. */
        const val MAX_GAP_MS = 5L * 60_000L

        /** Gap above this splits the window into two separate charge sessions. */
        const val SPLIT_GAP_MS = 30L * 60_000L

        /** Below this duration the window is rejected (likely noise). */
        const val MIN_DURATION_MS = 60_000L

        /**
         * A break (movement/discharge) must persist at least this long before it splits an active
         * charge. Shorter breaks that resume charging are treated as interruptions, not splits —
         * this is what stops a single glitchy sample from over-counting one charge as several.
         */
        const val BREAK_DEBOUNCE_MS = 60_000L

        /**
         * A finalized window needs at least this many plugged samples. Real charges stream hundreds
         * of samples; this floor rejects the sparse two-sample windows that a transient break can
         * otherwise leave behind.
         */
        const val MIN_SAMPLES = 3

        /**
         * Pack current more negative than this (i.e. ≥ this many amps flowing INTO the pack) is
         * treated as charging. The 1.0 A floor filters out parked-with-12V-conversion noise; real
         * EVSE charging is tens to hundreds of amps.
         */
        const val CHARGING_PACK_CURRENT_A_THRESHOLD = 1.0

        /** Adapter voltage above this with low speed indicates the car is on a charger. */
        const val PLUGGED_VOLTAGE_THRESHOLD = 13.5

        /** Speed above this means the car is moving — not plugged in. */
        const val STATIONARY_SPEED_KPH = 1.0
    }

    @JvmStatic
    fun materialize(
        input: MaterializerInput?,
        data: MaterializerData?,
    ): List<ChargeSession> {
        val result = ArrayList<ChargeSession>()
        if (input == null || data == null) {
            return result
        }
        val telemetry = data.readTelemetrySamples(input.sessionId)
        if (telemetry.isEmpty()) {
            // Without telemetry we have no continuous window to anchor a charge session on.
            return result
        }

        // Walk the samples once, tracking per run whether the high-confidence pack-current signal
        // ever fires inside it; if it does that run materializes OBSERVED, otherwise the fallback
        // voltage heuristic produces a WEAK row.
        var usedPackCurrent = false
        var currentRun = ArrayList<TelemetrySample>()
        var interruptions = 0
        // Timestamp of the first breaking sample of an as-yet-unresolved break, or null when the
        // run is not currently interrupted. Drives the transient-break debounce below.
        var pendingBreakStartMs: Long? = null

        for (sample in telemetry) {
            val plugged = isPluggedSample(sample)
            if (plugged != PluggedReason.NOT_PLUGGED) {
                if (currentRun.isNotEmpty()) {
                    val breakStartMs = pendingBreakStartMs
                    val hadTransientBreak = breakStartMs != null
                    // With sparse telemetry there may be only one breaking row. Re-check the break
                    // duration when charging resumes; otherwise a multi-minute drive is treated as
                    // a one-sample blip and two physical charges are stitched together.
                    val sustainedBreak =
                        breakStartMs != null && sample.capturedAtMs - breakStartMs > Tunables.BREAK_DEBOUNCE_MS
                    pendingBreakStartMs = null
                    val gap = sample.capturedAtMs - currentRun[currentRun.size - 1].capturedAtMs
                    if (gap > Tunables.SPLIT_GAP_MS || sustainedBreak) {
                        // This boundary is a SPLIT, not an interruption — so don't count the pending
                        // transient break against the run being closed. Finalize with the flag state
                        // as of ITS OWN samples; the incoming sample's evidence belongs to the run
                        // that starts with it.
                        val finalized = finalizeRun(currentRun, interruptions, usedPackCurrent)
                        if (finalized != null) {
                            result.add(finalized)
                        }
                        currentRun = ArrayList()
                        interruptions = 0
                        usedPackCurrent = false
                    } else if (hadTransientBreak || gap > Tunables.MAX_GAP_MS) {
                        // The run continues: a transient break (movement/discharge that resumed
                        // before BREAK_DEBOUNCE_MS) or a moderate gap counts as exactly one
                        // interruption, never both for the same resume.
                        interruptions += 1
                    }
                }
                if (plugged == PluggedReason.PACK_CURRENT) {
                    usedPackCurrent = true
                }
                currentRun.add(sample)
            } else if (currentRun.isNotEmpty() && breaksChargeRun(sample)) {
                val breakStart = pendingBreakStartMs ?: sample.capturedAtMs
                pendingBreakStartMs = breakStart
                if (sample.capturedAtMs - breakStart >= Tunables.BREAK_DEBOUNCE_MS) {
                    // Sustained movement/discharge: a real drive (or an unplug-and-leave), so split.
                    val finalized = finalizeRun(currentRun, interruptions, usedPackCurrent)
                    if (finalized != null) {
                        result.add(finalized)
                    }
                    currentRun = ArrayList()
                    interruptions = 0
                    usedPackCurrent = false
                    pendingBreakStartMs = null
                }
            }
            // Non-plugged samples between plugged runs are ignored — only the gap timestamp matters
            // and that is computed off the last plugged sample. Sustained movement/discharge is the
            // exception: it breaks the candidate so a drive cannot be stitched into a charge.
        }
        if (currentRun.isNotEmpty()) {
            val finalized = finalizeRun(currentRun, interruptions, usedPackCurrent)
            if (finalized != null) {
                result.add(finalized)
            }
        }
        return result
    }

    /** Why a sample qualified as plugged, or [NOT_PLUGGED] if it didn't. */
    private enum class PluggedReason {
        NOT_PLUGGED,
        PACK_CURRENT,
        AUX_VOLTAGE,
    }

    /**
     * Combines the primary pack-current signal with the legacy aux-voltage fallback. Discharge is
     * positive in this codebase, so real charging current is *negative*. A strong negative current
     * overrides a missing/near-zero speed reading (the Volt's 0xFF speed sentinel during a charge
     * makes `speed_kph` NULL for most of the session). For weaker signals we still require a valid
     * stationary speed reading because the aux-voltage heuristic is too noisy to trust on its own.
     */
    private fun isPluggedSample(sample: TelemetrySample?): PluggedReason {
        if (sample == null) {
            return PluggedReason.NOT_PLUGGED
        }
        val packCurrent = sample.packCurrentA
        val speed = sample.speedKph
        val strongCharging =
            packCurrent != null &&
                packCurrent.isFinite() &&
                packCurrent <= -Tunables.CHARGING_PACK_CURRENT_A_THRESHOLD
        val clearlyMoving = speed != null && speed.isFinite() && speed > Tunables.STATIONARY_SPEED_KPH
        if (clearlyMoving) {
            return PluggedReason.NOT_PLUGGED
        }
        if (strongCharging) {
            return PluggedReason.PACK_CURRENT
        }
        if (speed == null || !speed.isFinite()) {
            // Without a strong pack-current signal AND without a speed reading, we cannot
            // distinguish stationary-plugged from moving-with-regen — be conservative.
            return PluggedReason.NOT_PLUGGED
        }
        if (packCurrent != null) {
            // Definitive pack-current reading that does NOT indicate charging — overrides the
            // noisier aux-voltage heuristic.
            return PluggedReason.NOT_PLUGGED
        }
        val voltage = sample.adapterVoltage
        if (voltage != null && voltage.isFinite() && voltage > Tunables.PLUGGED_VOLTAGE_THRESHOLD) {
            return PluggedReason.AUX_VOLTAGE
        }
        return PluggedReason.NOT_PLUGGED
    }

    private fun breaksChargeRun(sample: TelemetrySample?): Boolean {
        if (sample == null) {
            return false
        }
        val speed = sample.speedKph
        if (speed != null && speed.isFinite() && speed > Tunables.STATIONARY_SPEED_KPH) {
            return true
        }
        val packCurrent = sample.packCurrentA
        if (packCurrent != null && packCurrent.isFinite() && packCurrent > Tunables.CHARGING_PACK_CURRENT_A_THRESHOLD) {
            return true
        }
        val power = sample.powerKw
        return power != null && power.isFinite() && power > 1.0
    }

    private fun finalizeRun(
        run: List<TelemetrySample>,
        interruptions: Int,
        usedPackCurrent: Boolean,
    ): ChargeSession? {
        if (run.isEmpty()) {
            return null
        }
        if (run.size < Tunables.MIN_SAMPLES) {
            // Too few plugged samples to trust — a sparse two-sample window is noise, not a charge.
            return null
        }
        val startedAtMs = run[0].capturedAtMs
        val endedAtMs = run[run.size - 1].capturedAtMs
        val durationMs = maxOf(0L, endedAtMs - startedAtMs)
        if (durationMs < Tunables.MIN_DURATION_MS) {
            return null
        }
        val voltageStart = run[0].adapterVoltage?.takeIf { it.isFinite() }
        val voltageEnd = run[run.size - 1].adapterVoltage?.takeIf { it.isFinite() }
        val confidence = if (usedPackCurrent) Confidence.OBSERVED else Confidence.WEAK
        return ChargeSession(
            startedAtMs,
            endedAtMs,
            durationMs,
            voltageStart,
            voltageEnd,
            interruptions,
            "unknown",
            confidence,
            firstFiniteSoc(run),
            lastFiniteSoc(run),
            peakChargePowerKw(run),
            integrateChargeEnergyKwh(run),
        )
    }

    private fun firstFiniteSoc(run: List<TelemetrySample>): Double? {
        for (s in run) {
            val soc = s.socPct
            if (soc != null && !soc.isNaN() && !soc.isInfinite()) {
                return soc
            }
        }
        return null
    }

    private fun lastFiniteSoc(run: List<TelemetrySample>): Double? {
        for (i in run.indices.reversed()) {
            val soc = run[i].socPct
            if (soc != null && !soc.isNaN() && !soc.isInfinite()) {
                return soc
            }
        }
        return null
    }

    /**
     * Peak power INTO the pack during the window, in kW. Pack convention is discharge-positive, so
     * charging power = `-(packVoltage * packCurrentA / 1000)`. Returns `null` when no sample in the
     * run carried both values.
     */
    private fun peakChargePowerKw(run: List<TelemetrySample>): Double? {
        var peak: Double? = null
        for (s in run) {
            val v = s.packVoltage
            val c = s.packCurrentA
            if (v == null || c == null) {
                continue
            }
            val kw = PackEnergyMath.chargePowerKw(v, c) ?: continue
            if (kw <= 0.0) {
                continue
            }
            if (peak == null || kw > peak) {
                peak = kw
            }
        }
        return peak
    }

    /**
     * Trapezoidal integration of charging power across the run. Same sign convention as
     * [peakChargePowerKw]. Returns `null` when fewer than two samples in the window carry both pack
     * values (a single point can't form an area).
     */
    private fun integrateChargeEnergyKwh(run: List<TelemetrySample>): Double? {
        var prevKw: Double? = null
        var prevMs: Long? = null
        var energyKwh = 0.0
        var integrated = 0
        var lastV: Double? = null
        for (s in run) {
            val c = s.packCurrentA ?: continue
            // Pack voltage is polled on a slower lane than current, so carry the last-known voltage
            // across a current-only sample rather than dropping it from the integral (B2).
            s.packVoltage?.let { lastV = it }
            val v = lastV ?: continue
            var kw = PackEnergyMath.chargePowerKw(v, c) ?: continue
            // Clip negative (discharge) samples to 0 so a brief discharge dip inside an otherwise-
            // plugged window doesn't subtract from the integrated charge total.
            if (kw < 0.0) {
                kw = 0.0
            }
            val pKw = prevKw
            val pMs = prevMs
            if (pKw != null && pMs != null) {
                val segmentKwh = PackEnergyMath.trapezoidKwh(pKw, kw, pMs, s.capturedAtMs)
                if (segmentKwh != null) {
                    energyKwh += segmentKwh
                    integrated += 1
                }
            }
            prevKw = kw
            prevMs = s.capturedAtMs
        }
        return if (integrated == 0) null else energyKwh
    }
}
