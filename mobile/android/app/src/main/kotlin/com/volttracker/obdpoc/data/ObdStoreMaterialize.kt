package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.Cursor
import androidx.core.database.sqlite.transaction
import com.volttracker.obdpoc.materialize.ChargeSession
import com.volttracker.obdpoc.materialize.LocationSample
import com.volttracker.obdpoc.materialize.PidObservation
import com.volttracker.obdpoc.materialize.TelemetrySample
import com.volttracker.obdpoc.materialize.Trip
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections

/** Read and write paths used exclusively by the session materializers. */
class ObdStoreMaterialize(
    private val helper: VoltTrackerDb,
) {
    fun readLocationSamples(sessionId: Long): List<LocationSample> {
        val rows = ArrayList<LocationSample>()
        val db = helper.readableDatabase
        db
            .query(
                VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                arrayOf("captured_at_ms", "latitude", "longitude", "speed_mps", "accuracy_m"),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude"))
                    val longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))
                    if (!ObdStoreSupport.validLatLng(latitude, longitude)) {
                        continue
                    }
                    rows.add(
                        LocationSample(
                            cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                            latitude,
                            longitude,
                            nullableDouble(cursor, "speed_mps"),
                            nullableFloat(cursor, "accuracy_m"),
                        ),
                    )
                }
            }
        return Collections.unmodifiableList(rows)
    }

    fun readPidObservations(sessionId: Long): List<PidObservation> {
        val rows = ArrayList<PidObservation>()
        val db = helper.readableDatabase
        db
            .query(
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                arrayOf("observed_at_ms", "command", "header", "raw_response", "value_numeric"),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "observed_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        PidObservation(
                            cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")),
                            cursor.getString(cursor.getColumnIndexOrThrow("command")),
                            cursor.getString(cursor.getColumnIndexOrThrow("header")),
                            cursor.getString(cursor.getColumnIndexOrThrow("raw_response")),
                            null,
                            nullableDouble(cursor, "value_numeric"),
                        ),
                    )
                }
            }
        return Collections.unmodifiableList(rows)
    }

    fun readTelemetrySamples(sessionId: Long): List<TelemetrySample> {
        val rows = ArrayList<TelemetrySample>()
        val db = helper.readableDatabase
        db
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf(
                    "captured_at_ms",
                    "speed_kph",
                    "rpm",
                    "voltage",
                    "pack_voltage",
                    "pack_current_a",
                    "power_kw",
                    "soc",
                ),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val speed = nullableInt(cursor, "speed_kph")
                    rows.add(
                        TelemetrySample(
                            cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                            speed?.toDouble(),
                            nullableInt(cursor, "rpm"),
                            nullableDouble(cursor, "voltage"),
                            nullableDouble(cursor, "pack_voltage"),
                            nullableDouble(cursor, "pack_current_a"),
                            nullableDouble(cursor, "power_kw"),
                            nullableDouble(cursor, "soc"),
                        ),
                    )
                }
            }
        return Collections.unmodifiableList(rows)
    }

    fun persistTrips(
        sessionId: Long,
        trips: List<Trip>?,
    ) {
        if (trips.isNullOrEmpty()) {
            return
        }
        val createdAt = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.transaction {
            for (trip in trips) {
                val values = ContentValues()
                values.put("session_id", sessionId)
                values.put("started_at_ms", trip.startedAtMs)
                values.put("ended_at_ms", trip.endedAtMs)
                values.put("route_available", if (trip.hasRoute) 1 else 0)
                values.put("distance_m", trip.distanceMeters)
                values.put("max_speed_kph", trip.maxSpeedKph)
                if (trip.durationMs > 0L) {
                    values.put("avg_speed_kph", (trip.distanceMeters / 1000.0) / (trip.durationMs / 3_600_000.0))
                }
                values.put("classification", trip.classification)
                values.put("confidence", trip.confidence.asScore())
                if (trip.energyKwh != null) {
                    values.put("energy_kwh", trip.energyKwh)
                }
                values.put("created_at_ms", createdAt)
                db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, values)
            }
        }
    }

    fun persistChargeSessions(
        sessionId: Long,
        sessions: List<ChargeSession>?,
    ) {
        if (sessions.isNullOrEmpty()) {
            return
        }
        val createdAt = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.transaction {
            for (session in sessions) {
                val values = ContentValues()
                values.put("session_id", sessionId)
                values.put("started_at_ms", session.startedAtMs)
                values.put("ended_at_ms", session.endedAtMs)
                values.put("charger_type", session.chargerType)
                values.put("interrupted", if (session.interruptionCount > 0) 1 else 0)
                values.put("confidence", session.confidence.asScore())
                if (isFinite(session.voltageStart)) {
                    values.put("voltage", session.voltageStart)
                }
                if (isFinite(session.startSoc)) {
                    values.put("start_soc", session.startSoc)
                }
                if (isFinite(session.endSoc)) {
                    values.put("end_soc", session.endSoc)
                }
                if (isFinite(session.peakPowerKw)) {
                    values.put("power_kw", session.peakPowerKw)
                }
                if (isFinite(session.energyKwh)) {
                    values.put("energy_kwh", session.energyKwh)
                }
                values.put("created_at_ms", createdAt)
                try {
                    val summary = JSONObject()
                    summary.put("interruptionCount", session.interruptionCount)
                    summary.put("confidence", session.confidence.name)
                    putDoubleIfFinite(summary, "voltageStart", session.voltageStart)
                    putDoubleIfFinite(summary, "voltageEnd", session.voltageEnd)
                    summary.put("durationMs", session.durationMs)
                    values.put("summary_json", summary.toString())
                } catch (ex: JSONException) {
                    values.put("summary_json", "{}")
                }
                db.insertOrThrow(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, values)
            }
        }
    }

    private fun isFinite(value: Double?): Boolean = value != null && !value.isNaN() && !value.isInfinite()

    @Throws(JSONException::class)
    private fun putDoubleIfFinite(
        target: JSONObject,
        key: String,
        value: Double?,
    ) {
        if (isFinite(value)) {
            target.put(key, value)
        } else {
            target.put(key, JSONObject.NULL)
        }
    }

    private fun nullableDouble(
        cursor: Cursor,
        column: String,
    ): Double? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getDouble(index).takeIf { it.isFinite() }
    }

    private fun nullableInt(
        cursor: Cursor,
        column: String,
    ): Int? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getInt(index)
    }

    private fun nullableFloat(
        cursor: Cursor,
        column: String,
    ): Float? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getFloat(index).takeIf { it.isFinite() }
    }
}
