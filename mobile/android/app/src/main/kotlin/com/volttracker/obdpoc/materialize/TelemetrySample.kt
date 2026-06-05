package com.volttracker.obdpoc.materialize

/**
 * Read-side view of one row from `telemetry_samples` as the materializers consume it. Numeric fields
 * are boxed because the underlying SQLite columns are nullable.
 *
 * [packVoltage] and [packCurrentA] are the raw HV pack readings (mode-22 PIDs 222429 / 222414). Both
 * are needed independently — `TripMaterializer` integrates `packVoltage * packCurrentA` for trip
 * energy, and `ChargeSessionMaterializer` uses `packCurrentA` alone to detect "current flowing into
 * the pack" without the false-positive risk of relying on the auxiliary 12V [adapterVoltage].
 * [powerKw] is the pre-multiplied convenience copy of the same values for dashboards that don't want
 * to integrate.
 */
class TelemetrySample(
    @JvmField val capturedAtMs: Long,
    @JvmField val speedKph: Double?,
    @JvmField val rpm: Int?,
    @JvmField val adapterVoltage: Double?,
    @JvmField val packVoltage: Double?,
    @JvmField val packCurrentA: Double?,
    @JvmField val powerKw: Double?,
    @JvmField val socPct: Double?,
) {
    // Back-compat constructor: callers that pre-date the pack_voltage column pass null.
    constructor(
        capturedAtMs: Long,
        speedKph: Double?,
        rpm: Int?,
        adapterVoltage: Double?,
        packCurrentA: Double?,
        socPct: Double?,
    ) : this(capturedAtMs, speedKph, rpm, adapterVoltage, null, packCurrentA, null, socPct)
}
