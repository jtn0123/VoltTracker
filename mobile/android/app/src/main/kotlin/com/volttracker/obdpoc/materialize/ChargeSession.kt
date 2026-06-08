package com.volttracker.obdpoc.materialize

/**
 * Materialized charge-session window. The materializer produces these from a continuous run of
 * plugged observations; persistence to `charge_sessions` happens in `ObdLocalStore`.
 */
class ChargeSession(
    @JvmField val startedAtMs: Long,
    @JvmField val endedAtMs: Long,
    @JvmField val durationMs: Long,
    @JvmField val voltageStart: Double?,
    @JvmField val voltageEnd: Double?,
    @JvmField val interruptionCount: Int,
    chargerType: String?,
    confidence: Confidence?,
    /** Pack SOC (%) observed at the first plugged sample, or `null` when unknown. */
    @JvmField val startSoc: Double? = null,
    /** Pack SOC (%) observed at the last plugged sample, or `null` when unknown. */
    @JvmField val endSoc: Double? = null,
    /** Peak charger power in kW seen during the window. */
    @JvmField val peakPowerKw: Double? = null,
    /** Trapezoidal energy delivered into the pack (kWh) over the window, or `null` when unknown. */
    @JvmField val energyKwh: Double? = null,
) {
    @JvmField val chargerType: String = chargerType ?: "unknown"

    @JvmField val confidence: Confidence = confidence ?: Confidence.UNKNOWN
}
