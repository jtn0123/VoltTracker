package com.volttracker.obdpoc.materialize;

/**
 * Read-side view of one row from {@code telemetry_samples} as the materializers consume it. Numeric
 * fields are boxed because the underlying SQLite columns are nullable.
 */
public final class TelemetrySample {
    public final long capturedAtMs;
    public final Double speedKph;
    public final Integer rpm;
    public final Double adapterVoltage;
    public final Double packCurrentA;
    public final Double socPct;

    public TelemetrySample(
            long capturedAtMs,
            Double speedKph,
            Integer rpm,
            Double adapterVoltage,
            Double packCurrentA,
            Double socPct) {
        this.capturedAtMs = capturedAtMs;
        this.speedKph = speedKph;
        this.rpm = rpm;
        this.adapterVoltage = adapterVoltage;
        this.packCurrentA = packCurrentA;
        this.socPct = socPct;
    }
}
