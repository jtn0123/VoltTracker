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
        oldHelper = V6Helper(context, name)
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
     * Minimal v6-schema helper used to simulate a pre-v7 install. Only creates the four tables
     * whose prune-time indexes are added in v7 — the migration only cares that those tables exist
     * before the upgrade runs.
     */
    private class V6Helper(
        context: Context,
        name: String,
    ) : SQLiteOpenHelper(context.applicationContext, name, null, 6) {
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
