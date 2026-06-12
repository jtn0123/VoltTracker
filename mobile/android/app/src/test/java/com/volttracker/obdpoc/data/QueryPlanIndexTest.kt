package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Proves that the read-side hot-path queries used by [ObdStoreReports], [ObdStoreTrips],
 * and [ObdStoreSnapshots] are backed by indexes — they SEARCH USING INDEX
 * rather than SCAN TABLE. Seeds realistic row counts (50 sessions, 500+ telemetry/location/event
 * rows) so SQLite's planner doesn't pick a table scan just because the table is tiny.
 *
 * If a future query is added that scans a hot path, the corresponding assertion here will fail
 * with a plan dump pointing at the table that needs an index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueryPlanIndexTest {
    private lateinit var store: ObdLocalStore
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        store.clearAllData()
        db = openWritableDb(store)
        // ANALYZE after seeding so the planner has stats; without this on very small tables SQLite
        // may still prefer a scan. We seed first, then ANALYZE.
        seedRealisticData()
        db.execSQL("ANALYZE")
    }

    @After
    fun tearDown() {
        store.close()
    }

    // ---- recent-sessions list (storage summary, recent routes, etc.) -----------------

    @Test
    fun recentSessions_usesStartedAtIndex() {
        // SELECT * FROM obd_sessions ORDER BY started_at_ms DESC LIMIT ?
        val sql =
            "SELECT * FROM " +
                VoltTrackerDb.TABLE_SESSIONS +
                " ORDER BY started_at_ms DESC LIMIT 10"
        assertUsesIndex(sql, emptyArray(), "idx_sessions_started")
    }

    // ---- telemetry by session (session detail, trips, speed trace) -------------------

    @Test
    fun recentTelemetryBySession_usesSessionTimeIndex() {
        // SELECT ... FROM telemetry_samples WHERE session_id = ? ORDER BY captured_at_ms DESC LIMIT
        val sql =
            "SELECT captured_at_ms, speed_kph FROM " +
                VoltTrackerDb.TABLE_TELEMETRY +
                " WHERE session_id = ? ORDER BY captured_at_ms DESC LIMIT 48"
        assertUsesIndex(sql, arrayOf("1"), "idx_telemetry_session_time")
    }

    // ---- locations by session (map render) -------------------------------------------

    @Test
    fun locationSamplesBySession_usesSessionTimeIndex() {
        val sql =
            "SELECT captured_at_ms, latitude, longitude FROM " +
                VoltTrackerDb.TABLE_LOCATION_SAMPLES +
                " WHERE session_id = ? ORDER BY captured_at_ms DESC LIMIT 240"
        assertUsesIndex(sql, arrayOf("1"), "idx_location_samples_session_time")
    }

    // ---- pid observations by session (review screen) ---------------------------------

    @Test
    fun pidObservationsBySession_usesSessionTimeIndex() {
        val sql =
            "SELECT observed_at_ms, command, pid FROM " +
                VoltTrackerDb.TABLE_PID_OBSERVATIONS +
                " WHERE session_id = ? ORDER BY observed_at_ms DESC LIMIT 20"
        assertUsesIndex(sql, arrayOf("1"), "idx_pid_observations_session_time")
    }

    @Test
    fun pidObservationsByCommandHeader_usesCommandIndex() {
        // The secondary lookup pattern: most recent observation for a given command/header.
        val sql =
            "SELECT observed_at_ms FROM " +
                VoltTrackerDb.TABLE_PID_OBSERVATIONS +
                " WHERE command = ? AND header = ? ORDER BY observed_at_ms DESC LIMIT 1"
        assertUsesIndex(sql, arrayOf("010C", "7E0"), "idx_pid_observations_command_header")
    }

    // ---- events by session (recent events) -------------------------------------------

    @Test
    fun eventsBySession_usesSessionTimeIndex() {
        val sql =
            "SELECT occurred_at_ms, kind FROM " +
                VoltTrackerDb.TABLE_EVENTS +
                " WHERE session_id = ? ORDER BY occurred_at_ms DESC LIMIT 20"
        assertUsesIndex(sql, arrayOf("1"), "idx_events_session_time")
    }

    // ---- adapter history (key lookup, list) ------------------------------------------

    @Test
    fun adapterHistoryByKey_usesPrimaryKey() {
        // adapter_key is the PRIMARY KEY (TEXT) — SQLite auto-builds an index for it.
        val sql =
            "SELECT * FROM " + VoltTrackerDb.TABLE_ADAPTER_HISTORY + " WHERE adapter_key = ?"
        // PK lookup shows up as either "USING INDEX sqlite_autoindex_..." or as a covering
        // PK search — accept either by checking it isn't a full SCAN of the table.
        assertNotFullTableScan(sql, arrayOf("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun adapterHistoryList_usesLastSeenIndex() {
        val sql =
            "SELECT * FROM " +
                VoltTrackerDb.TABLE_ADAPTER_HISTORY +
                " ORDER BY last_seen_ms DESC LIMIT 6"
        assertUsesIndex(sql, emptyArray(), "idx_adapter_history_seen")
    }

    // ---- materializer reads (oldest-first, session-scoped) ---------------------------

    @Test
    fun materializerTelemetryRead_usesSessionTimeIndex() {
        // ObdStoreMaterialize.readTelemetrySamples shape: oldest-first, no LIMIT.
        val sql =
            "SELECT captured_at_ms, speed_kph, rpm, voltage, pack_voltage, pack_current_a," +
                " power_kw, soc FROM " +
                VoltTrackerDb.TABLE_TELEMETRY +
                " WHERE session_id = ? ORDER BY captured_at_ms ASC"
        assertUsesIndex(sql, arrayOf("1"), "idx_telemetry_session_time")
    }

    @Test
    fun materializerLocationRead_usesSessionTimeIndex() {
        // ObdStoreMaterialize.readLocationSamples shape.
        val sql =
            "SELECT captured_at_ms, latitude, longitude, speed_mps, accuracy_m FROM " +
                VoltTrackerDb.TABLE_LOCATION_SAMPLES +
                " WHERE session_id = ? ORDER BY captured_at_ms ASC"
        assertUsesIndex(sql, arrayOf("1"), "idx_location_samples_session_time")
    }

    @Test
    fun materializerPidRead_usesSessionTimeIndex() {
        // ObdStoreMaterialize.readPidObservations shape.
        val sql =
            "SELECT observed_at_ms, command, header, raw_response, value_numeric FROM " +
                VoltTrackerDb.TABLE_PID_OBSERVATIONS +
                " WHERE session_id = ? ORDER BY observed_at_ms ASC"
        assertUsesIndex(sql, arrayOf("1"), "idx_pid_observations_session_time")
    }

    // ---- route-downsample reads (Trips/Map, ObdStoreTrips#routePointsForSessionJson) --
    // These back the Drive/Map route render and are the hot path G1 reworks. The
    // session-scoped, oldest-first scan must stay index-bound as history grows.

    @Test
    fun routeDownsampleLocationRead_usesSessionTimeIndex() {
        // ObdStoreTrips#downsampledRoutePoints over location_samples.
        val sql =
            "SELECT captured_at_ms, latitude, longitude, accuracy_m, speed_mps, bearing_deg," +
                " altitude_m FROM " +
                VoltTrackerDb.TABLE_LOCATION_SAMPLES +
                " WHERE session_id = ? ORDER BY captured_at_ms ASC"
        assertUsesIndex(sql, arrayOf("1"), "idx_location_samples_session_time")
    }

    @Test
    fun routeDownsampleTelemetryFallbackRead_usesSessionTimeIndex() {
        // Telemetry fallback when a session has no GPS fixes. The extra
        // "latitude/longitude IS NOT NULL" predicate is a residual filter; the
        // session_id equality + captured_at_ms ordering must still ride the index.
        val sql =
            "SELECT captured_at_ms, latitude, longitude, accuracy_m, gps_speed_mps, bearing_deg," +
                " soc FROM " +
                VoltTrackerDb.TABLE_TELEMETRY +
                " WHERE session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL" +
                " ORDER BY captured_at_ms ASC"
        assertUsesIndex(sql, arrayOf("1"), "idx_telemetry_session_time")
    }

    // ---- prune-by-time deletes (background maintenance) ------------------------------

    @Test
    fun prune_telemetryByTime_usesNewTimeIndex() {
        // DELETE FROM telemetry_samples WHERE captured_at_ms < ? — composite session/time index
        // can't help here because session_id isn't constrained. idx_telemetry_captured_at backs it.
        val sql =
            "SELECT _id FROM " + VoltTrackerDb.TABLE_TELEMETRY + " WHERE captured_at_ms < ?"
        assertUsesIndex(sql, arrayOf("0"), "idx_telemetry_captured_at")
    }

    @Test
    fun prune_locationByTime_usesNewTimeIndex() {
        val sql =
            "SELECT _id FROM " +
                VoltTrackerDb.TABLE_LOCATION_SAMPLES +
                " WHERE captured_at_ms < ?"
        assertUsesIndex(sql, arrayOf("0"), "idx_location_samples_captured_at")
    }

    @Test
    fun prune_eventsByTime_usesNewTimeIndex() {
        val sql = "SELECT _id FROM " + VoltTrackerDb.TABLE_EVENTS + " WHERE occurred_at_ms < ?"
        assertUsesIndex(sql, arrayOf("0"), "idx_events_occurred_at")
    }

    @Test
    fun prune_pidObservationsByTime_usesNewTimeIndex() {
        val sql =
            "SELECT _id FROM " +
                VoltTrackerDb.TABLE_PID_OBSERVATIONS +
                " WHERE observed_at_ms < ?"
        assertUsesIndex(sql, arrayOf("0"), "idx_pid_observations_observed_at")
    }

    // ---- diagnostic codes (upsert lookup, latest list) -------------------------------

    @Test
    fun diagnosticCodeUpsertLookup_usesCompositeIndex() {
        // The table has UNIQUE(module_key, dtc, status), so SQLite may legitimately pick the
        // auto-index built for that constraint instead of the named idx_diagnostic_codes_lookup.
        // Either covers the lookup — accept either name and just ensure no full scan.
        val sql =
            "SELECT _id FROM " +
                VoltTrackerDb.TABLE_DIAGNOSTIC_CODES +
                " WHERE module_key = ? AND dtc = ? AND status = ?"
        assertUsesIndex(
            sql,
            arrayOf("generic-obd", "P0420", "stored"),
            "sqlite_autoindex_diagnostic_codes_1",
            "idx_diagnostic_codes_lookup",
        )
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Asserts that the planner's chosen plan for [sql] uses at least one of [expectedIndexNames],
     * and that the plan does not contain a bare "SCAN TABLE" line (i.e. a full
     * table scan with no index help). Note that SQLite legitimately says "SCAN TABLE x USING INDEX
     * y" when walking an index in order for an unfiltered `ORDER BY` — that is index use, not
     * a full scan, and is accepted here.
     */
    private fun assertUsesIndex(
        sql: String,
        args: Array<String>,
        vararg expectedIndexNames: String,
    ) {
        val plan = explainQueryPlan(sql, args)
        val dump = joinPlan(plan)
        var matched = false
        for (name in expectedIndexNames) {
            if (dump.contains(name)) {
                matched = true
                break
            }
        }
        assertTrue(
            "Plan for [" +
                sql +
                "] did not use any expected index from " +
                expectedIndexNames.joinToString(", ") +
                ".\nPlan:\n" +
                dump,
            matched,
        )
        assertNotFullScanInternal(sql, dump)
    }

    private fun assertNotFullTableScan(
        sql: String,
        args: Array<String>,
    ) {
        val plan = explainQueryPlan(sql, args)
        assertNotFullScanInternal(sql, joinPlan(plan))
    }

    private fun explainQueryPlan(
        sql: String,
        args: Array<String>,
    ): List<String> {
        val rows = ArrayList<String>()
        db.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            val detailIndex = cursor.getColumnIndex("detail")
            while (cursor.moveToNext()) {
                if (detailIndex >= 0) {
                    rows.add(cursor.getString(detailIndex)!!)
                } else {
                    // Some SQLite builds expose the plan via column 3.
                    rows.add(cursor.getString(cursor.columnCount - 1)!!)
                }
            }
        }
        assertNotNull(rows)
        assertTrue("Empty query plan for $sql", rows.isNotEmpty())
        return rows
    }

    private fun seedRealisticData() {
        for (sessionIndex in 0 until SESSION_COUNT) {
            val sessionStart = 1_700_000_000_000L + sessionIndex * 60_000L
            val sv = ContentValues()
            sv.put("mode", "obd")
            sv.put("adapter_address", "AA:BB:CC:DD:EE:" + String.format(Locale.US, "%02X", sessionIndex))
            sv.put("adapter_name", "Adapter $sessionIndex")
            sv.put("started_at_ms", sessionStart)
            sv.put("status", "complete")
            sv.put("sample_count", TELEMETRY_PER_SESSION)
            sv.put("created_at_ms", sessionStart)
            val sessionId = db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, sv)

            for (t in 0 until TELEMETRY_PER_SESSION) {
                val tv = ContentValues()
                tv.put("session_id", sessionId)
                tv.put("captured_at_ms", sessionStart + t * 1000L)
                tv.put("source", "obd")
                tv.put("speed_kph", 30 + t)
                tv.put("rpm", 1500)
                tv.put("json", "{}")
                db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, tv)
            }
            for (l in 0 until LOCATION_PER_SESSION) {
                val lv = ContentValues()
                lv.put("session_id", sessionId)
                lv.put("captured_at_ms", sessionStart + l * 2000L)
                lv.put("latitude", 37.0 + l * 0.001)
                lv.put("longitude", -122.0 + l * 0.001)
                lv.put("json", "{}")
                db.insertOrThrow(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, lv)
            }
            for (e in 0 until EVENT_PER_SESSION) {
                val ev = ContentValues()
                ev.put("session_id", sessionId)
                ev.put("occurred_at_ms", sessionStart + e * 5000L)
                ev.put("kind", "log")
                ev.put("payload", "{}")
                db.insertOrThrow(VoltTrackerDb.TABLE_EVENTS, null, ev)
            }
            for (p in 0 until PID_OBS_PER_SESSION) {
                val pv = ContentValues()
                pv.put("session_id", sessionId)
                pv.put("observed_at_ms", sessionStart + p * 750L)
                pv.put("command", if (p % 2 == 0) "010C" else "010D")
                pv.put("header", "7E0")
                pv.put("pid", if (p % 2 == 0) "0C" else "0D")
                pv.put("json", "{}")
                db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, pv)
            }
        }
        // Adapter history: one row per session-adapter, plus a couple repeat keys.
        for (a in 0 until SESSION_COUNT) {
            val av = ContentValues()
            av.put("adapter_key", "AA:BB:CC:DD:EE:" + String.format(Locale.US, "%02X", a))
            av.put("address", "AA:BB:CC:DD:EE:" + String.format(Locale.US, "%02X", a))
            av.put("name", "Adapter $a")
            av.put("first_seen_ms", 1_700_000_000_000L + a * 1000L)
            av.put("last_seen_ms", 1_700_000_000_000L + a * 1000L)
            db.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, av)
        }
        // Diagnostic codes spread across a few modules so the composite lookup is selective.
        val modules = arrayOf("generic-obd", "engine", "battery", "transmission")
        val statuses = arrayOf("stored", "pending", "permanent")
        for (m in modules.indices) {
            for (s in statuses.indices) {
                for (code in 0 until 5) {
                    val dv = ContentValues()
                    dv.put("dtc", "P04" + (10 * m + code))
                    dv.put("status", statuses[s])
                    dv.put("module_key", modules[m])
                    dv.put("first_seen_ms", 1_700_000_000_000L)
                    dv.put("last_seen_ms", 1_700_000_000_000L + s * 100L)
                    dv.put("seen_count", 1)
                    dv.put("json", "{}")
                    db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, dv)
                }
            }
        }
    }

    companion object {
        private const val SESSION_COUNT = 50
        private const val TELEMETRY_PER_SESSION = 12
        private const val LOCATION_PER_SESSION = 8
        private const val EVENT_PER_SESSION = 6
        private const val PID_OBS_PER_SESSION = 10

        private fun assertNotFullScanInternal(
            sql: String,
            dump: String,
        ) {
            // SQLite emits "SCAN TABLE foo" for a full scan and "SCAN TABLE foo USING INDEX bar" when
            // it walks an index in order without a search key (e.g. ORDER BY with no WHERE on a
            // covered table). The second form is correct index use; only the first is a missing-index
            // smell.
            for (line in dump.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.startsWith("SCAN TABLE ") && !trimmed.contains("USING INDEX")) {
                    throw AssertionError(
                        "Plan for [$sql] does a full SCAN TABLE.\nPlan:\n$dump",
                    )
                }
            }
        }

        private fun joinPlan(plan: List<String>): String {
            val joined = StringBuilder()
            for (row in plan) {
                joined.append(row).append('\n')
            }
            return joined.toString()
        }

        /**
         * Reaches into [ObdLocalStore] via reflection for its helper, then opens the writable DB.
         * Keeps [ObdLocalStore]'s test surface (which doesn't expose the helper) untouched.
         */
        private fun openWritableDb(store: ObdLocalStore): SQLiteDatabase {
            val field = ObdLocalStore::class.java.getDeclaredField("helper")
            field.isAccessible = true
            val helper = field.get(store) as VoltTrackerDb
            return helper.writableDatabase
        }
    }
}
