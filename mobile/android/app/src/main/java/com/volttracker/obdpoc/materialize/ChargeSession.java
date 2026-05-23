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

    public ChargeSession(
            long startedAtMs,
            long endedAtMs,
            long durationMs,
            Double voltageStart,
            Double voltageEnd,
            int interruptionCount,
            String chargerType,
            Confidence confidence) {
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.durationMs = durationMs;
        this.voltageStart = voltageStart;
        this.voltageEnd = voltageEnd;
        this.interruptionCount = interruptionCount;
        this.chargerType = chargerType == null ? "unknown" : chargerType;
        this.confidence = confidence == null ? Confidence.UNKNOWN : confidence;
    }
}
