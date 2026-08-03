package com.volttracker.obdpoc.ui.insights

/** One labeled bar (efficiency bucket, monthly distance, weekly distance…). */
data class LabeledValue(
    val label: String,
    val value: Double,
)

/**
 * Everything the Insights screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 */
data class InsightsUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val lifetimeMiPerKwh: Double? = null,
    val cityMiPerKwh: Double? = null,
    val highwayMiPerKwh: Double? = null,
    val lifetimeMiles: Double = 0.0,
    val driveCount: Int = 0,
    val driveTimeLabel: String = "--",
    val topSpeedMph: Int = 0,
    /** Efficiency by speed bucket; the sweet spot is highlighted. */
    val efficiencyBySpeed: List<LabeledValue> = emptyList(),
    val efficiencyPeakIndex: Int? = null,
    val efficiencyPeakLabel: String? = null,
    val monthlyMiles: List<LabeledValue> = emptyList(),
    val avgMilesPerMonth: Double = 0.0,
    val batteryHealthPercent: Int? = null,
    val packVolts: Double = 0.0,
    val packTempF: Int = 0,
    val vehicleLabel: String = "",
    val odometerMiles: Int = 0,
    val loggedMiles: Double = 0.0,
    val maintenanceLabel: String = "none logged",
) {
    companion object {
        /** Sample state mirroring the demo scenario's history. */
        val demo =
            InsightsUiState(
                connected = true,
                statusLabel = "Live · 1 Hz",
                lifetimeMiPerKwh = 4.5,
                cityMiPerKwh = 3.7,
                highwayMiPerKwh = 4.6,
                lifetimeMiles = 24.0,
                driveCount = 3,
                driveTimeLabel = "1h 15m",
                topSpeedMph = 65,
                efficiencyBySpeed =
                    listOf(
                        LabeledValue("15", 2.9),
                        LabeledValue("30", 4.3),
                        LabeledValue("45", 4.7),
                        LabeledValue("60", 4.2),
                    ),
                efficiencyPeakIndex = 2,
                efficiencyPeakLabel = "Most efficient near 45 mph — about 4.7 mi/kWh",
                monthlyMiles =
                    listOf(
                        LabeledValue("May", 210.0),
                        LabeledValue("Jun", 168.0),
                        LabeledValue("Jul", 189.0),
                        LabeledValue("Aug", 19.0),
                    ),
                avgMilesPerMonth = 146.0,
                batteryHealthPercent = 91,
                packVolts = 364.0,
                packTempF = 73,
                vehicleLabel = "2017 Chevrolet Volt",
                odometerMiles = 48_213,
                loggedMiles = 24.0,
                maintenanceLabel = "none logged",
            )
    }
}
