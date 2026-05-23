package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Migration coverage for {@link VoltTrackerDb}. The schema bump that introduced this test (v7)
 * added time-only indexes on {@code telemetry_samples}, {@code location_samples}, {@code
 * status_events}, and {@code pid_observations} so the daily {@link
 * ObdStoreMaintenance#pruneRawDataOlderThan(int)} delete stops doing a full table scan. This test
 * opens an in-memory database at the prior schema version, then upgrades to the current version and
 * asserts the new indexes exist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoltTrackerDbMigrationTest {

    private static final String[] V7_INDEXES = {
        "idx_telemetry_captured_at",
        "idx_location_samples_captured_at",
        "idx_events_occurred_at",
        "idx_pid_observations_observed_at"
    };

    private SQLiteOpenHelper oldHelper;
    private SQLiteOpenHelper newHelper;

    @After
    public void tearDown() {
        if (oldHelper != null) {
            oldHelper.close();
        }
        if (newHelper != null) {
            newHelper.close();
        }
    }

    @Test
    public void freshInstall_createsPruneIndexes() {
        Context context = RuntimeEnvironment.getApplication();
        // Use a distinct DB name so this test doesn't trample others. Robolectric reuses the app
        // data dir across tests, so delete first to guarantee onCreate (not onUpgrade) runs.
        String name = "volttracker_migration_fresh.db";
        context.deleteDatabase(name);
        newHelper = new VoltTrackerDb(context, name);
        // Touch the writable DB so onCreate fires.
        SQLiteDatabase db = newHelper.getWritableDatabase();
        Set<String> indexes = readIndexNames(db);
        for (String expected : V7_INDEXES) {
            assertTrue(
                    "Fresh install missing expected v7 index " + expected + ".\nGot: " + indexes,
                    indexes.contains(expected));
        }
        newHelper.close();
        newHelper = null;
        context.deleteDatabase(name);
    }

    @Test
    public void upgradeFromV6_addsPruneIndexes() {
        Context context = RuntimeEnvironment.getApplication();
        String name = "volttracker_migration_v6_v7.db";
        // Make sure we start clean — Robolectric reuses the app data dir between tests.
        context.deleteDatabase(name);

        // 1. Open at v6 and let onCreate build the v6 schema.
        oldHelper = new V6Helper(context, name);
        SQLiteDatabase oldDb = oldHelper.getWritableDatabase();
        Set<String> beforeIndexes = readIndexNames(oldDb);
        for (String expected : V7_INDEXES) {
            assertEquals(
                    "v6 schema unexpectedly already had " + expected,
                    false,
                    beforeIndexes.contains(expected));
        }
        oldHelper.close();

        // 2. Reopen the SAME file with the real helper at the current version; that triggers
        // onUpgrade. Critical: we pass the same name to VoltTrackerDb so it opens the file
        // V6Helper just created, not the default production DB.
        newHelper = new VoltTrackerDb(context, name);
        SQLiteDatabase newDb = newHelper.getWritableDatabase();
        assertEquals(
                "Reopened DB should be at the current schema version after onUpgrade.",
                VoltTrackerDb.DATABASE_VERSION,
                newDb.getVersion());
        Set<String> afterIndexes = readIndexNames(newDb);
        for (String expected : V7_INDEXES) {
            assertTrue(
                    "After upgrade missing expected v7 index "
                            + expected
                            + ".\nGot: "
                            + afterIndexes,
                    afterIndexes.contains(expected));
        }

        // Cleanup the named db file.
        newHelper.close();
        newHelper = null;
        context.deleteDatabase(name);
    }

    private static Set<String> readIndexNames(SQLiteDatabase db) {
        Set<String> names = new HashSet<>();
        try (Cursor cursor =
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null)) {
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        }
        return names;
    }

    /**
     * Minimal v6-schema helper used to simulate a pre-v7 install. Only creates the four tables
     * whose prune-time indexes are added in v7 — the migration only cares that those tables exist
     * before the upgrade runs.
     */
    private static final class V6Helper extends SQLiteOpenHelper {
        V6Helper(Context context, String name) {
            super(context.getApplicationContext(), name, null, 6);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE "
                            + VoltTrackerDb.TABLE_SESSIONS
                            + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " mode TEXT NOT NULL, adapter_address TEXT, adapter_name TEXT,"
                            + " started_at_ms INTEGER NOT NULL, ended_at_ms INTEGER,"
                            + " status TEXT NOT NULL, supported_pids TEXT,"
                            + " sample_count INTEGER NOT NULL DEFAULT 0,"
                            + " last_event_at_ms INTEGER, created_at_ms INTEGER NOT NULL)");
            db.execSQL(
                    "CREATE TABLE "
                            + VoltTrackerDb.TABLE_TELEMETRY
                            + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " session_id INTEGER NOT NULL,"
                            + " captured_at_ms INTEGER NOT NULL,"
                            + " json TEXT NOT NULL)");
            db.execSQL(
                    "CREATE TABLE "
                            + VoltTrackerDb.TABLE_EVENTS
                            + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " session_id INTEGER,"
                            + " occurred_at_ms INTEGER NOT NULL,"
                            + " kind TEXT NOT NULL,"
                            + " payload TEXT NOT NULL)");
            db.execSQL(
                    "CREATE TABLE "
                            + VoltTrackerDb.TABLE_LOCATION_SAMPLES
                            + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " session_id INTEGER NOT NULL,"
                            + " captured_at_ms INTEGER NOT NULL,"
                            + " latitude REAL NOT NULL, longitude REAL NOT NULL,"
                            + " json TEXT NOT NULL)");
            db.execSQL(
                    "CREATE TABLE "
                            + VoltTrackerDb.TABLE_PID_OBSERVATIONS
                            + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " session_id INTEGER NOT NULL,"
                            + " observed_at_ms INTEGER NOT NULL,"
                            + " json TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // No-op: this stub only exists to land the v6 schema, then the real helper upgrades.
        }
    }
}
