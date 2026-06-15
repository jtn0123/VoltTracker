package com.volttracker.obdpoc.widget

/**
 * Compact, immutable snapshot of the latest vehicle state, sized for the home-screen widget.
 *
 * Deliberately tiny: only the four facts the widget shows ([socPct], [charging], [connected],
 * [updatedAtMs]) plus a coarse [vehicleState] string for the status line. The full telemetry
 * payload is NOT carried here — the widget reads this from SharedPreferences out-of-process and
 * must stay cheap to (de)serialize.
 *
 * [socPct] is `-1` when SOC is unknown. [updatedAtMs] is `0L` when no data has ever been written
 * (the "open the app to connect" state). [vehicleState] uses the same payload keys as
 * `VehicleStateClassifier` (`charging`, `driving_ev`, `parked`, …) and may be empty.
 */
data class WidgetSnapshot(
    val socPct: Int,
    val charging: Boolean,
    val connected: Boolean,
    val vehicleState: String,
    val updatedAtMs: Long,
) {
    /** True when nothing has ever been persisted — the widget shows its empty/onboarding line. */
    fun hasData(): Boolean = updatedAtMs > 0L

    /** True when SOC is known (non-negative). */
    fun hasSoc(): Boolean = socPct >= 0

    companion object {
        const val UNKNOWN_SOC = -1

        /** The empty snapshot used before any session has written state. */
        @JvmField
        val EMPTY =
            WidgetSnapshot(UNKNOWN_SOC, charging = false, connected = false, vehicleState = "", updatedAtMs = 0L)
    }
}
