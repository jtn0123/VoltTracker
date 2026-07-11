package com.volttracker.obdpoc

import android.util.Log
import org.json.JSONObject

/**
 * Maintenance-log bridge implementation (M5): add-entry form parsing/validation, the newest-first
 * log read, and per-entry deletion. [VoltBridge] owns the `@JavascriptInterface` wrappers; this
 * class is plumbing only.
 */
internal class VoltBridgeMaintenance(
    private val activity: DashboardHost,
) {
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
            return BridgeJsonResult.error("storage_unavailable", "Maintenance log is not available yet.").serialize()
        }
        return try {
            BridgeJsonResult.array(store.getMaintenanceLogJson(MAX_MAINTENANCE_ROWS)).serialize()
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "getMaintenanceLog failed", ex)
            BridgeJsonResult.error("maintenance_log_failed", "Could not read the maintenance log.").serialize()
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

    private companion object {
        /** Cap on maintenance rows returned to the dashboard in one read. */
        const val MAX_MAINTENANCE_ROWS = 200
    }
}
