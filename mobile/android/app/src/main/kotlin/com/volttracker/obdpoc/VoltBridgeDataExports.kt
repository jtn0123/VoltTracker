package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdTripLabels
import org.json.JSONObject

internal class VoltBridgeDataExports(
    private val activity: DashboardHost,
    private val stateProvider: BridgeStateProvider,
) {
    fun exportDebugBundle(): String =
        stateProvider.requireDataBackup().exportDebugBundle(
            stateProvider.getAppStateJson(),
            stateProvider.getStorageSummaryJson(),
        )

    fun shareBackup() {
        activity.runOnUiThread(stateProvider.requireBackupController()::launchShare)
    }

    fun shareEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = VoltBridge.safe(passphrase, VoltBridge.MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedShare(cleanPassphrase)
        }
    }

    fun restoreBackup() {
        activity.runOnUiThread(stateProvider.requireBackupController()::launchRestorePicker)
    }

    fun restoreEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = VoltBridge.safe(passphrase, VoltBridge.MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedRestorePicker(cleanPassphrase)
        }
    }

    fun clearStoredData() {
        if (activity.isLoggingActive()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
            }
            return
        }
        activity.confirmBridgeAction(
            "Clear stored data?",
            "This permanently deletes local OBD sessions, samples, routes, diagnostics, and storage rollups from this phone.",
            "Clear data",
        ) {
            clearStoredDataConfirmed()
        }
    }

    private fun clearStoredDataConfirmed() {
        activity.runOnBackground {
            if (activity.isLoggingActive()) {
                activity.runOnUiThread {
                    activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
                }
                return@runOnBackground
            }
            try {
                activity.localStore?.clearAllData()
            } catch (ex: RuntimeException) {
                Log.w(MainActivity.TAG, "clearStoredData failed", ex)
                activity.runOnUiThread {
                    activity.publishStatus("blocked", "Could not clear the local OBD database.", true)
                }
                return@runOnBackground
            }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus("ready", "On-phone OBD database cleared.", false)
            }
        }
    }

    fun exportDetailedSignalLog(id: String?): String {
        val rowId = VoltBridge.parsePositiveId(id)
        // isOpen guard: same teardown-race contract as DashboardStorageReader — a store
        // closed by onDestroy must degrade to an error payload, not throw into the bridge.
        val store = activity.localStore?.takeIf { it.isOpen }
        if (rowId <= 0L || store == null) {
            return errorPayload("invalid_id", "Choose a saved detailed signal log.")
        }
        return store.getEnhancedCapabilityExportJson(rowId).toString()
    }

    fun exportDetailedSignalLogs(): String {
        val store = activity.localStore?.takeIf { it.isOpen }
        if (store == null) {
            return errorPayload("storage_unavailable", "Local storage is not ready.")
        }
        return store.getEnhancedCapabilitiesExportJson(250).toString()
    }

    /**
     * Exports the trip identified by [routeKeyOrSessionId] as a GPX track and launches the share
     * sheet. Forwards to the host seam (which reads the route, writes the cache file, records the
     * export, and shares); returns the host's JSON result verbatim. The id may be a bare session id
     * or a `sessionId:startedAt:endedAt` route key — the store resolves both.
     */
    fun exportTripGpx(routeKeyOrSessionId: String?): String =
        activity.exportTripFromBridge(VoltBridge.safe(routeKeyOrSessionId, VoltBridge.MAX_LABEL_LEN), "gpx")

    /** Exports the same trip as a CSV sample log. See [exportTripGpx]. */
    fun exportTripCsv(routeKeyOrSessionId: String?): String =
        activity.exportTripFromBridge(VoltBridge.safe(routeKeyOrSessionId, VoltBridge.MAX_LABEL_LEN), "csv")

    /**
     * Bulk all-trips CSV export (M6): every logged trip's GPS samples in one combined CSV with a
     * leading trip-id/label column. Rides the existing trip-export host seam with the `csv_all`
     * sentinel format (so it needs no new host override; the controller dispatches it to the
     * all-trips path). Returns the host's JSON result verbatim.
     */
    fun exportAllTripsCsv(): String = activity.exportTripFromBridge(null, "csv_all")

    fun deleteDetailedSignalLog(id: String?) {
        val rowId = VoltBridge.parsePositiveId(id)
        if (rowId <= 0L) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a saved detailed signal log.", true)
            }
            return
        }
        activity.runOnBackground {
            var deleted = 0
            try {
                deleted = activity.localStore?.deleteEnhancedCapability(rowId) ?: 0
            } catch (ex: RuntimeException) {
                Log.w(MainActivity.TAG, "deleteDetailedSignalLog failed", ex)
            }
            val deletedRows = deleted
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (deletedRows > 0) "ready" else "blocked",
                    if (deletedRows > 0) {
                        "Detailed signal log removed."
                    } else {
                        "Detailed signal log was already gone."
                    },
                    deletedRows <= 0,
                )
            }
        }
    }

    /**
     * Reads [key] from [parsed] as a finite, non-negative Double, or null when absent/JSON-null.
     * `optDouble` can yield NaN/Infinity for malformed input, so non-finite (and negative) values
     * are treated as absent and never reach storage.
     */
    private fun optPositiveFiniteDouble(
        parsed: JSONObject,
        key: String,
    ): Double? =
        if (parsed.has(key) && !parsed.isNull(key)) {
            parsed.optDouble(key).takeIf { it.isFinite() && it >= 0.0 }
        } else {
            null
        }

    private fun errorPayload(
        error: String,
        message: String,
    ): String =
        JSONObject()
            .put("ok", false)
            .put("error", error)
            .put("message", message)
            .toString()

    fun markTripNotTrip(routeKey: String?) {
        val cleanRouteKey = VoltBridge.safe(routeKey, VoltBridge.MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored map trip to mark as not a trip.", true)
            }
            return
        }
        activity.confirmBridgeAction(
            "Mark as not a trip?",
            "This hides the selected route from Maps and Trips. Raw samples stay on the phone for diagnostics and backups.",
            "Mark not trip",
        ) {
            markTripNotTripConfirmed(cleanRouteKey)
        }
    }

    /**
     * Sets (or clears, when [label] is blank) the user label for the trip identified by [routeKey]
     * (M4). Marshals to the background, persists, then refreshes the dashboard's trip data so the
     * label shows immediately. Mirrors [markTripNotTrip]'s threading without the confirm dialog —
     * naming a trip is reversible and low-stakes.
     */
    fun setTripLabel(
        routeKey: String?,
        label: String?,
    ) {
        val cleanRouteKey = VoltBridge.safe(routeKey, VoltBridge.MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored trip to rename.", true)
            }
            return
        }
        // The label cap is owned by the data layer (ObdTripLabels re-truncates to the same length on
        // store), so reference that single constant rather than a second, larger bridge cap that the
        // storage would silently clip — keeping one cap for the trip-label path.
        val cleanLabel = VoltBridge.safe(label, ObdTripLabels.MAX_LABEL_LEN)
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripLabel(cleanRouteKey, cleanLabel) == true
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "setTripLabel failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (changed) "ready" else "blocked",
                    when {
                        !changed -> "That trip could not be renamed."
                        cleanLabel.isEmpty() -> "Trip label cleared."
                        else -> "Trip renamed."
                    },
                    !changed,
                )
            }
        }
    }

    /**
     * Sets or clears the user "favorite" flag for the trip identified by [routeKey] (M4 favorites
     * half). Marshals to the background, persists as a route-key-keyed status event (no schema
     * change), then refreshes the dashboard so the star state shows immediately. Mirrors
     * [setTripLabel]'s threading without a confirm dialog — favoriting is reversible and low-stakes.
     */
    fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ) {
        val cleanRouteKey = VoltBridge.safe(routeKey, VoltBridge.MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored trip to favorite.", true)
            }
            return
        }
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripFavorite(cleanRouteKey, favorite) == true
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "setTripFavorite failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (changed) "ready" else "blocked",
                    when {
                        !changed -> "That trip could not be updated."
                        favorite -> "Trip added to favorites."
                        else -> "Trip removed from favorites."
                    },
                    !changed,
                )
            }
        }
    }

    /**
     * Records a maintenance-log entry (M5) from the dashboard's add-entry form. [json] carries
     * `type`, `note`, optional `odometerKm`, optional `date` (ms epoch; defaults to now), and the
     * optional service interval `intervalKm` / `intervalMonths` (M1/C4) that drives the dashboard's
     * "next due" line. Refreshes the dashboard on success so the new row renders.
     */
    fun addMaintenanceEntry(json: String?) {
        val parsed =
            try {
                JSONObject(VoltBridge.safe(json, VoltBridge.MAX_DETAIL_LEN))
            } catch (ex: org.json.JSONException) {
                Log.w(MainActivity.TAG, "addMaintenanceEntry: bad JSON", ex)
                JSONObject()
            }
        val type = VoltBridge.safe(parsed.optString("type", ""), VoltBridge.MAX_LABEL_LEN)
        val note = VoltBridge.safe(parsed.optString("note", ""), VoltBridge.MAX_DETAIL_LEN)
        if (type.isEmpty() && note.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Add a maintenance type or note before saving.", true)
            }
            return
        }
        val odometerKm = optPositiveFiniteDouble(parsed, "odometerKm")
        // Interval > 0 only (a non-positive interval can never come "due"); non-finite is rejected.
        val intervalKm = optPositiveFiniteDouble(parsed, "intervalKm")?.takeIf { it > 0.0 }
        val intervalMonths =
            if (parsed.has("intervalMonths") && !parsed.isNull("intervalMonths")) {
                parsed.optInt("intervalMonths", 0).takeIf { it > 0 }
            } else {
                null
            }
        val createdAtMs = parsed.optLong("date", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        activity.runOnBackground {
            val id =
                try {
                    activity.localStore?.addMaintenanceEntry(
                        createdAtMs,
                        odometerKm,
                        type,
                        note,
                        intervalKm,
                        intervalMonths,
                    ) ?: -1L
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "addMaintenanceEntry failed", ex)
                    -1L
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (id > 0L) "ready" else "blocked",
                    if (id > 0L) "Maintenance entry saved." else "Could not save the maintenance entry.",
                    id <= 0L,
                )
            }
        }
    }

    /** Newest-first maintenance log as a JSON array (M5). Read synchronously, like the export reads. */
    fun getMaintenanceLog(): String {
        val store = activity.localStore?.takeIf { it.isOpen }
        if (store == null) {
            return "[]"
        }
        return try {
            store.getMaintenanceLogJson(MAX_MAINTENANCE_ROWS).toString()
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "getMaintenanceLog failed", ex)
            "[]"
        }
    }

    /** Deletes one maintenance-log entry by id (M5), then refreshes the dashboard. */
    fun deleteMaintenanceEntry(id: String?) {
        val rowId = VoltBridge.parsePositiveId(id)
        if (rowId <= 0L) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a maintenance entry to remove.", true)
            }
            return
        }
        activity.runOnBackground {
            val deleted =
                try {
                    activity.localStore?.deleteMaintenanceEntry(rowId) ?: 0
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "deleteMaintenanceEntry failed", ex)
                    0
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (deleted > 0) "ready" else "blocked",
                    if (deleted > 0) "Maintenance entry removed." else "That maintenance entry was already gone.",
                    deleted <= 0,
                )
            }
        }
    }

    private fun markTripNotTripConfirmed(routeKey: String) {
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.markTripNotTrip(routeKey) == true
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "markTripNotTrip failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (changed) "ready" else "blocked",
                    if (changed) {
                        "Trip marked as not a trip. Raw data was kept."
                    } else {
                        "That map row could not be marked as not a trip."
                    },
                    !changed,
                )
            }
        }
    }

    private companion object {
        /** Cap on maintenance rows returned to the dashboard in one read. */
        const val MAX_MAINTENANCE_ROWS = 200
    }
}
