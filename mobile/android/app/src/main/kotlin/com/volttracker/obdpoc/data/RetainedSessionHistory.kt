package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Durable inputs for rebuilding projections after raw retention. Unlike the projection caches,
 * these facts are never invalidated by edits, cache-version changes or backup merge. Trip visibility
 * remains an event overlay; the pre-expiry charge contribution remains historical evidence.
 */
internal object RetainedSessionHistory {
    private const val TABLE = VoltTrackerDb.TABLE_RETAINED_SESSION_HISTORY
    private const val EMPTY_CHARGE = "{\"rows\":[],\"boundaries\":[]}"

    fun trips(
        db: SQLiteDatabase,
        sessionId: Long,
    ): List<JSONObject>? =
        readColumn(db, sessionId, "trip_json")?.let { raw ->
            checkedJson {
                val array = JSONArray(raw)
                (0 until array.length()).map { array.getJSONObject(it) }
            }
        }

    fun charge(
        db: SQLiteDatabase,
        sessionId: Long,
    ): String? = readColumn(db, sessionId, "charge_json")

    /** Save every finalized session touched by expiry, including one spanning the cutoff. */
    fun preserveBeforePrune(
        db: SQLiteDatabase,
        cutoffMs: Long,
        trips: ObdStoreTrips,
    ) {
        preserveLegacySummaries(db)
        val sessions = ArrayList<ObdSessionRecord>()
        db
            .rawQuery(
                "SELECT s.* FROM ${VoltTrackerDb.TABLE_SESSIONS} s WHERE s.ended_at_ms > 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM $TABLE r WHERE r.session_id = s._id) " +
                    "AND (EXISTS (SELECT 1 FROM ${VoltTrackerDb.TABLE_TELEMETRY} t " +
                    "WHERE t.session_id = s._id AND t.captured_at_ms < ?) " +
                    "OR EXISTS (SELECT 1 FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES} l " +
                    "WHERE l.session_id = s._id AND l.captured_at_ms < ?))",
                arrayOf(cutoffMs.toString(), cutoffMs.toString()),
            ).use { c -> while (c.moveToNext()) sessions.add(ObdStoreSupport.readSession(c)) }
        for (session in sessions) {
            db.transaction {
                val facts = JSONArray()
                trips.tripFactsForRetention(db, session).forEach { facts.put(it) }
                insert(
                    db,
                    session.id,
                    System.currentTimeMillis(),
                    facts.toString(),
                    ObdStoreChargeSummary.contributionForRetention(db, session),
                )
            }
        }
    }

