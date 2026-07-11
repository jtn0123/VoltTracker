package com.volttracker.obdpoc.data

import org.json.JSONArray

/**
 * User-authored maintenance-log entries (M5): add, list, delete. Extends the
 * [ObdSessionStore]/[ObdQueryStore] convention of narrow capability interfaces — bridge-side
 * callers reach this via [ObdLocalStore.maintenanceLog] instead of flat methods on the facade.
 */
interface ObdMaintenanceLogStore {
    /**
     * Inserts a user-authored maintenance-log entry (M5). [odometerKm] may be null. [createdAtMs]
     * dates the entry. [intervalKm] / [intervalMonths] are the optional service interval (M1/C4)
     * for the dashboard's "next due" line; null leaves a plain history entry. Returns the new row
     * id, or -1 on failure.
     */
    fun addMaintenanceEntry(
        createdAtMs: Long,
        odometerKm: Double?,
        type: String?,
        note: String?,
        intervalKm: Double? = null,
        intervalMonths: Int? = null,
    ): Long

    /** Newest-first user maintenance log (M5). See [ObdStoreReports.maintenanceLogJson]. */
    fun getMaintenanceLogJson(limit: Int): JSONArray

    /** Removes one maintenance-log entry by id; returns the number of rows deleted (0 or 1). */
    fun deleteMaintenanceEntry(id: Long): Int
}
