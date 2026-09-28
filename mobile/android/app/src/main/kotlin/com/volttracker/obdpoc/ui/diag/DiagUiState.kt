package com.volttracker.obdpoc.ui.diag

/**
 * How serious a trouble code is, as the classic dashboard grades it: the DTC table's severity when
 * it lists the code, else by family (chassis codes ALERT, the rest WARNING).
 */
enum class DtcSeverity { INFO, WARNING, ALERT }

/** One saved trouble code, as the store reports it, with its plain-language name when known. */
data class DtcCode(
    val code: String,
    /** "stored", "pending", "permanent", "current" or "freeze-frame". */
    val status: String = STATUS_STORED,
    /** The DTC table's wording, e.g. "Catalyst system efficiency below threshold (Bank 1)". */
    val description: String? = null,
    /** The DTC table's category ("1.4L engine", "HV battery", …), when it lists one. */
    val category: String? = null,
    val severity: DtcSeverity = DtcSeverity.WARNING,
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
    val seenCount: Int = 1,
) {
    companion object {
        const val STATUS_STORED = "stored"
        const val STATUS_PENDING = "pending"
        const val STATUS_PERMANENT = "permanent"
        const val STATUS_FREEZE_FRAME = "freeze-frame"
    }
}

/**
 * Everything the Health screen renders, as one immutable value. Pure data — previewable and
 * screenshot-testable with no service running.
 */
data class DiagUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val adapterLabel: String = "--",
    /**
     * The codes the car reported at its last check, newest first; null until the car has been
     * scanned (or a code saved), so "not scanned yet" never reads as "no trouble codes".
     */
    val codes: List<DtcCode>? = null,
    /** Saved codes the last scan or clear no longer found. */
    val earlierCodes: List<String> = emptyList(),
    /** When the codes were last read, if known. */
    val scannedAtMs: Long? = null,
    /** When the codes were last cleared from the car, if after the last scan. */
    val clearedAtMs: Long? = null,
    /** The clock the "2 h ago" labels count from. */
    val nowMs: Long = 0L,
    /** A scan or clear is running; the detail says which stage. */
    val busyLabel: String? = null,
) {
    companion object {
        /** The demo's check: taken two hours before [DEMO_NOW_MS]. */
        const val DEMO_NOW_MS = 1_777_585_320_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS

        /** Sample state mirroring the mockups' fault scenario (a catalyst code and a pending cam code). */
        fun demoAt(nowMs: Long): DiagUiState =
            DiagUiState(
                connected = true,
                statusLabel = "Live",
                adapterLabel = "OBDLink MX+",
                codes =
                    listOf(
                        DtcCode(
                            code = "P0420",
                            description = "Catalyst system efficiency below threshold (Bank 1)",
                            category = "1.4L engine",
                            firstSeenMs = nowMs - 3 * DAY_MS,
                            lastSeenMs = nowMs - 2 * HOUR_MS,
                            seenCount = 4,
                        ),
                        DtcCode(
                            code = "P0011",
                            status = DtcCode.STATUS_PENDING,
                            description = "Camshaft position 'A' timing over-advanced (Bank 1)",
                            category = "1.4L engine",
                            firstSeenMs = nowMs - 2 * HOUR_MS,
                            lastSeenMs = nowMs - 2 * HOUR_MS,
                        ),
                    ),
                scannedAtMs = nowMs - 2 * HOUR_MS,
                nowMs = nowMs,
            )

        val demo: DiagUiState = demoAt(DEMO_NOW_MS)
    }
}

/** What the Health screen can ask its host to do. Defaults are no-ops (previews, tests). */
class HealthActions(
    /** Read the car's trouble codes through the remembered adapter (or simulate it in the demo). */
    val onScan: () -> Unit = {},
    /** Erase the car's codes, after the host's own confirmation. */
    val onClear: () -> Unit = {},
    /** Share this plain-text report. */
    val onShare: (String) -> Unit = {},
    /** Live signals, freeze frames and the troubleshooter live in the classic dashboard. */
    val onOpenClassic: () -> Unit = {},
    /** Settings → Adapter. */
    val onOpenAdapter: () -> Unit = {},
)
