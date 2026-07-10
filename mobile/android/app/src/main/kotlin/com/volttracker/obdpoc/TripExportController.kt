package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.TripTrackFormatter
import org.json.JSONObject

/**
 * Orchestrates a single per-trip GPX/CSV export end-to-end: read the trip's route projection from
 * the local store, serialize + write it to the trip-export cache dir ([TripExportShareIntent]),
 * record the export into the `exports` table, and hand the file to the system share sheet.
 *
 * Holds the body off the `MainActivity` class surface (mirrors `EventNotificationHostDelegate`): the
 * host keeps a single instance and forwards [exportAndShare] in one line. Built around the
 * [DashboardHost] seam (store + UI hops + status) plus a [Context] for the file write / chooser, so
 * it stays exercisable without a fully constructed Activity.
 */
class TripExportController(
    private val context: Context,
    private val host: DashboardHost,
) {
    /**
     * Reads the route for [routeKey], writes it as [formatKey] (`gpx`/`csv`), records the export,
     * launches the share sheet, and returns the JSON result the bridge hands back to the dashboard.
     * Synchronous build (so the returned size/point count is honest); only the chooser launch is
     * posted to the UI thread.
     */
    fun exportAndShare(
        routeKey: String?,
        formatKey: String?,
    ): String {
        // The bulk all-trips export (M6) rides the same host seam with a sentinel format so it needs
        // no new MainActivity override (keeps the host surface — and the detekt TooManyFunctions
        // ceiling — flat). routeKey is ignored for the bulk path.
        if (ALL_TRIPS_FORMAT.equals(formatKey?.trim(), ignoreCase = true)) {
            return exportAllTripsAndShare()
        }
        // The charge-history CSV export (M1) rides the same host seam with its own sentinel format,
        // passing the optional electricity rate through the (otherwise-unused-for-bulk) routeKey slot.
        if (CHARGE_SESSIONS_FORMAT.equals(formatKey?.trim(), ignoreCase = true)) {
            return exportChargeSessionsAndShare(routeKey)
        }
        // The drive-summary card share rides the same seam; the routeKey slot carries a small JSON
        // payload (route key + the already-formatted stat strings the trip-detail sheet shows).
        if (CARD_FORMAT.equals(formatKey?.trim(), ignoreCase = true)) {
            return shareTripCard(routeKey)
        }
        val format =
            TripExportShareIntent.Format.fromKey(formatKey)
                ?: return error("invalid_format", "Choose GPX or CSV.")
        val cleanKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanKey.isEmpty()) {
            return error("invalid_id", "Choose a saved drive to export.")
        }
        val store =
            host.localStore?.takeIf { it.isOpen }
                ?: return error("storage_unavailable", "Local storage is not ready.")
        val route = readRoute(store, cleanKey) ?: return error("route_read_failed", "Could not read that drive.")
        if (TripTrackFormatter.pointCount(route) <= 0) {
            return error("empty_route", "That drive has no GPS points to export.")
        }
        val export =
            TripExportShareIntent.writeExport(context, route, format)
                ?: return error("export_failed", "Could not write the ${format.key.uppercase()} file.")
        recordExport(store, cleanKey, export)
        // Build the share intent synchronously so the bridge result reflects actual viability: a
        // FileProvider rejection must surface as ok=false rather than a false success.
        val shareIntent =
            TripExportShareIntent.buildShareIntent(context, export)
                ?: return error("share_failed", context.getString(R.string.status_trip_export_share_failed))
        launchShare(shareIntent)
        return success(export)
    }

    /**
     * Bulk all-trips export (M6): reads every logged trip's route, serializes them into one combined
     * CSV (a leading trip-id/label column then per-sample rows), records the export, launches the
     * share sheet, and returns the bridge JSON result. Bounded by [MAX_ALL_TRIPS] trips and
     * [ALL_TRIPS_POINT_LIMIT] samples/trip so a huge history can't blow up memory or the share file.
     * Synchronous build (so the returned byte/point count is honest); only the chooser launch hops to
     * the UI thread. Reached via [exportAndShare] with the [ALL_TRIPS_FORMAT] sentinel.
     */
    private fun exportAllTripsAndShare(): String {
        val store =
            host.localStore?.takeIf { it.isOpen }
                ?: return error("storage_unavailable", "Local storage is not ready.")
        val trips = readAllTrips(store)
        val pointCount = totalPointCount(trips)
        if (trips.length() == 0 || pointCount <= 0) {
            return error("empty_route", context.getString(R.string.status_all_trips_export_empty))
        }
        val export =
            TripExportShareIntent.writeAllTripsCsv(context, trips, pointCount)
                ?: return error("export_failed", "Could not write the all-trips CSV file.")
        recordAllTripsExport(store, export)
        val shareIntent =
            TripExportShareIntent.buildShareIntent(context, export)
                ?: return error("share_failed", context.getString(R.string.status_trip_export_share_failed))
        launchShare(shareIntent, R.string.status_all_trips_export_ready)
        return JSONObject()
            .put("ok", true)
            .put("format", "csv")
            .put("fileName", export.name)
            .put("bytes", export.bytes)
            .put("tripCount", trips.length())
            .put("pointCount", export.pointCount)
            .toString()
    }

    /**
     * Charge-history CSV export (M1): reads the logged charge sessions, serializes them into one CSV
     * (one row per charge — start/end time + SOC, energy, peak power, charger type, confidence, plus
     * an estimated-cost column when [rateArg] parses as a positive finite per-kWh rate), records the
     * export, launches the share sheet, and returns the bridge JSON result. Bounded by
     * [MAX_CHARGE_SESSIONS]. Reached via [exportAndShare] with the [CHARGE_SESSIONS_FORMAT] sentinel;
     * the rate rides through the routeKey slot (unused for bulk exports).
     */
    private fun exportChargeSessionsAndShare(rateArg: String?): String {
        val store =
            host.localStore?.takeIf { it.isOpen }
                ?: return error("storage_unavailable", "Local storage is not ready.")
        val rate = parsePricePerKwh(rateArg)
        val rows = readChargeSessions(store)
        if (rows.length() == 0) {
            return error("empty_route", context.getString(R.string.status_charge_sessions_export_empty))
        }
        val export =
            TripExportShareIntent.writeChargeSessionsCsv(context, rows, rate, rows.length())
                ?: return error("export_failed", "Could not write the charge-history CSV file.")
        recordChargeSessionsExport(store, export)
        val shareIntent =
            TripExportShareIntent.buildShareIntent(context, export)
                ?: return error("share_failed", context.getString(R.string.status_trip_export_share_failed))
        launchShare(shareIntent, R.string.status_charge_sessions_export_ready)
        return JSONObject()
            .put("ok", true)
            .put("format", "csv")
            .put("fileName", export.name)
            .put("bytes", export.bytes)
            .put("sessionCount", rows.length())
            .put("withCost", rate != null)
            .toString()
    }

    /**
     * Drive-summary card share: parses the dashboard's card payload (`{routeKey, title, subtitle,
     * stats:[{label, value}, …]}` — stat strings arrive already formatted so units/cost math stays
     * single-sourced in the WebView), renders the PNG with the route outline, records the export,
     * and launches the share sheet. Reached via [exportAndShare] with the [CARD_FORMAT] sentinel.
     */
    private fun shareTripCard(cardArg: String?): String {
        val card =
            try {
                JSONObject(cardArg ?: "")
            } catch (ex: org.json.JSONException) {
                return error("invalid_card", "Could not read the drive-card details.")
            }
        val routeKey = bridgeSafe(card.optString("routeKey", ""), BRIDGE_MAX_LABEL_LEN)
        if (routeKey.isEmpty()) {
            return error("invalid_id", "Choose a saved drive to share.")
        }
        val store =
            host.localStore?.takeIf { it.isOpen }
                ?: return error("storage_unavailable", "Local storage is not ready.")
        val route = readRoute(store, routeKey) ?: return error("route_read_failed", "Could not read that drive.")
        if (TripTrackFormatter.pointCount(route) <= 0) {
            return error("empty_route", "That drive has no GPS points to share.")
        }
        val stats = ArrayList<TripShareCardRenderer.CardStat>()
        val rawStats = card.optJSONArray("stats")
        if (rawStats != null) {
            for (i in 0 until minOf(rawStats.length(), TripShareCardRenderer.MAX_STATS)) {
                val stat = rawStats.optJSONObject(i) ?: continue
                val label = bridgeSafe(stat.optString("label", ""), CARD_TEXT_MAX)
                val value = bridgeSafe(stat.optString("value", ""), CARD_TEXT_MAX)
                if (label.isNotEmpty() && value.isNotEmpty()) {
                    stats.add(TripShareCardRenderer.CardStat(label, value))
                }
            }
        }
        val bitmap =
            TripShareCardRenderer.render(
                route,
                bridgeSafe(card.optString("title", ""), CARD_TEXT_MAX).ifEmpty { "Drive" },
                bridgeSafe(card.optString("subtitle", ""), CARD_TEXT_MAX),
                stats,
            )
        val export =
            TripExportShareIntent.writeCardPng(context, bitmap)
                ?: return error("export_failed", "Could not write the drive card image.")
        recordExport(store, routeKey, export)
        val shareIntent =
            TripExportShareIntent.buildShareIntent(context, export)
                ?: return error("share_failed", context.getString(R.string.status_trip_export_share_failed))
        launchShare(shareIntent)
        return success(export)
    }

    /**
     * Parses the electricity rate the bridge forwarded through the routeKey slot. A null/blank or
     * non-positive / non-finite value yields null, which omits the estimated-cost column.
     */
    private fun parsePricePerKwh(rateArg: String?): Double? {
        val cleaned = bridgeSafe(rateArg, BRIDGE_MAX_LABEL_LEN)
        if (cleaned.isEmpty()) {
            return null
        }
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun readChargeSessions(store: ObdLocalStore): org.json.JSONArray =
        try {
            store.projections().chargeSessionsForExport(MAX_CHARGE_SESSIONS)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "charge-sessions export read failed", ex)
            org.json.JSONArray()
        }

    private fun recordChargeSessionsExport(
        store: ObdLocalStore,
        export: TripExportShareIntent.TripExportFile,
    ) {
        try {
            store.recordAllTripsExport("csv_charges", export.name, export.format.mime, export.bytes)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "charge-sessions export recordExport failed", ex)
        }
    }

    private fun readAllTrips(store: ObdLocalStore): org.json.JSONArray =
        try {
            store.getAllTripsForExportJson(MAX_ALL_TRIPS, ALL_TRIPS_POINT_LIMIT)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "all-trips export read failed", ex)
            org.json.JSONArray()
        }

    private fun totalPointCount(trips: org.json.JSONArray): Int {
        var total = 0
        for (i in 0 until trips.length()) {
            val route = trips.optJSONObject(i)?.optJSONObject("route") ?: continue
            total += TripTrackFormatter.pointCount(route)
        }
        return total
    }

    private fun recordAllTripsExport(
        store: ObdLocalStore,
        export: TripExportShareIntent.TripExportFile,
    ) {
        try {
            store.recordAllTripsExport("csv_all", export.name, export.format.mime, export.bytes)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "all-trips export recordExport failed", ex)
        }
    }

    private fun readRoute(
        store: ObdLocalStore,
        routeKey: String,
    ): JSONObject? =
        try {
            store.getTripRouteJson(routeKey)
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "trip export route read failed", ex)
            null
        }

    private fun recordExport(
        store: ObdLocalStore,
        routeKey: String,
        export: TripExportShareIntent.TripExportFile,
    ) {
        try {
            store.recordExport(routeKey, export.format.key, export.name, export.format.mime, export.bytes)
        } catch (ex: RuntimeException) {
            // Recording is best-effort bookkeeping; never let it sink the share.
            Log.w(AppPrefs.LOG_TAG, "trip export recordExport failed", ex)
        }
    }

    private fun launchShare(
        intent: Intent,
        readyMessageRes: Int = R.string.status_trip_export_ready,
    ) {
        host.runOnUiThread {
            try {
                host.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.chooser_share_trip_export)),
                )
                host.publishStatus("ready", context.getString(readyMessageRes), false)
            } catch (ex: RuntimeException) {
                Log.w(AppPrefs.LOG_TAG, "trip export share launch failed", ex)
                host.publishStatus("blocked", context.getString(R.string.status_trip_export_share_failed), true)
            }
        }
    }

    private fun success(export: TripExportShareIntent.TripExportFile): String =
        JSONObject()
            .put("ok", true)
            .put("format", export.format.key)
            .put("fileName", export.name)
            .put("bytes", export.bytes)
            .put("pointCount", export.pointCount)
            .toString()

    private fun error(
        error: String,
        message: String,
    ): String =
        JSONObject()
            .put("ok", false)
            .put("error", error)
            .put("message", message)
            .toString()

    private companion object {
        /**
         * Sentinel "format" the bridge passes through [DashboardHost.exportTripFromBridge] to trigger
         * the bulk all-trips export without a dedicated host override (keeps MainActivity's member
         * count — and the detekt TooManyFunctions ceiling — flat).
         */
        const val ALL_TRIPS_FORMAT = "csv_all"

        /**
         * Sentinel "format" the bridge passes through [DashboardHost.exportTripFromBridge] to trigger
         * the charge-history CSV export (M1) without a dedicated host override (same flat-host-surface
         * rationale as [ALL_TRIPS_FORMAT]). The electricity rate rides through the routeKey slot.
         */
        const val CHARGE_SESSIONS_FORMAT = "csv_charges"

        /**
         * Sentinel "format" for the drive-summary card share; the routeKey slot carries the card
         * payload JSON (same flat-host-surface rationale as [ALL_TRIPS_FORMAT]).
         */
        const val CARD_FORMAT = "card"

        /** Per-string cap for card title/subtitle/stat text (they render onto the image only). */
        const val CARD_TEXT_MAX = 64

        /** Bulk-export bound: most-recent N trips, so a huge history can't blow up memory/file size. */
        const val MAX_ALL_TRIPS = 500

        /** Charge-export bound: most-recent N charge sessions, mirroring [MAX_ALL_TRIPS]. */
        const val MAX_CHARGE_SESSIONS = 500

        /** Bulk-export bound: samples per trip (caps the per-trip route projection). */
        const val ALL_TRIPS_POINT_LIMIT = 500
    }
}
