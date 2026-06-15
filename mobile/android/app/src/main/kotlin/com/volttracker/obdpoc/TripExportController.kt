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
        val format =
            TripExportShareIntent.Format.fromKey(formatKey)
                ?: return error("invalid_format", "Choose GPX or CSV.")
        val cleanKey = VoltBridge.safe(routeKey, VoltBridge.MAX_LABEL_LEN)
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

    private fun readRoute(
        store: ObdLocalStore,
        routeKey: String,
    ): JSONObject? =
        try {
            store.getTripRouteJson(routeKey)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "trip export route read failed", ex)
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
            Log.w(MainActivity.TAG, "trip export recordExport failed", ex)
        }
    }

    private fun launchShare(intent: Intent) {
        host.runOnUiThread {
            try {
                host.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.chooser_share_trip_export)),
                )
                host.publishStatus("ready", context.getString(R.string.status_trip_export_ready), false)
            } catch (ex: RuntimeException) {
                Log.w(MainActivity.TAG, "trip export share launch failed", ex)
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
}
