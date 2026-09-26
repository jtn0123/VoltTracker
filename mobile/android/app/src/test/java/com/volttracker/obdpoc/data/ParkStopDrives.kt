package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.materialize.TripSplitRules
import org.json.JSONObject

/**
 * Records synthetic drives with a Park stretch through the real [ObdLocalStore] writer, for the
 * gear-aware trip-split DB tests: 30-second GPS + telemetry samples, driving at 45 km/h drawing
 * 11 kW, parked (gear P, 0 km/h) drawing 2 kW of climate.
 */
internal class ParkStopDrives(
    private val store: ObdLocalStore,
) {
    fun record(
        baseMs: Long,
        parkFromMinute: Int,
        parkToMinute: Int,
        endMinute: Int,
        gear: Boolean = true,
        legacy: Boolean = false,
    ): Long {
        val id = store.startSession("obd", "00:11", "Adapter", baseMs)
        // A session recorded before the cutover: the rules version is fixed when it starts.
        if (legacy) markLegacy(id)
        var lat = 34.05
        var atMs = baseMs
        val endMs = baseMs + endMinute * 60_000L
        while (atMs < endMs) {
            val parked = atMs >= baseMs + parkFromMinute * 60_000L && atMs < baseMs + parkToMinute * 60_000L
            if (!parked) lat += 0.0015
            val raw =
                when {
                    !gear -> null
                    parked -> PARK
                    else -> DRIVE
                }
            store.recordLocationSample(id, atMs, "gps", lat, -118.25, 5.0, null, null, null, null, null)
            store.recordTelemetry(
                id,
                row(atMs, if (parked) 0 else 45, raw)
                    .put("latitude", lat)
                    .put("longitude", -118.25)
                    .put("powerKw", if (parked) 2.0 else 11.0),
            )
            atMs += HALF_MINUTE_MS
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, endMs - HALF_MINUTE_MS, "")
        return id
    }

    fun row(
        atMs: Long,
        speedKph: Int,
        prndlRaw: Int?,
        staleMs: Long = 0L,
    ): JSONObject {
        val sample = JSONObject()
        sample.put("source", "obd")
        sample.put("updatedAt", atMs)
        sample.put("speedKph", speedKph)
        sample.put("voltage", 14.1)
        if (prndlRaw != null) {
            sample.put("prndlRaw", prndlRaw)
            sample.put("prndlStateStaleMs", staleMs)
        }
        return sample
    }

    fun markLegacy(sessionId: Long) {
        withDb { db ->
            val cv = ContentValues()
            cv.put("trip_rules_version", TripSplitRules.LEGACY)
            db.update(VoltTrackerDb.TABLE_SESSIONS, cv, "_id = ?", arrayOf(sessionId.toString()))
        }
        check(session(sessionId).tripRulesVersion == TripSplitRules.LEGACY) { "session $sessionId not legacy" }
    }

    fun session(sessionId: Long): ObdSessionRecord = checkNotNull(store.getSession(sessionId))

    fun windows(sessionId: Long): List<DriveWindowDetector.DriveWindow> =
        withDb { db -> DriveWindowDetector.windowsForSession(db, session(sessionId)) }

    fun <T> withDb(block: (SQLiteDatabase) -> T): T {
        store.checkpoint()
        return SQLiteDatabase
            .openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READWRITE)
            .use(block)
    }

    companion object {
        const val PARK = 8
        const val DRIVE = 3
        const val HALF_MINUTE_MS = 30_000L
        const val BASE_MS = 1_780_000_000_000L

        fun minute(m: Int): Long = BASE_MS + m * 60_000L
    }
}
