package com.volttracker.obdpoc.data

import org.json.JSONArray

/**
 * [ObdMaintenanceLogStore] implementation: writes ride [ObdStoreWriter], the list projection
 * rides [ObdStoreReports].
 */
internal class ObdStoreMaintenanceLog(
    private val writer: ObdStoreWriter,
    private val reports: ObdStoreReports,
) : ObdMaintenanceLogStore {
    override fun addMaintenanceEntry(
        createdAtMs: Long,
        odometerKm: Double?,
        type: String?,
        note: String?,
        intervalKm: Double?,
        intervalMonths: Int?,
    ): Long = writer.addMaintenanceEntry(createdAtMs, odometerKm, type, note, intervalKm, intervalMonths)

    override fun getMaintenanceLogJson(limit: Int): JSONArray = reports.maintenanceLogJson(limit)

    override fun deleteMaintenanceEntry(id: Long): Int = writer.deleteMaintenanceEntry(id)
}
