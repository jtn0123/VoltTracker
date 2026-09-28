package com.volttracker.obdpoc.ui.charge

/**
 * One logged charge (the same observed + inferred rows the classic Charge card and the charge
 * CSV export read). Nullable fields are "not recorded": the row shows less, never a guess.
 */
data class ChargeSession(
    val startedAtMs: Long,
    val endedAtMs: Long?,
    /** "L1" / "L2" / "DC fast", or null when the logger could not tell. */
    val level: String?,
    val fromSoc: Int?,
    val toSoc: Int?,
    val energyKwh: Double?,
    /** Billed at the public rate (DC fast / public charger) when one is set in Settings → Costs. */
    val publicCharger: Boolean = false,
)

/** One pack SOC reading during the charge in progress. */
data class SocPoint(
    val atMs: Long,
    val soc: Float,
)

/**
 * Everything the Charge screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 */
data class ChargeUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val charging: Boolean = false,
    /** Raw pack SOC (%) — what time-to-full is computed from. */
    val socPercent: Double = 0.0,
    /** The SOC the car's cluster shows (%), or null when the car hasn't reported it. */
    val displayedSocPercent: Double? = null,
    /** The car's own EV range estimate in miles; null (caption hidden) until it reports. */
    val evRangeMiles: Double? = null,
    val sohPct: Double? = null,
    /** The pack's measured capacity (Ah), read with [sohPct]; null until the car reports it. */
    val capacityAh: Double? = null,
    val chargeKw: Double = 0.0,
    val acVolts: Double? = null,
    val acAmps: Double? = null,
    /** "L1" / "L2", or null when the car hasn't said. */
    val level: String? = null,
    /** SOC when this charge began; null until a charge is seen starting. */
    val fromSoc: Double? = null,
    val addedKwh: Double = 0.0,
    val startedAtMs: Long? = null,
    /** Wall-clock time of the newest sample (ms) — "now" on the session chart. */
    val sampleAtMs: Long = 0L,
    /** Pack temperature (°F), or null before the first reading. */
    val packTempF: Int? = null,
    /** Settings → Costs ($/kWh); 0 = not set, so no cost is shown. */
    val homeRate: Double = 0.0,
    val publicRate: Double = 0.0,
    /** Settings → Charging → charge limit (%). */
    val targetSoc: Int = 100,
    /** Pack SOC across the charge in progress, oldest first. */
    val socPoints: List<SocPoint> = emptyList(),
    /** Logged charges, newest first. */
    val sessions: List<ChargeSession> = emptyList(),
) {
    companion object {
        /** 9:42 PM on Apr 30 2026 (local) — the mockup's "tonight". */
        private const val DEMO_NOW_MS = 1_777_610_520_000L
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR

        /** Sample state mirroring the mockup mid-L2-charge (a 2017 Volt tops out at 3.6 kW). */
        val demo: ChargeUiState
            get() =
                ChargeUiState(
                    connected = true,
                    statusLabel = "Live · 1 Hz",
                    charging = true,
                    socPercent = 71.0,
                    evRangeMiles = 29.0,
                    sohPct = 91.0,
                    chargeKw = 3.6,
                    acVolts = 240.0,
                    acAmps = 15.0,
                    level = "L2",
                    fromSoc = 41.0,
                    addedKwh = 4.3,
                    startedAtMs = DEMO_NOW_MS - 30 * MIN,
                    sampleAtMs = DEMO_NOW_MS,
                    packTempF = 74,
                    homeRate = 0.12,
                    socPoints =
                        (0..6).map { i ->
                            SocPoint(DEMO_NOW_MS - 30 * MIN + i * 5 * MIN, 41f + i * 5f)
                        },
                    sessions = demoSessions(DEMO_NOW_MS),
                )

        /** The demo's charge history, anchored on [nowMs] so the dates read as recent. */
        fun demoSessions(nowMs: Long): List<ChargeSession> =
            listOf(
                ChargeSession(nowMs - DAY - 24 * MIN, nowMs - DAY + 3 * HOUR, "L2", 24, 91, 11.8),
                ChargeSession(nowMs - 2 * DAY + 22 * MIN, nowMs - 2 * DAY + 3 * HOUR, "L2", 36, 90, 9.6),
                ChargeSession(nowMs - 3 * DAY - 3 * HOUR - 30 * MIN, nowMs - 3 * DAY, "L1", 58, 88, 5.2),
                ChargeSession(nowMs - 4 * DAY - 54 * MIN, nowMs - 4 * DAY + 2 * HOUR, "L2", 32, 90, 10.4),
            )
    }
}
