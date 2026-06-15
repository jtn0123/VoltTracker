package com.volttracker.obdpoc.widget

/**
 * Compact, immutable snapshot of the latest vehicle state, sized for the home-screen widget.
 *
 * Deliberately tiny: only the four facts the widget shows ([socPct], [charging], [connected],
 * freshness) plus a coarse [vehicleState] string for the status line. The full telemetry payload is
 * NOT carried here — the widget reads this from SharedPreferences out-of-process and must stay cheap
 * to (de)serialize.
 *
 * Two timestamps, intentionally distinct (report item B7):
 * - [updatedAtMs] — the last time a *display field* (SOC/charging/connected/state) actually changed.
 *   This drives the change-debounced persist/redraw so an identical 1 Hz sample doesn't thrash prefs.
 * - [lastSampleAtMs] — the last time *any* fresh sample arrived, bumped on every sample even when the
 *   display is unchanged. This is the freshness signal, so a steady charge/parked period (flat SOC
 *   for >15 min) is NOT wrongly flagged "stale" while fresh identical samples keep arriving.
 *
 * [socPct] is `-1` when SOC is unknown. Both timestamps are `0L` when no data has ever been written
 * (the "open the app to connect" state). [vehicleState] uses the same payload keys as
 * `VehicleStateClassifier` (`charging`, `driving_ev`, `parked`, …) and may be empty.
 */
data class WidgetSnapshot(
    val socPct: Int,
    val charging: Boolean,
    val connected: Boolean,
    val vehicleState: String,
    val updatedAtMs: Long,
    val lastSampleAtMs: Long = updatedAtMs,
) {
    /** True when nothing has ever been persisted — the widget shows its empty/onboarding line. */
    fun hasData(): Boolean = lastSampleAtMs > 0L || updatedAtMs > 0L

    /** True when SOC is known (non-negative). */
    fun hasSoc(): Boolean = socPct >= 0

    /** The freshness reference time: the last sample arrival, falling back to the change time. */
    fun freshnessAtMs(): Long = if (lastSampleAtMs > 0L) lastSampleAtMs else updatedAtMs

    companion object {
        const val UNKNOWN_SOC = -1

        /** The empty snapshot used before any session has written state. */
        @JvmField
        val EMPTY =
            WidgetSnapshot(
                UNKNOWN_SOC,
                charging = false,
                connected = false,
                vehicleState = "",
                updatedAtMs = 0L,
                lastSampleAtMs = 0L,
            )
    }
}
