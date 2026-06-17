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

    @Volatile
    private var closed = false

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

    open fun getRecentRoutesJson(
        limit: Int,
        pointLimit: Int,
    ): JSONArray = reports.recentRoutesProjectionJson(limit, pointLimit)

    /**
     * The individual read-side JSON projections, grouped behind one accessor so they live as members
     * of [StoreProjections] rather than as flat functions on this class — the same "group behind one
     * accessor" pattern as `MainActivity.eventNotifications()` (see `app/detekt.yml` TooManyFunctions
     * note), which keeps this class under the detekt ratchet. [StoreProjections.latestVehicle] is the
     * lightweight single-query VIN/vehicle path the OBD connect handshake reads.
     */
    open fun projections(): StoreProjections = StoreProjections(reports, getDatabaseFile())

    /** Read-side projections grouped off [ObdLocalStore]'s class surface; each delegates to [reports]. */
    class StoreProjections internal constructor(
        private val reports: ObdStoreReports,
        private val databaseFile: File,
    ) {
        fun storageCounts(): JSONObject = reports.storageCountsJson(databaseFile)

        fun diagnosticsSummary(limit: Int): JSONObject = reports.diagnosticsSummaryJson(limit)

        fun chargeSummary(): JSONObject = reports.chargeSummaryProjectionJson()

        fun batterySummary(): JSONObject = reports.batterySummaryProjectionJson()

        fun latestVehicle(): JSONObject = reports.latestVehicleRecord()
    }

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

    /**
     * Trips bundled for the bulk all-trips CSV export (M6): each element `{ tripId, label, route }`.
     * Bounded by [tripLimit] trips and [pointLimit] samples/trip. See
     * [ObdStoreReports.allTripsForExportJson].
     */
    open fun getAllTripsForExportJson(
        tripLimit: Int,
        pointLimit: Int,
    ): JSONArray = reports.allTripsForExportJson(tripLimit, pointLimit)

    /**
     * Records a completed per-trip GPX/CSV export into the `exports` table (one row per share).
     * Best-effort: returns the new row id, or -1 if the insert failed (recording must never break
     * the share). See [ObdStoreWriter.recordExport].
     */
    open fun recordExport(
        routeKey: String?,
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
    ): Long {
        val parsed = DriveWindowDetector.parseRouteKey(routeKey)
        return writer.recordExport(
            parsed?.sessionId ?: -1L,
            exportType,
            fileName,
            mimeType,
            bytes,
            parsed?.startedAtMs ?: -1L,
            parsed?.endedAtMs ?: -1L,
        )
    }

    /**
     * Records a completed bulk all-trips CSV export (M6) into the `exports` table. The export spans
     * every trip rather than one session, so it has no single session id / time range. Best-effort:
     * returns the new row id, or -1 if the insert failed (recording must never break the share).
     */
    open fun recordAllTripsExport(
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
    ): Long = writer.recordExport(-1L, exportType, fileName, mimeType, bytes, -1L, -1L)

    /**
     * Route projection for the in-progress (status = [STATUS_ACTIVE]) session, or an empty object
     * when nothing is recording. Lets the dashboard rehydrate the live track after the WebView is
     * torn down and recreated mid-drive (the foreground service keeps writing location samples to
     * the active session's row the whole time). Reuses the same projection as completed trips.
     */
    open fun getCurrentSessionRouteJson(): JSONObject {
        // Direct lookup of the newest in-progress (STATUS_ACTIVE) session, independent of how many
        // newer started rows exist: a fixed-size recent scan could miss the live drive after a
        // finalize race or rapid scan/demo churn pushed enough completed rows ahead of it; this
        // status-filtered query always finds it. Inlined (not a helper) to keep the class function
        // count under the detekt TooManyFunctions ratchet.
        var active: ObdSessionRecord? = null
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                "status = ?",
                arrayOf(STATUS_ACTIVE),
                null,
                null,
                "started_at_ms DESC",
                "1",
            ).use { cursor ->
                if (cursor.moveToNext()) {
                    active = ObdStoreSupport.readSession(cursor)
                }
            }
        return active?.let { reports.tripRouteJson(it.id) } ?: JSONObject()
    }

    /** Battery-health snapshots (oldest-first) for the dashboard's pack-health trend chart. */
    open fun getBatterySohHistoryJson(): JSONArray = reports.batterySohHistoryJson(1000)

    /**
     * Sets (or clears, when [label] is blank) the user label for the trip identified by [routeKey].
     * Persists the label as a route-key-keyed status event so it survives trip re-materialization,
     * and best-effort stamps the matching materialized `trip_segments` row's `label` column. Returns
     * false when the route key is unparseable.
     */
    open fun setTripLabel(
        routeKey: String?,
        label: String?,
    ): Boolean {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return false
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return false
        // canonicalRouteKey guarantees a 3-part key, so both timestamps are present here.
        val startedAtMs = parsed.startedAtMs ?: return false
        val endedAtMs = parsed.endedAtMs ?: return false
        val cleanLabel = ObdTripLabels.cleanLabel(label)
        writer.recordEvent(
            parsed.sessionId,
            ObdTripLabels.EVENT_KIND,
            if (cleanLabel.isEmpty()) "cleared" else "labeled",
            canonical,
            false,
            ObdTripLabels.eventPayload(canonical, cleanLabel),
        )
        writer.setTripSegmentLabel(parsed.sessionId, startedAtMs, endedAtMs, cleanLabel)
        return true
    }

    /**
     * Sets or clears the user "favorite" flag for the trip identified by [routeKey] (M4). Persists
     * the flag as a route-key-keyed status event so it survives trip re-materialization (no schema
     * change — mirrors [setTripLabel]). The latest event for a route key wins, so un-favoriting
     * writes a `favorite=false` event that supersedes an earlier favorite. Returns false when the
     * route key is unparseable.
     */
    open fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ): Boolean {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return false
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return false
        writer.recordEvent(
            parsed.sessionId,
            ObdTripFavorites.EVENT_KIND,
            if (favorite) "favorited" else "unfavorited",
            canonical,
            false,
            ObdTripFavorites.eventPayload(canonical, favorite),
        )
        return true
    }

    open fun markTripNotTrip(routeKey: String?): Boolean {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return false
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return false
        writer.recordEvent(
            parsed.sessionId,
            ObdTripExclusions.EVENT_KIND,
            "hidden",
            canonical,
            false,
            ObdTripExclusions.eventPayload(canonical, ObdTripExclusions.REASON_NOT_TRIP),
        )
        trips.invalidateSessionTripCache(parsed.sessionId)
        return true
    }

    /**
     * Inserts a user-authored maintenance-log entry (M5). [odometerKm] may be null. [createdAtMs]
     * dates the entry (defaults to now). [intervalKm] / [intervalMonths] are the optional service
     * interval (M1/C4) for the dashboard's "next due" line; null leaves a plain history entry.
     * Returns the new row id, or -1 on failure.
     */
    open fun addMaintenanceEntry(
        createdAtMs: Long,
        odometerKm: Double?,
        type: String?,
        note: String?,
        intervalKm: Double? = null,
        intervalMonths: Int? = null,
    ): Long = writer.addMaintenanceEntry(createdAtMs, odometerKm, type, note, intervalKm, intervalMonths)

    /** Newest-first user maintenance log (M5). See [ObdStoreReports.maintenanceLogJson]. */
    open fun getMaintenanceLogJson(limit: Int): JSONArray = reports.maintenanceLogJson(limit)

    /** Removes one maintenance-log entry by id; returns the number of rows deleted (0 or 1). */
    open fun deleteMaintenanceEntry(id: Long): Int = writer.deleteMaintenanceEntry(id)

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

    open override fun checkpoint() {
        maintenance.checkpoint()
    }

    open fun mergeFrom(
        donorDbFile: File?,
        progressListener: DatabaseMerger.ProgressListener? = null,
    ): DatabaseMerger.MergeResult = maintenance.mergeFrom(donorDbFile, progressListener)

    open override fun pruneRawDataOlderThan(keepDays: Int): Int = maintenance.pruneRawDataOlderThan(keepDays)

    /** Runs `PRAGMA quick_check`; never throws. See [ObdStoreMaintenance.quickCheck]. */
    open fun quickCheck(): ObdStoreMaintenance.IntegrityResult = maintenance.quickCheck()

    /**
     * Startup maintenance: prunes raw rows older than [keepDays], then VACUUMs when enough
     * freed pages have accumulated. Returns the pruned row count.
     */
    open fun runStartupMaintenance(keepDays: Int): Int = maintenance.runStartupMaintenance(keepDays)

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

        const val DEFAULT_RAW_RETENTION_DAYS: Int = ObdStoreMaintenance.DEFAULT_RAW_RETENTION_DAYS
    }
}
