package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards [ObdStatementCache] against a stale DB handle (B3): the cached telemetry INSERT statement
 * binds to one native [SQLiteDatabase] handle, so if the DB is recycled (closed and reopened) the
 * cache must recompile against the new handle instead of executing against the dead one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStatementCacheTest {
    private lateinit var helperA: VoltTrackerDb
    private lateinit var helperB: VoltTrackerDb
    private lateinit var cache: ObdStatementCache

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Two distinct helpers / database files => two distinct SQLiteDatabase instances, standing
        // in for a recycled (close + reopen) handle.
        helperA = VoltTrackerDb(context, "stmt-cache-test-a.db")
        helperB = VoltTrackerDb(context, "stmt-cache-test-b.db")
        cache = ObdStatementCache()
    }

    @After
    fun tearDown() {
        cache.close()
        helperA.close()
        helperB.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("stmt-cache-test-a.db")
        context.deleteDatabase("stmt-cache-test-b.db")
    }

    @Test
    fun recompilesAndInsertsWhenADifferentDbInstanceIsPassed() {
        val dbA = helperA.writableDatabase
        val dbB = helperB.writableDatabase
        assertNotEquals("test setup must hand two distinct db instances", dbA, dbB)

        val sessionA = insertSession(dbA)
        val sessionB = insertSession(dbB)

        // First write compiles the statement against dbA.
        val rowA = insertTelemetry(dbA, sessionA)
        assertTrue("insert against the first db should succeed", rowA > 0)

        // Passing a different db instance must transparently recompile against the new handle and
        // insert without throwing (the stale-handle bug would surface here).
        val rowB = insertTelemetry(dbB, sessionB)
        assertTrue("insert against a recycled db handle should recompile and succeed", rowB > 0)

        // The original handle still works — and the cache flips back to it without throwing.
        val rowA2 = insertTelemetry(dbA, sessionA)
        assertTrue("re-using the first db handle should still insert", rowA2 > 0)
    }

    private fun insertTelemetry(
        db: SQLiteDatabase,
        sessionId: Long,
    ): Long =
        db.transaction {
            cache.bindAndInsertTelemetry(db, sessionId, System.currentTimeMillis(), sample())
        }

    private fun insertSession(db: SQLiteDatabase): Long {
        val values =
            ContentValues().apply {
                put("mode", "obd")
                put("started_at_ms", System.currentTimeMillis())
                put("status", "active")
                put("created_at_ms", System.currentTimeMillis())
            }
        return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values)
    }

    private fun sample(): JSONObject =
        JSONObject().apply {
            put("source", "obd")
            put("vehicleState", "driving")
            put("speedKph", 42)
            put("rpm", 1500)
        }
}
