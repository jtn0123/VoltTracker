package com.volttracker.obdpoc.materialize;

/**
 * Read-side view of one row from {@code telemetry_samples} as the materializers consume it. Numeric
 * fields are boxed because the underlying SQLite columns are nullable.
 *
 * <p>{@link #packVoltage} and {@link #packCurrentA} are the raw HV pack readings (mode-22 PIDs
 * 222429 / 222414). Both are needed independently — {@link TripMaterializer} integrates {@code
 * packVoltage * packCurrentA} for trip energy, and {@link ChargeSessionMaterializer} uses {@code
 * packCurrentA} alone to detect "current flowing into the pack" without the false-positive risk of
 * relying on the auxiliary 12V {@link #adapterVoltage}. {@link #powerKw} is the pre-multiplied
 * convenience copy of the same values for dashboards that don't want to integrate.
 */
public final class TelemetrySample {
    public final long capturedAtMs;
    public final Double speedKph;
    public final Integer rpm;
    public final Double adapterVoltage;
    public final Double packVoltage;
    public final Double packCurrentA;
    public final Double powerKw;
    public final Double socPct;

    public TelemetrySample(
            long capturedAtMs,
            Double speedKph,
            Integer rpm,
            Double adapterVoltage,
            Double packCurrentA,
            Double socPct) {
        // Back-compat constructor: callers that pre-date the pack_voltage column pass null.
        this(capturedAtMs, speedKph, rpm, adapterVoltage, null, packCurrentA, null, socPct);
    }

    public TelemetrySample(
            long capturedAtMs,
            Double speedKph,
            Integer rpm,
            Double adapterVoltage,
            Double packVoltage,
            Double packCurrentA,
            Double powerKw,
            Double socPct) {
        this.capturedAtMs = capturedAtMs;
        this.speedKph = speedKph;
        this.rpm = rpm;
        this.adapterVoltage = adapterVoltage;
        this.packVoltage = packVoltage;
        this.packCurrentA = packCurrentA;
        this.powerKw = powerKw;
        this.socPct = socPct;
    }
}
