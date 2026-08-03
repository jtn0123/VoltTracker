package com.volttracker.obdpoc.ui.diag

/** Severity bucket for a trouble code — drives the row accent color. */
enum class DtcSeverity { INFO, WARNING, ALERT }

/** One diagnostic trouble code row. */
data class DtcCode(
    val code: String,
    val title: String,
    val adviceLabel: String,
    val severity: DtcSeverity,
    val pending: Boolean,
    val seenLabel: String,
)

/** One data-health checklist row. */
data class HealthItem(
    val name: String,
    val detail: String,
    val stateLabel: String,
    val ok: Boolean,
)

/**
 * Everything the Diagnostics screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 */
data class DiagUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val codes: List<DtcCode> = emptyList(),
    val lastScanLabel: String? = null,
    val adapterLabel: String = "--",
    val sampleCount: Int = 0,
    val runtimeLabel: String = "--",
    val vehicleStateLabel: String = "--",
    val health: List<HealthItem> = emptyList(),
) {
    companion object {
        /** Sample state mirroring the demo scenario's fault data. */
        val demo =
            DiagUiState(
                connected = true,
                statusLabel = "Live · 1 Hz",
                codes =
                    listOf(
                        DtcCode(
                            code = "P0420",
                            title = "Catalyst system efficiency below threshold",
                            adviceLabel = "Service soon — generally safe to drive",
                            severity = DtcSeverity.WARNING,
                            pending = false,
                            seenLabel = "4× · first 3d ago · last 1d ago",
                        ),
                        DtcCode(
                            code = "P0011",
                            title = "Camshaft position 'A' timing over-advanced",
                            adviceLabel = "Service soon — generally safe to drive",
                            severity = DtcSeverity.WARNING,
                            pending = true,
                            seenLabel = "1× · first 12h ago",
                        ),
                    ),
                lastScanLabel = "Last scan 2h ago",
                adapterLabel = "OBDLink MX+",
                sampleCount = 461,
                runtimeLabel = "47m",
                vehicleStateLabel = "driving (EV)",
                health =
                    listOf(
                        HealthItem("OBD stream", "Fresh sample now", "47×", ok = true),
                        HealthItem("GPS trace", "883 location samples", "idle", ok = true),
                        HealthItem("Database writes", "21,358 rows on device", "writing", ok = true),
                        HealthItem("PID parsing", "driving (EV)", "active", ok = true),
                        HealthItem("Background test", "Run after your next drive", "manual", ok = false),
                    ),
            )
    }
}
