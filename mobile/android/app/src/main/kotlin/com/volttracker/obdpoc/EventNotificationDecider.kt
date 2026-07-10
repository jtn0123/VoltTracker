package com.volttracker.obdpoc

import com.volttracker.obdpoc.materialize.PackEnergyMath

/**
 * Pure decision core for the event-notification feature (M1). It is fed live telemetry samples and
 * diagnostic-scan results and decides which notifications should fire, with no Android dependency so
 * the firing edge cases are fully unit-testable.
 *
 * The event kinds:
 *
 * - **Charge complete / interrupted** — fires once when an active charge (pack current flowing into
 *   the pack at low speed, the same high-confidence signal [ChargeSessionMaterializer] uses)
 *   transitions back to not-charging. When charging stopped with the SOC at (or near) the user's
 *   target it's a [ChargeComplete] carrying the energy integrated across the charge window
 *   (trapezoidal, same sign convention as the materializer); when it stopped well below the target
 *   (e.g. the EVSE tripped or the cable was knocked loose) it's a [ChargeInterrupted] carrying the
 *   SOC at stop. Either only fires when at least [MIN_CHARGE_SAMPLES] charging samples were seen, so
 *   a single glitchy sample never raises a phantom alert. Both ride the charge-complete toggle.
 *
 * - **Target SOC reached** — fires once per charge when the SOC crosses the user's charge target
 *   while still charging (M2), reusing the same arming pattern as the threshold alerts so it cannot
 *   spam. Implicit under the charge-complete toggle (no separate toggle); suppressed when the target
 *   is 100% since the charge-complete alert already covers a full charge.
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
        // User charge target (M2). The live-charge ETA targets this, the target-SOC-reached ping
        // fires when the charge crosses it, and a charge that stops well below it is reported as
        // interrupted rather than complete (M3). Defaults to a full charge.
        val targetSocPct: Double = DEFAULT_TARGET_SOC_PCT,
    )

    sealed interface Event {
        /** A charge session just ended at (or near) target; [energyKwh] is the integrated energy (>= 0). */
        data class ChargeComplete(
            val energyKwh: Double,
        ) : Event

        /**
         * A charge ended well below the user's target (EVSE tripped / cable unplugged); [socPct] is
         * the SOC at stop, [targetPct] the target it fell short of. Distinct copy from ChargeComplete
         * so the owner knows to check the plug rather than assume the car finished charging. (M3.)
         */
        data class ChargeInterrupted(
            val socPct: Double,
            val targetPct: Double,
        ) : Event

        /** SOC rose through the user's charge target while charging; fires once per charge. (M2.) */
        data class TargetSocReached(
            val socPct: Double,
            val targetPct: Double,
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

        /**
         * A tracked maintenance item just went overdue (M2). [entryId] is the maintenance-log row id
         * (distinct per entry even when two rows share a [serviceType] label), [serviceType] is the
         * user's label for the service, [tripped] names the interval furthest past due, and
         * [overdueMagnitude] is the raw amount overdue in that interval's unit (km for
         * [MaintenanceDueEvaluator.Interval.KM], days for [MaintenanceDueEvaluator.Interval.MONTHS]).
         * [EventNotifier] formats the amount (unit + locale grouping) and derives the per-entry
         * notification id from [entryId] so two distinct overdue rows never collide onto one alert.
         * Decided on app-open by [MaintenanceDueEvaluator] (not the per-sample core); rides the
         * maintenance-due toggle and only this [Event] kind reaches [EventNotifier].
         */
        data class MaintenanceDue(
            val entryId: Long,
            val serviceType: String,
            val tripped: MaintenanceDueEvaluator.Interval,
            val overdueMagnitude: Double,
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
    private var lastChargeVoltage: Double? = null
    private var lastChargingAtMs = 0L

    // Last SOC seen while charging, so the charging->idle transition can tell a finished charge
    // (at/near target -> ChargeComplete) from an interrupted one (well below target -> M3).
    private var lastChargingSocPct: Double? = null

    // Highest SOC seen across the charge. The interrupted-vs-complete decision uses this peak rather
    // than the single final reading so one noisy low final SOC sample cannot flip a charge that
    // actually reached (near) the target into a false "interrupted" alert. The event still reports
    // the SOC at stop.
    private var maxChargingSocPct: Double? = null

    // Armed once per charge so the target-SOC-reached ping fires exactly once when the SOC first
    // crosses the target; reset when a new charge begins (M2).
    private var targetSocArmed = true

    // ---- threshold arming --------------------------------------------------------------
    // "armed" means the reading is currently on the safe side of the threshold, so the next crossing
    // should fire. Starts armed so the very first crossing in a session alerts.
    private var lowSocArmed = true
    private var highTempArmed = true

    /** Feeds one live telemetry sample; returns the events (possibly several) it triggers. */
    fun onSample(sample: Sample): List<Event> {
        val events = ArrayList<Event>(2)
        evaluateCharge(sample, events)
        evaluateLowSoc(sample)?.let { events.add(it) }
        evaluateHighTemp(sample)?.let { events.add(it) }
        return events
    }

    /**
     * Compares a scan's DTC set against the [previousCodes] baseline. Returns a [NewDtc] event when
     * codes appeared that were not present before (and the toggle is on), plus the full normalized
     * code set so the caller can persist it as the new baseline regardless.
     *
     * When [hasBaseline] is false this is the first scan ever — pre-existing codes (e.g. a months-old
     * pending P0420) are by definition not "new", so the event is suppressed and only the baseline is
     * established (the caller still persists [ScanResult.allCodes]). This is distinct from a prior
     * scan that found zero codes, where [hasBaseline] is true and a newly-appearing code DOES fire.
     */
    fun onScan(
        scannedCodes: Collection<String>,
        previousCodes: Collection<String>,
        hasBaseline: Boolean,
    ): ScanResult {
        val normalized = normalizeCodes(scannedCodes)
        val baseline = normalizeCodes(previousCodes)
        val newCodes = normalized.filter { it !in baseline }.sorted()
        val event =
            if (hasBaseline && settings.newDtcEnabled && newCodes.isNotEmpty()) {
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

    private fun evaluateCharge(
        sample: Sample,
        events: MutableList<Event>,
    ) {
        val isCharging = isChargingSample(sample)
        if (isCharging) {
            charging = true
            lastChargingAtMs = maxOf(lastChargingAtMs, sample.capturedAtMs)
            chargeSamples += 1
            accumulateChargeEnergy(sample)
            evaluateTargetReached(sample)?.let { events.add(it) }
            return
        }
        if (!charging) {
            return
        }
        // Missing current at a stationary sample is UNKNOWN, not a charge-end transition. Even a
        // definite non-charging reading must be separated from the last positive charging evidence
        // long enough to outlive an ordinary PID dropout; the persisted materializer uses the same
        // one-minute break confidence window.
        val hasChargeEndEvidence =
            sample.packCurrentA?.takeIf { it.isFinite() }?.let { it >= -CHARGING_PACK_CURRENT_A_THRESHOLD } == true ||
                sample.speedKph?.takeIf { it.isFinite() }?.let { it > STATIONARY_SPEED_KPH } == true
        if (!hasChargeEndEvidence || sample.capturedAtMs - lastChargingAtMs < CHARGE_END_DEBOUNCE_MS) {
            return
        }
        // Transition charging -> not-charging: a charge just ended.
        val energy = chargeEnergyKwh
        val samples = chargeSamples
        val socAtStop = lastChargingSocPct
        val peakSoc = maxChargingSocPct
        val target = settings.targetSocPct
        resetChargeAccumulator()
        if (!settings.chargeCompleteEnabled || samples < MIN_CHARGE_SAMPLES) {
            return
        }
        // Interrupted = the charge's PEAK SOC stayed well below the target (EVSE tripped / cable
        // unplugged). Using the peak rather than the single final reading means one noisy low final
        // sample cannot flip a charge that actually reached near target into a false "interrupted"
        // alert. Otherwise it's a normal completed charge. A null SOC at stop (current seen but SOC
        // never reported) falls back to the complete copy rather than a false "check the plug" alert;
        // the event still reports the SOC at stop.
        // Compare against a target the pack can actually reach: a full Volt reports ~90%, never the
        // default 100, so a normal completed charge would otherwise always land 5+ points "under
        // target" and be misreported as interrupted. A user-set sub-90 target is used unchanged.
        val reachableTarget = minOf(target, VOLT_FULL_SOC_PCT)
        if (socAtStop != null && peakSoc != null && peakSoc <= reachableTarget - INTERRUPTED_SOC_MARGIN_PCT) {
            events.add(Event.ChargeInterrupted(socAtStop, target))
            return
        }
        events.add(Event.ChargeComplete(maxOf(0.0, energy)))
    }

    /**
     * Fires [Event.TargetSocReached] once per charge when the charging SOC first reaches the user's
     * target (M2). Implicit under the charge-complete toggle. Suppressed when the target is a full
     * 100% charge, since the charge-complete alert already announces that.
     */
    private fun evaluateTargetReached(sample: Sample): Event? {
        val soc = sample.socPct
        if (soc == null || !soc.isFinite()) {
            return null
        }
        lastChargingSocPct = soc
        maxChargingSocPct = maxOf(maxChargingSocPct ?: soc, soc)
        // Require the same minimum-samples confidence as the charge-complete alert before pinging:
        // a single transient charging sample already at/above target must not fire a target-reached
        // alert that no ChargeComplete (which guards phantom charges) would back up. The SOC
        // bookkeeping above still runs on the early samples so the peak-SOC tracking stays accurate.
        if (chargeSamples < MIN_CHARGE_SAMPLES) {
            return null
        }
        val target = settings.targetSocPct
        if (!settings.chargeCompleteEnabled || target >= DEFAULT_TARGET_SOC_PCT) {
            return null
        }
        if (targetSocArmed && soc >= target) {
            targetSocArmed = false
            return Event.TargetSocReached(soc, target)
        }
        return null
    }

    private fun accumulateChargeEnergy(sample: Sample) {
        val c = sample.packCurrentA ?: return
        // Pack voltage is polled on a slower lane than current, so carry the last-known voltage
        // across a current-only sample rather than dropping it from the integral (B2). The
        // carried voltage resets with the rest of the accumulator at the start of each charge.
        sample.packVoltage?.let { lastChargeVoltage = it }
        val v = lastChargeVoltage ?: return
        var kw = PackEnergyMath.chargePowerKw(v, c) ?: return
        if (kw < 0.0) {
            kw = 0.0
        }
        val pKw = prevChargeKw
        val pMs = prevChargeMs
        if (pKw == null || pMs == null) {
            // First charging sample: establish the integration baseline.
            prevChargeKw = kw
            prevChargeMs = sample.capturedAtMs
            return
        }
        val segmentKwh = PackEnergyMath.trapezoidKwh(pKw, kw, pMs, sample.capturedAtMs)
        if (segmentKwh == null) {
            // Out-of-order or duplicate-timestamp sample: ignore it entirely and keep the existing
            // baseline. Rolling the baseline backward would double-count energy on the next in-order
            // sample, so we must not advance prevChargeKw/prevChargeMs here.
            return
        }
        chargeEnergyKwh += segmentKwh
        prevChargeKw = kw
        prevChargeMs = sample.capturedAtMs
    }

    private fun resetChargeAccumulator() {
        charging = false
        chargeSamples = 0
        chargeEnergyKwh = 0.0
        prevChargeKw = null
        prevChargeMs = null
        lastChargeVoltage = null
        lastChargingAtMs = 0L
        lastChargingSocPct = null
        maxChargingSocPct = null
        targetSocArmed = true
    }

    private fun evaluateLowSoc(sample: Sample): Event? {
        if (!settings.lowSocEnabled) {
            return null
        }
        val soc = sample.socPct ?: return null
        if (!soc.isFinite()) {
            return null
        }
        val threshold = settings.lowSocThresholdPct
        if (lowSocArmed && soc <= threshold) {
            lowSocArmed = false
            return Event.LowSoc(soc, threshold)
        }
        // Cap the re-arm target at SOC_MAX_PCT: a threshold clamped near 100 would otherwise need an
        // SOC above 100 (e.g. 99 + 3 = 102) to re-arm, which never occurs — the alert could never fire
        // again in a session. Capping keeps the re-arm headroom reachable at a fully charged pack.
        val rearmAt = minOf(threshold + SOC_HYSTERESIS_PCT, SOC_MAX_PCT)
        if (!lowSocArmed && soc >= rearmAt) {
            lowSocArmed = true
        }
        return null
    }

    private fun evaluateHighTemp(sample: Sample): Event? {
        if (!settings.highPackTempEnabled) {
            return null
        }
        val temp = sample.packTempC ?: return null
        if (!temp.isFinite()) {
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
        /**
         * Pack current more negative than this (amps INTO the pack) counts as charging — but only as
         * one of the two gates; see [isChargingSample], which also requires the car to be stationary.
         */
        const val CHARGING_PACK_CURRENT_A_THRESHOLD = 1.0

        /** Speed above this means the car is moving — not charging. */
        const val STATIONARY_SPEED_KPH = 1.0

        /** Minimum charging samples before a charge-complete notification is trusted. */
        const val MIN_CHARGE_SAMPLES = 3

        /** Minimum separation from the last charging sample before an end alert is trusted. */
        const val CHARGE_END_DEBOUNCE_MS: Long = 60_000L

        /** SOC must recover this far above the threshold before the low-SOC alert re-arms. */
        const val SOC_HYSTERESIS_PCT = 3.0

        /** Maximum reachable SOC; caps the low-SOC re-arm target so it stays attainable. */
        const val SOC_MAX_PCT = 100.0

        /** Pack temp must fall this far below the threshold before the high-temp alert re-arms. */
        const val TEMP_HYSTERESIS_C = 3.0

        /**
         * Default charge target — a full charge (M2). A target at or above this is a full charge, so
         * the target-reached ping defers to the charge-complete alert.
         */
        const val DEFAULT_TARGET_SOC_PCT = 100.0

        /**
         * A charge that stops this far (or more) below the target is reported as INTERRUPTED rather
         * than complete — distinguishes a tripped EVSE / unplugged cable from a normal finish or the
         * usual tail-end taper where the car cuts off a hair under the requested target.
         */
        const val INTERRUPTED_SOC_MARGIN_PCT = 5.0

        /**
         * The SOC a fully-charged Volt actually reports. The Gen-2 pack keeps a hidden buffer, so a
         * complete charge tops out around 88-91% on the raw PID and never reaches 100. The
         * interrupted check compares against min(target, this) so a normal full charge under the
         * default 100% target isn't misread as "interrupted / check the plug"; a target the user
         * sets below this is used as-is.
         */
        const val VOLT_FULL_SOC_PCT = 90.0

        /**
         * A sample counts as charging only when BOTH gates pass: the car is stationary (speed at or
         * below [STATIONARY_SPEED_KPH], or unknown) AND pack current is flowing into the pack (more
         * negative than [CHARGING_PACK_CURRENT_A_THRESHOLD]). A moving car, or one with no/positive
         * pack current, is not charging.
         */
        private fun isChargingSample(sample: Sample): Boolean {
            val speed = sample.speedKph
            if (speed != null && (!speed.isFinite() || speed > STATIONARY_SPEED_KPH)) {
                return false
            }
            val packCurrent = sample.packCurrentA ?: return false
            return packCurrent.isFinite() && packCurrent <= -CHARGING_PACK_CURRENT_A_THRESHOLD
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
