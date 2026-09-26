package com.volttracker.obdpoc.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.VehicleActivityThresholds
import com.volttracker.obdpoc.materialize.TripSplitRules
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Detects drive windows inside one longer OBD session using GPS stops and OBD activity. */
object DriveWindowDetector {
    private const val MAX_INACTIVE_MS: Long = 5L * 60_000L
    private const val MIN_STOP_SPLIT_MS: Long = 3L * 60_000L
    private const val STOP_SPEED_MPS: Double = 2.5
    private const val MAX_STOP_DRIFT_METERS: Double = 300.0

    /**
     * Memory guard on the batched sample reads: a detection pass never materializes more rows
     * than this per query. At ~1 telemetry row/850 ms that is weeks of continuous driving for a
     * single batch; if a dataset ever exceeds it, sessions past the cap fall back to coarse
     * session-bounds windows instead of OOMing the process. The batched reads order by
     * session_id DESC so this budget is spent on the NEWEST sessions first — those are what
     * recentRoutes renders — and only the oldest sessions in an over-cap batch get truncated,
     * rather than the newest (the ones the map view exists to show).
     */
    private const val MAX_SAMPLE_ROWS: Int = 200_000

    private const val GEO_TELEMETRY_WHERE = "latitude IS NOT NULL AND longitude IS NOT NULL"

    @JvmStatic
    fun windowsForSession(
        db: SQLiteDatabase?,
        session: ObdSessionRecord?,
    ): List<DriveWindow> {
        if (db == null || session == null) {
            return emptyList()
        }
        val data = readSessionData(db, listOf(session))[session.id] ?: SessionData()
        return windowsForSession(session, data)
    }

    @JvmStatic
    fun windowsForSessions(
        db: SQLiteDatabase?,
        sessions: List<ObdSessionRecord>?,
    ): Map<Long, List<DriveWindow>> {
        if (db == null || sessions.isNullOrEmpty()) {
            return emptyMap()
        }
        val dataBySession = readSessionData(db, sessions)
        val windowsBySession = LinkedHashMap<Long, List<DriveWindow>>()
        for (session in sessions) {
            windowsBySession[session.id] = windowsForSession(session, dataBySession[session.id] ?: SessionData())
        }
        return windowsBySession
    }

    private fun windowsForSession(
        session: ObdSessionRecord,
        data: SessionData,
    ): List<DriveWindow> {
        val bounds = dataBounds(data)
        var fallbackStartMs = session.startedAtMs
        var fallbackEndMs =
            if (session.endedAtMs > 0) {
                session.endedAtMs
            } else {
                session.lastEventAtMs
            }
        if (bounds != null) {
            if (fallbackStartMs <= 0L ||
                fallbackStartMs > bounds.lastMs ||
                fallbackStartMs < bounds.firstMs
            ) {
                fallbackStartMs = bounds.firstMs
            }
            fallbackEndMs = bounds.lastMs
        }
        if (fallbackEndMs <= fallbackStartMs) {
            return emptyList()
        }
        val spans = splitSpans(data, gearAnalysis(session.tripRulesVersion, data.activitySamples, data.userSplits))
        if (spans.isEmpty()) {
            return listOf(DriveWindow(session.id, 0, fallbackStartMs, fallbackEndMs))
        }
        val windows = mutableListOf<DriveWindow>()
        var windowStartMs = fallbackStartMs
        var index = 0
        for (span in mergeSpans(spans)) {
            if (span.endMs <= windowStartMs) {
                continue
            }
            if (span.startMs > windowStartMs) {
                windows.add(DriveWindow(session.id, index++, windowStartMs, span.startMs))
            }
            windowStartMs = max(windowStartMs, span.endMs + 1L)
        }
        if (windowStartMs < fallbackEndMs) {
            windows.add(DriveWindow(session.id, index, windowStartMs, fallbackEndMs))
        }
        return windows
    }

