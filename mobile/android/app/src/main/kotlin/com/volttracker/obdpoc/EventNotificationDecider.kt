package com.volttracker.obdpoc

/**
 * Pure decision core for the event-notification feature (M1). It is fed live telemetry samples and
 * diagnostic-scan results and decides which notifications should fire, with no Android dependency so
 * the firing edge cases are fully unit-testable.
 *
 * The three event kinds:
 *
 * - **Charge complete** — fires once when an active charge (pack current flowing into the pack at
 *   low speed, the same high-confidence signal [ChargeSessionMaterializer] uses) transitions back to
 *   not-charging. Carries the energy integrated across the charge window (trapezoidal, same sign
 *   convention as the materializer). Only fires when at least [MIN_CHARGE_SAMPLES] charging samples
 *   were seen, so a single glitchy sample never raises a phantom "charge complete".
 *
 * - **Low SOC / high pack temp** — fire once per *crossing* of the configured threshold, not on
 *   every sample below/above it. After firing, the alert re-arms only once the reading recovers past
 *   the threshold (with a small hysteresis band), so a value hovering at the line cannot spam.
 *
 * - **New DTC** — fires when a scan surfaces a code not present in the previous scan's set. The
 *   previous set is supplied by the caller (persisted in [EventNotificationPrefs]); the result also
 *   carries the new full set so the caller can persist it.
 *
 * The decider holds only in-memory per-connection state (charge accumulation, last reading, the
 * armed/fired flags). The DTC baseline lives in prefs and is passed in per scan.
 */
