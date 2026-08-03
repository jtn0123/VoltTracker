package com.volttracker.obdpoc.ui.charge

/** One row in the recent-charges list. */
data class ChargeEntry(
    val whenLabel: String,
    val level: String,
    val fromPercent: Int,
    val toPercent: Int,
    val powerKw: Double,
    val durationLabel: String,
    val energyKwh: Double,
    val active: Boolean = false,
)

/** One bar in the monthly energy chart. */
data class MonthEnergy(
    val label: String,
    val kwh: Double,
)

/**
 * Everything the Charge screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 */
data class ChargeUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val charging: Boolean = false,
    val socPercent: Double = 0.0,
    val evRangeMiles: Double = 0.0,
    val chargeKw: Double = 0.0,
    val chargeLevel: String = "",
    val timeToFullLabel: String? = null,
    val recentCharges: List<ChargeEntry> = emptyList(),
    val monthKwh: Double = 0.0,
    val monthChargeCount: Int = 0,
    val avgKwhPerCharge: Double = 0.0,
    val monthly: List<MonthEnergy> = emptyList(),
    val cellBalanceLabel: String? = null,
) {
    companion object {
        /** Sample state mirroring the demo scenario mid-L2-charge. */
        val demo =
            ChargeUiState(
                connected = true,
                statusLabel = "Live · 1 Hz",
                charging = true,
                socPercent = 71.0,
                evRangeMiles = 30.0,
                chargeKw = 7.1,
                chargeLevel = "Level 2",
                timeToFullLabel = "1h 40m to full",
                recentCharges =
                    listOf(
                        ChargeEntry("Now", "Level 2", 54, 71, 7.1, "charging", 3.0, active = true),
                        ChargeEntry("Yesterday", "Level 2", 24, 91, 7.2, "3h 24m", 11.8),
                        ChargeEntry("2 days ago", "Level 2", 36, 90, 7.0, "3h 00m", 9.6),
                        ChargeEntry("4 days ago", "Level 1", 58, 88, 1.3, "4h 36m", 5.2),
                    ),
                monthKwh = 24.4,
                monthChargeCount = 4,
                avgKwhPerCharge = 7.4,
                monthly =
                    listOf(
                        MonthEnergy("May", 31.2),
                        MonthEnergy("Jun", 26.8),
                        MonthEnergy("Jul", 29.6),
                        MonthEnergy("Aug", 24.4),
                    ),
                cellBalanceLabel = "Δ 8 mV across 96 groups — healthy",
            )
    }
}
