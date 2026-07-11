package com.volttracker.obdpoc.data

import android.content.Context
import android.util.Log
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
 *
 * This facade owns the store lifecycle ([isOpen]/[close]) plus the session write/read surface
 * ([ObdSessionStore]/[ObdQueryStore]) the recording pipeline depends on, and hands everything
 * else out through narrow capability interfaces. Query-family map:
 *
 * | Query family                                      | Capability                             |
 * |---------------------------------------------------|----------------------------------------|
 * | Session/telemetry/GPS/event writes                | [ObdSessionStore] (this class)         |
 * | Session/telemetry/storage reads                   | [ObdQueryStore] (this class)           |
 * | Trip edits (label / favorite / hide)              | [tripEdits] -> [ObdTripEditStore]      |
 * | Route + live-track + SOH-history projections      | [routes] -> [ObdRouteQueryStore]       |
 * | Read-side dashboard JSON projections              | [projections] -> [StoreProjections]    |
 * | Enhanced-capability signal logs                   | [signalLogs] -> [ObdSignalLogStore]    |
 * | Maintenance log (M5)                              | [maintenanceLog] -> [ObdMaintenanceLogStore] |
 * | Export bookkeeping (`exports` table)              | [exportLog] -> [ObdExportLogStore]     |
 * | Whole-DB maintenance (merge / integrity / vacuum) | [dbMaintenance] -> [ObdDbMaintenanceStore] |
 */
