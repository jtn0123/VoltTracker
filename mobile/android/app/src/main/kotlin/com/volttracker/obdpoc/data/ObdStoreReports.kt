package com.volttracker.obdpoc.data

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

    // Writable: ObdStoreChargeSummary.summaryJson lazily backfills the per-session charge-rollup
    // cache (G2) for finalized sessions on first read, so it needs a writable handle.
    fun chargeSummaryProjectionJson(): JSONObject =
        safeJson { ObdStoreChargeSummary.summaryJson(helper.writableDatabase) }

    /**
     * Charge-session rows bundled for the CSV export (M1, "grade-report"). Reuses the same
     * charge detection (observed + inferred) the dashboard's charge card renders, then serializes
     * the newest [limit] rows — so the export carries id, start/end ms, start/end SOC, peak/avg
     * power, energy, charger type, and confidence. Bounded by [limit] (like the all-trips export)
     * so a huge history can't blow up memory or the share file.
     *
     * `TripTrackFormatter.toChargeSessionsCsv` turns this into one CSV (one row per charge).
     */
    fun chargeSessionsForExportJson(limit: Int): JSONArray =
        // Writable: the charge scan backfills the per-session charge-rollup cache (G2) on read.
        safeArray { ObdStoreChargeSummary.exportRowsJson(helper.writableDatabase, limit) }

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
        private fun batterySummaryJson(db: SQLiteDatabase): JSONObject =
            JSONObject()
                .put("snapshotCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS))
                .put("cellSnapshotCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS))
                .put("latestTelemetry", latestTelemetryJson(db))
                .put("latestBatterySnapshot", latestBatterySnapshotJson(db))
                .put("latestCellSnapshot", latestCellSnapshotJson(db))

        /**
         * The latest full-pack cell-voltage snapshot (from a 96-cell probe pass) for the
         * dashboard's per-cell voltage map: `{capturedAtMs, cellCount, cells: [{index, voltage},
         * ...]}` with `index` 1-based. Empty object until a cell probe has completed at least once.
         */
        @JvmStatic
        @Throws(JSONException::class)
        fun latestCellSnapshotJson(db: SQLiteDatabase): JSONObject {
            var snapshotId = -1L
            var capturedAtMs = 0L
            db
                .rawQuery(
                    "SELECT b._id, b.captured_at_ms FROM ${VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS} b " +
                        "WHERE EXISTS (SELECT 1 FROM ${VoltTrackerDb.TABLE_CELL_SNAPSHOTS} c " +
                        "WHERE c.battery_snapshot_id = b._id) " +
                        "ORDER BY b.captured_at_ms DESC LIMIT 1",
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        snapshotId = cursor.getLong(0)
                        capturedAtMs = cursor.getLong(1)
                    }
                }
            if (snapshotId < 0) {
                return JSONObject()
            }
            val cells = JSONArray()
            db
                .query(
                    VoltTrackerDb.TABLE_CELL_SNAPSHOTS,
                    arrayOf("cell_index", "voltage"),
                    "battery_snapshot_id = ?",
                    arrayOf(snapshotId.toString()),
                    null,
                    null,
                    "cell_index ASC",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        cells.put(
                            JSONObject()
                                .put("index", cursor.getInt(0))
                                .put(
                                    "voltage",
                                    ObdStoreReportJson.boxedOrNull(
                                        ObdStoreSupport.nullableDoubleBoxed(cursor, "voltage"),
                                    ),
                                ),
                        )
                    }
                }
            return JSONObject()
                .put("capturedAtMs", capturedAtMs)
                .put("cellCount", cells.length())
                .put("cells", cells)
        }

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
