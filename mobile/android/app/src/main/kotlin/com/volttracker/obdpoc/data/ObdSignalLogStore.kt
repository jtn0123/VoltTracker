package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Detailed enhanced-capability signal logs: list/export/delete plus the probe-history checks the
 * discovery runners consult before re-probing an adapter. Extends the
 * [ObdSessionStore]/[ObdQueryStore] convention of narrow capability interfaces — callers reach
 * this via [ObdLocalStore.signalLogs] instead of flat methods on the facade.
 */
interface ObdSignalLogStore {
    fun getEnhancedCapabilitiesJson(limit: Int): JSONArray

    /** True when the newest probe of this adapter/header/command was rejected by the vehicle. */
    fun hasRejectedEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
    ): Boolean

    /** True when this adapter/header/command was probed within the last [minAgeMs]. */
    fun hasRecentEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
        minAgeMs: Long,
    ): Boolean

    /** One capability row with its full raw frames, for the detailed-signal-log export. */
    fun getEnhancedCapabilityExportJson(id: Long): JSONObject

    /** The newest [limit] capability rows bundled for the bulk detailed-signal-log export. */
    fun getEnhancedCapabilitiesExportJson(limit: Int): JSONObject

    /** Removes one capability row by id; returns the number of rows deleted (0 or 1). */
    fun deleteEnhancedCapability(id: Long): Int
}
