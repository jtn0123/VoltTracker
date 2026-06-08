package com.volttracker.obdpoc.classify

/**
 * Immutable bundle of signals fed to [VehicleStateClassifier]. Nullable fields mean the caller can
 * leave a signal unknown instead of inventing a sentinel; [sampleAtMs] is always set.
 */
class ClassifierInput(
    /** Vehicle speed in km/h. */
    @JvmField val speedKph: Double?,
    /** Engine RPM. A few hundred or more strongly suggests the ICE is running. */
    @JvmField val rpm: Int?,
    /** ELM327 ATRV adapter voltage in volts. Around 13.0V+ suggests DC-DC is active. */
    @JvmField val adapterVoltage: Double?,
    /**
     * HV pack current in amps. Sign convention matches the rest of the codebase: discharge =
     * positive, charge = negative.
     */
    @JvmField val packCurrentA: Double?,
    /** True if a Volt PID indicates the car is plugged in. */
    @JvmField val pluggedHint: Boolean?,
    /** True if RPM > 200 or an explicit PID says the ICE is running. */
    @JvmField val engineRunningHint: Boolean?,
    /** Wall-clock when the underlying sample was taken. Always set. */
    @JvmField val sampleAtMs: Long,
) {
    init {
        // Reject physically-impossible values so a parser bug surfaces here instead of silently
        // producing a confident-but-wrong classification. Null always means "unknown".
        requireInRange("speedKph", speedKph, 0.0, 500.0)
        requireInRange("rpm", rpm?.toDouble(), 0.0, 20_000.0)
        requireInRange("adapterVoltage", adapterVoltage, 0.0, 60.0)
        requireInRange("packCurrentA", packCurrentA, -2_000.0, 2_000.0)
    }

    companion object {
        private fun requireInRange(
            name: String,
            value: Double?,
            min: Double,
            max: Double,
        ) {
            if (value == null) {
                return
            }
            if (value.isNaN() || value.isInfinite() || value < min || value > max) {
                throw IllegalArgumentException("$name out of range [$min, $max]: $value")
            }
        }
    }
}