    /** Backfill existing retained caches when upgrading a database whose raw rows already expired. */
    fun preserveLegacySummaries(db: SQLiteDatabase) {
        if (!tableExists(db, VoltTrackerDb.TABLE_SESSIONS) ||
            !tableExists(db, VoltTrackerDb.TABLE_TRIP_LIST_CACHE) ||
            !tableExists(db, VoltTrackerDb.TABLE_TELEMETRY) ||
            !tableExists(db, VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS) ||
            !hasSessionCounters(db)
        ) {
            return
        }
        val ids = ArrayList<Long>()
        db
            .rawQuery(
                "SELECT s._id FROM ${VoltTrackerDb.TABLE_SESSIONS} s WHERE s.ended_at_ms > 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM $TABLE r WHERE r.session_id = s._id) " +
                    "AND s.sample_count > (SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY} t WHERE t.session_id = s._id)",
                null,
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        for (id in ids) legacyRecord(db, id)?.let { insert(db, id, System.currentTimeMillis(), it.first, it.second) }
    }

    /** Existing target facts win; a duplicate/older backup must not replace the only durable inputs. */
    fun merge(
        db: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
    ): Long {
        var imported = 0L
        for ((from, to) in sessionMap) {
            if (readColumn(db, to, "trip_json") != null) continue
            val record =
                if (tableExists(donor, TABLE)) {
                    readColumn(donor, from, "trip_json")?.let { it to checkNotNull(charge(donor, from)) }
                } else {
                    null
                }
            val facts = record ?: legacyRecord(donor, from)
            if (facts == null) continue
            checkedJson {
                insert(
                    db,
                    to,
                    System.currentTimeMillis(),
                    remapJson(JSONArray(facts.first), sessionMap).toString(),
                    remapJson(JSONObject(facts.second), sessionMap).toString(),
                )
            }
            imported++
        }
        return imported
    }

    private fun legacyRecord(
        db: SQLiteDatabase,
        id: Long,
    ): Pair<String, String>? {
        // A donor without the new table may already have been pruned by an older version. Only
        // promote its caches when the persisted sample count proves the raw source is incomplete.
        if (!tableExists(db, VoltTrackerDb.TABLE_TRIP_LIST_CACHE) ||
            !tableExists(db, VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS) ||
            !tableExists(db, VoltTrackerDb.TABLE_SESSIONS) ||
            !tableExists(db, VoltTrackerDb.TABLE_TELEMETRY) ||
            !hasSessionCounters(db)
        ) {
            return null
        }
        val incomplete =
            db
                .rawQuery(
                    "SELECT 1 FROM ${VoltTrackerDb.TABLE_SESSIONS} s WHERE s._id = ? AND s.ended_at_ms > 0 " +
                        "AND s.sample_count > (SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY} t WHERE t.session_id = s._id)",
                    arrayOf(id.toString()),
                ).use { it.moveToFirst() }
        if (!incomplete) return null
        val array = JSONArray()
        db
            .rawQuery(
                "SELECT trip_json FROM ${VoltTrackerDb.TABLE_TRIP_LIST_CACHE} WHERE session_id = ? ORDER BY ended_at_ms",
                arrayOf(id.toString()),
            ).use { c ->
                while (c.moveToNext()) checkedJson { array.put(JSONObject(c.getString(0))) }
            }
        val charge =
            db
                .rawQuery(
                    "SELECT charge_json FROM ${VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS} WHERE session_id = ?",
                    arrayOf(id.toString()),
                ).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        if (array.length() == 0 && charge == null) return null
        return array.toString() to (charge ?: EMPTY_CHARGE)
    }

    // Corrupt durable inputs must abort a transaction rather than be dropped like a cache.
    // Normalize the checked JSON exception to the database operation's failure contract.
    private inline fun <T> checkedJson(read: () -> T): T =
        try {
            read()
        } catch (ex: JSONException) {
            throw IllegalStateException("Invalid retained history", ex)
        }

    private fun insert(
        db: SQLiteDatabase,
        id: Long,
        at: Long,
        trips: String,
        charge: String,
    ) {
        db.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("session_id", id)
                put("retained_at_ms", at)
                put("trip_json", trips)
                put("charge_json", charge)
            },
        )
    }

    private fun readColumn(
        db: SQLiteDatabase,
        id: Long,
        column: String,
    ): String? =
        db.rawQuery("SELECT $column FROM $TABLE WHERE session_id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun tableExists(
        db: SQLiteDatabase,
        table: String,
    ): Boolean =
        db
            .rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { it.moveToFirst() }

    private fun hasSessionCounters(db: SQLiteDatabase): Boolean {
        val columns = HashSet<String>()
        db.rawQuery("PRAGMA table_info(${VoltTrackerDb.TABLE_SESSIONS})", null).use { c ->
            while (c.moveToNext()) columns.add(c.getString(1))
        }
        return columns.containsAll(listOf("_id", "ended_at_ms", "sample_count"))
    }

    private fun remapJson(
        value: Any,
        sessions: Map<Long, Long>,
    ): Any {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) value.put(i, remapJson(value.get(i), sessions))
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                for (key in keys) {
                    val child = value.get(key)
                    when {
                        key == "sessionId" && child is Number -> value.put(key, sessions[child.toLong()] ?: child)
                        key == "id" && child is String -> value.put(key, remapId(child, sessions))
                        else -> value.put(key, remapJson(child, sessions))
                    }
                }
            }
        }
        return value
    }

    private fun remapId(
        id: String,
        sessions: Map<Long, Long>,
    ): String {
        if (!id.startsWith("inferred:")) return TripEditRemapper.remapRouteKey(id, sessions) ?: id
        val parts = id.split(':').toMutableList()
        if (parts.size != 5) return id
        for (i in listOf(1, 3)) parts[i].toLongOrNull()?.let { old -> sessions[old]?.let { parts[i] = it.toString() } }
        return parts.joinToString(":")
    }
}
