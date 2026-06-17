package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.EnhancedPidProfiles
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Maps a nullable REAL battery_snapshots column onto [jsonKey], boxed to null when absent. Shared
 *  by the latest-snapshot and SOH-history projections so a new column is wired in one place. */
private fun JSONObject.putBatteryDouble(
    cursor: Cursor,
    jsonKey: String,
    column: String,
): JSONObject = put(jsonKey, ObdStoreReportJson.boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, column)))

/** Read-side projections for records and dashboard JSON consumed by [ObdLocalStore]. */
class ObdStoreReports(
    private val helper: VoltTrackerDb,
    private val trips: ObdStoreTrips,
) {
    fun getSession(sessionId: Long): ObdSessionRecord? =
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                "_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) ObdStoreSupport.readSession(cursor) else null
            }

    fun tripRouteJson(sessionId: Long): JSONObject {
        val session = getSession(sessionId) ?: return JSONObject()
        return try {
            ObdStoreRouteProjection.routeForSession(
                helper.readableDatabase,
                session,
                ObdStoreRouteProjection.MAX_TRACK_POINTS,
            )
        } catch (ex: JSONException) {
            JSONObject()
        }
    }

    fun tripRouteJson(routeKey: String?): JSONObject {
        val parsed = DriveWindowDetector.parseRouteKey(routeKey) ?: return JSONObject()
        val session = getSession(parsed.sessionId) ?: return JSONObject()
        val db = helper.readableDatabase
        if (ObdTripExclusions.isHidden(db, routeKey)) {
            return JSONObject()
        }
        return try {
            ObdStoreRouteProjection.routeForSession(
                db,
                session,
                ObdStoreRouteProjection.MAX_TRACK_POINTS,
                parsed.startedAtMs,
                parsed.endedAtMs,
                routeKey,
            )
        } catch (ex: JSONException) {
            JSONObject()
        }
    }

    fun getRecentSessions(limit: Int): List<ObdSessionRecord> {
        val records = ArrayList<ObdSessionRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                null,
                null,
                null,
                null,
                "started_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readSession(cursor))
                }
            }
        return records
    }

    fun getRecentTelemetry(
        sessionId: Long,
        limit: Int,
    ): List<TelemetrySampleRecord> {
        val records = ArrayList<TelemetrySampleRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                null,
                "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readTelemetry(cursor))
                }
            }
        return records
    }

    fun getRecentEvents(
        sessionId: Long,
        limit: Int,
    ): List<StatusEventRecord> {
        val records = ArrayList<StatusEventRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_EVENTS,
                null,
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "occurred_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readStatusEvent(cursor))
                }
            }
        return records
    }

    fun getAdapterHistory(limit: Int): List<AdapterHistoryRecord> {
        val records = ArrayList<AdapterHistoryRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readAdapterHistory(cursor))
                }
            }
        return records
    }

    fun enhancedCapabilitiesJson(limit: Int): JSONArray {
        val items = JSONArray()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                null,
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        items.put(ObdStoreReportJson.capabilityFromCursor(cursor))
                    } catch (ignored: JSONException) {
                        // Skip malformed rows instead of breaking the debug payload.
                    }
                }
            }
        return items
    }

    fun enhancedCapabilityJson(id: Long): JSONObject {
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                null,
                "_id = ?",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return ObdStoreReportJson.notFound("detailed signal log not found")
                }
                return try {
                    JSONObject()
                        .put("ok", true)
                        .put("kind", "detailed-signal-log")
                        .put("item", ObdStoreReportJson.capabilityFromCursor(cursor))
                } catch (ex: JSONException) {
                    ObdStoreReportJson.exportError("detailed signal log could not be exported")
                }
            }
    }

    fun enhancedCapabilitiesExportJson(limit: Int): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("ok", true)
            payload.put("kind", "detailed-signal-logs")
            payload.put("exportedAtMs", System.currentTimeMillis())
            payload.put("items", enhancedCapabilitiesJson(limit))
        } catch (ignored: JSONException) {
            // Local keys are safe.
        }
        return payload
    }

    fun hasRecentEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
        minAgeMs: Long,
    ): Boolean {
        val adapterKey = ObdStoreSupport.adapterKey(adapterAddress, ObdLocalStore.MODE_OBD)
        val cutoff = System.currentTimeMillis() - maxOf(0L, minAgeMs)
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                arrayOf("last_seen_ms"),
                "adapter_key = ? AND header = ? AND command = ? AND last_seen_ms >= ?",
                arrayOf(adapterKey, ObdStoreSupport.clean(header), ObdStoreSupport.clean(command), cutoff.toString()),
                null,
                null,
                "last_seen_ms DESC",
                "1",
            ).use { cursor ->
                return cursor.moveToFirst()
            }
    }

    fun deleteEnhancedCapability(id: Long): Int =
        helper.writableDatabase.delete(
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
            "_id = ?",
            arrayOf(id.toString()),
        )

    fun hasRejectedEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
    ): Boolean {
        val adapterKey = ObdStoreSupport.adapterKey(adapterAddress, ObdLocalStore.MODE_OBD)
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                arrayOf("supported", "response_count"),
                "adapter_key = ? AND header = ? AND command = ?",
                arrayOf(adapterKey, ObdStoreSupport.clean(header), ObdStoreSupport.clean(command)),
                null,
                null,
                "last_seen_ms DESC",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return false
                }
                val supported = cursor.getInt(cursor.getColumnIndexOrThrow("supported")) == 1
                val responseCount = cursor.getLong(cursor.getColumnIndexOrThrow("response_count"))
                return !supported && responseCount <= 0L
            }
    }

    fun storageSummaryRecord(databaseFile: File): StorageSummaryRecord {
        val db = helper.readableDatabase
        val counts = storageCountsProjection(db, databaseFile)
        val reviewSession = ObdStoreSessionReview.latestReviewableSession(db)
        return StorageSummaryRecord(
            counts.database,
            counts.databaseBytes,
            counts.sessionCount,
            counts.rawTelemetryCount,
            counts.sampleCount,
            counts.emptyTelemetryCount,
            counts.eventCount,
            counts.adapterCount,
            counts.pidObservationCount,
            counts.diagnosticCodeCount,
            counts.diagnosticCodeStatusCounts,
            counts.locationSampleCount,
            counts.vehicleCount,
            counts.fieldCapabilityCount,
            counts.tripSegmentCount,
            counts.chargeSessionCount,
            counts.batterySnapshotCount,
            counts.cellSnapshotCount,
            counts.exportCount,
            counts.latestSession,
            recentSessionSummaries(db, 6),
            getAdapterHistory(6),
            latestDiagnosticCodeReports(db, 12),
            if (reviewSession ==
                null
            ) {
                JSONObject()
            } else {
                safeJson { ObdStoreSessionReview.sessionReview(db, reviewSession) }
            },
            if (reviewSession == null) {
                JSONObject()
            } else {
                safeJson { ObdStoreRouteProjection.routeForSession(db, reviewSession, 240) }
            },
            recentRoutesProjectionJson(8, 500),
            overviewProjectionJson(),
            chargeSummaryProjectionJson(),
            batterySummaryProjectionJson(),
            safeJson { latestVehicleJson(db) },
            safeArray { enhancedCapabilitiesJson(24) },
        )
    }

    fun storageOverviewRecord(databaseFile: File): StorageSummaryRecord {
        val db = helper.readableDatabase
        val counts = storageCountsProjection(db, databaseFile)
        return StorageSummaryRecord(
            counts.database,
            counts.databaseBytes,
            counts.sessionCount,
            counts.rawTelemetryCount,
            counts.sampleCount,
            counts.emptyTelemetryCount,
            counts.eventCount,
            counts.adapterCount,
            counts.pidObservationCount,
            counts.diagnosticCodeCount,
            counts.diagnosticCodeStatusCounts,
            counts.locationSampleCount,
            counts.vehicleCount,
            counts.fieldCapabilityCount,
            counts.tripSegmentCount,
            counts.chargeSessionCount,
            counts.batterySnapshotCount,
            counts.cellSnapshotCount,
            counts.exportCount,
            counts.latestSession,
            recentSessionSummaries(db, 6),
            getAdapterHistory(6),
            latestDiagnosticCodeReports(db, 12),
            null,
            null,
            null,
            null,
            null,
            null,
            safeJson { latestVehicleJson(db) },
            null,
        )
    }

    fun storageDetailsJson(): JSONObject =
        safeJson {
            val db = helper.readableDatabase
            val reviewSession = ObdStoreSessionReview.latestReviewableSession(db)
            JSONObject()
                .put("storageDetails", true)
                .put(
                    "latestReview",
                    if (reviewSession == null) {
                        JSONObject()
                    } else {
                        safeJson { ObdStoreSessionReview.sessionReview(db, reviewSession) }
                    },
                ).put(
                    "latestRoute",
                    if (reviewSession == null) {
                        JSONObject()
                    } else {
                        safeJson { ObdStoreRouteProjection.routeForSession(db, reviewSession, 240) }
                    },
                ).put("recentRoutes", recentRoutesProjectionJson(8, 500))
                .put("overview", overviewProjectionJson())
                .put("chargeSummary", chargeSummaryProjectionJson())
                .put("batterySummary", batterySummaryProjectionJson())
                .put("latestVehicle", safeJson { latestVehicleJson(db) })
                .put("enhancedCapabilities", safeArray { enhancedCapabilitiesJson(24) })
                .put("detailedSignalCatalog", EnhancedPidProfiles.catalogJson())
        }

    fun storageCountsJson(databaseFile: File): JSONObject =
        storageCountsProjection(helper.readableDatabase, databaseFile).toJson()

    /**
     * Lightweight latest-vehicle projection: a single `last_seen_ms DESC LIMIT 1` query producing
     * the same `{name, make, model, vin, year, vehicleId, …}` JSON the storage-summary record carries
     * in its `latestVehicle` slot, without the ~20 surrounding projections (whole-history charge scan
     * included). Used on the latency-critical OBD connect handshake to read the stored redacted VIN.
     */
    fun latestVehicleRecord(): JSONObject = latestVehicleJson(helper.readableDatabase)

    /**
     * The most recent odometer reading in km, or null when the car has never answered the odometer
     * PID. Mirrors the dashboard's `latestOdometerKm` primary source (the newest battery snapshot's
     * `odometer_km`); the maintenance-overdue alert (M2) drives its distance math from this.
     */
    fun latestOdometerKm(): Double? {
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                arrayOf("odometer_km"),
                "odometer_km IS NOT NULL AND odometer_km > 0",
                null,
                null,
                null,
                "captured_at_ms DESC",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                return cursor.getDouble(0)
            }
    }

    fun recentRoutesProjectionJson(
        limit: Int,
        pointLimit: Int,
    ): JSONArray = safeArray { ObdStoreRouteProjection.recentRoutes(helper.readableDatabase, limit, pointLimit) }

    /**
     * Trips bundled for the bulk all-trips CSV export (M6). Reuses [ObdStoreTrips.tripsJson] for the
     * trip list (so it shares drive-window detection, hiding, and labels) and the per-trip route
     * projection for the GPS samples. Each element is `{ tripId, label, route }`, where `route` is
     * the same `{ points, … }` payload a single-trip export uses. Bounded by [tripLimit] trips and
     * [pointLimit] samples/trip so a huge history can't blow up memory or the share file.
     *
     * `TripTrackFormatter.toAllTripsCsv` turns this into one CSV with a leading trip-id/label column.
     */
    fun allTripsForExportJson(
        tripLimit: Int,
        pointLimit: Int,
    ): JSONArray =
        safeArray {
            val out = JSONArray()
            val trips = trips.tripsJson(tripLimit)
            for (i in 0 until trips.length()) {
                val trip = trips.optJSONObject(i) ?: continue
                val routeKey = trip.optString("id", "")
                if (routeKey.isEmpty()) {
                    continue
                }
                val route = boundedTripRouteJson(routeKey, pointLimit)
                val points = route.optJSONArray("points")
                if (points == null || points.length() == 0) {
                    continue
                }
                out.put(
                    JSONObject()
                        .put("tripId", routeKey)
                        .put("label", trip.optString("label", ""))
                        .put("route", route),
                )
            }
            out
        }

    private fun boundedTripRouteJson(
        routeKey: String,
        pointLimit: Int,
    ): JSONObject {
        val parsed = DriveWindowDetector.parseRouteKey(routeKey) ?: return JSONObject()
        val session = getSession(parsed.sessionId) ?: return JSONObject()
        val db = helper.readableDatabase
        if (ObdTripExclusions.isHidden(db, routeKey)) {
            return JSONObject()
        }
        return try {
            ObdStoreRouteProjection.routeForSession(
                db,
                session,
                pointLimit.coerceIn(1, ObdStoreRouteProjection.MAX_TRACK_POINTS),
                parsed.startedAtMs,
                parsed.endedAtMs,
                routeKey,
            )
        } catch (ex: JSONException) {
            JSONObject()
        }
    }

    fun diagnosticsSummaryJson(limit: Int): JSONObject {
        val db = helper.readableDatabase
        val latest = JSONArray()
        for (report in latestDiagnosticCodeReports(db, limit)) {
            latest.put(report.toJson())
        }
        return JSONObject()
            .put("diagnosticCodeCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES))
            .put("statusCounts", ObdStoreReportJson.statusCounts(diagnosticCodeStatusCounts(db)))
            .put("latestDiagnosticCodes", latest)
    }

    fun overviewProjectionJson(): JSONObject = safeJson { overviewJson(helper.readableDatabase) }

    // Writable: chargeSummaryJson lazily backfills the per-session charge-rollup cache (G2) for
    // finalized sessions on first read, so it needs a writable handle.
    fun chargeSummaryProjectionJson(): JSONObject = safeJson { chargeSummaryJson(helper.writableDatabase) }

    /**
     * Charge-session rows bundled for the CSV export (M1, "grade-report"). Reuses the same
     * [chargeSummaryRows] detection (observed + inferred) the dashboard's charge card renders, then
     * serializes the newest [limit] rows with [chargeSummaryRowJson] — so the export carries id,
     * start/end ms, start/end SOC, peak/avg power, energy, charger type, and confidence. Bounded by
     * [limit] (like the all-trips export) so a huge history can't blow up memory or the share file.
     *
     * `TripTrackFormatter.toChargeSessionsCsv` turns this into one CSV (one row per charge).
     */
    fun chargeSessionsForExportJson(limit: Int): JSONArray =
        // Writable: chargeSummaryRows backfills the per-session charge-rollup cache (G2) on read.
        safeArray { chargeSummaryRowsJson(chargeSummaryRows(helper.writableDatabase), limit) }

    fun batterySummaryProjectionJson(): JSONObject = safeJson { batterySummaryJson(helper.readableDatabase) }

    /**
     * Battery-health snapshots oldest-first for trend charting: the State-of-Health, capacity, pack
     * voltage, temperature, odometer, and SOC captured whenever a fresh pack-capacity read landed.
     * Empty array until the car has answered the (rare) capacity PID at least once.
     */
    fun batterySohHistoryJson(limit: Int): JSONArray =
        safeArray {
            val rows = ArrayList<JSONObject>()
            helper.readableDatabase
                .query(
                    VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                    arrayOf(
                        "captured_at_ms",
                        "soh_pct",
                        "capacity_ah",
                        "pack_voltage",
                        "battery_temp_c",
                        "odometer_km",
                        "soc",
                    ),
                    "soh_pct IS NOT NULL OR capacity_ah IS NOT NULL",
                    null,
                    null,
                    null,
                    "captured_at_ms DESC",
                    limit.coerceIn(1, 2000).toString(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(
                            JSONObject()
                                .put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                                .putBatteryDouble(cursor, "sohPct", "soh_pct")
                                .putBatteryDouble(cursor, "capacityAh", "capacity_ah")
                                .putBatteryDouble(cursor, "packVoltage", "pack_voltage")
                                .putBatteryDouble(cursor, "batteryTempC", "battery_temp_c")
                                .putBatteryDouble(cursor, "odometerKm", "odometer_km")
                                .putBatteryDouble(cursor, "soc", "soc"),
                        )
                    }
                }
            // Query is newest-first for the LIMIT; flip to oldest-first so the chart reads left→right.
            val array = JSONArray()
            for (row in rows.asReversed()) array.put(row)
            array
        }

    private fun recentSessionSummaries(
        db: SQLiteDatabase,
        limit: Int,
    ): List<RecentSessionSummaryRecord> {
        val sessions = getRecentSessions(limit)
        val usefulBySession = ObdStoreSupport.usefulSampleCountsBySession(db, sessions.map { it.id })
        val records = ArrayList<RecentSessionSummaryRecord>(sessions.size)
        for (session in sessions) {
            val usefulSamples = usefulBySession[session.id] ?: 0L
            records.add(
                RecentSessionSummaryRecord(
                    session,
                    usefulSamples,
                    maxOf(0L, session.sampleCount - usefulSamples),
                ),
            )
        }
        return records
    }

    private fun latestVehicleJson(db: SQLiteDatabase): JSONObject {
        val result = JSONObject()
        db
            .query(
                VoltTrackerDb.TABLE_VEHICLES,
                arrayOf(
                    "_id",
                    "display_name",
                    "make",
                    "model",
                    "model_year",
                    "vin_redacted",
                    "first_seen_ms",
                    "last_seen_ms",
                ),
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return result
                }
                try {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"))
                    val make = cursor.getString(cursor.getColumnIndexOrThrow("make"))
                    val model = cursor.getString(cursor.getColumnIndexOrThrow("model"))
                    val vinRedacted = cursor.getString(cursor.getColumnIndexOrThrow("vin_redacted"))
                    val yearIndex = cursor.getColumnIndexOrThrow("model_year")
                    if (displayName != null) {
                        result.put("name", displayName)
                    }
                    if (make != null) {
                        result.put("make", make)
                    }
                    if (model != null) {
                        result.put("model", model)
                    }
                    if (!vinRedacted.isNullOrEmpty()) {
                        result.put("vin", "…$vinRedacted")
                    }
                    if (!cursor.isNull(yearIndex)) {
                        result.put("year", cursor.getInt(yearIndex))
                        result.put("modelYear", cursor.getInt(yearIndex))
                    }
                    result.put("vehicleId", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                    result.put("firstSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")))
                    result.put("lastSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")))
                } catch (ignored: JSONException) {
                    // Local values are safe.
                }
            }
        return result
    }

    fun recentSessionsJson(limit: Int): JSONArray {
        val payload = JSONArray()
        val db = helper.readableDatabase
        val sessions = getRecentSessions(limit)
        val usefulBySession = ObdStoreSupport.usefulSampleCountsBySession(db, sessions.map { it.id })
        for (record in sessions) {
            var item = JSONObject()
            try {
                item = ObdStoreSupport.sessionToJson(record)
                item.put("supportedPids", record.supportedPids)
                val usefulSamples = usefulBySession[record.id] ?: 0L
                item.put("usefulSampleCount", usefulSamples)
                item.put("emptySampleCount", maxOf(0L, record.sampleCount - usefulSamples))
            } catch (ignored: JSONException) {
                // Local fields are safe.
            }
            payload.put(item)
        }
        return payload
    }

    fun adapterHistoryJson(limit: Int): JSONArray {
        val payload = JSONArray()
        for (record in getAdapterHistory(limit)) {
            val item = JSONObject()
            try {
                item.put("adapterKey", record.adapterKey)
                item.put("address", record.address)
                item.put("name", record.name)
                item.put("firstSeenMs", record.firstSeenMs)
                item.put("lastSeenMs", record.lastSeenMs)
                item.put("connectCount", record.connectCount)
                item.put("scanCount", record.scanCount)
                item.put("demoCount", record.demoCount)
                item.put("sampleCount", record.sampleCount)
                item.put("lastSessionId", record.lastSessionId)
                item.put("lastMode", record.lastMode)
                item.put("lastStatus", record.lastStatus)
                item.put("supportedPids", record.supportedPids)
                item.put("lastEventDetail", record.lastEventDetail)
            } catch (ignored: JSONException) {
                // Local fields are safe.
            }
            payload.put(item)
        }
        return payload
    }

    /**
     * The user-authored maintenance log (M5), newest first. Each row carries `id`, `createdAtMs`,
     * `odometerKm` (JSON null when unknown), `type`, `note`, and the optional service interval
     * (`intervalKm` / `intervalMonths`, JSON null when unset) the dashboard uses to compute a
     * "next due / overdue" line (M1/C4). The dashboard renders these as the real maintenance
     * entries, replacing the old hardcoded placeholder rows.
     */
    fun maintenanceLogJson(limit: Int): JSONArray {
        val payload = JSONArray()
        val db = helper.readableDatabase
        db
            .query(
                VoltTrackerDb.TABLE_MAINTENANCE_LOG,
                arrayOf("_id", "created_at_ms", "odometer_km", "type", "note", "interval_km", "interval_months"),
                null,
                null,
                null,
                null,
                "created_at_ms DESC, _id DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                val odoIndex = cursor.getColumnIndexOrThrow("odometer_km")
                val intervalKmIndex = cursor.getColumnIndexOrThrow("interval_km")
                val intervalMonthsIndex = cursor.getColumnIndexOrThrow("interval_months")
                while (cursor.moveToNext()) {
                    val item = JSONObject()
                    try {
                        item.put("id", cursor.getLong(0))
                        item.put("createdAtMs", cursor.getLong(1))
                        item.put(
                            "odometerKm",
                            if (cursor.isNull(odoIndex)) JSONObject.NULL else cursor.getDouble(odoIndex),
                        )
                        item.put("type", cursor.getString(3) ?: "")
                        item.put("note", cursor.getString(4) ?: "")
                        item.put(
                            "intervalKm",
                            if (cursor.isNull(intervalKmIndex)) JSONObject.NULL else cursor.getDouble(intervalKmIndex),
                        )
                        item.put(
                            "intervalMonths",
                            if (cursor.isNull(intervalMonthsIndex)) {
                                JSONObject.NULL
                            } else {
                                cursor.getInt(intervalMonthsIndex)
                            },
                        )
                    } catch (ignored: JSONException) {
                        // Local fields are safe.
                    }
                    payload.put(item)
                }
            }
        return payload
    }

    private fun overviewJson(db: SQLiteDatabase): JSONObject {
        val payload = JSONObject()
        payload.put("distanceMeters", trips.totalDistanceMeters())
        payload.put("maxSpeedKph", ObdStoreSupport.maxInt(db, VoltTrackerDb.TABLE_TELEMETRY, "speed_kph"))
        payload.put("avgSampleIntervalMs", ObdStoreSupport.averageSampleIntervalMs(db))
        payload.put(
            "drivingSamples",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "vehicle_state LIKE ?",
                arrayOf("%driving%"),
            ),
        )
        payload.put(
            "chargingHints",
            ObdStoreSupport.countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, "charge_transition_hint = 1", null),
        )
        payload.put("latestTelemetry", latestTelemetryJson(db))
        return payload
    }

    companion object {
        private val CHARGE_SESSION_COLUMNS =
            arrayOf(
                "_id",
                "started_at_ms",
                "ended_at_ms",
                "charger_type",
                "start_soc",
                "end_soc",
                "power_kw",
                "energy_kwh",
                "confidence",
            )

        private fun chargeSessionColumns(alias: String): String =
            CHARGE_SESSION_COLUMNS.joinToString(", ") {
                "$alias.$it AS $it"
            }

        private fun plausibleChargeWhere(alias: String): String =
            "NOT EXISTS (" +
                "SELECT 1 FROM ${VoltTrackerDb.TABLE_TELEMETRY} t " +
                "WHERE t.session_id = $alias.session_id " +
                "AND t.captured_at_ms >= $alias.started_at_ms " +
                "AND t.captured_at_ms <= COALESCE($alias.ended_at_ms, $alias.started_at_ms) " +
                "AND t.speed_kph IS NOT NULL AND t.speed_kph >= $CHARGE_DRIVING_SPEED_KPH" +
                ") AND NOT EXISTS (" +
                "SELECT 1 FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES} l " +
                "WHERE l.session_id = $alias.session_id " +
                "AND l.captured_at_ms >= $alias.started_at_ms " +
                "AND l.captured_at_ms <= COALESCE($alias.ended_at_ms, $alias.started_at_ms) " +
                "AND l.speed_mps IS NOT NULL AND l.speed_mps >= $CHARGE_DRIVING_SPEED_MPS" +
                ")"

        private const val MIN_INFERRED_CHARGE_SOC_GAIN = 8.0
        private const val MIN_INFERRED_CHARGE_GAP_MS = 10L * 60_000L
        private const val MIN_OBSERVED_CHARGE_SOC_GAIN = 5.0
        private const val MIN_OBSERVED_CHARGE_DURATION_MS = 10L * 60_000L
        private const val MIN_OBSERVED_CHARGE_ENERGY_KWH = 1.0
        private const val MIN_OBSERVED_ENERGY_DURATION_MS = 20L * 60_000L
        private const val ESTIMATED_USABLE_BATTERY_KWH = 14.0
        private const val CHARGE_DRIVING_SPEED_KPH = 5
        private const val CHARGE_DRIVING_SPEED_MPS = CHARGE_DRIVING_SPEED_KPH / 3.6

        // Bump to invalidate cached per-session charge rollups (G2): a logic change in the
        // within-session SOC scan or the drive/SOC-boundary detection makes old blobs stale, so
        // raising this forces a one-time recompute-and-rewrite on the next storage-summary read.
        private const val CHARGE_ROLLUP_CACHE_VERSION = 1

        // Keeps an IN (...) list under SQLite's bind-argument limit (the rollup-version bind shares
        // the budget, so this leaves ample headroom below the 999/1000 ceiling).
        private const val SQLITE_BIND_CHUNK = 500

        private data class ChargeSummaryRow(
            val id: Any,
            val startedAtMs: Long,
            val endedAtMs: Long?,
            val chargerType: String?,
            val startSoc: Double?,
            val endSoc: Double?,
            val powerKw: Double?,
            val energyKwh: Double?,
            val confidence: Double?,
        )

        private data class DriveSocBoundary(
            val sessionId: Long,
            val startedAtMs: Long,
            val endedAtMs: Long,
            val startSoc: Double,
            val endSoc: Double,
        )

        private data class SocDriveSample(
            val sessionId: Long,
            val atMs: Long,
            val soc: Double,
        )

        private data class StorageCountsProjection(
            val database: String,
            val databaseBytes: Long,
            val sessionCount: Long,
            val rawTelemetryCount: Long,
            val sampleCount: Long,
            val emptyTelemetryCount: Long,
            val eventCount: Long,
            val adapterCount: Long,
            val pidObservationCount: Long,
            val diagnosticCodeCount: Long,
            val diagnosticCodeStatusCounts: Map<String, Long>,
            val locationSampleCount: Long,
            val vehicleCount: Long,
            val fieldCapabilityCount: Long,
            val tripSegmentCount: Long,
            val chargeSessionCount: Long,
            val batterySnapshotCount: Long,
            val cellSnapshotCount: Long,
            val exportCount: Long,
            val latestSession: ObdSessionRecord?,
        ) {
            fun toJson(): JSONObject =
                JSONObject()
                    .put("database", database)
                    .put("databaseBytes", databaseBytes)
                    .put("sessionCount", sessionCount)
                    .put("rawTelemetryCount", rawTelemetryCount)
                    .put("sampleCount", sampleCount)
                    .put("emptyTelemetryCount", emptyTelemetryCount)
                    .put("eventCount", eventCount)
                    .put("adapterCount", adapterCount)
                    .put("pidObservationCount", pidObservationCount)
                    .put("diagnosticCodeCount", diagnosticCodeCount)
                    .put("diagnosticCodeStatusCounts", ObdStoreReportJson.statusCounts(diagnosticCodeStatusCounts))
                    .put("locationSampleCount", locationSampleCount)
                    .put("vehicleCount", vehicleCount)
                    .put("fieldCapabilityCount", fieldCapabilityCount)
                    .put("tripSegmentCount", tripSegmentCount)
                    .put("chargeSessionCount", chargeSessionCount)
                    .put("batterySnapshotCount", batterySnapshotCount)
                    .put("cellSnapshotCount", cellSnapshotCount)
                    .put("exportCount", exportCount)
                    .put("lastSessionId", latestSession?.id ?: JSONObject.NULL)
        }

        private fun storageCountsProjection(
            db: SQLiteDatabase,
            databaseFile: File,
        ): StorageCountsProjection {
            // Total + useful telemetry counts in ONE scan of telemetry_samples (the millions-of-rows
            // table) instead of two separate COUNT(*) passes — the dominant cost of this projection.
            val (rawTelemetryCount, usefulTelemetryCount) = ObdStoreSupport.telemetryTotalAndUsefulCounts(db)
            val latest =
                db
                    .query(
                        VoltTrackerDb.TABLE_SESSIONS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "started_at_ms DESC",
                        "1",
                    ).use { cursor ->
                        if (cursor.moveToFirst()) ObdStoreSupport.readSession(cursor) else null
                    }
            return StorageCountsProjection(
                VoltTrackerDb.DATABASE_NAME,
                databaseFile.length(),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_SESSIONS),
                rawTelemetryCount,
                usefulTelemetryCount,
                maxOf(0L, rawTelemetryCount - usefulTelemetryCount),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_EVENTS),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_ADAPTER_HISTORY),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES),
                diagnosticCodeStatusCounts(db),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_VEHICLES),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_FIELD_CAPABILITIES),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_TRIP_SEGMENTS),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS),
                ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_EXPORTS),
                latest,
            )
        }

        private inline fun safeJson(block: () -> JSONObject?): JSONObject =
            try {
                block() ?: JSONObject()
            } catch (ex: JSONException) {
                JSONObject()
            }

        private inline fun safeArray(block: () -> JSONArray?): JSONArray =
            try {
                block() ?: JSONArray()
            } catch (ex: JSONException) {
                JSONArray()
            }

        private fun latestDiagnosticCodeReports(
            db: SQLiteDatabase,
            limit: Int,
        ): List<DiagnosticCodeReport> {
            val reports = ArrayList<DiagnosticCodeReport>()
            db
                .query(
                    VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                    DiagnosticCodeReport.QUERY_COLUMNS,
                    null,
                    null,
                    null,
                    null,
                    "last_seen_ms DESC",
                    ObdStoreSupport.boundedLimit(limit),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        reports.add(DiagnosticCodeReport.fromCursor(cursor))
                    }
                }
            return reports
        }

        private fun diagnosticCodeStatusCounts(db: SQLiteDatabase): Map<String, Long> {
            val payload = LinkedHashMap<String, Long>()
            db
                .rawQuery(
                    "SELECT status, COUNT(*) AS count FROM ${VoltTrackerDb.TABLE_DIAGNOSTIC_CODES} GROUP BY status",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        var key = ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                        if (key.isEmpty()) {
                            key = "stored"
                        }
                        payload[key] = cursor.getLong(cursor.getColumnIndexOrThrow("count"))
                    }
                }
            return payload
        }

        @Throws(JSONException::class)
        private fun chargeSummaryJson(db: SQLiteDatabase): JSONObject {
            val rows = chargeSummaryRows(db)
            return JSONObject()
                .put("chargeSessionCount", rows.size)
                .put(
                    "chargingHintCount",
                    ObdStoreSupport.countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "charge_transition_hint = 1",
                        null,
                    ),
                ).put("maxPowerKw", maxChargePowerKw(rows))
                .put("latest", if (rows.isEmpty()) JSONObject() else chargeSummaryRowJson(rows[0]))
                .put("recentSessions", chargeSummaryRowsJson(rows, 12))
        }

        @Throws(JSONException::class)
        private fun batterySummaryJson(db: SQLiteDatabase): JSONObject =
            JSONObject()
                .put("snapshotCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS))
                .put("cellSnapshotCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS))
                .put("latestTelemetry", latestTelemetryJson(db))
                .put("latestBatterySnapshot", latestBatterySnapshotJson(db))

        @JvmStatic
        @Throws(JSONException::class)
        fun latestTelemetryJson(db: SQLiteDatabase): JSONObject {
            db
                .query(
                    VoltTrackerDb.TABLE_TELEMETRY,
                    arrayOf(
                        "captured_at_ms",
                        "vehicle_state",
                        "speed_kph",
                        "rpm",
                        "voltage",
                        "soc",
                        "battery_temp",
                        "power_kw",
                        "pack_voltage",
                        "pack_current_a",
                        "json",
                    ),
                    ObdStoreSupport.USEFUL_TELEMETRY_WHERE,
                    null,
                    null,
                    null,
                    "captured_at_ms DESC",
                    "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return JSONObject()
                    }
                    val item = ObdStoreSupport.parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")))
                    item.put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                    item.put(
                        "vehicleState",
                        ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state"))),
                    )
                    item.put("speedKph", reportBoxed(ObdStoreSupport.nullableIntBoxed(cursor, "speed_kph")))
                    item.put("rpm", reportBoxed(ObdStoreSupport.nullableIntBoxed(cursor, "rpm")))
                    item.put("voltage", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "voltage")))
                    item.put("soc", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "soc")))
                    item.put("batteryTemp", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "battery_temp")))
                    item.put("powerKw", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw")))
                    item.put("packVoltage", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_voltage")))
                    item.put("packCurrentA", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_current_a")))
                    return item
                }
        }

        private fun boxedOrNull(value: Number?): Any = value ?: JSONObject.NULL

        private fun chargeSummaryRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val inferred = inferredChargeRows(db)
            val observed =
                observedChargeRows(db)
                    .filterNot { observed ->
                        inferred.any { inferredRow -> isInsideInferredCharge(observed, inferredRow) }
                    }
            return (inferred + observed).sortedWith(
                compareByDescending<ChargeSummaryRow> { it.startedAtMs }.thenByDescending { it.endedAtMs ?: 0L },
            )
        }

        private fun observedChargeRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            db
                .rawQuery(
                    "SELECT ${chargeSessionColumns("c")} FROM ${VoltTrackerDb.TABLE_CHARGE_SESSIONS} c " +
                        "WHERE ${plausibleChargeWhere("c")} ORDER BY c.started_at_ms DESC",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = chargeSessionRow(cursor)
                        if (isMeaningfulObservedCharge(row)) {
                            rows.add(row)
                        }
                    }
                }
            return rows
        }

        private fun isMeaningfulObservedCharge(row: ChargeSummaryRow): Boolean {
            val durationMs = (row.endedAtMs ?: row.startedAtMs) - row.startedAtMs
            val startSoc = row.startSoc
            val endSoc = row.endSoc
            if (startSoc != null &&
                endSoc != null &&
                endSoc - startSoc >= MIN_OBSERVED_CHARGE_SOC_GAIN &&
                durationMs >= MIN_OBSERVED_CHARGE_DURATION_MS
            ) {
                return true
            }
            val energyKwh = row.energyKwh
            val powerKw = row.powerKw
            return energyKwh != null &&
                powerKw != null &&
                energyKwh >= MIN_OBSERVED_CHARGE_ENERGY_KWH &&
                powerKw > 0.0 &&
                durationMs >= MIN_OBSERVED_ENERGY_DURATION_MS
        }

        private fun inferredChargeRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            rows.addAll(inferredChargeRowsBetweenDriveBoundaries(db))
            rows.addAll(inferredChargeRowsWithinSessions(db))
            return mergeInferredChargeRows(rows)
        }

        private fun inferredChargeRowsBetweenDriveBoundaries(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val boundaries = meaningfulDriveSocBoundaries(db)
            if (boundaries.size < 2) {
                return emptyList()
            }
            val rows = ArrayList<ChargeSummaryRow>()
            var previous = boundaries[0]
            for (i in 1 until boundaries.size) {
                val current = boundaries[i]
                val socGain = current.startSoc - previous.endSoc
                val gapMs = current.startedAtMs - previous.endedAtMs
                if (socGain >= MIN_INFERRED_CHARGE_SOC_GAIN && gapMs >= MIN_INFERRED_CHARGE_GAP_MS) {
                    rows.add(
                        ChargeSummaryRow(
                            "inferred:${previous.sessionId}:${previous.endedAtMs}:${current.sessionId}:${current.startedAtMs}",
                            previous.endedAtMs,
                            current.startedAtMs,
                            "inferred",
                            previous.endSoc,
                            current.startSoc,
                            null,
                            (socGain / 100.0) * ESTIMATED_USABLE_BATTERY_KWH,
                            0.7,
                        ),
                    )
                }
                previous = current
            }
            return rows
        }

        private fun inferredChargeRowsWithinSessions(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            for (contribution in sessionChargeContributions(db)) {
                rows.addAll(contribution.withinSessionRows)
            }
            return rows
        }

        /**
         * Computes this session's within-session inferred-charge rows directly from its telemetry
         * (the intra-session SOC-gain scan). Pure function of one session's rows — cached per
         * finalized session in [sessionChargeContributions].
         */
        private fun computeWithinSessionRows(
            db: SQLiteDatabase,
            session: ObdSessionRecord,
        ): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            var previous: SocDriveSample? = null
            db
                .rawQuery(
                    "SELECT captured_at_ms, soc FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND soc IS NOT NULL AND speed_kph IS NOT NULL " +
                        "AND speed_kph >= ? ORDER BY captured_at_ms ASC",
                    arrayOf(session.id.toString(), CHARGE_DRIVING_SPEED_KPH.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val current = SocDriveSample(session.id, cursor.getLong(0), cursor.getDouble(1))
                        val last = previous
                        if (last != null) {
                            maybeInferredChargeRow(last, current)?.let { rows.add(it) }
                        }
                        previous = current
                    }
                }
            return rows
        }

        private fun maybeInferredChargeRow(
            previous: SocDriveSample,
            current: SocDriveSample,
        ): ChargeSummaryRow? {
            val socGain = current.soc - previous.soc
            val gapMs = current.atMs - previous.atMs
            if (socGain < MIN_INFERRED_CHARGE_SOC_GAIN || gapMs < MIN_INFERRED_CHARGE_GAP_MS) {
                return null
            }
            return ChargeSummaryRow(
                "inferred:${previous.sessionId}:${previous.atMs}:${current.sessionId}:${current.atMs}",
                previous.atMs,
                current.atMs,
                "inferred",
                previous.soc,
                current.soc,
                null,
                (socGain / 100.0) * ESTIMATED_USABLE_BATTERY_KWH,
                0.7,
            )
        }

        private fun mergeInferredChargeRows(rows: List<ChargeSummaryRow>): List<ChargeSummaryRow> {
            if (rows.size < 2) {
                return rows
            }
            val merged = ArrayList<ChargeSummaryRow>()
            for (row in rows.sortedWith(compareBy<ChargeSummaryRow> { it.startedAtMs }.thenBy { it.endedAtMs ?: 0L })) {
                val existingIndex = merged.indexOfFirst { existing -> chargeIntervalsOverlap(existing, row) }
                if (existingIndex < 0) {
                    merged.add(row)
                    continue
                }
                if (socGain(row) > socGain(merged[existingIndex])) {
                    merged[existingIndex] = row
                }
            }
            return merged
        }

        private fun chargeIntervalsOverlap(
            left: ChargeSummaryRow,
            right: ChargeSummaryRow,
        ): Boolean {
            val leftEnd = left.endedAtMs ?: left.startedAtMs
            val rightEnd = right.endedAtMs ?: right.startedAtMs
            return left.startedAtMs <= rightEnd && right.startedAtMs <= leftEnd
        }

        private fun socGain(row: ChargeSummaryRow): Double {
            val start = row.startSoc ?: return 0.0
            val end = row.endSoc ?: return 0.0
            return end - start
        }

        private fun meaningfulDriveSocBoundaries(db: SQLiteDatabase): List<DriveSocBoundary> {
            val boundaries = ArrayList<DriveSocBoundary>()
            for (contribution in sessionChargeContributions(db)) {
                boundaries.addAll(contribution.boundaries)
            }
            return boundaries.sortedBy { it.startedAtMs }
        }

        /**
         * Computes this session's meaningful drive/SOC boundaries directly from its drive windows
         * and telemetry. Pure function of one session's rows — cached per finalized session in
         * [sessionChargeContributions]. The caller applies the final sort across all sessions.
         */
        private fun computeBoundaries(
            db: SQLiteDatabase,
            session: ObdSessionRecord,
        ): List<DriveSocBoundary> {
            val boundaries = ArrayList<DriveSocBoundary>()
            for (window in DriveWindowDetector.windowsForSession(db, session)) {
                val points =
                    ObdStoreRouteProjection.routePointsForSessionJson(
                        db,
                        session.id,
                        1000,
                        window.startedAtMs,
                        window.endedAtMs,
                    )
                val distanceMeters = ObdStoreSupport.distanceMeters(points)
                val maxSpeed = ObdSessionClassifier.maxSpeedKphForWindow(db, session.id, window)
                if (!ObdSessionClassifier.isMeaningfulTrip(points.length(), distanceMeters, maxSpeed)) {
                    continue
                }
                val startSoc = socForWindow(db, session.id, window, "ASC") ?: continue
                val endSoc = socForWindow(db, session.id, window, "DESC") ?: continue
                boundaries.add(DriveSocBoundary(session.id, window.startedAtMs, window.endedAtMs, startSoc, endSoc))
            }
            return boundaries
        }

        private fun socForWindow(
            db: SQLiteDatabase,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
            order: String,
        ): Double? =
            db
                .rawQuery(
                    "SELECT soc FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                        "AND soc IS NOT NULL ORDER BY captured_at_ms $order LIMIT 1",
                    arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
                }

        /**
         * Per-session contribution to the inferred-charge scan (G2): this session's within-session
         * inferred-charge rows plus its meaningful drive/SOC boundaries. Both are pure functions of
         * one finalized session's raw rows, so they are cached per session in `charge_session_rollups`
         * and reassembled by the cross-session merge/sort, which is what makes the cache transparent.
         */
        private class SessionChargeContribution(
            val withinSessionRows: List<ChargeSummaryRow>,
            val boundaries: List<DriveSocBoundary>,
        )

        /**
         * Enumerates every OBD session with useful telemetry in oldest-first order and returns each
         * one's charge contribution. The qualifying-session query is intentionally one SQL pass rather
         * than the old bounded session read plus per-session COUNT(*) filter, so whole-history charge
         * summaries do not inherit the recent-session cap or an N+1 query pattern. For a finalized
         * session cached at [CHARGE_ROLLUP_CACHE_VERSION] the cached blob is served; otherwise the
         * contribution is computed, and — only for finalized sessions — persisted before being returned.
         * The active (not-yet-finalized) session is always computed live and never cached.
         */
        private fun sessionChargeContributions(db: SQLiteDatabase): List<SessionChargeContribution> {
            val sessions = ObdStoreSupport.getObdSessionsWithUsefulTelemetry(db)
            if (sessions.isEmpty()) {
                return emptyList()
            }
            val cached = cachedChargeContributions(db, sessions.map { it.id })
            val contributions = ArrayList<SessionChargeContribution>(sessions.size)
            for (session in sessions) {
                val finalized = session.endedAtMs > 0
                val hit = if (finalized) cached[session.id] else null
                if (hit != null) {
                    contributions.add(hit)
                    continue
                }
                val computed =
                    SessionChargeContribution(
                        computeWithinSessionRows(db, session),
                        computeBoundaries(db, session),
                    )
                if (finalized) {
                    writeChargeRollup(db, session.id, computed)
                }
                contributions.add(computed)
            }
            return contributions
        }

        /**
         * Loads cached charge contributions for [sessionIds] that are still at the current
         * [CHARGE_ROLLUP_CACHE_VERSION], parsing each stored blob back into a contribution. A blob
         * that fails to parse is skipped (treated as a miss), so the session recomputes and rewrites
         * a fresh row instead of poisoning the projection.
         */
        private fun cachedChargeContributions(
            db: SQLiteDatabase,
            sessionIds: List<Long>,
        ): Map<Long, SessionChargeContribution> {
            val result = HashMap<Long, SessionChargeContribution>()
            for (chunk in sessionIds.chunked(SQLITE_BIND_CHUNK)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = (chunk.map { it.toString() } + CHARGE_ROLLUP_CACHE_VERSION.toString()).toTypedArray()
                db
                    .rawQuery(
                        "SELECT session_id, charge_json FROM ${VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS} " +
                            "WHERE session_id IN ($placeholders) AND rollup_version = ?",
                        args,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val sessionId = cursor.getLong(0)
                            parseChargeContribution(cursor.getString(1))?.let { result[sessionId] = it }
                        }
                    }
            }
            return result
        }

        private fun writeChargeRollup(
            db: SQLiteDatabase,
            sessionId: Long,
            contribution: SessionChargeContribution,
        ) {
            val values =
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("rollup_version", CHARGE_ROLLUP_CACHE_VERSION)
                    put("computed_at_ms", System.currentTimeMillis())
                    put("charge_json", serializeChargeContribution(contribution).toString())
                }
            db.insertWithOnConflict(
                VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }

        @Throws(JSONException::class)
        private fun serializeChargeContribution(contribution: SessionChargeContribution): JSONObject {
            val rows = JSONArray()
            for (row in contribution.withinSessionRows) {
                rows.put(serializeChargeRow(row))
            }
            val boundaries = JSONArray()
            for (boundary in contribution.boundaries) {
                boundaries.put(serializeBoundary(boundary))
            }
            return JSONObject().put("rows", rows).put("boundaries", boundaries)
        }

        private fun parseChargeContribution(json: String?): SessionChargeContribution? {
            if (json.isNullOrEmpty()) {
                return null
            }
            return try {
                val obj = JSONObject(json)
                val rowsArray = obj.getJSONArray("rows")
                val rows = ArrayList<ChargeSummaryRow>(rowsArray.length())
                for (i in 0 until rowsArray.length()) {
                    rows.add(parseChargeRow(rowsArray.getJSONObject(i)))
                }
                val boundariesArray = obj.getJSONArray("boundaries")
                val boundaries = ArrayList<DriveSocBoundary>(boundariesArray.length())
                for (i in 0 until boundariesArray.length()) {
                    boundaries.add(parseBoundary(boundariesArray.getJSONObject(i)))
                }
                SessionChargeContribution(rows, boundaries)
            } catch (ex: JSONException) {
                null
            }
        }

        @Throws(JSONException::class)
        private fun serializeChargeRow(row: ChargeSummaryRow): JSONObject =
            JSONObject()
                .put("id", row.id)
                .put("startedAtMs", row.startedAtMs)
                .put("endedAtMs", row.endedAtMs ?: JSONObject.NULL)
                .put("chargerType", row.chargerType ?: JSONObject.NULL)
                .put("startSoc", row.startSoc ?: JSONObject.NULL)
                .put("endSoc", row.endSoc ?: JSONObject.NULL)
                .put("powerKw", row.powerKw ?: JSONObject.NULL)
                .put("energyKwh", row.energyKwh ?: JSONObject.NULL)
                .put("confidence", row.confidence ?: JSONObject.NULL)

        @Throws(JSONException::class)
        private fun parseChargeRow(obj: JSONObject): ChargeSummaryRow =
            ChargeSummaryRow(
                obj.get("id"),
                obj.getLong("startedAtMs"),
                if (obj.isNull("endedAtMs")) null else obj.getLong("endedAtMs"),
                if (obj.isNull("chargerType")) null else obj.getString("chargerType"),
                if (obj.isNull("startSoc")) null else obj.getDouble("startSoc"),
                if (obj.isNull("endSoc")) null else obj.getDouble("endSoc"),
                if (obj.isNull("powerKw")) null else obj.getDouble("powerKw"),
                if (obj.isNull("energyKwh")) null else obj.getDouble("energyKwh"),
                if (obj.isNull("confidence")) null else obj.getDouble("confidence"),
            )

        @Throws(JSONException::class)
        private fun serializeBoundary(boundary: DriveSocBoundary): JSONObject =
            JSONObject()
                .put("sessionId", boundary.sessionId)
                .put("startedAtMs", boundary.startedAtMs)
                .put("endedAtMs", boundary.endedAtMs)
                .put("startSoc", boundary.startSoc)
                .put("endSoc", boundary.endSoc)

        @Throws(JSONException::class)
        private fun parseBoundary(obj: JSONObject): DriveSocBoundary =
            DriveSocBoundary(
                obj.getLong("sessionId"),
                obj.getLong("startedAtMs"),
                obj.getLong("endedAtMs"),
                obj.getDouble("startSoc"),
                obj.getDouble("endSoc"),
            )

        private fun isInsideInferredCharge(
            observed: ChargeSummaryRow,
            inferred: ChargeSummaryRow,
        ): Boolean {
            val observedEnd = observed.endedAtMs ?: observed.startedAtMs
            val inferredEnd = inferred.endedAtMs ?: inferred.startedAtMs
            return observed.startedAtMs >= inferred.startedAtMs && observedEnd <= inferredEnd
        }

        private fun maxChargePowerKw(rows: List<ChargeSummaryRow>): Double {
            var max = 0.0
            for (row in rows) {
                val power = row.powerKw
                if (power != null && !power.isNaN() && !power.isInfinite() && power > max) {
                    max = power
                }
            }
            return max
        }

        private fun chargeSessionRow(cursor: Cursor): ChargeSummaryRow {
            val chargerType = cursor.getString(cursor.getColumnIndexOrThrow("charger_type"))
            return ChargeSummaryRow(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
                ObdStoreSupport.nullableLongBoxed(cursor, "ended_at_ms"),
                if (chargerType == null) null else ObdStoreSupport.clean(chargerType),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "start_soc"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "end_soc"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "energy_kwh"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "confidence"),
            )
        }

        @Throws(JSONException::class)
        private fun chargeSummaryRowJson(row: ChargeSummaryRow): JSONObject =
            JSONObject()
                .put("id", row.id)
                .put("startedAtMs", row.startedAtMs)
                .put("endedAtMs", boxedOrNull(row.endedAtMs))
                .put("chargerType", row.chargerType ?: JSONObject.NULL)
                .put("startSoc", boxedOrNull(row.startSoc))
                .put("endSoc", boxedOrNull(row.endSoc))
                .put("powerKw", boxedOrNull(row.powerKw))
                .put("energyKwh", boxedOrNull(row.energyKwh))
                .put("confidence", boxedOrNull(row.confidence))

        @Throws(JSONException::class)
        private fun chargeSummaryRowsJson(
            rows: List<ChargeSummaryRow>,
            limit: Int,
        ): JSONArray {
            val out = JSONArray()
            val count = minOf(maxOf(1, limit), rows.size)
            for (i in 0 until count) {
                out.put(chargeSummaryRowJson(rows[i]))
            }
            return out
        }

        @Throws(JSONException::class)
        private fun latestBatterySnapshotJson(db: SQLiteDatabase): JSONObject {
            db
                .query(
                    VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                    arrayOf(
                        "_id",
                        "captured_at_ms",
                        "soc",
                        "capacity_ah",
                        "soh_pct",
                        "pack_voltage",
                        "pack_current_a",
                        "pack_power_kw",
                        "battery_temp_c",
                    ),
                    null,
                    null,
                    null,
                    null,
                    "captured_at_ms DESC",
                    "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return JSONObject()
                    }
                    return JSONObject()
                        .put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                        .put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                        .putBatteryDouble(cursor, "soc", "soc")
                        .putBatteryDouble(cursor, "capacityAh", "capacity_ah")
                        .putBatteryDouble(cursor, "sohPct", "soh_pct")
                        .putBatteryDouble(cursor, "packVoltage", "pack_voltage")
                        .putBatteryDouble(cursor, "packCurrentA", "pack_current_a")
                        .putBatteryDouble(cursor, "packPowerKw", "pack_power_kw")
                        .putBatteryDouble(cursor, "batteryTempC", "battery_temp_c")
                }
        }

        private fun reportBoxed(value: Number?): Any = ObdStoreReportJson.boxedOrNull(value)
    }
}
