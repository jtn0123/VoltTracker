package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * A damaged live database is renamed aside (never deleted) once per damaged file, bounded to a few
 * copies that favour the one holding history, announced, and removed by "Clear data".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseCorruptionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val liveFile: File get() = context.getDatabasePath(VoltTrackerDb.DATABASE_NAME)

    @Before
    fun setUp() {
        DatabaseCorruption.dismissNotice(context)
    }

    @After
    fun tearDown() {
        DatabaseCorruption.discardAll(context, liveFile)
        context.deleteDatabase(VoltTrackerDb.DATABASE_NAME)
        context.deleteDatabase(WORKING_NAME)
    }

    @Test
    fun aDamagedHeaderOnTheLiveDatabaseIsSetAsideNotDeleted() {
        seedOneSession(VoltTrackerDb(context))
        val damaged = damageHeader(liveFile)

        val sessions = VoltTrackerDb(context).use { helper -> countSessions(helper) }

        assertEquals("the reopened database starts empty", 0, sessions)
        val copies = DatabaseCorruption.setAsideCopies(liveFile)
        assertEquals("the damaged file must be kept, not deleted", 1, copies.size)
        assertArrayEquals("the kept copy is the damaged file byte for byte", damaged, copies.single().readBytes())
        assertNotNull("the owner must be told", DatabaseCorruption.pendingNoticeAtMs(context))
    }

    @Test
    fun workingCopiesKeepTheDefaultHandler() {
        seedOneSession(VoltTrackerDb(context, WORKING_NAME))
        val working = context.getDatabasePath(WORKING_NAME)
        damageHeader(working)

        VoltTrackerDb(context, WORKING_NAME).use { helper -> countSessions(helper) }

        assertTrue(DatabaseCorruption.setAsideCopies(working).isEmpty())
        assertNull("a working copy is not the owner's history", DatabaseCorruption.pendingNoticeAtMs(context))
    }

    @Test
    fun onlyTheNewestCopiesAreKeptWithTheirWal() {
        for (stamp in listOf(1_000L, 2_000L, 3_000L)) {
            writeFamily(liveFile, "main-$stamp")
            assertNotNull(DatabaseCorruption.setAside(context, liveFile, stamp))
        }

        val kept = DatabaseCorruption.setAsideCopies(liveFile)

        assertEquals(DatabaseCorruption.KEEP_COPIES, kept.size)
        assertEquals(listOf("2000", "3000"), kept.map { it.name.substringAfterLast("-") })
        assertEquals("main-3000", kept.last().readText())
        assertEquals("the WAL travels with its database", "wal", File(kept.last().path + "-wal").readText())
        assertFalse("the oldest copy's WAL goes with it", File(liveFile.path + ".corrupt-1000-wal").exists())
        assertFalse("nothing is left at the live path", liveFile.exists() || File(liveFile.path + "-wal").exists())
        assertEquals(3_000L, DatabaseCorruption.pendingNoticeAtMs(context))
    }

    @Test
    fun corruptionOnAnOpenConnectionSetsTheFileAsideWithItsWal() {
        seedManySessions()
        damageMiddlePages(liveFile)

        VoltTrackerDb(context).use { helper ->
            // A write that stays in the WAL, in a table whose pages are intact.
            insertAdapter(helper, "wal-only")

            assertCorrupt { scanSessions(helper) }

            val copies = DatabaseCorruption.setAsideCopies(liveFile)
            assertEquals("the damaged file must be kept, not deleted", 1, copies.size)
            assertNotNull("the owner must be told", DatabaseCorruption.pendingNoticeAtMs(context))
            assertFalse("nothing is left at the live path", liveFile.exists())
            assertEquals("the kept copy still holds the WAL write", listOf("wal-only"), adapterKeys(copies.single()))

            assertEquals("the same helper carries on with a new empty database", 0, countSessions(helper))
            assertTrue(liveFile.exists())
        }
    }

    @Test
    fun aSecondHelperOnTheMovedFileDoesNotMoveTheNewDatabase() {
        seedManySessions()
        val damaged = damageMiddlePages(liveFile)
        val first = VoltTrackerDb(context)
        val second = VoltTrackerDb(context)
        try {
            // Both open the damaged file before either finds the damage.
            assertTrue(first.writableDatabase.isOpen)
            assertTrue(second.writableDatabase.isOpen)

            assertCorrupt { scanSessions(first) }
            assertEquals("the first helper starts a new empty database", 0, countSessions(first))
            assertCorrupt { scanSessions(second) }

            val copies = DatabaseCorruption.setAsideCopies(liveFile)
            assertEquals("one damaged file, one copy", 1, copies.size)
            assertArrayEquals("the copy is the damaged history", damaged, copies.single().readBytes())
            assertTrue("the new database stays at the live path", liveFile.exists())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun theLargestCopyOutlastsNewerSmallerOnes() {
        writeFamily(liveFile, "history".repeat(1_000))
        DatabaseCorruption.setAside(context, liveFile, 1_000L)
        writeFamily(liveFile, "small")
        DatabaseCorruption.setAside(context, liveFile, 2_000L)
        writeFamily(liveFile, "small")
        DatabaseCorruption.setAside(context, liveFile, 3_000L)

        val kept = DatabaseCorruption.setAsideCopies(liveFile)

        assertEquals(listOf("1000", "3000"), kept.map { it.name.substringAfterLast("-") })
        assertFalse("a pruned copy's WAL goes with it", File(liveFile.path + ".corrupt-2000-wal").exists())
    }

    @Test
    fun clearingAllDataRemovesTheCopiesAndTheNotice() {
        writeFamily(liveFile, "damaged")
        DatabaseCorruption.setAside(context, liveFile, 1_000L)

        ObdLocalStore(context).use { it.clearAllData() }

        assertTrue(DatabaseCorruption.setAsideCopies(liveFile).isEmpty())
        assertFalse(File(liveFile.path + ".corrupt-1000-wal").exists())
        assertNull(DatabaseCorruption.pendingNoticeAtMs(context))
    }

    /** Fills the sessions table across many pages, then checkpoints so it all sits in the main file. */
    private fun seedManySessions() {
        VoltTrackerDb(context).use { helper ->
            val db = helper.writableDatabase
            repeat(SEEDED_SESSIONS) { index ->
                db.execSQL(
                    "INSERT INTO ${VoltTrackerDb.TABLE_SESSIONS} " +
                        "(mode, adapter_name, started_at_ms, status, created_at_ms) VALUES ('obd', ?, ?, 'complete', 1)",
                    arrayOf<Any>("x".repeat(1024), index.toLong()),
                )
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }

    /** Overwrites pages in the middle of [file] with garbage; the header stays intact, so it opens. */
    private fun damageMiddlePages(file: File): ByteArray {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(maxOf(PAGE_SIZE, raf.length() / 2 / PAGE_SIZE * PAGE_SIZE))
            raf.write(ByteArray(DAMAGED_PAGES * PAGE_SIZE.toInt()) { 0x5A })
        }
        return file.readBytes()
    }

    private fun scanSessions(helper: VoltTrackerDb) {
        helper.readableDatabase.rawQuery("SELECT * FROM ${VoltTrackerDb.TABLE_SESSIONS}", null).use { it.count }
    }

    private fun assertCorrupt(block: () -> Unit) {
        try {
            block()
            fail("reading the damaged pages must report corruption")
        } catch (expected: SQLiteDatabaseCorruptException) {
            // The handler has run by the time the caller sees this.
        }
    }

    private fun insertAdapter(
        helper: VoltTrackerDb,
        key: String,
    ) {
        helper.writableDatabase.insertOrThrow(
            VoltTrackerDb.TABLE_ADAPTER_HISTORY,
            null,
            ContentValues().apply {
                put("adapter_key", key)
                put("first_seen_ms", 1_000L)
                put("last_seen_ms", 1_000L)
            },
        )
    }

    /** Reads a set-aside copy over its own connection, as a recovery tool would. */
    private fun adapterKeys(copy: File): List<String> {
        val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE) { _ -> }
        return db.use {
            it.rawQuery("SELECT adapter_key FROM ${VoltTrackerDb.TABLE_ADAPTER_HISTORY}", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
    }

    private fun seedOneSession(helper: VoltTrackerDb) {
        helper.use {
            it.writableDatabase.insertOrThrow(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                ContentValues().apply {
                    put("started_at_ms", 1_000L)
                    put("mode", "obd")
                    put("status", "complete")
                    put("created_at_ms", 1_000L)
                },
            )
        }
    }

    private fun countSessions(helper: VoltTrackerDb): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_SESSIONS}", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** Overwrites the "SQLite format 3" magic so SQLite reports the file as not a database. */
    private fun damageHeader(file: File): ByteArray {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(ByteArray(HEADER_BYTES) { 0x5A })
        }
        return file.readBytes()
    }

    private fun writeFamily(
        dbFile: File,
        content: String,
    ) {
        dbFile.parentFile?.mkdirs()
        dbFile.writeText(content)
        File(dbFile.path + "-wal").writeText("wal")
        File(dbFile.path + "-shm").writeText("shm")
    }

    private companion object {
        const val WORKING_NAME = "working-copy.db"
        const val HEADER_BYTES = 16
        const val SEEDED_SESSIONS = 300
        const val PAGE_SIZE = 4096L
        const val DAMAGED_PAGES = 8
    }
}