class EventNotificationDecider(
    settings: Settings,
) {
    // Mutable so the coordinator can refresh the user toggles/thresholds mid-session (a pref change
    // must take effect on the next sample) WITHOUT discarding the per-connection accumulation /
    // arming state below — rebuilding the decider would reset those and break charge-complete
    // detection across a toggle change.
    private var settings: Settings = settings

    /** Replaces the live toggles/thresholds; in-memory accumulation/arming state is untouched. */
    fun updateSettings(next: Settings) {
        settings = next
    }

    /** Immutable snapshot of the user toggles + thresholds, taken once per sample/scan. */
    data class Settings(
        val chargeCompleteEnabled: Boolean,
        val newDtcEnabled: Boolean,
        val lowSocEnabled: Boolean,
        val lowSocThresholdPct: Double,
        val highPackTempEnabled: Boolean,
        val highPackTempThresholdC: Double,
    )

    sealed interface Event {
        /** A charge session just ended; [energyKwh] is the integrated charge energy (>= 0). */
        data class ChargeComplete(
            val energyKwh: Double,
        ) : Event

        /** SOC dropped through the low-SOC threshold; [socPct] is the crossing reading. */
        data class LowSoc(
            val socPct: Double,
            val thresholdPct: Double,
        ) : Event

        /** Pack temperature rose through the high-temp threshold; [tempC] is the crossing reading. */
        data class HighPackTemp(
            val tempC: Double,
            val thresholdC: Double,
        ) : Event

        /** A scan found codes not in the previous scan; [newCodes] is sorted, deduped, non-empty. */
        data class NewDtc(
            val newCodes: List<String>,
        ) : Event
    }

    /** A single live telemetry reading the decider cares about. All fields are nullable/absent. */
    data class Sample(
        val capturedAtMs: Long,
        val packCurrentA: Double?,
        val packVoltage: Double?,
        val speedKph: Double?,
        val socPct: Double?,
        val packTempC: Double?,
    )

    // ---- charge accumulation -----------------------------------------------------------
    private var charging = false
    private var chargeSamples = 0
    private var chargeEnergyKwh = 0.0
    private var prevChargeKw: Double? = null
    private var prevChargeMs: Long? = null

    // ---- threshold arming --------------------------------------------------------------
    // "armed" means the reading is currently on the safe side of the threshold, so the next crossing
    // should fire. Starts armed so the very first crossing in a session alerts.
    private var lowSocArmed = true
    private var highTempArmed = true

    /** Feeds one live telemetry sample; returns the events (possibly several) it triggers. */
    fun onSample(sample: Sample): List<Event> {
        val events = ArrayList<Event>(2)
        evaluateCharge(sample)?.let { events.add(it) }
        evaluateLowSoc(sample)?.let { events.add(it) }
        evaluateHighTemp(sample)?.let { events.add(it) }
        return events
    }

    /**
     * Compares a scan's DTC set against the [previousCodes] baseline. Returns a [NewDtc] event when
     * codes appeared that were not present before (and the toggle is on), plus the full normalized
     * code set so the caller can persist it as the new baseline regardless.
     */
    fun onScan(
        scannedCodes: Collection<String>,
        previousCodes: Collection<String>,
    ): ScanResult {
        val normalized = normalizeCodes(scannedCodes)
        val baseline = normalizeCodes(previousCodes)
        val newCodes = normalized.filter { it !in baseline }.sorted()
        val event =
            if (settings.newDtcEnabled && newCodes.isNotEmpty()) {
                Event.NewDtc(newCodes)
            } else {
                null
            }
        return ScanResult(event, normalized)
    }

    data class ScanResult(
        val event: Event.NewDtc?,
        /** Sorted, deduped, upper-cased full code set from this scan (the new baseline to persist). */
        val allCodes: List<String>,
    )

    private fun evaluateCharge(sample: Sample): Event? {
        val isCharging = isChargingSample(sample)
        if (isCharging) {
            charging = true
            chargeSamples += 1
            accumulateChargeEnergy(sample)
            return null
        }
        if (!charging) {
            return null
        }
        // Transition charging -> not-charging: a charge just ended.
        val energy = chargeEnergyKwh
        val samples = chargeSamples
        resetChargeAccumulator()
        if (!settings.chargeCompleteEnabled || samples < MIN_CHARGE_SAMPLES) {
            return null
        }
        return Event.ChargeComplete(maxOf(0.0, energy))
    }

    private fun accumulateChargeEnergy(sample: Sample) {
        val v = sample.packVoltage
        val c = sample.packCurrentA
        if (v == null || c == null) {
            return
        }
        var kw = -(v * c) / 1000.0
        if (kw.isNaN() || kw.isInfinite()) {
            return
        }
        if (kw < 0.0) {
            kw = 0.0
        }
        val pKw = prevChargeKw
        val pMs = prevChargeMs
        if (pKw != null && pMs != null) {
            val hours = (sample.capturedAtMs - pMs) / 3_600_000.0
            if (hours > 0.0) {
                chargeEnergyKwh += ((pKw + kw) / 2.0) * hours
            }
        }
        prevChargeKw = kw
        prevChargeMs = sample.capturedAtMs
    }

    private fun resetChargeAccumulator() {
        charging = false
        chargeSamples = 0
        chargeEnergyKwh = 0.0
        prevChargeKw = null
        prevChargeMs = null
    }

    private fun evaluateLowSoc(sample: Sample): Event? {
        if (!settings.lowSocEnabled) {
            return null
        }
        val soc = sample.socPct ?: return null
        if (soc.isNaN()) {
            return null
        }
        val threshold = settings.lowSocThresholdPct
        if (lowSocArmed && soc <= threshold) {
            lowSocArmed = false
            return Event.LowSoc(soc, threshold)
        }
        if (!lowSocArmed && soc >= threshold + SOC_HYSTERESIS_PCT) {
            lowSocArmed = true
        }
        return null
    }

    private fun evaluateHighTemp(sample: Sample): Event? {
        if (!settings.highPackTempEnabled) {
            return null
        }
        val temp = sample.packTempC ?: return null
        if (temp.isNaN()) {
            return null
        }
        val threshold = settings.highPackTempThresholdC
        if (highTempArmed && temp >= threshold) {
            highTempArmed = false
            return Event.HighPackTemp(temp, threshold)
        }
        if (!highTempArmed && temp <= threshold - TEMP_HYSTERESIS_C) {
            highTempArmed = true
        }
        return null
    }

    companion object {
        /** Pack current more negative than this (amps INTO the pack) counts as charging. */
        const val CHARGING_PACK_CURRENT_A_THRESHOLD = 1.0

        /** Speed above this means the car is moving — not charging. */
        const val STATIONARY_SPEED_KPH = 1.0

        /** Minimum charging samples before a charge-complete notification is trusted. */
        const val MIN_CHARGE_SAMPLES = 3

        /** SOC must recover this far above the threshold before the low-SOC alert re-arms. */
        const val SOC_HYSTERESIS_PCT = 3.0

        /** Pack temp must fall this far below the threshold before the high-temp alert re-arms. */
        const val TEMP_HYSTERESIS_C = 3.0

        private fun isChargingSample(sample: Sample): Boolean {
            val speed = sample.speedKph
            if (speed != null && speed > STATIONARY_SPEED_KPH) {
                return false
            }
            val packCurrent = sample.packCurrentA ?: return false
            return packCurrent <= -CHARGING_PACK_CURRENT_A_THRESHOLD
        }

        private fun normalizeCodes(codes: Collection<String>): List<String> {
            val out = LinkedHashSet<String>(codes.size)
            for (code in codes) {
                val clean = code.trim().uppercase()
                if (clean.isNotEmpty()) {
                    out.add(clean)
                }
            }
            return out.toList()
        }
    }
}