    @JvmStatic
    fun parseRouteKey(raw: String?): RouteKey? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) {
            return null
        }
        val parts = clean.split(":")
        try {
            if (parts.size == 1) {
                return RouteKey(parts[0].toLong(), null, null)
            }
            if (parts.size == 3) {
                return RouteKey(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
            }
        } catch (ignored: NumberFormatException) {
            return null
        }
        return null
    }

    /**
     * In-trip Park stops ([TripSplitRules]) between [startMs] and [endMs] of a gear-aware session;
     * always empty for a legacy session, which is never queried. A stop the user split the trip at
     * ([ObdTripSplits]) is a trip boundary now, not an in-trip stop, so it is never listed.
     */
    @JvmStatic
    fun parkStopsForWindow(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
        startMs: Long,
        endMs: Long,
    ): List<TripSplitRules.ParkStop> {
        if (!TripSplitRules.appliesTo(session.tripRulesVersion) || endMs <= startMs) {
            return emptyList()
        }
        val samples = ArrayList<TripSplitRules.GearSample>()
        db
            .rawQuery(
                "SELECT captured_at_ms, prndl_raw, door_open FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                    "AND (prndl_raw IS NOT NULL OR door_open IS NOT NULL) " +
                    "ORDER BY captured_at_ms ASC LIMIT $MAX_SAMPLE_ROWS",
                arrayOf(session.id.toString(), startMs.toString(), endMs.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    samples.add(
                        TripSplitRules.GearSample(
                            cursor.getLong(0),
                            if (cursor.isNull(1)) null else cursor.getInt(1),
                            if (cursor.isNull(2)) null else cursor.getInt(2) != 0,
                        ),
                    )
                }
            }
        val userSplits = ObdTripSplits.activeSplits(db, session.id)
        return TripSplitRules.analyze(session.tripRulesVersion, samples, userSplits).stopsWithin(startMs, endMs)
    }

    private fun gearAnalysis(
        rulesVersion: Int,
        samples: List<ActivitySample>,
        userSplits: List<TripSplitRules.Span>,
    ): TripSplitRules.Analysis {
        if (!TripSplitRules.appliesTo(rulesVersion)) {
            return TripSplitRules.Analysis.NONE
        }
        return TripSplitRules.analyze(
            rulesVersion,
            samples.map { TripSplitRules.GearSample(it.atMs, it.prndlRaw, it.doorOpen) },
            userSplits,
        )
    }

    /** Mirrors TripMaterializer.splitSpans so both splitters cut a session the same way. */
    private fun splitSpans(
        data: SessionData,
        gear: TripSplitRules.Analysis,
    ): List<SplitSpan> {
        val spans = mutableListOf<SplitSpan>()
        spans.addAll(gpsStopSpans(data))
        spans.addAll(inactiveTelemetrySpans(data))
        spans.removeAll { gear.governs(it.startMs, it.endMs) }
        gear.splitSpans.mapTo(spans) { SplitSpan(it.startMs, it.endMs) }
        return spans
    }

    private fun gpsStopSpans(data: SessionData): List<SplitSpan> {
        val samples = routeSamples(data)
        val spans = mutableListOf<SplitSpan>()
        if (samples.size < 2) {
            return spans
        }
        var runStart = -1
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val sample = samples[i]
            val seconds = max(1.0, (sample.atMs - previous.atMs) / 1000.0)
            val slow = haversineMeters(previous, sample) / seconds < STOP_SPEED_MPS
            if (slow) {
                if (runStart < 0) {
                    runStart = i - 1
                }
            } else if (runStart >= 0) {
                addStopSpan(data.activitySamples, spans, samples, runStart, i - 1)
                runStart = -1
            }
        }
        if (runStart >= 0) {
            addStopSpan(data.activitySamples, spans, samples, runStart, samples.lastIndex)
        }
        return spans
    }

    private fun addStopSpan(
        activitySamples: List<ActivitySample>,
        spans: MutableList<SplitSpan>,
        samples: List<RouteSample>,
        startIndex: Int,
        endIndex: Int,
    ) {
        val startMs = samples[startIndex].atMs
        val endMs = samples[endIndex].atMs
        val stoppedAtMs = samples[min(startIndex + 1, endIndex)].atMs
        if (endMs - stoppedAtMs >= MIN_STOP_SPLIT_MS &&
            pathMeters(samples, startIndex, endIndex) <= MAX_STOP_DRIFT_METERS &&
            !hasActiveTelemetryInSpan(activitySamples, stoppedAtMs, endMs)
        ) {
            spans.add(SplitSpan(startMs, endMs))
        }
    }

    private fun dataBounds(data: SessionData): DataBounds? {
        if (data.geoTelemetryBounds != null) {
            return mergeBounds(data.locationBounds, data.geoTelemetryBounds)
        }
        return mergeBounds(data.locationBounds, data.telemetryBounds)
    }

    private fun mergeBounds(
        left: DataBounds?,
        right: DataBounds?,
    ): DataBounds? {
        if (left == null) {
            return right
        }
        if (right == null) {
            return left
        }
        return DataBounds(min(left.firstMs, right.firstMs), max(left.lastMs, right.lastMs))
    }

    private fun pathMeters(
        samples: List<RouteSample>,
        startIndex: Int,
        endIndex: Int,
    ): Double {
        var meters = 0.0
        for (i in startIndex + 1..endIndex) {
            meters += haversineMeters(samples[i - 1], samples[i])
        }
        return meters
    }

    private fun routeSamples(data: SessionData): List<RouteSample> =
        if (data.locationSamples.size >= data.telemetryRouteSamples.size) {
            data.locationSamples
        } else {
            data.telemetryRouteSamples
        }

    private fun inactiveTelemetrySpans(data: SessionData): List<SplitSpan> {
        val spans = mutableListOf<SplitSpan>()
        var inactiveStartMs = -1L
        var inactiveEndMs = -1L
        for (sample in data.activitySamples) {
            if (isActiveVehicleSample(sample)) {
                addInactiveSpan(spans, inactiveStartMs, inactiveEndMs)
                inactiveStartMs = -1L
                inactiveEndMs = -1L
            } else {
                if (inactiveStartMs < 0L) {
                    inactiveStartMs = sample.atMs
                }
                inactiveEndMs = sample.atMs
            }
        }
        addInactiveSpan(spans, inactiveStartMs, inactiveEndMs)
        return spans
    }

    private fun addInactiveSpan(
        spans: MutableList<SplitSpan>,
        startMs: Long,
        endMs: Long,
    ) {
        if (startMs >= 0L && endMs >= startMs && endMs - startMs > MAX_INACTIVE_MS) {
            spans.add(SplitSpan(startMs, endMs))
        }
    }

    private fun hasActiveTelemetryInSpan(
        activitySamples: List<ActivitySample>,
        startMs: Long,
        endMs: Long,
    ): Boolean {
        for (sample in activitySamples) {
            if (sample.atMs >= startMs && sample.atMs <= endMs && isActiveVehicleSample(sample)) {
                return true
            }
        }
        return false
    }

    private fun mergeSpans(raw: List<SplitSpan>): List<SplitSpan> {
        if (raw.isEmpty()) {
            return raw
        }
        val sorted = raw.sortedBy { it.startMs }
        val merged = mutableListOf<SplitSpan>()
        for (span in sorted) {
            if (merged.isEmpty()) {
                merged.add(span)
                continue
            }
            val last = merged.last()
            if (span.startMs <= last.endMs) {
                merged[merged.lastIndex] = SplitSpan(last.startMs, max(last.endMs, span.endMs))
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    private fun isActiveVehicleSample(sample: ActivitySample): Boolean {
        val speedKph = sample.speedKph
        if (speedKph != null && speedKph > VehicleActivityThresholds.MOVING_SPEED_KPH) {
            return true
        }
        val rpm = sample.rpm
        if (rpm != null && rpm > VehicleActivityThresholds.ENGINE_READY_RPM) {
            return true
        }
        val voltage = sample.voltage
        if (voltage != null && voltage > VehicleActivityThresholds.READY_VOLTAGE) {
            return true
        }
        val powerKw = sample.powerKw
        if (powerKw != null && abs(powerKw) > VehicleActivityThresholds.ACTIVE_POWER_KW) {
            return true
        }
        val packCurrentA = sample.packCurrentA
        return packCurrentA != null &&
            abs(packCurrentA) > VehicleActivityThresholds.ACTIVE_PACK_CURRENT_A
    }

    private fun nullableInt(
        cursor: Cursor,
        column: String,
    ): Int? {
        val idx = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(idx)) null else cursor.getInt(idx)
    }

    private fun nullableDouble(
        cursor: Cursor,
        column: String,
    ): Double? {
        val idx = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(idx)) null else cursor.getDouble(idx)
    }

    private fun readSessionData(
        db: SQLiteDatabase,
        sessions: List<ObdSessionRecord>,
    ): Map<Long, SessionData> {
        val ids = sessions.map { it.id }.distinct()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val dataBySession = LinkedHashMap<Long, SessionData>()
        for (id in ids) {
            dataBySession[id] = SessionData()
        }
        applyBounds(
            dataBySession,
            readBoundsBySession(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, ids, ""),
        ) { data, bounds -> data.locationBounds = bounds }
        readTelemetryBoundsBySession(db, ids, dataBySession)
        readLocationSamplesBySession(db, ids).forEach { (sessionId, samples) ->
            dataBySession[sessionId]?.locationSamples = samples
        }
        readTelemetrySamplesBySession(db, ids, dataBySession)
        // User split points only ever apply to gear-aware sessions, so legacy sessions skip the read.
        val gearAwareIds = sessions.filter { TripSplitRules.appliesTo(it.tripRulesVersion) }.map { it.id }.distinct()
        ObdTripSplits.activeSplitsBySession(db, gearAwareIds).forEach { (sessionId, spans) ->
            dataBySession[sessionId]?.userSplits = spans
        }
        return dataBySession
    }

    private fun applyBounds(
        dataBySession: Map<Long, SessionData>,
        boundsBySession: Map<Long, DataBounds>,
        apply: (SessionData, DataBounds) -> Unit,
    ) {
        for ((sessionId, bounds) in boundsBySession) {
            dataBySession[sessionId]?.let { apply(it, bounds) }
        }
    }

    private fun readBoundsBySession(
        db: SQLiteDatabase,
        table: String,
        sessionIds: List<Long>,
        extraWhere: String,
    ): Map<Long, DataBounds> {
        val selection = sessionSelection(sessionIds)
        val boundsBySession = LinkedHashMap<Long, DataBounds>()
        db
            .rawQuery(
                "SELECT session_id, MIN(captured_at_ms), MAX(captured_at_ms) " +
                    "FROM $table WHERE session_id IN (${selection.placeholders}) $extraWhere GROUP BY session_id",
                selection.args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(1) && !cursor.isNull(2)) {
                        boundsBySession[cursor.getLong(0)] = DataBounds(cursor.getLong(1), cursor.getLong(2))
                    }
                }
            }
        return boundsBySession
    }

    /**
     * One scan of telemetry per detection computing both bounds variants: all rows and rows that
     * carry GPS. Replaces what used to be two separate aggregate queries over the same table.
     */
    private fun readTelemetryBoundsBySession(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
        dataBySession: Map<Long, SessionData>,
    ) {
        val selection = sessionSelection(sessionIds)
        db
            .rawQuery(
                "SELECT session_id, MIN(captured_at_ms), MAX(captured_at_ms), " +
                    "MIN(CASE WHEN $GEO_TELEMETRY_WHERE THEN captured_at_ms END), " +
                    "MAX(CASE WHEN $GEO_TELEMETRY_WHERE THEN captured_at_ms END) " +
                    "FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id IN (${selection.placeholders}) GROUP BY session_id",
                selection.args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val data = dataBySession[cursor.getLong(0)] ?: continue
                    if (!cursor.isNull(1) && !cursor.isNull(2)) {
                        data.telemetryBounds = DataBounds(cursor.getLong(1), cursor.getLong(2))
                    }
                    if (!cursor.isNull(3) && !cursor.isNull(4)) {
                        data.geoTelemetryBounds = DataBounds(cursor.getLong(3), cursor.getLong(4))
                    }
                }
            }
    }

    private fun readLocationSamplesBySession(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Map<Long, List<RouteSample>> {
        val selection = sessionSelection(sessionIds)
        val samplesBySession = LinkedHashMap<Long, MutableList<RouteSample>>()
        db
            .rawQuery(
                "SELECT session_id, captured_at_ms, latitude, longitude " +
                    "FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES} " +
                    "WHERE session_id IN (${selection.placeholders}) " +
                    "ORDER BY session_id DESC, captured_at_ms ASC LIMIT $MAX_SAMPLE_ROWS",
                selection.args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    samplesBySession
                        .getOrPut(cursor.getLong(0)) { mutableListOf() }
                        .add(RouteSample(cursor.getLong(1), cursor.getDouble(2), cursor.getDouble(3)))
                }
            }
        return samplesBySession
    }

    /**
     * One scan of telemetry rows building both the activity samples (every row) and the
     * telemetry-derived route samples (rows that carry GPS). Replaces two separate full reads of
     * the same table.
     */
    private fun readTelemetrySamplesBySession(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
        dataBySession: Map<Long, SessionData>,
    ) {
        val selection = sessionSelection(sessionIds)
        val activityBySession = LinkedHashMap<Long, MutableList<ActivitySample>>()
        val routeBySession = LinkedHashMap<Long, MutableList<RouteSample>>()
        db
            .rawQuery(
                "SELECT session_id, captured_at_ms, speed_kph, rpm, voltage, power_kw, pack_current_a, " +
                    "latitude, longitude, prndl_raw, door_open " +
                    "FROM ${VoltTrackerDb.TABLE_TELEMETRY} WHERE session_id IN (${selection.placeholders}) " +
                    "ORDER BY session_id DESC, captured_at_ms ASC LIMIT $MAX_SAMPLE_ROWS",
                selection.args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val sessionId = cursor.getLong(0)
                    val atMs = cursor.getLong(1)
                    activityBySession
                        .getOrPut(sessionId) { mutableListOf() }
                        .add(
                            ActivitySample(
                                atMs,
                                nullableInt(cursor, "speed_kph"),
                                nullableInt(cursor, "rpm"),
                                nullableDouble(cursor, "voltage"),
                                nullableDouble(cursor, "power_kw"),
                                nullableDouble(cursor, "pack_current_a"),
                                nullableInt(cursor, "prndl_raw"),
                                nullableInt(cursor, "door_open")?.let { it != 0 },
                            ),
                        )
                    if (!cursor.isNull(7) && !cursor.isNull(8)) {
                        routeBySession
                            .getOrPut(sessionId) { mutableListOf() }
                            .add(RouteSample(atMs, cursor.getDouble(7), cursor.getDouble(8)))
                    }
                }
            }
        activityBySession.forEach { (sessionId, samples) ->
            dataBySession[sessionId]?.activitySamples = samples
        }
        routeBySession.forEach { (sessionId, samples) ->
            dataBySession[sessionId]?.telemetryRouteSamples = samples
        }
    }

    private fun sessionSelection(sessionIds: List<Long>): SessionSelection =
        SessionSelection(
            sessionIds.joinToString(",") { "?" },
            sessionIds.map { it.toString() }.toTypedArray(),
        )

    private fun haversineMeters(
        a: RouteSample,
        b: RouteSample,
    ): Double {
        val earthMeters = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val x =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(Math.toRadians(a.lat)) *
                cos(Math.toRadians(b.lat)) *
                sin(dLng / 2.0) *
                sin(dLng / 2.0)
        return earthMeters * 2.0 * atan2(sqrt(x), sqrt(1.0 - x))
    }

    class DriveWindow(
        @JvmField val sessionId: Long,
        @JvmField val index: Int,
        @JvmField val startedAtMs: Long,
        @JvmField val endedAtMs: Long,
    ) {
        fun durationMs(): Long = max(0L, endedAtMs - startedAtMs)

        fun routeKey(): String = String.format(Locale.US, "%d:%d:%d", sessionId, startedAtMs, endedAtMs)
    }

    class RouteKey(
        @JvmField val sessionId: Long,
        @JvmField val startedAtMs: Long?,
        @JvmField val endedAtMs: Long?,
    )

    private class SplitSpan(
        val startMs: Long,
        val endMs: Long,
    )

    private class RouteSample(
        val atMs: Long,
        val lat: Double,
        val lng: Double,
    )

    private class ActivitySample(
        val atMs: Long,
        val speedKph: Int?,
        val rpm: Int?,
        val voltage: Double?,
        val powerKw: Double?,
        val packCurrentA: Double?,
        val prndlRaw: Int?,
        val doorOpen: Boolean?,
    )

    private class DataBounds(
        val firstMs: Long,
        val lastMs: Long,
    )

    private class SessionData(
        var locationBounds: DataBounds? = null,
        var geoTelemetryBounds: DataBounds? = null,
        var telemetryBounds: DataBounds? = null,
        var locationSamples: List<RouteSample> = emptyList(),
        var telemetryRouteSamples: List<RouteSample> = emptyList(),
        var activitySamples: List<ActivitySample> = emptyList(),
        var userSplits: List<TripSplitRules.Span> = emptyList(),
    )

    private class SessionSelection(
        val placeholders: String,
        val args: Array<String>,
    )
}
