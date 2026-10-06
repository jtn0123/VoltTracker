package com.volttracker.obdpoc.ui.units

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings → Units for display. The UI states keep the car's readings in one fixed scale (the
 * store's miles, mph, °F and psi) and every screen formats them through here, so switching to
 * Metric changes what is shown and never what is stored or computed.
 *
 * Each quantity has a `…Value` (the number alone, for big figures with a separate unit label),
 * a `…Unit`, and a `…Text` ("38 mi" / "61 km").
 */
data class VoltUnits(
    val metric: Boolean = false,
) {
    // Distance
    fun distance(miles: Double): Double = if (metric) miles * KM_PER_MI else miles

    val distanceUnit: String get() = if (metric) "km" else "mi"

    /** Whole units, e.g. "38" / "61". */
    fun distanceWhole(miles: Double): String = distance(miles).roundToInt().toString()

    /** One decimal, e.g. "12.4" / "20.0". */
    fun distanceOneDecimal(miles: Double): String = oneDecimal(distance(miles))

    /** "38 mi" from 10 up, "7.3 mi" below (a short hop keeps its tenths). */
    fun distanceText(miles: Double): String {
        val d = distance(miles)
        val n = if (d >= WHOLE_FROM) d.roundToInt().toString() else oneDecimal(d)
        return "$n $distanceUnit"
    }

    /**
     * An odometer reading cut down to whole, grouped units like the cluster's: "59,448 mi" /
     * "95,673 km". The slack absorbs floating-point error in the km → mi → km round trip.
     */
    fun odometerText(miles: Double): String =
        "%,d %s".format(Locale.US, (distance(miles) + ODOMETER_SLACK).toLong(), distanceUnit)

    // Speed
    fun speed(mph: Double): Int = (if (metric) mph * KM_PER_MI else mph).roundToInt()

    val speedUnit: String get() = if (metric) "km/h" else "mph"

    fun speedText(mph: Double): String = "${speed(mph)} $speedUnit"

    // Temperature
    fun temp(fahrenheit: Double): Int = (if (metric) (fahrenheit - F_OFFSET) / F_PER_C else fahrenheit).roundToInt()

    val tempUnit: String get() = if (metric) "°C" else "°F"

    /** "74°F" / "23°C". */
    fun tempText(fahrenheit: Double): String = "${temp(fahrenheit)}$tempUnit"

    // Electric efficiency: mi/kWh (higher is better) or kWh/100 km (lower is better).
    fun efficiency(miPerKwh: Double): Double? =
        when {
            miPerKwh <= 0.0 || !miPerKwh.isFinite() -> null
            metric -> KM_BASE / (miPerKwh * KM_PER_MI)
            else -> miPerKwh
        }

    val efficiencyUnit: String get() = if (metric) "kWh/100 km" else "mi/kWh"

    /** "4.1" / "15.2" — one decimal, or null when the figure can't be formed. */
    fun efficiencyValue(miPerKwh: Double?): String? = miPerKwh?.let(::efficiency)?.let(::oneDecimal)

    fun efficiencyText(miPerKwh: Double): String? = efficiency(miPerKwh)?.let { "${oneDecimal(it)} $efficiencyUnit" }

    // Gas economy: mpg or L/100 km.
    fun economy(mpg: Double): Double? =
        when {
            mpg <= 0.0 || !mpg.isFinite() -> null
            metric -> L100KM_PER_MPG / mpg
            else -> mpg
        }

    val economyUnit: String get() = if (metric) "L/100 km" else "mpg"

    fun economyValue(mpg: Double?): String? = mpg?.let(::economy)?.let(::oneDecimal)

    fun economyText(mpg: Double): String? = economy(mpg)?.let { "${oneDecimal(it)} $economyUnit" }

    /** A figure typed in [economyUnit] back to mpg (L/100 km and mpg are each other's reciprocal × 235.2). */
    fun mpgFrom(shown: Double): Double = if (metric) L100KM_PER_MPG / shown else shown

    // Gas price: stored per US gallon, shown per litre in metric.
    fun gasPrice(perGallon: Double): Double = if (metric) perGallon / L_PER_GAL else perGallon

    val gasVolumeUnit: String get() = if (metric) "L" else "gal"

    /** A price typed per [gasVolumeUnit] back to $/gal. */
    fun gasPricePerGallon(shown: Double): Double = if (metric) shown * L_PER_GAL else shown

    // Tyre pressure

    /** Unrounded, for editors and range bounds. */
    fun pressureValue(psi: Double): Double = if (metric) psi / PSI_PER_KPA else psi

    fun pressure(psi: Double): Int = pressureValue(psi).roundToInt()

    val pressureUnit: String get() = if (metric) "kPa" else "psi"

    fun pressureText(psi: Double): String = "${pressure(psi)} $pressureUnit"

    /** A pressure typed in [pressureUnit] back to psi. */
    fun psiFrom(shown: Double): Double = if (metric) shown * PSI_PER_KPA else shown

    companion object {
        val Imperial = VoltUnits(metric = false)
        val Metric = VoltUnits(metric = true)

        fun of(metric: Boolean): VoltUnits = if (metric) Metric else Imperial

        const val KM_PER_MI = 1.609344
        const val PSI_PER_KPA = 0.145038
        const val L_PER_GAL = 3.785411784
        private const val F_PER_C = 1.8
        private const val F_OFFSET = 32.0
        private const val KM_BASE = 100.0

        /** 235.215 = 100 × 3.785411784 L/gal ÷ 1.609344 km/mi. */
        private const val L100KM_PER_MPG = 235.214583
        private const val WHOLE_FROM = 10.0
        private const val ODOMETER_SLACK = 0.001

        private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
    }
}
