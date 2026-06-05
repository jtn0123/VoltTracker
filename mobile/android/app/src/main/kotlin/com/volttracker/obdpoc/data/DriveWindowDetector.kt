package com.volttracker.obdpoc.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.VehicleActivityThresholds
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

    @JvmStatic
    fun windowsForSession(
        db: SQLiteDatabase?,
        session: ObdSessionRecord?,
    ): List<DriveWindow> {
        if (db == null || session == null) {
            return emptyList()
        }
        val bounds = dataBounds(db, session.id)
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
        val spans = splitSpans(db, session.id)
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

    private fun splitSpans(
        db: SQLiteDatabase,
        sessionId: Long,
    ): List<SplitSpan> {
        val spans = mutableListOf<SplitSpan>()
        spans.addAll(gpsStopSpans(db, sessionId))
        spans.addAll(inactiveTelemetrySpans(db, sessionId))
        return spans
    }

    private fun gpsStopSpans(
        db: SQLiteDatabase,
        sessionId: Long,
    ): List<SplitSpan> {
        val samples = readRouteSamples(db, sessionId)
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
                addStopSpan(db, sessionId, spans, samples, runStart, i - 1)
                runStart = -1
            }
        }
        if (runStart >= 0) {
            addStopSpan(db, sessionId, spans, samples, runStart, samples.lastIndex)
        }
        return spans
    }

    private fun addStopSpan(
        db: SQLiteDatabase,
        sessionId: Long,
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
            !hasActiveTelemetryInSpan(db, sessionId, stoppedAtMs, endMs)
        ) {
            spans.add(SplitSpan(startMs, endMs))
        }
    }

    private fun dataBounds(
        db: SQLiteDatabase,
        sessionId: Long,
    ): DataBounds? {
        val route = boundsFor(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, "session_id = ?", sessionId)
        val geoTelemetry =
            boundsFor(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL",
                sessionId,
            )
        if (geoTelemetry != null) {
            return mergeBounds(route, geoTelemetry)
        }
        return mergeBounds(
            route,
            boundsFor(db, VoltTrackerDb.TABLE_TELEMETRY, "session_id = ?", sessionId),
        )
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

    private fun boundsFor(
        db: SQLiteDatabase,
        table: String,
        where: String,
        sessionId: Long,
    ): DataBounds? {
        db
            .rawQuery(
                "SELECT MIN(captured_at_ms), MAX(captured_at_ms) FROM $table WHERE $where",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) {
                    return null
                }
                return DataBounds(cursor.getLong(0), cursor.getLong(1))
            }
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

    private fun readRouteSamples(
        db: SQLiteDatabase,
        sessionId: Long,
    ): List<RouteSample> {
        val locationSamples =
            readRouteSamplesFromTable(
                db,
                VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                "session_id = ?",
                arrayOf(sessionId.toString()),
            )
        val telemetrySamples =
            readRouteSamplesFromTable(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL",
                arrayOf(sessionId.toString()),
            )
        return if (locationSamples.size >= telemetrySamples.size) {
            locationSamples
        } else {
            telemetrySamples
        }
    }

    private fun readRouteSamplesFromTable(
        db: SQLiteDatabase,
        table: String,
        where: String,
        args: Array<String>,
    ): List<RouteSample> {
        val samples = mutableListOf<RouteSample>()
        db
            .query(
                table,
                arrayOf("captured_at_ms", "latitude", "longitude"),
                where,
                args,
                null,
                null,
                "captured_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    samples.add(
                        RouteSample(
                            cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                            cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                            cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                        ),
                    )
                }
            }
        return samples
    }

    private fun inactiveTelemetrySpans(
        db: SQLiteDatabase,
        sessionId: Long,
    ): List<SplitSpan> {
        val spans = mutableListOf<SplitSpan>()
        var inactiveStartMs = -1L
        var inactiveEndMs = -1L
        db
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf("captured_at_ms", "speed_kph", "rpm", "voltage", "power_kw", "pack_current_a"),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val atMs = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms"))
                    if (isActiveVehicleSample(cursor)) {
                        addInactiveSpan(spans, inactiveStartMs, inactiveEndMs)
                        inactiveStartMs = -1L
                        inactiveEndMs = -1L
                    } else {
                        if (inactiveStartMs < 0L) {
                            inactiveStartMs = atMs
                        }
                        inactiveEndMs = atMs
                    }
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
        db: SQLiteDatabase,
        sessionId: Long,
        startMs: Long,
        endMs: Long,
    ): Boolean {
        db
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf("speed_kph", "rpm", "voltage", "power_kw", "pack_current_a"),
                "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ?",
                arrayOf(sessionId.toString(), startMs.toString(), endMs.toString()),
                null,
                null,
                "captured_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (isActiveVehicleSample(cursor)) {
                        return true
                    }
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

    private fun isActiveVehicleSample(cursor: Cursor): Boolean {
        val speedKph = nullableInt(cursor, "speed_kph")
        if (speedKph != null && speedKph > VehicleActivityThresholds.MOVING_SPEED_KPH) {
            return true
        }
        val rpm = nullableInt(cursor, "rpm")
        if (rpm != null && rpm > VehicleActivityThresholds.ENGINE_READY_RPM) {
            return true
        }
        val voltage = nullableDouble(cursor, "voltage")
        if (voltage != null && voltage > VehicleActivityThresholds.READY_VOLTAGE) {
            return true
        }
        val powerKw = nullableDouble(cursor, "power_kw")
        if (powerKw != null && abs(powerKw) > VehicleActivityThresholds.ACTIVE_POWER_KW) {
            return true
        }
        val packCurrentA = nullableDouble(cursor, "pack_current_a")
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

    private class DataBounds(
        val firstMs: Long,
        val lastMs: Long,
    )
}
