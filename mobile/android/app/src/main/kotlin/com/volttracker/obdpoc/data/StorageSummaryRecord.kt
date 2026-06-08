package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class StorageSummaryRecord(
    database: String?,
    @JvmField val databaseBytes: Long,
    @JvmField val sessionCount: Long,
    @JvmField val rawTelemetryCount: Long,
    @JvmField val sampleCount: Long,
    @JvmField val emptyTelemetryCount: Long,
    @JvmField val eventCount: Long,
    @JvmField val adapterCount: Long,
    @JvmField val pidObservationCount: Long,
    @JvmField val diagnosticCodeCount: Long,
    diagnosticCodeStatusCounts: Map<String, Long>?,
    @JvmField val locationSampleCount: Long,
    @JvmField val vehicleCount: Long,
    @JvmField val fieldCapabilityCount: Long,
    @JvmField val tripSegmentCount: Long,
    @JvmField val chargeSessionCount: Long,
    @JvmField val batterySnapshotCount: Long,
    @JvmField val cellSnapshotCount: Long,
    @JvmField val exportCount: Long,
    @JvmField val latestSession: ObdSessionRecord?,
    recentSessions: List<RecentSessionSummaryRecord>?,
    adapters: List<AdapterHistoryRecord>?,
    latestDiagnosticCodes: List<DiagnosticCodeReport>?,
    latestReview: JSONObject?,
    latestRoute: JSONObject?,
    recentRoutes: JSONArray?,
    overview: JSONObject?,
    chargeSummary: JSONObject?,
    batterySummary: JSONObject?,
    latestVehicle: JSONObject?,
    enhancedCapabilities: JSONArray?,
) {
    @JvmField val database: String = database ?: ""

    @JvmField val diagnosticCodeStatusCounts: Map<String, Long> =
        if (diagnosticCodeStatusCounts == null) emptyMap() else java.util.Map.copyOf(diagnosticCodeStatusCounts)

    @JvmField val recentSessions: List<RecentSessionSummaryRecord> =
        if (recentSessions == null) emptyList() else java.util.List.copyOf(recentSessions)

    @JvmField val adapters: List<AdapterHistoryRecord> =
        if (adapters == null) emptyList() else java.util.List.copyOf(adapters)

    @JvmField val latestDiagnosticCodes: List<DiagnosticCodeReport> =
        if (latestDiagnosticCodes == null) emptyList() else java.util.List.copyOf(latestDiagnosticCodes)

    @JvmField val latestReview: JSONObject = latestReview.copy()

    @JvmField val latestRoute: JSONObject = latestRoute.copy()

    @JvmField val recentRoutes: JSONArray = recentRoutes.copy()

    @JvmField val overview: JSONObject = overview.copy()

    @JvmField val chargeSummary: JSONObject = chargeSummary.copy()

    @JvmField val batterySummary: JSONObject = batterySummary.copy()

    @JvmField val latestVehicle: JSONObject = latestVehicle.copy()

    @JvmField val enhancedCapabilities: JSONArray = enhancedCapabilities.copy()
}

private fun JSONObject?.copy(): JSONObject {
    if (this == null) {
        return JSONObject()
    }
    return try {
        JSONObject(toString())
    } catch (ignored: JSONException) {
        JSONObject()
    }
}

private fun JSONArray?.copy(): JSONArray {
    if (this == null) {
        return JSONArray()
    }
    return try {
        JSONArray(toString())
    } catch (ignored: JSONException) {
        JSONArray()
    }
}
