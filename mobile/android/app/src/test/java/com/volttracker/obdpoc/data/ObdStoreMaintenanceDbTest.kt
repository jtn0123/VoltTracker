package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

/**
 * Exercises the integrity-check and space-reclamation maintenance paths against real SQLite:
 * `PRAGMA quick_check` on healthy/corrupted/unopenable databases, the freelist-gated VACUUM,
 * and the combined startup-maintenance entry point. None of these paths may ever throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreMaintenanceDbTest {
    private val context = RuntimeEnvironment.getApplication()
    private val openHelpers = ArrayList<VoltTrackerDb>()
    private val databaseNames = ArrayList<String>()

    @After
    fun tearDown() {
        for (helper in openHelpers) {
            try {
                helper.close()
            } catch (ignored: RuntimeException) {
                // Some tests corrupt the file on purpose; close must not fail the test.
            }
        }
        for (name in databaseNames) {
            context.deleteDatabase(name)
        }
    }

    private fun maintenanceFor(name: String): Pair<ObdStoreMaintenance, VoltTrackerDb> {
        val helper = VoltTrackerDb(context, name)
        openHelpers.add(helper)
        databaseNames.add(name)
        return Pair(ObdStoreMaintenance(context, helper), helper)
    }

    // ---- quick_check ---------------------------------------------------------------

    @Test
    fun quickCheckPassesOnAHealthyDatabase() {
        val (maintenance, helper) = maintenanceFor("healthy.db")
        val sessionId = insertSession(helper.writableDatabase)
        insertBulkTelemetry(helper.writableDatabase, sessionId, 10, 1000L)

        val result = maintenance.quickCheck()

        assertTrue("healthy database must pass quick_check", result.ok)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun quickCheckReportsACorruptedFileWithoutThrowing() {
        // Build a real multi-page database, close it cleanly, then stomp garbage over pages in
        // the middle of the file (header stays intact so the file still opens).
        val name = "corrupt.db"
        run {
            val helper = VoltTrackerDb(context, name)
            databaseNames.add(name)
            val db = helper.writableDatabase
            val sessionId = insertSession(db)
            insertBulkTelemetry(db, sessionId, 300, 1000L)
            checkpoint(db)
            helper.close()
        }
        val file = context.getDatabasePath(name)
        assertTrue("seeded database should span many pages", file.length() > 64L * PAGE_SIZE)
        RandomAccessFile(file, "rw").use { raf ->
            val middlePage = (raf.length() / 2 / PAGE_SIZE) * PAGE_SIZE
            raf.seek(maxOf(PAGE_SIZE, middlePage))
            raf.write(ByteArray(2 * PAGE_SIZE.toInt()) { 0x5A })
        }

        val (maintenance, _) = maintenanceFor(name)
        val result = maintenance.quickCheck()

        assertFalse("corrupted database must fail quick_check", result.ok)
        assertTrue("problems list should explain the failure", result.problems.isNotEmpty())
        assertTrue(
            "problems list is bounded",
            result.problems.size <= ObdStoreMaintenance.MAX_QUICK_CHECK_PROBLEMS,
        )
    }

    @Test
    fun quickCheckNeverThrowsWhenTheDatabaseCannotOpen() {
        // A directory squatting on the database path makes every open attempt fail; the
        // failure must come back as a non-ok result, not an exception.
        val blocked = context.getDatabasePath("blocked.db")
        blocked.parentFile?.mkdirs()
        assertTrue("setup: directory must squat on the db path", blocked.mkdir())
        val (maintenance, _) = maintenanceFor("blocked.db")

        val result = maintenance.quickCheck()

        assertFalse(result.ok)
        assertEquals(1, result.problems.size)
        assertTrue(result.problems[0].startsWith("quick_check failed:"))
        blocked.delete()
    }

    // ---- checkpoint ------------------------------------------------------------------

    @Test
    fun checkpointTruncatesTheWalFile() {
        // Regression: wal_checkpoint returns a result row, so issuing it via execSQL threw
        // (and was silently swallowed), leaving WAL frames out of the file backups copy.
        val (maintenance, helper) = maintenanceFor("checkpoint.db")
        val db = helper.writableDatabase
        val sessionId = insertSession(db)
        insertBulkTelemetry(db, sessionId, 50, 1000L)
        val walFile = context.getDatabasePath("checkpoint.db-wal")
        assertTrue("setup: writes should have produced WAL frames", walFile.length() > 0L)

        maintenance.checkpoint()

        assertEquals("checkpoint(TRUNCATE) must empty the WAL", 0L, walFile.length())
    }

    // ---- pruneRawDataOlderThan ---------------------------------------------------------

    @Test
    fun pruneDeletesAcrossMultipleBatches() {
        // More rows than one DELETE batch: the batched loop must still remove every old row and
        // report the full count.
        val (maintenance, helper) = maintenanceFor("prune-batches.db")
        val db = helper.writableDatabase
        val sessionId = insertSession(db)
        val oldMs = System.currentTimeMillis() - 100L * 86_400_000L
        val rows = ObdStoreMaintenance.PRUNE_DELETE_BATCH * 2 + 7
        insertBulkTelemetry(db, sessionId, rows, oldMs)

        assertEquals(rows, maintenance.pruneRawDataOlderThan(30))

        db.rawQuery("SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY}", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0L, cursor.getLong(0))
        }
    }

    @Test
    fun pruneInvalidatesTripCachesOnlyForAffectedSessions() {
        // Rollup/trip-cache invalidation is scoped to the sessions whose raw rows were pruned;
        // an untouched session keeps its cached rollup instead of being flushed globally.
        val (maintenance, helper) = maintenanceFor("prune-scoped.db")
        val db = helper.writableDatabase
        val oldSession = insertSession(db)
        val recentSession = insertSession(db)
        val oldMs = System.currentTimeMillis() - 100L * 86_400_000L
        insertBulkTelemetry(db, oldSession, 5, oldMs)
        insertBulkTelemetry(db, recentSession, 5, System.currentTimeMillis())
        insertRollup(db, oldSession)
        insertRollup(db, recentSession)

        assertEquals(5, maintenance.pruneRawDataOlderThan(30))

        assertEquals(0L, countRollupsFor(db, oldSession))
        assertEquals(1L, countRollupsFor(db, recentSession))
    }

    // ---- vacuumIfNeeded ------------------------------------------------------------

    @Test
    fun vacuumReclaimsFreedPagesAfterAPrune() {
        // Android-created databases default to auto_vacuum=FULL, where the freelist
        // self-drains. Rebuild this one with auto_vacuum=NONE — the shape a database file
        // restored or merged from elsewhere can arrive in, and the case the gated VACUUM
        // exists for.
        val (maintenance, helper) = maintenanceForNonAutoVacuum("vacuum.db")
        val db = helper.writableDatabase
        val sessionId = insertSession(db)
        val oldMs = System.currentTimeMillis() - 100L * 86_400_000L
        insertBulkTelemetry(db, sessionId, 400, oldMs)

        assertEquals(400, maintenance.pruneRawDataOlderThan(30))
        val freeBefore = longPragma(db, "freelist_count")
        assertTrue("prune should leave many free pages, saw $freeBefore", freeBefore > 16L)
        val pagesBefore = longPragma(db, "page_count")

        assertTrue("vacuum should run above threshold", maintenance.vacuumIfNeeded(16L))

        assertTrue(
            "VACUUM must shrink the database file",
            longPragma(db, "page_count") < pagesBefore,
        )
        assertEquals(0L, longPragma(db, "freelist_count"))
    }

    @Test
    fun vacuumIsSkippedWhenTheFreelistIsBelowThreshold() {
        val (maintenance, helper) = maintenanceFor("vacuum-skip.db")
        val db = helper.writableDatabase
        val pagesBefore = longPragma(db, "page_count")

        assertFalse("fresh database has nothing to reclaim", maintenance.vacuumIfNeeded())

        assertEquals(pagesBefore, longPragma(db, "page_count"))
    }

    @Test
    fun vacuumIsSkippedInsideATransactionWithoutThrowing() {
        // VACUUM cannot run inside a transaction; the guard must skip instead of throwing.
        // Threshold -1 guarantees the freelist gate alone would not have stopped it.
        val (maintenance, helper) = maintenanceFor("vacuum-tx.db")
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            assertFalse(maintenance.vacuumIfNeeded(-1L))
        } finally {
            db.endTransaction()
        }
    }

    @Test
    fun vacuumNeverThrowsWhenTheDatabaseCannotOpen() {
        val blocked = context.getDatabasePath("blocked-vacuum.db")
        blocked.parentFile?.mkdirs()
        assertTrue("setup: directory must squat on the db path", blocked.mkdir())
        val (maintenance, _) = maintenanceFor("blocked-vacuum.db")

        assertFalse(maintenance.vacuumIfNeeded(-1L))
        blocked.delete()
    }

    // ---- runStartupMaintenance -------------------------------------------------------

    @Test
    fun startupMaintenancePrunesAndVacuumsWhenEnoughSpaceWasFreed() {
        val (maintenance, helper) = maintenanceForNonAutoVacuum("startup.db")
        val db = helper.writableDatabase
        val sessionId = insertSession(db)
        val oldMs = System.currentTimeMillis() - 100L * 86_400_000L
        // ~4 KiB of json per row: 512 rows frees well over the 256-page (~1 MiB) threshold.
        insertBulkTelemetry(db, sessionId, 512, oldMs)
        val pagesBefore = longPragma(db, "page_count")
        assertTrue(pagesBefore > ObdStoreMaintenance.VACUUM_FREE_PAGE_THRESHOLD)

        val pruned = maintenance.runStartupMaintenance(30)

        assertEquals(512, pruned)
        assertTrue(
            "default-threshold vacuum should have shrunk the file",
            longPragma(db, "page_count") < pagesBefore,
        )
        assertEquals(0L, longPragma(db, "freelist_count"))
    }

    @Test
    fun startupMaintenanceSkipsTheVacuumWhenLittleWasFreed() {
        val (maintenance, helper) = maintenanceForNonAutoVacuum("startup-small.db")
        val db = helper.writableDatabase
        val sessionId = insertSession(db)
        val oldMs = System.currentTimeMillis() - 100L * 86_400_000L
        insertBulkTelemetry(db, sessionId, 3, oldMs)
        val pagesBefore = longPragma(db, "page_count")

        val pruned = maintenance.runStartupMaintenance(30)

        assertEquals(3, pruned)
        assertEquals("no vacuum below threshold", pagesBefore, longPragma(db, "page_count"))
    }

    @Test
    fun storeFacadeExposesQuickCheckAndStartupMaintenance() {
        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            val sessionId = store.startSession("obd", "00:11", "Adapter")
            val oldMs = System.currentTimeMillis() - 100L * 86_400_000L
            val sample =
                org.json
                    .JSONObject()
                    .put("source", "obd")
                    .put("speedKph", 42)
                    .put("updatedAt", oldMs)
            store.recordTelemetry(sessionId, sample, oldMs)

            assertTrue(store.quickCheck().ok)
            assertEquals(1, store.runStartupMaintenance(30))
        } finally {
            store.close()
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Creates the database file with auto_vacuum=NONE before [VoltTrackerDb] ever opens it,
     * so deleted pages land on the freelist instead of being auto-reclaimed. Android's own
     * SQLite defaults new files to auto_vacuum=FULL, but restored/merged database files can
     * arrive in NONE shape — that is the case the gated VACUUM exists for. Android writes
     * the file header on open (android_metadata), so converting needs the pragma plus a
     * VACUUM on a plain single connection.
     */
    private fun maintenanceForNonAutoVacuum(name: String): Pair<ObdStoreMaintenance, VoltTrackerDb> {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA auto_vacuum=0")
            db.execSQL("VACUUM")
        }
        val pair = maintenanceFor(name)
        pair.second.writableDatabase.rawQuery("PRAGMA auto_vacuum", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("setup: auto_vacuum must be NONE", 0L, cursor.getLong(0))
        }
        return pair
    }

    private fun checkpoint(db: SQLiteDatabase) {
        // wal_checkpoint returns a result row, so it must go through rawQuery.
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    private fun insertRollup(
        db: SQLiteDatabase,
        sessionId: Long,
    ) {
        val values = ContentValues()
        values.put("session_id", sessionId)
        values.put("counted", 1)
        values.put("distance_m", 1000.0)
        values.put("duration_ms", 60_000L)
        values.put("has_route", 1)
        values.put("started_at_ms", 1L)
        values.put("rollup_version", 1)
        db.insertOrThrow(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, null, values)
    }

    private fun countRollupsFor(
        db: SQLiteDatabase,
        sessionId: Long,
    ): Long =
        db
            .rawQuery(
                "SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS} WHERE session_id = ?",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }

    private fun insertSession(db: SQLiteDatabase): Long {
        val values = ContentValues()
        values.put("mode", "obd")
        values.put("started_at_ms", 1L)
        values.put("status", "complete")
        values.put("created_at_ms", 1L)
        return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values)
    }

    private fun insertBulkTelemetry(
        db: SQLiteDatabase,
        sessionId: Long,
        rows: Int,
        capturedAtMs: Long,
    ) {
        val json = "{\"pad\":\"" + "x".repeat(4096) + "\"}"
        db.beginTransaction()
        try {
            val statement =
                db.compileStatement(
                    "INSERT INTO ${VoltTrackerDb.TABLE_TELEMETRY}" +
                        " (session_id, captured_at_ms, json) VALUES (?, ?, ?)",
                )
            statement.use {
                for (i in 0 until rows) {
                    it.clearBindings()
                    it.bindLong(1, sessionId)
                    it.bindLong(2, capturedAtMs + i)
                    it.bindString(3, json)
                    it.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun longPragma(
        db: SQLiteDatabase,
        pragma: String,
    ): Long =
        db.rawQuery("PRAGMA $pragma", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private companion object {
        const val PAGE_SIZE = 4096L
    }
}