open class ObdLocalStore(
    context: Context,
) : Closeable,
    MaterializerData,
    ObdSessionStore,
    ObdQueryStore {
    private val helper = VoltTrackerDb(context.applicationContext)
    private val trips = ObdStoreTrips(helper)
    private val reports = ObdStoreReports(helper, trips)
    private val maintenance = ObdStoreMaintenance(context.applicationContext, helper)
    private val writer = ObdStoreWriter(helper, ObdStoreSnapshots())
    private val vehicles = ObdStoreVehicles(helper, VinKeyHasher(context.applicationContext))
    private val materialize = ObdStoreMaterialize(helper)

    /** Trip-edit writes (labels, favorites, hide/restore) from the dashboard bridge. */
    open val tripEdits: ObdTripEditStore = ObdStoreTripEdits(writer, trips)

    /** Route/track projections for the dashboard's map views and the pack-health trend. */
    open val routes: ObdRouteQueryStore = ObdStoreRoutes(helper, reports)

    /** Detailed enhanced-capability signal logs (list/export/delete + probe-history checks). */
    open val signalLogs: ObdSignalLogStore = ObdStoreSignalLogs(reports)

    /** The user-authored maintenance log (M5): add, list, delete. */
    open val maintenanceLog: ObdMaintenanceLogStore = ObdStoreMaintenanceLog(writer, reports)

    /** Best-effort bookkeeping of completed share-sheet exports. */
    open val exportLog: ObdExportLogStore = ObdStoreExportLog(writer)

    /** Whole-database maintenance: donor merges, integrity checks, startup prune+vacuum. */
    open val dbMaintenance: ObdDbMaintenanceStore = maintenance

    @Volatile
    private var closed = false

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
        materializeFinalizedHistory(sessionId)
    }

    open override fun finishSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
    ) {
        writer.finishSession(sessionId, status, endedAtMs, supportedPids)
        materializeFinalizedHistory(sessionId)
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
        writer.finalizeSession(
            sessionId,
            status,
            endedAtMs,
            supportedPids,
            address,
            adapterName,
            mode,
            sampleCount,
            lastEventDetail,
        )
        materializeFinalizedHistory(sessionId)
    }

    private fun materializeFinalizedHistory(sessionId: Long) {
        try {
            trips.materializeFinalizedSession(sessionId)
            ObdStoreChargeSummary.materializeFinalizedSession(helper.writableDatabase, sessionId)
        } catch (ex: RuntimeException) {
            // Session finalization itself already committed. A missed projection is recoverable via
            // bounded lazy backfill, so never turn a completed drive into a logging failure.
            Log.w("VoltTracker", "deferred history materialization for session $sessionId", ex)
        }
    }

    open override fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long?,
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

    open fun recordPidObservations(
        sessionId: Long,
        observations: List<JSONObject>,
    ): Int = writer.recordPidObservations(sessionId, observations)

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

    open override fun getStorageOverviewRecord(): StorageSummaryRecord =
        reports.storageOverviewRecord(getDatabaseFile())

    open override fun getStorageDetailsJson(): JSONObject = reports.storageDetailsJson()

    /**
     * The individual read-side JSON projections, grouped behind one accessor so they live as members
     * of [StoreProjections] rather than as flat functions on this class — the same "group behind one
     * accessor" pattern as the [tripEdits]/[routes]/[signalLogs] capability accessors above.
     * [StoreProjections.latestVehicle] is the lightweight single-query VIN/vehicle path the OBD
     * connect handshake reads.
     */
    open fun projections(): StoreProjections = StoreProjections(reports, getDatabaseFile())

    /**
     * Read-side projections grouped off [ObdLocalStore]'s class surface; each delegates to [reports].
     * Open with an open [chargeSessionsForExport] + a no-arg [protected] constructor so a test fake
     * store can serve canned charge rows without standing up a real database (the all-trips export
     * fakes a flat [ObdLocalStore] method instead).
     */
    open class StoreProjections {
        private val reports: ObdStoreReports?
        private val databaseFile: File?

        internal constructor(
            reports: ObdStoreReports,
            databaseFile: File,
        ) {
            this.reports = reports
            this.databaseFile = databaseFile
        }

        /** No-arg seam for test subclasses that override the projections they exercise. */
        protected constructor() {
            this.reports = null
            this.databaseFile = null
        }

        private fun requireReports(): ObdStoreReports =
            reports ?: throw UnsupportedOperationException("StoreProjections built without a store")

        fun storageCounts(): JSONObject = requireReports().storageCountsJson(databaseFile!!)

        fun diagnosticsSummary(limit: Int): JSONObject = requireReports().diagnosticsSummaryJson(limit)

        fun chargeSummary(): JSONObject = requireReports().chargeSummaryProjectionJson()

        fun batterySummary(): JSONObject = requireReports().batterySummaryProjectionJson()

        fun latestVehicle(): JSONObject = requireReports().latestVehicleRecord()

        /**
         * The most recent odometer reading in km, or null when none was ever captured. Backs the
         * maintenance-overdue alert's distance math (M2). Open so a test fake can serve a canned
         * odometer without a real database.
         */
        open fun latestOdometerKm(): Double? = requireReports().latestOdometerKm()

        /**
         * Charge-session rows bundled for the CSV export (M1): the newest [limit] charges as a JSON
         * array, each `{ id, startedAtMs, endedAtMs, chargerType, startSoc, endSoc, powerKw,
         * energyKwh, confidence }`. `TripTrackFormatter.toChargeSessionsCsv` turns it into one CSV.
         */
        open fun chargeSessionsForExport(limit: Int): JSONArray = requireReports().chargeSessionsForExportJson(limit)

        open fun tripsPage(
            limit: Int,
            offset: Int,
        ): JSONArray = requireReports().tripsPageJson(limit, offset)

        open fun allTripsForExport(
            tripLimit: Int,
            pointLimit: Int,
        ): JSONArray = requireReports().allTripsForExportJson(tripLimit, pointLimit)

        open fun allTripsForExportPage(
            tripLimit: Int,
            pointLimit: Int,
            offset: Int,
        ): JSONObject = requireReports().allTripsForExportPageJson(tripLimit, pointLimit, offset)
    }

    open override fun getRecentSessionsJson(limit: Int): JSONArray = reports.recentSessionsJson(limit)

    open override fun getAdapterHistoryJson(limit: Int): JSONArray = reports.adapterHistoryJson(limit)

    open override fun getTripsJson(limit: Int): JSONArray = trips.tripsJson(limit)

    open override fun getInsightsJson(): JSONObject = trips.insightsJson()

    /**
     * Persists one full-pack cell-voltage probe result (surfaced to the dashboard via the
     * `batterySummary.latestCellSnapshot` storage-details block); returns the parent snapshot
     * id or -1.
     */
    open fun recordCellSnapshot(voltages: List<Double?>): Long = writer.recordCellSnapshot(voltages)

    open override fun readLocationSamples(sessionId: Long): List<LocationSample> =
        materialize.readLocationSamples(sessionId)

    open override fun readPidObservations(sessionId: Long): List<PidObservation> =
        materialize.readPidObservations(sessionId)

    open override fun readTelemetrySamples(sessionId: Long): List<TelemetrySample> =
        materialize.readTelemetrySamples(sessionId)

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

    open override fun checkpoint(): Boolean = maintenance.checkpoint()

    open override fun pruneRawDataOlderThan(keepDays: Int): Int = maintenance.pruneRawDataOlderThan(keepDays)

    /**
     * False once [close] has been called. Synchronous readers on other threads (the WebView
     * JS-bridge thread) check this before touching SQLite so a teardown race degrades to a
     * "storage unavailable" payload instead of throwing into the bridge.
     */
    open val isOpen: Boolean
        get() = !closed

    open override fun close() {
        closed = true
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
        const val STATUS_INTERRUPTED: String = "interrupted"

        const val DEFAULT_RAW_RETENTION_DAYS: Int = ObdStoreMaintenance.DEFAULT_RAW_RETENTION_DAYS
    }
}
