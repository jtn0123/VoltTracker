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
 */
object ChargeSessionMaterializer {
    private object Tunables {
        /** Brief gap inside a session — counted as an interruption but not a split. */
        const val MAX_GAP_MS = 5L * 60_000L

        /** Gap above this splits the window into two separate charge sessions. */
        const val SPLIT_GAP_MS = 30L * 60_000L

        /** Below this duration the window is rejected (likely noise). */
        const val MIN_DURATION_MS = 60_000L

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
        if (telemetry == null || telemetry.isEmpty()) {
            // Without telemetry we have no continuous window to anchor a charge session on.
            return result
        }

        // Walk the samples once to find out whether the high-confidence pack-current signal ever
        // fires in this session; if it does we materialize OBSERVED, otherwise the fallback voltage
        // heuristic produces WEAK rows.
        var usedPackCurrent = false
        var currentRun = ArrayList<TelemetrySample>()
        var interruptions = 0

        for (sample in telemetry) {
            val plugged = isPluggedSample(sample)
            if (plugged != PluggedReason.NOT_PLUGGED) {
                if (plugged == PluggedReason.PACK_CURRENT) {
                    usedPackCurrent = true
                }
                if (currentRun.isNotEmpty()) {
                    val gap = sample.capturedAtMs - currentRun[currentRun.size - 1].capturedAtMs
                    if (gap > Tunables.SPLIT_GAP_MS) {
                        val finalized = finalizeRun(currentRun, interruptions, usedPackCurrent)
                        if (finalized != null) {
                            result.add(finalized)
                        }
                        currentRun = ArrayList()
                        interruptions = 0
                        usedPackCurrent = plugged == PluggedReason.PACK_CURRENT
                    } else if (gap > Tunables.MAX_GAP_MS) {
                        interruptions += 1
                    }
                }
                currentRun.add(sample)
            }
            // Non-plugged samples between plugged runs are ignored — only the gap timestamp matters
            // and that is computed off the last plugged sample.
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
            packCurrent != null && packCurrent <= -Tunables.CHARGING_PACK_CURRENT_A_THRESHOLD
        val clearlyMoving = speed != null && speed > Tunables.STATIONARY_SPEED_KPH
        if (clearlyMoving) {
            return PluggedReason.NOT_PLUGGED
        }
        if (strongCharging) {
            return PluggedReason.PACK_CURRENT
        }
        if (speed == null) {
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
        if (voltage != null && voltage > Tunables.PLUGGED_VOLTAGE_THRESHOLD) {
            return PluggedReason.AUX_VOLTAGE
        }
        return PluggedReason.NOT_PLUGGED
    }

    private fun finalizeRun(
        run: List<TelemetrySample>,
        interruptions: Int,
        usedPackCurrent: Boolean,
    ): ChargeSession? {
        if (run.isEmpty()) {
            return null
        }
        val startedAtMs = run[0].capturedAtMs
        val endedAtMs = run[run.size - 1].capturedAtMs
        val durationMs = maxOf(0L, endedAtMs - startedAtMs)
        if (durationMs < Tunables.MIN_DURATION_MS) {
            return null
        }
        val voltageStart = run[0].adapterVoltage
        val voltageEnd = run[run.size - 1].adapterVoltage
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
            val kw = -(v * c) / 1000.0
            if (kw.isNaN() || kw.isInfinite()) {
                continue
            }
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
        for (s in run) {
            val v = s.packVoltage
            val c = s.packCurrentA
            if (v == null || c == null) {
                continue
            }
            var kw = -(v * c) / 1000.0
            if (kw.isNaN() || kw.isInfinite()) {
                continue
            }
            // Clip negative (discharge) samples to 0 so a brief discharge dip inside an otherwise-
            // plugged window doesn't subtract from the integrated charge total.
            if (kw < 0.0) {
                kw = 0.0
            }
            val pKw = prevKw
            val pMs = prevMs
            if (pKw != null && pMs != null) {
                val hours = (s.capturedAtMs - pMs) / 3_600_000.0
                if (hours > 0.0) {
                    energyKwh += ((pKw + kw) / 2.0) * hours
                    integrated += 1
                }
            }
            prevKw = kw
            prevMs = s.capturedAtMs
        }
        return if (integrated == 0) null else energyKwh
    }
}
