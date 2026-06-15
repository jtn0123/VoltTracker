package com.volttracker.obdpoc

import android.util.Log
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
        val cleanLabel = VoltBridge.safe(label, VoltBridge.MAX_LABEL_LEN)
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
     * Records a maintenance-log entry (M5) from the dashboard's add-entry form. [json] carries
     * `type`, `note`, optional `odometerKm`, and optional `date` (ms epoch; defaults to now).
     * Refreshes the dashboard on success so the new row renders.
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
        val odometerKm =
            if (parsed.has("odometerKm") && !parsed.isNull("odometerKm")) {
                // optDouble can yield NaN/Infinity for malformed input; treat non-finite (and
                // negative) values as absent so they never reach storage.
                parsed.optDouble("odometerKm").takeIf { it.isFinite() && it >= 0.0 }
            } else {
                null
            }
        val createdAtMs = parsed.optLong("date", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        activity.runOnBackground {
            val id =
                try {
                    activity.localStore?.addMaintenanceEntry(createdAtMs, odometerKm, type, note) ?: -1L
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
