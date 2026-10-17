package com.volttracker.obdpoc.data

import android.content.ContentValues
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreSignalHistoryTest {
    private lateinit var helper: VoltTrackerDb
    private lateinit var history: ObdStoreSignalHistory

    @Before fun setUp() {
        helper = VoltTrackerDb(RuntimeEnvironment.getApplication(), "signal-history-test.db")
        history = ObdStoreSignalHistory(helper)
    }

    @After fun tearDown() {
        helper.close()
        RuntimeEnvironment.getApplication().deleteDatabase("signal-history-test.db")
    }

    @Test fun realRecordingsAreIsolatedAndCsvContainsEveryOriginalSampleWithoutGps() {
        val real = session("obd", 1_000)
        val demo = session("demo", 3_000)
        val scan = session("scan", 4_000)
        sample(demo, 3_000, 777.0)
        sample(scan, 4_000, 888.0)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            repeat(1_501) { i -> sample(real, 1_000L + i * 1_000, if (i == 605) 300.0 else 50.0) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        assertEquals(listOf(real), history.sessions().map { it.id })
        val recording = history.read(real)!!
        assertEquals(1_501, recording.session.sampleCount)
        val points = recording.series.getValue("egrCoolerTemp1C")
        assertTrue(points.size <= 600)
        assertEquals(300.0, points.mapNotNull { it.value }.max(), 0.0)
        val csv = StringWriter()
        assertEquals(1_501, history.writeCsv(real, csv))
        assertEquals(1_502, csv.toString().lineSequence().count { it.isNotEmpty() })
        assertNull(history.read(demo))
        assertThrows(IllegalStateException::class.java) { history.writeCsv(demo, StringWriter()) }
    }

    @Test fun corruptOrDeletedHistoryFailsWithoutReturningAnIncompleteExport() {
        val id = session("obd", 1_000)
        helper.writableDatabase.insertOrThrow(
            VoltTrackerDb.TABLE_TELEMETRY,
            null,
            ContentValues().apply {
                put("session_id", id)
                put("captured_at_ms", 1_000L)
                put("json", "broken")
            },
        )
        assertThrows(IllegalStateException::class.java) { history.read(id) }
        assertThrows(IllegalStateException::class.java) { history.writeCsv(id, StringWriter()) }
        helper.writableDatabase.delete(VoltTrackerDb.TABLE_SESSIONS, "_id=?", arrayOf(id.toString()))
        assertNull(history.read(id))
    }

    private fun session(
        mode: String,
        at: Long,
    ): Long =
        helper.writableDatabase.insertOrThrow(
            VoltTrackerDb.TABLE_SESSIONS,
            null,
            ContentValues().apply {
                put("mode", mode)
                put("started_at_ms", at)
                put("created_at_ms", at)
                put("status", "complete")
            },
        )

    private fun sample(
        sessionId: Long,
        at: Long,
        temperature: Double,
    ) {
        helper.writableDatabase.insertOrThrow(
            VoltTrackerDb.TABLE_TELEMETRY,
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("captured_at_ms", at)
                put("json", JSONObject().put("egrCoolerTemp1C", temperature).put("egrStaleMs", 0L).toString())
            },
        )
    }
}
