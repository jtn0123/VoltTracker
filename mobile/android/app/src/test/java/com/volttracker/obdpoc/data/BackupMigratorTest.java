package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Exercises {@link BackupMigrator}: an older-schema VoltTracker backup is upgraded to the current
 * schema (reusing the real onUpgrade path), a current-version file is left alone, a newer-than-app
 * file is refused, and a non-backup SQLite file is refused without being mangled.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupMigratorTest {

    private Context context;
    private File file;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        file = new File(context.getCacheDir(), "migrate-test.db");
        deleteFamily(file);
    }

    @After
    public void tearDown() {
        deleteFamily(file);
    }

    @Test
    public void upgradesAnOlderBackupToCurrentSchema() {
        // Build a v8-shaped backup: the core sessions table + a few rows, schema version 8, and
        // crucially WITHOUT the v9 session_trip_rollups cache table.
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file.getPath(), null);
        try {
            db.execSQL(
                    "CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT "
                            + "NOT NULL, started_at_ms INTEGER NOT NULL, status TEXT NOT NULL, "
                            + "sample_count INTEGER NOT NULL DEFAULT 0, created_at_ms INTEGER NOT "
                            + "NULL)");
            db.execSQL(
                    "INSERT INTO obd_sessions (mode, started_at_ms, status, created_at_ms) VALUES "
                            + "('obd', 1000, 'complete', 1000)");
            db.setVersion(8);
        } finally {
            db.close();
        }

        BackupMigrator.Result result = BackupMigrator.migrateToCurrentVersion(context, file);

        assertEquals(BackupMigrator.Result.MIGRATED, result);
        try (SQLiteDatabase check =
                SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(VoltTrackerDb.DATABASE_VERSION, check.getVersion());
            assertTrue(
                    "v9 migration must add the rollups cache table",
                    hasTable(check, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS));
            // The original data survives the upgrade.
            assertEquals(1, count(check, VoltTrackerDb.TABLE_SESSIONS));
        }
    }

    @Test
    public void migrationPreservesExistingRows() {
        // Build a v8 backup with real data across two tables (no v9 rollups cache), then confirm
        // the upgrade is non-destructive: every session/telemetry row survives, and the new cache
        // table is added empty.
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file.getPath(), null);
        try {
            db.execSQL(
                    "CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT "
                            + "NOT NULL, started_at_ms INTEGER NOT NULL, status TEXT NOT NULL, "
                            + "sample_count INTEGER NOT NULL DEFAULT 0, created_at_ms INTEGER NOT "
                            + "NULL)");
            db.execSQL(
                    "CREATE TABLE telemetry_samples (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "session_id INTEGER NOT NULL, captured_at_ms INTEGER NOT NULL, "
                            + "json TEXT NOT NULL)");
            db.execSQL(
                    "INSERT INTO obd_sessions (mode, started_at_ms, status, created_at_ms) VALUES "
                            + "('obd', 1000, 'complete', 1000)");
            db.execSQL(
                    "INSERT INTO telemetry_samples (session_id, captured_at_ms, json) VALUES "
                            + "(1, 1000, '{}'), (1, 2000, '{}'), (1, 3000, '{}')");
            db.setVersion(8);
        } finally {
            db.close();
        }

        assertEquals(
                BackupMigrator.Result.MIGRATED,
                BackupMigrator.migrateToCurrentVersion(context, file));

        try (SQLiteDatabase check =
                SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(VoltTrackerDb.DATABASE_VERSION, check.getVersion());
            assertEquals("session row must survive the upgrade", 1, count(check, "obd_sessions"));
            assertEquals(
                    "all telemetry rows must survive the upgrade",
                    3,
                    count(check, "telemetry_samples"));
            assertTrue(hasTable(check, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS));
            assertEquals(
                    "the added rollups cache starts empty",
                    0,
                    count(check, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS));
        }
    }

    @Test
    public void leavesACurrentVersionBackupUnchanged() {
        // A fresh helper-created DB is already at the current version.
        VoltTrackerDb helper = new VoltTrackerDb(context, "already-current.db");
        helper.getWritableDatabase();
        helper.close();
        File current = context.getDatabasePath("already-current.db");

        BackupMigrator.Result result = BackupMigrator.migrateToCurrentVersion(context, current);

        assertEquals(BackupMigrator.Result.ALREADY_CURRENT, result);
        context.deleteDatabase("already-current.db");
    }

    @Test
    public void refusesANewerThanAppBackup() {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file.getPath(), null);
        try {
            db.execSQL(
                    "CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT)");
            db.setVersion(VoltTrackerDb.DATABASE_VERSION + 1);
        } finally {
            db.close();
        }

        assertEquals(
                BackupMigrator.Result.TOO_NEW,
                BackupMigrator.migrateToCurrentVersion(context, file));
    }

    @Test
    public void refusesANonBackupSqliteFile() {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file.getPath(), null);
        try {
            db.execSQL("CREATE TABLE unrelated (x INTEGER)");
            db.setVersion(3);
        } finally {
            db.close();
        }

        assertEquals(
                BackupMigrator.Result.NOT_A_BACKUP,
                BackupMigrator.migrateToCurrentVersion(context, file));
        // The unrelated file must not have been upgraded/rewritten.
        try (SQLiteDatabase check =
                SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(3, check.getVersion());
            assertFalse(hasTable(check, VoltTrackerDb.TABLE_SESSIONS));
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        try (Cursor c =
                db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                        new String[] {table})) {
            return c.moveToFirst();
        }
    }

    private static int count(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
    }

    private static void deleteFamily(File f) {
        f.delete();
        new File(f.getPath() + "-wal").delete();
        new File(f.getPath() + "-shm").delete();
        new File(f.getPath() + "-journal").delete();
    }
}
