package com.volttracker.obdpoc.data

import android.content.Context
import com.volttracker.obdpoc.materialize.ChargeSession
import com.volttracker.obdpoc.materialize.ChargeSessionMaterializer
import com.volttracker.obdpoc.materialize.LocationSample
import com.volttracker.obdpoc.materialize.MaterializerData
import com.volttracker.obdpoc.materialize.MaterializerInput
import com.volttracker.obdpoc.materialize.PidObservation
import com.volttracker.obdpoc.materialize.TelemetrySample
import com.volttracker.obdpoc.materialize.Trip
import com.volttracker.obdpoc.materialize.TripMaterializer
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * On-device SQLite store for OBD sessions, telemetry, GPS, events and adapter history.
 */
open class ObdLocalStore(
    context: Context,
) : Closeable,
    MaterializerData,
    ObdSessionStore,
    ObdQueryStore {
    private val helper: VoltTrackerDb
    private val trips: ObdStoreTrips
    private val reports: ObdStoreReports
    private val maintenance: ObdStoreMaintenance
    private val writer: ObdStoreWriter
    private val vehicles: ObdStoreVehicles
    private val materialize: ObdStoreMaterialize

    init {
        val appContext = context.applicationContext
        helper = VoltTrackerDb(appContext)
        trips = ObdStoreTrips(helper)
        reports = ObdStoreReports(helper, trips)
        maintenance = ObdStoreMaintenance(appContext, helper)
        writer = ObdStoreWriter(helper, ObdStoreSnapshots())
        vehicles = ObdStoreVehicles(helper)
        materialize = ObdStoreMaterialize(helper)
    }

    open override fun startSession(
        mode: String?,
        adapterAddress: String?,
        adapterName: String?,
    ): Long = writer.startSession(mode, adapterAddress, adapterName)

    open override fun startSession(
        mode: String?,
        adapterAddress: String?,
        adapterName: String?,
        startedAtMs: Long,
    ): Long = writer.startSession(mode, adapterAddress, adapterName, startedAtMs)

    open override fun finishSession(
        sessionId: Long,
        status: String?,
    ) {
        writer.finishSession(sessionId, status)
    }

    open override fun finishSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
    ) {
        writer.finishSession(sessionId, status, endedAtMs, supportedPids)
    }

    open override fun finalizeSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
        address: String?,
        adapterName: String?,
        mode: String?,
        sampleCount: Int,
        lastEventDetail: String?,
    ) {
        writer.finalizeSession(sessionId, status, endedAtMs, supportedPids, address, adapterName, mode, sampleCount, lastEventDetail)
    }

    open override fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
    ): Long = writer.recordTelemetry(sessionId, sample)

    open override fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long,
    ): Long = writer.recordTelemetry(sessionId, sample, capturedAtMs)

    open override fun recordPidObservation(
        sessionId: Long,
        observation: JSONObject?,
    ): Long = writer.recordPidObservation(sessionId, observation)

    open override fun recordPidObservation(
        sessionId: Long,
        observation: JSONObject?,
        observedAtMs: Long,
    ): Long = writer.recordPidObservation(sessionId, observation, observedAtMs)

    open override fun recordPidObservation(
        sessionId: Long,
        observedAtMs: Long,
        command: String?,
        header: String?,
        pid: String?,
        name: String?,
        valueText: String?,
        valueNumeric: Double?,
        unit: String?,
        rawRequest: String?,
        rawResponse: String?,
    ): Long =
        writer.recordPidObservation(
            sessionId,
            observedAtMs,
            command,
            header,
            pid,
            name,
            valueText,
            valueNumeric,
            unit,
            rawRequest,
            rawResponse,
        )

    open override fun recordDiagnosticCode(
        sessionId: Long,
        diagnosticCode: JSONObject?,
    ): Long = writer.recordDiagnosticCode(sessionId, diagnosticCode)

    open override fun recordLocationSample(
        sessionId: Long,
        sample: JSONObject?,
    ): Long = writer.recordLocationSample(sessionId, sample)

    open override fun recordLocationSample(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long,
    ): Long = writer.recordLocationSample(sessionId, sample, capturedAtMs)

    open override fun recordLocationSample(
        sessionId: Long,
        capturedAtMs: Long,
        provider: String?,
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        altitudeM: Double?,
        speedMps: Double?,
        bearingDeg: Double?,
        locationAgeMs: Long?,
        elapsedRealtimeNanos: Long?,
    ): Long =
        writer.recordLocationSample(
            sessionId,
            capturedAtMs,
            provider,
            latitude,
            longitude,
            accuracyM,
            altitudeM,
            speedMps,
            bearingDeg,
            locationAgeMs,
            elapsedRealtimeNanos,
        )

    open override fun recordStatus(
        sessionId: Long,
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ): Long = writer.recordStatus(sessionId, state, detail, blocked, payload)

    open override fun recordEvent(
        sessionId: Long,
        kind: String?,
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ): Long = writer.recordEvent(sessionId, kind, state, detail, blocked, payload)

    open override fun upsertVehicleFromVin(vin: String?): Long = vehicles.upsertVehicleFromVin(vin)

    open override fun recordAdapterSummary(
        address: String?,
        name: String?,
        mode: String?,
        sessionId: Long,
        status: String?,
        samples: Int,
        supportedPids: String?,
        lastEventDetail: String?,
    ) {
        writer.recordAdapterSummary(address, name, mode, sessionId, status, samples, supportedPids, lastEventDetail)
    }

    open override fun getSession(sessionId: Long): ObdSessionRecord? = reports.getSession(sessionId)

    open override fun getRecentSessions(limit: Int): List<ObdSessionRecord> = reports.getRecentSessions(limit)

    open override fun getRecentTelemetry(
        sessionId: Long,
        limit: Int,
    ): List<TelemetrySampleRecord> = reports.getRecentTelemetry(sessionId, limit)

    open override fun getRecentEvents(
        sessionId: Long,
        limit: Int,
    ): List<StatusEventRecord> = reports.getRecentEvents(sessionId, limit)

    open override fun getAdapterHistory(limit: Int): List<AdapterHistoryRecord> = reports.getAdapterHistory(limit)

    open override fun getStorageSummaryRecord(): StorageSummaryRecord = reports.storageSummaryRecord(getDatabaseFile())

    open override fun getRecentSessionsJson(limit: Int): JSONArray = reports.recentSessionsJson(limit)

    open override fun getAdapterHistoryJson(limit: Int): JSONArray = reports.adapterHistoryJson(limit)

    open fun getEnhancedCapabilitiesJson(limit: Int): JSONArray = reports.enhancedCapabilitiesJson(limit)

    open fun hasRejectedEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
    ): Boolean = reports.hasRejectedEnhancedCapability(adapterAddress, header, command)

    open fun hasRecentEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
        minAgeMs: Long,
    ): Boolean = reports.hasRecentEnhancedCapability(adapterAddress, header, command, minAgeMs)

    open fun getEnhancedCapabilityExportJson(id: Long): JSONObject = reports.enhancedCapabilityJson(id)

    open fun getEnhancedCapabilitiesExportJson(limit: Int): JSONObject = reports.enhancedCapabilitiesExportJson(limit)

    open fun deleteEnhancedCapability(id: Long): Int = reports.deleteEnhancedCapability(id)

    open override fun getTripsJson(limit: Int): JSONArray = trips.tripsJson(limit)

    open override fun getInsightsJson(): JSONObject = trips.insightsJson()

    open fun getTripRouteJson(sessionId: Long): JSONObject = reports.tripRouteJson(sessionId)

    open fun getTripRouteJson(routeKey: String?): JSONObject = reports.tripRouteJson(routeKey)

    open override fun readLocationSamples(sessionId: Long): List<LocationSample> = materialize.readLocationSamples(sessionId)

    open override fun readPidObservations(sessionId: Long): List<PidObservation> = materialize.readPidObservations(sessionId)

    open override fun readTelemetrySamples(sessionId: Long): List<TelemetrySample> = materialize.readTelemetrySamples(sessionId)

    open override fun persistTrips(
        sessionId: Long,
        trips: List<Trip>?,
    ) {
        materialize.persistTrips(sessionId, trips)
    }

    open override fun persistChargeSessions(
        sessionId: Long,
        sessions: List<ChargeSession>?,
    ) {
        materialize.persistChargeSessions(sessionId, sessions)
    }

    open fun materializeSession(
        sessionId: Long,
        startedAtMs: Long,
        closedAtMs: Long,
    ) {
        val input = MaterializerInput(sessionId, startedAtMs, closedAtMs)
        persistTrips(sessionId, TripMaterializer.materialize(input, this))
        persistChargeSessions(sessionId, ChargeSessionMaterializer.materialize(input, this))
    }

    open override fun clearAllData() {
        maintenance.clearAllData()
    }

    open override fun getDatabaseFile(): File = maintenance.getDatabaseFile()

    open override fun checkpoint() {
        maintenance.checkpoint()
    }

    open fun mergeFrom(donorDbFile: File?): DatabaseMerger.MergeResult = maintenance.mergeFrom(donorDbFile)

    open override fun pruneRawDataOlderThan(keepDays: Int): Int = maintenance.pruneRawDataOlderThan(keepDays)

    open override fun close() {
        writer.close()
        helper.close()
    }

    companion object {
        const val MODE_OBD: String = "obd"
        const val MODE_SCAN: String = "scan"
        const val MODE_DEMO: String = "demo"

        const val STATUS_ACTIVE: String = "active"
        const val STATUS_COMPLETE: String = "complete"
        const val STATUS_ERROR: String = "error"
        const val STATUS_DISCONNECTED: String = "disconnected"

        const val DEFAULT_RAW_RETENTION_DAYS: Int = ObdStoreMaintenance.DEFAULT_RAW_RETENTION_DAYS
    }
}
