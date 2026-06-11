package com.volttracker.obdpoc.data

import android.content.ContentValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cursor-path coverage for [DiagnosticCodeReport.fromCursor]: a `diagnostic_codes` row whose
 * `last_session_id` is SQL NULL (the merger writes those when the donor session was not imported)
 * must surface as JSON null — not as session id 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticCodeReportDbTest {
    private lateinit var helper: VoltTrackerDb

    @Before
    fun setUp() {
        helper = VoltTrackerDb(RuntimeEnvironment.getApplication(), DB_NAME)
    }

    @After
    fun tearDown() {
        helper.close()
        RuntimeEnvironment.getApplication().deleteDatabase(DB_NAME)
    }

    @Test
    fun nullLastSessionIdSurvivesTheCursorPath() {
        val db = helper.writableDatabase
        val cv = ContentValues()
        cv.put("module_key", "MOD")
        cv.put("dtc", "P0001")
        cv.put("status", "current")
        cv.put("status_label", "Current")
        cv.put("first_seen_ms", 1_000L)
        cv.put("last_seen_ms", 2_000L)
        cv.put("seen_count", 3L)
        cv.putNull("last_session_id")
        cv.put("json", "{}")
        db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv)

        val reports = ObdStoreReports(helper, ObdStoreTrips(helper))
        val latest = reports.diagnosticsSummaryJson(5).getJSONArray("latestDiagnosticCodes")

        assertEquals(1, latest.length())
        val payload = latest.getJSONObject(0)
        assertEquals("P0001", payload.optString("dtc"))
        assertTrue(
            "SQL NULL last_session_id must serialize as JSON null, not 0",
            payload.isNull("lastSessionId"),
        )
    }

    private companion object {
        private const val DB_NAME = "dtc-report-test.db"
    }
}
