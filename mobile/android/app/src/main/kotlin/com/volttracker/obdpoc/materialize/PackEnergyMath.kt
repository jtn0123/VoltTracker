package com.volttracker.obdpoc.materialize

/** Shared pack sign convention and trapezoidal integration primitives. */
internal object PackEnergyMath {
    private const val MILLIS_PER_HOUR = 3_600_000.0

    fun dischargePowerKw(
        packVoltage: Double,
        packCurrentA: Double,
    ): Double? = finiteOrNull(packVoltage * packCurrentA / 1000.0)

    fun chargePowerKw(
        packVoltage: Double,
        packCurrentA: Double,
    ): Double? = dischargePowerKw(packVoltage, packCurrentA)?.let { -it }

    fun trapezoidKwh(
        previousPowerKw: Double,
        currentPowerKw: Double,
        previousAtMs: Long,
        currentAtMs: Long,
    ): Double? {
        val elapsedMs = currentAtMs - previousAtMs
        if (elapsedMs <= 0L) return null
        return finiteOrNull(((previousPowerKw + currentPowerKw) / 2.0) * (elapsedMs / MILLIS_PER_HOUR))
    }

    private fun finiteOrNull(value: Double): Double? = if (value.isFinite()) value else null
}
