package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [ObdSignalLogStore] implementation: every member delegates to the matching
 * [ObdStoreReports] enhanced-capability query.
 */
internal class ObdStoreSignalLogs(
    private val reports: ObdStoreReports,
) : ObdSignalLogStore {
    override fun getEnhancedCapabilitiesJson(limit: Int): JSONArray = reports.enhancedCapabilitiesJson(limit)

    override fun hasRejectedEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
    ): Boolean = reports.hasRejectedEnhancedCapability(adapterAddress, header, command)

    override fun hasRecentEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
        minAgeMs: Long,
    ): Boolean = reports.hasRecentEnhancedCapability(adapterAddress, header, command, minAgeMs)

    override fun getEnhancedCapabilityExportJson(id: Long): JSONObject = reports.enhancedCapabilityJson(id)

    override fun getEnhancedCapabilitiesExportJson(limit: Int): JSONObject =
        reports.enhancedCapabilitiesExportJson(limit)

    override fun deleteEnhancedCapability(id: Long): Int = reports.deleteEnhancedCapability(id)
}
