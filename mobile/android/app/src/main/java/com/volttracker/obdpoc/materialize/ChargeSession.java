package com.volttracker.obdpoc.materialize;

/**
 * Materialized charge-session window. The materializer produces these from a continuous run of
 * "plugged" observations; persistence to {@code charge_sessions} happens in {@code ObdLocalStore}.
 */
public final class ChargeSession {
    public final long startedAtMs;
    public final long endedAtMs;
    public final long durationMs;
    public final Double voltageStart;
    public final Double voltageEnd;
    public final int interruptionCount;
    public final String chargerType;
    public final Confidence confidence;

    /** Pack SOC (%) observed at the first plugged sample, or {@code null} when unknown. */
    public final Double startSoc;

    /** Pack SOC (%) observed at the last plugged sample, or {@code null} when unknown. */
    public final Double endSoc;

    /**
     * Peak charger power in kW seen during the window, computed from packVoltage * packCurrentA
     * (sign flipped so positive = power INTO the pack, the natural convention for charging rates).
     * {@code null} when no usable samples carried both values.
     */
    public final Double peakPowerKw;

    /**
     * Trapezoidal energy delivered into the pack (kWh) over the window; {@code null} when unknown.
     */
    public final Double energyKwh;

    public ChargeSession(
            long startedAtMs,
            long endedAtMs,
            long durationMs,
            Double voltageStart,
            Double voltageEnd,
            int interruptionCount,
            String chargerType,
            Confidence confidence) {
        this(
                startedAtMs,
                endedAtMs,
                durationMs,
                voltageStart,
                voltageEnd,
                interruptionCount,
                chargerType,
                confidence,
                null,
                null,
                null,
                null);
    }

    public ChargeSession(
            long startedAtMs,
            long endedAtMs,
            long durationMs,
            Double voltageStart,
            Double voltageEnd,
            int interruptionCount,
            String chargerType,
            Confidence confidence,
            Double startSoc,
            Double endSoc,
            Double peakPowerKw,
            Double energyKwh) {
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.durationMs = durationMs;
        this.voltageStart = voltageStart;
        this.voltageEnd = voltageEnd;
        this.interruptionCount = interruptionCount;
        this.chargerType = chargerType == null ? "unknown" : chargerType;
        this.confidence = confidence == null ? Confidence.UNKNOWN : confidence;
        this.startSoc = startSoc;
        this.endSoc = endSoc;
        this.peakPowerKw = peakPowerKw;
        this.energyKwh = energyKwh;
    }
}
