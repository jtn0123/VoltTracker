package com.volttracker.obdpoc.classify;

/**
 * Immutable bundle of signals fed to {@link VehicleStateClassifier}. All fields are nullable so the
 * caller can leave a signal "unknown" instead of inventing a sentinel; {@code sampleAtMs} is the
 * only field that is always set.
 *
 * <p>This intentionally avoids a builder — the field set is small and adding a builder for six
 * fields would obscure how each signal maps to a classifier rule.
 */
public final class ClassifierInput {
    /** Vehicle speed in km/h. */
    public final Double speedKph;

    /** Engine RPM (a few hundred or more strongly suggests the ICE is running). */
    public final Integer rpm;

    /** ELM327 ATRV adapter voltage in volts. ~13.0V+ suggests DC-DC is active. */
    public final Double adapterVoltage;

    /**
     * HV pack current in amps. Positive = charging into the pack (i.e. wall charger or strong
     * regen), negative = discharging. Null if unknown.
     */
    public final Double packCurrentA;

    /** True if a Volt PID (e.g. 22 41 0D) indicates the car is plugged in. */
    public final Boolean pluggedHint;

    /** True if RPM > 200 or an explicit PID says the ICE is running. */
    public final Boolean engineRunningHint;

    /** Wall-clock when the underlying sample was taken. Always set. */
    public final long sampleAtMs;

    public ClassifierInput(
            Double speedKph,
            Integer rpm,
            Double adapterVoltage,
            Double packCurrentA,
            Boolean pluggedHint,
            Boolean engineRunningHint,
            long sampleAtMs) {
        this.speedKph = speedKph;
        this.rpm = rpm;
        this.adapterVoltage = adapterVoltage;
        this.packCurrentA = packCurrentA;
        this.pluggedHint = pluggedHint;
        this.engineRunningHint = engineRunningHint;
        this.sampleAtMs = sampleAtMs;
    }
}
