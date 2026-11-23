package com.volttracker.obdpoc.data

import android.content.ContentValues
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/** A damaged live database is renamed aside (never deleted), bounded to a few copies, and announced. */
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
        DatabaseCorruption.setAsideCopies(liveFile).forEach { DatabaseCorruption.discard(context, it) }
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
    fun discardingTheAnnouncedCopyWithdrawsTheNotice() {
        writeFamily(liveFile, "older")
        val older = DatabaseCorruption.setAside(context, liveFile, 1_000L)!!
        writeFamily(liveFile, "newer")
        val newer = DatabaseCorruption.setAside(context, liveFile, 2_000L)!!

        DatabaseCorruption.discard(context, older)
        assertEquals("another copy's notice stays", 2_000L, DatabaseCorruption.pendingNoticeAtMs(context))

        DatabaseCorruption.discard(context, newer)
        assertNull(DatabaseCorruption.pendingNoticeAtMs(context))
        assertTrue(DatabaseCorruption.setAsideCopies(liveFile).isEmpty())
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
    }
}
