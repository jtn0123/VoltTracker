package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.AdapterHistoryRecord
import com.volttracker.obdpoc.data.DiagnosticCodeReport
import com.volttracker.obdpoc.data.ObdSessionRecord
import com.volttracker.obdpoc.data.StorageSummaryRecord
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object StorageSummaryJson {
    @JvmStatic
    fun build(record: StorageSummaryRecord?): JSONObject {
        val payload = JSONObject()
        if (record == null) {
            return payload
        }
        try {
            payload.put("database", record.database)
            payload.put("databaseBytes", record.databaseBytes)
            payload.put("sessionCount", record.sessionCount)
            payload.put("rawTelemetryCount", record.rawTelemetryCount)
            payload.put("sampleCount", record.sampleCount)
            payload.put("emptyTelemetryCount", record.emptyTelemetryCount)
            payload.put("eventCount", record.eventCount)
            payload.put("adapterCount", record.adapterCount)
            payload.put("pidObservationCount", record.pidObservationCount)
            payload.put("diagnosticCodeCount", record.diagnosticCodeCount)
            payload.put("diagnosticCodeStatusCounts", diagnosticCodeStatusCountsJson(record))
            payload.put("locationSampleCount", record.locationSampleCount)
            payload.put("vehicleCount", record.vehicleCount)
            payload.put("fieldCapabilityCount", record.fieldCapabilityCount)
            payload.put("tripSegmentCount", record.tripSegmentCount)
            payload.put("chargeSessionCount", record.chargeSessionCount)
            payload.put("batterySnapshotCount", record.batterySnapshotCount)
            payload.put("cellSnapshotCount", record.cellSnapshotCount)
            payload.put("exportCount", record.exportCount)
            putLatestSession(payload, record.latestSession)
            payload.put("recentSessions", recentSessionsJson(record))
            payload.put("adapters", adapterHistoryJson(record))
            payload.put("latestDiagnosticCodes", diagnosticCodesJson(record))
            payload.put("latestReview", copy(record.latestReview))
            payload.put("latestRoute", copy(record.latestRoute))
            payload.put("recentRoutes", copy(record.recentRoutes))
            payload.put("overview", copy(record.overview))
            payload.put("chargeSummary", copy(record.chargeSummary))
            payload.put("batterySummary", copy(record.batterySummary))
            payload.put("latestVehicle", copy(record.latestVehicle))
            payload.put("enhancedCapabilities", copy(record.enhancedCapabilities))
            payload.put("detailedSignalCatalog", EnhancedPidProfiles.catalogJson())
        } catch (_: JSONException) {
            // Static keys and already-normalized local values are safe.
        }
        return payload
    }

    private fun putLatestSession(
        payload: JSONObject,
        latest: ObdSessionRecord?,
    ) {
        if (latest == null) {
            return
        }
        payload.put("lastSessionId", latest.id)
        payload.put("lastMode", latest.mode)
        payload.put("lastStatus", latest.status)
        payload.put("lastStartedAtMs", latest.startedAtMs)
        payload.put("lastEventAtMs", latest.lastEventAtMs)
        payload.put("lastSampleCount", latest.sampleCount)
        payload.put("lastAdapter", latest.adapterName)
    }

    private fun diagnosticCodeStatusCountsJson(record: StorageSummaryRecord): JSONObject {
        val payload = JSONObject()
        for ((key, value) in record.diagnosticCodeStatusCounts) {
            payload.put(key, value)
        }
        return payload
    }

    private fun recentSessionsJson(record: StorageSummaryRecord): JSONArray {
        val payload = JSONArray()
        for (summary in record.recentSessions) {
            val session = summary.session
            val item = JSONObject()
            item.put("id", session.id)
            item.put("mode", session.mode)
            item.put("adapterAddress", session.adapterAddress)
            item.put("adapterName", session.adapterName)
            item.put("startedAtMs", session.startedAtMs)
            item.put("endedAtMs", session.endedAtMs)
            item.put("status", session.status)
            item.put("supportedPids", session.supportedPids)
            item.put("sampleCount", session.sampleCount)
            item.put("usefulSampleCount", summary.usefulSampleCount)
            item.put("emptySampleCount", summary.emptySampleCount)
            item.put("lastEventAtMs", session.lastEventAtMs)
            payload.put(item)
        }
        return payload
    }

    private fun adapterHistoryJson(record: StorageSummaryRecord): JSONArray {
        val payload = JSONArray()
        for (adapter: AdapterHistoryRecord in record.adapters) {
            val item = JSONObject()
            item.put("adapterKey", adapter.adapterKey)
            item.put("address", adapter.address)
            item.put("name", adapter.name)
            item.put("firstSeenMs", adapter.firstSeenMs)
            item.put("lastSeenMs", adapter.lastSeenMs)
            item.put("connectCount", adapter.connectCount)
            item.put("scanCount", adapter.scanCount)
            item.put("demoCount", adapter.demoCount)
            item.put("sampleCount", adapter.sampleCount)
            item.put("lastSessionId", adapter.lastSessionId)
            item.put("lastMode", adapter.lastMode)
            item.put("lastStatus", adapter.lastStatus)
            item.put("supportedPids", adapter.supportedPids)
            item.put("lastEventDetail", adapter.lastEventDetail)
            payload.put(item)
        }
        return payload
    }

    private fun diagnosticCodesJson(record: StorageSummaryRecord): JSONArray {
        val payload = JSONArray()
        for (code: DiagnosticCodeReport in record.latestDiagnosticCodes) {
            payload.put(code.toJson())
        }
        return payload
    }

    private fun copy(source: JSONObject?): JSONObject {
        if (source == null) {
            return JSONObject()
        }
        return try {
            JSONObject(source.toString())
        } catch (_: JSONException) {
            JSONObject()
        }
    }

    private fun copy(source: JSONArray?): JSONArray {
        if (source == null) {
            return JSONArray()
        }
        return try {
            JSONArray(source.toString())
        } catch (_: JSONException) {
            JSONArray()
        }
    }
}
