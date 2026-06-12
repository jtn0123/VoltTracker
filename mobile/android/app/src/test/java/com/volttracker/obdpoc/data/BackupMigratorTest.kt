package com.volttracker.obdpoc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Exercises [BackupMigrator]: an older-schema VoltTracker backup is upgraded to the current schema
 * (reusing the real onUpgrade path), a current-version file is left alone, a newer-than-app file is
 * refused, and a non-backup SQLite file is refused without being mangled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupMigratorTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        file = File(context.cacheDir, "migrate-test.db")
        deleteFamily(file)
    }

    @After
    fun tearDown() {
        deleteFamily(file)
    }

    @Test
    fun upgradesAnOlderBackupToCurrentSchema() {
        // Build a v8-shaped backup: the core sessions table + a few rows, schema version 8, and
        // crucially WITHOUT the v9 session_trip_rollups cache table.
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            db.execSQL(
                "CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT " +
                    "NOT NULL, started_at_ms INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "sample_count INTEGER NOT NULL DEFAULT 0, created_at_ms INTEGER NOT " +
                    "NULL)",
            )
            db.execSQL(
                "INSERT INTO obd_sessions (mode, started_at_ms, status, created_at_ms) VALUES " +
                    "('obd', 1000, 'complete', 1000)",
            )
            db.version = 8
        } finally {
            db.close()
        }

        val result = BackupMigrator.migrateToCurrentVersion(context, file)

        assertEquals(BackupMigrator.Result.MIGRATED, result)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
            assertEquals(VoltTrackerDb.DATABASE_VERSION, check.version)
            assertTrue(
                "v9 migration must add the rollups cache table",
                hasTable(check, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS),
            )
            // The original data survives the upgrade.
            assertEquals(1, count(check, VoltTrackerDb.TABLE_SESSIONS))
        }
    }

    @Test
    fun migrationPreservesExistingRows() {
        // Build a v8 backup with real data across two tables (no v9 rollups cache), then confirm
        // the upgrade is non-destructive: every session/telemetry row survives, and the new cache
        // table is added empty.
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            db.execSQL(
                "CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT " +
                    "NOT NULL, started_at_ms INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "sample_count INTEGER NOT NULL DEFAULT 0, created_at_ms INTEGER NOT " +
                    "NULL)",
            )
            db.execSQL(
                "CREATE TABLE telemetry_samples (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER NOT NULL, captured_at_ms INTEGER NOT NULL, " +
                    "json TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO obd_sessions (mode, started_at_ms, status, created_at_ms) VALUES " +
                    "('obd', 1000, 'complete', 1000)",
            )
            db.execSQL(
                "INSERT INTO telemetry_samples (session_id, captured_at_ms, json) VALUES " +
                    "(1, 1000, '{}'), (1, 2000, '{}'), (1, 3000, '{}')",
            )
            db.version = 8
        } finally {
            db.close()
        }

        assertEquals(
            BackupMigrator.Result.MIGRATED,
            BackupMigrator.migrateToCurrentVersion(context, file),
        )

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
            assertEquals(VoltTrackerDb.DATABASE_VERSION, check.version)
            assertEquals("session row must survive the upgrade", 1, count(check, "obd_sessions"))
            assertEquals(
                "all telemetry rows must survive the upgrade",
                3,
                count(check, "telemetry_samples"),
            )
            assertTrue(hasTable(check, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS))
            assertEquals(
                "the added rollups cache starts empty",
                0,
                count(check, VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS),
            )
        }
    }

    @Test
    fun leavesACurrentVersionBackupUnchanged() {
        // A fresh helper-created DB is already at the current version.
        val helper = VoltTrackerDb(context, "already-current.db")
        helper.writableDatabase
        helper.close()
        val current = context.getDatabasePath("already-current.db")

        val result = BackupMigrator.migrateToCurrentVersion(context, current)

        assertEquals(BackupMigrator.Result.ALREADY_CURRENT, result)
        context.deleteDatabase("already-current.db")
    }

    @Test
    fun migrationRunSweepsStaleWorkingFiles() {
        // A crash mid-migration leaks restore-migrate-* working copies in the databases dir;
        // every later migration call must sweep them (including WAL siblings), even on a path
        // that returns early like ALREADY_CURRENT.
        val databasesDir = context.getDatabasePath("sweep-current.db").parentFile!!
        databasesDir.mkdirs()
        val stale = File(databasesDir, "restore-migrate-stale.db")
        val staleWal = File(databasesDir, "restore-migrate-stale.db-wal")
        stale.writeText("junk")
        staleWal.writeText("junk")

        val helper = VoltTrackerDb(context, "sweep-current.db")
        helper.writableDatabase
        helper.close()
        val current = context.getDatabasePath("sweep-current.db")

        assertEquals(
            BackupMigrator.Result.ALREADY_CURRENT,
            BackupMigrator.migrateToCurrentVersion(context, current),
        )

        assertFalse("stale working copy must be swept", stale.exists())
        assertFalse("stale WAL sibling must be swept", staleWal.exists())
        context.deleteDatabase("sweep-current.db")
    }

    @Test
    fun refusesANewerThanAppBackup() {
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            db.execSQL(
                "CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT)",
            )
            db.version = VoltTrackerDb.DATABASE_VERSION + 1
        } finally {
            db.close()
        }

        assertEquals(
            BackupMigrator.Result.TOO_NEW,
            BackupMigrator.migrateToCurrentVersion(context, file),
        )
    }

    @Test
    fun refusesANonBackupSqliteFile() {
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            db.execSQL("CREATE TABLE unrelated (x INTEGER)")
            db.version = 3
        } finally {
            db.close()
        }

        assertEquals(
            BackupMigrator.Result.NOT_A_BACKUP,
            BackupMigrator.migrateToCurrentVersion(context, file),
        )
        // The unrelated file must not have been upgraded/rewritten.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
            assertEquals(3, check.version)
            assertFalse(hasTable(check, VoltTrackerDb.TABLE_SESSIONS))
        }
    }

    private fun hasTable(
        db: SQLiteDatabase,
        table: String,
    ): Boolean =
        db
            .rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(table),
            ).use { c -> c.moveToFirst() }

    private fun count(
        db: SQLiteDatabase,
        table: String,
    ): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }

    private fun deleteFamily(f: File) {
        f.delete()
        File(f.path + "-wal").delete()
        File(f.path + "-shm").delete()
        File(f.path + "-journal").delete()
    }
}
