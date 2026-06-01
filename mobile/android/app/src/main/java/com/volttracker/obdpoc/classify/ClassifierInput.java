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
     * HV pack current in amps. Sign convention matches the rest of the codebase: discharge =
     * positive (current flowing OUT of the pack, i.e. driving / accessory load), charge = negative
     * (current flowing INTO the pack, i.e. wall charger or strong regen). Null if unknown. See the
     * matching comments in {@code ChargeSessionMaterializer.isPluggedSample} and {@code
     * telemetry.js drivePackCurrent}.
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
        // Reject physically-impossible values so a parser bug or a wild adapter read surfaces here
        // (fail fast) instead of silently producing a confident-but-wrong classification. Bounds
        // are
        // deliberately generous — only nonsense trips them; null always means "unknown" and is
        // fine.
        // The single live caller (LiveSampleReader) catches this and degrades to an unknown sample,
        // so a transient glitch can never crash the polling loop.
        requireInRange("speedKph", speedKph, 0.0, 500.0);
        requireInRange("rpm", rpm == null ? null : rpm.doubleValue(), 0.0, 20_000.0);
        requireInRange("adapterVoltage", adapterVoltage, 0.0, 60.0);
        requireInRange("packCurrentA", packCurrentA, -2_000.0, 2_000.0);
        this.speedKph = speedKph;
        this.rpm = rpm;
        this.adapterVoltage = adapterVoltage;
        this.packCurrentA = packCurrentA;
        this.pluggedHint = pluggedHint;
        this.engineRunningHint = engineRunningHint;
        this.sampleAtMs = sampleAtMs;
    }

    /**
     * Throws {@link IllegalArgumentException} when a non-null value is NaN, infinite, or out of
     * range.
     */
    private static void requireInRange(String name, Double value, double min, double max) {
        if (value == null) {
            return;
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " out of range [" + min + ", " + max + "]: " + value);
        }
    }
}
