package com.volttracker.obdpoc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Migration coverage for [VoltTrackerDb]. The schema bump that introduced this test (v7)
 * added time-only indexes on `telemetry_samples`, `location_samples`, `status_events`, and
 * `pid_observations` so the daily [ObdStoreMaintenance.pruneRawDataOlderThan] delete stops doing a
 * full table scan. This test opens an in-memory database at the prior schema version, then upgrades
 * to the current version and asserts the new indexes exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltTrackerDbMigrationTest {
    private var oldHelper: SQLiteOpenHelper? = null
    private var newHelper: SQLiteOpenHelper? = null

    @After
    fun tearDown() {
        oldHelper?.close()
        newHelper?.close()
    }

    @Test
    fun freshInstall_createsPruneIndexes() {
        val context = RuntimeEnvironment.getApplication()
        // Use a distinct DB name so this test doesn't trample others. Robolectric reuses the app
        // data dir across tests, so delete first to guarantee onCreate (not onUpgrade) runs.
        val name = "volttracker_migration_fresh.db"
        context.deleteDatabase(name)
        newHelper = VoltTrackerDb(context, name)
        // Touch the writable DB so onCreate fires.
        val db = newHelper!!.writableDatabase
        val indexes = readIndexNames(db)
        for (expected in V7_INDEXES) {
            assertTrue(
                "Fresh install missing expected v7 index $expected.\nGot: $indexes",
                indexes.contains(expected),
            )
        }
        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV6_addsPruneIndexes() {
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v6_v7.db"
        // Make sure we start clean — Robolectric reuses the app data dir between tests.
        context.deleteDatabase(name)

        // 1. Open at v6 and let onCreate build the v6 schema.
        oldHelper = LegacyHelper(context, name, 6)
        val oldDb = oldHelper!!.writableDatabase
        val beforeIndexes = readIndexNames(oldDb)
        for (expected in V7_INDEXES) {
            assertEquals(
                "v6 schema unexpectedly already had $expected",
                false,
                beforeIndexes.contains(expected),
            )
        }
        oldHelper!!.close()

        // 2. Reopen the SAME file with the real helper at the current version; that triggers
        // onUpgrade. Critical: we pass the same name to VoltTrackerDb so it opens the file
        // V6Helper just created, not the default production DB.
        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        val afterIndexes = readIndexNames(newDb)
        for (expected in V7_INDEXES) {
            assertTrue(
                "After upgrade missing expected v7 index $expected.\nGot: $afterIndexes",
                afterIndexes.contains(expected),
            )
        }

        // Cleanup the named db file.
        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV4_backfillsTelemetryFlagsByParsingJson() {
        // The v5 backfill derives charge_transition_hint / app_foreground from the stored JSON
        // with a real parse (it used to be a fragile LIKE on the serialized text). Pins the
        // tricky cases: a spacing variant the LIKE missed, the literal appearing only in a
        // nested object the LIKE false-positived on, and unparseable JSON falling back to the
        // defaults (hint 0, foreground 1).
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v4_v5.db"
        context.deleteDatabase(name)

        oldHelper = LegacyHelper(context, name, 4)
        val v4Db = oldHelper!!.writableDatabase
        val jsonBySampleTime =
            linkedMapOf(
                1000L to """{"chargeTransitionHint":true}""",
                2000L to """{"chargeTransitionHint": true}""",
                3000L to """{"nested":{"chargeTransitionHint":true}}""",
                4000L to """{"appForeground":false}""",
                5000L to """not json at all""",
            )
        for ((atMs, json) in jsonBySampleTime) {
            v4Db.execSQL(
                "INSERT INTO ${VoltTrackerDb.TABLE_TELEMETRY} (session_id, captured_at_ms, json)" +
                    " VALUES (1, $atMs, ?)",
                arrayOf(json),
            )
        }
        oldHelper!!.close()
        oldHelper = null

        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(VoltTrackerDb.DATABASE_VERSION, newDb.version)
        val expected =
            mapOf(
                // captured_at_ms to (charge_transition_hint, app_foreground)
                1000L to Pair(1, 1),
                2000L to Pair(1, 1),
                3000L to Pair(0, 1),
                4000L to Pair(0, 0),
                5000L to Pair(0, 1),
            )
        newDb
            .rawQuery(
                "SELECT captured_at_ms, charge_transition_hint, app_foreground" +
                    " FROM ${VoltTrackerDb.TABLE_TELEMETRY}",
                null,
            ).use { cursor ->
                var rows = 0
                while (cursor.moveToNext()) {
                    rows++
                    val atMs = cursor.getLong(0)
                    val want = expected[atMs]!!
                    assertEquals("charge_transition_hint for row at $atMs", want.first, cursor.getInt(1))
                    assertEquals("app_foreground for row at $atMs", want.second, cursor.getInt(2))
                }
                assertEquals(expected.size, rows)
            }

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV7_addsHvPackColumns() {
        // The v7→v8 migration adds pack_voltage and pack_current_a to telemetry_samples so
        // the trip materializer can integrate V·I for energy_kwh and the dashboard can show
        // the real HV pack readings instead of the aux 12V battery.
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v7_v8.db"
        context.deleteDatabase(name)

        // Pre-seed a minimal v7 schema with the telemetry_samples columns the v7 install
        // already had — this is a focused regression test, not a full schema replay.
        val v7Helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 7) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_SESSIONS +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " mode TEXT NOT NULL, adapter_address TEXT," +
                            " adapter_name TEXT, started_at_ms INTEGER NOT NULL," +
                            " ended_at_ms INTEGER, status TEXT NOT NULL," +
                            " supported_pids TEXT," +
                            " sample_count INTEGER NOT NULL DEFAULT 0," +
                            " last_event_at_ms INTEGER," +
                            " created_at_ms INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_TELEMETRY +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " session_id INTEGER NOT NULL," +
                            " captured_at_ms INTEGER NOT NULL," +
                            " voltage REAL, power_kw REAL," +
                            " json TEXT NOT NULL)",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val v7Db = v7Helper.writableDatabase
        val beforeColumns = readColumnNames(v7Db, VoltTrackerDb.TABLE_TELEMETRY)
        assertFalse(
            "v7 schema must not pre-contain pack_voltage",
            beforeColumns.contains("pack_voltage"),
        )
        assertFalse(
            "v7 schema must not pre-contain pack_current_a",
            beforeColumns.contains("pack_current_a"),
        )
        v7Helper.close()

        // Open the real helper at the current version; that runs onUpgrade(7, 8).
        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        val afterColumns = readColumnNames(newDb, VoltTrackerDb.TABLE_TELEMETRY)
        assertTrue(
            "After v7→v8 upgrade, pack_voltage must exist. Got: $afterColumns",
            afterColumns.contains("pack_voltage"),
        )
        assertTrue(
            "After v7→v8 upgrade, pack_current_a must exist. Got: $afterColumns",
            afterColumns.contains("pack_current_a"),
        )

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV9_addsRollupVersionColumn() {
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v9_v10.db"
        context.deleteDatabase(name)

        val v9Helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 9) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS +
                            " (session_id INTEGER PRIMARY KEY," +
                            " counted INTEGER NOT NULL," +
                            " distance_m REAL NOT NULL DEFAULT 0," +
                            " duration_ms INTEGER NOT NULL DEFAULT 0," +
                            " max_speed_kph INTEGER," +
                            " has_route INTEGER NOT NULL DEFAULT 0," +
                            " started_at_ms INTEGER NOT NULL DEFAULT 0)",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val v9Db = v9Helper.writableDatabase
        assertFalse(
            "v9 schema must not pre-contain rollup_version",
            readColumnNames(v9Db, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS)
                .contains("rollup_version"),
        )
        v9Helper.close()

        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        assertTrue(
            "After v9->v10 upgrade, rollup_version must exist.",
            readColumnNames(newDb, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS)
                .contains("rollup_version"),
        )

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV10_addsTripListCache() {
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v10_v11.db"
        context.deleteDatabase(name)

        val v10Helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 10) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_SESSIONS +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " mode TEXT NOT NULL, adapter_address TEXT," +
                            " adapter_name TEXT, started_at_ms INTEGER NOT NULL," +
                            " ended_at_ms INTEGER, status TEXT NOT NULL," +
                            " supported_pids TEXT," +
                            " sample_count INTEGER NOT NULL DEFAULT 0," +
                            " last_event_at_ms INTEGER," +
                            " created_at_ms INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS +
                            " (session_id INTEGER PRIMARY KEY," +
                            " counted INTEGER NOT NULL," +
                            " distance_m REAL NOT NULL DEFAULT 0," +
                            " duration_ms INTEGER NOT NULL DEFAULT 0," +
                            " max_speed_kph INTEGER," +
                            " has_route INTEGER NOT NULL DEFAULT 0," +
                            " started_at_ms INTEGER NOT NULL DEFAULT 0," +
                            " rollup_version INTEGER NOT NULL DEFAULT 0)",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val v10Db = v10Helper.writableDatabase
        assertFalse(
            "v10 schema must not pre-contain the trip list cache table",
            readTableNames(v10Db).contains(VoltTrackerDb.TABLE_TRIP_LIST_CACHE),
        )
        v10Helper.close()

        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        assertTrue(
            "After v10->v11 upgrade, the trip list cache table must exist.",
            readTableNames(newDb).contains(VoltTrackerDb.TABLE_TRIP_LIST_CACHE),
        )
        val indexes = readIndexNames(newDb)
        assertTrue(
            "After v10->v11 upgrade, trip list cache ended-at index must exist. Got: $indexes",
            indexes.contains("idx_trip_list_cache_ended"),
        )
        assertTrue(
            "After v10->v11 upgrade, trip list cache session index must exist. Got: $indexes",
            indexes.contains("idx_trip_list_cache_session"),
        )

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV11_addsTripLabelColumnAndMaintenanceLog() {
        // The v11→v12 migration adds a nullable label column to trip_segments (M4) and creates the
        // maintenance_log table (M5). Both steps are non-destructive: an existing trip_segments row
        // and its data must survive, gaining only the new NULL label.
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v11_v12.db"
        context.deleteDatabase(name)

        val v11Helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 11) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_TRIP_SEGMENTS +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " session_id INTEGER," +
                            " started_at_ms INTEGER NOT NULL," +
                            " ended_at_ms INTEGER," +
                            " distance_m REAL," +
                            " created_at_ms INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO " +
                            VoltTrackerDb.TABLE_TRIP_SEGMENTS +
                            " (session_id, started_at_ms, ended_at_ms, distance_m, created_at_ms)" +
                            " VALUES (7, 1000, 2000, 4200.5, 9000)",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val v11Db = v11Helper.writableDatabase
        assertFalse(
            "v11 schema must not pre-contain trip_segments.label",
            readColumnNames(v11Db, VoltTrackerDb.TABLE_TRIP_SEGMENTS).contains("label"),
        )
        assertFalse(
            "v11 schema must not pre-contain the maintenance_log table",
            readTableNames(v11Db).contains(VoltTrackerDb.TABLE_MAINTENANCE_LOG),
        )
        v11Helper.close()

        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        assertTrue(
            "After v11->v12 upgrade, trip_segments.label must exist.",
            readColumnNames(newDb, VoltTrackerDb.TABLE_TRIP_SEGMENTS).contains("label"),
        )
        assertTrue(
            "After v11->v12 upgrade, the maintenance_log table must exist.",
            readTableNames(newDb).contains(VoltTrackerDb.TABLE_MAINTENANCE_LOG),
        )
        assertTrue(
            "After v11->v12 upgrade, the maintenance_log created index must exist.",
            readIndexNames(newDb).contains("idx_maintenance_log_created"),
        )

        // The pre-existing trip_segments row survives with its data intact and a NULL label.
        newDb
            .rawQuery(
                "SELECT distance_m, label FROM ${VoltTrackerDb.TABLE_TRIP_SEGMENTS} WHERE session_id = 7",
                null,
            ).use { cursor ->
                assertTrue("pre-existing trip_segments row must survive", cursor.moveToFirst())
                assertEquals(4200.5, cursor.getDouble(0), 0.001)
                assertTrue("new label column must default to NULL", cursor.isNull(1))
            }

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV12_addsMaintenanceIntervalColumns() {
        // The v12→v13 migration adds nullable interval_km / interval_months columns to
        // maintenance_log (M1/C4). The step is non-destructive: an existing maintenance_log row and
        // its data must survive, gaining only the two new NULL interval columns.
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v12_v13.db"
        context.deleteDatabase(name)

        val v12Helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 12) {
                override fun onCreate(db: SQLiteDatabase) {
                    // The v12 maintenance_log shape (no interval columns yet).
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_MAINTENANCE_LOG +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " created_at_ms INTEGER NOT NULL," +
                            " odometer_km REAL, type TEXT, note TEXT)",
                    )
                    db.execSQL(
                        "INSERT INTO " +
                            VoltTrackerDb.TABLE_MAINTENANCE_LOG +
                            " (created_at_ms, odometer_km, type, note)" +
                            " VALUES (5000, 12345.6, 'Oil change', 'Synthetic')",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val v12Db = v12Helper.writableDatabase
        val beforeColumns = readColumnNames(v12Db, VoltTrackerDb.TABLE_MAINTENANCE_LOG)
        assertFalse(
            "v12 schema must not pre-contain maintenance_log.interval_km",
            beforeColumns.contains("interval_km"),
        )
        assertFalse(
            "v12 schema must not pre-contain maintenance_log.interval_months",
            beforeColumns.contains("interval_months"),
        )
        v12Helper.close()

        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        val afterColumns = readColumnNames(newDb, VoltTrackerDb.TABLE_MAINTENANCE_LOG)
        assertTrue(
            "After v12->v13 upgrade, maintenance_log.interval_km must exist. Got: $afterColumns",
            afterColumns.contains("interval_km"),
        )
        assertTrue(
            "After v12->v13 upgrade, maintenance_log.interval_months must exist. Got: $afterColumns",
            afterColumns.contains("interval_months"),
        )

        // The pre-existing maintenance_log row survives with its data intact and NULL intervals.
        newDb
            .rawQuery(
                "SELECT odometer_km, type, note, interval_km, interval_months" +
                    " FROM ${VoltTrackerDb.TABLE_MAINTENANCE_LOG} WHERE created_at_ms = 5000",
                null,
            ).use { cursor ->
                assertTrue("pre-existing maintenance_log row must survive", cursor.moveToFirst())
                assertEquals(12345.6, cursor.getDouble(0), 0.001)
                assertEquals("Oil change", cursor.getString(1))
                assertEquals("Synthetic", cursor.getString(2))
                assertTrue("new interval_km column must default to NULL", cursor.isNull(3))
                assertTrue("new interval_months column must default to NULL", cursor.isNull(4))
            }

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun upgradeFromV13_addsChargeSessionRollupsTable() {
        // The v13→v14 migration creates the charge_session_rollups cache table (G2). The step is
        // non-destructive: an existing session row must survive untouched while the new table
        // appears empty, ready to be backfilled lazily on the next storage-summary read.
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_v13_v14.db"
        context.deleteDatabase(name)

        val v13Helper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 13) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE " +
                            VoltTrackerDb.TABLE_SESSIONS +
                            " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " mode TEXT NOT NULL, adapter_address TEXT," +
                            " adapter_name TEXT, started_at_ms INTEGER NOT NULL," +
                            " ended_at_ms INTEGER, status TEXT NOT NULL," +
                            " supported_pids TEXT," +
                            " sample_count INTEGER NOT NULL DEFAULT 0," +
                            " last_event_at_ms INTEGER," +
                            " created_at_ms INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO " +
                            VoltTrackerDb.TABLE_SESSIONS +
                            " (mode, started_at_ms, status, created_at_ms)" +
                            " VALUES ('obd', 1000, 'complete', 1000)",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val v13Db = v13Helper.writableDatabase
        assertFalse(
            "v13 schema must not pre-contain the charge session rollups table",
            readTableNames(v13Db).contains(VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS),
        )
        v13Helper.close()

        newHelper = VoltTrackerDb(context, name)
        val newDb = newHelper!!.writableDatabase
        assertEquals(
            "Reopened DB should be at the current schema version after onUpgrade.",
            VoltTrackerDb.DATABASE_VERSION,
            newDb.version,
        )
        assertTrue(
            "After v13->v14 upgrade, the charge session rollups table must exist.",
            readTableNames(newDb).contains(VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS),
        )
        // It carries the documented cache columns and starts empty.
        val columns = readColumnNames(newDb, VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS)
        assertTrue("rollup table must key on session_id", columns.contains("session_id"))
        assertTrue("rollup table must carry rollup_version", columns.contains("rollup_version"))
        assertTrue("rollup table must carry charge_json", columns.contains("charge_json"))
        newDb
            .rawQuery("SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS}", null)
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("new cache table must start empty", 0L, cursor.getLong(0))
            }
        // The pre-existing session row survives untouched.
        newDb
            .rawQuery(
                "SELECT status FROM ${VoltTrackerDb.TABLE_SESSIONS} WHERE started_at_ms = 1000",
                null,
            ).use { cursor ->
                assertTrue("pre-existing session row must survive", cursor.moveToFirst())
                assertEquals("complete", cursor.getString(0))
            }

        newHelper!!.close()
        newHelper = null
        context.deleteDatabase(name)
    }

    @Test
    fun databaseVersionBumpRequiresMigrationCoverageUpdate() {
        assertEquals(
            "DATABASE_VERSION changed. Add a focused migration test for the new version, " +
                "then update EXPECTED_MIGRATION_COVERAGE_VERSION in this test.",
            EXPECTED_MIGRATION_COVERAGE_VERSION,
            VoltTrackerDb.DATABASE_VERSION,
        )
    }

    /**
     * B2 regression: when a migration step throws partway through, the whole step must roll back,
     * leaving the database at the prior version with NO partial changes applied. Without the
     * transaction wrapper a half-applied ALTER would stick and the next launch would retry the
     * whole migration, hitting "duplicate column" and masking the original failure.
     */
    @Test
    fun failingMigrationStep_rollsBackPartialChanges() {
        val context = RuntimeEnvironment.getApplication()
        val name = "volttracker_migration_rollback.db"
        context.deleteDatabase(name)

        // Open a clean database at v1 with a single throwaway table we can ALTER below.
        val bareHelper =
            object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE rollback_probe (" +
                            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            " value TEXT)",
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // unused
                }
            }
        val db = bareHelper.writableDatabase
        val startingColumns = readColumnNames(db, "rollback_probe")
        assertEquals(2, startingColumns.size) // _id + value

        try {
            VoltTrackerDb.runMigrationStep(db, 1, 2, "rollback-probe") { target ->
                // First ALTER succeeds — would commit if not wrapped in a transaction.
                target.execSQL("ALTER TABLE rollback_probe ADD COLUMN added_first TEXT")
                // Second ALTER throws — invalid SQL forces the whole step to roll back.
                target.execSQL("ALTER TABLE rollback_probe ADD COLUMN ??? syntactically invalid")
                fail("Expected the malformed ALTER to throw")
            }
            fail("runMigrationStep should have rethrown the SQLite exception")
        } catch (expected: RuntimeException) {
            // Expected — the wrapper rethrows after rolling back so SQLiteOpenHelper knows the
            // upgrade failed.
            assertNotNull(expected)
        }

        val columnsAfter = readColumnNames(db, "rollback_probe")
        assertEquals(
            "Migration step that failed must roll back every change, including the ALTER" +
                " that ran before the failure.\nExpected only the original columns," +
                " got: " +
                columnsAfter,
            startingColumns,
            columnsAfter,
        )
        assertFalse(
            "added_first column must NOT survive the failed migration",
            columnsAfter.contains("added_first"),
        )

        bareHelper.close()
        context.deleteDatabase(name)
    }

    /**
     * Minimal legacy-schema helper used to simulate a pre-v7 install at a chosen version. Only
     * creates the tables the intervening migrations touch (ALTERs and prune-time indexes) — the
     * migrations only care that those tables exist before the upgrade runs.
     */
    private open class LegacyHelper(
        context: Context,
        name: String,
        version: Int,
    ) : SQLiteOpenHelper(context.applicationContext, name, null, version) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE " +
                    VoltTrackerDb.TABLE_SESSIONS +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " mode TEXT NOT NULL, adapter_address TEXT, adapter_name TEXT," +
                    " started_at_ms INTEGER NOT NULL, ended_at_ms INTEGER," +
                    " status TEXT NOT NULL, supported_pids TEXT," +
                    " sample_count INTEGER NOT NULL DEFAULT 0," +
                    " last_event_at_ms INTEGER, created_at_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE " +
                    VoltTrackerDb.TABLE_TELEMETRY +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " session_id INTEGER NOT NULL," +
                    " captured_at_ms INTEGER NOT NULL," +
                    " json TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE " +
                    VoltTrackerDb.TABLE_EVENTS +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " session_id INTEGER," +
                    " occurred_at_ms INTEGER NOT NULL," +
                    " kind TEXT NOT NULL," +
                    " payload TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE " +
                    VoltTrackerDb.TABLE_LOCATION_SAMPLES +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " session_id INTEGER NOT NULL," +
                    " captured_at_ms INTEGER NOT NULL," +
                    " latitude REAL NOT NULL, longitude REAL NOT NULL," +
                    " json TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE " +
                    VoltTrackerDb.TABLE_PID_OBSERVATIONS +
                    " (_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " session_id INTEGER NOT NULL," +
                    " observed_at_ms INTEGER NOT NULL," +
                    " json TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            // No-op: this stub only exists to land the v6 schema, then the real helper upgrades.
        }
    }

    companion object {
        private const val EXPECTED_MIGRATION_COVERAGE_VERSION = 14

        private val V7_INDEXES =
            arrayOf(
                "idx_telemetry_captured_at",
                "idx_location_samples_captured_at",
                "idx_events_occurred_at",
                "idx_pid_observations_observed_at",
            )

        private fun readIndexNames(db: SQLiteDatabase): Set<String> {
            val names = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(0)!!)
                }
            }
            return names
        }

        private fun readTableNames(db: SQLiteDatabase): Set<String> {
            val names = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(0)!!)
                }
            }
            return names
        }

        private fun readColumnNames(
            db: SQLiteDatabase,
            table: String,
        ): Set<String> {
            val names = HashSet<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(1)!!)
                }
            }
            return names
        }
    }
}
