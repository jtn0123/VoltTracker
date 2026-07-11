package com.volttracker.obdpoc

/**
 * Trip-export bridge implementation: single-trip GPX/CSV, bulk all-trips CSV, charge-history CSV,
 * and the shareable drive-summary card. Every method forwards to the
 * [DashboardHost.exportTripFromBridge] host seam and returns its JSON result verbatim.
 * [VoltBridge] owns the `@JavascriptInterface` wrappers; this class is plumbing only.
 */
internal class VoltBridgeTripExports(
    private val activity: DashboardHost,
) {
    /**
     * Exports the trip identified by [routeKeyOrSessionId] as a GPX track and launches the share
     * sheet. Forwards to the host seam (which reads the route, writes the cache file, records the
     * export, and shares); returns the host's JSON result verbatim. The id may be a bare session id
     * or a `sessionId:startedAt:endedAt` route key — the store resolves both.
     */
    fun exportTripGpx(routeKeyOrSessionId: String?): String =
        activity.exportTripFromBridge(bridgeSafe(routeKeyOrSessionId, BRIDGE_MAX_LABEL_LEN), "gpx")

    /** Exports the same trip as a CSV sample log. See [exportTripGpx]. */
    fun exportTripCsv(routeKeyOrSessionId: String?): String =
        activity.exportTripFromBridge(bridgeSafe(routeKeyOrSessionId, BRIDGE_MAX_LABEL_LEN), "csv")

    /**
     * Bulk all-trips CSV export (M6): every logged trip's GPS samples in one combined CSV with a
     * leading trip-id/label column. Rides the existing trip-export host seam with the `csv_all`
     * sentinel format (so it needs no new host override; the controller dispatches it to the
     * all-trips path). Returns the host's JSON result verbatim.
     */
    fun exportAllTripsCsv(): String = activity.exportTripFromBridge(null, "csv_all")

    /**
     * Charge-history CSV export (M1): every logged charge session's start/end time, SOC, energy, peak
     * power, charger type, and confidence in one CSV, with an optional estimated-cost column when
     * [pricePerKwh] is a positive rate. Rides the existing trip-export host seam with the
     * `csv_charges` sentinel format (so it needs no new host override; the controller dispatches it to
     * the charge path), passing the rate through the routeKey slot (unused for bulk exports). Returns
     * the host's JSON result verbatim.
     */
    fun exportChargeSessionsCsv(pricePerKwh: String?): String =
        activity.exportTripFromBridge(bridgeSafe(pricePerKwh, BRIDGE_MAX_LABEL_LEN), "csv_charges")

    /**
     * Shareable drive-summary card: renders the drive's route outline + the already-formatted stat
     * strings from the trip-detail sheet as a PNG and launches the share sheet. Rides the existing
     * trip-export host seam with the `card` sentinel format; [cardJson] (`{routeKey, title,
     * subtitle, stats:[{label, value}, …]}`) travels through the routeKey slot with the wider
     * detail-length cap, and the controller re-clamps every field it draws. Returns the host's JSON
     * result verbatim.
     */
    fun shareTripCard(cardJson: String?): String =
        activity.exportTripFromBridge(bridgeSafe(cardJson, BRIDGE_MAX_DETAIL_LEN), "card")
}
