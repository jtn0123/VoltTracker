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

    fun shareBackup(dashboardPreferencesJson: String? = null) {
        val preferences = dashboardPreferencesJson?.take(MAX_BACKUP_PREFERENCES_CHARS)
        activity.runOnUiThread { stateProvider.requireBackupController().launchShare(preferences) }
    }

    fun shareEncryptedBackup(
        passphrase: String?,
        dashboardPreferencesJson: String? = null,
    ) {
        val cleanPassphrase = sanitizedPassphraseOrNull(passphrase) ?: return
        val preferences = dashboardPreferencesJson?.take(MAX_BACKUP_PREFERENCES_CHARS)
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedShare(cleanPassphrase, preferences)
        }
    }

    fun restoreBackup() {
        activity.runOnUiThread(stateProvider.requireBackupController()::launchRestorePicker)
    }

    fun restoreEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = sanitizedPassphraseOrNull(passphrase) ?: return
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedRestorePicker(cleanPassphrase)
        }
    }

    /**
     * Length-bounds the backup passphrase WITHOUT trimming: edge whitespace is key material, and
     * trimming it (the old behavior) silently derived a different PBKDF2 key than the user typed.
     * An all-whitespace passphrase is rejected with a clear error instead of silently collapsing
     * to empty; an empty/null one passes through so [BackupController]'s existing
     * "enter a passphrase" messaging still handles it. Backups encrypted before this change used
     * the TRIMMED form — decrypt compatibility lives in [BackupCrypto.decryptFileWithTrimFallback].
     */
    private fun sanitizedPassphraseOrNull(passphrase: String?): String? {
        val bounded = bridgeSafeNoTrim(passphrase, BRIDGE_MAX_PASSPHRASE_LEN)
        if (bounded.isNotEmpty() && bounded.isBlank()) {
            activity.runOnUiThread {
                activity.publishStatus(
                    "blocked",
                    "A backup passphrase cannot be only spaces. Include visible characters.",
                    true,
                )
            }
            return null
        }
        return bounded
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
                Log.w(AppPrefs.LOG_TAG, "clearStoredData failed", ex)
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
        val rowId = parseBridgePositiveId(id)
        // isOpen guard: same teardown-race contract as DashboardStorageReader — a store
        // closed by onDestroy must degrade to an error payload, not throw into the bridge.
        val store = activity.localStore?.takeIf { it.isOpen }
        if (rowId <= 0L || store == null) {
            return errorPayload("invalid_id", "Choose a saved detailed signal log.")
        }
        return BridgeJsonResult.obj(store.getEnhancedCapabilityExportJson(rowId)).serialize()
    }

    fun exportDetailedSignalLogs(): String {
        val store = activity.localStore?.takeIf { it.isOpen }
        if (store == null) {
            return errorPayload("storage_unavailable", "Local storage is not ready.")
        }
        return BridgeJsonResult.obj(store.getEnhancedCapabilitiesExportJson(250)).serialize()
    }

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

    fun deleteDetailedSignalLog(id: String?) {
        val rowId = parseBridgePositiveId(id)
        if (rowId <= 0L) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a saved detailed signal log.", true)
            }
            return
        }
        activity.runOnBackground {
            val deleted =
                try {
                    activity.localStore?.deleteEnhancedCapability(rowId) ?: 0
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "deleteDetailedSignalLog failed", ex)
                    0
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (deleted > 0) "ready" else "blocked",
                    if (deleted > 0) {
                        "Detailed signal log removed."
                    } else {
                        "Detailed signal log was already gone."
                    },
                    deleted <= 0,
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

    /** Reads a positive whole-number Int without optInt's silent fractional truncation. */
    private fun optPositiveWholeInt(
        parsed: JSONObject,
        key: String,
    ): Int? {
        if (!parsed.has(key) || parsed.isNull(key)) return null
        val numeric =
            when (val raw = parsed.opt(key)) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            } ?: return null
        return numeric
            .takeIf { it.isFinite() && it > 0.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }
            ?.toInt()
    }

    private fun errorPayload(
        error: String,
        message: String,
    ): String = BridgeJsonResult.error(error, message).serialize()

    fun markTripNotTrip(routeKey: String?) {
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
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

    fun restoreTrip(routeKey: String?) {
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishActionConfirmation("blocked", "Choose a hidden trip to restore.", true)
            }
            return
        }
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripHidden(cleanRouteKey, false) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "restoreTrip failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
                    if (changed) "ready" else "blocked",
                    if (changed) "Drive restored." else "That drive could not be restored.",
                    !changed,
                )
            }
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
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored trip to rename.", true)
            }
            return
        }
        // The label cap is owned by the data layer (ObdTripLabels re-truncates to the same length on
        // store), so reference that single constant rather than a second, larger bridge cap that the
        // storage would silently clip — keeping one cap for the trip-label path.
        val cleanLabel = bridgeSafe(label, ObdTripLabels.MAX_LABEL_LEN)
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripLabel(cleanRouteKey, cleanLabel) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "setTripLabel failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
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
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
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
                    Log.w(AppPrefs.LOG_TAG, "setTripFavorite failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
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
                JSONObject(bridgeSafe(json, BRIDGE_MAX_DETAIL_LEN))
            } catch (ex: org.json.JSONException) {
                Log.w(AppPrefs.LOG_TAG, "addMaintenanceEntry: bad JSON", ex)
                JSONObject()
            }
        val type = bridgeSafe(parsed.optString("type", ""), BRIDGE_MAX_LABEL_LEN)
        val note = bridgeSafe(parsed.optString("note", ""), BRIDGE_MAX_DETAIL_LEN)
        if (type.isEmpty() && note.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Add a maintenance type or note before saving.", true)
            }
            return
        }
        val odometerKm = optPositiveFiniteDouble(parsed, "odometerKm")
        // Interval > 0 only (a non-positive interval can never come "due"); non-finite is rejected.
        val intervalKm = optPositiveFiniteDouble(parsed, "intervalKm")?.takeIf { it > 0.0 }
        val intervalMonths = optPositiveWholeInt(parsed, "intervalMonths")
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
                    Log.w(AppPrefs.LOG_TAG, "addMaintenanceEntry failed", ex)
                    -1L
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
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
            return errorPayload("storage_unavailable", "Maintenance log is not available yet.")
        }
        return try {
            BridgeJsonResult.array(store.getMaintenanceLogJson(MAX_MAINTENANCE_ROWS)).serialize()
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "getMaintenanceLog failed", ex)
            errorPayload("maintenance_log_failed", "Could not read the maintenance log.")
        }
    }

    /** Deletes one maintenance-log entry by id (M5), then refreshes the dashboard. */
    fun deleteMaintenanceEntry(id: String?) {
        val rowId = parseBridgePositiveId(id)
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
                    Log.w(AppPrefs.LOG_TAG, "deleteMaintenanceEntry failed", ex)
                    0
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
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
                    activity.localStore?.setTripHidden(routeKey, true) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "markTripNotTrip failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                if (changed) {
                    activity.publishDashboardPayload(
                        "showTripUndo",
                        JSONObject().put("routeKey", routeKey).toString(),
                    )
                }
                activity.publishActionConfirmation(
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
        const val MAX_BACKUP_PREFERENCES_CHARS = 64 * 1024
    }
}
