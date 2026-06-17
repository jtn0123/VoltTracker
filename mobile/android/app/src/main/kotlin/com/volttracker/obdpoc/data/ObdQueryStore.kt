package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.materialize.LocationSample
import com.volttracker.obdpoc.materialize.MaterializerData
import com.volttracker.obdpoc.materialize.PidObservation
import com.volttracker.obdpoc.materialize.TelemetrySample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Read-side of the on-device SQLite store. Callers that only need reads should depend on this
 * narrower interface rather than the full [ObdLocalStore] facade.
 */
interface ObdQueryStore : MaterializerData {
    fun getSession(sessionId: Long): ObdSessionRecord?

    fun getRecentSessions(limit: Int): List<ObdSessionRecord>

    fun getRecentTelemetry(
        sessionId: Long,
        limit: Int,
    ): List<TelemetrySampleRecord>

    fun getRecentEvents(
        sessionId: Long,
        limit: Int,
    ): List<StatusEventRecord>

    fun getAdapterHistory(limit: Int): List<AdapterHistoryRecord>

    fun getStorageSummaryRecord(): StorageSummaryRecord

    fun getStorageOverviewRecord(): StorageSummaryRecord

    fun getStorageDetailsJson(): JSONObject

    fun getRecentSessionsJson(limit: Int): JSONArray

    fun getAdapterHistoryJson(limit: Int): JSONArray

    fun getTripsJson(limit: Int): JSONArray

    fun getInsightsJson(): JSONObject

    override fun readLocationSamples(sessionId: Long): List<LocationSample>

    override fun readPidObservations(sessionId: Long): List<PidObservation>

    override fun readTelemetrySamples(sessionId: Long): List<TelemetrySample>

    fun getDatabaseFile(): File
}
